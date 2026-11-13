package net.vulkanmod.mixin.extra;

import net.vulkanmod.Initializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** VulkanMod Turbo Extra: beacon beam. */
@Mixin(net.minecraft.client.renderer.blockentity.BeaconRenderer.class)
public abstract class BlockEntitySwitchesM {
   @Inject(method = "submit", at = @At("HEAD"), cancellable = true)
   private void vb$beam(CallbackInfo ci) {
      if (!Initializer.CONFIG.beaconBeam) {
         ci.cancel();
      }
   }
}