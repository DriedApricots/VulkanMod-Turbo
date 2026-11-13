package net.vulkanmod.boost;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.PrimitiveTopology;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.vulkanmod.Initializer;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * VulkanMod Turbo: the F3 text (often 60+ lines, thousands of glyph quads plus their backgrounds) was rebuilt from scratch
 * every frame. It is now drawn into a texture only when its content changes (at most 20 times per second, see
 * DebugScreenOverlayM) and composited with a single quad in all other frames.
 * Uses vanilla's picture-in-picture path, so the result is blitted in the same GUI layer the F3 text used to occupy.
 * Layout and colours replicate DebugScreenOverlay.extractLines exactly.
 */
public final class F3TextRenderer extends PictureInPictureRenderer<F3TextRenderer.State> {
   private static final int LINE_HEIGHT = 9;
   private static final int BACKGROUND_COLOR = -1873784752;
   private static final int TEXT_COLOR = -2039584;
   private static final int FULL_BRIGHT = 15728880;

   /** True once Fabric created an instance for the GUI renderer; until then the vanilla per-frame path is used. */
   public static volatile boolean active;

   private final Projection projection = new Projection();
   private final ProjectionMatrixBuffer projectionBuffer = new ProjectionMatrixBuffer("VulkanMod Turbo F3");
   private long renderedVersion = -1L;

   public F3TextRenderer() {
      active = true;
   }

   public record State(int x0, int y0, int x1, int y1, long version, List<String> left, List<String> right) implements PictureInPictureRenderState {
      @Override
      public float scale() {
         return 1.0F;
      }

      @Override
      public @Nullable ScreenRectangle scissorArea() {
         return null;
      }

      @Override
      public @Nullable ScreenRectangle bounds() {
         return PictureInPictureRenderState.getBounds(this.x0, this.y0, this.x1, this.y1, null);
      }
   }

   @Override
   public Class<State> getRenderStateClass() {
      return State.class;
   }

   @Override
   protected String getTextureLabel() {
      return "vulkanmodturbo_f3";
   }

   @Override
   protected boolean textureIsReadyToBlit(State state) {
      return state.version() == this.renderedVersion;
   }

   @Override
   protected void renderToTexture(State state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector) {
      GpuTextureView target = RenderSystem.outputColorTextureOverride;
      if (target == null) {
         return;
      }

      this.renderedVersion = state.version();
      int guiWidth = state.x1() - state.x0();
      int guiHeight = state.y1() - state.y0();
      Font font = Minecraft.getInstance().font;

      // 1) build meshes: all backgrounds first (vanilla draws them below the text), then glyphs grouped by pipeline + atlas page
      List<Batch> batches = new ArrayList<>();
      Batch fills = new Batch(RenderPipelines.GUI, null);
      Map<Object, Batch> glyphBatches = new LinkedHashMap<>();
      Matrix4f identity = new Matrix4f();
      try {
         addLines(font, state.left(), true, guiWidth, fills, glyphBatches, identity);
         addLines(font, state.right(), false, guiWidth, fills, glyphBatches, identity);
         batches.add(fills);
         batches.addAll(glyphBatches.values());

         // 2) upload (must happen outside of a render pass)
         GpuDevice device = RenderSystem.getDevice();
         int maxIndices = 0;
         for (Batch b : batches) {
            b.upload(device);
            maxIndices = Math.max(maxIndices, b.indexCount);
         }

         if (maxIndices == 0) {
            return;
         }

         RenderSystem.AutoStorageIndexBuffer autoIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
         GpuBuffer indexBuffer = autoIndices.getBuffer(maxIndices);
         this.projection.setupOrtho(-1000.0F, 1000.0F, guiWidth, guiHeight, true);
         RenderSystem.setProjectionMatrix(this.projectionBuffer.getBuffer(this.projection), ProjectionType.ORTHOGRAPHIC);
         GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f());
         GpuTextureView lightmap = Minecraft.getInstance().gameRenderer.lightmap();

         // 3) draw
         try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "VulkanMod Turbo F3 text", target, Optional.empty(), null, OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            for (Batch b : batches) {
               if (b.vertexBuffer == null) {
                  continue;
               }

               pass.setPipeline(b.pipeline);
               pass.setVertexBuffer(0, b.vertexBuffer.slice());
               if (b.texture != null) {
                  pass.bindTexture("Sampler0", b.texture, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                  pass.bindTexture("Sampler2", lightmap, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
               }

               pass.setIndexBuffer(indexBuffer, autoIndices.type());
               pass.drawIndexed(b.indexCount, 1, 0, 0, 0);
            }
         }
      } catch (Throwable t) {
         Initializer.LOGGER.error("F3 text cache failed, falling back to vanilla F3 rendering", t);
         active = false;
      } finally {
         for (Batch b : batches) {
            b.close();
         }
      }
   }

