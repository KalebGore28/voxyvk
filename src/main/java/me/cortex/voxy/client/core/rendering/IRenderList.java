package me.cortex.voxy.client.core.rendering;

/**
 * Backend-neutral handle to the hierarchical traversal's render list output.
 * The GL path implements this on {@link me.cortex.voxy.client.core.gl.GlBuffer}.
 * The pure-Vulkan path implements it on its own VkBuffer (VkViewport's indirect
 * lookup buffer), which has no GL name: {@link #glId()} is GL-path only.
 */
public interface IRenderList {
    /** GL buffer name usable with glBindBufferBase from the traversal compute pass (GL backend only). */
    int glId();
    /** Size of the buffer in bytes. */
    long sizeBytes();
}
