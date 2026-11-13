package net.vulkanmod.mixin.boost;

import net.minecraft.client.gui.font.FontManager;
import net.vulkanmod.boost.TextPrepareCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** VulkanMod Turbo: cached text layouts reference baked glyphs; drop them when fonts are reloaded. */
@Mixin(FontManager.class)
public abstract class FontManagerM {
   @Inject(method = "apply", at = @At("HEAD"))
   private void vb$clearTextCache(CallbackInfo ci) {
      TextPrepareCache.clear();
   }
}
