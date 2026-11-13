package net.vulkanmod.render.chunk;

import com.google.common.collect.Sets;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.Zone;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.chunk.build.RenderRegionBuilder;
import net.vulkanmod.render.chunk.build.task.ChunkTask;
import net.vulkanmod.render.chunk.build.task.TaskDispatcher;
import net.vulkanmod.render.chunk.graph.SectionGraph;
import net.vulkanmod.render.chunk.util.StaticQueue;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.render.profiling.BuildTimeProfiler;
import net.vulkanmod.render.profiling.Profiler;
import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.memory.buffer.IndexBuffer;
import net.vulkanmod.vulkan.memory.buffer.IndirectBuffer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3d;

public class WorldRenderer {
   private static WorldRenderer INSTANCE;
   public RenderRegionBuilder renderRegionCache;
   private final Minecraft minecraft;
   private ClientLevel level;
   private int renderDistance;
   private final RenderBuffers renderBuffers;
   private final EntityRenderDispatcher entityRenderDispatcher;
   private final BlockEntityRenderDispatcher blockEntityRenderDispatcher;
   private final GameRenderState levelRenderState;
   private final FeatureRenderDispatcher featureRenderDispatcher;
   private float partialTick;
   private final Vector3d cameraPos = new Vector3d();
   private int lastCameraSectionX;
   private int lastCameraSectionY;
   private int lastCameraSectionZ;
   private float lastCameraX;
   private float lastCameraY;
   private float lastCameraZ;
   private float lastCamRotX;
   private float lastCamRotY;
   private SectionGrid sectionGrid;
   private SectionGraph sectionGraph;
   private boolean graphNeedsUpdate;
   private final Set<BlockEntity> globalBlockEntities = Sets.newHashSet();
   private final TaskDispatcher taskDispatcher;
   private double xTransparentOld;
   private double yTransparentOld;
   private double zTransparentOld;
   IndirectBuffer[] indirectBuffers;
   private long terrainSampler;
   private final List<Runnable> onAllChangedCallbacks = new ObjectArrayList();

   public static WorldRenderer init(
      EntityRenderDispatcher entityRenderDispatcher,
      BlockEntityRenderDispatcher blockEntityRenderDispatcher,
      RenderBuffers renderBuffers,
      GameRenderState levelRenderState,
      FeatureRenderDispatcher featureRenderDispatcher
   ) {
      return INSTANCE != null
         ? INSTANCE
         : (INSTANCE = new WorldRenderer(entityRenderDispatcher, blockEntityRenderDispatcher, renderBuffers, levelRenderState, featureRenderDispatcher));
   }

   private WorldRenderer(
      EntityRenderDispatcher entityRenderDispatcher,
      BlockEntityRenderDispatcher blockEntityRenderDispatcher,
      RenderBuffers renderBuffers,
      GameRenderState levelRenderState,
      FeatureRenderDispatcher featureRenderDispatcher
   ) {
      this.minecraft = Minecraft.getInstance();
      this.renderBuffers = renderBuffers;
      this.entityRenderDispatcher = entityRenderDispatcher;
      this.blockEntityRenderDispatcher = blockEntityRenderDispatcher;
      this.levelRenderState = levelRenderState;
      this.featureRenderDispatcher = featureRenderDispatcher;
      this.renderRegionCache = new RenderRegionBuilder();
      this.taskDispatcher = new TaskDispatcher();
      ChunkTask.setTaskDispatcher(this.taskDispatcher);
      this.allocateIndirectBuffers();
      TerrainRenderType.updateMapping();
      Renderer.getInstance().addOnResizeCallback(() -> {
         if (this.indirectBuffers.length != Renderer.getFramesNum()) {
            this.allocateIndirectBuffers();
         }
      });
   }

   private void allocateIndirectBuffers() {
      if (this.indirectBuffers != null) {
         Arrays.stream(this.indirectBuffers).forEach(Buffer::scheduleFree);
      }

      this.indirectBuffers = new IndirectBuffer[Renderer.getFramesNum()];

      for (int i = 0; i < this.indirectBuffers.length; i++) {
         this.indirectBuffers[i] = new IndirectBuffer(1000000, MemoryTypes.HOST_MEM);
      }
   }

   private void benchCallback() {
      BuildTimeProfiler.runBench(this.graphNeedsUpdate || !this.taskDispatcher.isIdle());
   }

