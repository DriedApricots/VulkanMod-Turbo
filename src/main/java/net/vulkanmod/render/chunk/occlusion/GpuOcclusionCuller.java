package net.vulkanmod.render.chunk.occlusion;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

/**
 * VulkanMod Turbo: GPU occlusion culling of chunk sections, a Vulkan re-implementation of the temporal occlusion scheme of
 * Nvidium by MCRcortex (https://github.com/MCRcortex/nvidium, LGPL-3.0). Nvidium is OpenGL + NVIDIA-only (mesh shaders,
 * bindless); this version uses only core Vulkan 1.2 features (multi draw indirect, fragment stores), so it runs on any
 * GPU VulkanMod supports, including old ones without mesh shaders.
 *
 * Per frame:
 *  1. The CPU writes the indirect draw commands of every section the section graph considers visible (frustum + cave
 *     culling), for every terrain layer, into one buffer. Unused bytes 20..23 of each 32-byte command hold the section id.
 *  2. Compute (phase 0): opaque commands keep instanceCount=1 only if their section was seen in the previous frame.
 *  3. The opaque layers are drawn with these commands: the depth buffer now holds what was visible last frame.
 *  4. The bounding box of every candidate section is rasterised against that depth (no depth / colour writes); the
 *     fragment shader marks the section as visible in this frame.
 *  5. Compute (phase 1): opaque commands are enabled for sections visible now but not drawn in step 3 (newly
 *     disoccluded), translucent commands for every section visible now. Those opaque commands are drawn right away,
 *     so nothing pops in a frame late; the translucent layer later uses the same buffer.
 * Sections hidden behind terrain are therefore not drawn at all (their vertices never reach the GPU pipeline).
 */
public final class GpuOcclusionCuller {
   public static final int CMD_STRIDE = 32;
   public static final int FLAG_TRANSLUCENT = 1 << 30;
   public static final int FLAG_FORCE = 1 << 31;
   /** blocks a section's geometry may extend past its 16^3 cell (vanilla models stay within one block) */
   public static final float BOX_MARGIN = 1.0F;
   /** sections whose box grown by this contains the camera (the camera section and its 26 neighbours) are always drawn
    *  and not box tested: they are nearly always visible and their boxes would cover most of the screen */
   public static final float FORCE_RADIUS = 16.0F + BOX_MARGIN;

   private static final int STAGES = VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT | VK10.VK_SHADER_STAGE_COMPUTE_BIT;
   private static final int PC_SIZE = 80; // mat4 mvp, uint frame, uint count, uint phase, float margin

   private static GpuOcclusionCuller INSTANCE;
   private static boolean unsupportedLogged;

   private final VkDevice device;
   private long setLayout, pipelineLayout, descriptorPool;
   private long computePipeline;
   private long boxPipeline;
   private int boxColorFormat = -1, boxDepthFormat = -1;
   private final long[] sets;
   private final long[][] setBuffers; // per frame: {cmd, vis, box} buffer ids the set points at

   private final Buffer[] cmdBuffers;
   private final Buffer[] boxBuffers;
   private Buffer visBuffer;
   private int visSections;
   private boolean visNeedsClear;

   private int frame = 1;

   public static boolean isSupported() {
      return DeviceManager.device != null && DeviceManager.device.availableFeatures.features().fragmentStoresAndAtomics()
         && DeviceManager.device.isDrawIndirectSupported();
   }

   /** the culler, or null if disabled / not supported */
   public static GpuOcclusionCuller get() {
      if (!Initializer.CONFIG.gpuOcclusionCulling || !Initializer.CONFIG.indirectDraw) {
         return null;
      }

      if (!isSupported()) {
         if (!unsupportedLogged) {
            unsupportedLogged = true;
            Initializer.LOGGER.info("GPU occlusion culling not available on this GPU (needs fragmentStoresAndAtomics + multiDrawIndirect)");
         }
         return null;
      }

      if (INSTANCE == null) {
         try {
            INSTANCE = new GpuOcclusionCuller();
         } catch (RuntimeException e) {
            Initializer.LOGGER.error("GPU occlusion culling disabled: init failed", e);
            Initializer.CONFIG.gpuOcclusionCulling = false;
            return null;
         }
      }

      return INSTANCE;
   }

