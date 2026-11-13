package net.vulkanmod.boost;

import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * VulkanMod Turbo: name tags / holograms were turned into glyph vertices every frame (glyph visiting, per-glyph builder
 * lookup, per-vertex glyph math) although only their pose (facing the camera) and light change. A laid-out text
 * (cached by {@link TextPrepareCache}) is recorded once through the normal glyph code into local-space vertex runs
 * - one run per consecutive render type, so draw order is unchanged - and replayed every frame with the tag's pose
 * and light. Text with normals (not produced by fonts) is never baked.
 */
public final class NameTagBake {
   private static final int EXPIRE_FRAMES = 240, MAX_ENTRIES = 8192;
   private static final Matrix4f IDENTITY = new Matrix4f();
   private static final Map<Font.PreparedText, Entry> CACHE = new IdentityHashMap<>();
   private static long frame;
   private static int fontGeneration = -1;

   private NameTagBake() {
   }

   /** Recorded vertices of one render type run. Per vertex: floats x, y, z, u, v; ints color, uv1. */
   public static final class Run {
      final RenderType renderType;
      final FloatArrayList f = new FloatArrayList();
      final IntArrayList i = new IntArrayList();
      boolean hasColor, hasUv, hasUv1, hasLight;

      Run(RenderType renderType) {
         this.renderType = renderType;
      }

      int vertices() {
         return this.f.size() / 5;
      }
   }

   private static final class Entry {
      final List<Run>[] byMode = newArray();
      long lastFrame;

      @SuppressWarnings("unchecked")
      private static List<Run>[] newArray() {
         return new List[Font.DisplayMode.values().length];
      }
   }

   /** The baked runs of a cached prepared text for a display mode, recording them on first use (null: not bakeable). */
   public static List<Run> get(Font.PreparedText text, Font.DisplayMode mode) {
      if (fontGeneration != TextPrepareCache.fontGeneration) {
         fontGeneration = TextPrepareCache.fontGeneration;
         CACHE.clear();
      }
      Entry e = CACHE.get(text);
      if (e == null) {
         if (CACHE.size() >= MAX_ENTRIES) {
            CACHE.clear();
         }
         e = new Entry();
         CACHE.put(text, e);
      }
      e.lastFrame = frame;
      List<Run> runs = e.byMode[mode.ordinal()];
      if (runs == null) {
         Recorder recorder = new Recorder();
         text.visit(new Font.GlyphVisitor() {
            @Override
            public void acceptRenderable(TextRenderable renderable) {
               recorder.begin(renderable.renderType(mode));
               renderable.render(IDENTITY, recorder, 0, false);
            }
         });
         runs = recorder.failed ? List.of() : recorder.runs;
         e.byMode[mode.ordinal()] = runs;
      }
      return runs.isEmpty() && !hasNoGlyphs(text) ? null : runs;
   }

   private static boolean hasNoGlyphs(Font.PreparedText text) {
      boolean[] any = new boolean[1];
      text.visit(new Font.GlyphVisitor() {
         @Override
         public void acceptRenderable(TextRenderable renderable) {
            any[0] = true;
         }
      });
      return !any[0];
   }

   /** Emits the runs transformed by pose, with the given light. */
   public static void replay(List<Run> runs, Matrix4fc pose, int light, Function<RenderType, VertexConsumer> builders) {
      float m00 = pose.m00(), m01 = pose.m01(), m02 = pose.m02();
      float m10 = pose.m10(), m11 = pose.m11(), m12 = pose.m12();
      float m20 = pose.m20(), m21 = pose.m21(), m22 = pose.m22();
      float m30 = pose.m30(), m31 = pose.m31(), m32 = pose.m32();
      for (int r = 0, rn = runs.size(); r < rn; r++) {
         Run run = runs.get(r);
         VertexConsumer b = builders.apply(run.renderType);
         float[] f = run.f.elements();
         int[] in = run.i.elements();
         if (LITTLE_ENDIAN && b instanceof net.vulkanmod.mixin.boost.BufferBuilderAccessor raw && rawWrite(run, raw, f, in, light,
            m00, m01, m02, m10, m11, m12, m20, m21, m22, m30, m31, m32, b)) {
            continue;
         }
         for (int v = 0, n = run.vertices(); v < n; v++) {
            int fo = v * 5, io = v * 2;
            float x = f[fo], y = f[fo + 1], z = f[fo + 2];
            b.addVertex(m00 * x + m10 * y + m20 * z + m30, m01 * x + m11 * y + m21 * z + m31, m02 * x + m12 * y + m22 * z + m32);
            if (run.hasColor) {
               b.setColor(in[io]);
            }
            if (run.hasUv) {
               b.setUv(f[fo + 3], f[fo + 4]);
            }
            if (run.hasUv1) {
               b.setUv1(in[io + 1] & 0xFFFF, in[io + 1] >>> 16);
            }
            if (run.hasLight) {
               b.setLight(light);
            }
         }
      }
   }

   private static final boolean LITTLE_ENDIAN = java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN;

