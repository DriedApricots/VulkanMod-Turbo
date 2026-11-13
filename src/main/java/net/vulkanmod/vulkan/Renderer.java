package net.vulkanmod.vulkan;

import com.mojang.blaze3d.opengl.GlStateManager;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.vulkanmod.Initializer;
import net.vulkanmod.gl.VkGlFramebuffer;
import net.vulkanmod.mixin.window.WindowAccessor;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.buffer.UploadManager;
import net.vulkanmod.render.profiling.Profiler;
import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.render.texture.ImageUploadHelper;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.pass.DefaultMainPass;
import net.vulkanmod.vulkan.pass.MainPass;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.queue.TransferQueue;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.shader.Uniforms;
import net.vulkanmod.vulkan.shader.layout.PushConstants;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.util.VkResult;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkClearAttachment;
import org.lwjgl.vulkan.VkClearRect;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkClearAttachment.Buffer;

public class Renderer {
   private static Renderer INSTANCE;
   private static VkDevice device;
   private static boolean swapChainUpdate = false;
   private final Set<Pipeline> usedPipelines = new ObjectOpenHashSet();
   private Pipeline boundPipeline;
   private long boundPipelineHandle;
   private Drawer drawer;
   private SwapChain swapChain;
   private int framesNum;
   private List<VkCommandBuffer> mainCommandBuffers;
   private ArrayList<Long> imageAvailableSemaphores;
   private ArrayList<Long> renderFinishedSemaphores;
   private ArrayList<Long> inFlightFences;
   private List<CommandPool.CommandBuffer> transferCbs;
   private Framebuffer boundFramebuffer;
   private RenderPass boundRenderPass;
   private static int currentFrame = 0;
   private static int imageIndex = 0;
   private static int lastReset = -1;
   private VkCommandBuffer currentCmdBuffer;
   private boolean recordingCmds = false;
   int recursion = 0;
   MainPass mainPass;
   private final List<Runnable> onResizeCallbacks = new ObjectArrayList();

   public static void initRenderer() {
      INSTANCE = new Renderer();
      INSTANCE.init();
   }

   public static Renderer getInstance() {
      return INSTANCE;
   }

   public static Drawer getDrawer() {
      return INSTANCE.drawer;
   }

   public static int getCurrentFrame() {
      return currentFrame;
   }

   public static int getCurrentImage() {
      return imageIndex;
   }

   public Renderer() {
      device = Vulkan.getVkDevice();
      this.framesNum = Initializer.CONFIG.frameQueueSize;
   }

   public static void setLineWidth(float width) {
      if (INSTANCE.boundFramebuffer != null) {
         VK10.vkCmdSetLineWidth(INSTANCE.currentCmdBuffer, width);
      }
   }

   private void init() {
      MemoryManager.createInstance(getFramesNum());
      Vulkan.createStagingBuffers();
      this.swapChain = new SwapChain();
      this.mainPass = DefaultMainPass.create();
      this.drawer = new Drawer();
      this.drawer.createResources(this.framesNum);
      Uniforms.setupDefaultUniforms();
      PipelineManager.init();
      UploadManager.createInstance();
      this.allocateCommandBuffers();
      this.createSyncObjects();
   }

   private void allocateCommandBuffers() {
      if (this.mainCommandBuffers != null) {
         this.mainCommandBuffers.forEach(commandBuffer -> VK10.vkFreeCommandBuffers(device, Vulkan.getCommandPool(), commandBuffer));
      }

      this.mainCommandBuffers = new ArrayList<>(this.framesNum);
      MemoryStack stack = MemoryStack.stackPush();

      try {
         VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack);
         allocInfo.sType(40);
         allocInfo.commandPool(Vulkan.getCommandPool());
         allocInfo.level(0);
         allocInfo.commandBufferCount(this.framesNum);
         PointerBuffer pCommandBuffers = stack.mallocPointer(this.framesNum);
         int vkResult = VK10.vkAllocateCommandBuffers(device, allocInfo, pCommandBuffers);
         if (vkResult != 0) {
            throw new RuntimeException("Failed to allocate command buffers: %s".formatted(VkResult.decode(vkResult)));
         }

         for (int i = 0; i < this.framesNum; i++) {
            this.mainCommandBuffers.add(new VkCommandBuffer(pCommandBuffers.get(i), device));
         }
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

      if (this.transferCbs != null) {
         this.transferCbs.forEach(commandBuffer -> {
            VK10.vkResetCommandBuffer(commandBuffer.handle, 0);
            commandBuffer.reset();
         });
      }

      this.transferCbs = new ArrayList<>(this.framesNum);

      for (int i = 0; i < this.framesNum; i++) {
         this.transferCbs.add(DeviceManager.getTransferQueue().getCommandPool().getCommandBuffer());
      }
   }

