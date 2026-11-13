package net.vulkanmod.mixin.render.frame;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 26.2 port: frame begin/end moved to VkGpuSurface (acquireNextTexture / present) and the screen blit is
// GpuSurface.blitFromTexture (a no-op in VkGpuSurface).
@Mixin(Minecraft.class)
public class MinecraftMixin {
   /**
    * VulkanMod Turbo: runTick updates animated textures (render passes into the atlases) before the frame has begun, so
    * the frame was started early by that render pass. acquireNextTexture then had to submit that partial frame on its
    * own (presenting a stale swapchain image) and wait for another fence - a camera hitch 20 times a second, visible
    * below ~150 FPS. The update is deferred into the frame instead: it runs right after the frame begins
    * (VkGpuSurface.acquireNextTexture), or at the end of renderFrame when nothing was acquired (minimized window).
    */
   @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/TextureManager;tick()V"))
   private void vb$deferTextureTick(TextureManager textureManager) {
      Renderer.pendingTextureTick = true;
   }

   /**
    * VulkanMod Turbo: while the window is minimized vanilla neither acquires nor presents, but still renders. The frame is
    * then started lazily by the first render pass and nothing ever ended it: every frame kept appending to the same
    * command buffer until the window was restored, and submitting that buffer (full of references to long-changed
    * resources) lost the device. End such a frame here, so every rendered frame is submitted on its own.
    */
   @Inject(method = "renderFrame", at = @At("TAIL"))
   private void vb$endUnpresentedFrame(boolean advanceGameTime, CallbackInfo ci) {
      Renderer.runPendingTextureTick();
      if (Renderer.isRecording()) {
         Renderer.getInstance().endFrame();
      }
   }
}
