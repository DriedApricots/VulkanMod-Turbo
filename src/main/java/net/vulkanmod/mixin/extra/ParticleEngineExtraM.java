package net.vulkanmod.mixin.extra;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** VulkanBoost Extra: master particle switch, per-type switches and a particle render distance. */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineExtraM {
   @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
   private void vb$filterType(ParticleOptions options, double x, double y, double z, double xa, double ya, double za, CallbackInfoReturnable<Particle> cir) {
      Config c = Initializer.CONFIG;
      if (!c.particles) {
         cir.setReturnValue(null);
         return;
      }
      if (!c.disabledParticles.isEmpty()) {
         Identifier id = BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType());
         if (id != null && c.isParticleDisabled(id.toString())) {
            cir.setReturnValue(null);
         }
      }
   }

   @Inject(method = "add", at = @At("HEAD"), cancellable = true)
   private void vb$filterAdd(Particle particle, CallbackInfo ci) {
      Config c = Initializer.CONFIG;
      if (!c.particles) {
         ci.cancel();
         return;
      }
      int dist = c.particleRenderDistance;
      if (dist > 0) {
         Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
         if (camera != null) {
            Vec3 cam = camera.position();
            AABB box = particle.getBoundingBox();
            double dx = (box.minX + box.maxX) * 0.5 - cam.x, dy = (box.minY + box.maxY) * 0.5 - cam.y, dz = (box.minZ + box.maxZ) * 0.5 - cam.z;
            if (dx * dx + dy * dy + dz * dz > (double)dist * dist) {
               ci.cancel();
            }
         }
      }
   }
}