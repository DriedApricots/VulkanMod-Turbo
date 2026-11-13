package net.vulkanmod.mixin.boost;

import java.util.Optional;
import net.minecraft.client.resources.language.FormattedBidiReorder;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.SubStringSource;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * VulkanMod Turbo: every piece of GUI text (F3, chat, scoreboard, tooltips...) went through ICU Bidi + ArabicShaping every
 * frame. With a left-to-right base direction and no character from U+0590 upwards (Hebrew, Arabic, RTL marks and other
 * RTL/shaping scripts start there) the result is always a single non-reversed run, i.e. the text in logical order -
 * so build exactly that without running the bidi algorithm.
 */
@Mixin(FormattedBidiReorder.class)
public abstract class FormattedBidiReorderM {
   @Inject(method = "reorder", at = @At("HEAD"), cancellable = true)
   private static void vb$ltrFastPath(FormattedText text, boolean defaultRightToLeft, CallbackInfoReturnable<FormattedCharSequence> cir) {
      if (defaultRightToLeft) {
         return;
      }

      Optional<net.minecraft.util.Unit> rtl = text.visit(content -> {
         for (int i = 0, n = content.length(); i < n; i++) {
            if (content.charAt(i) >= 0x0590) {
               return FormattedText.STOP_ITERATION;
            }
         }
         return Optional.empty();
      });
      if (rtl.isPresent()) {
         return;
      }

      SubStringSource source = SubStringSource.create(text, c -> c, s -> s);
      cir.setReturnValue(FormattedCharSequence.composite(source.substring(0, source.getPlainText().length(), false)));
   }
}
