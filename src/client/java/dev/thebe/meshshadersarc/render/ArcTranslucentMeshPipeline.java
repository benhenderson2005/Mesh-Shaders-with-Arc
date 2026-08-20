package dev.thebe.meshshadersarc.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import dev.thebe.meshshadersarc.MeshShadersWithArcClient;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceMeshShaderPropertiesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfoKHR;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Vulkan mesh pipeline whose blend and depth state matches TRANSLUCENT_TERRAIN. */
final class ArcTranslucentMeshPipeline implements AutoCloseable {
	private static final String MESH_SHADER = "/assets/mesh-shaders-with-arc/shaders/terrain_translucent.mesh.glsl";
	private static final String FRAGMENT_SHADER = "/assets/mesh-shaders-with-arc/shaders/terrain.frag.glsl";
	private static final int MESH_STAGE = EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT;
	private static final int FRAGMENT_STAGE = VK12.VK_SHADER_STAGE_FRAGMENT_BIT;
	private static final int PUSH_CONSTANT_BYTES = 12;

	private final VulkanDevice device;
	private final long descriptorSetLayout;
	private final long pipelineLayout;
	private final long pipeline;
	private final int maxDrawWorkgroups;
	private final long maxStorageBufferRange;
	private boolean closed;

	private ArcTranslucentMeshPipeline(
		final VulkanDevice device,
		final long descriptorSetLayout,
		final long pipelineLayout,
		final long pipeline,
		final int maxDrawWorkgroups,
		final long maxStorageBufferRange
	) {
		this.device = device;
		this.descriptorSetLayout = descriptorSetLayout;
		this.pipelineLayout = pipelineLayout;
		this.pipeline = pipeline;
		this.maxDrawWorkgroups = maxDrawWorkgroups;
		this.maxStorageBufferRange = maxStorageBufferRange;
	}

	static ArcTranslucentMeshPipeline create(final VulkanDevice device, final GpuFormat colorFormat, final GpuFormat depthFormat) {
		MeshLimits limits = queryLimits(device);
		limits.requireSupported();
		long descriptorSetLayout = 0L;
		long pipelineLayout = 0L;
		long pipeline = 0L;
		long meshModule = 0L;
		long fragmentModule = 0L;
		try {
			descriptorSetLayout = createDescriptorSetLayout(device);
			pipelineLayout = createPipelineLayout(device, descriptorSetLayout);
			meshModule = compileShaderModule(device, MESH_SHADER, Shaderc.shaderc_glsl_mesh_shader);
			fragmentModule = compileShaderModule(device, FRAGMENT_SHADER, Shaderc.shaderc_glsl_fragment_shader);
			pipeline = createGraphicsPipeline(device, pipelineLayout, meshModule, fragmentModule, colorFormat, depthFormat);
			MeshShadersWithArcClient.LOGGER.info("Created sorted translucent VK_EXT_mesh_shader terrain pipeline");
			return new ArcTranslucentMeshPipeline(
				device,
				descriptorSetLayout,
				pipelineLayout,
				pipeline,
				limits.maxDrawWorkgroups,
				limits.maxStorageBufferRange
			);
		} catch (Throwable throwable) {
			if (pipeline != 0L) {
				VK12.vkDestroyPipeline(device.vkDevice(), pipeline, null);
			}
			if (pipelineLayout != 0L) {
				VK12.vkDestroyPipelineLayout(device.vkDevice(), pipelineLayout, null);
			}
			if (descriptorSetLayout != 0L) {
				VK12.vkDestroyDescriptorSetLayout(device.vkDevice(), descriptorSetLayout, null);
			}
			throw throwable;
		} finally {
			if (meshModule != 0L) {
				VK12.vkDestroyShaderModule(device.vkDevice(), meshModule, null);
			}
			if (fragmentModule != 0L) {
				VK12.vkDestroyShaderModule(device.vkDevice(), fragmentModule, null);
			}
		}
	}

	void begin(final VkCommandBuffer commandBuffer) {
		VK12.vkCmdBindPipeline(commandBuffer, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipeline);
	}

	void requireStorageRange(final GpuBuffer buffer, final String label) {
		if (buffer.size() > this.maxStorageBufferRange) {
			throw new IllegalStateException(
				label + " buffer range " + buffer.size() + " exceeds maxStorageBufferRange " + this.maxStorageBufferRange
			);
		}
	}

