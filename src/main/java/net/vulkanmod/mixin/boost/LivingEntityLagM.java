package net.vulkanmod.mixin.boost;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.vulkanmod.boost.LagCompensation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * VulkanMod Turbo TPS compensation for item use (players only): eating, drinking, drawing bows/crossbows/tridents/spears.
 * The remaining use time is shortened by the measured lag, but vanilla always performs the final decrement itself,
 * so completion still goes through the normal code path. Brushes, horns, spyglasses etc. are left untouched.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityLagM {
   @Shadow
   protected int useItemRemaining;

   @Inject(method = "updateUsingItem", at = @At("HEAD"))
   private void vb$compensateLag(ItemStack useItem, CallbackInfo ci) {
      int extra = LagCompensation.extraTicks();
      if (extra <= 0 || !((Object)this instanceof ServerPlayer) || this.useItemRemaining <= 1) {
         return;
      }

      ItemUseAnimation animation = useItem.getUseAnimation();
      if (animation == ItemUseAnimation.EAT
         || animation == ItemUseAnimation.DRINK
         || animation == ItemUseAnimation.BOW
         || animation == ItemUseAnimation.CROSSBOW
         || animation == ItemUseAnimation.TRIDENT
         || animation == ItemUseAnimation.SPEAR) {
         this.useItemRemaining -= Math.min(extra, this.useItemRemaining - 1);
      }
   }
}
