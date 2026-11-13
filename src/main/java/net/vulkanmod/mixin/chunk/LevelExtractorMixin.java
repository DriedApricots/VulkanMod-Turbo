package net.vulkanmod.mixin.chunk;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.server.packs.resources.ResourceManager;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.sky.CloudRendererHolder;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2 moved level bookkeeping (setLevel/allChanged/dirty sections/visible block entities)
 * from LevelRenderer to LevelExtractor. Route it to VulkanMod's WorldRenderer.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
   @Shadow
   @Nullable
   private ClientLevel level;

   @Inject(method = "setLevel", at = @At("RETURN"))
   private void vk$setLevel(@Nullable ClientLevel clientLevel, CallbackInfo ci) {
      WorldRenderer.getInstance().setLevel(clientLevel);
      CloudRendererHolder.reset();
   }

   @Inject(method = "allChanged", at = @At("RETURN"))
   private void vk$allChanged(CallbackInfo ci) {
      // setLevel() calls allChanged() before WorldRenderer knows the new level; it is rebuilt in vk$setLevel.
      WorldRenderer worldRenderer = WorldRenderer.getInstance();
      if (this.level != null && WorldRenderer.getLevel() == this.level) {
         worldRenderer.allChanged();
      }

      CloudRendererHolder.reset();
   }

   @Inject(method = "onResourceManagerReload", at = @At("RETURN"))
   private void vk$reload(ResourceManager resourceManager, CallbackInfo ci) {
      CloudRendererHolder.reload();
   }

   /** The vanilla occlusion graph is not used. */
   @Overwrite
   private void applyFrustum(Frustum frustum) {
   }

   @Overwrite
   private void extractVisibleBlockEntities(Camera camera, float deltaPartialTick, LevelRenderState levelRenderState) {
      if (this.level != null) {
         WorldRenderer.getInstance().extractBlockEntities(camera.position(), deltaPartialTick, levelRenderState, this.level.destructionProgress());
      }
   }

   @Overwrite
   private void setSectionDirty(int sectionX, int sectionY, int sectionZ, boolean playerChanged) {
      WorldRenderer.getInstance().setSectionDirty(sectionX, sectionY, sectionZ, playerChanged);
   }

   @Overwrite
   public int countRenderedSections() {
      return WorldRenderer.getInstance().getVisibleSectionsCount();
   }

   @Overwrite
   public @Nullable String sectionStatistics() {
      return WorldRenderer.getInstance().getChunkStatistics();
   }
}
