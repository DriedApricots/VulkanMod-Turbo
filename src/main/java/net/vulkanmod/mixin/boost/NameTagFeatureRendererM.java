package net.vulkanmod.mixin.boost;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.vulkanmod.boost.NameTagBake;
import net.vulkanmod.boost.TextPrepareCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

/**
 * VulkanBoost: name tag / hologram text layout is cached (see {@link TextPrepareCache}) and its glyph vertices are
 * baked once per text and replayed with the tag's pose (see {@link NameTagBake}).
 */
@Mixin(NameTagFeatureRenderer.class)
public abstract class NameTagFeatureRendererM extends RenderTypeFeatureRenderer<NameTagFeatureRenderer.Submit> {
   /**
    * @author VulkanBoost
    * @reason cache the layout per name component instead of redoing it every frame
    */
   @Overwrite
   private static Font.PreparedText prepareText(final Font font, final NameTagFeatureRenderer.Submit nameTag) {
      net.minecraft.util.FormattedCharSequence text = nameTag.text().getVisualOrderText(); // cached by MutableComponent
      return TextPrepareCache.get(
         font, nameTag.text(), text, nameTag.x(), nameTag.y(), nameTag.color(), false, nameTag.backgroundColor(), TextPrepareCache.KIND_TEXT,
         () -> font.prepareText(text, nameTag.x(), nameTag.y(), nameTag.color(), false, false, nameTag.backgroundColor())
      );
   }

   /**
    * @author VulkanBoost
    * @reason replay baked glyph vertices for unchanged texts instead of rebuilding them every frame
    */
   @Overwrite
   protected void buildGroup(final FeatureFrameContext context, final List<NameTagFeatureRenderer.Submit> submits) {
      for (NameTagFeatureRenderer.Submit nameTag : submits) {
         Font.PreparedText preparedText = prepareText(context.font(), nameTag);
         List<NameTagBake.Run> runs = TextPrepareCache.lastCached ? NameTagBake.get(preparedText, nameTag.displayMode()) : null;
         if (runs != null) {
            NameTagBake.replay(runs, nameTag.pose(), nameTag.lightCoords(), this::getVertexBuilder);
         } else {
            this.vb$vanilla(preparedText, nameTag);
         }
      }
   }

   @Unique
   private void vb$vanilla(Font.PreparedText preparedText, NameTagFeatureRenderer.Submit nameTag) {
      org.joml.Matrix4f pose = new org.joml.Matrix4f(nameTag.pose());
      int light = nameTag.lightCoords();
      Font.DisplayMode mode = nameTag.displayMode();
      preparedText.visit(new Font.GlyphVisitor() {
         @Override
         public void acceptRenderable(TextRenderable renderable) {
            renderable.render(pose, NameTagFeatureRendererM.this.getVertexBuilder(renderable.renderType(mode)), light, false);
         }
      });
   }
}