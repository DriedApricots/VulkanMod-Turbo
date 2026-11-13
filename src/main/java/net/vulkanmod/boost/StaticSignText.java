package net.vulkanmod.boost;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.SectionPos;
import net.minecraft.util.FormattedCharSequence;
import net.vulkanmod.Initializer;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/**
 * VulkanBoost: sign text was laid out and turned into glyph quads for every sign every frame. Sign text only changes
 * when its content, colour, glow, outline visibility (glow ink, &lt; 16 blocks), light or the font changes, so every
 * line is baked once per chunk section into GPU buffers and drawn with vanilla's own text render types, at the same
 * point of the frame where vanilla draws world text. All those inputs are part of a per-line signature that is compared
 * every frame; any difference re-bakes the section. Obfuscated (&amp;k) text is left to vanilla so it keeps animating.
 */
public final class StaticSignText {
   /** One sign text line as vanilla would submit it; pose is relative to the section origin. */
   public record Line(long section, Matrix4f pose, float x, float y, FormattedCharSequence text, Font.DisplayMode mode, int light, int color,
                      int outline, long signature) {}

   private static final int EXPIRE_FRAMES = 300;
   private static final Long2ObjectOpenHashMap<Section> SECTIONS = new Long2ObjectOpenHashMap<>();
   private static long frame;
   private static boolean armed;
   private static double camX, camY, camZ;

   private StaticSignText() {
   }

   public static boolean enabled() {
      return Initializer.CONFIG.staticSignText;
   }

   public static void submit(Line line) {
      Section section = SECTIONS.get(line.section());
      if (section == null) {
         section = new Section(line.section());
         SECTIONS.put(line.section(), section);
      }

      if (section.frame != frame) {
         section.frame = frame;
         section.current.clear();
         section.currentSignature.clear();
      }

      section.current.add(line);
      section.currentSignature.add(line.signature());
   }

   /** Main level pass only: lets the next "texts" feature phase draw the baked text. */
   public static void arm(double x, double y, double z) {
      armed = true;
      camX = x;
      camY = y;
      camZ = z;
   }

   /** Called right before vanilla's world text phase. */
   public static void drawIfArmed() {
      if (!armed) {
         return;
      }

      armed = false;
      long thisFrame = frame++;
      if (SECTIONS.isEmpty()) {
         return;
      }

      Matrix4fStack modelView = RenderSystem.getModelViewStack();
      for (Iterator<Section> it = SECTIONS.values().iterator(); it.hasNext(); ) {
         Section section = it.next();
         if (section.frame != thisFrame) {
            if (thisFrame - section.frame > EXPIRE_FRAMES) {
               section.close();
               it.remove();
            }
            continue;
         }

         if (!section.currentSignature.equals(section.builtSignature)) {
            section.rebuild();
         }

         modelView.pushMatrix();
         modelView.translate((float)(section.originX - camX), (float)(section.originY - camY), (float)(section.originZ - camZ));
         try {
            for (Batch batch : section.batches) {
               PreparedRenderType prepared = batch.renderType.prepare();
               RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(batch.renderType.primitiveTopology());
               prepared.drawFromBuffer(batch.vertexBuffer, indices.getBuffer(batch.indexCount), indices.type(), 0, 0, batch.indexCount);
            }
         } finally {
            modelView.popMatrix();
         }
      }
   }

   public static double camX() {
      return camX;
   }

   public static double camY() {
      return camY;
   }

   public static double camZ() {
      return camZ;
   }

   private record Batch(RenderType renderType, GpuBuffer vertexBuffer, int indexCount) {}

   private static final class Section {
      final int originX, originY, originZ;
      final List<Line> current = new ArrayList<>();
      final LongArrayList currentSignature = new LongArrayList();
      final LongArrayList builtSignature = new LongArrayList();
      final List<Batch> batches = new ArrayList<>();
      long frame = -1;

      Section(long key) {
         this.originX = SectionPos.sectionToBlockCoord(SectionPos.x(key));
         this.originY = SectionPos.sectionToBlockCoord(SectionPos.y(key));
         this.originZ = SectionPos.sectionToBlockCoord(SectionPos.z(key));
      }

      void rebuild() {
         HitchLog.signRebuilds++;
         this.close();
         Font font = Minecraft.getInstance().font;
         Map<RenderType, BufferBuilder> builders = new IdentityHashMap<>();
         List<ByteBufferBuilder> memory = new ArrayList<>();
         try {
            for (Line line : this.current) {
               // same as TextFeatureRenderer.buildGroup
               if (line.outline() == 0) {
                  emit(font.prepareText(line.text(), line.x(), line.y(), line.color(), false, false, 0), line, line.mode(), builders, memory);
               } else {
                  emit(font.prepare8xTextOutline(line.text(), line.x(), line.y(), line.outline()), line, Font.DisplayMode.NORMAL, builders, memory);
                  emit(font.prepareText(line.text(), line.x(), line.y(), line.color(), false, false, 0), line, Font.DisplayMode.POLYGON_OFFSET, builders, memory);
               }
            }

            for (Map.Entry<RenderType, BufferBuilder> entry : builders.entrySet()) {
               MeshData mesh = entry.getValue().build();
               if (mesh != null) {
                  try (mesh) {
                     GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "VulkanMod+ sign text", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
                     this.batches.add(new Batch(entry.getKey(), buffer, mesh.drawState().indexCount()));
                  }
               }
            }
         } finally {
            for (ByteBufferBuilder m : memory) {
               m.close();
            }
         }

         this.builtSignature.clear();
         this.builtSignature.addAll(this.currentSignature);
      }

      private static void emit(Font.PreparedText text, Line line, Font.DisplayMode mode, Map<RenderType, BufferBuilder> builders, List<ByteBufferBuilder> memory) {
         text.visit(new Font.GlyphVisitor() {
            @Override
            public void acceptRenderable(TextRenderable renderable) {
               RenderType renderType = renderable.renderType(mode);
               BufferBuilder builder = builders.computeIfAbsent(renderType, rt -> {
                  ByteBufferBuilder bytes = new ByteBufferBuilder(16384);
                  memory.add(bytes);
                  return new BufferBuilder(bytes, rt.primitiveTopology(), rt.format());
               });
               renderable.render(line.pose(), builder, line.light(), false);
            }
         });
      }

      void close() {
         for (Batch batch : this.batches) {
            batch.vertexBuffer().close();
         }
         this.batches.clear();
         this.builtSignature.clear();
      }
   }
}
