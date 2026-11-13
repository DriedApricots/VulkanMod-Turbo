package net.vulkanmod.mixin.render;

import com.mojang.blaze3d.systems.GpuBackend;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.Options;
import net.minecraft.client.main.GameConfig;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.engine.VkBackend;
import net.vulkanmod.vulkan.Vulkan;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
   @Shadow
   @Final
   public Options options;

   @Inject(method = "<init>", at = @At("RETURN"))
   private void forceGraphicsMode(GameConfig gameConfig, CallbackInfo ci) {
      OptionInstance<GraphicsPreset> graphicsModeOption = this.options.graphicsPreset();
      if (graphicsModeOption.get() == GraphicsPreset.FABULOUS) {
         Initializer.LOGGER.error("Fabulous graphics mode not supported, forcing Fancy.");
         graphicsModeOption.set(GraphicsPreset.FANCY);
      }

      if ((Boolean)this.options.improvedTransparency().get()) {
         Initializer.LOGGER.error("Improved transparency currently not supported, forcing it off.");
         this.options.improvedTransparency().set(false);
      }
   }

   // 26.2: backends come from PreferredGraphicsApi.getBackendsToTry() (vanilla now also ships GL + its own Vulkan backend)
   @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/PreferredGraphicsApi;getBackendsToTry()[Lcom/mojang/blaze3d/systems/GpuBackend;"))
   private GpuBackend[] useVulkanBackend(PreferredGraphicsApi api) {
      return new GpuBackend[]{new VkBackend()};
   }

   @Inject(method = "close", at = @At("HEAD"))
   public void close(CallbackInfo ci) {
      Vulkan.waitIdle();
   }

   // 26.2: GpuDevice.close() moved into RenderSystem.shutdownRenderer(); Vulkan.cleanUp() is hooked there (RenderSystemMixin).

}
