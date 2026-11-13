package net.vulkanmod.mixin.extra;

import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.client.gui.components.toasts.NowPlayingToast;
import net.minecraft.client.gui.components.toasts.RecipeToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.components.toasts.TutorialToast;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** VulkanMod Turbo Extra: advancement / recipe / tutorial / music toasts. System toasts (errors, warnings) always show. */
@Mixin(ToastManager.class)
public abstract class ToastManagerM {
   @Inject(method = "addToast", at = @At("HEAD"), cancellable = true)
   private void vb$toasts(Toast toast, CallbackInfo ci) {
      Config c = Initializer.CONFIG;
      if (toast instanceof AdvancementToast && !c.advancementToasts || toast instanceof RecipeToast && !c.recipeToasts
         || toast instanceof TutorialToast && !c.tutorialToasts || toast instanceof NowPlayingToast && !c.musicToasts) {
         ci.cancel();
      }
   }
}