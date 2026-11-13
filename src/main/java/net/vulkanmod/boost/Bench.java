package net.vulkanmod.boost;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.vulkanmod.Initializer;

/**
 * Dev-only performance bench, enabled with env VB_BENCH=1 (world "vbbench", datapack from tools/gen-bench.js).
 * For each scene: run "function vbbench:view_scene", let it settle, then measure average FPS and the 1% low
 * (FPS of the slowest 1% frames = stutter). With VB_BENCH_AB=1 every scene is measured with all toggles off (A)
 * and on (B), so each optimization is compared in the same run. Results: "VBBENCH ..." lines in the log.
 */
public final class Bench {
   /** An optimization switch for A/B runs. */
   public record Toggle(String name, Consumer<Boolean> set) {}

   public static final List<Toggle> TOGGLES = new ArrayList<>();
   private static final int BUILD_TICK = 40, SETTLE_TICKS = 160, MEASURE_TICKS = 160;
   private static final java.util.Set<String> BUILT = new java.util.HashSet<>();

   private static boolean enabled, ab;
   /** dev: runs once per frame right before the frame is presented (VB_CAPTURE). */
   public static Runnable CAPTURE_HOOK;
   private static List<String> scenes = List.of();
   private static int stage = -2, ticks, sceneIdx, pass;
   private static boolean measuring;
   private static long lastFrame;
   private static long[] frameTimes = new long[1 << 16];
   private static int frameCount;
   private static long lightNanos;

   /** Client light engine time is only measured on the render thread while a scene is measured. */
   public static boolean measuringLight() {
      return enabled && measuring && com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread();
   }

   public static void addLightNanos(long nanos) {
      lightNanos += nanos;
   }

