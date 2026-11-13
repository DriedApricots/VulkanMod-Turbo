package net.vulkanmod.mixin.extra;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.vulkanmod.Initializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** VulkanMod Turbo Extra: player / other entity name tags (server holograms made of named armor stands count as "other"). */
@Mixin(EntityRenderer.class)
public abstract class EntityNameTagM<T extends Entity, S extends EntityRenderState> {
   @Inject(method = "extractNameTags(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;FDD)V", at = @At("TAIL"))
   private void vb$nameTags(T entity, S state, float partialTicks, double nameTagDistance, double belowNameDistance, CallbackInfo ci) {
      boolean show = entity instanceof Player ? Initializer.CONFIG.playerNameTags : Initializer.CONFIG.entityNameTags;
      if (!show) {
         state.nameTag = null;
         state.scoreText = null;
      }
   }
}