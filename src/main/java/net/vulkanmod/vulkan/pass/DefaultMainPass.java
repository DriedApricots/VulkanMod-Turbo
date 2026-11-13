package net.vulkanmod.vulkan.pass;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.List;
import java.util.function.IntSupplier;
import net.vulkanmod.render.engine.VkGpuDevice;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D.Buffer;

public class DefaultMainPass implements MainPass {
   private Framebuffer mainFramebuffer;
   private RenderPass mainRenderPass;
   private RenderPass auxRenderPass;
   private GpuTexture[] colorAttachmentTextures;
   private GpuTextureView[] colorAttachmentTextureViews;
   IntSupplier imageIdxSupplier;
   private GpuTexture depthAttachmentTexture;
   private GpuTextureView depthAttachmentTextureView;

   public static DefaultMainPass create() {
      return new DefaultMainPass();
   }

   DefaultMainPass() {
      this.createResources();
   }

   private void createResources() {
      if (this.mainFramebuffer != null) {
         if (this.mainFramebuffer != Renderer.getInstance().getSwapChain()) {
            // VulkanBoost: the off-screen stand-in (minimized window) may still be referenced by commands of this
            // frame (textures fetched before the frame started); free it once this frame slot has completed
            Framebuffer old = this.mainFramebuffer;
            net.vulkanmod.vulkan.memory.MemoryManager.getInstance().addFrameOp(() -> old.cleanUp(true));
         }

         this.mainRenderPass.cleanUp();
         this.auxRenderPass.cleanUp();
      }

      Framebuffer framebuffer;
      if (Renderer.getInstance().getSwapChain().hasImages() && !Renderer.isForceOffscreen()) {
         framebuffer = Renderer.getInstance().getSwapChain();
      } else {
         // VulkanBoost: no swapchain images (minimized window). The off-screen stand-in must have the size MainTarget
         // still reports (last non-zero framebuffer size): mods such as Skyboxify build render areas from
         // RenderTarget.width/height, and a 10x10 stand-in made createRenderPass throw "render area out of bounds".
         int w = 10, h = 10;
         com.mojang.blaze3d.platform.Window window = net.minecraft.client.Minecraft.getInstance() != null
            ? net.minecraft.client.Minecraft.getInstance().getWindow() : null;
         if (window != null && window.getWidth() > 0 && window.getHeight() > 0) {
            w = window.getWidth();
            h = window.getHeight();
         }
         framebuffer = Framebuffer.builder(w, h, 1, true).build();
      }

      this.mainFramebuffer = framebuffer;
      this.createRenderPasses();
      this.createAttachmentTextures();
   }

   private void createRenderPasses() {
      RenderPass.Builder builder = RenderPass.builder(this.mainFramebuffer);
      builder.getColorAttachmentInfo().setFinalLayout(2);
      builder.getColorAttachmentInfo().setOps(2, 0);
      builder.getDepthAttachmentInfo().setOps(2, 0);
      this.mainRenderPass = builder.build();
      builder = RenderPass.builder(this.mainFramebuffer);
      builder.getColorAttachmentInfo().setOps(0, 0);
      builder.getDepthAttachmentInfo().setOps(0, 0);
      builder.getColorAttachmentInfo().setFinalLayout(2);
      this.auxRenderPass = builder.build();
   }

   @Override
   public void begin(VkCommandBuffer commandBuffer, MemoryStack stack) {
      Framebuffer framebuffer = this.mainFramebuffer;
      VulkanImage colorAttachment = framebuffer.getColorAttachment();
      if (framebuffer == Renderer.getInstance().getSwapChain() && colorAttachment.getCurrentLayout() != 2) {
         // VulkanBoost: the transition out of PRESENT_SRC / UNDEFINED must wait for the image-available semaphore,
         // which the frame submit waits on at COLOR_ATTACHMENT_OUTPUT. The generic path used BOTTOM_OF_PIPE as source
         // stage, which in the first scope means "no stage": the transition (and the frame's first draws) could run
         // while the presentation engine was still reading the image. Invisible on fast GPUs; on slow ones (GTX 750 Ti,
         // iGPUs) it showed as flickering, half-old frames and a jerky camera/GUI.
         VulkanImage.transitionLayout(stack, commandBuffer, colorAttachment, colorAttachment.getCurrentLayout(), 2,
            1024, 0, 1024, 256 | 128); // COLOR_ATTACHMENT_OUTPUT -> COLOR_ATTACHMENT_OUTPUT, write|read
      } else {
         colorAttachment.transitionImageLayout(stack, commandBuffer, 2);
      }

      VulkanImage depthAttachment = framebuffer.getDepthAttachment();
      if (depthAttachment != null && depthAttachment.getCurrentLayout() == 3) {
         // VulkanBoost: one depth image is shared by all frames in flight and the layout never changes, so no barrier
         // was recorded between frames: the next frame's depth clear / tests could overlap the previous frame's depth
         // tests on a slow GPU (geometry flashing through). Order them explicitly.
         VulkanImage.transitionLayout(stack, commandBuffer, depthAttachment, 3, 3,
            256 | 512, 1024, 256 | 512, 512 | 1024); // EARLY|LATE_FRAGMENT_TESTS, DEPTH_WRITE -> DEPTH_READ|WRITE
      }

      Renderer.getInstance().beginRenderPass(this.mainRenderPass, framebuffer);
      Renderer.setViewport(0, 0, framebuffer.getWidth(), framebuffer.getHeight(), stack);
      Buffer pScissor = framebuffer.scissor(stack);
      VK10.vkCmdSetScissor(commandBuffer, 0, pScissor);
   }

