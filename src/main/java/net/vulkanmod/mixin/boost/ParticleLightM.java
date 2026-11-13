package net.vulkanmod.mixin.boost;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.BlockAndLightGetter;
import net.vulkanmod.Initializer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * VulkanBoost: Particle.getLightCoords uses the particle's tick position (not the interpolated one), yet it looked up the
 * block state and light of that position every frame for every particle (~30% of the frame with many particles).
 * The base lookup is cached per particle and redone when the block position or the game tick changes. Subclasses
 * (flames, glow, lava...) still apply their own emission on top every frame, exactly as before.
 */
@Mixin(Particle.class)
public abstract class ParticleLightM {
   @Shadow @Final protected ClientLevel level;

   @Unique private long vb$lightPos = Long.MIN_VALUE;
   @Unique private long vb$lightTick = Long.MIN_VALUE;
   @Unique private int vb$light;

   @Redirect(
      method = "getLightCoords",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/util/LightCoordsUtil;getLightCoords(Lnet/minecraft/world/level/BlockAndLightGetter;Lnet/minecraft/core/BlockPos;)I")
   )
   private int vb$cachedLight(BlockAndLightGetter getter, BlockPos pos) {
      if (!Initializer.CONFIG.particleLightCache) {
         return LightCoordsUtil.getLightCoords(getter, pos);
      }

      long key = pos.asLong();
      long tick = this.level.getGameTime();
      if (key != this.vb$lightPos || tick != this.vb$lightTick) {
         this.vb$light = LightCoordsUtil.getLightCoords(getter, pos);
         this.vb$lightPos = key;
         this.vb$lightTick = tick;
      }

      return this.vb$light;
   }
}