   public void setupRenderer(Camera camera, Frustum frustum, boolean isCapturedFrustum, boolean spectator) {
      Profiler profiler = Profiler.getMainProfiler();
      profiler.push("Setup_Renderer");
      ProfilerFiller mcProfiler = net.minecraft.util.profiling.Profiler.get();
      this.benchCallback();
      Vec3 camPos = camera.position();
      this.cameraPos.set(camPos.x(), camPos.y(), camPos.z());
      if (this.minecraft.options.getEffectiveRenderDistance() != this.renderDistance) {
         this.allChanged();
      }

      mcProfiler.push("camera");
      float cameraX = (float)this.cameraPos.x();
      float cameraY = (float)this.cameraPos.y();
      float cameraZ = (float)this.cameraPos.z();
      int sectionX = SectionPos.posToSectionCoord(cameraX);
      int sectionY = SectionPos.posToSectionCoord(cameraY);
      int sectionZ = SectionPos.posToSectionCoord(cameraZ);
      profiler.push("reposition");
      if (this.lastCameraSectionX != sectionX || this.lastCameraSectionY != sectionY || this.lastCameraSectionZ != sectionZ) {
         this.lastCameraSectionX = sectionX;
         this.lastCameraSectionY = sectionY;
         this.lastCameraSectionZ = sectionZ;
         this.sectionGrid.repositionCamera(cameraX, cameraZ);
      }

      profiler.pop();
      double entityDistanceScaling = (Double)this.minecraft.options.entityDistanceScaling().get();
      Entity.setViewScale(Mth.clamp(this.renderDistance / 8.0, 1.0, 2.5) * entityDistanceScaling);
      mcProfiler.popPush("cull");
      mcProfiler.popPush("update");
      boolean cameraMoved = false;
      float d_xRot = Math.abs(camera.xRot() - this.lastCamRotX);
      float d_yRot = Math.abs(camera.yRot() - this.lastCamRotY);
      cameraMoved |= d_xRot > 2.0F || d_yRot > 2.0F;
      cameraMoved |= cameraX != this.lastCameraX || cameraY != this.lastCameraY || cameraZ != this.lastCameraZ;
      this.graphNeedsUpdate |= cameraMoved;
      if (!isCapturedFrustum && this.graphNeedsUpdate()) {
         this.graphNeedsUpdate = false;
         this.lastCameraX = cameraX;
         this.lastCameraY = cameraY;
         this.lastCameraZ = cameraZ;
         this.lastCamRotX = camera.xRot();
         this.lastCamRotY = camera.yRot();
         this.sectionGraph.update(camera, frustum, spectator);
      }

      this.sectionGraph.retryPendingRebuilds(); // VulkanMod Turbo: see SectionGraph.scheduleRebuilds

      this.indirectBuffers[Renderer.getCurrentFrame()].reset();
      mcProfiler.pop();
      profiler.pop();
   }

   public void uploadSections() {
      ProfilerFiller mcProfiler = net.minecraft.util.profiling.Profiler.get();
      mcProfiler.push("upload");
      Profiler profiler = Profiler.getMainProfiler();
      profiler.push("Uploads");

      try {
         if (this.taskDispatcher.updateSections()) {
            this.graphNeedsUpdate = true;
         }
      } catch (Exception e) {
         Initializer.LOGGER.error(e.getMessage());
         this.allChanged();
      }

      profiler.pop();
      mcProfiler.pop();
   }

   public boolean isSectionCompiled(BlockPos blockPos) {
      if (this.sectionGrid == null) {
         return false;
      }

      RenderSection renderSection = this.sectionGrid.getSectionAtBlockPos(blockPos);
      return renderSection != null && renderSection.isCompiled();
   }

   public void allChanged() {
      if (this.level != null) {
         this.level.clearTintCaches();
         this.renderRegionCache.clear();
         this.taskDispatcher.createThreads(Initializer.CONFIG.builderThreads);
         this.graphNeedsUpdate = true;
         this.renderDistance = this.minecraft.options.getEffectiveRenderDistance();
         if (this.sectionGrid != null) {
            this.sectionGrid.freeAllBuffers();
         }

         this.taskDispatcher.clearBatchQueue();
         synchronized (this.globalBlockEntities) {
            this.globalBlockEntities.clear();
         }

         this.sectionGrid = new SectionGrid(this.level, this.renderDistance);
         this.sectionGraph = new SectionGraph(this.level, this.sectionGrid, this.taskDispatcher);
         net.vulkanmod.render.chunk.occlusion.GpuOcclusionCuller.onAllChanged();
         this.onAllChangedCallbacks.forEach(Runnable::run);
         Entity entity = this.minecraft.getCameraEntity();
         if (entity != null) {
            this.sectionGrid.repositionCamera(entity.getX(), entity.getZ());
         }
      }
   }

