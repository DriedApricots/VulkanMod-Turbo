package net.vulkanmod.mixin.render.depth;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Camera.class)
public class CameraMixin {
   // setupPerspective(zNear, zFar, fov, width, height): zFar is the 2nd float argument (ordinal, not LVT name)
   @ModifyVariable(method = "setupPerspective", at = @At("HEAD"), argsOnly = true, ordinal = 1)
   private float infiniteZFar(float zFar) {
      return Float.POSITIVE_INFINITY;
   }
}
