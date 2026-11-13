package net.vulkanmod.mixin.boost;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** VulkanMod Turbo: raw vertex writes for baked name tags (see NameTagBake). */
@Mixin(BufferBuilder.class)
public interface BufferBuilderAccessor {
   @Accessor("vertexPointer")
   long vb$vertexPointer();

   @Accessor("elementsToFill")
   void vb$setElementsToFill(int value);

   @Accessor("initialElementsToFill")
   int vb$initialElementsToFill();

   @Accessor("elements")
   VertexFormatElement[] vb$elements();
}