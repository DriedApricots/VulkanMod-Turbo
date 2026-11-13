package net.vulkanmod.mixin.extra;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.vulkanmod.boost.AnimationKind;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** VulkanBoost Extra: tags each animation state with the kind of its sprite (see AnimationStateM). */
@Mixin(SpriteContents.class)
public abstract class SpriteContentsAnimM {
   @Inject(method = "createAnimationState", at = @At("RETURN"))
   private void vb$tagKind(GpuBufferSlice uboSlice, int spriteUboSize, CallbackInfoReturnable<SpriteContents.AnimationState> cir) {
      SpriteContents.AnimationState state = cir.getReturnValue();
      if (state != null) {
         ((AnimationKind)(Object)state).vb$setKind(AnimationKind.of(((SpriteContents)(Object)this).name().getPath()));
      }
   }
}