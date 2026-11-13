package net.vulkanmod.mixin.render.target;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.OptionalInt;
import net.minecraft.client.renderer.RenderPipelines;
import net.vulkanmod.render.engine.VkFbo;
import net.vulkanmod.render.engine.VkGpuTexture;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(RenderTarget.class)
public abstract class RenderTargetMixin {
   @Shadow
   public int width;
   @Shadow
   public int height;
   @Shadow
   @Nullable
   protected GpuTexture colorTexture;
   @Shadow
   @Nullable
   protected GpuTexture depthTexture;
   @Shadow
   @Nullable
   protected GpuTextureView colorTextureView;

   @Overwrite
   public void blitAndBlendToTexture(GpuTextureView gpuTextureView, GpuTextureView outputDepth) {   // 26.2: + output depth
      RenderSystem.assertOnRenderThread();
      VkFbo fbo = ((VkGpuTexture)this.colorTexture).getFbo(this.depthTexture);
      if (!fbo.needsClear()) {
         RenderPass renderPass = RenderSystem.getDevice()
            .createCommandEncoder()
            .createRenderPass(() -> "Blit render target", gpuTextureView, java.util.Optional.empty(), outputDepth, java.util.OptionalDouble.empty());

         try {
            renderPass.setPipeline(RenderPipelines.ENTITY_OUTLINE_BLIT);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.bindTexture("InSampler", this.colorTextureView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            renderPass.draw(3, 1, 0, 0);   // 26.2: (vertexCount, instanceCount, firstVertex, firstInstance)
         } catch (Throwable var7) {
            if (renderPass != null) {
               try {
                  renderPass.close();
               } catch (Throwable var6) {
                  var7.addSuppressed(var6);
               }
            }

            throw var7;
         }

         if (renderPass != null) {
            renderPass.close();
         }
      }
   }
}
