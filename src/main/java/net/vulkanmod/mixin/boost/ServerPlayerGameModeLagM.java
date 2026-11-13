package net.vulkanmod.mixin.boost;

import net.minecraft.server.level.ServerPlayerGameMode;
import net.vulkanmod.boost.LagCompensation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * VulkanMod Turbo TPS compensation for block breaking. In vanilla 26.2, ServerPlayerGameMode.gameTicks is only used to
 * time block destruction (ticks spent destroying = gameTicks - start). Advancing it by the measured lag makes the
 * server's breaking time follow real time, so it accepts blocks the client finished breaking instead of reverting them.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeLagM {
   @Shadow
   private int gameTicks;

   @Inject(method = "tick", at = @At("HEAD"))
   private void vb$compensateLag(CallbackInfo ci) {
      this.gameTicks += LagCompensation.extraTicks();
   }
}
