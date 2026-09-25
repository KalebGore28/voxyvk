# Voxy Documentation Index

Welcome to the Voxy architecture documentation. This guide helps you find what you need.

## Quick Navigation

### I have 5 minutes
Start here: **ARCHITECTURE_QUICKREF.md**
- 10-second summary
- Component responsibility table
- Thread model overview
- Quick file reference

### I have 30 minutes
Read: **CLAUDE.md** (top to bottom)
- Project overview
- High-level architecture diagrams
- Core components (10 sections)
- Real-world data flow examples

### I'm debugging something specific
Use **CLAUDE.md** sections:
- Thread contention → "Thread Model & Concurrency"
- Geometry issues → "RenderGenerationService" + "GeometryCache"
- GPU problems → "HierarchicalOcclusionTraverser"
- Shader issues → "IrisVoxyRenderPipeline"
- Performance → "Performance Characteristics"

### I'm adding a new feature
Check **ARCHITECTURE_QUICKREF.md** section:
- "Common Tasks" → Pick your task type
- "Key Files Quick Map" → Find the files
- "Integration Points" → Understand dependencies

---

## Documentation Files

| File | Size | Sections | Best For |
|------|------|----------|----------|
| **CLAUDE.md** | 27KB | 10+ | Deep understanding, complete reference |
| **ARCHITECTURE_QUICKREF.md** | 7KB | 12 | Quick lookups, common tasks |
| **DOCUMENTATION_INDEX.md** | This | Navigation | Finding the right doc |

---

## Key Concepts by Category

### Architecture Patterns
- **Hierarchical LOD** - See CLAUDE.md: "Advanced Concepts"
- **GPU-Driven Rendering** - See CLAUDE.md: "HierarchicalOcclusionTraverser"
- **Async Processing** - See CLAUDE.md: "AsyncNodeManager", "Thread Model"
- **Lock-Free Sync** - See CLAUDE.md: "Thread Model & Concurrency"

### Components & Responsibilities

#### Data Model
- **WorldEngine** - CLAUDE.md section 2
- **WorldSection** - CLAUDE.md section 2
- **Coordinate System** - QUICKREF.md: "Coordinate System"

#### Mesh Generation
- **RenderGenerationService** - CLAUDE.md section 3
- **ModelBakerySubsystem** - CLAUDE.md section 7
- **GeometryCache** - CLAUDE.md section 6

#### Rendering
- **HierarchicalOcclusionTraverser** - CLAUDE.md section 5
- **AbstractSectionRenderer** - CLAUDE.md section 9
- **RenderPipeline** - CLAUDE.md section 8

#### Integration
- **Minecraft** - CLAUDE.md: "Integration Points"
- **Sodium** - CLAUDE.md: "Integration Points"
- **Iris** - CLAUDE.md: "Integration Points"

### Practical Topics

#### Performance
- Frame time budgets - CLAUDE.md: "Performance Characteristics"
- Memory requirements - CLAUDE.md: "Performance Characteristics"
- GPU optimization - CLAUDE.md: "Advanced Concepts"

#### Threading
- Thread model overview - QUICKREF.md: "Thread Model"
- Synchronization primitives - CLAUDE.md: "Thread Model & Concurrency"
- Frame synchronization - CLAUDE.md: "Data Flow"

#### Development
- Adding components - QUICKREF.md: "Common Tasks"
- Configuration - CLAUDE.md: "Configuration & Customization"
- Debugging - CLAUDE.md: "Troubleshooting & Debugging"

---

## How to Read This Documentation

### Linear Reading (Comprehensive)
1. Start with "Project Overview" in CLAUDE.md
2. Review "High-Level Architecture" diagram
3. Read "Core Components" sections 1-10 sequentially
4. Study "Thread Model & Concurrency"
5. Understand "Integration Points"
6. Review your area of interest in depth

### Topical Reading (Focused)
1. Find your topic in the index above
2. Jump to the referenced section
3. Read related subsections
4. Cross-reference with QUICKREF.md tables

### Task-Based Reading (Practical)
1. Find your task in QUICKREF.md: "Common Tasks"
2. Identify key classes in "Key Files Quick Map"
3. Read relevant CLAUDE.md sections
4. Review "Integration Points" for dependencies

### Debugging Reading (Problem-Solving)
1. Identify the subsystem affected
2. Jump to relevant CLAUDE.md section
3. Review "Responsibilities" and "Architecture"
4. Check "Troubleshooting" section
5. Look for verification modes or debugging flags

