package net.vulkanmod.mixin.extra;

import net.vulkanmod.Initializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** VulkanMod Turbo Extra: the floating book above enchanting tables. */
@Mixin(net.minecraft.client.renderer.blockentity.EnchantTableRenderer.class)
public abstract class EnchantTableRendererM {
   @Inject(method = "submit", at = @At("HEAD"), cancellable = true)
   private void vb$book(CallbackInfo ci) {
      if (!Initializer.CONFIG.enchantingBook) {
         ci.cancel();
      }
   }
}