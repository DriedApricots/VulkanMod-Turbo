package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.vulkanmod.interfaces.ExtendedRenderType;
import net.vulkanmod.render.vertex.TerrainRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2 port: RenderType no longer draws MeshData itself (prepare() -> PreparedRenderType -> RenderPass),
 * and those render passes already go through VulkanMod's backend, so the former draw() @Overwrite is gone.
 * Only the terrain render type tagging remains.
 */
@Mixin(RenderType.class)
public class RenderTypeM implements ExtendedRenderType {
   @Unique
   TerrainRenderType terrainRenderType;

   @Inject(method = "<init>", at = @At("RETURN"))
   private void inj(String string, RenderSetup renderSetup, CallbackInfo ci) {
      this.terrainRenderType = switch (string) {
         case "solid" -> TerrainRenderType.SOLID;
         case "cutout" -> TerrainRenderType.CUTOUT;
         case "translucent" -> TerrainRenderType.TRANSLUCENT;
         case "tripwire" -> TerrainRenderType.TRIPWIRE;
         default -> null;
      };
   }

   @Override
   public TerrainRenderType getTerrainRenderType() {
      return this.terrainRenderType;
   }
}
