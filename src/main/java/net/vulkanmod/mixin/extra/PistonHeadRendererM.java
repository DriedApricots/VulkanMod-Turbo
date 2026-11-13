package net.vulkanmod.mixin.extra;

import net.vulkanmod.Initializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** VulkanMod Turbo Extra: blocks moved by pistons are not drawn while moving (they appear when the move ends). */
@Mixin(net.minecraft.client.renderer.blockentity.PistonHeadRenderer.class)
public abstract class PistonHeadRendererM {
   @Inject(method = "submit", at = @At("HEAD"), cancellable = true)
   private void vb$moving(CallbackInfo ci) {
      if (!Initializer.CONFIG.pistonAnimation) {
         ci.cancel();
      }
   }
}