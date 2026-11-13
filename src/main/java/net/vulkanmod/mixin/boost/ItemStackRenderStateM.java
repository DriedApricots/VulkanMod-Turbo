package net.vulkanmod.mixin.boost;

import java.util.HashMap;
import java.util.function.Supplier;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.AABB;
import net.vulkanmod.Initializer;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * VulkanMod Turbo: dropped items need their model bounding box every frame (hover offset). The render state is rebuilt
 * each frame, so vanilla recomputed it from every model vertex each time. The box only depends on the display context
 * and, per layer, on the (memoized) model extents, the item transform and the local transform, so it is cached by
 * exactly those.
 */
@Mixin(ItemStackRenderState.class)
public abstract class ItemStackRenderStateM {
   @Unique private static final HashMap<Object, AABB> VB_BOXES = new HashMap<>();
   @Shadow private int activeLayerCount;
   @Shadow private ItemStackRenderState.LayerRenderState[] layers;
   @Shadow ItemDisplayContext displayContext;
   @Shadow private AABB cachedModelBoundingBox;

   @Inject(method = "getModelBoundingBox", at = @At("HEAD"), cancellable = true)
   private void vb$cachedBox(CallbackInfoReturnable<AABB> cir) {
      if (this.cachedModelBoundingBox != null || !Initializer.CONFIG.itemBoxCache || this.activeLayerCount > 4
          || !com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
         return;
      }

      Object[] key = new Object[1 + this.activeLayerCount * 3];
      key[0] = this.displayContext;
      for (int i = 0; i < this.activeLayerCount; i++) {
         ItemLayerAccessor layer = (ItemLayerAccessor)(Object)this.layers[i];
         key[1 + i * 3] = layer.vb$extents();
         key[2 + i * 3] = layer.vb$itemTransform();
         key[3 + i * 3] = new Matrix4f(layer.vb$localTransform());
      }

      java.util.List<Object> listKey = java.util.Arrays.asList(key);
      AABB box = VB_BOXES.get(listKey);
      if (box == null) {
         return; // vanilla computes it; stored by vb$store
      }

      this.cachedModelBoundingBox = box;
      cir.setReturnValue(box);
   }

   @Inject(method = "getModelBoundingBox", at = @At("RETURN"))
   private void vb$store(CallbackInfoReturnable<AABB> cir) {
      if (!Initializer.CONFIG.itemBoxCache || this.activeLayerCount > 4 || !com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
         return;
      }

      Object[] key = new Object[1 + this.activeLayerCount * 3];
      key[0] = this.displayContext;
      for (int i = 0; i < this.activeLayerCount; i++) {
         ItemLayerAccessor layer = (ItemLayerAccessor)(Object)this.layers[i];
         key[1 + i * 3] = layer.vb$extents();
         key[2 + i * 3] = layer.vb$itemTransform();
         key[3 + i * 3] = new Matrix4f(layer.vb$localTransform());
      }

      if (VB_BOXES.size() > 4096) {
         VB_BOXES.clear();
      }
      VB_BOXES.putIfAbsent(java.util.Arrays.asList(key), cir.getReturnValue());
   }
}