   public void setLevel(@Nullable ClientLevel level) {
      this.lastCameraX = Float.MIN_VALUE;
      this.lastCameraY = Float.MIN_VALUE;
      this.lastCameraZ = Float.MIN_VALUE;
      this.lastCameraSectionX = Integer.MIN_VALUE;
      this.lastCameraSectionY = Integer.MIN_VALUE;
      this.lastCameraSectionZ = Integer.MIN_VALUE;
      this.level = level;
      ChunkStatusMap.createInstance(this.renderDistance);
      if (level != null) {
         this.allChanged();
      } else {
         if (this.sectionGrid != null) {
            this.sectionGrid.freeAllBuffers();
            this.sectionGrid = null;
         }

         this.taskDispatcher.stopThreads();
         this.graphNeedsUpdate = true;
      }
   }

   public void addOnAllChangedCallback(Runnable runnable) {
      this.onAllChangedCallbacks.add(runnable);
   }

   public void clearOnAllChangedCallbacks() {
      this.onAllChangedCallbacks.clear();
   }

   // ------------------------------------------------------------------ VulkanMod Turbo GPU occlusion culling
   private static final int MAX_LAYERS = TerrainRenderType.VALUES.length;
   /** per layer: areas drawn and their command ranges in the culled command buffer of this frame */
   private final ChunkArea[][] culledAreas = new ChunkArea[MAX_LAYERS][];
   private final int[][] culledFirst = new int[MAX_LAYERS][];
   private final int[][] culledCount = new int[MAX_LAYERS][];
   private final int[] culledAreaNum = new int[MAX_LAYERS];
   private int culledCmdTotal;
   private int culledBoxTotal;
   /** 0: nothing prepared this frame, 1: phase-0 commands ready, 2: phase-1 done (translucent may use the buffer) */
   private int cullStage;
   private long cullFrameMark = -1L;

   private boolean isLastOpaqueLayer(TerrainRenderType renderType) {
      return renderType == TerrainRenderType.CUTOUT;
   }

   private Set<TerrainRenderType> allowedRenderTypes() {
      return Initializer.CONFIG.uniqueOpaqueLayer ? TerrainRenderType.COMPACT_RENDER_TYPES : TerrainRenderType.SEMI_COMPACT_RENDER_TYPES;
   }

