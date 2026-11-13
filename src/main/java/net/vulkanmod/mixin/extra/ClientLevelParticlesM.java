package net.vulkanmod.mixin.extra;

import net.minecraft.client.multiplayer.ClientLevel;
import net.vulkanmod.Initializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** VulkanBoost Extra: block destroy / block cracking particles. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelParticlesM {
   @Inject(method = "addDestroyBlockEffect", at = @At("HEAD"), cancellable = true)
   private void vb$destroyParticles(CallbackInfo ci) {
      if (!Initializer.CONFIG.particles || !Initializer.CONFIG.blockBreakParticles) {
         ci.cancel();
      }
   }

   @Inject(method = "addBreakingBlockEffect", at = @At("HEAD"), cancellable = true)
   private void vb$breakingParticles(CallbackInfo ci) {
      if (!Initializer.CONFIG.particles || !Initializer.CONFIG.blockBreakingParticles) {
         ci.cancel();
      }
   }
}