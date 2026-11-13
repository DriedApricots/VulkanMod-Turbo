package net.vulkanmod.mixin.screen;

import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.VOptionScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(OptionsScreen.class)
public class OptionsScreenM extends Screen {
   @Shadow
   @Final
   private Screen lastScreen;
   @Shadow
   @Final
   private Options options;

   protected OptionsScreenM(Component title) {
      super(title);
   }

   // VulkanBoost: the lambda index changes between game versions (in 26.2 "$2" is Music & Sounds, video is "$3"),
   // so match whichever init lambda creates the vanilla VideoSettingsScreen instead of a fixed index.
   @Inject(
      method = {"lambda$init$1", "lambda$init$2", "lambda$init$3", "lambda$init$4", "lambda$init$5", "lambda$init$6", "lambda$init$7", "lambda$init$8", "lambda$init$9", "lambda$init$10"},
      at = @At(value = "NEW", target = "net/minecraft/client/gui/screens/options/VideoSettingsScreen"),
      cancellable = true,
      require = 1,
      allow = 1
   )
   private void injectVideoOptionScreen(CallbackInfoReturnable<Screen> cir) {
      cir.setReturnValue(new VOptionScreen(Component.literal("Video Setting"), this));
   }
}
