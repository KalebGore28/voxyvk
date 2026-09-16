# VoxyVK

A development fork of [Voxy](https://github.com/MCRcortex/voxy) maintaining Vulkan/MoltenVK support based on [PR-614](https://github.com/MCRcortex/voxy/pull/614).

## License

**Copyright © 2025 MCRcortex - All Rights Reserved**

This project is licensed "All Rights Reserved - Do not redistribute" by the original author.

- You can build from source for personal use
- You cannot redistribute compiled binaries

## Building from Source

### Requirements

- Java 25 (Temurin recommended)
- Git

### Build

```bash
git clone https://github.com/KalebGore28/voxyvk.git
cd voxy-vulkan
./gradlew build
```

The JAR will be in `build/libs/voxyvk-<version>.jar`

### Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.2
2. Install [Sodium 0.9.2+](https://modrinth.com/mod/sodium)
3. Install [Fabric API 0.91.1+](https://modrinth.com/mod/fabric-api)
4. Copy the built JAR to your `.minecraft/mods` folder

## Compatibility

- Minecraft: 26.2
- Fabric Loader: 0.14.22+
- Sodium: 0.9.2+
- Fabric API: 0.91.1+

## Features

- Far distance LOD rendering (from original Voxy)
- Vulkan/MoltenVK support for macOS
- Hybrid OpenGL/Vulkan rendering

## Version Tags

Stable snapshots are tagged for building:

```bash
git tag              # List versions
git checkout v0.2.18+mc26.2
./gradlew build
```

## Credits

- **Original Voxy:** [MCRcortex](https://github.com/MCRcortex) - All rights reserved
- **Vulkan Implementation:** [PR-614](https://github.com/MCRcortex/voxy/pull/614)
- **Fork Maintenance:** [KalebGore28](https://github.com/KalebGore28)

## Issues

If you find a bug or have questions, please [open an issue](https://github.com/KalebGore28/voxyvk/issues).

---

**Disclaimer:** This fork is for educational and development purposes. Not endorsed by the original author.
