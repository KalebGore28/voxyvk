package me.cortex.voxy.client.core.model.bakery;

import com.mojang.blaze3d.textures.GpuTexture;

import java.util.function.Consumer;

//Backend-neutral readback of MC's stitched block atlas into a CPU int[]
// (RGBA8, one int per texel, byte order R,G,B,A — identical for GL
// RGBA/UNSIGNED_BYTE and VK_FORMAT_R8G8B8A8_UNORM). The software model-texture
// rasterizer samples this array on worker threads.
//
//The result arrives through a callback on the render thread: the GL reader
// completes before readAsync returns, the Blaze3D reader used on Vulkan about a
// frame later, once the GPU has run the copy.
//
//The GL default lazily loads GlAtlasTextureReader on first use (there is always
// a GL context by then). When MC is on Vulkan, VkRenderCore installs
// Blaze3DAtlasTextureReader via setInstance BEFORE the model bakery is constructed,
// so the GL class — and any GL classload — never happens on the VK path. Mirrors the
// AbstractUploadStream/AbstractDownloadStream seam pattern.
public abstract class IAtlasTextureReader {
    /** Reads mip 0 of the given RGBA8 atlas into a fresh {@code int[width*height]} and hands it to {@code onReady}. */
    public abstract void readAsync(GpuTexture atlas, int width, int height, Consumer<int[]> onReady);

    private static IAtlasTextureReader INSTANCE;

    public static IAtlasTextureReader INSTANCE() {
        var i = INSTANCE;
        if (i == null) i = INSTANCE = new GlAtlasTextureReader();
        return i;
    }

    public static void setInstance(IAtlasTextureReader instance) {
        if (INSTANCE != null) throw new IllegalStateException("Atlas texture reader already initialized");
        INSTANCE = instance;
    }

    public static void clearInstance() {
        INSTANCE = null;
    }
}