   @Override
   public void end(VkCommandBuffer commandBuffer) {
      Renderer.getInstance().endRenderPass(commandBuffer);
      if (this.mainFramebuffer == Renderer.getInstance().getSwapChain()) {
         MemoryStack stack = MemoryStack.stackPush();

         try {
            this.mainFramebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, 1000001002);
         } catch (Throwable var6) {
            if (stack != null) {
               try {
                  stack.close();
               } catch (Throwable var5) {
                  var6.addSuppressed(var5);
               }
            }

            throw var6;
         }

         if (stack != null) {
            stack.close();
         }
      }

      int result = VK10.vkEndCommandBuffer(commandBuffer);
      if (result != 0) {
         throw new RuntimeException("Failed to record command buffer:" + result);
      }
   }

   @Override
   public void cleanUp() {
      this.mainRenderPass.cleanUp();
      this.auxRenderPass.cleanUp();
   }

   @Override
   public void onResize() {
      this.createResources();
   }

   @Override
   public void rebindMainTarget() {
      Renderer.getInstance().ensureFrameStarted(); // may recreate mainFramebuffer / render passes (swapchain resize)
      VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
      RenderPass boundRenderPass = Renderer.getInstance().getBoundRenderPass();
      if (boundRenderPass != this.mainRenderPass && boundRenderPass != this.auxRenderPass) {
         Renderer.getInstance().endRenderPass(commandBuffer);
         Renderer.getInstance().beginRenderPass(this.auxRenderPass, this.mainFramebuffer);
      }
   }

   @Override
   public void bindAsTexture() {
      Renderer.getInstance().ensureFrameStarted();
      VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
      RenderPass boundRenderPass = Renderer.getInstance().getBoundRenderPass();
      if (boundRenderPass == this.mainRenderPass || boundRenderPass == this.auxRenderPass) {
         Renderer.getInstance().endRenderPass(commandBuffer);
      }

      MemoryStack stack = MemoryStack.stackPush();

      try {
         this.mainFramebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, 5);
      } catch (Throwable var7) {
         if (stack != null) {
            try {
               stack.close();
            } catch (Throwable var6) {
               var7.addSuppressed(var6);
            }
         }

         throw var7;
      }

      if (stack != null) {
         stack.close();
      }

      VTextureSelector.bindTexture(this.mainFramebuffer.getColorAttachment());
   }

   @Override
   public Framebuffer getMainFramebuffer() {
      return this.mainFramebuffer;
   }

   @Override
   public GpuTexture getColorAttachment() {
      return this.colorAttachmentTextures[this.imageIdxSupplier.getAsInt()];
   }

   @Override
   public GpuTextureView getColorAttachmentView() {
      return this.colorAttachmentTextureViews[this.imageIdxSupplier.getAsInt()];
   }

   @Override
   public GpuTexture getDepthAttachment() {
      return this.depthAttachmentTexture;
   }

   @Override
   public GpuTextureView getDepthAttachmentView() {   // 26.2: SkyRenderer etc. pass color and depth views together; both must follow the swapchain size
      return this.depthAttachmentTextureView;
   }

   private void createAttachmentTextures() {
      VkGpuDevice device = (VkGpuDevice)RenderSystem.getDevice().backend;
      SwapChain swapChain = Renderer.getInstance().getSwapChain();
      if (this.mainFramebuffer == swapChain) {
         List<VulkanImage> swapChainImages = swapChain.getImages();
         int imageCount = swapChainImages.size();
         this.colorAttachmentTextures = new GpuTexture[imageCount];
         this.colorAttachmentTextureViews = new GpuTextureView[imageCount];

         for (int i = 0; i < imageCount; i++) {
            VkGpuTexture attachmentTexture = device.gpuTextureFromVulkanImage(swapChainImages.get(i));
            GpuTextureView attachmentTextureView = device.createTextureView(attachmentTexture);
            this.colorAttachmentTextures[i] = attachmentTexture;
            this.colorAttachmentTextureViews[i] = attachmentTextureView;
         }

         this.imageIdxSupplier = Renderer::getCurrentImage;
      } else {
         this.colorAttachmentTextures = new GpuTexture[1];
         this.colorAttachmentTextureViews = new GpuTextureView[1];
         VkGpuTexture attachmentTexture = device.gpuTextureFromVulkanImage(this.mainFramebuffer.getColorAttachment());
         GpuTextureView attachmentTextureView = device.createTextureView(attachmentTexture);
         this.colorAttachmentTextures[0] = attachmentTexture;
         this.colorAttachmentTextureViews[0] = attachmentTextureView;
         this.imageIdxSupplier = () -> 0;
      }

      this.depthAttachmentTexture = device.gpuTextureFromVulkanImage(this.mainFramebuffer.getDepthAttachment());
      this.depthAttachmentTextureView = device.createTextureView(this.depthAttachmentTexture);
   }
}
