# VulkanMod+ — licences

VulkanMod+ is an unofficial fork of **VulkanMod** by Collateral (https://github.com/xCollateral/VulkanMod) for Minecraft 26.2,
with fixes and additions. VulkanMod and therefore VulkanMod+ are licensed under the
**GNU Lesser General Public License v3.0** (see LICENSE-VulkanMod.txt). The full source code of VulkanMod+ is available at
https://github.com/DriedApricots/VulkanModPlus

## Code based on other projects

| Project | Licence | What | Source |
|---|---|---|---|
| Nvidium (MCRcortex) | LGPL-3.0 | GPU occlusion culling (`net.vulkanmod.render.chunk.occlusion`): Vulkan re-implementation of Nvidium's temporal culling scheme (draw last frame's visible sections, rasterise section boxes against that depth, draw newly visible sections in the same frame). No Nvidium code is shipped; the design and the per-section visibility approach follow Nvidium. | https://github.com/MCRcortex/nvidium |

## Bundled third-party components

Shipped unmodified inside META-INF/jars (jar-in-jar). Each remains under its own licence; source is available from the linked projects.

| Component | Version | Licence | Source |
|---|---|---|---|
| Lithium (CaffeineMC) | 0.25.2+mc26.2 | LGPL-3.0-only | https://github.com/CaffeineMC/lithium |
| FerriteCore (malte0811) | 9.0.0 | MIT | https://github.com/malte0811/FerriteCore |
| Dynamic FPS (juliand665 / LostLuma) | 3.11.9 | MIT | https://github.com/juliand665/Dynamic-FPS |
| Alternate Current (SpaceWalkerRS) | 1.9.0 | MIT | https://github.com/SpaceWalkerRS/alternate-current |
| Clumps (Jaredlll08) | 26.2.1 | MIT | https://github.com/jaredlll08/Clumps |
| Debugify (isXander) | 26.2.0.0 | LGPL-3.0-or-later | https://github.com/isXander/Debugify |
| Language Reload (Jerozgen) | 1.7.7+26.2 | MIT | https://github.com/Jerozgen/LanguageReload |
| ImmediatelyFast (RaphiMC) | 1.16.2+26.2 | LGPL-3.0-or-later | https://github.com/RaphiMC/ImmediatelyFast |
| Ixeris | 4.5.2+26.2 | LGPL-3.0-only | https://modrinth.com/mod/ixeris |
| MixinExtras (LlamaLad7) | 0.5.5 | MIT | https://github.com/LlamaLad7/MixinExtras |
| LWJGL Vulkan / VMA / shaderc | 3.4.1 | BSD-3-Clause (+ Apache-2.0 for shaderc/VMA natives) | https://github.com/LWJGL/lwjgl3 |
| Fabric API modules (FabricMC) | 0.161.0+26.2 | Apache-2.0 | https://github.com/FabricMC/fabric |

MIT-licensed components: Permission is hereby granted, free of charge, to any person obtaining a copy of this
software and associated documentation files, to deal in the Software without restriction, subject to the
condition that the copyright notice and this permission notice are included in all copies or substantial
portions of the Software. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.
Copyright notices: (c) their respective authors listed above.