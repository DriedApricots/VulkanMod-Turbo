package net.vulkanmod.render.engine;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.GpuFormat;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public class VkGpuTexture extends GlTexture {
   private static final Reference2ReferenceOpenHashMap<GlTexture, VkGpuTexture> glToVkMap = new Reference2ReferenceOpenHashMap();
   protected VkGlTexture glTexture;
   protected final int id;
   private final Int2ReferenceMap<VkFbo> fboCache = new Int2ReferenceOpenHashMap();
   protected boolean closed;
   VkTextureView fboView;
   boolean needsClear = false;
   int clearColor = 0;
   float depthClearValue = 0.0F;   // 26.2 reversed-Z: far plane is 0

   protected VkGpuTexture(int usage, String string, GpuFormat textureFormat, int width, int height, int layers, int mipLevel, int id, VkGlTexture glTexture) {
      super(usage, string, textureFormat, width, height, layers, mipLevel, id, null); // 26.2: FrameBufferCache is GL-only
      this.id = id;
      this.glTexture = glTexture;
   }

   public void close() {
      if (!this.closed) {
         this.closed = true;
         GlStateManager._deleteTexture(this.id);
      }
   }

   public boolean isClosed() {
      return this.closed;
   }

   public int glId() {
      return this.id;
   }

   public void setClearColor(int clearColor) {
      this.needsClear = true;
      this.clearColor = clearColor;
   }

   public void setDepthClearValue(float depthClearValue) {
      this.needsClear = true;
      this.depthClearValue = depthClearValue;
   }

   public boolean needsClear() {
      return this.needsClear;
   }

   public VkFbo getFbo(@Nullable GpuTexture depthAttachment) {
      int depthAttachmentId = depthAttachment == null ? 0 : ((VkGpuTexture)depthAttachment).id;
      if (this.fboView == null) {
         VkGpuDevice gpuDevice = (VkGpuDevice)RenderSystem.getDevice().backend;
         this.fboView = (VkTextureView)gpuDevice.createTextureView(this, 0, this.getMipLevels());
      }

      return (VkFbo)this.fboCache.computeIfAbsent(depthAttachmentId, j -> new VkFbo(this.fboView, (VkGpuTexture)depthAttachment));
   }

   public VulkanImage getVulkanImage() {
      return this.glTexture.getVulkanImage();
   }

   public static VkGpuTexture fromGlTexture(GlTexture glTexture) {
      return (VkGpuTexture)glToVkMap.computeIfAbsent(
         glTexture,
         glTexture1 -> {
            String name = glTexture.getLabel();
            int id = glTexture.glId();
            VkGlTexture vglTexture = VkGlTexture.getTexture(id);
            return new VkGpuTexture(
               0, name, glTexture.getFormat(), glTexture.getWidth(0), glTexture.getHeight(0), 1, glTexture.getMipLevels(), glTexture.glId(), vglTexture
            );
         }
      );
   }

   public static GpuFormat textureFormat(int format) {
      return VkFormats.fromVk(format);
   }

   public static int vkFormat(GpuFormat textureFormat) {
      return VkFormats.toVk(textureFormat);
   }

   public static int vkImageViewType(int usage) {
      int viewType;
      if ((usage & 16) != 0) {
         viewType = 3;
      } else {
         viewType = 1;
      }

      return viewType;
   }
}
