package net.vulkanmod.boost;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.vulkanmod.Initializer;

/**
 * VulkanMod Turbo: name tags (and server holograms made of named armor stands) get a brand-new Component every frame
 * (Entity.getDisplayName()). Everything vanilla caches per component instance - the bidi visual order, and the width
 * used to centre the tag - was therefore recomputed every frame for every tag (about a third of the frame with 400
 * holograms). Equal components are mapped to one canonical instance, so those caches work again, and the width is
 * cached per canonical instance.
 */
public final class NameTagCache {
   private static final int EXPIRE_FRAMES = 240, MAX_ENTRIES = 8192;
   private static final HashMap<Component, Entry> CANONICAL = new HashMap<>();
   private static long frame;
   private static int fontGeneration = -1;

   private NameTagCache() {
   }

   private static final class Entry {
      final Component component;
      int width = -1;
      long lastFrame;

      Entry(Component component) {
         this.component = component;
      }
   }

   private static Entry entry(Component name) {
      if (fontGeneration != TextPrepareCache.fontGeneration) { // fonts reloaded: widths are stale
         fontGeneration = TextPrepareCache.fontGeneration;
         CANONICAL.clear();
      }
      Entry e = CANONICAL.get(name);
      if (e == null) {
         if (CANONICAL.size() >= MAX_ENTRIES) {
            CANONICAL.clear();
         }
         e = new Entry(name);
         CANONICAL.put(name, e);
      }
      e.lastFrame = frame;
      return e;
   }

   /** The canonical instance of an equal component (render thread only). */
   public static Component intern(Component name) {
      if (!Initializer.CONFIG.textLayoutCache) {
         return name;
      }
      return entry(name).component;
   }

   /** Width of a (canonical) name, computed once. */
   public static int width(Font font, Component name) {
      if (!Initializer.CONFIG.textLayoutCache) {
         return font.width(name);
      }
      Entry e = entry(name);
      if (e.width < 0) {
         e.width = font.width(e.component);
      }
      return e.width;
   }

   /** Once per frame (render thread): drop entries not used for a while. */
   public static void onFrame() {
      if (++frame % 60 == 0 && !CANONICAL.isEmpty()) {
         long limit = frame - EXPIRE_FRAMES;
         for (Iterator<Map.Entry<Component, Entry>> it = CANONICAL.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().lastFrame < limit) {
               it.remove();
            }
         }
      }
   }
}