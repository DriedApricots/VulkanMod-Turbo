package net.vulkanmod.mixin.render.clouds;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.render.profiling.Profiler;
import net.vulkanmod.render.sky.CloudRendererHolder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 26.2: allChanged/onResourceManagerReload moved to LevelExtractor (handled in LevelExtractorMixin).
@Mixin(LevelRenderer.class)
public abstract class LevelRendererM {
   @Shadow
   @Final
   private LevelTargetBundle targets;

   @Inject(method = "addCloudsPass", at = @At("HEAD"), cancellable = true)
   public void addCloudsPass(
      FrameGraphBuilder frame,
      CloudStatus cloudStatus,
      Vec3 cameraPosition,
      long gameTime,
      float partialTicks,
      int cloudColor,
      float cloudHeight,
      int cloudRange,
      CallbackInfo ci
   ) {
      FramePass framePass = frame.addPass("clouds");
      if (this.targets.clouds != null) {
         this.targets.clouds = framePass.readsAndWrites(this.targets.clouds);
      } else {
         this.targets.main = framePass.readsAndWrites(this.targets.main);
      }

      framePass.executes(() -> {
         Profiler profiler = Profiler.getMainProfiler();
         profiler.push("Clouds");
         CloudRendererHolder.get()
            .renderClouds(cloudHeight, cloudColor, cameraPosition.x(), cameraPosition.y(), cameraPosition.z(), gameTime, partialTicks);
         profiler.pop();
      });
      ci.cancel();
   }
}