	void draw(
		final VkCommandBuffer commandBuffer,
		final GpuBuffer geometryBuffer,
		final GpuBuffer indexBuffer,
		final GpuBuffer taskBuffer,
		final GpuBuffer chunkBuffer,
		final GpuBufferSlice globals,
		final GpuBufferSlice projection,
		final GpuBufferSlice fog,
		final GpuTextureView atlasView,
		final GpuSampler atlasSampler,
		final GpuTextureView lightmapView,
		final GpuSampler lightmapSampler,
		final int firstTask,
		final int taskCount,
		final int geometryFormat
	) {
		pushDescriptors(
			commandBuffer,
			geometryBuffer,
			indexBuffer,
			taskBuffer,
			chunkBuffer,
			globals,
			projection,
			fog,
			atlasView,
			atlasSampler,
			lightmapView,
			lightmapSampler
		);

		int dispatched = 0;
		while (dispatched < taskCount) {
			int count = Math.min(taskCount - dispatched, this.maxDrawWorkgroups);
			try (MemoryStack stack = MemoryStack.stackPush()) {
				ByteBuffer constants = stack.malloc(PUSH_CONSTANT_BYTES);
				constants.putInt(0, firstTask + dispatched);
				// Sodium's translucent terrain shader keeps texels down to alpha 0.01.
				constants.putFloat(4, 0.01F);
				constants.putInt(8, geometryFormat);
				VK12.vkCmdPushConstants(
					commandBuffer,
					this.pipelineLayout,
					MESH_STAGE | FRAGMENT_STAGE,
					0,
					constants
				);
			}
			EXTMeshShader.vkCmdDrawMeshTasksEXT(commandBuffer, count, 1, 1);
			dispatched += count;
		}
	}