   /**
    * Writes a run straight into a BufferBuilder: position through addVertex, colour / uv / uv1 / light at their format
    * offsets, then marks the vertex complete. Only when the format holds exactly what the run provides; false = use setters.
    */
   private static boolean rawWrite(Run run, net.vulkanmod.mixin.boost.BufferBuilderAccessor raw, float[] f, int[] in, int light,
                                   float m00, float m01, float m02, float m10, float m11, float m12, float m20, float m21, float m22,
                                   float m30, float m31, float m32, VertexConsumer b) {
      int provided = (run.hasColor ? 1 << 1 : 0) | (run.hasUv ? 1 << 2 : 0) | (run.hasUv1 ? 1 << 3 : 0) | (run.hasLight ? 1 << 4 : 0);
      if (raw.vb$initialElementsToFill() != provided) {
         return false;
      }
      com.mojang.blaze3d.vertex.VertexFormatElement[] el = raw.vb$elements();
      if (run.hasColor && el[1].format().blockSize() != 4 || run.hasUv && el[2].format().blockSize() != 8
         || run.hasUv1 && el[3].format().blockSize() != 4 || run.hasLight && el[4].format().blockSize() != 4) {
         return false;
      }
      long colorOff = run.hasColor ? el[1].offset() : 0, uvOff = run.hasUv ? el[2].offset() : 0;
      long uv1Off = run.hasUv1 ? el[3].offset() : 0, lightOff = run.hasLight ? el[4].offset() : 0;
      for (int v = 0, n = run.vertices(); v < n; v++) {
         int fo = v * 5, io = v * 2;
         float x = f[fo], y = f[fo + 1], z = f[fo + 2];
         b.addVertex(m00 * x + m10 * y + m20 * z + m30, m01 * x + m11 * y + m21 * z + m31, m02 * x + m12 * y + m22 * z + m32);
         long p = raw.vb$vertexPointer();
         if (run.hasColor) {
            org.lwjgl.system.MemoryUtil.memPutInt(p + colorOff, net.minecraft.util.ARGB.toABGR(in[io]));
         }
         if (run.hasUv) {
            org.lwjgl.system.MemoryUtil.memPutFloat(p + uvOff, f[fo + 3]);
            org.lwjgl.system.MemoryUtil.memPutFloat(p + uvOff + 4, f[fo + 4]);
         }
         if (run.hasUv1) {
            org.lwjgl.system.MemoryUtil.memPutInt(p + uv1Off, in[io + 1]);
         }
         if (run.hasLight) {
            org.lwjgl.system.MemoryUtil.memPutInt(p + lightOff, light);
         }
         raw.vb$setElementsToFill(0);
      }
      return true;
   }
   /** Once per frame (render thread): drop entries not used for a while. */
   public static void onFrame() {
      if (++frame % 60 == 0 && !CACHE.isEmpty()) {
         long limit = frame - EXPIRE_FRAMES;
         for (Iterator<Entry> it = CACHE.values().iterator(); it.hasNext(); ) {
            if (it.next().lastFrame < limit) {
               it.remove();
            }
         }
      }
   }

   /** Captures what the glyph code writes, in local space. */
   private static final class Recorder implements VertexConsumer {
      final List<Run> runs = new ArrayList<>();
      Run current;
      boolean failed;

      void begin(RenderType renderType) {
         if (this.current == null || this.current.renderType != renderType) {
            this.current = new Run(renderType);
            this.runs.add(this.current);
         }
      }

      @Override
      public VertexConsumer addVertex(float x, float y, float z) {
         Run r = this.current;
         r.f.add(x);
         r.f.add(y);
         r.f.add(z);
         r.f.add(0.0F);
         r.f.add(0.0F);
         r.i.add(-1);
         r.i.add(0);
         return this;
      }

      private int last() {
         return this.current.vertices() - 1;
      }

      @Override
      public VertexConsumer setColor(int r, int g, int b, int a) {
         return this.setColor((a & 0xFF) << 24 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | b & 0xFF);
      }

      @Override
      public VertexConsumer setColor(int color) {
         this.current.hasColor = true;
         this.current.i.set(this.last() * 2, color);
         return this;
      }

      @Override
      public VertexConsumer setUv(float u, float v) {
         this.current.hasUv = true;
         int o = this.last() * 5;
         this.current.f.set(o + 3, u);
         this.current.f.set(o + 4, v);
         return this;
      }

      @Override
      public VertexConsumer setUv1(int u, int v) {
         this.current.hasUv1 = true;
         this.current.i.set(this.last() * 2 + 1, u & 0xFFFF | v << 16);
         return this;
      }

      @Override
      public VertexConsumer setUv2(int u, int v) {
         this.current.hasLight = true; // replaced by the tag's light on replay
         return this;
      }

      @Override
      public VertexConsumer setNormal(float x, float y, float z) {
         this.failed = true;
         return this;
      }

      @Override
      public VertexConsumer setLineWidth(float width) {
         this.failed = true;
         return this;
      }
   }
}