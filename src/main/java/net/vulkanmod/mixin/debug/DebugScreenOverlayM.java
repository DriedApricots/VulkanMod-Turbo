package net.vulkanmod.mixin.debug;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.vulkanmod.boost.F3TextRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * VulkanBoost: F3 entries were recomputed every frame (string formatting, memory/chunk/entity statistics...).
 * At thousands of FPS that is a noticeable share of the frame, while the text can't visibly change faster than the
 * game ticks. Each entry's output is recorded and replayed; it is recomputed at most every {@link #REFRESH_NS}.
 */
@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOverlayM {
   @Unique private static final long REFRESH_NS = 50_000_000L; // 20 Hz, same as the tick rate
   @Unique private final Map<DebugScreenEntry, List<Object[]>> vb$cache = new IdentityHashMap<>();
   @Unique private long vb$lastRefresh;
   @Unique private boolean vb$refresh;

   @Unique private List<String> vb$left = List.of();
   @Unique private List<String> vb$shownLeft = List.of();
   @Unique private List<String> vb$shownRight = List.of();
   @Unique private long vb$version;

   @Shadow
   protected abstract void extractLines(GuiGraphicsExtractor graphics, List<String> lines, boolean alignLeft);

   /**
    * Both text columns are handed to {@link F3TextRenderer}, which re-renders its texture only when the text changed.
    * The left column is held back until the right one arrives, so both end up in one picture-in-picture quad.
    */
   @Redirect(
      method = "extractRenderState",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/components/DebugScreenOverlay;extractLines(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Ljava/util/List;Z)V"
      )
   )
   private void vb$extractLines(DebugScreenOverlay self, GuiGraphicsExtractor graphics, List<String> lines, boolean alignLeft) {
      if (!F3TextRenderer.active) {
         this.extractLines(graphics, lines, alignLeft);
         return;
      }

      if (alignLeft) {
         this.vb$left = lines;
         return;
      }

      if (!lines.equals(this.vb$shownRight) || !this.vb$left.equals(this.vb$shownLeft)) {
         this.vb$shownLeft = List.copyOf(this.vb$left);
         this.vb$shownRight = List.copyOf(lines);
         this.vb$version++;
      }

      ((GuiGraphicsExtractorAccessor) graphics).vb$getGuiRenderState().addPicturesInPictureState(
         new F3TextRenderer.State(0, 0, graphics.guiWidth(), graphics.guiHeight(), this.vb$version, this.vb$shownLeft, this.vb$shownRight)
      );
   }

   @Inject(method = "extractRenderState", at = @At("HEAD"))
   private void vb$frame(GuiGraphicsExtractor graphics, CallbackInfo ci) {
      long now = System.nanoTime();
      this.vb$refresh = now - this.vb$lastRefresh >= REFRESH_NS;
      if (this.vb$refresh) {
         this.vb$lastRefresh = now;
      }
   }

   @Redirect(
      method = "extractRenderState",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/components/debug/DebugScreenEntry;display(Lnet/minecraft/client/gui/components/debug/DebugScreenDisplayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/chunk/LevelChunk;)V"
      )
   )
   private void vb$cachedDisplay(DebugScreenEntry entry, DebugScreenDisplayer out, Level level, LevelChunk clientChunk, LevelChunk serverChunk) {
      List<Object[]> ops = this.vb$cache.get(entry);
      if (ops == null || this.vb$refresh) {
         List<Object[]> recorded = new ArrayList<>(4);
         entry.display(new DebugScreenDisplayer() {
            @Override
            public void addPriorityLine(String line) {
               recorded.add(new Object[]{0, line});
            }

            @Override
            public void addLine(String line) {
               recorded.add(new Object[]{1, line});
            }

            @Override
            public void addToGroup(Identifier group, Collection<String> lines) {
               recorded.add(new Object[]{2, group, List.copyOf(lines)});
            }

            @Override
            public void addToGroup(Identifier group, String line) {
               recorded.add(new Object[]{3, group, line});
            }
         }, level, clientChunk, serverChunk);
         this.vb$cache.put(entry, recorded);
         ops = recorded;
      }

      for (Object[] op : ops) {
         switch ((int) op[0]) {
            case 0 -> out.addPriorityLine((String) op[1]);
            case 1 -> out.addLine((String) op[1]);
            case 2 -> {
               @SuppressWarnings("unchecked") Collection<String> lines = (Collection<String>) op[2];
               out.addToGroup((Identifier) op[1], lines);
            }
            default -> out.addToGroup((Identifier) op[1], (String) op[2]);
         }
      }
   }
}
