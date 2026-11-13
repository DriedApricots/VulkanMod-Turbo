package net.vulkanmod.render.sky;

/** Single VulkanMod cloud renderer shared between the LevelRenderer and LevelExtractor mixins (26.2 split). */
public final class CloudRendererHolder {
   private static CloudRenderer instance;

   private CloudRendererHolder() {
   }

   public static CloudRenderer get() {
      if (instance == null) {
         instance = new CloudRenderer();
      }

      return instance;
   }

   public static void reset() {
      if (instance != null) {
         instance.resetBuffer();
      }
   }

   public static void reload() {
      if (instance != null) {
         instance.loadTexture();
      }
   }
}
