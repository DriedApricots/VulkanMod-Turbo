package net.vulkanmod.mixin.boost;

import com.mojang.blaze3d.vertex.CompactVectorArray;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.vulkanmod.Initializer;
import net.vulkanmod.boost.FastSort;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * VulkanMod Turbo: translucent immediate geometry (name tags, text backgrounds, translucent entities...) is depth sorted
 * every frame with a comparator-driven merge sort. For the two standard sortings {@link FastSort} produces the identical
 * order with a primitive sort.
 */
@Mixin(MeshData.SortState.class)
public abstract class MeshSortStateM {
   @Redirect(
      method = {"buildSortedIndexBuffer", "writeSortedIndexBuffer"},
      at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexSorting;sort(Lcom/mojang/blaze3d/vertex/CompactVectorArray;)[I")
   )
   private int[] vb$fastSort(VertexSorting sorting, CompactVectorArray points) {
      boolean perspective = sorting == VertexSorting.DISTANCE_TO_ORIGIN;
      if (!Initializer.CONFIG.fastTranslucentSort || !perspective && sorting != VertexSorting.ORTHOGRAPHIC_Z) {
         return sorting.sort(points);
      }

      return FastSort.sort(points, perspective);
   }
}
