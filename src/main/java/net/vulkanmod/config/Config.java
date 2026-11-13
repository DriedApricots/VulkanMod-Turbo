package net.vulkanmod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import net.vulkanmod.config.video.VideoModeSet;

public class Config {
   public VideoModeSet.VideoMode videoMode = VideoModeSet.getDummy().getVideoMode();
   public int windowMode = 0;
   public int advCulling = 2;
   public boolean indirectDraw = true;
   public boolean gpuOcclusionCulling = true;   // VulkanMod+: Nvidium-style GPU occlusion culling of sections
   public boolean uniqueOpaqueLayer = true;
   public boolean entityCulling = true;
   public boolean tpsCompensation = true;   // VulkanBoost: lag compensation for block breaking / item use (singleplayer)
   // VulkanBoost invisible optimizations (no visual change; kept switchable for A/B benchmarks and as a safety valve)
   public boolean particleLightCache = true;
   public boolean fastTranslucentSort = true;
   public boolean textLayoutCache = true;
   public boolean itemBoxCache = true;
   public boolean staticChests = true;
   public boolean staticSignText = true;
   public boolean spirvCache = true;
   // VulkanBoost visible trade-offs (0 = off / vanilla)
   public int particleLimit = 0;          // max live particles
   public int itemRenderDistance = 0;     // blocks, dropped items
   public int mobRenderDistance = 0;      // blocks, mobs/animals (players, bosses, glowing and named entities always drawn)
   // VulkanBoost "Extra" settings (Sodium Extra style). Defaults = vanilla behaviour.
   // Animations
   public boolean animWater = true;
   public boolean animLava = true;
   public boolean animFire = true;
   public boolean animPortal = true;
   public boolean animOther = true;
   // Particles
   public boolean particles = true;
   public int particleRenderDistance = 0;   // blocks, 0 = off (like Particle Core's distance culling)
   public boolean blockBreakParticles = true;
   public boolean blockBreakingParticles = true;
   public java.util.List<String> disabledParticles = new java.util.ArrayList<>();
   // Details
   public boolean sky = true;
   public boolean sun = true;
   public boolean moon = true;
   public boolean stars = true;
   public boolean weather = true;
   public boolean beaconBeam = true;
   // Render
   public boolean fog = true;
   public boolean itemFrames = true;
   public boolean armorStands = true;
   public boolean paintings = true;
   public boolean playerNameTags = true;
   public boolean entityNameTags = true;
   public boolean enchantingBook = true;
   public boolean pistonAnimation = true;
   // Extra
   public int fpsOverlay = 0;               // 0 off, 1 top left, 2 top right, 3 bottom left, 4 bottom right
   public boolean coordsOverlay = false;
   public boolean advancementToasts = true;
   public boolean recipeToasts = true;
   public boolean tutorialToasts = true;
   public boolean musicToasts = true;

   private static java.util.Set<String> disabledParticleSet;

   public boolean isParticleDisabled(String id) {
      java.util.Set<String> set = disabledParticleSet;
      if (set == null) {
         set = disabledParticleSet = new java.util.HashSet<>(this.disabledParticles);
      }
      return set.contains(id);
   }

   public void setParticleEnabled(String id, boolean enabled) {
      this.disabledParticles.remove(id);
      if (!enabled) {
         this.disabledParticles.add(id);
      }
      disabledParticleSet = null;
   }
   public int device = -1;
   public boolean useWayland = false;
   public int ambientOcclusion = 1;
   public int frameQueueSize = 2;
   public int builderThreads = 0;
   public boolean backFaceCulling = true;
   public boolean textureAnimations = true;
   private static Path CONFIG_PATH;
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().excludeFieldsWithModifiers(new int[]{2}).create();

   public void write() {
      if (!Files.exists(CONFIG_PATH.getParent())) {
         try {
            Files.createDirectories(CONFIG_PATH);
         } catch (IOException e) {
            e.printStackTrace();
         }
      }

      try {
         Files.write(CONFIG_PATH, Collections.singleton(GSON.toJson(this)));
      } catch (IOException e) {
         e.printStackTrace();
      }
   }

   public static Config load(Path path) {
      CONFIG_PATH = path;
      Config config;
      if (Files.exists(path)) {
         try (FileReader fileReader = new FileReader(path.toFile())) {
            config = (Config)GSON.fromJson(fileReader, Config.class);
         } catch (IOException exception) {
            throw new RuntimeException(exception.getMessage());
         }
      } else {
         config = new Config();
         config.write();
      }

      return config;
   }
}
