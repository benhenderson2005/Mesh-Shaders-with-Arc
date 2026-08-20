package dev.thebe.meshshadersarc.mixin;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import dev.thebe.meshshadersarc.ArcMeshConfig;
import java.util.Collection;
import java.util.Set;
import java.nio.IntBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceMeshShaderFeaturesEXT;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(VulkanBackend.class)
abstract class VulkanBackendMixin {
	@ModifyArgs(
		method = "createDevice",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vulkan/VulkanBackend;createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;"
		)
	)
	private void meshShadersWithArc$enableMeshShaderFeature(final Args args) {
		ArcMeshConfig.setVulkanMeshFeatureEnabled(false);
		ArcMeshConfig.setVulkanTaskFeatureEnabled(false);
		ArcMeshConfig.setVulkanSparseResidencyEnabled(false);
		if (!ArcMeshConfig.enabled()) {
			return;
		}

		VulkanPhysicalDevice physicalDevice = args.get(1);
		if (!physicalDevice.hasDeviceExtension(EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME)) {
			return;
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceMeshShaderFeaturesEXT meshFeatures = VkPhysicalDeviceMeshShaderFeaturesEXT.calloc(stack).sType$Default();
			VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(meshFeatures);
			VK12.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), features2);
			if (!meshFeatures.meshShader()) {
				return;
			}

			Collection<String> enabledExtensions = args.get(0);
			enabledExtensions.add(EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME);

			VulkanPNextStruct meshFeatureStruct = new VulkanPNextStruct(
				EXTMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT,
				VkPhysicalDeviceMeshShaderFeaturesEXT.SIZEOF
			);
			Set<VulkanFeature> enabledFeatures = args.get(2);
			enabledFeatures.add(new VulkanFeature(meshFeatureStruct, "meshShader", VkPhysicalDeviceMeshShaderFeaturesEXT.MESHSHADER));
			// Enable optional capabilities whenever hardware supports them. The Sodium
			// settings can then toggle paths after a renderer reload without recreating VkDevice.
			if (meshFeatures.taskShader()) {
				enabledFeatures.add(new VulkanFeature(meshFeatureStruct, "taskShader", VkPhysicalDeviceMeshShaderFeaturesEXT.TASKSHADER));
				ArcMeshConfig.setVulkanTaskFeatureEnabled(true);
			}

			if (features2.features().sparseBinding()
				&& features2.features().sparseResidencyBuffer()
				&& hasSparseGraphicsQueue(physicalDevice, stack)) {
				enabledFeatures.add(new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT, "sparseBinding", VkPhysicalDeviceFeatures.SPARSEBINDING));
				enabledFeatures.add(new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT, "sparseResidencyBuffer", VkPhysicalDeviceFeatures.SPARSERESIDENCYBUFFER));
				ArcMeshConfig.setVulkanSparseResidencyEnabled(true);
			}
			ArcMeshConfig.setVulkanMeshFeatureEnabled(true);
		}
	}

	private static boolean hasSparseGraphicsQueue(final VulkanPhysicalDevice physicalDevice, final MemoryStack stack) {
		if (physicalDevice.graphicsQueueFamilyAndIndex() == null) {
			return false;
		}
		IntBuffer count = stack.callocInt(1);
		VK12.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice.vkPhysicalDevice(), count, null);
		VkQueueFamilyProperties.Buffer properties = VkQueueFamilyProperties.calloc(count.get(0), stack);
		VK12.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice.vkPhysicalDevice(), count, properties);
		int family = physicalDevice.graphicsQueueFamilyAndIndex().firstInt();
		return family >= 0
			&& family < properties.capacity()
			&& (properties.get(family).queueFlags() & VK12.VK_QUEUE_SPARSE_BINDING_BIT) != 0;
	}
}
