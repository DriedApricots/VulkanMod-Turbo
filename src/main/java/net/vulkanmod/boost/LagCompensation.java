package net.vulkanmod.boost;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.vulkanmod.Initializer;

/**
 * VulkanMod Turbo TPS compensation (original implementation).
 *
 * When the (integrated) server cannot keep its tick rate, every server tick covers more than one tick of real time.
 * Timers that are counted in server ticks (block breaking, eating, drawing a bow) then take longer in real time than
 * the client expects. Once per server tick we measure the real time since the previous tick and convert the surplus
 * into "extra ticks" that those timers may advance by.
 *
 * Safety limits: at most {@link #MAX_EXTRA_TICKS} extra ticks per tick and a backlog of at most
 * {@link #MAX_BACKLOG_TICKS} ticks, so a single freeze (world save, pause menu) never completes an action instantly.
 * Disabled while the tick rate is frozen or sprinting (/tick command).
 */
public final class LagCompensation {
   public static final int MAX_EXTRA_TICKS = 4;
   private static final int MAX_BACKLOG_TICKS = 5;

   private static long lastTickStart;
   private static long backlogNanos;
   private static int extraTicks;

   private LagCompensation() {
   }

   /** Called at the start of every server tick. */
   public static void onServerTick(MinecraftServer server) {
      long now = System.nanoTime();
      long previous = lastTickStart;
      lastTickStart = now;
      extraTicks = 0;

      if (!Initializer.CONFIG.tpsCompensation || previous == 0L) {
         backlogNanos = 0L;
         return;
      }

      ServerTickRateManager rates = server.tickRateManager();
      if (rates.isFrozen() || rates.isSprinting()) {
         backlogNanos = 0L;
         return;
      }

      long tickNanos = rates.nanosecondsPerTick();
      if (tickNanos <= 0L) {
         return;
      }

      backlogNanos = Math.min(backlogNanos + (now - previous), tickNanos * MAX_BACKLOG_TICKS);
      long owedTicks = backlogNanos / tickNanos;          // 1 at a healthy tick rate
      int extra = (int)Math.max(0L, Math.min(owedTicks - 1L, MAX_EXTRA_TICKS));
      backlogNanos = Math.max(0L, backlogNanos - (1L + extra) * tickNanos);
      extraTicks = extra;
   }

   /** Extra ticks that tick-counted player timers may advance by during the current server tick. */
   public static int extraTicks() {
      return extraTicks;
   }

   public static void reset() {
      lastTickStart = 0L;
      backlogNanos = 0L;
      extraTicks = 0;
   }
}