   private static void addLines(Font font, List<String> lines, boolean alignLeft, int guiWidth, Batch fills, Map<Object, Batch> glyphBatches, Matrix4f identity) {
      for (int i = 0; i < lines.size(); i++) {
         String line = lines.get(i);
         if (line == null || line.isEmpty()) {
            continue;
         }

         int width = font.width(line);
         int left = alignLeft ? 2 : guiWidth - 2 - width;
         int top = 2 + LINE_HEIGHT * i;
         fills.quad(left - 1, top - 1, left + width + 1, top + LINE_HEIGHT - 1, BACKGROUND_COLOR);
         font.prepareText(Language.getInstance().getVisualOrder(FormattedText.of(line)), left, top, TEXT_COLOR, false, false, 0)
            .visit(new Font.GlyphVisitor() {
               @Override
               public void acceptRenderable(TextRenderable renderable) {
                  RenderPipeline pipeline = renderable.guiPipeline();
                  GpuTextureView texture = renderable.textureView();
                  Batch batch = glyphBatches.computeIfAbsent(List.of(pipeline, texture), k -> new Batch(pipeline, texture));
                  batch.vertices += 4;
                  renderable.render(identity, batch.builder(), FULL_BRIGHT, true);
               }
            });
      }
   }

   @Override
   public void close() {
      super.close();
      this.projectionBuffer.close();
   }

   private static final class Batch {
      final RenderPipeline pipeline;
      final @Nullable GpuTextureView texture;
      private @Nullable ByteBufferBuilder memory;
      private @Nullable BufferBuilder builder;
      int vertices;
      int indexCount;
      @Nullable GpuBuffer vertexBuffer;

      Batch(RenderPipeline pipeline, @Nullable GpuTextureView texture) {
         this.pipeline = pipeline;
         this.texture = texture;
      }

      BufferBuilder builder() {
         if (this.builder == null) {
            this.memory = new ByteBufferBuilder(8192);
            this.builder = new BufferBuilder(this.memory, PrimitiveTopology.QUADS, this.pipeline.getVertexFormatBinding(0));
         }
         return this.builder;
      }

      void quad(int x0, int y0, int x1, int y1, int color) {
         BufferBuilder b = this.builder();
         b.addVertex(x0, y0, 0.0F).setColor(color);
         b.addVertex(x0, y1, 0.0F).setColor(color);
         b.addVertex(x1, y1, 0.0F).setColor(color);
         b.addVertex(x1, y0, 0.0F).setColor(color);
         this.vertices += 4;
      }

      void upload(GpuDevice device) {
         if (this.builder == null || this.vertices == 0) {
            return;
         }

         try (MeshData mesh = this.builder.buildOrThrow()) {
            this.indexCount = mesh.drawState().indexCount();
            this.vertexBuffer = device.createBuffer(() -> "VulkanMod Turbo F3 vertices", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
         }
      }

      void close() {
         if (this.vertexBuffer != null) {
            this.vertexBuffer.close();
         }
         if (this.memory != null) {
            this.memory.close();
         }
      }
   }
}
