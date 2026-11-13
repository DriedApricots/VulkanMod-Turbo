package net.vulkanmod.boost;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.HashMap;
import java.util.Iterator;
import java.util.function.Supplier;
import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;
import net.vulkanmod.Initializer;

/**
 * VulkanMod Turbo: world text (name tags, holograms, signs, text displays) was laid out from scratch every frame:
 * code point decoding, glyph lookup per character, glyph instance creation. A {@link Font.PreparedText} is position
 * relative (the pose is applied when its glyphs are emitted), so it is cached per text object + layout parameters.
 * <ul>
 * <li>Keys use the identity of the FormattedCharSequence: components and sign texts keep theirs while unchanged, so a
 * changed text simply misses the cache.</li>
 * <li>Obfuscated (&amp;k) text picks random glyphs on every layout and is never cached, so it keeps animating.</li>
 * <li>Entries unused for {@link #EXPIRE_FRAMES} frames are dropped; everything is dropped on font reload.</li>
 * </ul>
 */
public final class TextPrepareCache {
   public static final int KIND_TEXT = 0, KIND_OUTLINE = 1;
   /** Whether the last get() returned a cached (stable) layout - false for obfuscated text or when disabled. */
   public static boolean lastCached;
   private static final int EXPIRE_FRAMES = 240, MAX_ENTRIES = 8192;
   private static final HashMap<Key, Entry> CACHE = new HashMap<>();
   private static final Key PROBE = new Key();
   private static long frame;

   private TextPrepareCache() {
   }

   /**
    * @param key the text object: a FormattedCharSequence (compared by identity) or a Component (compared by value -
    *            name tags may rebuild their component every frame)
    * @param text the sequence to lay out
    */
   public static Font.PreparedText get(Font font, Object key, FormattedCharSequence text, float x, float y, int color, boolean shadow, int background, int kind,
                                       Supplier<Font.PreparedText> compute) {
      if (!Initializer.CONFIG.textLayoutCache || !RenderSystem.isOnRenderThread()) {
         lastCached = false;
         return compute.get();
      }

      Key probe = PROBE.set(font, key, x, y, color, shadow, background, kind);
      Entry entry = CACHE.get(probe);
      if (entry == null) {
         entry = new Entry();
         entry.obfuscated = isObfuscated(text);
         if (!entry.obfuscated) {
            entry.text = compute.get();
         }
         if (CACHE.size() >= MAX_ENTRIES) {
            CACHE.clear();
         }
         CACHE.put(new Key().set(font, key, x, y, color, shadow, background, kind), entry);
      }

      entry.lastFrame = frame;
      lastCached = !entry.obfuscated;
      return entry.obfuscated ? compute.get() : entry.text;
   }

   public static boolean isObfuscated(FormattedCharSequence text) {
      boolean[] found = new boolean[1];
      text.accept((position, style, codepoint) -> {
         if (style.isObfuscated()) {
            found[0] = true;
            return false;
         }
         return true;
      });
      return found[0];
   }

   /** Once per frame (render thread). */
   public static void onFrame() {
      if (++frame % 60 == 0 && !CACHE.isEmpty()) {
         long limit = frame - EXPIRE_FRAMES;
         for (Iterator<Entry> it = CACHE.values().iterator(); it.hasNext(); ) {
            if (it.next().lastFrame < limit) {
               it.remove();
            }
         }
      }
   }

   /** Bumped on every font reload; baked text (StaticSignText) includes it in its signature. */
   public static int fontGeneration;

   public static void clear() {
      RenderSystem.assertOnRenderThread();
      fontGeneration++;
      CACHE.clear();
   }

   private static final class Entry {
      Font.PreparedText text;
      boolean obfuscated;
      long lastFrame;
   }

   private static final class Key {
      Font font;
      Object text;
      boolean byValue;
      float x, y;
      int color, background, kind;
      boolean shadow;
      int hash;

      Key set(Font font, Object text, float x, float y, int color, boolean shadow, int background, int kind) {
         this.font = font;
         this.text = text;
         this.byValue = !(text instanceof FormattedCharSequence);
         this.x = x;
         this.y = y;
         this.color = color;
         this.shadow = shadow;
         this.background = background;
         this.kind = kind;
         int h = this.byValue ? text.hashCode() : System.identityHashCode(text);
         h = h * 31 + System.identityHashCode(font);
         h = h * 31 + Float.floatToIntBits(x);
         h = h * 31 + Float.floatToIntBits(y);
         h = h * 31 + color;
         h = h * 31 + background;
         h = h * 31 + kind * 2 + (shadow ? 1 : 0);
         this.hash = h;
         return this;
      }

      @Override
      public int hashCode() {
         return this.hash;
      }

      @Override
      public boolean equals(Object o) {
         return o instanceof Key k && k.byValue == this.byValue && (this.byValue ? k.text.equals(this.text) : k.text == this.text) && k.font == this.font && Float.floatToIntBits(k.x) == Float.floatToIntBits(this.x)
            && Float.floatToIntBits(k.y) == Float.floatToIntBits(this.y) && k.color == this.color && k.background == this.background
            && k.kind == this.kind && k.shadow == this.shadow;
      }
   }
}
