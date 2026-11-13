package net.vulkanmod.render.model;

import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

public class CubeModel {
   private CubeModel.Polygon[] polygons;
   public float minX;
   public float minY;
   public float minZ;
   public float maxX;
   public float maxY;
   public float maxZ;
   CubeModel.Vertex[] vertices;

   public void setVertices(
      int u,
      int v,
      float minX,
      float minY,
      float minZ,
      float dimX,
      float dimY,
      float dimZ,
      float growX,
      float growY,
      float growZ,
      boolean mirror,
      float uTexScale,
      float vTexScale,
      Set<Direction> set
   ) {
      this.minX = minX;
      this.minY = minY;
      this.minZ = minZ;
      this.maxX = minX + dimX;
      this.maxY = minY + dimY;
      this.maxZ = minZ + dimZ;
      this.polygons = new CubeModel.Polygon[set.size()];
      float s = this.maxX;
      float t = this.maxY;
      float u1 = this.maxZ;
      minX -= growX;
      minY -= growY;
      minZ -= growZ;
      s += growX;
      t += growY;
      u1 += growZ;
      if (mirror) {
         float v1 = s;
         s = minX;
         minX = v1;
      }

      this.vertices = new CubeModel.Vertex[]{
         new CubeModel.Vertex(minX, minY, minZ, 0.0F, 0.0F),
         new CubeModel.Vertex(s, minY, minZ, 0.0F, 8.0F),
         new CubeModel.Vertex(s, t, minZ, 8.0F, 8.0F),
         new CubeModel.Vertex(minX, t, minZ, 8.0F, 0.0F),
         new CubeModel.Vertex(minX, minY, u1, 0.0F, 0.0F),
         new CubeModel.Vertex(s, minY, u1, 0.0F, 8.0F),
         new CubeModel.Vertex(s, t, u1, 8.0F, 8.0F),
         new CubeModel.Vertex(minX, t, u1, 8.0F, 0.0F)
      };
      float w = u;
      float x = u + dimZ;
      float y = u + dimZ + dimX;
      float z = u + dimZ + dimX + dimX;
      float aa = u + dimZ + dimX + dimZ;
      float ab = u + dimZ + dimX + dimZ + dimX;
      float ac = v;
      float ad = v + dimZ;
      float ae = v + dimZ + dimY;
      CubeModel.Vertex vertex1 = this.vertices[0];
      CubeModel.Vertex vertex2 = this.vertices[1];
      CubeModel.Vertex vertex3 = this.vertices[2];
      CubeModel.Vertex vertex4 = this.vertices[3];
      CubeModel.Vertex vertex5 = this.vertices[4];
      CubeModel.Vertex vertex6 = this.vertices[5];
      CubeModel.Vertex vertex7 = this.vertices[6];
      CubeModel.Vertex vertex8 = this.vertices[7];
      int idx = 0;
      if (set.contains(Direction.DOWN)) {
         this.polygons[idx++] = new CubeModel.Polygon(
            new CubeModel.Vertex[]{vertex6, vertex5, vertex1, vertex2}, x, ac, y, ad, uTexScale, vTexScale, mirror, Direction.DOWN
         );
      }

      if (set.contains(Direction.UP)) {
         this.polygons[idx++] = new CubeModel.Polygon(
            new CubeModel.Vertex[]{vertex3, vertex4, vertex8, vertex7}, y, ad, z, ac, uTexScale, vTexScale, mirror, Direction.UP
         );
      }

      if (set.contains(Direction.WEST)) {
         this.polygons[idx++] = new CubeModel.Polygon(
            new CubeModel.Vertex[]{vertex1, vertex5, vertex8, vertex4}, w, ad, x, ae, uTexScale, vTexScale, mirror, Direction.WEST
         );
      }

      if (set.contains(Direction.NORTH)) {
         this.polygons[idx++] = new CubeModel.Polygon(
            new CubeModel.Vertex[]{vertex2, vertex1, vertex4, vertex3}, x, ad, y, ae, uTexScale, vTexScale, mirror, Direction.NORTH
         );
      }

      if (set.contains(Direction.EAST)) {
         this.polygons[idx++] = new CubeModel.Polygon(
            new CubeModel.Vertex[]{vertex6, vertex2, vertex3, vertex7}, y, ad, aa, ae, uTexScale, vTexScale, mirror, Direction.EAST
         );
      }

      if (set.contains(Direction.SOUTH)) {
         this.polygons[idx] = new CubeModel.Polygon(
            new CubeModel.Vertex[]{vertex5, vertex6, vertex7, vertex8}, aa, ad, ab, ae, uTexScale, vTexScale, mirror, Direction.SOUTH
         );
      }
   }

