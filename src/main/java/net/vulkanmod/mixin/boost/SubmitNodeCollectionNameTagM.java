package net.vulkanmod.mixin.boost;

import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.vulkanmod.boost.NameTagCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

/** VulkanMod Turbo: canonical name tag components + cached widths (see {@link NameTagCache}). */
@Mixin(SubmitNodeCollection.class)
public abstract class SubmitNodeCollectionNameTagM {
   @ModifyVariable(method = "submitNameTag", at = @At("HEAD"), argsOnly = true)
   private Component vb$canonicalName(Component name) {
      return name == null ? null : NameTagCache.intern(name);
   }

   @Redirect(method = "submitNameTag", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Font;width(Lnet/minecraft/network/chat/FormattedText;)I"))
   private int vb$cachedWidth(Font font, FormattedText text) {
      return text instanceof Component component ? NameTagCache.width(font, component) : font.width(text);
   }
}