	private void pushDescriptors(
		final VkCommandBuffer commandBuffer,
		final GpuBuffer geometryBuffer,
		final GpuBuffer indexBuffer,
		final GpuBuffer taskBuffer,
		final GpuBuffer chunkBuffer,
		final GpuBufferSlice globals,
		final GpuBufferSlice projection,
		final GpuBufferSlice fog,
		final GpuTextureView atlasView,
		final GpuSampler atlasSampler,
		final GpuTextureView lightmapView,
		final GpuSampler lightmapSampler
	) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(9, stack);
			setBufferWrite(stack, writes.get(0), 0, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, geometryBuffer.slice());
			setBufferWrite(stack, writes.get(1), 1, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, taskBuffer.slice());
			setBufferWrite(stack, writes.get(2), 2, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, chunkBuffer.slice());
			setBufferWrite(stack, writes.get(3), 3, VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, globals);
			setBufferWrite(stack, writes.get(4), 4, VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, projection);
			setBufferWrite(stack, writes.get(5), 5, VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, fog);
			setImageWrite(stack, writes.get(6), 6, atlasView, atlasSampler);
			setImageWrite(stack, writes.get(7), 7, lightmapView, lightmapSampler);
			setBufferWrite(stack, writes.get(8), 8, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, indexBuffer.slice());
			KHRPushDescriptor.vkCmdPushDescriptorSetKHR(
				commandBuffer,
				VK12.VK_PIPELINE_BIND_POINT_GRAPHICS,
				this.pipelineLayout,
				0,
				writes
			);
		}
	}

	private static void setBufferWrite(
		final MemoryStack stack,
		final VkWriteDescriptorSet write,
		final int binding,
		final int descriptorType,
		final GpuBufferSlice slice
	) {
		VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack)
			.buffer(((VulkanGpuBuffer)slice.buffer()).vkBuffer())
			.offset(slice.offset())
			.range(slice.length());
		write.sType$Default()
			.dstBinding(binding)
			.dstArrayElement(0)
			.descriptorCount(1)
			.descriptorType(descriptorType)
			.pBufferInfo(info);
	}

	private static void setImageWrite(
		final MemoryStack stack,
		final VkWriteDescriptorSet write,
		final int binding,
		final GpuTextureView view,
		final GpuSampler sampler
	) {
		VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack)
			.sampler(((VulkanGpuSampler)sampler).vkSampler())
			.imageView(((VulkanGpuTextureView)view).vkImageView())
			.imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
		write.sType$Default()
			.dstBinding(binding)
			.dstArrayElement(0)
			.descriptorCount(1)
			.descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
			.pImageInfo(info);
	}

	private static long createDescriptorSetLayout(final VulkanDevice device) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(9, stack);
			setLayoutBinding(bindings.get(0), 0, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, MESH_STAGE);
			setLayoutBinding(bindings.get(1), 1, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, MESH_STAGE);
			setLayoutBinding(bindings.get(2), 2, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, MESH_STAGE);
			setLayoutBinding(bindings.get(3), 3, VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, MESH_STAGE | FRAGMENT_STAGE);
			setLayoutBinding(bindings.get(4), 4, VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, MESH_STAGE);
			setLayoutBinding(bindings.get(5), 5, VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, FRAGMENT_STAGE);
			setLayoutBinding(bindings.get(6), 6, VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, FRAGMENT_STAGE);
			setLayoutBinding(bindings.get(7), 7, VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, MESH_STAGE);
			setLayoutBinding(bindings.get(8), 8, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, MESH_STAGE);

			VkDescriptorSetLayoutCreateInfo createInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
				.sType$Default()
				.flags(KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR)
				.pBindings(bindings);
			LongBuffer handle = stack.callocLong(1);
			VulkanUtils.crashIfFailure(
				device,
				VK12.vkCreateDescriptorSetLayout(device.vkDevice(), createInfo, null, handle),
				"Couldn't create sorted translucent mesh descriptor layout"
			);
			return handle.get(0);
		}
	}

	private static void setLayoutBinding(
		final VkDescriptorSetLayoutBinding binding,
		final int index,
		final int descriptorType,
		final int stageFlags
	) {
		binding.binding(index).descriptorType(descriptorType).descriptorCount(1).stageFlags(stageFlags);
	}

	private static long createPipelineLayout(final VulkanDevice device, final long descriptorSetLayout) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
				.stageFlags(MESH_STAGE | FRAGMENT_STAGE)
				.offset(0)
				.size(PUSH_CONSTANT_BYTES);
			VkPipelineLayoutCreateInfo createInfo = VkPipelineLayoutCreateInfo.calloc(stack)
				.sType$Default()
				.pSetLayouts(stack.longs(descriptorSetLayout))
				.pPushConstantRanges(pushRange);
			LongBuffer handle = stack.callocLong(1);
			VulkanUtils.crashIfFailure(
				device,
				VK12.vkCreatePipelineLayout(device.vkDevice(), createInfo, null, handle),
				"Couldn't create sorted translucent mesh pipeline layout"
			);
			return handle.get(0);
		}
	}

	private static long createGraphicsPipeline(
		final VulkanDevice device,
		final long pipelineLayout,
		final long meshModule,
		final long fragmentModule,
		final GpuFormat colorFormat,
		final GpuFormat depthFormat
	) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer entryPoint = stack.UTF8("main");
			VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
			stages.get(0).sType$Default().stage(MESH_STAGE).module(meshModule).pName(entryPoint);
			stages.get(1).sType$Default().stage(FRAGMENT_STAGE).module(fragmentModule).pName(entryPoint);

			VkPipelineRasterizationStateCreateInfo rasterization = VkPipelineRasterizationStateCreateInfo.calloc(stack)
				.sType$Default()
				.polygonMode(VK12.VK_POLYGON_MODE_FILL)
				.cullMode(VK12.VK_CULL_MODE_BACK_BIT)
				.frontFace(VK12.VK_FRONT_FACE_CLOCKWISE)
				.lineWidth(1.0F);
			VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
				.sType$Default()
				.depthTestEnable(true)
				.depthWriteEnable(true)
				.depthCompareOp(VK12.VK_COMPARE_OP_GREATER_OR_EQUAL);
			VkPipelineColorBlendAttachmentState.Buffer blendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
				.blendEnable(true)
				.srcColorBlendFactor(VK12.VK_BLEND_FACTOR_SRC_ALPHA)
				.dstColorBlendFactor(VK12.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
				.colorBlendOp(VK12.VK_BLEND_OP_ADD)
				.srcAlphaBlendFactor(VK12.VK_BLEND_FACTOR_ONE)
				.dstAlphaBlendFactor(VK12.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
				.alphaBlendOp(VK12.VK_BLEND_OP_ADD)
				.colorWriteMask(
					VK12.VK_COLOR_COMPONENT_R_BIT
						| VK12.VK_COLOR_COMPONENT_G_BIT
						| VK12.VK_COLOR_COMPONENT_B_BIT
						| VK12.VK_COLOR_COMPONENT_A_BIT
				);
			VkPipelineColorBlendStateCreateInfo blend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
				.sType$Default()
				.pAttachments(blendAttachment);
			VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack)
				.sType$Default()
				.viewportCount(1)
				.scissorCount(1);
			VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
				.sType$Default()
				.rasterizationSamples(VK12.VK_SAMPLE_COUNT_1_BIT);
			IntBuffer dynamicStates = stack.ints(VK12.VK_DYNAMIC_STATE_VIEWPORT, VK12.VK_DYNAMIC_STATE_SCISSOR);
			VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack)
				.sType$Default()
				.pDynamicStates(dynamicStates);
			VkPipelineRenderingCreateInfoKHR rendering = VkPipelineRenderingCreateInfoKHR.calloc(stack)
				.sType$Default()
				.pColorAttachmentFormats(stack.ints(VulkanConst.toVk(colorFormat)))
				.depthAttachmentFormat(VulkanConst.toVk(depthFormat));

			VkGraphicsPipelineCreateInfo.Buffer createInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
				.sType$Default()
				.pStages(stages)
				.pRasterizationState(rasterization)
				.pDepthStencilState(depth)
				.pColorBlendState(blend)
				.pViewportState(viewport)
				.pMultisampleState(multisample)
				.pDynamicState(dynamic)
				.layout(pipelineLayout)
				.pNext(rendering);
			LongBuffer handle = stack.callocLong(1);
			VulkanUtils.crashIfFailure(
				device,
				VK12.vkCreateGraphicsPipelines(device.vkDevice(), VK12.VK_NULL_HANDLE, createInfo, null, handle),
				"Couldn't create sorted translucent mesh pipeline"
			);
			return handle.get(0);
		}
	}

	private static long compileShaderModule(final VulkanDevice device, final String resource, final int shaderKind) {
		ByteBuffer spirv = ArcShaderCompiler.compileResource(resource, shaderKind);
		try {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
				LongBuffer handle = stack.callocLong(1);
				VulkanUtils.crashIfFailure(
					device,
					VK12.vkCreateShaderModule(device.vkDevice(), createInfo, null, handle),
					"Couldn't create shader module for " + resource
				);
				return handle.get(0);
			}
		} finally {
			MemoryUtil.memFree(spirv);
		}
	}

	private static MeshLimits queryLimits(final VulkanDevice device) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceMeshShaderPropertiesEXT mesh = VkPhysicalDeviceMeshShaderPropertiesEXT.calloc(stack).sType$Default();
			VkPhysicalDeviceProperties2 properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(mesh);
			VK12.vkGetPhysicalDeviceProperties2(device.vkDevice().getPhysicalDevice(), properties);
			long xLimit = Integer.toUnsignedLong(mesh.maxMeshWorkGroupCount(0));
			long totalLimit = Integer.toUnsignedLong(mesh.maxMeshWorkGroupTotalCount());
			return new MeshLimits(
				mesh.maxMeshWorkGroupInvocations(),
				mesh.maxMeshWorkGroupSize(0),
				mesh.maxMeshOutputVertices(),
				mesh.maxMeshOutputPrimitives(),
				(int)Math.min(Integer.MAX_VALUE, Math.min(xLimit, totalLimit)),
				Integer.toUnsignedLong(properties.properties().limits().maxStorageBufferRange())
			);
		}
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		VK12.vkDestroyPipeline(this.device.vkDevice(), this.pipeline, null);
		VK12.vkDestroyPipelineLayout(this.device.vkDevice(), this.pipelineLayout, null);
		VK12.vkDestroyDescriptorSetLayout(this.device.vkDevice(), this.descriptorSetLayout, null);
	}

	private record MeshLimits(
		int maxInvocations,
		int maxWorkgroupSizeX,
		int maxOutputVertices,
		int maxOutputPrimitives,
		int maxDrawWorkgroups,
		long maxStorageBufferRange
	) {
		private void requireSupported() {
			if (this.maxInvocations < 32
				|| this.maxWorkgroupSizeX < 32
				|| this.maxOutputVertices < 128
				|| this.maxOutputPrimitives < 64
				|| this.maxDrawWorkgroups < 1) {
				throw new IllegalStateException("Mesh shader limits are too small for sorted translucent terrain");
			}
		}
	}
}
