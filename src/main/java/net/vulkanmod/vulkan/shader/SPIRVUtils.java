package net.vulkanmod.vulkan.shader;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.NativeResource;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.shaderc.ShadercIncludeResolveI;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultReleaseI;
import org.lwjgl.vulkan.VK12;

public class SPIRVUtils {
   private static final boolean DEBUG = true;
   private static final boolean OPTIMIZATIONS = false;
   private static long compiler;
   private static long options;
   private static final SPIRVUtils.ShaderIncluder SHADER_INCLUDER = new SPIRVUtils.ShaderIncluder();
   private static final SPIRVUtils.ShaderReleaser SHADER_RELEASER = new SPIRVUtils.ShaderReleaser();
   private static final long pUserData = 0L;
   private static ObjectArrayList<String> includePaths;

   private static void initCompiler() {
      compiler = Shaderc.shaderc_compiler_initialize();
      if (compiler == 0L) {
         throw new RuntimeException("Failed to create shader compiler");
      }

      options = Shaderc.shaderc_compile_options_initialize();
      if (options == 0L) {
         throw new RuntimeException("Failed to create compiler options");
      }

      Shaderc.shaderc_compile_options_set_generate_debug_info(options);
      Shaderc.shaderc_compile_options_set_target_env(options, 4202496, VK12.VK_API_VERSION_1_2);
      Shaderc.shaderc_compile_options_set_include_callbacks(options, SHADER_INCLUDER, SHADER_RELEASER, 0L);
      includePaths = new ObjectArrayList();
      addIncludePath("/assets/vulkanmod/shaders/include/");
   }

   public static void addIncludePath(String path) {
      // Directories are not resolvable as resources on every loader (Quilt zfs), so no existence check here;
      // individual files are looked up with getResourceAsStream in ShaderIncluder.
      if (!path.endsWith("/")) {
         path = path + "/";
      }

      if (!includePaths.contains(path)) {
         includePaths.add(path);
      }
   }

   /**
    * VulkanMod Turbo: compiled SPIR-V is cached on disk (.minecraft/vulkanmodturbo-cache/spirv), keyed by a SHA-256 of the
    * full source, shader kind, file name and mod version, so unchanged shaders are not recompiled on every launch or
    * resource reload. Any change of the source (resource packs, other mods, a new VulkanMod Turbo version) is a new key.
    */
   public static SPIRVUtils.SPIRV compileShader(String filename, String source, SPIRVUtils.ShaderKind shaderKind) {
      if (source == null) {
         throw new NullPointerException("Source for %s.%s is null".formatted(filename, shaderKind));
      }

      java.nio.file.Path cacheFile = net.vulkanmod.boost.SpirvCache.path(filename, source, shaderKind.name());
      ByteBuffer cached = net.vulkanmod.boost.SpirvCache.read(cacheFile);
      if (cached != null) {
         return new SPIRVUtils.SPIRV(0L, cached);
      }

      net.vulkanmod.boost.HitchLog.shaderCompiles++;
      SPIRVUtils.SPIRV compiled = compileShaderUncached(filename, source, shaderKind);
      net.vulkanmod.boost.SpirvCache.write(cacheFile, compiled.bytecode());
      return compiled;
   }

   private static SPIRVUtils.SPIRV compileShaderUncached(String filename, String source, SPIRVUtils.ShaderKind shaderKind) {
      if (source == null) {
         throw new NullPointerException("Source for %s.%s is null".formatted(filename, shaderKind));
      } else {
         long result = Shaderc.shaderc_compile_into_spv(compiler, source, shaderKind.kind, filename, "main", options);
         if (result == 0L) {
            throw new RuntimeException("Failed to compile shader %s into SPRI-V".formatted(filename));
         } else if (Shaderc.shaderc_result_get_compilation_status(result) != 0) {
            String errorMessage = Shaderc.shaderc_result_get_error_message(result);
            throw new RuntimeException("Failed to compile shader %s into SPIR-V:\n\t%s".formatted(filename, errorMessage));
         } else {
            return new SPIRVUtils.SPIRV(result, Shaderc.shaderc_result_get_bytes(result));
         }
      }
   }

   static {
      initCompiler();
   }

   public static final class SPIRV implements NativeResource {
      private final long handle;
      private ByteBuffer bytecode;

      public SPIRV(long handle, ByteBuffer bytecode) {
         this.handle = handle;
         this.bytecode = bytecode;
      }

      public ByteBuffer bytecode() {
         return this.bytecode;
      }

      public void free() {
         this.bytecode = null;
      }
   }

   // Port fix: the original resolver resolved includes through Paths.get(URI) (fails on Quilt's quilt.zfs:// resources),
   // threw exceptions out of a native upcall (fatal) and returned a result allocated on an already popped MemoryStack.
   // Now: classpath stream lookup, heap allocation freed by ShaderReleaser, and errors reported to shaderc instead of thrown.
   private static class ShaderIncluder implements ShadercIncludeResolveI {
      public long invoke(long user_data, long requested_source, int type, long requesting_source, long include_depth) {
         String requested = MemoryUtil.memUTF8(requested_source);
         String requesting = MemoryUtil.memUTF8(requesting_source);
         byte[] bytes = null;

         try {
            for (String includePath : SPIRVUtils.includePaths) {
               try (java.io.InputStream in = SPIRVUtils.class.getResourceAsStream(includePath + requested)) {
                  if (in != null) {
                     bytes = in.readAllBytes();
                     break;
                  }
               }
            }
         } catch (Throwable t) {
            bytes = null;
         }

         // shaderc: an empty source_name signals failure, content then holds the error message
         String name = bytes != null ? requested : "";
         byte[] content = bytes != null ? bytes : "%s: Unable to find %s in include paths".formatted(requesting, requested).getBytes(java.nio.charset.StandardCharsets.UTF_8);
         byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
         ByteBuffer nameBuf = MemoryUtil.memAlloc(Math.max(nameBytes.length, 1));   // never a zero-size allocation
         nameBuf.put(nameBytes).flip();
         ByteBuffer contentBuf = MemoryUtil.memAlloc(Math.max(content.length, 1));
         contentBuf.put(content).flip();
         ShadercIncludeResult result = ShadercIncludeResult.calloc();
         result.source_name(nameBuf).content(contentBuf).user_data(user_data);
         return result.address();
      }
   }
   public enum ShaderKind {
      VERTEX_SHADER(0),
      GEOMETRY_SHADER(3),
      FRAGMENT_SHADER(1),
      COMPUTE_SHADER(2);

      private final int kind;

      ShaderKind(int kind) {
         this.kind = kind;
      }
   }

   private static class ShaderReleaser implements ShadercIncludeResultReleaseI {
      public void invoke(long user_data, long include_result) {
         if (include_result != 0L) {
            ShadercIncludeResult result = ShadercIncludeResult.create(include_result);
            MemoryUtil.nmemFree(MemoryUtil.memGetAddress(include_result + ShadercIncludeResult.SOURCE_NAME));
            MemoryUtil.nmemFree(MemoryUtil.memGetAddress(include_result + ShadercIncludeResult.CONTENT));
            result.free();
         }
      }
   }
}
