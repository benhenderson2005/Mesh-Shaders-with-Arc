package dev.thebe.meshshadersarc;

import dev.thebe.meshshadersarc.config.ArcSettings;
import dev.thebe.meshshadersarc.config.ArcSettingsStore;

import java.util.concurrent.atomic.AtomicLong;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

public final class ArcMeshConfig {
	private static final String PREFIX = "meshShadersWithArc.";
	private static final String ENABLED_PROPERTY = PREFIX + "enabled";
	private static final String KEEP_DISTANCE_PROPERTY = PREFIX + "keepDistance";
	private static final String MAX_GPU_MEMORY_PROPERTY = PREFIX + "sparsePhysicalMiB";
	private static final String AUTOMATIC_MEMORY_PROPERTY = PREFIX + "automaticMemory";

	private static final boolean ENABLED_SYSTEM_OVERRIDE = System.getProperty(ENABLED_PROPERTY) != null;
	private static final boolean KEEP_DISTANCE_SYSTEM_OVERRIDE = System.getProperty(KEEP_DISTANCE_PROPERTY) != null;
	private static final boolean MAX_GPU_MEMORY_SYSTEM_OVERRIDE = System.getProperty(MAX_GPU_MEMORY_PROPERTY) != null;
	private static final boolean AUTOMATIC_MEMORY_SYSTEM_OVERRIDE = System.getProperty(AUTOMATIC_MEMORY_PROPERTY) != null;
	private static final ArcSettings SETTINGS = ArcSettingsStore.instance().settings();
	private static final AtomicLong RENDERER_SETTINGS_REVISION = new AtomicLong();

	private static volatile boolean disabledForSession;
	private static volatile boolean vulkanMeshFeatureEnabled;
	private static volatile boolean vulkanTaskFeatureEnabled;
	private static volatile boolean vulkanSparseResidencyEnabled;

	private ArcMeshConfig() {
	}

	public static boolean enabled() {
		if (ENABLED_SYSTEM_OVERRIDE) {
			return Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"));
		}
		return !disabledForSession;
	}

	public static boolean disabledForSession() {
		return disabledForSession;
	}

	public static void setDisabledForSession(final boolean disabled) {
		if (disabledForSession != disabled) {
			disabledForSession = disabled;
			RENDERER_SETTINGS_REVISION.incrementAndGet();
		}
	}

	public static boolean enabledSystemOverride() {
		return ENABLED_SYSTEM_OVERRIDE;
	}

	public static int keepDistanceChunks() {
		if (KEEP_DISTANCE_SYSTEM_OVERRIDE) {
			return ArcSettings.normalizeKeepDistance(readIntProperty(KEEP_DISTANCE_PROPERTY, SETTINGS.keepDistanceChunks()));
		}
		return SETTINGS.keepDistanceChunks();
	}

	public static void setKeepDistanceChunks(final int chunks) {
		final int normalized = ArcSettings.normalizeKeepDistance(chunks);
		if (SETTINGS.keepDistanceChunks() != normalized) {
			SETTINGS.setKeepDistanceChunks(normalized);
			RENDERER_SETTINGS_REVISION.incrementAndGet();
		}
	}

	public static boolean keepDistanceSystemOverride() {
		return KEEP_DISTANCE_SYSTEM_OVERRIDE;
	}

	public static int maxGpuMemoryMiB() {
		if (MAX_GPU_MEMORY_SYSTEM_OVERRIDE) {
			return ArcSettings.normalizeMaxGpuMemory(readIntProperty(MAX_GPU_MEMORY_PROPERTY, SETTINGS.maxGpuMemoryMiB()));
		}
		return SETTINGS.maxGpuMemoryMiB();
	}

	public static void setMaxGpuMemoryMiB(final int mebibytes) {
		final int normalized = ArcSettings.normalizeMaxGpuMemory(mebibytes);
		if (SETTINGS.maxGpuMemoryMiB() != normalized) {
			SETTINGS.setMaxGpuMemoryMiB(normalized);
			if (!MAX_GPU_MEMORY_SYSTEM_OVERRIDE) {
				System.setProperty(MAX_GPU_MEMORY_PROPERTY, Integer.toString(normalized));
			}
			RENDERER_SETTINGS_REVISION.incrementAndGet();
		}
	}

	public static boolean maxGpuMemorySystemOverride() {
		return MAX_GPU_MEMORY_SYSTEM_OVERRIDE;
	}

	public static boolean automaticMemoryEnabled() {
		if (AUTOMATIC_MEMORY_SYSTEM_OVERRIDE) {
			return Boolean.parseBoolean(System.getProperty(AUTOMATIC_MEMORY_PROPERTY, "true"));
		}
		return SETTINGS.automaticMemory();
	}

	public static void setAutomaticMemoryEnabled(final boolean enabled) {
		if (SETTINGS.automaticMemory() != enabled) {
			SETTINGS.setAutomaticMemory(enabled);
			RENDERER_SETTINGS_REVISION.incrementAndGet();
		}
	}

	public static boolean automaticMemorySystemOverride() {
		return AUTOMATIC_MEMORY_SYSTEM_OVERRIDE;
	}

