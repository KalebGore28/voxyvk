package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.common.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaBudget;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMaintenance3Properties;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceSubgroupProperties;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

import static me.cortex.voxy.client.core.vk.VkUtil.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.vmaGetHeapBudgets;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;

//Wraps the Vulkan device Voxy renders on. Voxy never creates its own device:
// when MC 26.2 runs on its native Vulkan backend, Voxy ADOPTS the game's
// VkInstance/VkDevice/queue and its VMA allocator via IVkHost. MC owns (and
// destroys) the device, instance and allocator; destroy() tears down only the
// objects Voxy created on it (command pool, pipeline cache, and the device-lifetime
// sampler / descriptor-set-layout caches).
//
//Capabilities are what the ADOPTED device can legally use: device features come
// from what MC actually enabled (VkDeviceFeatures, fed by MixinVulkanBackend), not
// from physical-device support.
public final class VulkanContext {
    public final VkInstance instance;
    public final VkPhysicalDevice physicalDevice;
    public final VkDevice device;
    public final VkQueue queue;
    public final int queueFamily;
    public final long vma;//MC's VmaAllocator, which Voxy's buffers and images are allocated from
    public final boolean hasDrawIndirectCount;//drawIndirectCount ENABLED on MC's device
    public final boolean subgroupArithmetic;//basic + arithmetic subgroup ops usable in compute shaders
    public final boolean subgroupClustered;//... plus clustered ops
    public final int subgroupSize;
    public final long maxStorageBufferRange;
    public final long maxMemoryAllocationSize;
    public final int depthStencilFormat;//sampleable depth+stencil attachment format for Voxy's offscreen target
    public final String deviceName;
    public final long commandPool;
    public final long pipelineCache;
    private final long storageAlign;
    private final long uniformAlign;

    //Device-lifetime object caches, destroyed in destroy(). Render thread only in
    // practice; synchronized anyway since they are cheap and shared.
    private final Map<Integer, Long> samplers = new HashMap<>();
    private final Map<Object, Long> descriptorSetLayouts = new HashMap<>();

    public static VulkanContext adopt(IVkHost host) { return new VulkanContext(host); }

    private VulkanContext(IVkHost host) {
        this.instance = host.instance();
        this.physicalDevice = host.physicalDevice();
        this.device = host.device();
        this.queue = host.graphicsQueue();
        this.queueFamily = host.graphicsQueueFamily();
        this.vma = host.vma();

        String missing = VkDeviceFeatures.missingRequired();
        if (missing != null) {
            throw new IllegalStateException("Minecraft's Vulkan device lacks features Voxy requires: " + missing);
        }
        this.hasDrawIndirectCount = VkDeviceFeatures.drawIndirectCount();

        String name;
        try (MemoryStack stack = stackPush()) {
            //Subgroup support is a PROPERTY: it must be chained into
            // VkPhysicalDeviceProperties2 (chaining it into the features query, as
            // this did before, leaves it zeroed and silently disables every
            // subgroup path)
            var subgroup = VkPhysicalDeviceSubgroupProperties.calloc(stack).sType$Default();
            var maintenance3 = VkPhysicalDeviceMaintenance3Properties.calloc(stack).sType$Default()
                    .pNext(subgroup.address());
            var props2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default()
                    .pNext(maintenance3.address());
            vkGetPhysicalDeviceProperties2(this.physicalDevice, props2);
            var props = props2.properties();
            name = props.deviceNameString();

            int ops = subgroup.supportedOperations();
            boolean compute = (subgroup.supportedStages() & VK_SHADER_STAGE_COMPUTE_BIT) != 0;
            int basicArithmetic = VK_SUBGROUP_FEATURE_BASIC_BIT | VK_SUBGROUP_FEATURE_ARITHMETIC_BIT;
            this.subgroupSize = subgroup.subgroupSize();
            this.subgroupArithmetic = compute && (ops & basicArithmetic) == basicArithmetic;
            this.subgroupClustered = this.subgroupArithmetic && (ops & VK_SUBGROUP_FEATURE_CLUSTERED_BIT) != 0;

            var limits = props.limits();
            this.maxStorageBufferRange = Integer.toUnsignedLong(limits.maxStorageBufferRange());
            this.maxMemoryAllocationSize = maintenance3.maxMemoryAllocationSize();
            this.storageAlign = limits.minStorageBufferOffsetAlignment();
            this.uniformAlign = limits.minUniformBufferOffsetAlignment();

            this.depthStencilFormat = pickDepthStencilFormat(stack, this.physicalDevice);

            var cpci = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(this.queueFamily);
            var pPool = stack.mallocLong(1);
            check(vkCreateCommandPool(this.device, cpci, null, pPool), "vkCreateCommandPool(adopted)");
            this.commandPool = pPool.get(0);

            var pcci = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
            var pCache = stack.mallocLong(1);
            check(vkCreatePipelineCache(this.device, pcci, null, pCache), "vkCreatePipelineCache");
            this.pipelineCache = pCache.get(0);
        }
        this.deviceName = name + " (MC host)";
        Logger.info("Voxy Vulkan context adopted Minecraft device: " + this.deviceName
                + " (drawIndirectCount=" + this.hasDrawIndirectCount
                + ", subgroupSize=" + this.subgroupSize
                + ", subgroupArithmetic=" + this.subgroupArithmetic
                + ", subgroupClustered=" + this.subgroupClustered
                + ", maxStorageBufferRange=" + this.maxStorageBufferRange
                + ", depthStencilFormat=" + this.depthStencilFormat + ")");
    }

