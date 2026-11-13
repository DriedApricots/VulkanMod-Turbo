package net.vulkanmod.mixin.boost;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.object.chest.ChestModel;
import net.minecraft.client.renderer.MultiblockChestResources;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.client.renderer.blockentity.state.ChestRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.vulkanmod.boost.StaticChests;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** VulkanBoost: closed chests go to {@link StaticChests} instead of being rebuilt every frame. */
@Mixin(ChestRenderer.class)
public abstract class ChestRendererM {
   @Shadow @Final private SpriteGetter sprites;
   @Shadow @Final private MultiblockChestResources<ChestModel> models;

   @Inject(method = "submit(Lnet/minecraft/client/renderer/blockentity/state/ChestRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
      at = @At("HEAD"), cancellable = true)
   private void vb$staticChest(ChestRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
      if (!StaticChests.enabled() || state.open != 0.0F || state.breakProgress != null) {
         return;
      }

      // same resolution as ChestRenderer.submit + OrderedSubmitNodeCollector.submitModel(..., SpriteId, SpriteGetter, ...)
      SpriteId spriteId = Sheets.chooseSprite(state.material, state.type);
      ChestModel model = this.models.select(state.type);
      RenderType renderType = spriteId.renderType(model.renderType());
      if (renderType.hasBlending()) {
         return; // keep vanilla ordering for translucent (resource pack) chests
      }

      TextureAtlasSprite sprite = this.sprites.get(spriteId);
      com.mojang.math.Transformation local = ChestRenderer.modelTransformation(state.facing);
      long h = state.blockPos.asLong();
      h = h * 0x9E3779B97F4A7C15L + state.facing.ordinal();
      h = h * 0x9E3779B97F4A7C15L + state.type.ordinal();
      h = h * 0x9E3779B97F4A7C15L + state.material.ordinal();
      h = h * 0x9E3779B97F4A7C15L + state.lightCoords;
      h = h * 0x9E3779B97F4A7C15L + System.identityHashCode(model);
      h = h * 0x9E3779B97F4A7C15L + System.identityHashCode(renderType);
      h = h * 0x9E3779B97F4A7C15L + System.identityHashCode(sprite);
      h ^= h >>> 29;
      StaticChests.submit(new StaticChests.Chest(state.blockPos.immutable(), model, renderType, sprite, local, state.lightCoords, h));
      ci.cancel();
   }
}
