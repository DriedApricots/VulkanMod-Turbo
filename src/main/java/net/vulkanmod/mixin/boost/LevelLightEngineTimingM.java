package net.vulkanmod.mixin.boost;

import net.minecraft.world.level.lighting.LevelLightEngine;
import net.vulkanmod.boost.Bench;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Dev bench only: measures client light engine time on the render thread (no effect unless VB_BENCH is set). */
@Mixin(LevelLightEngine.class)
public abstract class LevelLightEngineTimingM {
   @Unique private long vb$start;

   @Inject(method = "runLightUpdates", at = @At("HEAD"))
   private void vb$begin(CallbackInfoReturnable<Integer> cir) {
      if (Bench.measuringLight()) {
         this.vb$start = System.nanoTime();
      }
   }

   @Inject(method = "runLightUpdates", at = @At("RETURN"))
   private void vb$end(CallbackInfoReturnable<Integer> cir) {
      if (Bench.measuringLight() && this.vb$start != 0L) {
         Bench.addLightNanos(System.nanoTime() - this.vb$start);
         this.vb$start = 0L;
      }
   }
}
