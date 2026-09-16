# Changelog

All notable changes to VoxyVK (fork) will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project uses versioning in the format `MAJOR.MINOR.PATCH+mcVERSION`.

**Note:** This is a community-maintained fork. Original code © 2025 MCRcortex - All Rights Reserved.

## [Unreleased]

### Changed
- Updated documentation to clarify license restrictions (no binary redistribution)
- Removed automated release workflow to comply with license

## [0.2.18+mc26.2] - 2026-09-16

### Fork Improvements
- Rebranded to "VoxyVK" to distinguish from original Voxy
- Updated Sodium compatibility to 0.9.2
- Fixed build issues with Sodium 0.9.2's new terrain buffer system
- Removed deprecated internal Sodium mixin accessors
- Version scheme now includes Minecraft version: `VERSION+mcMC_VERSION`

### From PR-614 (Vulkan Implementation)
- Vulkan/MoltenVK rendering support
- macOS compatibility via MoltenVK
- Hybrid GL/Vulkan renderer with config toggle
- MoltenVK terrain rendering fixes
- First-frame loading glitch fixes on macOS

## Origins & Attribution

This fork is based on:
- **Original Voxy** by [MCRcortex](https://github.com/MCRcortex) - [Repository](https://github.com/MCRcortex/voxy)
- **PR-614** which added Vulkan support - [Pull Request](https://github.com/MCRcortex/voxy/pull/614)

**All original code and concepts © 2025 MCRcortex - All Rights Reserved.**

This fork exists to maintain the Vulkan implementation and keep it compatible with newer Minecraft/Sodium versions. No redistribution of compiled binaries is permitted per the original license.

---

For changes to the original Voxy before PR-614, see the [original repository](https://github.com/MCRcortex/voxy).