   /** writes the commands of all layers + the section boxes for this frame and runs compute phase 0 */
   private void prepareCulledFrame(net.vulkanmod.render.chunk.occlusion.GpuOcclusionCuller culler, double camX, double camY, double camZ) {
      ChunkAreaManager areaManager = this.sectionGrid.chunkAreaManager;
      culler.beginFrame(areaManager.size * ChunkAreaManager.AREA_SIZE);
      Set<TerrainRenderType> allowed = this.allowedRenderTypes();

      int areaCount = 0;
      int sectionCount = 0;
      for (Iterator<ChunkArea> it = this.sectionGraph.getChunkAreaQueue().iterator(false); it.hasNext(); ) {
         ChunkArea area = it.next();
         areaCount++;
         sectionCount += area.sectionQueue.size();
      }

      long cmdPtr = culler.reserveCommands(sectionCount * QuadFacingCount * allowed.size() + 1);
      int cmd = 0;
      float forceRadius = net.vulkanmod.render.chunk.occlusion.GpuOcclusionCuller.FORCE_RADIUS;

      for (TerrainRenderType type : TerrainRenderType.VALUES) {
         int l = type.ordinal();
         this.culledAreaNum[l] = 0;
         if (!allowed.contains(type)) {
            continue;
         }

         if (this.culledAreas[l] == null || this.culledAreas[l].length < areaCount) {
            int n = Math.max(areaCount, 64);
            this.culledAreas[l] = new ChunkArea[n];
            this.culledFirst[l] = new int[n];
            this.culledCount[l] = new int[n];
         }

         boolean translucent = type == TerrainRenderType.TRANSLUCENT;
         for (Iterator<ChunkArea> it = this.sectionGraph.getChunkAreaQueue().iterator(translucent); it.hasNext(); ) {
            ChunkArea area = it.next();
            DrawBuffers drawBuffers = area.drawBuffers;
            if (drawBuffers.getAreaBuffer(type) == null || area.sectionQueue.size() == 0) {
               continue;
            }

            int written = drawBuffers.writeCulledCommands(cmdPtr + (long) cmd * 32L, this.cameraPos, area.sectionQueue, type,
               area.index * ChunkAreaManager.AREA_SIZE, forceRadius);
            if (written > 0) {
               int k = this.culledAreaNum[l]++;
               this.culledAreas[l][k] = area;
               this.culledFirst[l][k] = cmd;
               this.culledCount[l][k] = written;
               cmd += written;
            }
         }
      }

      this.culledCmdTotal = cmd;

      long boxPtr = culler.reserveBoxes(sectionCount);
      int boxes = 0;
      for (Iterator<ChunkArea> it = this.sectionGraph.getChunkAreaQueue().iterator(false); it.hasNext(); ) {
         ChunkArea area = it.next();
         int idBase = area.index * ChunkAreaManager.AREA_SIZE;
         StaticQueue<RenderSection> queue = area.sectionQueue;
         for (int i = 0; i < queue.size(); i++) {
            RenderSection section = queue.get(i);
            double dx = this.cameraPos.x - section.xOffset, dy = this.cameraPos.y - section.yOffset, dz = this.cameraPos.z - section.zOffset;
            if (dx > -forceRadius && dx < 16 + forceRadius && dy > -forceRadius && dy < 16 + forceRadius && dz > -forceRadius && dz < 16 + forceRadius) {
               continue; // forced visible (same test as DrawBuffers.writeCulledCommands), no box test
            }
            long p = boxPtr + (long) boxes * 16L;
            org.lwjgl.system.MemoryUtil.memPutFloat(p, (float) (section.xOffset - camX));
            org.lwjgl.system.MemoryUtil.memPutFloat(p + 4L, (float) (section.yOffset - camY));
            org.lwjgl.system.MemoryUtil.memPutFloat(p + 8L, (float) (section.zOffset - camZ));
            org.lwjgl.system.MemoryUtil.memPutInt(p + 12L, idBase + section.inAreaIndex);
            boxes++;
         }
      }

      this.culledBoxTotal = boxes;

      Renderer renderer = Renderer.getInstance();
      renderer.endRenderPass();
      culler.dispatch(Renderer.getCommandBuffer(), 0, this.culledCmdTotal);
      this.cullStage = 1;
   }

   private static final int QuadFacingCount = net.vulkanmod.render.chunk.cull.QuadFacing.COUNT;

   private void drawCulledLayer(net.vulkanmod.render.chunk.occlusion.GpuOcclusionCuller culler, GraphicsPipeline pipeline, TerrainRenderType renderType,
                                double camX, double camY, double camZ, long currentTimeMs, int fadeTimeMs, float fadeTimeInv) {
      int l = renderType.ordinal();
      Renderer renderer = Renderer.getInstance();
      long bufferId = culler.cmdBuffer().getId();
      org.lwjgl.vulkan.VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
      for (int k = 0; k < this.culledAreaNum[l]; k++) {
         ChunkArea area = this.culledAreas[l][k];
         DrawBuffers drawBuffers = area.drawBuffers;
         if (drawBuffers.getAreaBuffer(renderType) == null) {
            continue;
         }
         drawBuffers.bindBuffers(commandBuffer, pipeline, renderType, camX, camY, camZ, currentTimeMs, fadeTimeMs, fadeTimeInv);
         renderer.uploadAndBindUBOs(pipeline);
         org.lwjgl.vulkan.VK10.vkCmdDrawIndexedIndirect(commandBuffer, bufferId, (long) this.culledFirst[l][k] * 32L, this.culledCount[l][k], 32);
      }
   }

