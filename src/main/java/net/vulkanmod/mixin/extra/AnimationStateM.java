package net.vulkanmod.mixin.extra;

import net.minecraft.client.renderer.texture.SpriteContents;
import net.vulkanmod.Initializer;
import net.vulkanmod.boost.AnimationKind;
import net.vulkanmod.config.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** VulkanMod Turbo Extra: per-kind texture animation switches (water, lava, fire, portal, everything else). */
@Mixin(SpriteContents.AnimationState.class)
public abstract class AnimationStateM implements AnimationKind {
   @Unique private int vb$kind = AnimationKind.OTHER;

   @Override
   public void vb$setKind(int kind) {
      this.vb$kind = kind;
   }

   @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
   private void vb$animationSwitch(CallbackInfo ci) {
      Config c = Initializer.CONFIG;
      boolean on = switch (this.vb$kind) {
         case AnimationKind.WATER -> c.animWater;
         case AnimationKind.LAVA -> c.animLava;
         case AnimationKind.FIRE -> c.animFire;
         case AnimationKind.PORTAL -> c.animPortal;
         default -> c.animOther;
      };
      if (!on) {
         ci.cancel();
      }
   }
}