    private static int pickDepthStencilFormat(MemoryStack stack, VkPhysicalDevice physicalDevice) {
        //The spec only guarantees D24S8 OR D32S8 as a depth-stencil attachment (Apple
        // GPUs have no D24S8); Voxy also samples the depth aspect
        int needed = VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
        var formatProps = VkFormatProperties.calloc(stack);
        for (int format : new int[]{VK_FORMAT_D32_SFLOAT_S8_UINT, VK_FORMAT_D24_UNORM_S8_UINT}) {
            vkGetPhysicalDeviceFormatProperties(physicalDevice, format, formatProps);
            if ((formatProps.optimalTilingFeatures() & needed) == needed) {
                return format;
            }
        }
        throw new IllegalStateException("No sampleable depth-stencil attachment format");
    }

    /** The subgroup prefix sum (inital3_vk.comp) needs one subgroup to hold every per-subgroup total of a 256-wide group. */
    public boolean supportsSubgroupPrefixSum() {
        return this.subgroupArithmetic && this.subgroupSize >= 16 && this.subgroupSize <= 256 && 256 % this.subgroupSize == 0;
    }

    /** The subgroup HiZ (hiz_subgroup.comp) reduces clusters of 4 and 16 inside a subgroup. */
    public boolean supportsSubgroupHiZ() {
        return this.subgroupClustered && this.subgroupSize >= 16 && this.subgroupSize <= 256 && 256 % this.subgroupSize == 0;
    }

    /** minStorageBufferOffsetAlignment of the physical device. */
    public long storageBufferOffsetAlignment() {
        return this.storageAlign;
    }

    /** minUniformBufferOffsetAlignment of the physical device. */
    public long uniformBufferOffsetAlignment() {
        return this.uniformAlign;
    }

    /**
     * Estimated device-local memory still available: the budget of the largest
     * device-local heap minus what MC's VMA allocator (MC's and Voxy's allocations) has
     * taken from it. MC does not enable VK_EXT_memory_budget, so VMA budgets 80% of the
     * heap and cannot see other processes: an upper estimate, like the GL path's
     * free-memory query. -1 if the device reports no device-local heap.
     */
    public long deviceLocalBytesAvailable() {
        try (MemoryStack stack = stackPush()) {
            var props = VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(this.physicalDevice, props);
            int heap = -1;
            long heapSize = 0;
            for (int i = 0; i < props.memoryHeapCount(); i++) {
                var memoryHeap = props.memoryHeaps(i);
                if ((memoryHeap.flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0 && memoryHeap.size() > heapSize) {
                    heap = i;
                    heapSize = memoryHeap.size();
                }
            }
            if (heap < 0) return -1;
            var budgets = VmaBudget.malloc(VK_MAX_MEMORY_HEAPS, stack);
            vmaGetHeapBudgets(this.vma, budgets);
            var budget = budgets.get(heap);
            return budget.budget() - budget.usage();
        }
    }

    /** Shared clamp-to-edge sampler (nearest or linear, optional nearest mip), cached for the device lifetime. */
    public synchronized long sampler(boolean mipmapNearest, boolean linear) {
        int key = (mipmapNearest ? 1 : 0) | (linear ? 2 : 0);
        Long cached = this.samplers.get(key);
        if (cached != null) return cached;
        try (MemoryStack stack = stackPush()) {
            var sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST)
                    .minFilter(linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .minLod(0).maxLod(mipmapNearest ? VK_LOD_CLAMP_NONE : 0.25f);
            var pSampler = stack.mallocLong(1);
            check(vkCreateSampler(this.device, sci, null, pSampler), "vkCreateSampler");
            long handle = pSampler.get(0);
            this.samplers.put(key, handle);
            return handle;
        }
    }

    /** Interned descriptor set layout for {@code key} (compared with equals()), created on first use. */
    public synchronized long internDescriptorSetLayout(Object key, LongSupplier create) {
        Long cached = this.descriptorSetLayouts.get(key);
        if (cached != null) return cached;
        long handle = create.getAsLong();
        this.descriptorSetLayouts.put(key, handle);
        return handle;
    }

    public synchronized void destroy() {
        vkDeviceWaitIdle(this.device);
        for (long sampler : this.samplers.values()) {
            vkDestroySampler(this.device, sampler, null);
        }
        this.samplers.clear();
        for (long layout : this.descriptorSetLayouts.values()) {
            vkDestroyDescriptorSetLayout(this.device, layout, null);
        }
        this.descriptorSetLayouts.clear();
        vkDestroyPipelineCache(this.device, this.pipelineCache, null);
        vkDestroyCommandPool(this.device, this.commandPool, null);
        //Host mode: MC owns the device/instance/allocator; only the objects above were ours.
    }
}
