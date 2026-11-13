package net.vulkanmod.mixin.chunk;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.textures.GpuSampler;
import java.util.EnumMap;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.WorldBorderRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.LeavesBlock;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.profiling.Profiler;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.VRenderSystem;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2: LevelRenderer only consumes LevelRenderState; level/section bookkeeping moved to LevelExtractor
 * (see LevelExtractorMixin). The vanilla section pipeline (ViewArea, SectionRenderDispatcher,
 * SectionOcclusionGraph) is never created: VulkanMod's WorldRenderer replaces it entirely.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
   @Shadow
   @Final
   private WorldBorderRenderer worldBorderRenderer;
   @Unique
   private WorldRenderer worldRenderer;
   @Unique
   double camX;
   @Unique
   double camY;
   @Unique
   double camZ;
   @Unique
   private long lastCameraSection = Long.MIN_VALUE;

   @Shadow
   public abstract net.minecraft.client.renderer.CloudRenderer cloudRenderer();

   @Inject(method = "<init>", at = @At("RETURN"))
   private void init(
      EntityRenderDispatcher entityRenderDispatcher,
      BlockEntityRenderDispatcher blockEntityRenderDispatcher,
      ModelManager modelManager,
      TextureManager textureManager,
      AtlasManager atlasManager,
      ShaderManager shaderManager,
      GameRenderer gameRenderer,
      int width,
      int height,
      CallbackInfo ci
   ) {
      this.worldRenderer = WorldRenderer.init(
         entityRenderDispatcher, blockEntityRenderDispatcher, gameRenderer.renderBuffers(), gameRenderer.gameRenderState(), gameRenderer.featureRenderDispatcher()
      );
   }

   @Inject(method = "render", at = @At("HEAD"))
   private void setupRenderer(
      GraphicsResourceAllocator resourceAllocator,
      DeltaTracker deltaTracker,
      boolean renderOutline,
      CameraRenderState cameraState,
      Matrix4fc modelViewMatrix,
      GpuBufferSlice terrainFog,
      Vector4f fogColor,
      boolean shouldRenderSky,
      CallbackInfo ci
   ) {
      VRenderSystem.modelView = new Matrix4f(modelViewMatrix);
      this.camX = cameraState.pos.x();
      this.camY = cameraState.pos.y();
      this.camZ = cameraState.pos.z();

      Minecraft minecraft = Minecraft.getInstance();
      Camera camera = minecraft.gameRenderer.mainCamera();
      boolean spectator = minecraft.player != null && minecraft.player.isSpectator();
      this.worldRenderer.setupRenderer(camera, camera.getCullFrustum(), camera.getCapturedFrustum() != null, spectator);
   }

   /** Vanilla moves ViewArea/SectionRenderDispatcher here; neither exists with VulkanMod. */
   @Overwrite
   private void repositionCamera(CameraRenderState camera) {
      long section = SectionPos.asLong(SectionPos.posToSectionCoord(camera.pos.x), SectionPos.posToSectionCoord(camera.pos.y), SectionPos.posToSectionCoord(camera.pos.z));
      if (section != this.lastCameraSection) {
         this.lastCameraSection = section;
         this.worldBorderRenderer.invalidate();
      }
   }

   /** Vanilla section compilation is replaced by VulkanMod's TaskDispatcher. */
   @Overwrite
   private void compileSections(CameraRenderState camera) {
   }

   /** Keep only the side effects that are not about vanilla section storage. */
   @Overwrite
   public void invalidateCompiledGeometry(ClientLevel level, Options options, Camera camera, BlockColors blockColors) {
      this.cloudRenderer().markForRebuild();
      LeavesBlock.setCutoutLeaves(options.cutoutLeaves().get());
   }

   @Overwrite
   public ChunkSectionsToRender prepareChunkRenders(Matrix4fc modelViewMatrix) {
      return new ChunkSectionsToRender(null, new EnumMap<>(ChunkSectionLayer.class), 0, new GpuBufferSlice[0]);
   }

   @Redirect(
      method = "render",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/renderer/SectionOcclusionGraph;update(Lnet/minecraft/client/renderer/state/level/CameraRenderState;ILnet/minecraft/client/renderer/state/level/ChunkLoadingRenderState;)V"
      )
   )
   private void skipOcclusionGraph(SectionOcclusionGraph graph, CameraRenderState camera, int fov, ChunkLoadingRenderState chunkLoadingRenderState) {
   }

   @Overwrite
   public boolean isSectionCompiledAndVisible(BlockPos blockPos) {
      return this.worldRenderer.isSectionCompiled(blockPos);
   }

   @Redirect(
      method = "lambda$addMainPass$0",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V"
      )
   )
   private void renderSectionLayer(ChunkSectionsToRender instance, ChunkSectionLayerGroup group, GpuSampler sampler) {
      if (group == ChunkSectionLayerGroup.OPAQUE) {
         Profiler profiler = Profiler.getMainProfiler();
         profiler.push("Opaque_terrain");
         this.worldRenderer.renderSectionLayer(TerrainRenderType.SOLID, this.camX, this.camY, this.camZ, VRenderSystem.modelView, VRenderSystem.projection);
         this.worldRenderer.renderSectionLayer(TerrainRenderType.CUTOUT, this.camX, this.camY, this.camZ, VRenderSystem.modelView, VRenderSystem.projection);
         profiler.pop();
      } else if (group == ChunkSectionLayerGroup.TRANSLUCENT) {
         Profiler profiler = Profiler.getMainProfiler();
         profiler.push("Translucent_terrain");
         this.worldRenderer
            .renderSectionLayer(TerrainRenderType.TRANSLUCENT, this.camX, this.camY, this.camZ, VRenderSystem.modelView, VRenderSystem.projection);
         profiler.pop();
      }
   }

   /** VulkanMod Turbo: closed chests baked per section (StaticChests), drawn right before vanilla's solid features. */
   @Inject(
      method = "lambda$addMainPass$0",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeSolid()V")
   )
   private void vb$drawStaticChests(CallbackInfo ci) {
      net.vulkanmod.boost.StaticChests.draw(this.camX, this.camY, this.camZ);
      net.vulkanmod.boost.StaticSignText.arm(this.camX, this.camY, this.camZ);
   }

   @Overwrite
   public boolean hasRenderedAllSections() {
      return !this.worldRenderer.graphNeedsUpdate() && this.worldRenderer.getTaskDispatcher().isIdle();
   }
}
