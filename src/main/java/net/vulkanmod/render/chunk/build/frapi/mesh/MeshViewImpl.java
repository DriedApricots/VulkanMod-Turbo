package net.vulkanmod.render.chunk.build.frapi.mesh;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MeshView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import org.jetbrains.annotations.Range;

public class MeshViewImpl implements MeshView {
   private static final ThreadLocal<ObjectArrayList<QuadViewImpl>> CURSOR_POOLS = ThreadLocal.withInitial(ObjectArrayList::new);
   int[] data;
   int limit;

   MeshViewImpl() {
   }

   public @Range(from = 0L, to = 2147483647L) int size() {
      return this.limit / EncodingFormat.TOTAL_STRIDE;
   }

   public void forEach(Consumer<? super QuadView> action) {
      ObjectArrayList<QuadViewImpl> pool = CURSOR_POOLS.get();
      QuadViewImpl cursor;
      if (pool.isEmpty()) {
         cursor = new QuadViewImpl();
      } else {
         cursor = (QuadViewImpl)pool.pop();
      }

      this.forEach(action, cursor);
      pool.push(cursor);
   }

   <C extends QuadViewImpl> void forEach(Consumer<? super C> action, C cursor) {
      int limit = this.limit;
      int index = 0;

      for (cursor.data = this.data; index < limit; index += EncodingFormat.TOTAL_STRIDE) {
         cursor.baseIndex = index;
         cursor.load();
         action.accept(cursor);
      }

      cursor.data = null;
   }

   public void outputTo(QuadEmitter emitter) {
      if (!(emitter instanceof MutableQuadViewImpl)) {
         // VulkanBoost: foreign emitter (e.g. Indigo's feature renderers for 26.2 FRAPI submits) - copy through the public API
         this.forEach(quad -> copyToForeign(quad, emitter));
         return;
      }

      MutableQuadViewImpl e = (MutableQuadViewImpl)emitter;
      int[] data = this.data;
      int limit = this.limit;

      for (int index = 0; index < limit; index += EncodingFormat.TOTAL_STRIDE) {
         System.arraycopy(data, index, e.data, e.baseIndex, EncodingFormat.TOTAL_STRIDE);
         e.load();
         e.transformAndEmit();
      }

      e.clear();
   }

   /** Field-by-field copy of a quad into an emitter of another renderer implementation. */
   static void copyToForeign(QuadView quad, QuadEmitter out) {
      for (int i = 0; i < 4; i++) {
         out.pos(i, quad.x(i), quad.y(i), quad.z(i));
         out.color(i, quad.color(i));
         out.uv(i, quad.u(i), quad.v(i));
         out.lightmap(i, quad.lightmap(i));
         if (quad.hasNormal(i)) {
            out.normal(i, quad.normalX(i), quad.normalY(i), quad.normalZ(i));
         }
      }

      out.nominalFace(quad.nominalFace());
      out.cullFace(quad.cullFace());
      out.atlas(quad.atlas());
      out.chunkLayer(quad.chunkLayer());
      out.itemRenderType(quad.itemRenderType());
      out.emissive(quad.emissive());
      out.diffuseShade(quad.diffuseShade());
      out.ambientOcclusion(quad.ambientOcclusion());
      out.foilType(quad.foilType());
      out.shadeMode(quad.shadeMode());
      out.animated(quad.animated());
      out.tintIndex(quad.tintIndex());
      out.tag(quad.tag());
      out.emit();
   }
}
