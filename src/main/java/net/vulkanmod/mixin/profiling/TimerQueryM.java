package net.vulkanmod.mixin.profiling;

import com.mojang.blaze3d.systems.TimerQuery;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

// 26.2 port: endProfile() no longer returns a FrameProfile; GPU timer queries stay disabled under VulkanMod.
@Mixin(TimerQuery.class)
public class TimerQueryM {
   @Overwrite
   public void beginProfile() {
   }

   @Overwrite
   public void endProfile() {
   }
}