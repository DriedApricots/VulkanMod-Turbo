package net.vulkanmod.render.vertex;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

/**
 * 26.2 port: vertex elements are (name, offset, GpuFormat); component type / count / normalisation now live in GpuFormat.
 */
public class CustomVertexFormat {
   private static float POSITION_OFFSET = 4.0F;

   /** VulkanMod's compressed terrain vertex: int16 x4 position, uint16 x2 UV0, packed uint32 colour. */
   public static final VertexFormat COMPRESSED_TERRAIN = VertexFormat.builder(0)
      .addAttribute("Position", GpuFormat.RGBA16_SINT)
      .addAttribute("UV0", GpuFormat.RG16_UINT)
      .addAttribute("Color", GpuFormat.R32_UINT)
      .build();

   /** Uncompressed terrain vertex (vanilla BLOCK + normal; RGBA8_SNORM normal also covers the old padding byte). */
   public static final VertexFormat TERRAIN = VertexFormat.builder(0)
      .addAttribute("Position", GpuFormat.RGB32_FLOAT)
      .addAttribute("Color", GpuFormat.RGBA8_UNORM)
      .addAttribute("UV0", GpuFormat.RG32_FLOAT)
      .addAttribute("UV2", GpuFormat.RG16_SINT)
      .addAttribute("Normal", GpuFormat.RGBA8_SNORM)
      .build();

   public static final VertexFormat NONE = VertexFormat.builder(0).build();

   public static void setPositionOffset(float positionOffset) {
      POSITION_OFFSET = positionOffset;
   }

   public static float getPositionOffset() {
      return POSITION_OFFSET;
   }
}
