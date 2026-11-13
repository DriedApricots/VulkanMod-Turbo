package net.vulkanmod.boost;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.vulkanmod.Initializer;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/**
 * VulkanBoost: closed chests (regular, trapped, ender, copper; single and double) were rebuilt vertex by vertex every
 * frame through the entity model pipeline. Closed, not-being-broken chests are instead baked once per chunk section
 * into a GPU buffer and drawn with one draw call per section and render type, using vanilla's own render type
 * (same shader, textures, lighting, fog). The bake inputs (position, facing, type, material, light, model, sprite) are
 * compared every frame, so any change - opening, light, placement, texture reload - re-bakes the section, and an
 * opening or breaking chest is rendered by vanilla in the meantime (lid animation and crack overlay unchanged).
 */
public final class StaticChests {
   /** One chest as vanilla would submit it. */
   public record Chest(BlockPos pos, Model<Float> model, RenderType renderType, TextureAtlasSprite sprite, com.mojang.math.Transformation localPose, int light, long signature) {}

   private static final int EXPIRE_FRAMES = 300;
   private static final Long2ObjectOpenHashMap<Section> SECTIONS = new Long2ObjectOpenHashMap<>();
   private static long frame;

   private StaticChests() {
   }

   public static boolean enabled() {
      return Initializer.CONFIG.staticChests;
   }

   /** Called from ChestRenderer.submit for a static chest that vanilla will not render this frame. */
   public static void submit(Chest chest) {
      long key = SectionPos.asLong(chest.pos());
      Section section = SECTIONS.get(key);
      if (section == null) {
         section = new Section(key);
         SECTIONS.put(key, section);
      }

      if (section.frame != frame) {
         section.frame = frame;
         section.current.clear();
         section.currentSignature.clear();
      }

      section.current.add(chest);
      section.currentSignature.add(chest.signature());
   }

   /** Draws all sections that submitted chests this frame. Called right before vanilla's solid features. */
   public static void draw(double camX, double camY, double camZ) {
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

   public static void clear() {
      for (Section section : SECTIONS.values()) {
         section.close();
      }
      SECTIONS.clear();
   }

   private static final class Batch {
      final RenderType renderType;
      final GpuBuffer vertexBuffer;
      final int indexCount;

      Batch(RenderType renderType, GpuBuffer vertexBuffer, int indexCount) {
         this.renderType = renderType;
         this.vertexBuffer = vertexBuffer;
         this.indexCount = indexCount;
      }
   }

   private static final class Section {
      final int originX, originY, originZ;
      final List<Chest> current = new ArrayList<>();
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
         HitchLog.chestRebuilds++;
         this.close();
         Map<RenderType, List<Chest>> byType = new IdentityHashMap<>();
         for (Chest chest : this.current) {
            byType.computeIfAbsent(chest.renderType(), k -> new ArrayList<>()).add(chest);
         }

         PoseStack poseStack = new PoseStack();
         for (Map.Entry<RenderType, List<Chest>> entry : byType.entrySet()) {
            RenderType renderType = entry.getKey();
            try (ByteBufferBuilder memory = new ByteBufferBuilder(entry.getValue().size() * 72 * renderType.format().getVertexSize() + 256)) {
               BufferBuilder builder = new BufferBuilder(memory, renderType.primitiveTopology(), renderType.format());
               for (Chest chest : entry.getValue()) {
                  poseStack.pushPose();
                  BlockPos pos = chest.pos();
                  poseStack.translate(pos.getX() - this.originX, pos.getY() - this.originY, pos.getZ() - this.originZ);
                  poseStack.mulPose(chest.localPose());
                  Model<Float> model = chest.model();
                  model.setupAnim(0.0F);
                  model.renderToBuffer(poseStack, chest.sprite().wrap(builder), chest.light(), OverlayTexture.NO_OVERLAY, -1);
                  poseStack.popPose();
               }

               MeshData mesh = builder.build();
               if (mesh != null) {
                  try (mesh) {
                     GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "VulkanMod+ static chests", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
                     this.batches.add(new Batch(renderType, buffer, mesh.drawState().indexCount()));
                  }
               }
            }
         }

         this.builtSignature.clear();
         this.builtSignature.addAll(this.currentSignature);
      }

      void close() {
         for (Batch batch : this.batches) {
            batch.vertexBuffer.close();
         }
         this.batches.clear();
         this.builtSignature.clear();
      }
   }
}