   private GpuOcclusionCuller() {
      this.device = DeviceManager.vkDevice;
      int frames = Renderer.getFramesNum();
      this.sets = new long[frames];
      this.setBuffers = new long[frames][3];
      this.cmdBuffers = new Buffer[frames];
      this.boxBuffers = new Buffer[frames];

      for (int i = 0; i < frames; i++) {
         this.cmdBuffers[i] = new Buffer("VulkanMod Turbo culled indirect", VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, MemoryTypes.HOST_MEM);
         this.cmdBuffers[i].createBuffer(256L * 1024L);
         this.boxBuffers[i] = new Buffer("VulkanMod Turbo section boxes", VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, MemoryTypes.HOST_MEM);
         this.boxBuffers[i].createBuffer(64L * 1024L);
      }

      this.createLayouts(frames);
      this.computePipeline = this.createComputePipeline();
      Initializer.LOGGER.info("GPU occlusion culling enabled (Nvidium-style temporal culling, Vulkan)");
   }

   public int frame() {
      return this.frame;
   }

   public Buffer cmdBuffer() {
      return this.cmdBuffers[Renderer.getCurrentFrame()];
   }

   /** pointer to write `count` commands to, growing the buffer of the current frame if needed */
   public long reserveCommands(int count) {
      Buffer b = this.cmdBuffer();
      long need = (long) count * CMD_STRIDE;
      if (need > b.getBufferSize()) {
         b.resizeBuffer(Math.max(need, b.getBufferSize() * 2));
      }
      return b.getDataPtr();
   }

   public long reserveBoxes(int count) {
      Buffer b = this.boxBuffers[Renderer.getCurrentFrame()];
      long need = Math.max(16L, (long) count * 16L);
      if (need > b.getBufferSize()) {
         b.resizeBuffer(Math.max(need, b.getBufferSize() * 2));
      }
      return b.getDataPtr();
   }

   /** called once per frame before the commands are written; makes sure the visibility buffer covers all sections */
   public void beginFrame(int sectionCount) {
      this.frame++;
      if (this.frame >= 0x3FFFFFF0) { // never wraps in practice; restart cleanly if it ever does
         this.frame = 2;
         this.visNeedsClear = true;
      }

      if (this.visBuffer == null || sectionCount > this.visSections) {
         if (this.visBuffer != null) {
            this.visBuffer.scheduleFree();
         }
         this.visSections = sectionCount;
         this.visBuffer = new Buffer("VulkanMod Turbo section visibility", VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, MemoryTypes.GPU_MEM);
         this.visBuffer.createBuffer(Math.max(4L, sectionCount * 4L));
         this.visNeedsClear = true;
      }
   }

   /** after a world/renderer reset: forget all visibility */
   public static void onAllChanged() {
      if (INSTANCE != null) {
         INSTANCE.reset();
      }
   }

   public void reset() {
      this.visNeedsClear = true;
   }

   // ---------------------------------------------------------------- recording

   /** phase 0 or 1 compute pass over `count` commands. Must be called outside of a render pass. */
   public void dispatch(VkCommandBuffer cmd, int phase, int count) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         if (this.visNeedsClear) {
            this.visNeedsClear = false;
            VK10.vkCmdFillBuffer(cmd, this.visBuffer.getId(), 0L, VK10.VK_WHOLE_SIZE, 0);
            barrier(stack, cmd, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
               VK10.VK_ACCESS_TRANSFER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
         }

         if (phase == 0) {
            // box writes of earlier frames -> this read
            barrier(stack, cmd, VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
               VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT);
         } else {
            // this frame's box writes -> read; phase-0 draws read the commands this pass rewrites
            barrier(stack, cmd, VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK10.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
               VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
         }

         if (count > 0) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, this.computePipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, this.pipelineLayout, 0, stack.longs(this.currentSet()), null);
            this.pushConstants(stack, cmd, phase, count);
            VK10.vkCmdDispatch(cmd, (count + 63) >>> 6, 1, 1);
         }

