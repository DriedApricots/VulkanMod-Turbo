package net.vulkanmod.mixin.boost;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.IdentityHashMap;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.entity.SignText;
import net.vulkanmod.boost.StaticSignText;
import net.vulkanmod.boost.TextPrepareCache;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** VulkanMod Turbo: sign text lines go to {@link StaticSignText} (baked per section) instead of the per-frame text path. */
@Mixin(AbstractSignRenderer.class)
public abstract class AbstractSignRendererM {
   @Unique private static final IdentityHashMap<FormattedCharSequence, Boolean> VB_OBFUSCATED = new IdentityHashMap<>();
   @Unique private static double vb$camX, vb$camY, vb$camZ;
   @Unique private static BlockPos vb$pos = BlockPos.ZERO;

   @Inject(method = "submit(Lnet/minecraft/client/renderer/blockentity/state/SignRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("HEAD"))
   private void vb$captureCamera(SignRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
      vb$camX = camera.pos.x;
      vb$camY = camera.pos.y;
      vb$camZ = camera.pos.z;
      vb$pos = state.blockPos;
   }

   @Redirect(
      method = "submitSignText",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitText(Lcom/mojang/blaze3d/vertex/PoseStack;FFLnet/minecraft/util/FormattedCharSequence;ZLnet/minecraft/client/gui/Font$DisplayMode;IIII)V")
   )
   private void vb$staticText(SubmitNodeCollector collector, PoseStack poseStack, float x, float y, FormattedCharSequence text, boolean dropShadow,
                              Font.DisplayMode mode, int light, int color, int background, int outline) {
      if (!StaticSignText.enabled() || dropShadow || background != 0 || vb$obfuscated(text)) {
         collector.submitText(poseStack, x, y, text, dropShadow, mode, light, color, background, outline);
         return;
      }

      long section = SectionPos.asLong(vb$pos);
      // pose is camera relative; make it relative to the section origin
      Matrix4f pose = new Matrix4f()
         .translation((float)(vb$camX - SectionPos.sectionToBlockCoord(SectionPos.x(section))),
            (float)(vb$camY - SectionPos.sectionToBlockCoord(SectionPos.y(section))),
            (float)(vb$camZ - SectionPos.sectionToBlockCoord(SectionPos.z(section))))
         .mul(poseStack.last().pose());

      long h = section;
      h = h * 0x9E3779B97F4A7C15L + vb$pos.asLong();
      h = h * 0x9E3779B97F4A7C15L + Float.floatToIntBits(x);
      h = h * 0x9E3779B97F4A7C15L + Float.floatToIntBits(y);
      h = h * 0x9E3779B97F4A7C15L + System.identityHashCode(text);
      h = h * 0x9E3779B97F4A7C15L + mode.ordinal();
      h = h * 0x9E3779B97F4A7C15L + light;
      h = h * 0x9E3779B97F4A7C15L + color;
      h = h * 0x9E3779B97F4A7C15L + outline;
      h = h * 0x9E3779B97F4A7C15L + TextPrepareCache.fontGeneration;
      // the sign's own transform (rotation / wall / hanging / front vs back), rounded to tolerate camera float noise
      for (int i = 0; i < 16; i++) {
         float v = pose.getRowColumn(i & 3, i >> 2);
         h = h * 0x9E3779B97F4A7C15L + Math.round(v * 4096.0F);
      }
      h ^= h >>> 29;
      StaticSignText.submit(new StaticSignText.Line(section, pose, x, y, text, mode, light, color, outline, h));
   }

   @Unique
   private static boolean vb$obfuscated(FormattedCharSequence text) {
      Boolean cached = VB_OBFUSCATED.get(text);
      if (cached == null) {
         if (VB_OBFUSCATED.size() > 8192) {
            VB_OBFUSCATED.clear();
         }
         cached = TextPrepareCache.isObfuscated(text);
         VB_OBFUSCATED.put(text, cached);
      }
      return cached;
   }
}
