# VulkanMod+

An unofficial fork of [VulkanMod](https://github.com/xCollateral/VulkanMod) by **Collateral** for Minecraft **26.2** (Fabric):
the Vulkan renderer with extra optimizations, Sodium Extra style settings and bundled performance mods.
Not affiliated with the VulkanMod project.

A plain port of VulkanMod to 26.2 without these additions: [VulkanMod-Port](https://github.com/DriedApricots/VulkanMod-Port).

## What is added

**Rendering**
- GPU occlusion culling of chunk sections (Nvidium-style temporal culling re-implemented on core Vulkan 1.2; works on any
  GPU, including old ones without mesh shaders)
- Closed chests baked into the chunk mesh, sign text baked per section
- Cached text layout and baked name tags / holograms
- Cached F3 text, particle light cache, faster translucent sorting
- SPIR-V shader cache on disk (faster startup)

**Fixes** (also in the plain port): minimize / fullscreen crashes, real GPU fences (no flicker on slow GPUs), no 20 Hz
camera judder, fast screenshots (F2), lost chunk rebuilds, End sky, Fabric Renderer API crash.

**Settings** (Sodium Extra style): animations, particles (per type, render distance), sky / sun / moon / stars / weather /
fog, entity and block entity switches, FPS and coordinates overlay, toasts. Optional limits for particles, dropped items
and mob render distance (off by default).

**Bundled mods** (unmodified, jar-in-jar): Lithium, FerriteCore, Dynamic FPS, Alternate Current, Clumps, Debugify,
Language Reload, ImmediatelyFast, Ixeris. See `src/main/resources/licenses/THIRD_PARTY.md`.

## Benchmark (integrated AMD GPU, 16 chunks)

| Scene | VulkanMod port | VulkanMod+ |
|---|---|---|
| Empty platform | 116 FPS (1% low 98) | 137 FPS (119) |
| 3072 chests | 76 (57) | 86 (77) |
| 768 signs | 107 (75) | 128 (95) |
| 400 holograms | 126 (78) | 146 (108) |
| Mobs | 122 (96) | 133 (103) |
| Particles | 98 (42) | 104 (56) |
| Wall in front of the camera | 136 (114) | 160 (131) |

## Requirements

- Minecraft 26.2, Fabric Loader 0.17+
- A GPU with Vulkan 1.2 support
- Not compatible with Sodium, Iris or the plain VulkanMod

## Building

```
./gradlew build
```

Requires JDK 25. The bundled mods are taken from `libs-maven/`. The jar is written to `build/libs/`.

## License

[LGPL-3.0](LICENSE), same as VulkanMod. The GPU occlusion culling follows the design of
[Nvidium](https://github.com/MCRcortex/nvidium) by MCRcortex (LGPL-3.0). Bundled mods keep their own licenses.

Made with the help of AI (Claude).