         // commands -> indirect draws; and later box draws must not overwrite visibility this pass still reads
         barrier(stack, cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK10.VK_ACCESS_SHADER_READ_BIT);
      }
   }

   /** rasterises `count` section boxes against the current depth buffer. Must be called inside the main render pass. */
   public void drawBoxes(VkCommandBuffer cmd, int count) {
      if (count <= 0) {
         return;
      }

      Framebuffer fb = Renderer.getInstance().getMainPass().getMainFramebuffer();
      if (this.boxPipeline == 0L || fb.getFormat() != this.boxColorFormat || fb.getDepthFormat() != this.boxDepthFormat) {
         if (this.boxPipeline != 0L) {
            long old = this.boxPipeline;
            net.vulkanmod.vulkan.memory.MemoryManager.getInstance().addFrameOp(() -> VK10.vkDestroyPipeline(this.device, old, null));
         }
         this.boxColorFormat = fb.getFormat();
         this.boxDepthFormat = fb.getDepthFormat();
         this.boxPipeline = this.createBoxPipeline(this.boxColorFormat, this.boxDepthFormat);
      }

      try (MemoryStack stack = MemoryStack.stackPush()) {
         VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, this.boxPipeline);
         VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipelineLayout, 0, stack.longs(this.currentSet()), null);
         this.pushConstants(stack, cmd, 0, count);
         VK10.vkCmdDraw(cmd, 18, count, 0, 0);
      }

      Renderer.getInstance().resetBoundPipeline(); // the next VulkanMod pipeline bind must not be skipped
   }

   private void pushConstants(MemoryStack stack, VkCommandBuffer cmd, int phase, int count) {
      ByteBuffer pc = stack.malloc(PC_SIZE);
      MemoryUtil.memCopy(VRenderSystem.MVP.ptr, MemoryUtil.memAddress(pc), 64);
      pc.putInt(64, this.frame);
      pc.putInt(68, count);
      pc.putInt(72, phase);
      pc.putFloat(76, BOX_MARGIN);
      VK10.vkCmdPushConstants(cmd, this.pipelineLayout, STAGES, 0, pc);
   }

   private long currentSet() {
      int f = Renderer.getCurrentFrame();
      long cmdId = this.cmdBuffers[f].getId();
      long visId = this.visBuffer.getId();
      long boxId = this.boxBuffers[f].getId();
      long[] cur = this.setBuffers[f];
      if (cur[0] != cmdId || cur[1] != visId || cur[2] != boxId) {
         // this frame slot's previous submission has completed (fence waited in beginFrame): safe to update
         try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorBufferInfo.Buffer infos = VkDescriptorBufferInfo.calloc(3, stack);
            infos.get(0).buffer(cmdId).offset(0).range(VK10.VK_WHOLE_SIZE);
            infos.get(1).buffer(visId).offset(0).range(VK10.VK_WHOLE_SIZE);
            infos.get(2).buffer(boxId).offset(0).range(VK10.VK_WHOLE_SIZE);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);
            for (int i = 0; i < 3; i++) {
               writes.get(i).sType$Default().dstSet(this.sets[f]).dstBinding(i).descriptorCount(1)
                  .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(VkDescriptorBufferInfo.create(infos.address(i), 1));
            }
            VK10.vkUpdateDescriptorSets(this.device, writes, null);
         }
         cur[0] = cmdId;
         cur[1] = visId;
         cur[2] = boxId;
      }
      return this.sets[f];
   }

   private static void barrier(MemoryStack stack, VkCommandBuffer cmd, int srcStage, int dstStage, int srcAccess, int dstAccess) {
      VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess);
      VK10.vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, b, null, null);
   }

   // ---------------------------------------------------------------- setup

   private void createLayouts(int frames) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);
         for (int i = 0; i < 3; i++) {
            bindings.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(STAGES);
         }
         VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings);
         LongBuffer p = stack.mallocLong(1);
         Vulkan.checkResult(VK10.vkCreateDescriptorSetLayout(this.device, layoutInfo, null, p), "occlusion: descriptor set layout");
         this.setLayout = p.get(0);

         VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack);
         range.get(0).stageFlags(STAGES).offset(0).size(PC_SIZE);
         VkPipelineLayoutCreateInfo plInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
            .pSetLayouts(stack.longs(this.setLayout)).pPushConstantRanges(range);
         Vulkan.checkResult(VK10.vkCreatePipelineLayout(this.device, plInfo, null, p), "occlusion: pipeline layout");
         this.pipelineLayout = p.get(0);

         VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack);
         sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(3 * frames);
         VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(frames).pPoolSizes(sizes);
         Vulkan.checkResult(VK10.vkCreateDescriptorPool(this.device, poolInfo, null, p), "occlusion: descriptor pool");
         this.descriptorPool = p.get(0);

         LongBuffer layouts = stack.mallocLong(frames);
         for (int i = 0; i < frames; i++) {
            layouts.put(i, this.setLayout);
         }
         VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
            .descriptorPool(this.descriptorPool).pSetLayouts(layouts);
         LongBuffer pSets = stack.mallocLong(frames);
         Vulkan.checkResult(VK10.vkAllocateDescriptorSets(this.device, allocInfo, pSets), "occlusion: descriptor sets");
         for (int i = 0; i < frames; i++) {
            this.sets[i] = pSets.get(i);
         }
      }
   }

   private long shaderModule(String name, String src, SPIRVUtils.ShaderKind kind) {
      SPIRVUtils.SPIRV spirv = SPIRVUtils.compileShader(name, src, kind);
      try (MemoryStack stack = MemoryStack.stackPush()) {
         VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv.bytecode());
         LongBuffer p = stack.mallocLong(1);
         Vulkan.checkResult(VK10.vkCreateShaderModule(this.device, info, null, p), "occlusion: shader module " + name);
         return p.get(0);
      }
   }

   private long createComputePipeline() {
      long module = this.shaderModule("vmp_occlusion_filter.comp", COMPUTE_SRC, SPIRVUtils.ShaderKind.COMPUTE_SHADER);
      try (MemoryStack stack = MemoryStack.stackPush()) {
         VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
            .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
         VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
         info.get(0).sType$Default().stage(stage).layout(this.pipelineLayout).basePipelineIndex(-1);
         LongBuffer p = stack.mallocLong(1);
         Vulkan.checkResult(VK10.vkCreateComputePipelines(this.device, 0L, info, null, p), "occlusion: compute pipeline");
         return p.get(0);
      } finally {
         VK10.vkDestroyShaderModule(this.device, module, null);
      }
   }

   private long createBoxPipeline(int colorFormat, int depthFormat) {
      long vert = this.shaderModule("vmp_occlusion_box.vsh", BOX_VERT_SRC, SPIRVUtils.ShaderKind.VERTEX_SHADER);
      long frag = this.shaderModule("vmp_occlusion_box.fsh", BOX_FRAG_SRC, SPIRVUtils.ShaderKind.FRAGMENT_SHADER);
      try (MemoryStack stack = MemoryStack.stackPush()) {
         VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
         stages.get(0).sType$Default().stage(VK10.VK_SHADER_STAGE_VERTEX_BIT).module(vert).pName(stack.UTF8("main"));
         stages.get(1).sType$Default().stage(VK10.VK_SHADER_STAGE_FRAGMENT_BIT).module(frag).pName(stack.UTF8("main"));

         VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
         VkPipelineInputAssemblyStateCreateInfo assembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
            .topology(VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
         VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1);
         VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
            .polygonMode(VK10.VK_POLYGON_MODE_FILL).cullMode(VK10.VK_CULL_MODE_NONE).frontFace(VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1.0F);
         VkPipelineMultisampleStateCreateInfo ms = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
            .rasterizationSamples(VK10.VK_SAMPLE_COUNT_1_BIT);
         // 26.2 uses reversed Z: a box fragment is in front of (or level with) the scene when depth >= stored depth
         VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
            .depthTestEnable(true).depthWriteEnable(false).depthCompareOp(VK10.VK_COMPARE_OP_GREATER_OR_EQUAL).maxDepthBounds(1.0F);
         VkPipelineColorBlendAttachmentState.Buffer blendAtt = VkPipelineColorBlendAttachmentState.calloc(1, stack);
         blendAtt.get(0).colorWriteMask(0).blendEnable(false);
         VkPipelineColorBlendStateCreateInfo blend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blendAtt);
         VkPipelineDynamicStateCreateInfo dyn = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
            .pDynamicStates(stack.ints(VK10.VK_DYNAMIC_STATE_VIEWPORT, VK10.VK_DYNAMIC_STATE_SCISSOR));
         VkPipelineRenderingCreateInfoKHR rendering = VkPipelineRenderingCreateInfoKHR.calloc(stack).sType$Default()
            .colorAttachmentCount(1).pColorAttachmentFormats(stack.ints(colorFormat)).depthAttachmentFormat(depthFormat);

         VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
         info.get(0).sType$Default().pNext(rendering.address()).pStages(stages).pVertexInputState(vertexInput).pInputAssemblyState(assembly)
            .pViewportState(viewport).pRasterizationState(raster).pMultisampleState(ms).pDepthStencilState(depth)
            .pColorBlendState(blend).pDynamicState(dyn).layout(this.pipelineLayout).basePipelineIndex(-1);
         LongBuffer p = stack.mallocLong(1);
         Vulkan.checkResult(VK10.vkCreateGraphicsPipelines(this.device, 0L, info, null, p), "occlusion: box pipeline");
         return p.get(0);
      } finally {
         VK10.vkDestroyShaderModule(this.device, vert, null);
         VK10.vkDestroyShaderModule(this.device, frag, null);
      }
   }

   // ---------------------------------------------------------------- shaders

   private static final String PC_BLOCK = """
      layout(push_constant) uniform PC {
          mat4 MVP;
          uint frame;
          uint count;
          uint phase;
          float margin;
      };
      """;

   private static final String COMPUTE_SRC = "#version 460\nlayout(local_size_x = 64) in;\n" + PC_BLOCK + """
      layout(std430, binding = 0) buffer Commands { uint cmd[]; };
      layout(std430, binding = 1) buffer Visibility { uint vis[]; };

      void main() {
          uint i = gl_GlobalInvocationID.x;
          if (i >= count) return;
          uint base = i * 8u;
          uint meta = cmd[base + 5u];
          uint id = meta & 0x3FFFFFFFu;
          bool force = (meta & 0x80000000u) != 0u;
          bool translucent = (meta & 0x40000000u) != 0u;
          uint seen = vis[id];
          if (phase == 0u) {
              if (!translucent) cmd[base + 1u] = (force || seen == frame - 1u) ? 1u : 0u;
          } else if (translucent) {
              cmd[base + 1u] = (force || seen == frame) ? 1u : 0u;
          } else {
              // drawn in phase 0 -> done; otherwise draw now if the box test found it visible
              cmd[base + 1u] = (cmd[base + 1u] == 0u && seen == frame) ? 1u : 0u;
          }
      }
      """;

   private static final String BOX_VERT_SRC = "#version 460\n" + PC_BLOCK + """
      layout(std430, binding = 2) readonly buffer Boxes { uvec4 box[]; };
      layout(location = 0) flat out uint sectionId;

      // Only the (at most 3) faces turned towards the camera are drawn: 3 axes x 2 triangles = 18 vertices.
      // The camera is at the origin (box coordinates are camera relative) and never inside a tested box.
      const ivec2 QUAD[6] = ivec2[6](ivec2(0, 0), ivec2(1, 0), ivec2(1, 1), ivec2(0, 0), ivec2(1, 1), ivec2(0, 1));

      void main() {
          uvec4 b = box[gl_InstanceIndex];
          sectionId = b.w;
          vec3 lo = uintBitsToFloat(b.xyz) - vec3(margin);
          vec3 hi = lo + vec3(16.0 + 2.0 * margin);
          int axis = gl_VertexIndex / 6;
          float plane;
          if (lo[axis] > 0.0) plane = lo[axis];
          else if (hi[axis] < 0.0) plane = hi[axis];
          else { gl_Position = vec4(0.0, 0.0, 0.0, 1.0); return; } // camera between the two planes: no face, zero-area triangles
          ivec2 q = QUAD[gl_VertexIndex % 6];
          int u = (axis + 1) % 3;
          int v = (axis + 2) % 3;
          vec3 p;
          p[axis] = plane;
          p[u] = q.x == 0 ? lo[u] : hi[u];
          p[v] = q.y == 0 ? lo[v] : hi[v];
          gl_Position = MVP * vec4(p, 1.0);
      }
      """;
   private static final String BOX_FRAG_SRC = "#version 460\nlayout(early_fragment_tests) in;\n" + PC_BLOCK + """
      layout(std430, binding = 1) buffer Visibility { uint vis[]; };
      layout(location = 0) flat in uint sectionId;

      void main() {
          if (vis[sectionId] != frame) vis[sectionId] = frame;
      }
      """;
}
