package net.vulkanmod.render.util;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;

/**
 * 26.2 port: vanilla removed Tesselator. This keeps the same tiny API for VulkanMod's immediate geometry
 * (clouds, blit quads): one growable ByteBufferBuilder shared by successive BufferBuilders, render thread only.
 */
public final class VkTesselator {
   private static final int INITIAL_SIZE = 786432;
   private static VkTesselator instance;
   private final ByteBufferBuilder buffer = new ByteBufferBuilder(INITIAL_SIZE);

   public static VkTesselator getInstance() {
      if (instance == null) {
         instance = new VkTesselator();
      }
      return instance;
   }

   public BufferBuilder begin(PrimitiveTopology topology, VertexFormat format) {
      return new BufferBuilder(this.buffer, topology, format);
   }

   public void clear() {
      this.buffer.clear();
   }
}