   public void transformVertices(Matrix4f matrix) {
      for (int i = 0; i < 8; i++) {
         CubeModel.Vertex vertex = this.vertices[i];
         vertex.pos.mulPosition(matrix, vertex.transformed);
      }
   }

   // ---- VulkanMod Turbo: flat, allocation-free render path -------------------------------------------------------------
   // Corner positions, per-polygon corner indices / UVs / normals are flattened into primitive arrays once, so a cube is
   // transformed with plain float math on locals instead of 8 Vector3f.mulPosition calls through object graphs.
   private float[] cornerPos;      // 8 * xyz (already / 16)
   private int[] polyCorner;       // polygons * 4
   private float[] polyUv;         // polygons * 4 * uv
   private float[] polyNormal;     // polygons * xyz
   private final float[] scratch = new float[24];

   private void flatten() {
      int n = this.polygons.length;
      float[] cp = new float[24];
      for (int i = 0; i < 8; i++) {
         Vector3f p = this.vertices[i].pos;
         cp[i * 3] = p.x;
         cp[i * 3 + 1] = p.y;
         cp[i * 3 + 2] = p.z;
      }

      int[] pc = new int[n * 4];
      float[] uv = new float[n * 8];
      float[] nm = new float[n * 3];
      for (int k = 0; k < n; k++) {
         CubeModel.Polygon polygon = this.polygons[k];
         Vector3fc normal = polygon.normal();
         nm[k * 3] = normal.x();
         nm[k * 3 + 1] = normal.y();
         nm[k * 3 + 2] = normal.z();
         for (int j = 0; j < 4; j++) {
            CubeModel.Vertex v = polygon.vertices()[j];
            int corner = 0;
            for (int i = 0; i < 8; i++) {
               if (this.vertices[i].pos == v.pos) {
                  corner = i;
                  break;
               }
            }
            pc[k * 4 + j] = corner;
            uv[k * 8 + j * 2] = v.u;
            uv[k * 8 + j * 2 + 1] = v.v;
         }
      }

      this.polyCorner = pc;
      this.polyUv = uv;
      this.polyNormal = nm;
      this.cornerPos = cp;
   }

