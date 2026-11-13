package net.vulkanmod.render.engine;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.MemoryType;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public class VkGpuBuffer extends GpuBuffer {
   protected boolean closed;
   @Nullable
   protected final Supplier<String> label;
   Buffer buffer;
   int offset;
   /**
    * VulkanBoost: byte ranges read by draws, kept per frame for the last few frames. A write must not touch a range
    * that a frame which is not finished on the GPU still reads: the current frame (draws already recorded) and the
    * previous frames that are still in flight (frame queue). Writes go through the transfer queue / mapped memory
    * immediately, i.e. "before" those draws execute; on a slow GPU the previous frame then drew with the new data
    * (GUI flickering, geometry jumping - seen on a GTX 750 Ti). GL drivers orphan the storage in that case, so do we.
    */
   private static final int SLOTS = 6;
   private final long[] slotFrame = {-1L, -1L, -1L, -1L, -1L, -1L};
   private final long[][] slotRanges = new long[SLOTS][];
   private final int[] slotCount = new int[SLOTS];
   private int cur;
   /** Storages retired by orphaning, reused once every frame that could read them has completed. */
   private final Buffer[] retired = new Buffer[SLOTS];
   private final long[] retiredFrame = new long[SLOTS];
   public static long FRAME;
   public static long ORPHANS; // dev counter

   private static int pendingFrames() {
      return Math.min(SLOTS, Math.max(1, net.vulkanmod.vulkan.Renderer.getFramesNum()));
   }

   void markUsed() {
      this.markUsed(0L, this.size());
   }

   void markUsed(long start, long length) {
      if (this.slotFrame[this.cur] != FRAME) {
         this.cur = (this.cur + 1) % SLOTS;
         this.slotFrame[this.cur] = FRAME;
         this.slotCount[this.cur] = 0;
         if (this.slotRanges[this.cur] == null) {
            this.slotRanges[this.cur] = new long[8];
         }
      }
      long[] ranges = this.slotRanges[this.cur];
      int count = this.slotCount[this.cur];
      long end = start + length;
      if (count > 0) {
         int last = (count - 1) * 2;
         if (start <= ranges[last + 1] && end >= ranges[last]) { // overlaps / touches the last range: merge
            ranges[last] = Math.min(ranges[last], start);
            ranges[last + 1] = Math.max(ranges[last + 1], end);
            return;
         }
      }
      if (count * 2 == ranges.length) {
         if (count >= 256) { // collapse into one range, stays conservative
            long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
            for (int i = 0; i < count * 2; i += 2) { lo = Math.min(lo, ranges[i]); hi = Math.max(hi, ranges[i + 1]); }
            ranges[0] = Math.min(lo, start); ranges[1] = Math.max(hi, end); this.slotCount[this.cur] = 1;
            return;
         }
         ranges = this.slotRanges[this.cur] = java.util.Arrays.copyOf(ranges, ranges.length * 2);
      }
      ranges[count * 2] = start;
      ranges[count * 2 + 1] = end;
      this.slotCount[this.cur] = count + 1;
   }

   /** True when a frame that has not completed on the GPU yet (current or still in flight) reads this range. */
   boolean usedByPendingFrame(long start, long length) {
      int pending = pendingFrames();
      long end = start + length;
      for (int s = 0; s < SLOTS; s++) {
         long frame = this.slotFrame[s];
         if (frame < 0L || FRAME - frame >= pending) {
            continue;
         }
         long[] ranges = this.slotRanges[s];
         for (int i = 0, n = this.slotCount[s] * 2; i < n; i += 2) {
            if (start < ranges[i + 1] && end > ranges[i]) {
               return true;
            }
         }
      }
      return false;
   }

   /** Replaces the storage: a retired one that is safe again, or a new one. The old one is retired. */
   private Buffer swapStorage() {
      Buffer old = this.buffer;
      Buffer fresh = null;
      int pending = pendingFrames();
      for (int i = 0; i < SLOTS; i++) {
         if (this.retired[i] != null && FRAME - this.retiredFrame[i] >= pending) {
            fresh = this.retired[i];
            this.retired[i] = null;
            break;
         }
      }
      if (fresh == null) {
         fresh = new Buffer(old.name, old.usage, old.type);
         fresh.createBuffer(this.size());
      }
      boolean kept = false;
      for (int i = 0; i < SLOTS; i++) {
         if (this.retired[i] == null) {
            this.retired[i] = old;
            this.retiredFrame[i] = FRAME;
            kept = true;
            break;
         }
      }
      if (!kept) {
         MemoryManager.getInstance().addToFreeable(old);
      }
      this.buffer = fresh;
      java.util.Arrays.fill(this.slotFrame, -1L); // nothing reads the new storage yet
      ORPHANS++;
      return old;
   }
   protected VkGpuBuffer(VkDebugLabel debugLabel, @Nullable Supplier<String> supplier, int usage, long size) {
      super(usage, size);
      this.label = supplier;
      int vkUsage = 0;
      if ((usage & 16) != 0) {
         vkUsage |= 1;
      }

      if ((usage & 8) != 0) {
         vkUsage |= 2 | 1; // TRANSFER_DST + TRANSFER_SRC (SRC: orphaning copies the old contents, see orphanForWrite)
      }

      if ((usage & 32) != 0) {
         vkUsage |= 128;
      }

      if ((usage & 64) != 0) {
         vkUsage |= 64;
      }

      if ((usage & 128) != 0) {
         vkUsage |= 16;
      }

      if ((usage & 256) != 0) {
         vkUsage |= 4;
      }

      if ((usage & 512) != 0) {   // 26.2: USAGE_INDIRECT_PARAMETERS -> VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
         vkUsage |= 256;
      }

      boolean mappable = (usage & 1) != 0 | (usage & 2) != 0 | (usage & 4) != 0;
      if (!mappable) {
         // VulkanBoost: device-local buffers are filled through staging copies (createBuffer(..., data) -> writeToBuffer),
         // even when vanilla doesn't ask for USAGE_COPY_DST (e.g. RenderSystem's sequential QUADS index buffer, usage=INDEX).
         // Without TRANSFER_DST the copy is invalid and the buffer stays zeroed -> End sky, sun and moon were invisible.
         vkUsage |= 2 | 1; // TRANSFER_DST + TRANSFER_SRC (SRC: orphaning copies the old contents, see orphanForWrite)
      }
      MemoryType memoryType = !mappable ? MemoryTypes.GPU_MEM : (usage & USAGE_MAP_READ) != 0 ? MemoryTypes.hostReadback() : MemoryTypes.HOST_MEM;
      // label is @Nullable in the GpuDevice API (e.g. Xaero's Minimap passes null)
      this.buffer = new Buffer(supplier != null ? supplier.get() : "unnamed buffer", vkUsage, memoryType);
      this.buffer.createBuffer(this.size());
   }

   protected VkGpuBuffer(Buffer buffer) {
      super(buffer.usage, buffer.getBufferSize());
      this.label = null;
      this.buffer = buffer;
   }

   public boolean isClosed() {
      return this.closed;
   }

   public void close() {
      if (!this.closed) {
         this.closed = true;
         MemoryManager.getInstance().addToFreeable(this.buffer);
         for (int i = 0; i < SLOTS; i++) {
            if (this.retired[i] != null) {
               MemoryManager.getInstance().addToFreeable(this.retired[i]);
               this.retired[i] = null;
            }
         }
      }
   }

   /**
    * VulkanBoost: CommandEncoder.writeToBuffer is executed on the transfer queue right away, i.e. before the draws of
    * the current frame and while earlier frames may still be in flight. If such a frame reads the target range, give
    * this GpuBuffer another storage (old contents copied first in the same transfer command buffer, unless the write
    * replaces everything) so those draws keep the data they were recorded with - GL ordering semantics.
    */
   void orphanForWrite(org.lwjgl.vulkan.VkCommandBuffer transferCb, org.lwjgl.system.MemoryStack stack, long dstOffset, long length) {
      Buffer old = this.swapStorage();
      if (dstOffset == 0L && length >= this.size()) {
         return; // fully overwritten, nothing to preserve
      }
      org.lwjgl.vulkan.VkBufferCopy.Buffer region = org.lwjgl.vulkan.VkBufferCopy.calloc(1, stack);
      region.srcOffset(0L).dstOffset(0L).size(this.size());
      org.lwjgl.vulkan.VkMemoryBarrier.Buffer barrier = org.lwjgl.vulkan.VkMemoryBarrier.calloc(1, stack);
      barrier.sType$Default().srcAccessMask(4096).dstAccessMask(2048 | 4096); // TRANSFER_WRITE -> TRANSFER_READ|WRITE
      org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier(transferCb, 4096, 4096, 0, barrier, null, null);
      org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(transferCb, old.getId(), this.buffer.getId(), region);
   }
   public Buffer getBuffer() {
      return this.buffer;
   }

   public void setOffset(int offset) {
      this.offset = offset;
   }

   public static int bufferUsageToGlEnum(int i) {
      boolean stream = (i & 4) != 0;
      if ((i & 2) != 0) {
         return stream ? 35040 : 35044;
      } else if ((i & 1) != 0) {
         return stream ? 35041 : 35045;
      } else {
         return 35044;
      }
   }

   public static VkGpuBuffer from(Buffer buffer) {
      return new VkGpuBuffer(buffer);
   }

   // 26.2: mapping moved from CommandEncoderBackend to GpuBuffer. Host-visible buffers stay persistently mapped,
   // so a mapped view is just a window into that memory.
   @Override
   public GpuBufferSlice.MappedView map(long offset, long length, boolean readable, boolean writable) {
      if (this.closed) {
         throw new IllegalStateException("Buffer already closed");
      }
      if (!readable && !writable) {
         throw new IllegalArgumentException("At least read or write must be true");
      }
      if (readable && (this.usage() & USAGE_MAP_READ) == 0) {
         throw new IllegalStateException("Buffer is not readable");
      }
      if (writable && (this.usage() & USAGE_MAP_WRITE) == 0) {
         throw new IllegalStateException("Buffer is not writable");
      }
      if (offset + length > this.size()) {
         throw new IllegalArgumentException("Cannot map " + length + " bytes at offset " + offset + " from a " + this.size() + " byte buffer");
      }
      if (writable && this.offset == 0 && this.buffer.getDataPtr() != 0L && this.usedByPendingFrame(offset, length)) {
         // VulkanBoost: writing host memory that a draw of an unfinished frame (current or in flight) reads would
         // change that draw too (GL drivers orphan the storage here). Switch to another storage.
         Buffer old = this.swapStorage();
         if (offset != 0L || length < this.size()) {
            MemoryUtil.memCopy(old.getDataPtr(), this.buffer.getDataPtr(), this.size());
         }
      }      ByteBuffer data = MemoryUtil.memByteBuffer(this.buffer.getDataPtr() + offset, (int) length);
      return new GpuBufferSlice.MappedView(this.slice(offset, length), data, () -> {});
   }
}
