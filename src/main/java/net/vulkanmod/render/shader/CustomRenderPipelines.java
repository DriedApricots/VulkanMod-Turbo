package net.vulkanmod.render.shader;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline.Snippet;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.BindGroupLayouts;

/**
 * 26.2 port: vanilla snippets are private now and pipelines describe bind group layouts, a vertex binding and a
 * primitive topology separately, so the GUI triangles pipeline mirrors vanilla's GUI_SNIPPET (GLOBALS + MATRICES_PROJECTION).
 */
public class CustomRenderPipelines {
   public static final List<RenderPipeline> pipelines = new ArrayList<>();
   public static final Snippet GUI_TRIANGLES_SNIPPET = RenderPipeline.builder()
      .withBindGroupLayout(BindGroupLayouts.GLOBALS)
      .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
      .withVertexShader("core/gui")
      .withFragmentShader("core/gui")
      .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
      .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
      .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
      .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
      .buildSnippet();
   public static final RenderPipeline GUI_TRIANGLES = register(
      RenderPipeline.builder(new Snippet[]{GUI_TRIANGLES_SNIPPET}).withLocation("pipeline/gui").build()
   );

   static RenderPipeline register(RenderPipeline pipeline) {
      pipelines.add(pipeline);
      return pipeline;
   }
}
