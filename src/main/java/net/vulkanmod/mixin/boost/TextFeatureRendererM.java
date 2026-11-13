package net.vulkanmod.mixin.boost;

import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.util.FormattedCharSequence;
import net.vulkanmod.boost.TextPrepareCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** VulkanBoost: world text (signs, text displays...) layout is cached (see {@link TextPrepareCache}). */
@Mixin(TextFeatureRenderer.class)
public abstract class TextFeatureRendererM {
   @Redirect(
      method = "buildGroup",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Font;prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;")
   )
   private Font.PreparedText vb$cachedText(Font font, FormattedCharSequence text, float x, float y, int color, boolean shadow, boolean includeEmpty, int background) {
      if (includeEmpty) {
         return font.prepareText(text, x, y, color, shadow, true, background);
      }

      return TextPrepareCache.get(font, text, text, x, y, color, shadow, background, TextPrepareCache.KIND_TEXT,
         () -> font.prepareText(text, x, y, color, shadow, false, background));
   }

   @Redirect(
      method = "buildGroup",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Font;prepare8xTextOutline(Lnet/minecraft/util/FormattedCharSequence;FFI)Lnet/minecraft/client/gui/Font$PreparedText;")
   )
   private Font.PreparedText vb$cachedOutline(Font font, FormattedCharSequence text, float x, float y, int outlineColor) {
      return TextPrepareCache.get(font, text, text, x, y, outlineColor, false, 0, TextPrepareCache.KIND_OUTLINE,
         () -> font.prepare8xTextOutline(text, x, y, outlineColor));
   }
}
