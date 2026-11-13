package net.vulkanmod.vulkan.util;


import com.mojang.blaze3d.GpuFormat;import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice.MappedView;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import java.util.function.Consumer;
import net.minecraft.util.ARGB;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.vulkan.Renderer;

public abstract class ScreenshotUtil {
   public static void takeScreenshot(RenderTarget renderTarget, int mipLevel, Consumer<NativeImage> consumer) {
      int width = renderTarget.width;
      int height = renderTarget.height;
      GpuTexture gpuTexture = renderTarget.getColorTexture();
      if (gpuTexture == null) {
         throw new IllegalStateException("Tried to capture screenshot of an incomplete framebuffer");
      }

      Renderer.getInstance().flushCmds();
      int pixelSize = GpuFormat.RGBA8_UNORM.blockSize();
      GpuBuffer gpuBuffer = RenderSystem.getDevice().createBuffer(() -> "Screenshot buffer", 9, width * height * pixelSize);
      CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
      RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(gpuTexture, gpuBuffer, 0L, () -> {
         MappedView readView = gpuBuffer.map(true, false);   // 26.2: mapping moved to GpuBuffer

         try {
            NativeImage nativeImage = new NativeImage(width, height, false);
            VkGpuTexture colorAttachment = (VkGpuTexture)Renderer.getInstance().getMainPass().getColorAttachment();
            boolean isBgraFormat = colorAttachment.getVulkanImage().format == 44;
            int size = mipLevel * mipLevel;

            if (mipLevel == 1) {
               // VulkanBoost: raw memory pass instead of getInt/setPixelABGR per pixel (330 ms -> a few ms at 1440p,
               // the F2 freeze). RGBA bytes read as a little-endian int are already ABGR, like NativeImage stores them.
               long src = org.lwjgl.system.MemoryUtil.memAddress(readView.data());
               long dst = nativeImage.getPointer();
               long count = (long)width * height;
               for (long i = 0; i < count; i++) {
                  int c = org.lwjgl.system.MemoryUtil.memGetInt(src + i * 4L);
                  if (isBgraFormat) {
                     c = c & 0xFF00FF00 | (c >>> 16) & 0xFF | (c & 0xFF) << 16;
                  }
                  org.lwjgl.system.MemoryUtil.memPutInt(dst + i * 4L, c | 0xFF000000);
               }
            } else
            for (int y = 0; y < height; y++) {
               for (int x = 0; x < width; x++) {
                  if (mipLevel == 1) {
                     int color = readView.data().getInt((x + y * width) * pixelSize);
                     if (isBgraFormat) {
                        color = ColorUtil.BGRAtoRGBA(color);
                     }

                     nativeImage.setPixelABGR(x, y, color | 0xFF000000);
                  } else {
                     int red = 0;
                     int green = 0;
                     int blue = 0;

                     for (int x1 = 0; x1 < mipLevel; x1++) {
                        for (int y1 = 0; y1 < mipLevel; y1++) {
                           int color = readView.data().getInt((x + x1 + (y + y1) * width) * pixelSize);
                           if (isBgraFormat) {
                              color = ColorUtil.BGRAtoRGBA(color);
                           }

                           red += ARGB.red(color);
                           green += ARGB.green(color);
                           blue += ARGB.blue(color);
                        }
                     }

                     nativeImage.setPixelABGR(x, y, ARGB.color(255, red / size, green / size, blue / size));
                  }
               }
            }

            consumer.accept(nativeImage);
         } catch (Throwable t$) {
            if (readView != null) {
               try {
                  readView.close();
               } catch (Throwable x2) {
                  t$.addSuppressed(x2);
               }
            }

            throw t$;
         }

         if (readView != null) {
            readView.close();
         }

         gpuBuffer.close();
      }, 0);
   }
}