   /** Emits this cube with the fast vertex path (same output as transformVertices + per-vertex writes). */
   public void renderFast(Matrix4f m, org.joml.Matrix3f nm, net.vulkanmod.interfaces.ExtendedVertexBuilder out, int color, int overlay, int light) {
      if (this.cornerPos == null) {
         this.flatten();
      }

      float m00 = m.m00(), m01 = m.m01(), m02 = m.m02();
      float m10 = m.m10(), m11 = m.m11(), m12 = m.m12();
      float m20 = m.m20(), m21 = m.m21(), m22 = m.m22();
      float m30 = m.m30(), m31 = m.m31(), m32 = m.m32();
      float[] cp = this.cornerPos;
      float[] t = this.scratch;
      for (int i = 0; i < 24; i += 3) {
         float x = cp[i], y = cp[i + 1], z = cp[i + 2];
         // Vector3f.mulPosition: column-major, w = 1
         t[i] = Math.fma(m00, x, Math.fma(m10, y, Math.fma(m20, z, m30)));
         t[i + 1] = Math.fma(m01, x, Math.fma(m11, y, Math.fma(m21, z, m31)));
         t[i + 2] = Math.fma(m02, x, Math.fma(m12, y, Math.fma(m22, z, m32)));
      }

      float n00 = nm.m00, n01 = nm.m01, n02 = nm.m02, n10 = nm.m10, n11 = nm.m11, n12 = nm.m12, n20 = nm.m20, n21 = nm.m21, n22 = nm.m22;
      int[] pc = this.polyCorner;
      float[] uv = this.polyUv;
      float[] pn = this.polyNormal;
      for (int k = 0, n = this.polygons.length; k < n; k++) {
         float px = pn[k * 3], py = pn[k * 3 + 1], pz = pn[k * 3 + 2];
         // Matrix3f.transform + Vector3f.normalize
         float nx = Math.fma(n00, px, Math.fma(n10, py, n20 * pz));
         float ny = Math.fma(n01, px, Math.fma(n11, py, n21 * pz));
         float nz = Math.fma(n02, px, Math.fma(n12, py, n22 * pz));
         float invLen = org.joml.Math.invsqrt(Math.fma(nx, nx, Math.fma(ny, ny, nz * nz)));
         int packedNormal = net.vulkanmod.render.vertex.format.I32_SNorm.packNormal(nx * invLen, ny * invLen, nz * invLen);
         for (int j = 0; j < 4; j++) {
            int c = pc[k * 4 + j] * 3;
            out.vertex(t[c], t[c + 1], t[c + 2], color, uv[k * 8 + j * 2], uv[k * 8 + j * 2 + 1], overlay, light, packedNormal);
         }
      }
   }

   public CubeModel.Polygon[] getPolygons() {
      return this.polygons;
   }

   public record Polygon(CubeModel.Vertex[] vertices, Vector3fc normal) {
      public Polygon(CubeModel.Vertex[] vertices, float u0, float v0, float u1, float v1, float uSize, float vSize, boolean mirror, Direction direction) {
         this(vertices, (mirror ? mirrorFacing(direction) : direction).getUnitVec3f());
         float l = 0.0F / uSize;
         float m = 0.0F / vSize;
         vertices[0] = vertices[0].remap(u1 / uSize - l, v0 / vSize + m);
         vertices[1] = vertices[1].remap(u0 / uSize + l, v0 / vSize + m);
         vertices[2] = vertices[2].remap(u0 / uSize + l, v1 / vSize - m);
         vertices[3] = vertices[3].remap(u1 / uSize - l, v1 / vSize - m);
         if (mirror) {
            int n = vertices.length;

            for (int o = 0; o < n / 2; o++) {
               CubeModel.Vertex vertex = vertices[o];
               vertices[o] = vertices[n - 1 - o];
               vertices[n - 1 - o] = vertex;
            }
         }
      }

      private static Direction mirrorFacing(Direction direction) {
         return direction.getAxis() == Axis.X ? direction.getOpposite() : direction;
      }
   }

   public static class Vertex {
      private static final float SCALE_FACTOR = 16.0F;
      final Vector3f pos;
      final Vector3f transformed;
      float u;
      float v;

      public Vertex(float x, float y, float z, float u, float v) {
         this.pos = new Vector3f(x / 16.0F, y / 16.0F, z / 16.0F);
         this.transformed = new Vector3f();
         this.u = u;
         this.v = v;
      }

      public Vertex(Vector3f pos, Vector3f transformed, float u, float v) {
         this.pos = pos;
         this.transformed = transformed;
         this.u = u;
         this.v = v;
      }

      CubeModel.Vertex remap(float u, float v) {
         return new CubeModel.Vertex(this.pos, this.transformed, u, v);
      }

      public Vector3f pos() {
         return this.transformed;
      }

      public float u() {
         return this.u;
      }

      public float v() {
         return this.v;
      }
   }
}
