package net.vulkanmod.render.engine;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.opengl.Uniform;
import com.mojang.blaze3d.opengl.Uniform.Ubo;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BindGroupLayout.UniformDescription;
import com.mojang.blaze3d.pipeline.BlendEquation;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.TransientMemory;
import java.util.Optional;
import org.joml.Vector4fc;
import com.mojang.blaze3d.systems.RenderPass.Draw;
import com.mojang.blaze3d.systems.RenderPass.UniformUploader;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ARGB;
import net.vulkanmod.gl.VkGlFramebuffer;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.interfaces.shader.ExtendedRenderPipeline;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Synchronization;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.memory.buffer.StagingBuffer;
import net.vulkanmod.vulkan.memory.buffer.index.AutoIndexBuffer;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.queue.GraphicsQueue;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.texture.ImageUtil;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier.Buffer;
import org.slf4j.Logger;

public class VkCommandEncoder implements CommandEncoderBackend {
   private static final Logger LOGGER = LogUtils.getLogger();
   private final VkGpuDevice device;
   @Nullable
   private RenderPipeline lastPipeline;

   /**
    * VulkanBoost: the real screen target is VulkanMod's main pass attachment. Mods like Xaero's Minimap temporarily make
    * their own framebuffer the game's "main render target"; comparing with mainRenderTarget() then sent their offscreen
    * drawing to the screen.
    */
   private static boolean isScreen(GpuTexture texture) {
      return texture == Renderer.getInstance().getMainPass().getColorAttachment();
   }
   private boolean inRenderPass;
   @Nullable
   private EGlProgram lastProgram;
   private int framebufferId = VkGlFramebuffer.genFramebufferId();

   private final VkTransientMemory transientMemory;
   private boolean warnedDepthOnly;

   protected VkCommandEncoder(VkGpuDevice glDevice) {
      this.device = glDevice;
      this.transientMemory = new VkTransientMemory(glDevice.debugLabels());
   }

   /* ---------------- 26.2 backend API ---------------- */

   @Override
   public TransientMemory transientMemory() {
      return this.transientMemory;
   }

   public VkTransientMemory getTransientMemory() {
      return this.transientMemory;
   }

   @Override
   public void submit() {
      // VulkanMod submits the frame's command buffer in Renderer.endFrame (VkGpuSurface.present)
   }

   @Override
   public void submitRenderPass() {
      this.finishRenderPass(false);
   }

   @Override
   public void writeTimestamp(GpuQueryPool queryPool, int index) {
      // GPU timestamps not supported yet (see VkGpuDevice.createTimestampQueryPool)
   }