---

## File Locations

All documentation in repo root: `/Users/kalebgore/Developer/repositories/voxyvk/`

Key source files referenced:
- `src/main/java/me/cortex/voxy/client/` - Client systems
- `src/main/java/me/cortex/voxy/common/` - Shared systems
- `src/main/java/me/cortex/voxy/commonImpl/` - Implementation

---

## Searching Effectively

### In CLAUDE.md
- Use "Find" (Ctrl+F) for keywords:
  - "GPU" - GPU-related systems
  - "async" or "thread" - Concurrency
  - "mesh" or "geometry" - Mesh building
  - "shader" - Iris integration
  - "storage" - Persistence

### In ARCHITECTURE_QUICKREF.md
- Tables of contents with quick lookups
- Structured by subsystem
- "Common Tasks" for development
- "Debugging Flags" for troubleshooting

---

## Understanding the Architecture

### Three Ways to Understand Voxy

**1. Top-Down (How it all fits together)**
- Start: CLAUDE.md "High-Level Architecture" diagram
- Then: Component descriptions (sections 1-10)
- Then: Integration points
- Result: Understand the whole system

**2. Bottom-Up (How individual pieces work)**
- Start: Pick a component (e.g., HierarchicalOcclusionTraverser)
- Then: Read its detailed section in CLAUDE.md
- Then: Check what it connects to
- Result: Understand how one part works deeply

**3. Flow-Based (How data moves)**
- Start: "Data Flow: Rendering a Frame" in CLAUDE.md
- Trace through each step
- Look up components as needed
- Result: Understand information flow

---

## Common Questions Answered

**Q: How does Voxy render so fast?**
A: See CLAUDE.md sections 5 (GPU Culling) & 9 (Rendering)
   Also: CLAUDE.md "Advanced Concepts" - GPU-Driven Rendering

**Q: What threads does Voxy create?**
A: See CLAUDE.md "Thread Model & Concurrency"
   Quick: QUICKREF.md "Thread Model" diagram

**Q: How does it work with Sodium?**
A: See CLAUDE.md "Integration Points" - Sodium
   Deep dive: Look for MixinChunkJobQueue in source

**Q: How are shaders supported?**
A: See CLAUDE.md section 8 (IrisVoxyRenderPipeline)
   Also: CLAUDE.md "Integration Points" - Iris

**Q: Why does this need so much memory?**
A: See CLAUDE.md "Performance Characteristics"
   Also: section 6 (GeometryCache), section 10 (Storage)

**Q: How do I add a custom feature?**
A: See QUICKREF.md "Common Tasks"
   Then: "Key Files Quick Map"
   Then: Read relevant CLAUDE.md sections

---

## Updates & Maintenance

This documentation was generated by analyzing the codebase on 2026-09-17.

If you update the code:
- Check if architecture has changed
- Update relevant CLAUDE.md section
- Update QUICKREF.md if subsystems changed
- Update this index if new major components added

---

## Help & Support

**For questions about:**
- Specific class → Check "Key Files by Category" in CLAUDE.md
- Timing/performance → Check performance section in CLAUDE.md
- Integration → Check integration points section
- Configuration → Check "Configuration & Customization" in CLAUDE.md
- Debugging → Check "Troubleshooting & Debugging" in CLAUDE.md

**If something seems wrong:**
- Check both CLAUDE.md and QUICKREF.md
- Look for notes in component descriptions
- Check "Troubleshooting" section
- Refer to source code for latest implementation

---

## Document Statistics

- **CLAUDE.md**: 648 lines, 27 KB - Comprehensive guide
- **ARCHITECTURE_QUICKREF.md**: 266 lines, 7 KB - Quick reference
- **DOCUMENTATION_INDEX.md**: This file - Navigation guide

Total documentation: ~914 lines, ~34 KB

Covered components: 10+ major subsystems, 30+ key classes

Sections: 20+ topic areas across all documents

---

## Next Steps

1. **First time?** Read ARCHITECTURE_QUICKREF.md (5 min)
2. **Want details?** Read CLAUDE.md (30 min)
3. **Have questions?** Use this index to find answers
4. **Adding code?** Check "Common Tasks" in QUICKREF.md

Happy coding!

---

*Documentation auto-generated from Voxy codebase analysis*
*For latest version, see CLAUDE.md and ARCHITECTURE_QUICKREF.md in repo root*
