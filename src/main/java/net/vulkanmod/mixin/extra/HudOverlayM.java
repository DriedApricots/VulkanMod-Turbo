package net.vulkanmod.mixin.extra;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.entity.Entity;
import net.vulkanmod.Initializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** VulkanBoost Extra: small FPS counter and coordinates in a screen corner, without opening F3. */
@Mixin(Hud.class)
public abstract class HudOverlayM {
   @Inject(method = "extractRenderState", at = @At("TAIL"))
   private void vb$overlay(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
      int corner = Initializer.CONFIG.fpsOverlay;
      boolean coords = Initializer.CONFIG.coordsOverlay;
      Minecraft mc = Minecraft.getInstance();
      if (corner == 0 && !coords || ((Hud)(Object)this).isHidden() || mc.debugEntries.isOverlayVisible()) {
         return;
      }
      Font font = mc.font;
      java.util.List<String> lines = new java.util.ArrayList<>(2);
      if (corner != 0) {
         lines.add(mc.getFps() + " FPS");
      }
      Entity camera = mc.getCameraEntity();
      if (coords && camera != null) {
         lines.add(String.format(java.util.Locale.ROOT, "XYZ %d / %d / %d", camera.getBlockX(), camera.getBlockY(), camera.getBlockZ()));
      }
      int place = corner == 0 ? 1 : corner;
      int w = graphics.guiWidth(), h = graphics.guiHeight();
      for (int i = 0; i < lines.size(); i++) {
         String s = lines.get(i);
         int x = place == 1 || place == 3 ? 2 : w - font.width(s) - 2;
         int y = place <= 2 ? 2 + i * 10 : h - 10 * (lines.size() - i) - 2;
         graphics.text(font, s, x, y, 0xFFFFFFFF, true);
      }
   }
}