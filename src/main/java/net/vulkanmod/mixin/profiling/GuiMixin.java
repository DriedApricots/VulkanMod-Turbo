package net.vulkanmod.mixin.profiling;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.vulkanmod.render.profiling.ProfilerOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 26.2: in-game overlay drawing moved from Gui to Hud; the debug overlay lives on Minecraft.
@Mixin(Hud.class)
public class GuiMixin {
   @Inject(method = "<init>", at = @At("RETURN"))
   private void createProfilerOverlay(Minecraft minecraft, CallbackInfo ci) {
      ProfilerOverlay.createInstance(minecraft);
   }

   @Inject(method = "extractRenderState", at = @At("RETURN"))
   private void renderProfilerOverlay(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
      if (ProfilerOverlay.shouldRender && !Minecraft.getInstance().getDebugOverlay().showDebugScreen()) {
         ProfilerOverlay.INSTANCE.render(graphics);
      }
   }
}