   public static void init() {
      if (System.getenv("VB_AUTOSTOP") != null) { // dev: startup timing - quit cleanly 3 s after the title screen
         long[] seen = {0L};
         ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen) {
               if (seen[0] == 0L) {
                  seen[0] = System.currentTimeMillis();
                  Initializer.LOGGER.info("VBSTART title screen reached, uptime {} ms", java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime());
                  try { // dev check: loading OptionsScreen applies OptionsScreenM (fails loudly if its target is gone)
                     Class.forName("net.minecraft.client.gui.screens.options.OptionsScreen");
                     Initializer.LOGGER.info("VBSTART OptionsScreen mixin applied");
                  } catch (Throwable t) {
                     Initializer.LOGGER.error("VBSTART OptionsScreen mixin FAILED", t);
                  }
               } else if (System.currentTimeMillis() - seen[0] > 3000L) {
                  seen[0] = Long.MAX_VALUE / 2;
                  mc.stop();
               }
            }
         });
      }

      String spin = System.getenv("VB_SPIN"); // dev: turn the camera smoothly by N degrees per tick (flicker / judder tests)
      if (spin != null) {
         float step = Float.parseFloat(spin);
         ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player != null && mc.gui.screen() == null) {
               mc.player.setYRot(mc.player.getYRot() + step);
            }
         });
      }

      String capture = System.getenv("VB_CAPTURE"); // dev: N consecutive in-game screenshots, 30 s after joining a world
      if (capture != null) {
         int count = Integer.parseInt(capture);
         int[] state = {0, 0}; // ticks in world, frames captured
         ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.level != null && state[0] < 600) {
               state[0]++;
               String page = System.getenv("VB_CAPTURE_PAGE");
               if (page != null && state[0] == 560) { // dev: open a Vulkan settings page before the capture
                  net.vulkanmod.config.gui.VOptionScreen screen = new net.vulkanmod.config.gui.VOptionScreen(net.minecraft.network.chat.Component.literal("Video Setting"), null);
                  mc.gui.setScreen(screen);
                  screen.selectPage(Integer.parseInt(page));
               }
            }
         });
         CAPTURE_HOOK = () -> {
            Minecraft mc = Minecraft.getInstance();
            if (state[0] >= 600 && state[1] < count) {
               net.minecraft.client.Screenshot.grab(mc.gameDirectory, String.format("cap_%03d.png", state[1]), mc.gameRenderer.mainRenderTarget(), 1, c -> {});
               state[1]++;
            }
         };
      }

      if (System.getenv("VB_BENCH") == null) {
         return;
      }

      enabled = true;
      var cfg = Initializer.CONFIG;
      TOGGLES.add(new Toggle("particleLightCache", v -> cfg.particleLightCache = v));
      TOGGLES.add(new Toggle("fastTranslucentSort", v -> cfg.fastTranslucentSort = v));
      TOGGLES.add(new Toggle("textLayoutCache", v -> cfg.textLayoutCache = v));
      TOGGLES.add(new Toggle("itemBoxCache", v -> cfg.itemBoxCache = v));
      TOGGLES.add(new Toggle("staticChests", v -> cfg.staticChests = v));
      TOGGLES.add(new Toggle("staticSignText", v -> cfg.staticSignText = v));
      if (System.getenv("VB_BENCH_VISIBLE") != null) { // visible trade-off options, shown to the user with screenshots
         TOGGLES.add(new Toggle("particleLimit=2000", v -> cfg.particleLimit = v ? 2000 : 0));
         TOGGLES.add(new Toggle("itemRenderDistance=24", v -> cfg.itemRenderDistance = v ? 24 : 0));
         TOGGLES.add(new Toggle("mobRenderDistance=32", v -> cfg.mobRenderDistance = v ? 32 : 0));
      }
      TOGGLES.add(new Toggle("gpuOcclusionCulling", v -> cfg.gpuOcclusionCulling = v));
      String onlyToggles = System.getenv("VB_BENCH_TOGGLES"); // e.g. "gpuOcclusionCulling": A/B only these
      if (onlyToggles != null) {
         List<String> keep = Arrays.asList(onlyToggles.split(","));
         TOGGLES.removeIf(t -> !keep.contains(t.name()));
      }
      Initializer.LOGGER.info("VBBENCH selftest FastSort={}", FastSort.selfTest());
      ab = System.getenv("VB_BENCH_AB") != null;
      String only = System.getenv("VB_BENCH_SCENES");
      ClientTickEvents.END_CLIENT_TICK.register(Bench::tick);
      if (only != null) {
         scenes = Arrays.asList(only.split(","));
      }
   }

   // dev (VB_YAWLOG): per-frame camera yaw while the user turns the mouse steadily, to measure input judder
   private static final boolean YAWLOG = System.getenv("VB_YAWLOG") != null;
   private static long yawStart;
   private static int yawCount;
   private static final long[] yawTime = new long[20000];
   private static final float[] yawValue = new float[20000];

   /** Called when a frame is presented (VkGpuSurface.present). */
   public static void yawFrame() {
      if (!YAWLOG) {
         return;
      }
      Minecraft mc = Minecraft.getInstance();
      if (mc.player == null || mc.level == null || mc.gui.screen() != null) {
         return;
      }
      long now = System.nanoTime();
      if (yawStart == 0L) {
         yawStart = now;
         mc.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal("VBTEST: in 5 s turn the mouse steadily left/right for 15 s"));
         return;
      }
      long t = now - yawStart;
      if (t < 5_000_000_000L || yawCount >= yawTime.length) {
         return;
      }
      if (t < 20_000_000_000L) {
         yawTime[yawCount] = now;
         yawValue[yawCount] = mc.player.getYRot();
         yawCount++;
      } else if (yawCount > 0) {
         int n = yawCount;
         yawCount = yawTime.length; // report once
         int moving = 0, stalls = 0;
         double sum = 0, sumSq = 0;
         float[] d = new float[n];
         for (int i = 1; i < n; i++) {
            d[i] = Math.abs(yawValue[i] - yawValue[i - 1]);
         }
         for (int i = 2; i < n - 1; i++) {
            boolean neighboursMove = d[i - 1] > 0.05F && d[i + 1] > 0.05F;
            if (neighboursMove) {
               moving++;
               if (d[i] < 0.01F) {
                  stalls++; // no camera change in this frame although the mouse was moving around it
               } else {
                  double dt = (yawTime[i] - yawTime[i - 1]) / 1.0e9;
                  double v = d[i] / dt;
                  sum += v;
                  sumSq += v * v;
               }
            }
         }
         int vn = Math.max(1, moving - stalls);
         double mean = sum / vn, sd = Math.sqrt(Math.max(0, sumSq / vn - mean * mean));
         String msg = String.format(java.util.Locale.ROOT, "VBYAW frames=%d moving=%d stalls=%d (%.1f%%) speedCV=%.2f avgFrame=%.2fms",
            n, moving, stalls, 100.0 * stalls / Math.max(1, moving), mean > 0 ? sd / mean : 0, (yawTime[n - 1] - yawTime[0]) / 1.0e6 / Math.max(1, n - 1));
         Initializer.LOGGER.info(msg);
         try { // raw per-frame data: time since previous frame (ms), yaw change (deg)
            StringBuilder csv = new StringBuilder("dt_ms,dyaw\n");
            for (int i = 1; i < n; i++) {
               csv.append(String.format(java.util.Locale.ROOT, "%.3f,%.4f%n", (yawTime[i] - yawTime[i - 1]) / 1.0e6, yawValue[i] - yawValue[i - 1]));
            }
            java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("vbyaw.csv"), csv);
         } catch (java.io.IOException e) {
            Initializer.LOGGER.warn("vbyaw.csv", e);
         }
         mc.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal("VBTEST done: " + msg));
      }
   }

   /** Called once per rendered frame (Renderer.beginFrame). */
   public static void onFrame() {
      if (!enabled) {
         return;
      }

      long now = System.nanoTime();
      if (measuring && lastFrame != 0L && frameCount < frameTimes.length) {
         frameTimes[frameCount++] = now - lastFrame;
      }

      lastFrame = now;
   }

   private static void run(Minecraft mc, String cmd) {
      MinecraftServer server = mc.getSingleplayerServer();
      if (server != null) {
         server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd));
      }
   }

   private static void setToggles(boolean on) {
      for (Toggle t : TOGGLES) {
         t.set().accept(on);
      }
   }

   private static void tick(Minecraft mc) {
      if (mc.level == null || mc.player == null || stage == Integer.MAX_VALUE) {
         return;
      }

      ticks++;
      if (stage == -2) { // world joined: build all scenes
         if (ticks == 100) {
            if (scenes.isEmpty()) {
               scenes = List.of("baseline", "f3", "chests", "signs", "holograms", "items", "mobs", "particles", "particles_behind", "tnt", "light", "flight");
            }
            Initializer.LOGGER.info("VBBENCH building scenes {} (ab={}, toggles={})", scenes, ab, TOGGLES.stream().map(Toggle::name).toList());
            run(mc, "function vbbench:build");
         } else if (ticks >= 400) {
            stage = 0; ticks = 0; sceneIdx = 0; pass = ab ? 0 : 1;
            startScene(mc);
         }
         return;
      }

      String scene = scenes.get(sceneIdx);
      if (scene.equals("flight")) { // fly 1.5 blocks per tick over real terrain: chunk building / loading stutter
         run(mc, "execute in minecraft:overworld run tp @a " + (200 + ticks * 1.5) + " 110 200 -90 20");
      }

      if (ticks == BUILD_TICK) {
         if (scene.equals("tnt")) {
            run(mc, "function vbbench:tnt_reset");
         } else if (!scene.equals("f3") && !scene.equals("flight") && BUILT.add(scene)) {
            run(mc, "function vbbench:build_" + scene);
            ticks -= 160; // first visit: give the big build + chunk rebuilds time before measuring
         }
         if (!scene.equals("flight")) { // chunks are loaded now: place the camera barrier for real
            run(mc, "function vbbench:view_" + (scene.equals("f3") ? "baseline" : scene));
         }
      }

      if (ticks == SETTLE_TICKS - 10 && System.getenv("VB_BENCH_SHOTS") != null) { // in-game screenshot -> run/screenshots/bench_<scene>_<pass>.png
         net.minecraft.client.Screenshot.grab(mc.gameDirectory, "bench_" + scene + "_" + label() + ".png", mc.gameRenderer.mainRenderTarget(), 1, c -> {});
      }

      if (!measuring && ticks == SETTLE_TICKS) {
         if (scene.equals("tnt")) {
            run(mc, "function vbbench:tnt_ignite");
         }
         measuring = true; frameCount = 0; lastFrame = 0L; lightNanos = 0L; net.vulkanmod.render.engine.VkGpuBuffer.ORPHANS = 0L;
         Initializer.LOGGER.info("VBBENCH shot {}_{}", scene, label());
      } else if (measuring && ticks == SETTLE_TICKS + MEASURE_TICKS) {
         measuring = false;
         report(scene);
         endScene(mc, scene);
         if (ab && pass == 0) {
            pass = 1;
         } else {
            pass = ab ? 0 : 1;
            sceneIdx++;
         }

         if (sceneIdx >= scenes.size()) {
            Initializer.LOGGER.info("VBBENCH done");
            stage = Integer.MAX_VALUE;
            setToggles(true);
            return;
         }

         ticks = 0;
         startScene(mc);
      }
   }

   private static void startScene(Minecraft mc) {
      String scene = scenes.get(sceneIdx);
      setToggles(pass == 1);
      switch (scene) {
         case "f3" -> {
            run(mc, "function vbbench:view_baseline");
            mc.debugEntries.setOverlayVisible(true);
         }
         case "flight" -> run(mc, "execute in minecraft:overworld run tp @a 200 110 200 -90 20");
         default -> run(mc, "function vbbench:view_" + scene);
      }
   }

   private static void endScene(Minecraft mc, String scene) {
      if (scene.equals("f3")) {
         mc.debugEntries.setOverlayVisible(false);
      }
      if (scene.startsWith("particles") || scene.equals("light")) {
         run(mc, "function vbbench:stop_particles");
      }
   }

   private static void report(String scene) {
      int n = frameCount;
      if (n < 10) {
         Initializer.LOGGER.info("VBBENCH {} {}: too few frames ({})", scene, label(), n);
         return;
      }

      long total = 0L;
      for (int i = 0; i < n; i++) {
         total += frameTimes[i];
      }

      long[] sorted = Arrays.copyOf(frameTimes, n);
      Arrays.sort(sorted);
      int worst = Math.max(1, n / 100);
      long worstTotal = 0L;
      for (int i = n - worst; i < n; i++) {
         worstTotal += sorted[i];
      }

      double avgFps = n * 1.0e9 / total;
      double lowFps = worst * 1.0e9 / worstTotal;
      Initializer.LOGGER.info("VBBENCH orphans/frame=" + (double)net.vulkanmod.render.engine.VkGpuBuffer.ORPHANS / Math.max(1, n)); Initializer.LOGGER.info(String.format(java.util.Locale.ROOT, "VBBENCH %-17s %-3s avgFps=%7.1f  1%%low=%7.1f  maxFrame=%6.2fms  frames=%d  clientLight=%.2fms/s",
         scene, label(), avgFps, lowFps, sorted[n - 1] / 1.0e6, n, lightNanos / 1.0e6 / (total / 1.0e9)));
   }

   private static String label() {
      return ab ? (pass == 0 ? "A" : "B") : "-";
   }
}
