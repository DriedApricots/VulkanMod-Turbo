package net.vulkanmod.mixin.boost;

import java.util.Map;
import java.util.Queue;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import net.vulkanmod.Initializer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** VulkanBoost option "Particle Limit" (default off): new particles are dropped while the limit is reached. */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineLimitM {
   @Shadow @Final private Map<ParticleRenderType, ParticleGroup<?>> particles;
   @Shadow @Final private Queue<Particle> particlesToAdd;

   @Inject(method = "add", at = @At("HEAD"), cancellable = true)
   private void vb$limit(Particle particle, CallbackInfo ci) {
      int limit = Initializer.CONFIG.particleLimit;
      if (limit <= 0) {
         return;
      }

      int total = this.particlesToAdd.size();
      for (ParticleGroup<?> group : this.particles.values()) {
         total += group.size();
      }

      if (total >= limit) {
         ci.cancel();
      }
   }
}
