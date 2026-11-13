package net.vulkanmod.mixin.boost;

import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.vulkanmod.boost.StaticSignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** VulkanMod Turbo: baked sign text is drawn exactly where vanilla draws world text (first "texts" phase of the main frame). */
@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
public abstract class PreparedFrameM {
   @Inject(
      method = "executeTranslucent",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executePhase(Lnet/minecraft/client/renderer/feature/phase/FeatureRenderPhase;Lnet/minecraft/client/renderer/feature/FeatureFrameContext;)V", ordinal = 4)
   )
   private void vb$drawStaticSignText(CallbackInfo ci) {
      StaticSignText.drawIfArmed();
   }
}