   public void renderSectionLayer(TerrainRenderType renderType, double camX, double camY, double camZ, Matrix4f modelView, Matrix4f projection) {
      net.vulkanmod.render.chunk.occlusion.GpuOcclusionCuller culler = this.sectionGraph != null ? net.vulkanmod.render.chunk.occlusion.GpuOcclusionCuller.get() : null;
      if (culler == null) {
         this.cullStage = 0;
         this.renderSectionLayerLegacy(renderType, camX, camY, camZ, modelView, projection);
         return;
      }

      long frameMark = net.vulkanmod.render.engine.VkGpuBuffer.FRAME;
      if (frameMark != this.cullFrameMark) {
         this.cullFrameMark = frameMark;
         this.cullStage = 0;
      }

      boolean isTranslucent = renderType == TerrainRenderType.TRANSLUCENT;
      if (isTranslucent && this.cullStage != 2) {
         // opaque layers were not drawn through the culler this frame: nothing to rely on
         this.renderSectionLayerLegacy(renderType, camX, camY, camZ, modelView, projection);
         return;
      }

      if (!this.allowedRenderTypes().contains(renderType)) {
         if (!isTranslucent && this.isLastOpaqueLayer(renderType) && this.cullStage == 1) {
            this.finishOpaqueCulling(culler, camX, camY, camZ, modelView, projection);
         }
         return;
      }

      if (!isTranslucent && this.cullStage == 0) {
         this.sortTranslucentSections(camX, camY, camZ);
         this.prepareCulledFrame(culler, camX, camY, camZ);
      }

      if (!isTranslucent && this.cullStage != 1) {
         this.renderSectionLayerLegacy(renderType, camX, camY, camZ, modelView, projection);
         return;
      }

      ProfilerFiller mcProfiler = net.minecraft.util.profiling.Profiler.get();
      Zone zone = mcProfiler.zone(() -> "render_culled_" + renderType);
      this.drawCulledPass(culler, renderType, camX, camY, camZ, modelView, projection);

      if (!isTranslucent && this.isLastOpaqueLayer(renderType)) {
         this.finishOpaqueCulling(culler, camX, camY, camZ, modelView, projection);
      }

      zone.close();
   }

   /** box test against the depth of the phase-0 draws, compute phase 1, then draw the newly visible opaque sections */
   private void finishOpaqueCulling(net.vulkanmod.render.chunk.occlusion.GpuOcclusionCuller culler, double camX, double camY, double camZ,
                                    Matrix4f modelView, Matrix4f projection) {
      Renderer renderer = Renderer.getInstance();
      renderer.getMainPass().rebindMainTarget();
      VRenderSystem.applyMVP(modelView, projection);
      culler.drawBoxes(Renderer.getCommandBuffer(), this.culledBoxTotal);
      renderer.endRenderPass();
      culler.dispatch(Renderer.getCommandBuffer(), 1, this.culledCmdTotal);
      this.cullStage = 2;

      for (TerrainRenderType type : TerrainRenderType.VALUES) {
         if (type != TerrainRenderType.TRANSLUCENT && this.allowedRenderTypes().contains(type) && this.culledAreaNum[type.ordinal()] > 0) {
            this.drawCulledPass(culler, type, camX, camY, camZ, modelView, projection);
         }
      }
   }

   /** same state setup as the legacy path, then the culled indirect draws of this layer */
   private void drawCulledPass(net.vulkanmod.render.chunk.occlusion.GpuOcclusionCuller culler, TerrainRenderType renderType,
                               double camX, double camY, double camZ, Matrix4f modelView, Matrix4f projection) {
      Renderer.getInstance().getMainPass().rebindMainTarget();
      if (renderType != TerrainRenderType.TRANSLUCENT) {
         GlStateManager._disableBlend(0);
      } else {
         GlStateManager._enableBlend(0);
         VRenderSystem.blendFuncSeparate(770, 771, 1, 771);
      }

      VRenderSystem.enableCull();
      VRenderSystem.depthFunc(518);
      GlStateManager._enableDepthTest();
      GlStateManager._depthMask(true);
      GlStateManager._colorMask(15);
      GlStateManager._disablePolygonOffset();
      VRenderSystem.setPolygonModeGL(6914);
      VRenderSystem.applyMVP(modelView, projection);
      VRenderSystem.setPrimitiveTopologyGL(4);
      Renderer renderer = Renderer.getInstance();
      GraphicsPipeline pipeline = PipelineManager.getTerrainShader(renderType);
      renderer.bindGraphicsPipeline(pipeline);
      GpuTextureView texView = this.bindTerrainTextures(pipeline);
      VRenderSystem.setTextureSize(texView.getWidth(0), texView.getHeight(0));
      VRenderSystem.setCurrentTime((int)System.currentTimeMillis());
      long currentTimeMs = System.currentTimeMillis();
      float fadeTime = ((Double)Minecraft.getInstance().options.chunkSectionFadeInTime().get()).floatValue();
      int fadeTimeMs = (int)(fadeTime * 1000.0F);
      float fadeTimeInv = fadeTime > 0.0F ? 1.0F / (fadeTime * 1000.0F) : 1.0F;
      IndexBuffer indexBuffer = Renderer.getDrawer().getQuadsIndexBuffer().getIndexBuffer();
      Renderer.getDrawer().bindIndexBuffer(Renderer.getCommandBuffer(), indexBuffer, indexBuffer.indexType.value);
      renderType.setCutoutUniform();
      this.drawCulledLayer(culler, pipeline, renderType, camX, camY, camZ, currentTimeMs, fadeTimeMs, fadeTimeInv);
   }