   private void createSyncObjects() {
      this.slotSerial = new long[this.framesNum]; // new fences start signalled
      int swapChainImages = this.swapChain.getImagesNum();
      this.renderFinishedSemaphores = new ArrayList<>(swapChainImages);
      this.imageAvailableSemaphores = new ArrayList<>(this.framesNum);
      this.inFlightFences = new ArrayList<>(this.framesNum);
      MemoryStack stack = MemoryStack.stackPush();

      try {
         VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack);
         semaphoreInfo.sType(9);
         VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack);
         fenceInfo.sType(8);
         fenceInfo.flags(1);
         LongBuffer pImageAvailableSemaphore = stack.mallocLong(1);
         LongBuffer pRenderFinishedSemaphore = stack.mallocLong(1);
         LongBuffer pFence = stack.mallocLong(1);

         for (int i = 0; i < this.framesNum; i++) {
            if (VK10.vkCreateSemaphore(device, semaphoreInfo, null, pImageAvailableSemaphore) != 0 || VK10.vkCreateFence(device, fenceInfo, null, pFence) != 0) {
               throw new RuntimeException("Failed to create synchronization objects for the frame: " + i);
            }

            this.imageAvailableSemaphores.add(pImageAvailableSemaphore.get(0));
            this.inFlightFences.add(pFence.get(0));
         }

