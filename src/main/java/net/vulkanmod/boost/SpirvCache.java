package net.vulkanmod.boost;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import net.fabricmc.loader.api.FabricLoader;
import net.vulkanmod.Initializer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** Disk cache for compiled SPIR-V (see SPIRVUtils.compileShader). Any I/O problem simply falls back to compiling. */
public final class SpirvCache {
   private static final int SPIRV_MAGIC = 0x07230203;
   private static Path dir;

   private SpirvCache() {
   }

   private static Path dir() {
      if (dir == null) {
         dir = FabricLoader.getInstance().getGameDir().resolve("vulkanmodplus-cache").resolve("spirv");
      }
      return dir;
   }

   public static @Nullable Path path(String filename, String source, String kind) {
      try {
         MessageDigest sha = MessageDigest.getInstance("SHA-256");
         String version = Initializer.getVersion() == null ? "?" : Initializer.getVersion();
         sha.update((version + '\0' + kind + '\0' + filename + '\0').getBytes(StandardCharsets.UTF_8));
         sha.update(source.getBytes(StandardCharsets.UTF_8));
         return dir().resolve(HexFormat.of().formatHex(sha.digest()) + ".spv");
      } catch (Exception e) {
         return null;
      }
   }

   /** @return a native buffer with the SPIR-V words, or null when not cached / invalid */
   public static @Nullable ByteBuffer read(@Nullable Path file) {
      if (file == null || !Initializer.CONFIG.spirvCache) {
         return null;
      }

      try {
         if (!Files.isRegularFile(file)) {
            return null;
         }

         byte[] bytes = Files.readAllBytes(file);
         if (bytes.length < 20 || bytes.length % 4 != 0) {
            return null;
         }

         ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length); // kept alive with the shader module's lifetime like shaderc's result
         buffer.put(bytes).flip();
         if (buffer.getInt(0) != SPIRV_MAGIC) { // native order, as written
            MemoryUtil.memFree(buffer);
            return null;
         }
         return buffer;
      } catch (IOException | RuntimeException e) {
         return null;
      }
   }

   public static void write(@Nullable Path file, ByteBuffer bytecode) {
      if (file == null || !Initializer.CONFIG.spirvCache || bytecode == null) {
         return;
      }

      try {
         Files.createDirectories(file.getParent());
         byte[] bytes = new byte[bytecode.remaining()];
         bytecode.duplicate().get(bytes);
         Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
         Files.write(tmp, bytes);
         Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException | RuntimeException e) {
         Initializer.LOGGER.debug("Could not write SPIR-V cache {}", file, e);
      }
   }
}
