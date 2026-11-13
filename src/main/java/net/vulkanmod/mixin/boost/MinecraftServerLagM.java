package net.vulkanmod.mixin.boost;

import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.vulkanmod.boost.LagCompensation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// VulkanBoost TPS compensation: measure real time per server tick (integrated server only, the mod is client-side)
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerLagM {
   @Inject(method = "tickServer", at = @At("HEAD"))
   private void vb$measureTick(BooleanSupplier haveTime, CallbackInfo ci) {
      LagCompensation.onServerTick((MinecraftServer)(Object)this);
   }

   @Inject(method = "stopServer", at = @At("HEAD"))
   private void vb$reset(CallbackInfo ci) {
      LagCompensation.reset();
   }
}