   private GpuTextureView bindTerrainTextures(GraphicsPipeline pipeline) {
      TextureManager textureManager = Minecraft.getInstance().getTextureManager();
      AbstractTexture atlasTexture = textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS);
      GpuTextureView texView = atlasTexture.getTextureView();
      boolean useAnisotropy = this.minecraft.options.textureFiltering().get() == TextureFilteringMethod.ANISOTROPIC;
      int maxAnisotropy = this.minecraft.options.maxAnisotropyValue();
      VkGpuTexture texture = (VkGpuTexture)texView.texture();
      if (this.terrainSampler == 0L) {
         this.terrainSampler = SamplerManager.getSampler(true, false, texture.getVulkanImage().mipLevels - 1, useAnisotropy, maxAnisotropy);
      }

      texture.getVulkanImage().setSampler(this.terrainSampler);
      VRenderSystem.setShaderTexture(0, texView);
      VRenderSystem.setShaderTexture(2, Minecraft.getInstance().gameRenderer.lightmap());
      VTextureSelector.bindShaderTextures(pipeline);
      return texView;
   }

   private void renderSectionLayerLegacy(TerrainRenderType renderType, double camX, double camY, double camZ, Matrix4f modelView, Matrix4f projection) {
      Renderer.getInstance().getMainPass().rebindMainTarget();
      this.sortTranslucentSections(camX, camY, camZ);
      ProfilerFiller mcProfiler = net.minecraft.util.profiling.Profiler.get();
      Zone zone = mcProfiler.zone(() -> "render_" + renderType);
      boolean isTranslucent = renderType == TerrainRenderType.TRANSLUCENT;
      boolean indirectDraw = Initializer.CONFIG.indirectDraw;
      if (!isTranslucent) {
         GlStateManager._disableBlend(0);
      } else {
         GlStateManager._enableBlend(0);
         VRenderSystem.blendFuncSeparate(770, 771, 1, 771);
      }

      VRenderSystem.enableCull();
      VRenderSystem.depthFunc(518);   // 26.2 uses reversed-Z (depth cleared to 0, GREATER_THAN_OR_EQUAL)
      GlStateManager._enableDepthTest();
      GlStateManager._depthMask(true);
      GlStateManager._colorMask(15);
      GlStateManager._disablePolygonOffset();
      VRenderSystem.setPolygonModeGL(6914);
      VRenderSystem.applyMVP(modelView, projection);
      VRenderSystem.setPrimitiveTopologyGL(4);
      Renderer renderer = Renderer.getInstance();
      GraphicsPipeline pipeline = PipelineManager.getTerrainShader(renderType);
      renderer.bindGraphicsPipeline(pipeline);
      TextureManager textureManager = Minecraft.getInstance().getTextureManager();
      AbstractTexture atlasTexture = textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS);
      GpuTextureView texView = atlasTexture.getTextureView();
      boolean useAnisotropy = this.minecraft.options.textureFiltering().get() == TextureFilteringMethod.ANISOTROPIC;
      int maxAnisotropy = this.minecraft.options.maxAnisotropyValue();
      VkGpuTexture texture = (VkGpuTexture)texView.texture();
      if (this.terrainSampler == 0L) {
         this.terrainSampler = SamplerManager.getSampler(true, false, texture.getVulkanImage().mipLevels - 1, useAnisotropy, maxAnisotropy);
      }

      texture.getVulkanImage().setSampler(this.terrainSampler);
      VRenderSystem.setShaderTexture(0, texView);
      VRenderSystem.setShaderTexture(2, Minecraft.getInstance().gameRenderer.lightmap());
      VTextureSelector.bindShaderTextures(pipeline);
      int atlasTexWidth = texView.getWidth(0);
      int atlasTexHeight = texView.getHeight(0);
      VRenderSystem.setTextureSize(atlasTexWidth, atlasTexHeight);
      VRenderSystem.setCurrentTime((int)System.currentTimeMillis());
      long currentTimeMs = System.currentTimeMillis();
      float fadeTime = ((Double)Minecraft.getInstance().options.chunkSectionFadeInTime().get()).floatValue();
      int fadeTimeMs = (int)(fadeTime * 1000.0F);
      float fadeTimeInv = fadeTime > 0.0F ? 1.0F / (fadeTime * 1000.0F) : 1.0F;
      IndexBuffer indexBuffer = Renderer.getDrawer().getQuadsIndexBuffer().getIndexBuffer();
      Renderer.getDrawer().bindIndexBuffer(Renderer.getCommandBuffer(), indexBuffer, indexBuffer.indexType.value);
      int currentFrame = Renderer.getCurrentFrame();
      Set<TerrainRenderType> allowedRenderTypes = Initializer.CONFIG.uniqueOpaqueLayer
         ? TerrainRenderType.COMPACT_RENDER_TYPES
         : TerrainRenderType.SEMI_COMPACT_RENDER_TYPES;
      if (allowedRenderTypes.contains(renderType)) {
         renderType.setCutoutUniform();

         // translucent areas must be drawn back-to-front (the queue is filled front-to-back by the graph BFS)
         for (Iterator<ChunkArea> areaIt = this.sectionGraph.getChunkAreaQueue().iterator(isTranslucent); areaIt.hasNext(); ) {
            ChunkArea chunkArea = areaIt.next();
            StaticQueue<RenderSection> queue = chunkArea.sectionQueue;
            DrawBuffers drawBuffers = chunkArea.drawBuffers;
            if (drawBuffers.getAreaBuffer(renderType) != null && queue.size() > 0) {
               drawBuffers.bindBuffers(Renderer.getCommandBuffer(), pipeline, renderType, camX, camY, camZ, currentTimeMs, fadeTimeMs, fadeTimeInv);
               renderer.uploadAndBindUBOs(pipeline);
               if (indirectDraw) {
                  drawBuffers.buildDrawBatchesIndirect(this.cameraPos, this.indirectBuffers[currentFrame], queue, renderType);
               } else {
                  drawBuffers.buildDrawBatchesDirect(this.cameraPos, queue, renderType);
               }
            }
         }
      }

      if (renderType == TerrainRenderType.CUTOUT || renderType == TerrainRenderType.TRIPWIRE) {
         this.indirectBuffers[currentFrame].submitUploads();
      }

      if (!indirectDraw) {
         VRenderSystem.setModelOffset(0.0F, 0.0F, 0.0F);
         renderer.pushConstants(pipeline);
      }

      zone.close();
   }

   private void sortTranslucentSections(double camX, double camY, double camZ) {
      ProfilerFiller mcProfiler = net.minecraft.util.profiling.Profiler.get();
      mcProfiler.push("translucent_sort");
      double d0 = camX - this.xTransparentOld;
      double d1 = camY - this.yTransparentOld;
      double d2 = camZ - this.zTransparentOld;
      if (d0 * d0 + d1 * d1 + d2 * d2 > 2.0) {
         this.xTransparentOld = camX;
         this.yTransparentOld = camY;
         this.zTransparentOld = camZ;
         int j = 0;
         Iterator<RenderSection> iterator = this.sectionGraph.getSectionQueue().iterator(false);

         while (iterator.hasNext() && j < 200) {
            RenderSection section = iterator.next();
            section.resortTransparency(this.taskDispatcher, this.cameraPos);
            if (!section.isCompletelyEmpty()) {
               j++;
            }
         }
      }

      mcProfiler.pop();
   }

   // 26.2: extraction only (LevelExtractor); vanilla LevelRenderer.submitBlockEntities submits the states.
   public void extractBlockEntities(
      Vec3 vec3,
      float partialTick,
      LevelRenderState levelRenderState,
      Long2ObjectMap<SortedSet<BlockDestructionProgress>> destructionProgress
   ) {
      if (this.sectionGraph == null || this.level == null) {
         return;
      }

      this.partialTick = partialTick;
      PoseStack poseStack = new PoseStack();
      double camX = vec3.x();
      double camY = vec3.y();
      double camZ = vec3.z();

      for (RenderSection renderSection : this.sectionGraph.getBlockEntitiesSections()) {
         List<BlockEntity> list = renderSection.getCompiledSection().getBlockEntities();
         if (!list.isEmpty()) {
            for (BlockEntity blockEntity : list) {
               BlockPos blockPos = blockEntity.getBlockPos();
               SortedSet<BlockDestructionProgress> sortedSet = (SortedSet<BlockDestructionProgress>)destructionProgress.get(blockPos.asLong());
               CrumblingOverlay crumblingOverlay;
               if (sortedSet != null && !sortedSet.isEmpty()) {
                  poseStack.pushPose();
                  poseStack.translate(blockPos.getX() - camX, blockPos.getY() - camY, blockPos.getZ() - camZ);
                  crumblingOverlay = new CrumblingOverlay(sortedSet.last().getProgress(), poseStack.last());
                  poseStack.popPose();
               } else {
                  crumblingOverlay = null;
               }

               BlockEntityRenderState blockEntityRenderState = this.blockEntityRenderDispatcher
                  .tryExtractRenderState(blockEntity, this.partialTick, crumblingOverlay, false);   // 26.2: isGloballyRendered
               if (blockEntityRenderState != null) {
                  levelRenderState.blockEntityRenderStates.add(blockEntityRenderState);
               }
            }
         }
      }

      Iterator<BlockEntity> iterator = this.level.getGloballyRenderedBlockEntities().iterator();

      while (iterator.hasNext()) {
         BlockEntity blockEntity2 = iterator.next();
         if (blockEntity2.isRemoved()) {
            iterator.remove();
         } else {
            BlockEntityRenderState blockEntityRenderState2 = this.blockEntityRenderDispatcher.tryExtractRenderState(blockEntity2, this.partialTick, null, true);
            if (blockEntityRenderState2 != null) {
               levelRenderState.blockEntityRenderStates.add(blockEntityRenderState2);
            }
         }
      }
   }

   public void resetSampler() {
      this.terrainSampler = 0L;
   }

   public void setPartialTick(float partialTick) {
      this.partialTick = partialTick;
   }

   public void scheduleGraphUpdate() {
      this.graphNeedsUpdate = true;
   }

   public boolean graphNeedsUpdate() {
      return this.graphNeedsUpdate;
   }

   public int getVisibleSectionsCount() {
      return this.sectionGraph == null ? 0 : this.sectionGraph.getSectionQueue().size();
   }

   public void setSectionDirty(int x, int y, int z, boolean flag) {
      if (this.sectionGrid == null) {
         return;
      }

      this.sectionGrid.setDirty(x, y, z, flag);
      this.renderRegionCache.remove(x, z);
   }

   /**
    * VulkanMod Turbo entity culling: a box is visible when any render section it overlaps was reached by the
    * section graph during its last update (occlusion BFS). Unknown/out-of-grid sections count as visible.
    */
   public boolean isBoxGraphVisible(net.minecraft.world.phys.AABB box) {
      if (this.sectionGrid == null || this.sectionGraph == null || this.level == null) {
         return true;
      }

      int minX = Mth.floor(box.minX) >> 4;
      int minY = Mth.floor(box.minY) >> 4;
      int minZ = Mth.floor(box.minZ) >> 4;
      int maxX = Mth.floor(box.maxX) >> 4;
      int maxY = Mth.floor(box.maxY) >> 4;
      int maxZ = Mth.floor(box.maxZ) >> 4;
      if (maxX - minX > 3 || maxY - minY > 3 || maxZ - minZ > 3) {
         return true;   // very large boxes: not worth it, never cull
      }

      short frame = this.sectionGraph.getLastFrame();

      for (int sx = minX; sx <= maxX; sx++) {
         for (int sy = minY; sy <= maxY; sy++) {
            for (int sz = minZ; sz <= maxZ; sz++) {
               RenderSection section = this.sectionGrid.getSectionAtBlockPos(sx << 4, sy << 4, sz << 4);
               // the grid wraps around (floorMod), so make sure the slot really holds this section
               if (section == null || section.xOffset() >> 4 != sx || section.yOffset() >> 4 != sy || section.zOffset() >> 4 != sz) {
                  return true;
               }

               if (section.getLastFrame() == frame) {
                  return true;
               }
            }
         }
      }

      return false;
   }

   public SectionGrid getSectionGrid() {
      return this.sectionGrid;
   }

   public ChunkAreaManager getChunkAreaManager() {
      return this.sectionGrid == null ? null : this.sectionGrid.chunkAreaManager;
   }

   public TaskDispatcher getTaskDispatcher() {
      return this.taskDispatcher;
   }

   public short getLastFrame() {
      return this.sectionGraph.getLastFrame();
   }

   public int getRenderDistance() {
      return this.renderDistance;
   }

   public String getChunkStatistics() {
      return this.sectionGraph == null ? null : this.sectionGraph.getStatistics();
   }

   public void cleanUp() {
      if (this.indirectBuffers != null) {
         Arrays.stream(this.indirectBuffers).forEach(Buffer::scheduleFree);
      }
   }

   public static WorldRenderer getInstance() {
      return INSTANCE;
   }

   public static ClientLevel getLevel() {
      return INSTANCE.level;
   }

   public static Vector3d getCameraPos() {
      return INSTANCE.cameraPos;
   }
}
