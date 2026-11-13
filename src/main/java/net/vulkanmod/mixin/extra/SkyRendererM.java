package net.vulkanmod.mixin.extra;

import net.minecraft.client.renderer.SkyRenderer;
import net.vulkanmod.Initializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** VulkanMod Turbo Extra: sky, sun, moon and stars switches. */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererM {
   @Inject(method = {"renderSkyDisc", "renderSunriseAndSunset", "renderDarkDisc"}, at = @At("HEAD"), cancellable = true)
   private void vb$sky(CallbackInfo ci) {
      if (!Initializer.CONFIG.sky) {
         ci.cancel();
      }
   }

   @Inject(method = "renderSun", at = @At("HEAD"), cancellable = true)
   private void vb$sun(CallbackInfo ci) {
      if (!Initializer.CONFIG.sun) {
         ci.cancel();
      }
   }

   @Inject(method = "renderMoon", at = @At("HEAD"), cancellable = true)
   private void vb$moon(CallbackInfo ci) {
      if (!Initializer.CONFIG.moon) {
         ci.cancel();
      }
   }

   @Inject(method = "renderStars", at = @At("HEAD"), cancellable = true)
   private void vb$stars(CallbackInfo ci) {
      if (!Initializer.CONFIG.stars) {
         ci.cancel();
      }
   }
}