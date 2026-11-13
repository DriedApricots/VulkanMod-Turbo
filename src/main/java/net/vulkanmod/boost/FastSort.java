package net.vulkanmod.boost;

import com.mojang.blaze3d.vertex.CompactVectorArray;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.util.Arrays;
import java.util.Random;

/**
 * Primitive replacement for VertexSorting.DISTANCE_TO_ORIGIN / ORTHOGRAPHIC_Z: packs (descending distance key, index)
 * into longs and sorts them with Arrays.sort. Same order as vanilla's stable comparator merge sort.
 */
public final class FastSort {
   private FastSort() {
   }

   public static int[] sort(CompactVectorArray points, boolean perspective) {
      int n = points.size();
      long[] packed = new long[n];
      for (int i = 0; i < n; i++) {
         float x = points.getX(i), y = points.getY(i), z = points.getZ(i);
         // same arithmetic as JOML Vector3f(0,0,0).distanceSquared(p) (dx = 0 - x ...) / ORTHOGRAPHIC_Z (-z)
         float dx = -x, dy = -y, dz = -z;
         float key = perspective ? org.joml.Math.fma(dx, dx, org.joml.Math.fma(dy, dy, dz * dz)) : -z;
         int bits = Float.floatToIntBits(key);
         int sortable = bits ^ ((bits >> 31) & 0x7fffffff); // ascending int order == Float.compare order
         packed[i] = ((long)~sortable << 32) | (i & 0xffffffffL); // ~ -> descending key; low bits -> stable by index
      }

      Arrays.sort(packed);
      int[] result = new int[n];
      for (int i = 0; i < n; i++) {
         result[i] = (int)packed[i];
      }

      return result;
   }

   /** Compares against vanilla on random data with many ties; used by the dev bench. */
   public static boolean selfTest() {
      Random r = new Random(42);
      for (int round = 0; round < 300; round++) {
         int n = 1 + r.nextInt(400);
         CompactVectorArray a = new CompactVectorArray(n);
         for (int i = 0; i < n; i++) {
            a.set(i, r.nextInt(7) - 3 + (r.nextBoolean() ? 0.5F : 0.0F), r.nextInt(5) - 2, r.nextBoolean() ? 0.0F : -0.0F + r.nextInt(9) - 4);
         }

         if (!Arrays.equals(VertexSorting.DISTANCE_TO_ORIGIN.sort(a), sort(a, true)) || !Arrays.equals(VertexSorting.ORTHOGRAPHIC_Z.sort(a), sort(a, false))) {
            return false;
         }
      }

      return true;
   }
}
