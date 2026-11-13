package net.vulkanmod.render.engine;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.systems.RenderPass.Draw;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.systems.GpuQueryPool;
import java.nio.IntBuffer;
import org.lwjgl.PointerBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.SharedConstants;
import net.vulkanmod.interfaces.shader.ExtendedRenderPipeline;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public class VkRenderPass implements RenderPassBackend {
   protected static final int MAX_VERTEX_BUFFERS = 1;
   public static final boolean VALIDATION = SharedConstants.IS_RUNNING_IN_IDE;
   private final VkCommandEncoder encoder;
   private final boolean hasDepthTexture;
   private boolean closed;
   @Nullable
   protected RenderPipeline pipeline;
   protected final GpuBufferSlice[] vertexBuffers = new GpuBufferSlice[1];   // 26.2: bound as slices
   @Nullable
   protected GpuBuffer indexBuffer;
   protected IndexType indexType = IndexType.INT;
   private final ScissorState scissorState = new ScissorState();
   protected final HashMap<String, GpuBufferSlice> uniforms = new HashMap<>();
   protected final HashMap<String, VkRenderPass.TextureViewAndSampler> samplers = new HashMap<>();
   protected final Set<String> dirtyUniforms = new HashSet<>();
   protected int pushedDebugGroups;
   private final boolean autoManaged;

   public VkRenderPass(VkCommandEncoder commandEncoder, boolean hasDepthTexture, boolean autoManaged) {
      this.encoder = commandEncoder;
      this.hasDepthTexture = hasDepthTexture;
      this.autoManaged = autoManaged;
   }

   public boolean hasDepthTexture() {
      return this.hasDepthTexture;
   }

   public void pushDebugGroup(Supplier<String> supplier) {
      if (this.closed) {
         throw new IllegalStateException("Can't use a closed render pass");
      }

      this.pushedDebugGroups++;
   }

   public void popDebugGroup() {
      if (this.closed) {
         throw new IllegalStateException("Can't use a closed render pass");
      }

      if (this.pushedDebugGroups == 0) {
         throw new IllegalStateException("Can't pop more debug groups than was pushed!");
      }

      this.pushedDebugGroups--;
   }

   public void setPipeline(RenderPipeline renderPipeline) {
      if (this.pipeline == null || this.pipeline != renderPipeline) {
         this.dirtyUniforms.addAll(this.uniforms.keySet());
      }

      this.pipeline = renderPipeline;
      if (ExtendedRenderPipeline.of(renderPipeline).getPipeline() == null) {
         this.encoder.getDevice().compilePipeline(renderPipeline);
      }
   }

   public void bindTexture(String string, @Nullable GpuTextureView gpuTextureView, @Nullable GpuSampler gpuSampler) {
      if (gpuSampler == null) {
         this.samplers.remove(string);
      } else {
         this.samplers.put(string, new VkRenderPass.TextureViewAndSampler((VkTextureView)gpuTextureView, (VkSampler)gpuSampler));
      }

      this.dirtyUniforms.add(string);
   }

   public void setUniform(String string, GpuBuffer gpuBuffer) {
      this.uniforms.put(string, gpuBuffer.slice());
      this.dirtyUniforms.add(string);
   }

   public void setUniform(String string, GpuBufferSlice gpuBufferSlice) {
      int i = this.encoder.getDevice().getUniformOffsetAlignment();
      if (gpuBufferSlice.offset() % i > 0L) {
         throw new IllegalArgumentException("Uniform buffer offset must be aligned to " + i);
      }

      this.uniforms.put(string, gpuBufferSlice);
      this.dirtyUniforms.add(string);
   }

   public void enableScissor(int i, int j, int k, int l) {
      this.scissorState.enable(i, j, k, l);
   }

   public void disableScissor() {
      this.scissorState.disable();
   }

   public boolean isScissorEnabled() {
      return this.scissorState.enabled();
   }

   public int getScissorX() {
      return this.scissorState.x();
   }

   public int getScissorY() {
      return this.scissorState.y();
   }

   public int getScissorWidth() {
      return this.scissorState.width();
   }

   public int getScissorHeight() {
      return this.scissorState.height();
   }

   public ScissorState getScissorState() {
      return this.scissorState;
   }

   public void setVertexBuffer(int i, @Nullable GpuBufferSlice slice) {
      if (i >= 0 && i < 1) {
         this.vertexBuffers[i] = slice;
      } else {
         throw new IllegalArgumentException("Vertex buffer slot is out of range: " + i);
      }
   }

   public void setIndexBuffer(@Nullable GpuBuffer gpuBuffer, IndexType indexType) {
      this.indexBuffer = gpuBuffer;
      this.indexType = indexType;
   }

   // 26.2 parameter order: (indexCount, instanceCount, firstIndex, vertexOffset, firstInstance)
   public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
      if (this.closed) {
         throw new IllegalStateException("Can't use a closed render pass");
      }

      this.encoder.executeDraw(this, vertexOffset, firstIndex, indexCount, this.indexType, instanceCount);
   }

   // Multi-draw / indirect paths: VkGpuDevice reports these features as unsupported, so vanilla never calls them.
   public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
      throw new UnsupportedOperationException("multiDrawIndexed is not supported by VulkanMod");
   }

   public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
      throw new UnsupportedOperationException("multiDrawIndexed is not supported by VulkanMod");
   }

   public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
      throw new UnsupportedOperationException("drawIndexedIndirect is not supported by VulkanMod");
   }

   public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
      throw new UnsupportedOperationException("multiDraw is not supported by VulkanMod");
   }

   public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
      throw new UnsupportedOperationException("multiDraw is not supported by VulkanMod");
   }

   public void drawIndirect(GpuBufferSlice commands, int drawCount) {
      throw new UnsupportedOperationException("drawIndirect is not supported by VulkanMod");
   }

   public void writeTimestamp(GpuQueryPool queryPool, int index) {
   }

   public <T> void drawMultipleIndexed(
      Collection<Draw<T>> collection, @Nullable GpuBuffer gpuBuffer, @Nullable IndexType indexType, Collection<String> collection2, T object
   ) {
      if (this.closed) {
         throw new IllegalStateException("Can't use a closed render pass");
      }

      this.encoder.executeDrawMultiple(this, collection, gpuBuffer, indexType, collection2, object);
   }

   // 26.2 parameter order: (vertexCount, instanceCount, firstVertex, firstInstance)
   public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
      if (this.closed) {
         throw new IllegalStateException("Can't use a closed render pass");
      }

      this.encoder.executeDraw(this, firstVertex, 0, vertexCount, null, instanceCount);
   }

   public void close() {
      if (!this.closed) {
         if (this.pushedDebugGroups > 0) {
            throw new IllegalStateException("Render pass had debug groups left open!");
         }

         this.closed = true;
         this.encoder.finishRenderPass(!this.autoManaged);
      }
   }

   public boolean isClosed() {
      return this.closed;
   }

   @Nullable
   public RenderPipeline getPipeline() {
      return this.pipeline;
   }

   @Environment(EnvType.CLIENT)
   protected record TextureViewAndSampler(VkTextureView view, VkSampler sampler) {
   }
}
