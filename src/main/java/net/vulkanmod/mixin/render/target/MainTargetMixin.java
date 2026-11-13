package net.vulkanmod.mixin.render.target;


import com.mojang.blaze3d.GpuFormat;import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(MainTarget.class)
public class MainTargetMixin extends RenderTarget {
   public MainTargetMixin(boolean useDepth) {
      super("Main", useDepth, GpuFormat.RGBA8_UNORM);   // 26.2: RenderTarget takes the colour format
   }

   @Overwrite
   private void createFrameBuffer(int width, int height) {
      this.width = width;
      this.height = height;
   }

   public void createBuffers(int i, int j) {
      RenderSystem.assertOnRenderThread();
      GpuDevice gpuDevice = RenderSystem.getDevice();
      int k = gpuDevice.getDeviceInfo().limits().maxTextureSize();
      if (i > 0 && i <= k && j > 0 && j <= k) {
         this.width = i;
         this.height = j;
         if (this.useDepth) {
            this.depthTexture = gpuDevice.createTexture(() -> this.label + " / Depth", 15, GpuFormat.D32_FLOAT, i, j, 1, 1);
            this.depthTextureView = gpuDevice.createTextureView(this.depthTexture);
         }

         this.colorTexture = gpuDevice.createTexture(() -> this.label + " / Color", 15, GpuFormat.RGBA8_UNORM, i, j, 1, 1);
         this.colorTextureView = gpuDevice.createTextureView(this.colorTexture);
      } else {
         throw new IllegalArgumentException("Window " + i + "x" + j + " size out of bounds (max. size: " + k + ")");
      }
   }

   public GpuTexture getColorTexture() {
      return Renderer.getInstance().getMainPass().getColorAttachment();
   }

   public GpuTextureView getColorTextureView() {
      return Renderer.getInstance().getMainPass().getColorAttachmentView();
   }

   public GpuTexture getDepthTexture() {
      return Renderer.getInstance().getMainPass().getDepthAttachment();
   }

   // 26.2: render passes take color + depth views; without this the depth view kept the stale pre-resize size
   public GpuTextureView getDepthTextureView() {
      GpuTextureView view = Renderer.getInstance().getMainPass().getDepthAttachmentView();
      return view != null ? view : this.depthTextureView;
   }
}
