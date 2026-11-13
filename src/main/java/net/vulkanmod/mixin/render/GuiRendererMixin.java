package net.vulkanmod.mixin.render;



import com.mojang.blaze3d.IndexType;import com.mojang.blaze3d.PrimitiveTopology;import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;
import net.minecraft.client.gui.render.GuiRenderer;
import net.vulkanmod.render.engine.VkRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
   @Redirect(
      method = "executeDraw",
      at = @At(
         value = "INVOKE",
         target = "Lcom/mojang/blaze3d/systems/RenderPass;setIndexBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;)V"
      )
   )
   private void removeIndexBuffer(RenderPass instance, GpuBuffer indexBuffer, IndexType indexType) {
   }

   // 26.2: drawIndexed(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance)
   @Redirect(method = "executeDraw", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;drawIndexed(IIIII)V"))
   private void useVertexCount(RenderPass renderPass, int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance) {
      VkRenderPass vkRenderPass = (VkRenderPass)renderPass.backend;
      if (vkRenderPass.getPipeline().getPrimitiveTopology() != PrimitiveTopology.TRIANGLES) {
         int vertexCount = indexCount * 2 / 3;
         renderPass.drawIndexed(vertexCount, 1, 0, baseVertex, 0);
      } else {
         renderPass.drawIndexed(indexCount, 1, 0, baseVertex, 0);
      }
   }
}
