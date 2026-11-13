package net.vulkanmod.mixin.extra;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.Initializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** VulkanMod Turbo Extra: hide rain / snow columns (the sounds and gameplay are unchanged). */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererM {
   @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
   private void vb$weather(ClientLevel level, float partialTicks, Vec3 cameraPos, WeatherRenderState state, CallbackInfo ci) {
      if (!Initializer.CONFIG.weather) {
         state.intensity = 0.0F;
         ci.cancel();
      }
   }
}