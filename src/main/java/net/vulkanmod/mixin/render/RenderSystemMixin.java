package net.vulkanmod.mixin.render;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.SamplerCache;
import net.minecraft.client.renderer.DynamicUniforms;
import net.vulkanmod.vulkan.Renderer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.vulkanmod.vulkan.Vulkan;

@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
   @Shadow
   @Nullable
   private static Thread renderThread;
   @Shadow
   @Nullable
   private static GpuDevice DEVICE;
   @Shadow
   @Nullable
   private static DynamicUniforms dynamicUniforms;
   @Shadow
   private static SamplerCache samplerCache;
   @Overwrite
   public static void initRenderer(GpuDevice device) {
      if (DEVICE != null) {
         throw new IllegalStateException("RenderSystem.DEVICE already initialized");
      }

      DEVICE = device;
      Renderer.initRenderer();   // 26.2: apiDescription field removed (GpuDevice.getDeviceInfo() is queried directly)
      dynamicUniforms = new DynamicUniforms();
      samplerCache.initialize();
   }

   // 26.2: Minecraft.close() no longer calls GpuDevice.close() directly; it happens at the end of shutdownRenderer().
   @Inject(method = "shutdownRenderer", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuDevice;close()V"))
   private static void vk$cleanUp(CallbackInfo ci) {
      Vulkan.cleanUp();
   }
}
