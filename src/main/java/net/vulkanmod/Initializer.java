package net.vulkanmod;

import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.vulkanmod.config.Config;
import net.vulkanmod.config.Platform;
import net.vulkanmod.config.UpdateChecker;
import net.vulkanmod.render.chunk.build.frapi.VulkanModRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Initializer implements ClientModInitializer {
   public static final Logger LOGGER = LogManager.getLogger("VulkanMod+");
   private static String VERSION;
   public static Config CONFIG;

   public void onInitializeClient() {
      VERSION = ((ModContainer)FabricLoader.getInstance().getModContainer("vulkanmodplus").get()).getMetadata().getVersion().getFriendlyString();
      LOGGER.info("== VulkanMod ==");
      Path configPath = FabricLoader.getInstance().getConfigDir().resolve("vulkanmod_settings.json");
      CONFIG = loadConfig(configPath);
      Platform.init();
      Renderer.register(VulkanModRenderer.INSTANCE);
      registerFrapiFeatureRenderers();
      net.vulkanmod.boost.Bench.init(); // dev-only, env VB_BENCH
      try {
         net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry.register(ctx -> new net.vulkanmod.boost.F3TextRenderer());
      } catch (Throwable t) {
         LOGGER.warn("Could not register the cached F3 text renderer, F3 stays on the vanilla path", t);
      }
      // VulkanBoost: upstream update check disabled (it would offer upstream VulkanMod builds instead of this fork)
   }

   private static Config loadConfig(Path path) {
      return Config.load(path);
   }

   /**
    * 26.2 Fabric Renderer API submits "extended" block models / items as feature submits. Their FeatureRenderers are
    * registered by Indigo only when Indigo is the active renderer, so with VulkanBoost nothing handled them and the game
    * crashed ("No FeatureRenderer for type Extended Block Model", e.g. block entities with FRAPI models).
    * Indigo's renderers draw through vanilla RenderTypes (served by our Vulkan backend), so we register them ourselves.
    */
   private static void registerFrapiFeatureRenderers() {
      try {
         net.fabricmc.fabric.api.client.rendering.v1.FeatureRendererRegistry.register(
            net.fabricmc.fabric.api.client.renderer.v1.render.submit.ExtendedBlockModelSubmit.TYPE,
            net.fabricmc.fabric.impl.client.indigo.renderer.render.ExtendedBlockModelFeatureRenderer::new
         );
         net.fabricmc.fabric.api.client.rendering.v1.FeatureRendererRegistry.register(
            net.fabricmc.fabric.api.client.renderer.v1.render.submit.ExtendedItemSubmit.TYPE,
            net.fabricmc.fabric.impl.client.indigo.renderer.render.ExtendedItemFeatureRenderer::new
         );
         LOGGER.info("Registered FRAPI extended block/item feature renderers");
      } catch (Throwable t) {
         LOGGER.error("Could not register FRAPI feature renderers (fabric-renderer-indigo missing?)", t);
      }
   }

   public static String getVersion() {
      return VERSION;
   }
}