   @Override
   public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
      RenderPassDescriptor.Attachment<Optional<Vector4fc>> color = descriptor.colorAttachments().isEmpty() ? null : descriptor.colorAttachments().getFirst();
      RenderPassDescriptor.Attachment<OptionalDouble> depth = descriptor.depthAttachment();
      GpuTextureView depthView = depth != null ? depth.textureView() : null;
      OptionalDouble depthClear = depth != null && depth.clearValue() != null ? depth.clearValue() : OptionalDouble.empty();
      if (color == null) {
         if (!this.warnedDepthOnly) {
            this.warnedDepthOnly = true;
            LOGGER.warn("Depth-only render passes are not supported yet, pass ignored");
         }
         return new VkRenderPass(this, depthView != null, true);
      }
      Optional<Vector4fc> clearColor = color.clearValue() != null ? color.clearValue() : Optional.empty();
      return this.createRenderPass(descriptor.label(), color.textureView(), clearColor, depthView, depthClear);
   }

   private RenderPassBackend createRenderPass(
      Supplier<String> supplier,
      GpuTextureView colorAttachmentView,
      Optional<Vector4fc> clearColor,
      @Nullable GpuTextureView depthTexture,
      OptionalDouble optionalDouble
   ) {
      if (this.inRenderPass) {
         throw new IllegalStateException("Close the existing render pass before creating a new one!");
      }

      if (optionalDouble.isPresent() && depthTexture == null) {
         LOGGER.warn("Depth clear value was provided but no depth texture is being used");
      }

      if (isScreen(colorAttachmentView.texture())) {
         Renderer.getInstance().getMainPass().rebindMainTarget();
         int j = 0;
         if (clearColor.isPresent()) {
            Vector4fc c = clearColor.get();
            GL11.glClearColor(c.x(), c.y(), c.z(), c.w());
            j |= 16384;
         }

         if (depthTexture != null && optionalDouble.isPresent()) {
            GL11.glClearDepth(optionalDouble.getAsDouble());
            j |= 256;
         }

         if (j != 0) {
            GlStateManager._disableScissorTest();
            GlStateManager._depthMask(true);
            GlStateManager._colorMask(15);
            GlStateManager._clear(j);
         }

         return new VkRenderPass(this, depthTexture != null, true);
      } else {
         if (colorAttachmentView.isClosed()) {
            throw new IllegalStateException("Color texture is closed");
         }

         if (depthTexture != null && depthTexture.isClosed()) {
            throw new IllegalStateException("Depth texture is closed");
         }

         this.inRenderPass = true;
         GpuTexture depthTexture1 = depthTexture != null ? depthTexture.texture() : null;
         VkFbo fbo = ((VkTextureView)colorAttachmentView).getFbo(depthTexture1);
         fbo.bind();
         int j = 0;
         if (clearColor.isPresent()) {
            Vector4fc c = clearColor.get();
            GL11.glClearColor(c.x(), c.y(), c.z(), c.w());
            j |= 16384;
         }

         if (depthTexture != null && optionalDouble.isPresent()) {
            GL11.glClearDepth(optionalDouble.getAsDouble());
            j |= 256;
         }

         if (j != 0) {
            GlStateManager._disableScissorTest();
            GlStateManager._depthMask(true);
            GlStateManager._colorMask(15);
            GlStateManager._clear(j);
         }

         GlStateManager._viewport(0, 0, colorAttachmentView.getWidth(0), colorAttachmentView.getHeight(0));
         this.lastPipeline = null;
         return new VkRenderPass(this, depthTexture != null, true);
      }
   }

   public boolean isInRenderPass() {
      return this.inRenderPass;
   }

   private static int argb(Vector4fc c) {
      return ARGB.colorFromFloat(c.w(), c.x(), c.y(), c.z());
   }

   @Override
   public void clearColorTexture(GpuTexture colorAttachment, Vector4fc clearColor) {
      this.clearColorTextureInt(colorAttachment, argb(clearColor));
   }

   @Override
   public void clearColorAndDepthTextures(GpuTexture colorAttachment, Vector4fc clearColor, GpuTexture depthAttachment, double clearDepth) {
      this.clearColorAndDepthTexturesInt(colorAttachment, argb(clearColor), depthAttachment, clearDepth);
   }

   @Override
   public void clearColorAndDepthTextures(
      GpuTexture colorAttachment, Vector4fc clearColor, GpuTexture depthAttachment, double clearDepth, int x0, int y0, int width, int height
   ) {
      this.clearColorAndDepthTexturesInt(colorAttachment, argb(clearColor), depthAttachment, clearDepth, x0, y0, width, height);
   }

   private void clearColorTextureInt(GpuTexture colorAttachment, int clearColor) {
      if (this.inRenderPass) {
         throw new IllegalStateException("Close the existing render pass before creating a new one!");
      }

      if (Renderer.isRecording()) {
         if (isScreen(colorAttachment)) {
            Renderer.getInstance().getMainPass().rebindMainTarget();
            VRenderSystem.setClearColor(ARGB.redFloat(clearColor), ARGB.greenFloat(clearColor), ARGB.blueFloat(clearColor), ARGB.alphaFloat(clearColor));
            Renderer.clearAttachments(16384);
         } else {
            VkGpuTexture vkGpuTexture = (VkGpuTexture)colorAttachment;
            VkGlFramebuffer.bindFramebuffer(36160, this.framebufferId);
            VkGlFramebuffer.framebufferTexture2D(36160, 36064, 3553, vkGpuTexture.glId(), 0);
            VkGlFramebuffer.beginRendering(VkGlFramebuffer.getFramebuffer(this.framebufferId));
            VRenderSystem.setClearColor(ARGB.redFloat(clearColor), ARGB.greenFloat(clearColor), ARGB.blueFloat(clearColor), ARGB.alphaFloat(clearColor));
            Renderer.clearAttachments(16384);
            Renderer.getInstance().endRenderPass();
            VkFbo fbo = ((VkGpuTexture)colorAttachment).getFbo(null);
            ((VkGpuTexture)colorAttachment).setClearColor(clearColor);
            Framebuffer boundFramebuffer = Renderer.getInstance().getBoundFramebuffer();
            if (boundFramebuffer != null && boundFramebuffer.getColorAttachment() == ((VkGpuTexture)colorAttachment).getVulkanImage()) {
               fbo.clearAttachments();
            }
         }
      } else {
         GraphicsQueue graphicsQueue = DeviceManager.getGraphicsQueue();
         CommandPool.CommandBuffer commandBuffer = graphicsQueue.getCommandBuffer();
         VkGpuTexture vkGpuTexture = (VkGpuTexture)colorAttachment;
         VkGlFramebuffer glFramebuffer = VkGlFramebuffer.getFramebuffer(this.framebufferId);
         glFramebuffer.setAttachmentTexture(36064, vkGpuTexture.glId());
         glFramebuffer.create();
         Framebuffer framebuffer = glFramebuffer.getFramebuffer();
         RenderPass renderPass = glFramebuffer.getRenderPass();
         MemoryStack stack = MemoryStack.stackPush();

         try {
            framebuffer.beginRenderPass(commandBuffer.handle, renderPass, stack);
         } catch (Throwable var13) {
            if (stack != null) {
               try {
                  stack.close();
               } catch (Throwable var12) {
                  var13.addSuppressed(var12);
               }
            }

            throw var13;
         }

         if (stack != null) {
            stack.close();
         }

         VRenderSystem.setClearColor(ARGB.redFloat(clearColor), ARGB.greenFloat(clearColor), ARGB.blueFloat(clearColor), ARGB.alphaFloat(clearColor));
         Renderer.clearAttachments(commandBuffer.handle, 16384, 0, 0, framebuffer.getWidth(), framebuffer.getHeight());
         renderPass.endRenderPass(commandBuffer.handle);
         long fence = graphicsQueue.submitCommands(commandBuffer);
         Synchronization.waitFence(fence);
      }
   }

   private void clearColorAndDepthTexturesInt(GpuTexture colorAttachment, int clearColor, GpuTexture depthAttachment, double clearDepth) {
      if (this.inRenderPass) {
         throw new IllegalStateException("Close the existing render pass before creating a new one!");
      }

      if (isScreen(colorAttachment)) {
         Renderer.getInstance().getMainPass().rebindMainTarget();
         VRenderSystem.clearDepth(clearDepth);
         VRenderSystem.setClearColor(ARGB.redFloat(clearColor), ARGB.greenFloat(clearColor), ARGB.blueFloat(clearColor), ARGB.alphaFloat(clearColor));
         Renderer.clearAttachments(16640);
      } else {
         VkFbo fbo = ((VkGpuTexture)colorAttachment).getFbo(depthAttachment);
         ((VkGpuTexture)colorAttachment).setClearColor(clearColor);
         ((VkGpuTexture)depthAttachment).setDepthClearValue((float)clearDepth);
         Framebuffer boundFramebuffer = Renderer.getInstance().getBoundFramebuffer();
         if (boundFramebuffer != null
            && boundFramebuffer.getColorAttachment() == ((VkGpuTexture)colorAttachment).getVulkanImage()
            && boundFramebuffer.getDepthAttachment() == ((VkGpuTexture)depthAttachment).getVulkanImage()) {
            fbo.clearAttachments();
         }
      }
   }

   private void clearColorAndDepthTexturesInt(
      GpuTexture colorAttachment, int clearColor, GpuTexture depthAttachment, double clearDepth, int x0, int y0, int width, int height
   ) {
      if (this.inRenderPass) {
         throw new IllegalStateException("Close the existing render pass before creating a new one!");
      }

      VRenderSystem.clearDepth(clearDepth);
      VRenderSystem.setClearColor(ARGB.redFloat(clearColor), ARGB.greenFloat(clearColor), ARGB.blueFloat(clearColor), ARGB.alphaFloat(clearColor));
      int framebufferHeight = colorAttachment.getHeight(0);
      y0 = framebufferHeight - height - y0;
      Framebuffer boundFramebuffer = Renderer.getInstance().getBoundFramebuffer();
      if (boundFramebuffer != null
         && boundFramebuffer.getColorAttachment() == ((VkGpuTexture)colorAttachment).getVulkanImage()
         && boundFramebuffer.getDepthAttachment() == ((VkGpuTexture)depthAttachment).getVulkanImage()) {
         Renderer.clearAttachments(16640, x0, y0, width, height);
      } else {
         VkGpuTexture gpuTexture = (VkGpuTexture)colorAttachment;
         gpuTexture.getFbo(depthAttachment).bind();
         Renderer.clearAttachments(16640, x0, y0, width, height);
      }
   }

   public void clearDepthTexture(GpuTexture depthAttachment, double clearDepth) {
      if (this.inRenderPass) {
         throw new IllegalStateException("Close the existing render pass before creating a new one!");
      }

      Framebuffer boundFramebuffer = Renderer.getInstance().getBoundFramebuffer();
      if (boundFramebuffer != null && boundFramebuffer.getDepthAttachment() == ((VkGpuTexture)depthAttachment).getVulkanImage()) {
         VRenderSystem.clearDepth(clearDepth);
         Renderer.clearAttachments(256);
      } else {
         ((VkGpuTexture)depthAttachment).setDepthClearValue((float)clearDepth);
      }
   }

   public void writeToBuffer(GpuBufferSlice gpuBufferSlice, ByteBuffer byteBuffer) {
      if (this.inRenderPass) {
         throw new IllegalStateException("Close the existing render pass before performing additional commands");
      }

      VkGpuBuffer vkGpuBuffer = (VkGpuBuffer)gpuBufferSlice.buffer();
      if (vkGpuBuffer.closed) {
         throw new IllegalStateException("Buffer already closed");
      }

      int size = byteBuffer.remaining();
      if (size + gpuBufferSlice.offset() > vkGpuBuffer.size()) {
         throw new IllegalArgumentException(
            "Cannot write more data than this buffer can hold (attempting to write "
               + size
               + " bytes at offset "
               + gpuBufferSlice.offset()
               + " to "
               + gpuBufferSlice.length()
               + " slice size)"
         );
      }

      long dstOffset = gpuBufferSlice.offset();
      CommandPool.CommandBuffer commandBuffer = Renderer.getInstance().getTransferCb();
      StagingBuffer stagingBuffer = Vulkan.getStagingBuffer();
      stagingBuffer.copyBuffer(size, byteBuffer);
      long srcOffset = stagingBuffer.getOffset();
      MemoryStack stack = MemoryStack.stackPush();

      try {
         if (!commandBuffer.isRecording()) {
            commandBuffer.begin(stack);
         }

         if (vkGpuBuffer.offset == 0 && !vkGpuBuffer.buffer.type.mappable() && vkGpuBuffer.usedByPendingFrame(dstOffset, size)) {
            vkGpuBuffer.orphanForWrite(commandBuffer.handle, stack, dstOffset, size); // VulkanBoost: keep GL write/draw ordering
         }

         Buffer barrier = VkMemoryBarrier.calloc(1, stack);
         barrier.sType$Default();
         org.lwjgl.vulkan.VkBufferMemoryBarrier.Buffer bufferMemoryBarriers = VkBufferMemoryBarrier.calloc(1, stack);
         VkBufferMemoryBarrier bufferMemoryBarrier = (VkBufferMemoryBarrier)bufferMemoryBarriers.get(0);
         bufferMemoryBarrier.sType$Default();
         bufferMemoryBarrier.buffer(vkGpuBuffer.buffer.getId());
         bufferMemoryBarrier.srcAccessMask(4096);
         bufferMemoryBarrier.dstAccessMask(4096);
         bufferMemoryBarrier.size(-1L);
         VK10.vkCmdPipelineBarrier(commandBuffer.handle, 4096, 4096, 0, barrier, bufferMemoryBarriers, null);
         org.lwjgl.vulkan.VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack);
         copyRegion.size(size);
         copyRegion.srcOffset(srcOffset);
         copyRegion.dstOffset(dstOffset);
         VK10.vkCmdCopyBuffer(commandBuffer.handle, stagingBuffer.getId(), vkGpuBuffer.buffer.getId(), copyRegion);
      } catch (Throwable var17) {
         if (stack != null) {
            try {
               stack.close();
            } catch (Throwable var16) {
               var17.addSuppressed(var16);
            }
         }

         throw var17;
      }

      if (stack != null) {
         stack.close();
      }
   }

   public void copyToBuffer(GpuBufferSlice gpuBufferSlice, GpuBufferSlice gpuBufferSlice2) {
      if (this.inRenderPass) {
         throw new IllegalStateException("Close the existing render pass before performing additional commands");
      } else {
         VkGpuBuffer vkGpuBuffer = (VkGpuBuffer)gpuBufferSlice.buffer();
         if (vkGpuBuffer.closed) {
            throw new IllegalStateException("Source buffer already closed");
         } else if ((vkGpuBuffer.usage() & 16) == 0) {   // 26.2: source of a copy needs USAGE_COPY_SRC
            throw new IllegalStateException("Source buffer needs USAGE_COPY_SRC to be a source for a copy");
         } else {
            VkGpuBuffer vkGpuBuffer2 = (VkGpuBuffer)gpuBufferSlice2.buffer();
            if (vkGpuBuffer2.closed) {
               throw new IllegalStateException("Target buffer already closed");
            } else if ((vkGpuBuffer2.usage() & 8) == 0) {
               throw new IllegalStateException("Target buffer needs USAGE_COPY_DST to be a destination for a copy");
            } else if (gpuBufferSlice.length() != gpuBufferSlice2.length()) {
               long var6 = gpuBufferSlice.length();
               throw new IllegalArgumentException(
                  "Cannot copy from slice of size " + var6 + " to slice of size " + gpuBufferSlice2.length() + ", they must be equal"
               );
            } else if (gpuBufferSlice.offset() + gpuBufferSlice.length() > vkGpuBuffer.size()) {
               long var5 = gpuBufferSlice.length();
               throw new IllegalArgumentException(
                  "Cannot copy more data than the source buffer holds (attempting to copy "
                     + var5
                     + " bytes at offset "
                     + gpuBufferSlice.offset()
                     + " from "
                     + vkGpuBuffer.size()
                     + " size buffer)"
               );
            } else if (gpuBufferSlice2.offset() + gpuBufferSlice2.length() > vkGpuBuffer2.size()) {
               long var10002 = gpuBufferSlice2.length();
               throw new IllegalArgumentException(
                  "Cannot copy more data than the target buffer can hold (attempting to copy "
                     + var10002
                     + " bytes at offset "
                     + gpuBufferSlice2.offset()
                     + " to "
                     + vkGpuBuffer2.size()
                     + " size buffer)"
               );
            } else {
               // 26.2: StagedVertexBuffer uploads all immediate geometry (GUI, features) through staging -> GPU buffer copies.
               // Recorded on the transfer command buffer like writeToBuffer(), which is submitted before the frame's draws.
               CommandPool.CommandBuffer commandBuffer = Renderer.getInstance().getTransferCb();
               MemoryStack stack = MemoryStack.stackPush();

               try {
                  if (!commandBuffer.isRecording()) {
                     commandBuffer.begin(stack);
                  }

                  Buffer barrier = VkMemoryBarrier.calloc(1, stack);
                  barrier.sType$Default();
                  VkBufferMemoryBarrier.Buffer bufferMemoryBarriers = VkBufferMemoryBarrier.calloc(1, stack);
                  VkBufferMemoryBarrier bufferMemoryBarrier = bufferMemoryBarriers.get(0);
                  bufferMemoryBarrier.sType$Default();
                  bufferMemoryBarrier.buffer(vkGpuBuffer2.buffer.getId());
                  bufferMemoryBarrier.srcAccessMask(4096);   // TRANSFER_WRITE
                  bufferMemoryBarrier.dstAccessMask(4096);
                  bufferMemoryBarrier.size(-1L);
                  VK10.vkCmdPipelineBarrier(commandBuffer.handle, 4096, 4096, 0, barrier, bufferMemoryBarriers, null);
                  VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack);
                  copyRegion.size(gpuBufferSlice.length());
                  copyRegion.srcOffset(gpuBufferSlice.offset());
                  copyRegion.dstOffset(gpuBufferSlice2.offset());
                  VK10.vkCmdCopyBuffer(commandBuffer.handle, vkGpuBuffer.buffer.getId(), vkGpuBuffer2.buffer.getId(), copyRegion);
               } finally {
                  stack.close();
               }
            }
         }
      }
   }

   // 26.2: single backend entry point; vanilla converts NativeImage writes into a tightly packed ByteBuffer
   @Override
   public void writeToTexture(GpuTexture gpuTexture, ByteBuffer source, int level, int arrayLayer, int xOffset, int yOffset, int width, int height) {
      if (this.inRenderPass) {
         throw new IllegalStateException("Close the existing render pass before performing additional commands");
      }

      if (level >= 0 && level < gpuTexture.getMipLevels()) {
         if (xOffset + width > gpuTexture.getWidth(level) || yOffset + height > gpuTexture.getHeight(level)) {
            throw new IllegalArgumentException(
               "Dest texture ("
                  + width
                  + "x"
                  + height
                  + ") is not large enough to write a rectangle of "
                  + width
                  + "x"
                  + height
                  + " at "
                  + xOffset
                  + "x"
                  + yOffset
                  + " (at mip level "
                  + level
                  + ")"
            );
         }

         if (gpuTexture.isClosed()) {
            throw new IllegalStateException("Destination texture is closed");
         }

         VTextureSelector.setActiveTexture(0);
         VkGlTexture glTexture = VkGlTexture.getTexture(((GlTexture)gpuTexture).glId());
         VTextureSelector.bindTexture(glTexture.getVulkanImage());
         VTextureSelector.uploadSubTexture(level, arrayLayer, width, height, xOffset, yOffset, 0, 0, width, MemoryUtil.memAddress(source));
      } else {
         throw new IllegalArgumentException("Invalid mipLevel " + level + ", must be >= 0 and < " + gpuTexture.getMipLevels());
      }
   }

   @Override
   public void copyBufferToTexture(
      GpuBufferSlice source, int sourceX, int sourceY, int sourceWidth, int sourceHeight,
      GpuTexture destination, int destinationX, int destinationY, int copyWidth, int copyHeight, int mipLevel, int arrayLayer
   ) {
      if (this.inRenderPass) {
         throw new IllegalStateException("Close the existing render pass before performing additional commands");
      }
      // source buffers are host-visible VulkanMod buffers: upload straight from their mapped memory
      long ptr = ((VkGpuBuffer) source.buffer()).getBuffer().getDataPtr() + source.offset();
      VTextureSelector.setActiveTexture(0);
      VkGlTexture glTexture = VkGlTexture.getTexture(((GlTexture) destination).glId());
      VTextureSelector.bindTexture(glTexture.getVulkanImage());
      VTextureSelector.uploadSubTexture(mipLevel, arrayLayer, copyWidth, copyHeight, destinationX, destinationY, sourceY, sourceX, sourceWidth, ptr);
   }

   public void copyTextureToBuffer(GpuTexture gpuTexture, GpuBuffer gpuBuffer, long i, Runnable runnable, int j) {
      if (this.inRenderPass) {
         throw new IllegalStateException("Close the existing render pass before performing additional commands");
      }

      this.copyTextureToBuffer(gpuTexture, gpuBuffer, i, runnable, j, 0, 0, gpuTexture.getWidth(j), gpuTexture.getHeight(j));
   }

   public void copyTextureToBuffer(
      GpuTexture gpuTexture, GpuBuffer gpuBuffer, long dstOffset, Runnable runnable, int mipLevel, int xOffset, int yOffset, int width, int height
   ) {
      VkGpuBuffer vkGpuBuffer = (VkGpuBuffer)gpuBuffer;
      VkGpuTexture vkGpuTexture = (VkGpuTexture)gpuTexture;
      ImageUtil.copyImageToBuffer(vkGpuTexture.getVulkanImage(), vkGpuBuffer.getBuffer(), mipLevel, width, height, xOffset, yOffset, dstOffset, width, height);
      runnable.run();
   }

   public void copyTextureToTexture(GpuTexture gpuTexture, GpuTexture gpuTexture2, int mipLevel, int j, int k, int l, int m, int n, int o) {
      if (this.inRenderPass) {
         throw new IllegalStateException("Close the existing render pass before performing additional commands");
      }

      if (mipLevel >= 0 && mipLevel < gpuTexture.getMipLevels() && mipLevel < gpuTexture2.getMipLevels()) {
         if (j + n > gpuTexture2.getWidth(mipLevel) || k + o > gpuTexture2.getHeight(mipLevel)) {
            throw new IllegalArgumentException(
               "Dest texture ("
                  + gpuTexture2.getWidth(mipLevel)
                  + "x"
                  + gpuTexture2.getHeight(mipLevel)
                  + ") is not large enough to write a rectangle of "
                  + n
                  + "x"
                  + o
                  + " at "
                  + j
                  + "x"
                  + k
            );
         }

         if (l + n > gpuTexture.getWidth(mipLevel) || m + o > gpuTexture.getHeight(mipLevel)) {
            throw new IllegalArgumentException(
               "Source texture ("
                  + gpuTexture.getWidth(mipLevel)
                  + "x"
                  + gpuTexture.getHeight(mipLevel)
                  + ") is not large enough to read a rectangle of "
                  + n
                  + "x"
                  + o
                  + " at "
                  + l
                  + "x"
                  + m
            );
         }

         if (gpuTexture.isClosed()) {
            throw new IllegalStateException("Source texture is closed");
         }

         if (gpuTexture2.isClosed()) {
            throw new IllegalStateException("Destination texture is closed");
         }
      } else {
         throw new IllegalArgumentException(
            "Invalid mipLevel " + mipLevel + ", must be >= 0 and < " + gpuTexture.getMipLevels() + " and < " + gpuTexture2.getMipLevels()
         );
      }
   }

   public GpuFence createFence() {
      if (this.inRenderPass) {
         throw new IllegalStateException("Close the existing render pass before performing additional commands");
      } else {
         // VulkanBoost: a real fence. The stub reported completion immediately, so vanilla recycled vertex buffers
         // (StagedVertexBuffer) and ring buffers (MappableRingBuffer) while the GPU still drew the previous frame from
         // them: models flashing as huge cubes, a shaking hand/GUI - only on GPUs slower than the CPU.
         final long serial = Renderer.fenceSerial();
         return new GpuFence() {
            private boolean done;

            public void close() {
            }

            public boolean awaitCompletion(long timeoutNs) {
               if (!this.done) {
                  this.done = Renderer.awaitSerial(serial, timeoutNs);
               }
               return this.done;
            }
         };
      }
   }

   protected <T> void executeDrawMultiple(
      VkRenderPass renderPass,
      Collection<Draw<T>> collection,
      @Nullable GpuBuffer indexBuffer,
      @Nullable IndexType indexType,
      Collection<String> collection2,
      T object
   ) {
      if (this.trySetup(renderPass)) {
         if (indexType == null) {
            indexType = IndexType.SHORT;
         }

         Pipeline pipeline = ExtendedRenderPipeline.of(renderPass.getPipeline()).getPipeline();

         for (Draw draw : collection) {
            IndexType indexType2 = draw.indexType() == null ? indexType : draw.indexType();
            renderPass.setIndexBuffer(draw.indexBuffer() == null ? indexBuffer : draw.indexBuffer(), indexType2);
            renderPass.setVertexBuffer(draw.slot(), draw.vertexBuffer() == null ? null : draw.vertexBuffer().slice());
            if (VkRenderPass.VALIDATION) {
               if (renderPass.indexBuffer == null) {
                  throw new IllegalStateException("Missing index buffer");
               }

               if (renderPass.indexBuffer.isClosed()) {
                  throw new IllegalStateException("Index buffer has been closed!");
               }

               if (renderPass.vertexBuffers[0] == null) {
                  throw new IllegalStateException("Missing vertex buffer at slot 0");
               }

               if (renderPass.vertexBuffers[0].buffer().isClosed()) {
                  throw new IllegalStateException("Vertex buffer at slot 0 has been closed!");
               }
            }

            BiConsumer<T, UniformUploader> biConsumer = draw.uniformUploaderConsumer();
            if (biConsumer != null) {
               biConsumer.accept(object, (string, gpuBufferSlice) -> {
                  EGlProgram glProgram = ExtendedRenderPipeline.of(renderPass.pipeline).getProgram();
                  if (glProgram.getUniform(string) instanceof Ubo ubo1) {
                     int blockBinding;
                     try {
                        blockBinding = ubo1.blockBinding();
                     } catch (Throwable var7) {
                        throw new MatchException(var7.toString(), var7);
                     }

                     VkGpuBuffer gpuBuffer1 = (VkGpuBuffer)gpuBufferSlice.buffer();
                     UBO ubo = pipeline.getUBO(blockBinding);
                     ubo.setUseGlobalBuffer(false);
                     ubo.getBufferSlice().set(gpuBuffer1.buffer, gpuBufferSlice.offset(), (int)gpuBufferSlice.length());
                  }
               });
               Renderer.getInstance().uploadAndBindUBOs(pipeline);
            }

            this.drawFromBuffers(renderPass, draw.baseVertex(), draw.firstIndex(), draw.indexCount(), indexType2, renderPass.pipeline, 1);
         }
      }
   }

   protected void executeDraw(VkRenderPass renderPass, int vertexOffset, int firstIndex, int vertexCount, @Nullable IndexType indexType, int instanceCount) {
      if (this.trySetup(renderPass)) {
         if (VkRenderPass.VALIDATION) {
            if (indexType != null) {
               if (renderPass.indexBuffer == null) {
                  throw new IllegalStateException("Missing index buffer");
               }

               if (renderPass.indexBuffer.isClosed()) {
                  throw new IllegalStateException("Index buffer has been closed!");
               }
            }

            if (renderPass.vertexBuffers[0] == null) {
               throw new IllegalStateException("Missing vertex buffer at slot 0");
            }

            if (renderPass.vertexBuffers[0].buffer().isClosed()) {
               throw new IllegalStateException("Vertex buffer at slot 0 has been closed!");
            }
         }

         this.drawFromBuffers(renderPass, vertexOffset, firstIndex, vertexCount, indexType, renderPass.pipeline, instanceCount);
      }
   }

   public void drawFromBuffers(
      VkRenderPass renderPass,
      int vertexOffset,
      int firstIndex,
      int vertexCount,
      @Nullable IndexType indexType,
      RenderPipeline renderPipeline,
      int instanceCount
   ) {
      if (instanceCount < 1) {
         instanceCount = 1;
      }

      if (vertexOffset < 0) {
         vertexOffset = 0;
      }

      VkCommandBuffer vkCommandBuffer = Renderer.getCommandBuffer();
      GpuBufferSlice vertexSlice = renderPass.vertexBuffers[0];
      MemoryStack stack = MemoryStack.stackPush();

      try {
         if (vertexSlice != null) {
            // 26.2: vertex buffers are bound as slices, so the slice offset adds to VulkanMod's own buffer offset
            VkGpuBuffer vertexBuffer = (VkGpuBuffer)vertexSlice.buffer();
            vertexBuffer.markUsed(vertexSlice.offset(), vertexSlice.length());
            VK11.vkCmdBindVertexBuffers(vkCommandBuffer, 0, stack.longs(vertexBuffer.buffer.getId()), stack.longs(vertexBuffer.offset + vertexSlice.offset()));
         }

         if (renderPass.indexBuffer != null) {
            VkGpuBuffer indexBuffer = (VkGpuBuffer)renderPass.indexBuffer;
            indexBuffer.markUsed();

            int vkIndexType = switch (indexType) {
               case SHORT -> 0;
               case INT -> 1;
               default -> throw new MatchException(null, null);
            };
            VK11.vkCmdBindIndexBuffer(vkCommandBuffer, indexBuffer.buffer.getId(), indexBuffer.offset, vkIndexType);
            VK11.vkCmdDrawIndexed(vkCommandBuffer, vertexCount, instanceCount, firstIndex, vertexOffset, 0);
         } else {
            AutoIndexBuffer autoIndexBuffer = Renderer.getDrawer().getAutoIndexBuffer(renderPipeline.getPrimitiveTopology(), vertexCount);
            if (autoIndexBuffer != null) {
               int indexCount = autoIndexBuffer.getIndexCount(vertexCount);
               VK11.vkCmdBindIndexBuffer(vkCommandBuffer, autoIndexBuffer.getIndexBuffer().getId(), 0L, autoIndexBuffer.getIndexBuffer().indexType.value);
               VK11.vkCmdDrawIndexed(vkCommandBuffer, indexCount, instanceCount, firstIndex, vertexOffset, 0);
            } else {
               VK11.vkCmdDraw(vkCommandBuffer, vertexCount, instanceCount, vertexOffset, 0);
            }
         }
      } catch (Throwable var14) {
         if (stack != null) {
            try {
               stack.close();
            } catch (Throwable var13) {
               var14.addSuppressed(var13);
            }
         }

         throw var14;
      }

      if (stack != null) {
         stack.close();
      }
   }

   public boolean trySetup(VkRenderPass renderPass) {
      if (VkRenderPass.VALIDATION) {
         if (renderPass.pipeline == null) {
            throw new IllegalStateException("Can't draw without a render pipeline");
         }

         for (UniformDescription uniformDescription : BindGroupLayout.flattenUniforms(renderPass.pipeline.getBindGroupLayouts())) {
            Object object = renderPass.uniforms.get(uniformDescription.name());
            if (object == null && !GlProgram.BUILT_IN_UNIFORMS.contains(uniformDescription.name())) {
               throw new IllegalStateException("Missing uniform " + uniformDescription.name() + " (should be " + uniformDescription.type() + ")");
            }
         }
      }

      this.applyPipelineState(renderPass.pipeline);
      this.setupUniforms(renderPass);
      if (renderPass.isScissorEnabled()) {
         GlStateManager._enableScissorTest();
         GlStateManager._scissorBox(renderPass.getScissorX(), renderPass.getScissorY(), renderPass.getScissorWidth(), renderPass.getScissorHeight());
      } else {
         GlStateManager._disableScissorTest();
      }

      return this.bindPipeline(renderPass.pipeline);
   }

   public void setupUniforms(VkRenderPass renderPass) {
      RenderPipeline renderPipeline = renderPass.pipeline;
      EGlProgram glProgram = ExtendedRenderPipeline.of(renderPass.pipeline).getProgram();
      Pipeline pipeline = ExtendedRenderPipeline.of(renderPass.pipeline).getPipeline();
      if (pipeline.name.contains("item_cutout")) {
         System.nanoTime();
      }

      for (UBO ubo : pipeline.getBuffers()) {
         String uniformName = ubo.name;
         Uniform uniform = glProgram.getUniform(uniformName);
         GpuBufferSlice gpuBufferSlice = renderPass.uniforms.get(uniformName);
         if (gpuBufferSlice == null) {
            ubo.setUseGlobalBuffer(true);
            ubo.setUpdate(true);
         } else {
            VkGpuBuffer gpuBuffer = (VkGpuBuffer)gpuBufferSlice.buffer();
            gpuBuffer.markUsed(gpuBufferSlice.offset(), gpuBufferSlice.length());
            assert ubo != null;
            ubo.setUseGlobalBuffer(false);
            ubo.getBufferSlice().set(gpuBuffer.buffer, gpuBufferSlice.offset(), (int)gpuBufferSlice.length());
         }
      }

      for (ImageDescriptor imageDescriptor : pipeline.getImageDescriptors()) {
         String uniformName = imageDescriptor.name;
         int samplerIndex = imageDescriptor.imageIdx;
         VkRenderPass.TextureViewAndSampler textureSampler = renderPass.samplers.get(uniformName);
         if (textureSampler != null) {
            VkTextureView textureView = textureSampler.view();
            VkGpuTexture gpuTexture = textureView.texture();
            if (!gpuTexture.isClosed()) {
               GlStateManager._activeTexture(33984 + samplerIndex);
               GlStateManager._bindTexture(gpuTexture.id);
               gpuTexture.getVulkanImage().setSampler(textureSampler.sampler().getId());
            }
         }
      }
   }

   public boolean bindPipeline(RenderPipeline renderPipeline) {
      Pipeline pipeline = ExtendedRenderPipeline.of(renderPipeline).getPipeline();
      if (pipeline == null) {
         return false;
      }

      Renderer renderer = Renderer.getInstance();
      renderer.bindGraphicsPipeline((GraphicsPipeline)pipeline);
      renderer.uploadAndBindUBOs(pipeline);
      return true;
   }

   public void applyPipelineState(RenderPipeline renderPipeline) {
      if (this.lastPipeline != renderPipeline) {
         this.lastPipeline = renderPipeline;
         DepthStencilState depthStencilState = renderPipeline.getDepthStencilState();
         if (depthStencilState != null) {
            GlStateManager._enableDepthTest();
            GlStateManager._depthFunc(GlConst.toGl(depthStencilState.depthTest()));
            GlStateManager._depthMask(depthStencilState.writeDepth());
            if (depthStencilState.depthBiasConstant() == 0.0F && depthStencilState.depthBiasScaleFactor() == 0.0F) {
               GlStateManager._disablePolygonOffset();
            } else {
               GlStateManager._polygonOffset(depthStencilState.depthBiasScaleFactor(), depthStencilState.depthBiasConstant());
               GlStateManager._enablePolygonOffset();
            }
         } else {
            GlStateManager._disableDepthTest();
            GlStateManager._depthMask(false);
            GlStateManager._disablePolygonOffset();
         }

         if (renderPipeline.isCull()) {
            GlStateManager._enableCull();
         } else {
            GlStateManager._disableCull();
         }

         // 26.2: blend state is per colour target (index 0) and split into colour/alpha equations
         if (renderPipeline.getColorTargetState().blendFunction().isPresent()) {
            GlStateManager._enableBlend(0);
            BlendFunction blendFunction = (BlendFunction)renderPipeline.getColorTargetState().blendFunction().get();
            BlendEquation color = blendFunction.color();
            BlendEquation alpha = blendFunction.alpha();
            GlStateManager._blendFuncSeparate(
               GlConst.toGl(color.sourceFactor()),
               GlConst.toGl(color.destFactor()),
               GlConst.toGl(alpha.sourceFactor()),
               GlConst.toGl(alpha.destFactor())
            );
         } else {
            GlStateManager._disableBlend(0);
         }

         GlStateManager._polygonMode(1032, GlConst.toGl(renderPipeline.getPolygonMode()));
         GlStateManager._colorMask(renderPipeline.getColorTargetState().writeMask());
         VRenderSystem.setPrimitiveTopologyGL(GlConst.toGl(renderPipeline.getPrimitiveTopology()));
      }
   }

   public void finishRenderPass(boolean forceEnd) {
      if (forceEnd) {
         Renderer.getInstance().endRenderPass();
      }

      this.inRenderPass = false;
   }

   protected VkGpuDevice getDevice() {
      return this.device;
   }
}
