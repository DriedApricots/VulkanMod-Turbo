package net.vulkanmod.render.engine;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.TransientMemory;
import com.mojang.blaze3d.util.TransientBlockAllocator;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.Mth;
import org.lwjgl.system.MemoryUtil;

/**
 * 26.2 port: per-frame transient memory requested by vanilla through CommandEncoderBackend.transientMemory().
 * Blocks are host-visible VulkanMod buffers (persistently mapped), sub-allocated with vanilla's
 * TransientBlockAllocator and recycled once the frames that used them have finished on the GPU.
 */
@Environment(EnvType.CLIENT)
public class VkTransientMemory implements TransientMemory {
   private static final long BLOCK_SIZE = 524288L;
   private static final long MAX_CPU_ALIGNMENT = 16L;
   private static final long MAX_GPU_ALIGNMENT = 256L;
   private static final int BLOCK_USAGE = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC
      | GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_INDIRECT_PARAMETERS;
   /** frames that may still be in flight on the GPU before a block can be reused */
   private static final int KEEP_FRAMES = 3;

   private final VkDebugLabel debugLabels;
   private final TransientBlockAllocator<Long> cpuAllocator = new TransientBlockAllocator<>(
      BLOCK_SIZE, MAX_CPU_ALIGNMENT, TransientBlockAllocator.Allocator.create(MemoryUtil::nmemAlloc, MemoryUtil::nmemFree)
   );
   private final TransientBlockAllocator<VkGpuBuffer> gpuAllocator;
   private final ArrayDeque<Runnable> pendingFrees = new ArrayDeque<>();

   VkTransientMemory(VkDebugLabel debugLabels) {
      this.debugLabels = debugLabels;
      this.gpuAllocator = new TransientBlockAllocator<>(
         BLOCK_SIZE, MAX_GPU_ALIGNMENT, TransientBlockAllocator.Allocator.create(this::allocateBlock, VkGpuBuffer::close)
      );
   }

   private VkGpuBuffer allocateBlock(long size) {
      return new VkGpuBuffer(this.debugLabels, () -> "VulkanMod Transient Buffer", BLOCK_USAGE, size);
   }

   /** Call once per frame (frame begin). */
   public void rotate() {
      this.pendingFrees.add(this.gpuAllocator.rotate());
      this.pendingFrees.add(this.cpuAllocator.rotate());
      while (this.pendingFrees.size() > KEEP_FRAMES * 2) {
         this.pendingFrees.poll().run();
      }
   }

   private ByteBuffer hostView(TransientBlockAllocator.Allocation<VkGpuBuffer> alloc) {
      return MemoryUtil.memByteBuffer(alloc.block().getBuffer().getDataPtr() + alloc.offset(), (int) alloc.size());
   }

   @Override
   public ByteBuffer allocateCpu(long size, long alignment, long minimumAllocation, long elementSize) {
      TransientBlockAllocator.Allocation<Long> alloc = this.cpuAllocator.allocate(size, alignment, minimumAllocation, elementSize);
      return MemoryUtil.memByteBuffer(alloc.block() + alloc.offset(), (int) alloc.size());
   }

   @Override
   public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
      return this.allocateGpuMapped(size, alignment, usage, minimumAllocation, elementSize);
   }

   @Override
   public GpuBufferSlice allocateGpu(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
      TransientBlockAllocator.Allocation<VkGpuBuffer> alloc = this.gpuAllocator.allocate(size, alignment, minimumAllocation, elementSize);
      return new GpuBufferSlice(alloc.block(), alloc.offset(), alloc.size());
   }

   @Override
   public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
      TransientBlockAllocator.Allocation<VkGpuBuffer> alloc = this.gpuAllocator.allocate(size, alignment, minimumAllocation, elementSize);
      return new GpuBufferSlice.MappedView(new GpuBufferSlice(alloc.block(), alloc.offset(), alloc.size()), this.hostView(alloc), () -> {});
   }

   @Override
   public GpuBufferSlice uploadStaging(List<ByteBuffer> data, long alignment, int usage, long minimumAllocation, long elementSize) {
      return this.uploadGpu(data, alignment, usage, minimumAllocation, elementSize);
   }

   @Override
   public GpuBufferSlice uploadGpu(List<ByteBuffer> data, long alignment, int usage, long minimumAllocation, long elementSize) {
      long totalSize = 0L;
      for (ByteBuffer buffer : data) {
         totalSize += buffer.remaining();
         totalSize = Mth.roundToward(totalSize, alignment);
      }

      GpuBufferSlice.MappedView mapped = this.allocateGpuMapped(totalSize, alignment, usage, minimumAllocation, elementSize);
      long dst = MemoryUtil.memAddress(mapped.data());
      long offset = 0L;
      for (ByteBuffer buffer : data) {
         long n = Math.min(mapped.slice().length() - offset, buffer.remaining());
         MemoryUtil.memCopy(MemoryUtil.memAddress(buffer), dst + offset, n);
         offset = Mth.roundToward(offset + buffer.remaining(), alignment);
         if (offset >= mapped.slice().length()) {
            break;
         }
      }
      return mapped.slice();
   }

   @Override
   public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long alignment, int usage) {
      return this.multiUploadGpu(data, alignment, usage);
   }

   @Override
   public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long alignment, int usage) {
      List<GpuBufferSlice> out = new ArrayList<>(data.size());
      for (ByteBuffer buffer : data) {
         GpuBufferSlice.MappedView view = this.allocateGpuMapped(buffer.remaining(), alignment, usage);
         MemoryUtil.memCopy(buffer, view.data());
         out.add(view.slice());
      }
      return out;
   }
}