	public static int resolveMaxGpuMemoryMiB(final VulkanDevice device) {
		if (!automaticMemoryEnabled() || MAX_GPU_MEMORY_SYSTEM_OVERRIDE) {
			return maxGpuMemoryMiB();
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceMemoryProperties memory = VkPhysicalDeviceMemoryProperties.calloc(stack);
			VK12.vkGetPhysicalDeviceMemoryProperties(device.vkDevice().getPhysicalDevice(), memory);
			long largestDeviceLocalHeap = 0L;
			for (int index = 0; index < memory.memoryHeapCount(); index++) {
				if ((memory.memoryHeaps(index).flags() & VK12.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
					largestDeviceLocalHeap = Math.max(largestDeviceLocalHeap, memory.memoryHeaps(index).size());
				}
			}
			if (largestDeviceLocalHeap > 0L) {
				long automaticMiB = largestDeviceLocalHeap / (4L * 1024L * 1024L);
				return ArcSettings.normalizeMaxGpuMemory((int)Math.min(Integer.MAX_VALUE, automaticMiB));
			}
		} catch (Throwable ignored) {
			// The persisted manual value is a safe fallback when heap discovery is unavailable.
		}
		return maxGpuMemoryMiB();
	}

	public static long rendererSettingsRevision() {
		return RENDERER_SETTINGS_REVISION.get();
	}

	public static boolean vulkanMeshFeatureEnabled() {
		return enabled() && vulkanMeshFeatureEnabled;
	}

	public static boolean packedGeometryEnabled() {
		return enabled() && packedGeometrySetting();
	}

	public static boolean packedGeometrySetting() {
		return Boolean.parseBoolean(System.getProperty(PREFIX + "packedGeometry", Boolean.toString(SETTINGS.packedGeometry())));
	}

	public static boolean packedGeometrySystemOverride() {
		return System.getProperty(PREFIX + "packedGeometry") != null;
	}

	public static void setPackedGeometryEnabled(final boolean enabled) {
		if (SETTINGS.packedGeometry() != enabled) {
			SETTINGS.setPackedGeometry(enabled);
			RENDERER_SETTINGS_REVISION.incrementAndGet();
		}
	}

	public static boolean sparseResidencyEnabled() {
		return packedGeometryEnabled() && sparseResidencySetting();
	}

	public static boolean sparseResidencySetting() {
		return Boolean.parseBoolean(System.getProperty(PREFIX + "sparseResidency", Boolean.toString(SETTINGS.sparseResidency())));
	}

	public static boolean sparseResidencySystemOverride() {
		return System.getProperty(PREFIX + "sparseResidency") != null;
	}

	public static void setSparseResidencyEnabled(final boolean enabled) {
		if (SETTINGS.sparseResidency() != enabled) {
			SETTINGS.setSparseResidency(enabled);
			RENDERER_SETTINGS_REVISION.incrementAndGet();
		}
	}

	public static boolean taskCullingEnabled() {
		return enabled() && temporalHzbCullingSetting()
			&& Boolean.parseBoolean(System.getProperty(PREFIX + "taskCulling", "true"));
	}

	public static boolean temporalHzbCullingSetting() {
		return Boolean.parseBoolean(System.getProperty(PREFIX + "occlusionCulling", Boolean.toString(SETTINGS.temporalHzbCulling())));
	}

	public static boolean temporalHzbCullingSystemOverride() {
		return System.getProperty(PREFIX + "taskCulling") != null || System.getProperty(PREFIX + "occlusionCulling") != null;
	}

	public static boolean occlusionCullingEnabled() {
		return taskCullingEnabled() && temporalHzbCullingSetting();
	}

	public static void setTemporalHzbCullingEnabled(final boolean enabled) {
		if (SETTINGS.temporalHzbCulling() != enabled) {
			SETTINGS.setTemporalHzbCulling(enabled);
			RENDERER_SETTINGS_REVISION.incrementAndGet();
		}
	}

	public static boolean customTranslucencyEnabled() {
		return enabled() && customTranslucencySetting();
	}

	public static boolean customTranslucencySetting() {
		return Boolean.parseBoolean(System.getProperty(PREFIX + "customTranslucency", Boolean.toString(SETTINGS.customTranslucency())));
	}

	public static boolean customTranslucencySystemOverride() {
		return System.getProperty(PREFIX + "customTranslucency") != null;
	}

	public static void setCustomTranslucencyEnabled(final boolean enabled) {
		if (SETTINGS.customTranslucency() != enabled) {
			SETTINGS.setCustomTranslucency(enabled);
			RENDERER_SETTINGS_REVISION.incrementAndGet();
		}
	}

	public static void setVulkanMeshFeatureEnabled(final boolean enabled) {
		vulkanMeshFeatureEnabled = enabled;
	}

	public static boolean vulkanTaskFeatureEnabled() {
		return vulkanMeshFeatureEnabled() && vulkanTaskFeatureEnabled;
	}

	public static void setVulkanTaskFeatureEnabled(final boolean enabled) {
		vulkanTaskFeatureEnabled = enabled;
	}

	public static boolean vulkanSparseResidencyEnabled() {
		return sparseResidencyEnabled() && vulkanMeshFeatureEnabled() && vulkanSparseResidencyEnabled;
	}

	public static void setVulkanSparseResidencyEnabled(final boolean enabled) {
		vulkanSparseResidencyEnabled = enabled;
	}

	private static int readIntProperty(final String name, final int fallback) {
		try {
			return Integer.parseInt(System.getProperty(name, Integer.toString(fallback)));
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}
}