         for (int i = 0; i < this.swapChain.getImagesNum(); i++) {
            if (VK10.vkCreateSemaphore(device, semaphoreInfo, null, pRenderFinishedSemaphore) != 0) {
               throw new RuntimeException("Failed to create synchronization objects for the image: " + i);
            }

            this.renderFinishedSemaphores.add(pRenderFinishedSemaphore.get(0));
         }
      } catch (Throwable var10) {
         if (stack != null) {
            try {
               stack.close();
            } catch (Throwable var9) {
               var10.addSuppressed(var9);
            }
         }

         throw var10;
      }

      if (stack != null) {
         stack.close();
      }
   }

   public void preInitFrame() {
      Profiler p = Profiler.getMainProfiler();
      p.pop();
      p.round();
      p.push("Frame_ops");
      this.drawer.resetBuffers(currentFrame);
      WorldRenderer.getInstance().uploadSections();
      UploadManager.INSTANCE.submitUploads();
   }

   public void beginFrame() {
      net.vulkanmod.boost.Bench.onFrame();
      net.vulkanmod.boost.HitchLog.onFrame();
      net.vulkanmod.render.engine.VkGpuBuffer.FRAME++;
      net.vulkanmod.boost.TextPrepareCache.onFrame();
      net.vulkanmod.boost.NameTagCache.onFrame();
      net.vulkanmod.boost.NameTagBake.onFrame();
      this.recursion++;
      if (swapChainUpdate && this.recursion <= 1) {
         this.recreateSwapChain();
         swapChainUpdate = false;
      }

      if (this.recursion > 1) {
         this.endFrame();
      }

      Profiler p = Profiler.getMainProfiler();
      p.pop();
      p.push("Frame_fence");
      VK10.vkWaitForFences(device, this.inFlightFences.get(currentFrame), true, -1L);
      this.noteCompleted(currentFrame);
      p.pop();
      p.push("Acquire");
      // VulkanBoost: acquire before anything of this frame is set up, so an out-of-date swapchain can be rebuilt here
      this.acquireSwapChainImage();
      p.pop();
      p.push("Begin_rendering");
      this.submitUploads();
      MemoryManager.getInstance().initFrame(currentFrame);
      this.drawer.setCurrentFrame(currentFrame);
      Vulkan.getStagingBuffers().beginFrame(currentFrame);
      this.preInitFrame();
      this.resetDescriptors();
      this.currentCmdBuffer = this.mainCommandBuffers.get(currentFrame);
      VK10.vkResetCommandBuffer(this.currentCmdBuffer, 0);
      MemoryStack stack = MemoryStack.stackPush();

      try {
         this.beginMainRenderPass(stack);
      } catch (Throwable var8) {
         if (stack != null) {
            try {
               stack.close();
            } catch (Throwable var7) {
               var8.addSuppressed(var7);
            }
         }

         throw var8;
      }

      if (stack != null) {
         stack.close();
      }

      p.pop();
   }

   private void beginMainRenderPass(MemoryStack stack) {
      VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack);
      beginInfo.sType(42);
      beginInfo.flags(1);
      VkCommandBuffer commandBuffer = this.currentCmdBuffer;
      int vkResult = VK10.vkBeginCommandBuffer(commandBuffer, beginInfo);
      if (vkResult != 0) {
         throw new RuntimeException("Failed to begin recording command buffer: %s".formatted(VkResult.decode(vkResult)));
      }

      this.recordingCmds = true;
      this.mainPass.begin(commandBuffer, stack);
      resetDynamicState(commandBuffer);
   }

   public void endFrame() {
      if (this.recordingCmds) {
         if (this.recursion != 0) {
            this.recursion--;
            Profiler p = Profiler.getMainProfiler();
            p.push("End_rendering");
            this.mainPass.end(this.currentCmdBuffer);
            this.submitUploads();
            this.waitFences();
            this.submitFrame();
            this.recordingCmds = false;
            this.boundRenderPass = null;
            this.boundFramebuffer = null;
            p.pop();
            p.push("Post_rendering");
         }
      }
   }

   private void submitFrame() {
      MemoryStack stack = MemoryStack.stackPush();

      label80: {
         try {
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack);
            submitInfo.sType(4);
            int waitSemaphoreCount = Synchronization.INSTANCE.getWaitSemaphoreCount();
            int totalWaitSemaphores = waitSemaphoreCount;
            if (this.swapChain.isAcquired()) {
               totalWaitSemaphores++;
            }

            LongBuffer waitSemaphores = stack.mallocLong(totalWaitSemaphores);
            IntBuffer waitDstStageMask = stack.mallocInt(totalWaitSemaphores);
            Synchronization.INSTANCE.getWaitSemaphores(waitSemaphores);

            for (int i = 0; i < waitSemaphoreCount; i++) {
               waitDstStageMask.put(i, 1);
            }

            if (this.swapChain.isAcquired()) {
               waitSemaphores.put(totalWaitSemaphores - 1, this.imageAvailableSemaphores.get(currentFrame));
               waitDstStageMask.put(totalWaitSemaphores - 1, 1024);
            }

            waitSemaphores.position(0);
            waitSemaphores.limit(totalWaitSemaphores);
            submitInfo.pWaitSemaphores(waitSemaphores);
            submitInfo.waitSemaphoreCount(waitSemaphores.limit());
            submitInfo.pWaitDstStageMask(waitDstStageMask);
            submitInfo.pCommandBuffers(stack.pointers(this.currentCmdBuffer));
            if (this.swapChain.isAcquired()) {
               submitInfo.pSignalSemaphores(stack.longs(this.renderFinishedSemaphores.get(imageIndex)));
            }

            VK10.vkResetFences(device, this.inFlightFences.get(currentFrame));
            int vkResult;
            if ((vkResult = VK10.vkQueueSubmit(DeviceManager.getGraphicsQueue().vkQueue(), submitInfo, this.inFlightFences.get(currentFrame))) != 0) {
               VK10.vkResetFences(device, this.inFlightFences.get(currentFrame));
               throw new RuntimeException("Failed to submit draw command buffer: %s".formatted(VkResult.decode(vkResult)));
            }
            this.noteSubmitted(currentFrame);

            Synchronization.INSTANCE.scheduleCbReset();
            if (this.swapChain.isAcquired()) {
               VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack);
               presentInfo.sType(1000001001);
               presentInfo.pWaitSemaphores(stack.longs(this.renderFinishedSemaphores.get(imageIndex)));
               presentInfo.swapchainCount(1);
               presentInfo.pSwapchains(stack.longs(this.swapChain.getId()));
               presentInfo.pImageIndices(stack.ints(imageIndex));
               vkResult = KHRSwapchain.vkQueuePresentKHR(DeviceManager.getPresentQueue().vkQueue(), presentInfo);
               if (vkResult == -1000001004 || vkResult == 1000001003 || swapChainUpdate) {
                  swapChainUpdate = true;
                  break label80;
               }

               if (vkResult != 0) {
                  throw new RuntimeException("Failed to present rendered frame: %s".formatted(VkResult.decode(vkResult)));
               }
            }

            Vulkan.getStagingBuffers().endFrame(currentFrame);
            currentFrame = (currentFrame + 1) % this.framesNum;
            this.swapChain.setAcquired(false);
         } catch (Throwable var10) {
            if (stack != null) {
               try {
                  stack.close();
               } catch (Throwable var9) {
                  var10.addSuppressed(var9);
               }
            }

            throw var10;
         }

         if (stack != null) {
            stack.close();
         }

         return;
      }

      if (stack != null) {
         stack.close();
      }
   }

   public void flushCmds() {
      if (this.recordingCmds) {
         MemoryStack stack = MemoryStack.stackPush();

         try {
            this.endRenderPass(this.currentCmdBuffer);
            VK10.vkEndCommandBuffer(this.currentCmdBuffer);
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack);
            submitInfo.sType(4);
            submitInfo.pCommandBuffers(stack.pointers(this.currentCmdBuffer));
            int waitSemaphoreCount = Synchronization.INSTANCE.getWaitSemaphoreCount();
            LongBuffer waitSemaphores = stack.mallocLong(waitSemaphoreCount);
            IntBuffer waitDstStageMask = stack.mallocInt(waitSemaphoreCount);
            Synchronization.INSTANCE.getWaitSemaphores(waitSemaphores);

            for (int i = 0; i < waitSemaphoreCount; i++) {
               waitDstStageMask.put(i, 1);
            }

            waitSemaphores.position(0);
            waitSemaphores.limit(waitSemaphoreCount);
            submitInfo.pWaitSemaphores(waitSemaphores);
            submitInfo.waitSemaphoreCount(waitSemaphores.limit());
            submitInfo.pWaitDstStageMask(waitDstStageMask);
            this.submitUploads();
            this.waitFences();
            VK10.vkResetFences(device, this.inFlightFences.get(currentFrame));
            int vkResult;
            if ((vkResult = VK10.vkQueueSubmit(DeviceManager.getGraphicsQueue().vkQueue(), submitInfo, this.inFlightFences.get(currentFrame))) != 0) {
               VK10.vkResetFences(device, this.inFlightFences.get(currentFrame));
               throw new RuntimeException("Failed to submit draw command buffer: %s".formatted(VkResult.decode(vkResult)));
            }
            this.noteSubmitted(currentFrame);

            VK10.vkWaitForFences(device, this.inFlightFences.get(currentFrame), true, -1L);
            this.noteCompleted(currentFrame);
            this.beginMainRenderPass(stack);
         } catch (Throwable var9) {
            if (stack != null) {
               try {
                  stack.close();
               } catch (Throwable var8) {
                  var9.addSuppressed(var8);
               }
            }

            throw var9;
         }

         if (stack != null) {
            stack.close();
         }
      }
   }

   public void submitUploads() {
      CommandPool.CommandBuffer transferCb = this.transferCbs.get(currentFrame);
      if (transferCb.isRecording()) {
         TransferQueue transferQueue = DeviceManager.getTransferQueue();
         MemoryStack stack = MemoryStack.stackPush();

         try {
            transferCb.submitCommands(stack, transferQueue.vkQueue(), true);
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

         Synchronization.INSTANCE.addCommandBuffer(transferCb, true);
         this.transferCbs.set(currentFrame, transferQueue.getCommandPool().getCommandBuffer());
      }

      ImageUploadHelper.INSTANCE.submitCommands();
   }

   public void endRenderPass() {
      this.endRenderPass(this.currentCmdBuffer);
   }

   public void endRenderPass(VkCommandBuffer commandBuffer) {
      if (this.recordingCmds && this.boundFramebuffer != null) {
         this.boundRenderPass.endRenderPass(commandBuffer);
         this.boundRenderPass = null;
         this.boundFramebuffer = null;
         VkGlFramebuffer.resetBoundFramebuffer();
      }
   }

   /** VulkanBoost: true while the main pass renders off-screen because no swapchain image could be acquired. */
   private static boolean forceOffscreen;

   public static boolean isForceOffscreen() {
      return forceOffscreen;
   }

   /**
    * VulkanBoost: acquires the next swapchain image. The old code used the returned index even when the acquire failed
    * (VK_ERROR_OUT_OF_DATE_KHR on resize / fullscreen toggle / minimize): the index was garbage ("Index 512 out of
    * bounds for length 3") and the frame waited on a semaphore that was never signalled (VK_ERROR_DEVICE_LOST).
    * Now an out-of-date swapchain is rebuilt right away and the acquire retried; if that keeps failing (window still
    * being resized) the frame is rendered off-screen and the swapchain is rebuilt on the next frame.
    */
   private void acquireSwapChainImage() {
      this.swapChain.setAcquired(false);
      for (int attempt = 0; attempt < 3; attempt++) {
         if (!this.swapChain.hasImages()) {
            // The swapchain was dropped because the surface was 0x0 (minimized, or for a moment while toggling
            // fullscreen). If the window came back at its previous size no resize event follows, so nothing would
            // ever rebuild it and the game kept rendering off-screen (frozen picture). Rebuild it as soon as the
            // window is visible with a real size again; checked at most every 250 ms.
            if (attempt == 0 && this.windowHasArea() && System.nanoTime() - this.lastSurfaceRetry > 250_000_000L) {
               this.lastSurfaceRetry = System.nanoTime();
               this.recreateSwapChain();
               swapChainUpdate = false;
               VK10.vkWaitForFences(device, this.inFlightFences.get(currentFrame), true, -1L);
               if (this.swapChain.hasImages()) {
                  continue;
               }
            }
            this.setForceOffscreen(false); // minimized: the main pass already uses its off-screen stand-in
            return;
         }

         int vkResult;
         int index;
         try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pImageIndex = stack.ints(-1);
            vkResult = KHRSwapchain.vkAcquireNextImageKHR(device, this.swapChain.getId(), -1L, this.imageAvailableSemaphores.get(currentFrame), 0L, pImageIndex);
            index = pImageIndex.get(0);
         }

         if ((vkResult == VK10.VK_SUCCESS || vkResult == KHRSwapchain.VK_SUBOPTIMAL_KHR) && index >= 0 && index < this.swapChain.getImagesNum()) {
            if (vkResult == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
               if (recreations <= 50) Initializer.LOGGER.info("Swapchain suboptimal on acquire");
               swapChainUpdate = true; // the image is usable; rebuild on the next frame
            }
            imageIndex = index;
            this.swapChain.setAcquired(true);
            this.setForceOffscreen(false);
            return;
         }

         if (vkResult != KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR && vkResult != KHRSwapchain.VK_SUBOPTIMAL_KHR && vkResult != VK10.VK_SUCCESS) {
            throw new RuntimeException("Cannot acquire next swap chain image: %s".formatted(VkResult.decode(vkResult)));
         }

         // out of date: the semaphore was not signalled, nothing of this frame is recorded yet -> rebuild and retry
         this.recreateSwapChain();
         swapChainUpdate = false;
         VK10.vkWaitForFences(device, this.inFlightFences.get(currentFrame), true, -1L);
      }

      Initializer.LOGGER.warn("Could not acquire a swapchain image, rendering this frame off-screen");
      swapChainUpdate = true;
      this.setForceOffscreen(true);
   }

   private long lastSurfaceRetry;

   private boolean windowHasArea() {
      Minecraft mc = Minecraft.getInstance();
      if (mc == null || mc.getWindow() == null) {
         return false;
      }
      long handle = mc.getWindow().handle();
      if (org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(handle, org.lwjgl.glfw.GLFW.GLFW_ICONIFIED) != 0) {
         return false;
      }
      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1);
         org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(handle, w, h);
         return w.get(0) > 0 && h.get(0) > 0;
      }
   }

   private void setForceOffscreen(boolean value) {
      if (forceOffscreen != value) {
         forceOffscreen = value;
         this.mainPass.onResize();
      }
   }

   /**
    * VulkanBoost: starts the frame if it was not started yet. beginFrame may recreate the swapchain and the main pass
    * (window minimized / resized), so callers must call this before reading the main framebuffer or render passes.
    */
   public void ensureFrameStarted() {
      if (!this.recordingCmds) {
         this.beginFrame();
         this.recordingCmds = true;
      }
   }

   public boolean beginRenderPass(RenderPass renderPass, Framebuffer framebuffer) {
      if (!this.recordingCmds) {
         this.beginFrame();
         this.recordingCmds = true;
      }

      if (this.boundFramebuffer != framebuffer) {
         this.endRenderPass(this.currentCmdBuffer);
         MemoryStack stack = MemoryStack.stackPush();

         try {
            framebuffer.beginRenderPass(this.currentCmdBuffer, renderPass, stack);
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

         this.boundFramebuffer = framebuffer;
         this.boundRenderPass = renderPass;
         setViewportState(0, 0, framebuffer.getWidth(), framebuffer.getHeight());
         setScissor(0, 0, framebuffer.getWidth(), framebuffer.getHeight());
      }

      return true;
   }

   public void addUsedPipeline(Pipeline pipeline) {
      this.usedPipelines.add(pipeline);
   }

   public void removeUsedPipeline(Pipeline pipeline) {
      this.usedPipelines.remove(pipeline);
   }

   private void waitFences() {
      Synchronization.INSTANCE.waitFences();
      Vulkan.getStagingBuffer().reset();
   }

   private void resetDescriptors() {
      for (Pipeline pipeline : this.usedPipelines) {
         pipeline.resetDescriptorPool(currentFrame);
      }

      this.usedPipelines.clear();
      this.boundPipeline = null;
      this.boundPipelineHandle = 0L;
   }

   void waitForSwapChain() {
      VK10.vkResetFences(device, this.inFlightFences.get(currentFrame));
      MemoryStack stack = MemoryStack.stackPush();

      try {
         VkSubmitInfo info = VkSubmitInfo.calloc(stack)
            .sType$Default()
            .pWaitSemaphores(stack.longs(this.imageAvailableSemaphores.get(currentFrame)))
            .pWaitDstStageMask(stack.ints(65536));
         VK10.vkQueueSubmit(DeviceManager.getGraphicsQueue().vkQueue(), info, this.inFlightFences.get(currentFrame));
         this.noteSubmitted(currentFrame);
         VK10.vkWaitForFences(device, this.inFlightFences.get(currentFrame), true, -1L);
         this.noteCompleted(currentFrame);
      } catch (Throwable var5) {
         if (stack != null) {
            try {
               stack.close();
            } catch (Throwable var4) {
               var5.addSuppressed(var4);
            }
         }

         throw var5;
      }

      if (stack != null) {
         stack.close();
      }
   }

   private static int recreations;

   // VulkanBoost: GPU progress for GpuFence (vanilla recycles vertex buffers / ring buffers once a fence completes).
   // Every graphics-queue submission gets a serial; slotSerial[i] = serial of the last submission signalling
   // inFlightFences[i]. A fence signal also covers all earlier submissions on the queue.
   /** A texture animation update deferred from Minecraft.runTick (MinecraftMixin), run once the frame has begun. */
   public static boolean pendingTextureTick;

   public static void runPendingTextureTick() {
      if (pendingTextureTick) {
         pendingTextureTick = false;
         Minecraft.getInstance().getTextureManager().tick();
      }
   }
   private static long submitCount, completedSerial;
   private long[] slotSerial = new long[0];

   private void noteSubmitted(int slot) {
      submitCount++;
      if (slot < this.slotSerial.length) {
         this.slotSerial[slot] = submitCount;
      }
   }

   private void noteCompleted(int slot) {
      if (slot < this.slotSerial.length) {
         completedSerial = Math.max(completedSerial, this.slotSerial[slot]);
      }
   }

   /** Serial of the submission that will contain the work recorded so far. */
   public static long fenceSerial() {
      return INSTANCE != null && INSTANCE.recordingCmds ? submitCount + 1 : submitCount;
   }

   /** @return true once every GPU command recorded before fenceSerial() returned serial has completed. */
   public static boolean awaitSerial(long serial, long timeoutNs) {
      if (serial <= completedSerial) {
         return true;
      }
      Renderer r = INSTANCE;
      if (serial > submitCount) { // still being recorded: it can only complete after it is submitted
         if (timeoutNs <= 0L || r == null || !r.recordingCmds) {
            return false;
         }
         r.flushCmds(); // submits and waits
         completedSerial = Math.max(completedSerial, submitCount);
         return true;
      }
      int best = -1;
      for (int i = 0; i < r.slotSerial.length; i++) {
         if (r.slotSerial[i] >= serial && (best < 0 || r.slotSerial[i] < r.slotSerial[best])) {
            best = i;
         }
      }
      if (best < 0) {
         completedSerial = Math.max(completedSerial, serial); // its slot was reused, so it was waited for already
         return true;
      }
      long fence = r.inFlightFences.get(best);
      int res = timeoutNs <= 0L ? VK10.vkGetFenceStatus(device, fence) : VK10.vkWaitForFences(device, fence, true, timeoutNs);
      if (res == VK10.VK_SUCCESS) {
         r.noteCompleted(best);
         return true;
      }
      return false;
   }

   private void recreateSwapChain() {
      if (++recreations <= 50 || recreations % 100 == 0) {
         Initializer.LOGGER.info("Recreating swapchain (#{}), window {}x{}", recreations, Minecraft.getInstance().getWindow().getWidth(), Minecraft.getInstance().getWindow().getHeight());
      }
      this.submitUploads();
      this.waitFences();
      Vulkan.waitIdle();
      completedSerial = submitCount; // everything submitted so far has finished
      this.mainCommandBuffers.forEach(commandBuffer -> VK10.vkResetCommandBuffer(commandBuffer, 0));
      this.recordingCmds = false;
      this.swapChain.recreate();
      this.destroySyncObjects();
      int newFramesNum = Initializer.CONFIG.frameQueueSize;
      if (this.framesNum != newFramesNum) {
         UploadManager.INSTANCE.submitUploads();
         this.framesNum = newFramesNum;
         MemoryManager.getInstance().freeAllBuffers();
         MemoryManager.createInstance(newFramesNum);
         Vulkan.createStagingBuffers();
         this.allocateCommandBuffers();
         Pipeline.recreateDescriptorSets(this.framesNum);
         this.drawer.createResources(this.framesNum);
      }

      this.createSyncObjects();
      this.mainPass.onResize();
      this.onResizeCallbacks.forEach(Runnable::run);
      ((WindowAccessor)(Object)Minecraft.getInstance().getWindow()).getEventHandler().resizeGui();
      currentFrame = 0;
   }

   public void cleanUpResources() {
      WorldRenderer.getInstance().cleanUp();
      this.destroySyncObjects();
      this.drawer.cleanUpResources();
      this.mainPass.cleanUp();
      this.swapChain.cleanUp();
      PipelineManager.destroyPipelines();
      VTextureSelector.getWhiteTexture().free();
   }

   private void destroySyncObjects() {
      for (int i = 0; i < this.framesNum; i++) {
         VK10.vkDestroyFence(device, this.inFlightFences.get(i), null);
         VK10.vkDestroySemaphore(device, this.imageAvailableSemaphores.get(i), null);
      }

      for (int i = 0; i < this.swapChain.getImagesNum(); i++) {
         VK10.vkDestroySemaphore(device, this.renderFinishedSemaphores.get(i), null);
      }
   }

   public void addOnResizeCallback(Runnable runnable) {
      this.onResizeCallbacks.add(runnable);
   }

   public void bindGraphicsPipeline(GraphicsPipeline pipeline) {
      VkCommandBuffer commandBuffer = this.currentCmdBuffer;
      PipelineState currentState = PipelineState.getCurrentPipelineState(this.boundRenderPass);
      long handle = pipeline.getHandle(currentState);
      if (this.boundPipelineHandle != handle) {
         VK10.vkCmdBindPipeline(commandBuffer, 0, handle);
         this.boundPipelineHandle = handle;
         this.boundPipeline = pipeline;
         this.addUsedPipeline(pipeline);
      }
   }

   /** VulkanMod+: a pipeline was bound directly with vkCmdBindPipeline; the next bindGraphicsPipeline must rebind. */
   public void resetBoundPipeline() {
      this.boundPipelineHandle = 0L;
      this.boundPipeline = null;
   }

   public void uploadAndBindUBOs(Pipeline pipeline) {
      VkCommandBuffer commandBuffer = this.currentCmdBuffer;
      pipeline.bindDescriptorSets(commandBuffer, currentFrame);
   }

   public void pushConstants(Pipeline pipeline) {
      VkCommandBuffer commandBuffer = this.currentCmdBuffer;
      PushConstants pushConstants = pipeline.getPushConstants();
      MemoryStack stack = MemoryStack.stackPush();

      try {
         ByteBuffer buffer = stack.malloc(pushConstants.getSize());
         long ptr = MemoryUtil.memAddress0(buffer);
         pushConstants.update(ptr);
         VK10.nvkCmdPushConstants(commandBuffer, pipeline.getLayout(), 1, 0, pushConstants.getSize(), ptr);
      } catch (Throwable var9) {
         if (stack != null) {
            try {
               stack.close();
            } catch (Throwable var8) {
               var9.addSuppressed(var8);
            }
         }

         throw var9;
      }

      if (stack != null) {
         stack.close();
      }
   }

   public Pipeline getBoundPipeline() {
      return this.boundPipeline;
   }

   public void setBoundFramebuffer(Framebuffer framebuffer) {
      this.boundFramebuffer = framebuffer;
   }

   public Framebuffer getBoundFramebuffer() {
      return this.boundFramebuffer;
   }

   public void setBoundRenderPass(RenderPass boundRenderPass) {
      this.boundRenderPass = boundRenderPass;
   }

   public RenderPass getBoundRenderPass() {
      return this.boundRenderPass;
   }

   public void setMainPass(MainPass mainPass) {
      this.mainPass = mainPass;
   }

   public MainPass getMainPass() {
      return this.mainPass;
   }

   public SwapChain getSwapChain() {
      return this.swapChain;
   }

   public CommandPool.CommandBuffer getTransferCb() {
      return this.transferCbs.get(currentFrame);
   }

   private static void resetDynamicState(VkCommandBuffer commandBuffer) {
      VK10.vkCmdSetDepthBias(commandBuffer, 0.0F, 0.0F, 0.0F);
      VK10.vkCmdSetLineWidth(commandBuffer, 1.0F);
   }

   public static void setDepthBias(float constant, float slope) {
      VkCommandBuffer commandBuffer = INSTANCE.currentCmdBuffer;
      VK10.vkCmdSetDepthBias(commandBuffer, constant, 0.0F, slope);
   }

   public static void clearAttachments(int attachments) {
      clearAttachments(INSTANCE.currentCmdBuffer, attachments);
   }

   public static void clearAttachments(VkCommandBuffer commandBuffer, int attachments) {
      Framebuffer framebuffer = getInstance().boundFramebuffer;
      if (framebuffer != null) {
         clearAttachments(commandBuffer, attachments, framebuffer.getWidth(), framebuffer.getHeight());
      }
   }

   public static void clearAttachments(int attachments, int width, int height) {
      clearAttachments(INSTANCE.currentCmdBuffer, attachments, width, height);
   }

   public static void clearAttachments(int attachments, int x, int y, int width, int height) {
      clearAttachments(INSTANCE.currentCmdBuffer, attachments, x, y, width, height);
   }

   public static void clearAttachments(VkCommandBuffer commandBuffer, int attachments, int width, int height) {
      clearAttachments(commandBuffer, attachments, 0, 0, width, height);
   }

   public static void clearAttachments(VkCommandBuffer commandBuffer, int attachments, int x, int y, int width, int height) {
      MemoryStack stack = MemoryStack.stackPush();

      try {
         VkClearValue colorValue = VkClearValue.calloc(stack);
         colorValue.color().float32(VRenderSystem.clearColor);
         VkClearValue depthValue = VkClearValue.calloc(stack);
         depthValue.depthStencil().set(VRenderSystem.clearDepthValue, 0);
         int attachmentsCount = attachments == 16640 ? 2 : 1;
         Buffer pAttachments = VkClearAttachment.malloc(attachmentsCount, stack);
         switch (attachments) {
            case 256: {
               VkClearAttachment clearDepth = (VkClearAttachment)pAttachments.get(0);
               clearDepth.aspectMask(2);
               clearDepth.colorAttachment(0);
               clearDepth.clearValue(depthValue);
               break;
            }
            case 16384: {
               VkClearAttachment clearColor = (VkClearAttachment)pAttachments.get(0);
               clearColor.aspectMask(1);
               clearColor.colorAttachment(0);
               clearColor.clearValue(colorValue);
               break;
            }
            case 16640: {
               VkClearAttachment clearColor = (VkClearAttachment)pAttachments.get(0);
               clearColor.aspectMask(1);
               clearColor.colorAttachment(0);
               clearColor.clearValue(colorValue);
               VkClearAttachment clearDepth = (VkClearAttachment)pAttachments.get(1);
               clearDepth.aspectMask(2);
               clearDepth.colorAttachment(0);
               clearDepth.clearValue(depthValue);
               break;
            }
            default:
               throw new RuntimeException("unexpected value");
         }

         VkRect2D renderArea = VkRect2D.malloc(stack);
         renderArea.offset().set(x, y);
         renderArea.extent().set(width, height);
         org.lwjgl.vulkan.VkClearRect.Buffer pRect = VkClearRect.malloc(1, stack);
         pRect.rect(renderArea);
         pRect.baseArrayLayer(0);
         pRect.layerCount(1);
         VK10.vkCmdClearAttachments(commandBuffer, pAttachments, pRect);
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

   public static void setInvertedViewport(int x, int y, int width, int height) {
      setViewportState(x, y + height, width, -height);
   }

   public static void resetViewport() {
      Framebuffer framebuffer = INSTANCE.getMainPass().getMainFramebuffer();
      if (framebuffer != null) {
         int width = framebuffer.getWidth();
         int height = framebuffer.getHeight();
         if (width > 0 && height > 0) {
            setViewportState(0, 0, width, height);
         }
      }
   }

   public static void setViewportState(int x, int y, int width, int height) {
      GlStateManager._viewport(x, y, width, height);
   }

   public static void setViewport(int x, int y, int width, int height) {
      MemoryStack stack = MemoryStack.stackPush();

      try {
         setViewport(x, y, width, height, stack);
      } catch (Throwable var8) {
         if (stack != null) {
            try {
               stack.close();
            } catch (Throwable var7) {
               var8.addSuppressed(var7);
            }
         }

         throw var8;
      }

      if (stack != null) {
         stack.close();
      }
   }

   public static void setViewport(int x, int y, int width, int height, MemoryStack stack) {
      if (INSTANCE.recordingCmds) {
         org.lwjgl.vulkan.VkViewport.Buffer viewport = VkViewport.malloc(1, stack);
         viewport.x(x);
         viewport.y(height + y);
         viewport.width(width);
         viewport.height(-height);
         viewport.minDepth(0.0F);
         viewport.maxDepth(1.0F);
         VK10.vkCmdSetViewport(INSTANCE.currentCmdBuffer, 0, viewport);
      }
   }

   public static void setScissor(int x, int y, int width, int height) {
      if (INSTANCE.recordingCmds && INSTANCE.boundFramebuffer != null) {
         MemoryStack stack = MemoryStack.stackPush();

         try {
            Framebuffer framebuffer = INSTANCE.boundFramebuffer;
            int framebufferHeight = framebuffer.getHeight();
            x = Math.max(0, x);
            width = Math.min(width, framebuffer.getWidth());
            org.lwjgl.vulkan.VkRect2D.Buffer scissor = VkRect2D.malloc(1, stack);
            scissor.offset().set(x, framebufferHeight - (y + height));
            scissor.extent().set(width, height);
            VK10.vkCmdSetScissor(INSTANCE.currentCmdBuffer, 0, scissor);
         } catch (Throwable var9) {
            if (stack != null) {
               try {
                  stack.close();
               } catch (Throwable var8) {
                  var9.addSuppressed(var8);
               }
            }

            throw var9;
         }

         if (stack != null) {
            stack.close();
         }
      }
   }

   public static void resetScissor() {
      if (INSTANCE.boundFramebuffer != null) {
         MemoryStack stack = MemoryStack.stackPush();

         try {
            org.lwjgl.vulkan.VkRect2D.Buffer scissor = INSTANCE.boundFramebuffer.scissor(stack);
            VK10.vkCmdSetScissor(INSTANCE.currentCmdBuffer, 0, scissor);
         } catch (Throwable var4) {
            if (stack != null) {
               try {
                  stack.close();
               } catch (Throwable var3) {
                  var4.addSuppressed(var3);
               }
            }

            throw var4;
         }

         if (stack != null) {
            stack.close();
         }
      }
   }

   public static void pushDebugSection(String s) {
   }

   public static void popDebugSection() {
   }

   public static void popPushDebugSection(String s) {
      popDebugSection();
      pushDebugSection(s);
   }

   public static int getFramesNum() {
      return INSTANCE.framesNum;
   }

   public static VkCommandBuffer getCommandBuffer() {
      return INSTANCE.currentCmdBuffer;
   }

   public static boolean isRecording() {
      return INSTANCE.recordingCmds;
   }

   public static void scheduleSwapChainUpdate() {
      swapChainUpdate = true;
   }
}
