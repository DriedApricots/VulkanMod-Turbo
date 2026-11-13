package net.vulkanmod.boost;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import net.vulkanmod.Initializer;

/**
 * Diagnostics, off by default: with -Dvulkanmodturbo.hitchlog=true every frame longer than 50 ms is logged as
 * "VBHITCH" together with the JVM uptime (to line it up with a JFR recording) and what VulkanMod Turbo did since the
 * previous frame (sign / chest section rebakes, shader compiles, GC). Costs one nanoTime call per frame when on.
 */
public final class HitchLog {
   private static final boolean ENABLED = Boolean.getBoolean("vulkanmodturbo.hitchlog");
   private static final long THRESHOLD_NS = 50_000_000L;
   private static final int MAX_LINES = 2000;

   public static int signRebuilds, chestRebuilds, shaderCompiles;

   private static long last;
   private static int lines;
   private static long gcCount, gcTime;
   private static long frames, slowFrames;
   private static long windowStart;

   private HitchLog() {
   }

   public static boolean enabled() {
      return ENABLED;
   }

   public static void onFrame() {
      if (!ENABLED) {
         return;
      }

      long now = System.nanoTime();
      long dt = last == 0L ? 0L : now - last;
      last = now;
      frames++;
      if (windowStart == 0L) {
         windowStart = now;
      }

      long gcc = 0L, gct = 0L;
      if (dt > THRESHOLD_NS) {
         List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
         for (GarbageCollectorMXBean gc : gcs) {
            gcc += Math.max(0L, gc.getCollectionCount());
            gct += Math.max(0L, gc.getCollectionTime());
         }
         slowFrames++;
         if (lines++ < MAX_LINES) {
            Initializer.LOGGER.info("VBHITCH frame={}ms uptime={}ms signRebakes={} chestRebakes={} shaderCompiles={} gcCyclesSincePrevHitch={} gcMsSincePrevHitch={}",
               dt / 1_000_000L, ManagementFactory.getRuntimeMXBean().getUptime(), signRebuilds, chestRebuilds, shaderCompiles,
               gcc - gcCount, gct - gcTime);
         }
         gcCount = gcc;
         gcTime = gct;
      }

      if (now - windowStart > 10_000_000_000L) { // every 10 s: frame count and number of slow frames
         if (lines++ < MAX_LINES) {
            Initializer.LOGGER.info("VBHITCH summary 10s: frames={} slow={} uptime={}ms", frames, slowFrames, ManagementFactory.getRuntimeMXBean().getUptime());
         }
         frames = 0L;
         slowFrames = 0L;
         windowStart = now;
      }

      signRebuilds = 0;
      chestRebuilds = 0;
      shaderCompiles = 0;
   }
}
