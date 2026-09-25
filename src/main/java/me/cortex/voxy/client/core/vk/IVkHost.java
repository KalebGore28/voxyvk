package me.cortex.voxy.client.core.vk;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;

/**
 * The PURE-VK integration seam. When Minecraft 26.2+ itself runs on its
 * experimental Vulkan backend ("Prefer Vulkan" Graphics API setting), Voxy must
 * not create a second device: it adopts the game's device and records into the
 * game's frame. An adapter implements this against Blaze3D's Vulkan internals.
 *
 * MinecraftVkHostAdapter is the only class outside client/mixin/vk that may use
 * com.mojang.blaze3d.vulkan.*; everything else reaches MC's Vulkan objects through
 * this interface, so a Minecraft update touches the adapter and the mixins only
 * (see BLAZE3D_MIGRATION_AUDIT.md).
 *
 * What Voxy relies on from MC's Vulkan backend beyond its API. Verified against
 * 26.2; re-check on every Minecraft update (BLAZE3D_MIGRATION_AUDIT.md, section 2.3):
 *  D1  MC images go UNDEFINED -> GENERAL at creation and stay GENERAL
 *      (VkFrameHost.MC_IMAGE_LAYOUT)
 *  D2  MC orders its work only by the full barrier each of its operations ends with,
 *      so Voxy's frame must end with one too (VkFrameCtx.endFrame)
 *  D3  at the Sodium OPAQUE hook no render pass is active, so Voxy's frame can be
 *      spliced into MC's submission there (VkFrameCtx.endFrame)
 *  D4  MC submits once per frame, at the end of Minecraft.renderFrame, with at most
 *      two submissions in flight
 *  D5  MC enables push descriptors, dynamic rendering and timeline semaphores, which
 *      Voxy uses without enabling them (checked at device creation, VkDeviceFeatures)
 *  D6  MC only uses its graphics queue from the render thread (VkFrameCtx.flushImmediate
 *      submits to it directly)
 *  D7  Blaze3D pipelines render to D32_SFLOAT depth only
 *  D8  the block atlas is RGBA8 with COPY_SRC usage (Blaze3DAtlasTextureReader)
 *  D9  the lightmap is a sampleable 2D texture view (VkFrameHost.lightmapView)
 *  D10 reverse-Z shows as DepthStencilState.DEFAULT comparing GREATER_THAN_OR_EQUAL
 *      (RenderProperties)
 *  D11 Sodium draws opaque terrain through drawChunkLayer(OPAQUE) into the group's
 *      output target (MixinSodiumOpaqueVkFrame)
 *
 * This is also the macOS path: Voxy's GL backend needs OpenGL 4.6, which macOS
 * does not have, so on Mac Voxy runs only when MC itself runs on Vulkan (MoltenVK).
 */
public interface IVkHost {
    VkInstance instance();
    VkPhysicalDevice physicalDevice();
    VkDevice device();
    VkQueue graphicsQueue();
    int graphicsQueueFamily();

    /** VkImage behind one of MC's textures (MC keeps it in VkFrameHost.MC_IMAGE_LAYOUT). */
    long vkImage(GpuTexture texture);

    /** VkImageView behind one of MC's texture views. */
    long vkImageView(GpuTextureView view);

    /** The VkFormat MC creates images of this Blaze3D format with. */
    int vkFormat(GpuFormat format);

    /**
     * Begins a command buffer from the pool of MC's current submission (MC resets that
     * pool only after the submission has completed). Must be passed to endSegment.
     */
    VkCommandBuffer beginSegment();

    /**
     * Ends {@code segment} and splices it into MC's pending submission: MC ends the
     * command buffer it is recording first, so the segment runs after everything MC
     * recorded before this call and before anything MC records after it. MC must not
     * be inside a render pass.
     */
    void endSegment(VkCommandBuffer segment);

    /**
     * Appends a timeline-semaphore signal to MC's pending queue submission. MC ends
     * its current command buffer first, so the signal fires only after everything
     * recorded before this call has executed on the GPU (and only once MC submits).
     */
    void signalSemaphore(long timelineSemaphore, long value);
}
