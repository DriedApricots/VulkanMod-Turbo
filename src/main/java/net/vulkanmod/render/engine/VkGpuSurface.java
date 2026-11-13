package net.vulkanmod.render.engine;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Collection;
import java.util.EnumSet;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;

/**
 * 26.2 port: the window surface became part of the backend API (acquire -> blit -> submit -> present).
 * VulkanMod keeps driving its own swapchain, so the surface simply maps onto Renderer's frame lifecycle:
 * acquire = begin a VulkanMod frame, present = end/submit/present it. The main target is already rendered
 * into the swapchain by VulkanMod's main pass, so the blit is a no-op.
 */
@Environment(EnvType.CLIENT)
public class VkGpuSurface implements GpuSurfaceBackend {
   private final VkCommandEncoder encoder;
   private boolean suboptimal;

   public VkGpuSurface(VkCommandEncoder encoder) {
      this.encoder = encoder;
   }

   @Override
   public void configure(GpuSurface.Configuration configuration) {
      boolean vsync = configuration.presentMode() == GpuSurface.PresentMode.FIFO || configuration.presentMode() == GpuSurface.PresentMode.FIFO_RELAXED;
      Vulkan.setVsync(vsync);
      Renderer.scheduleSwapChainUpdate();
      this.suboptimal = false;
   }

   @Override
   public boolean isSuboptimal() {
      return this.suboptimal;
   }

   @Override
   public void acquireNextTexture() {
      Renderer.getInstance().beginFrame();
      this.encoder.getTransientMemory().rotate();
      Renderer.clearAttachments(16640);
      Renderer.runPendingTextureTick(); // animated textures, deferred from runTick (MinecraftMixin)
   }

   @Override
   public void blitFromTexture(CommandEncoderBackend commandEncoder, GpuTextureView textureView) {
      // main target is VulkanMod's swapchain pass already
   }

   @Override
   public void present() {
      net.vulkanmod.boost.Bench.yawFrame(); // dev only (VB_YAWLOG)
      if (net.vulkanmod.boost.Bench.CAPTURE_HOOK != null) {
         net.vulkanmod.boost.Bench.CAPTURE_HOOK.run(); // dev only (VB_CAPTURE)
      }
      Renderer.getInstance().endFrame();
   }

   @Override
   public void close() {
   }

   @Override
   public Collection<GpuSurface.PresentMode> supportedPresentModes() {
      return EnumSet.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.IMMEDIATE, GpuSurface.PresentMode.MAILBOX);
   }
}
