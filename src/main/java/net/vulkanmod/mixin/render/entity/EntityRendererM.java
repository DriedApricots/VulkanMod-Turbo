package net.vulkanmod.mixin.render.entity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.phys.AABB;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * VulkanBoost entity culling (replaces the upstream centre-point check).
 * After vanilla frustum culling accepted an entity, hide it when every render section its box overlaps
 * was left unreached by the occlusion graph. Never culled: renderers that opt out of culling, glowing
 * entities (visible through walls), leashed entities and anything the local player rides.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererM<T extends Entity> {
   @Shadow
   protected abstract boolean affectedByCulling(T entity);

   @Shadow
   protected abstract AABB getBoundingBoxForCulling(T entity);

   @Inject(method = "shouldRender", at = @At("RETURN"), cancellable = true)
   private void vb$occlusionCull(T entity, Frustum culler, double camX, double camY, double camZ, CallbackInfoReturnable<Boolean> cir) {
      if (!cir.getReturnValueZ()) {
         return;
      }

      // VulkanBoost Extra: item frames, paintings, visible armor stands. Invisible / marker armor stands are kept:
      // servers build holograms from them, and hiding them would hide the hologram text too.
      net.vulkanmod.config.Config cfg = Initializer.CONFIG;
      if (!cfg.itemFrames && entity instanceof net.minecraft.world.entity.decoration.ItemFrame
         || !cfg.paintings && entity instanceof net.minecraft.world.entity.decoration.painting.Painting
         || !cfg.armorStands && entity instanceof net.minecraft.world.entity.decoration.ArmorStand stand && !stand.isMarker() && !stand.isInvisible()) {
         cir.setReturnValue(false);
         return;
      }

      // VulkanBoost optional distance limits (0 = off): dropped items / mobs. Players, bosses, glowing, named and
      // ridden/leashed entities are never hidden by distance.
      int itemDist = Initializer.CONFIG.itemRenderDistance, mobDist = Initializer.CONFIG.mobRenderDistance;
      if (itemDist > 0 || mobDist > 0) {
         int limit = entity instanceof net.minecraft.world.entity.item.ItemEntity ? itemDist
            : entity instanceof net.minecraft.world.entity.Mob mob && !entity.hasCustomName() && !entity.isCurrentlyGlowing() && !entity.isVehicle()
               && !entity.isPassenger() && !(entity instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon)
               && !(entity instanceof net.minecraft.world.entity.boss.wither.WitherBoss) && !mob.isLeashed() ? mobDist : 0;
         if (limit > 0 && entity.distanceToSqr(camX, camY, camZ) > (double)limit * limit) {
            cir.setReturnValue(false);
            return;
         }
      }

      if (!Initializer.CONFIG.entityCulling) {
         return;
      }

      if (!this.affectedByCulling(entity) || entity.isCurrentlyGlowing()) {
         return;
      }

      if (entity instanceof Leashable leashable && leashable.getLeashHolder() != null) {
         return;
      }

      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player != null && (entity == minecraft.getCameraEntity() || entity.hasIndirectPassenger(minecraft.player))) {
         return;
      }

      AABB box = this.getBoundingBoxForCulling(entity);
      if (box.hasNaN()) {
         return;
      }

      WorldRenderer worldRenderer = WorldRenderer.getInstance();
      if (worldRenderer != null && !worldRenderer.isBoxGraphVisible(box.inflate(0.5))) {
         cir.setReturnValue(false);
      }
   }
}
