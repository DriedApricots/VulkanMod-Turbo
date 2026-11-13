package net.vulkanmod.mixin.profiling;

import net.minecraft.client.renderer.LevelRenderer;
import net.vulkanmod.render.profiling.Profiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 26.2: particles/entities are submitted in submitFeatures and drawn by FeatureRenderDispatcher,
// so the old main-pass profiler hooks were dropped. Only a coarse "Features" section remains.
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
   @Inject(method = "submitFeatures", at = @At("HEAD"))
   private void pushFeatures(CallbackInfo ci) {
      Profiler.getMainProfiler().push("Features");
   }

   @Inject(method = "submitFeatures", at = @At("RETURN"))
   private void popFeatures(CallbackInfo ci) {
      Profiler.getMainProfiler().pop();
   }
}
