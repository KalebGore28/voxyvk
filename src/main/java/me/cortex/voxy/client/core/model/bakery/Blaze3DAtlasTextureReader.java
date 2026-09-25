package me.cortex.voxy.client.core.model.bakery;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import me.cortex.voxy.common.Logger;

import java.nio.ByteOrder;
import java.util.function.Consumer;

//Block-atlas readback through Blaze3D's public API, used when MC is on Vulkan.
//
//The copy is recorded into MC's own command stream, so it runs after MC's pending
// work (e.g. the atlas upload of a resource reload), and nothing waits on the GPU:
// the pixels arrive through the copy's completion callback, which MC runs on the
// render thread about a frame later, once the GPU has finished the copy.
//
//MC creates the block atlas with COPY_SRC usage (TextureAtlas.createTexture), which
// copyTextureToBuffer requires.
public final class Blaze3DAtlasTextureReader extends IAtlasTextureReader {
    @Override
    public void readAsync(GpuTexture atlas, int width, int height, Consumer<int[]> onReady) {
        var device = RenderSystem.getDevice();
        var buffer = device.createBuffer(() -> "Voxy block atlas readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) width * height * 4);
        try {
            device.createCommandEncoder().copyTextureToBuffer(atlas, buffer, 0,
                    () -> complete(buffer, width, height, onReady), 0);
        } catch (RuntimeException e) {
            buffer.close();//the completion callback that would close it was never queued
            throw e;
        }
    }

    //Runs inside MC's frame submission, where an exception would take the frame down,
    // so failures are logged instead
    private static void complete(GpuBuffer buffer, int width, int height, Consumer<int[]> onReady) {
        try {
            var pixels = new int[width * height];
            try (var mapped = buffer.map(true, false)) {
                //Native order, as the raw readback read it: one int per texel from its R,G,B,A bytes
                mapped.data().order(ByteOrder.nativeOrder()).asIntBuffer().get(pixels);
            }
            onReady.accept(pixels);
        } catch (Throwable t) {
            Logger.error("Voxy: failed to read back the block atlas, LOD models will not bake", t);
        } finally {
            //Only after the mapping is closed: Blaze3D refuses to close a mapped buffer
            buffer.close();
        }
    }
}
