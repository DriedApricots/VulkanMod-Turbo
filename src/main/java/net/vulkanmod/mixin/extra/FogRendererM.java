package net.vulkanmod.mixin.extra;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import net.vulkanmod.Initializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * VulkanBoost Extra: remove the distance / atmospheric fog in air. Fog under water, in lava, in powder snow and the
 * blindness / darkness effects are kept, since they are gameplay.
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererM {
   @Inject(method = "setupFog", at = @At("RETURN"))
   private void vb$noFog(Camera camera, int renderDistanceInChunks, DeltaTracker deltaTracker, float darkenWorldAmount, ClientLevel level,
                         CallbackInfoReturnable<FogData> cir) {
      if (Initializer.CONFIG.fog || camera.getFluidInCamera() != FogType.NONE) {
         return;
      }
      if (camera.entity() instanceof LivingEntity living && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS))) {
         return;
      }
      FogData fog = cir.getReturnValue();
      fog.environmentalStart = 1.0E6F;
      fog.environmentalEnd = 1.0E6F + 1.0F;
      fog.renderDistanceStart = 1.0E6F;
      fog.renderDistanceEnd = 1.0E6F + 1.0F;
   }
}