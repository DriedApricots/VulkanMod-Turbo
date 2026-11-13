package net.vulkanmod.mixin.render.vertex;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.vulkanmod.interfaces.ExtendedVertexBuilder;
import net.vulkanmod.render.vertex.format.I32_SNorm;
import net.vulkanmod.vulkan.util.MemoryAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(BufferBuilder.class)
public abstract class BufferBuilderM implements VertexConsumer, ExtendedVertexBuilder {
   // 26.2: attribute "semantic ids" used by BufferBuilder.beginElement(int)
   private static final int COLOR_ID = 1, UV0_ID = 2, UV1_ID = 3, UV2_ID = 4, NORMAL_ID = 5;
   @Shadow
   private VertexFormat format;
   @Shadow
   private int elementsToFill;
   @Shadow
   @Final
   private int initialElementsToFill;
   private long ptr;
   private final float[] brightness = new float[4];
   private final int[] lights = new int[4];

   @Shadow
   protected abstract long beginVertex();

   @Shadow
   protected abstract long beginElement(int semanticId);

   @Override
   public void vertex(float x, float y, float z, int packedColor, float u, float v, int overlay, int light, int packedNormal) {
      this.ptr = this.beginVertex();
      if (this.format == DefaultVertexFormat.ENTITY) {
         MemoryAccess.memPutFloat(this.ptr + 0L, x);
         MemoryAccess.memPutFloat(this.ptr + 4L, y);
         MemoryAccess.memPutFloat(this.ptr + 8L, z);
         MemoryAccess.memPutInt(this.ptr + 12L, packedColor);
         MemoryAccess.memPutFloat(this.ptr + 16L, u);
         MemoryAccess.memPutFloat(this.ptr + 20L, v);
         MemoryAccess.memPutInt(this.ptr + 24L, overlay);
         MemoryAccess.memPutInt(this.ptr + 28L, light);
         MemoryAccess.memPutInt(this.ptr + 32L, packedNormal);
      } else {
         this.elementsToFill = this.initialElementsToFill;
         this.position(x, y, z);
         this.fastColor(packedColor);
         this.fastUv(u, v);
         this.fastOverlay(overlay);
         this.light(light);
         this.fastNormal(packedNormal);
      }
   }

   @Override
   public void vertex(float x, float y, float z, float u, float v, int packedColor, int light) {
      this.ptr = this.beginVertex();
      MemoryAccess.memPutFloat(this.ptr + 0L, x);
      MemoryAccess.memPutFloat(this.ptr + 4L, y);
      MemoryAccess.memPutFloat(this.ptr + 8L, z);
      MemoryAccess.memPutFloat(this.ptr + 12L, u);
      MemoryAccess.memPutFloat(this.ptr + 16L, v);
      MemoryAccess.memPutInt(this.ptr + 20L, packedColor);
      MemoryAccess.memPutInt(this.ptr + 24L, light);
   }

   public void position(float x, float y, float z) {
      MemoryAccess.memPutFloat(this.ptr + 0L, x);
      MemoryAccess.memPutFloat(this.ptr + 4L, y);
      MemoryAccess.memPutFloat(this.ptr + 8L, z);
   }

   public void fastColor(int packedColor) {
      long ptr = this.beginElement(COLOR_ID);
      if (ptr != -1L) {
         MemoryAccess.memPutInt(ptr, packedColor);
      }
   }

   public void fastUv(float u, float v) {
      long ptr = this.beginElement(UV0_ID);
      if (ptr != -1L) {
         MemoryAccess.memPutFloat(ptr, u);
         MemoryAccess.memPutFloat(ptr + 4L, v);
      }
   }

   public void fastOverlay(int o) {
      long ptr = this.beginElement(UV1_ID);
      if (ptr != -1L) {
         MemoryAccess.memPutInt(ptr, o);
      }
   }

   public void light(int l) {
      long ptr = this.beginElement(UV2_ID);
      if (ptr != -1L) {
         MemoryAccess.memPutInt(ptr, l);
      }
   }

   public void fastNormal(int packedNormal) {
      long ptr = this.beginElement(NORMAL_ID);
      if (ptr != -1L) {
         MemoryAccess.memPutInt(ptr, packedNormal);
      }
   }

   // 26.2: vanilla BufferBuilder.addVertex / setColor already have block/entity fast paths with correct
   // ARGB->RGBA swizzling, so VulkanMod's former @Overwrite versions are dropped.
}
