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
import org.lwjgl.vulkan.VkPhysicalDevicePushDescriptorPropertiesKHR;
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

/** Optional task -> mesh -> fragment opaque terrain pipeline. */
final class ArcTaskMeshPipeline implements AutoCloseable {
	private static final String TASK_SHADER = "/assets/mesh-shaders-with-arc/shaders/terrain.task.glsl";
	private static final String MESH_SHADER = "/assets/mesh-shaders-with-arc/shaders/terrain.mesh.glsl";
	private static final String FRAGMENT_SHADER = "/assets/mesh-shaders-with-arc/shaders/terrain.frag.glsl";
	private static final int TASK_STAGE = EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT;
	private static final int MESH_STAGE = EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT;
	private static final int FRAGMENT_STAGE = VK12.VK_SHADER_STAGE_FRAGMENT_BIT;
	private static final int PUSH_CONSTANT_BYTES = 20;
	private static final int TASKS_PER_WORKGROUP = 32;
	private static final int REQUIRED_PAYLOAD_BYTES = TASKS_PER_WORKGROUP * Integer.BYTES;
	private static final int REQUIRED_SHARED_BYTES = (TASKS_PER_WORKGROUP + 1) * Integer.BYTES;

	private final VulkanDevice device;
	private final long descriptorSetLayout;
	private final long pipelineLayout;
	private final long pipeline;
	private final int maxTaskDrawWorkgroups;
	private boolean closed;

	private ArcTaskMeshPipeline(
		final VulkanDevice device,
		final long descriptorSetLayout,
		final long pipelineLayout,
		final long pipeline,
		final int maxTaskDrawWorkgroups
	) {
		this.device = device;
		this.descriptorSetLayout = descriptorSetLayout;
		this.pipelineLayout = pipelineLayout;
		this.pipeline = pipeline;
		this.maxTaskDrawWorkgroups = maxTaskDrawWorkgroups;
	}

	static ArcTaskMeshPipeline create(final VulkanDevice device, final GpuFormat colorFormat, final GpuFormat depthFormat) {
		TaskLimits limits = queryLimits(device);
		limits.requireSupported();

		long descriptorSetLayout = 0L;
		long pipelineLayout = 0L;
		long pipeline = 0L;
		long taskModule = 0L;
		long meshModule = 0L;
		long fragmentModule = 0L;
		try {
			descriptorSetLayout = createDescriptorSetLayout(device);
			pipelineLayout = createPipelineLayout(device, descriptorSetLayout);
			taskModule = compileShaderModule(device, TASK_SHADER, Shaderc.shaderc_glsl_task_shader);
			meshModule = compileShaderModule(
				device,
				MESH_SHADER,
				Shaderc.shaderc_glsl_mesh_shader,
				"ARC_TASK_CULLING",
				"1"
			);
			fragmentModule = compileShaderModule(device, FRAGMENT_SHADER, Shaderc.shaderc_glsl_fragment_shader);
			pipeline = createGraphicsPipeline(
				device,
				pipelineLayout,
				taskModule,
				meshModule,
				fragmentModule,
				colorFormat,
				depthFormat
			);
			MeshShadersWithArcClient.LOGGER.info(
				"Created task/HZB terrain pipeline (task groups={}, preferred task width={}, payload={} bytes, push descriptors={})",
				limits.maxDirectTaskGroups,
				limits.preferredTaskInvocations,
				limits.maxTaskPayloadBytes,
				limits.maxPushDescriptors
			);
			return new ArcTaskMeshPipeline(device, descriptorSetLayout, pipelineLayout, pipeline, limits.maxDirectTaskGroups);
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
			if (taskModule != 0L) {
				VK12.vkDestroyShaderModule(device.vkDevice(), taskModule, null);
			}
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

	int draw(
		final VkCommandBuffer commandBuffer,
		final GpuBuffer geometryBuffer,
		final GpuBuffer taskBuffer,
		final GpuBuffer chunkBuffer,
		final GpuBufferSlice globals,
		final GpuBufferSlice projection,
		final GpuBufferSlice fog,
		final GpuTextureView atlasView,
		final GpuSampler atlasSampler,
		final GpuTextureView lightmapView,
		final GpuSampler lightmapSampler,
		final ArcOcclusionPyramid.ReadResources occlusion,
		final GpuSampler hzbSampler,
		final int firstTask,
		final int taskCount,
		final float alphaCutout,
		final int geometryFormat
	) {
		pushDescriptors(
			commandBuffer,
			geometryBuffer,
			taskBuffer,
			chunkBuffer,
			globals,
			projection,
			fog,
			atlasView,
			atlasSampler,
			lightmapView,
			lightmapSampler,
			occlusion,
			hzbSampler
		);

		int dispatchedTasks = 0;
		int dispatchCalls = 0;
		int maxCandidates = (int)Math.min(Integer.MAX_VALUE, (long)this.maxTaskDrawWorkgroups * TASKS_PER_WORKGROUP);
		while (dispatchedTasks < taskCount) {
			int candidates = Math.min(taskCount - dispatchedTasks, maxCandidates);
			int taskGroups = divideRoundUp(candidates, TASKS_PER_WORKGROUP);
			try (MemoryStack stack = MemoryStack.stackPush()) {
				ByteBuffer pushConstants = stack.malloc(PUSH_CONSTANT_BYTES);
				pushConstants.putInt(0, firstTask + dispatchedTasks);
				pushConstants.putFloat(4, alphaCutout);
				pushConstants.putInt(8, geometryFormat);
				pushConstants.putInt(12, candidates);
				pushConstants.putInt(16, 1);
				VK12.vkCmdPushConstants(
					commandBuffer,
					this.pipelineLayout,
					TASK_STAGE | MESH_STAGE | FRAGMENT_STAGE,
					0,
					pushConstants
				);
			}

			EXTMeshShader.vkCmdDrawMeshTasksEXT(commandBuffer, taskGroups, 1, 1);
			dispatchedTasks += candidates;
			dispatchCalls++;
		}
		return dispatchCalls;
	}

	private void pushDescriptors(
		final VkCommandBuffer commandBuffer,
		final GpuBuffer geometryBuffer,
		final GpuBuffer taskBuffer,
		final GpuBuffer chunkBuffer,
		final GpuBufferSlice globals,
		final GpuBufferSlice projection,
		final GpuBufferSlice fog,
		final GpuTextureView atlasView,
		final GpuSampler atlasSampler,
		final GpuTextureView lightmapView,
		final GpuSampler lightmapSampler,
		final ArcOcclusionPyramid.ReadResources occlusion,
		final GpuSampler hzbSampler
	) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(10, stack);
			setBufferWrite(stack, writes.get(0), 0, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, geometryBuffer.slice());
			setBufferWrite(stack, writes.get(1), 1, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, taskBuffer.slice());
			setBufferWrite(stack, writes.get(2), 2, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, chunkBuffer.slice());
			setBufferWrite(stack, writes.get(3), 3, VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, globals);
			setBufferWrite(stack, writes.get(4), 4, VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, projection);
			setBufferWrite(stack, writes.get(5), 5, VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, fog);
			setImageWrite(stack, writes.get(6), 6, atlasView, atlasSampler);
			setImageWrite(stack, writes.get(7), 7, lightmapView, lightmapSampler);
			setImageWrite(stack, writes.get(8), 8, occlusion.pyramid(), hzbSampler);
			setBufferWrite(stack, writes.get(9), 9, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, occlusion.snapshot().slice());
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
			.descriptorCount(1)
			.descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
			.pImageInfo(info);
	}

	private static long createDescriptorSetLayout(final VulkanDevice device) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(10, stack);
			setLayoutBinding(bindings.get(0), 0, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, MESH_STAGE);
			setLayoutBinding(bindings.get(1), 1, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, TASK_STAGE | MESH_STAGE);
			setLayoutBinding(bindings.get(2), 2, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, TASK_STAGE | MESH_STAGE);
			setLayoutBinding(bindings.get(3), 3, VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, MESH_STAGE | FRAGMENT_STAGE);
			setLayoutBinding(bindings.get(4), 4, VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, MESH_STAGE);
			setLayoutBinding(bindings.get(5), 5, VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, FRAGMENT_STAGE);
			setLayoutBinding(bindings.get(6), 6, VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, FRAGMENT_STAGE);
			setLayoutBinding(bindings.get(7), 7, VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, MESH_STAGE);
			setLayoutBinding(bindings.get(8), 8, VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, TASK_STAGE);
			setLayoutBinding(bindings.get(9), 9, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, TASK_STAGE);

			VkDescriptorSetLayoutCreateInfo createInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
				.sType$Default()
				.flags(KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR)
				.pBindings(bindings);
			LongBuffer handle = stack.callocLong(1);
			VulkanUtils.crashIfFailure(
				device,
				VK12.vkCreateDescriptorSetLayout(device.vkDevice(), createInfo, null, handle),
				"Couldn't create Arc task terrain descriptor layout"
			);
			return handle.get(0);
		}
	}

	private static void setLayoutBinding(
		final VkDescriptorSetLayoutBinding binding,
		final int index,
		final int descriptorType,
		final int stages
	) {
		binding.binding(index).descriptorType(descriptorType).descriptorCount(1).stageFlags(stages);
	}

	private static long createPipelineLayout(final VulkanDevice device, final long descriptorSetLayout) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack)
				.stageFlags(TASK_STAGE | MESH_STAGE | FRAGMENT_STAGE)
				.offset(0)
				.size(PUSH_CONSTANT_BYTES);
			VkPipelineLayoutCreateInfo createInfo = VkPipelineLayoutCreateInfo.calloc(stack)
				.sType$Default()
				.pSetLayouts(stack.longs(descriptorSetLayout))
				.pPushConstantRanges(range);
			LongBuffer handle = stack.callocLong(1);
			VulkanUtils.crashIfFailure(
				device,
				VK12.vkCreatePipelineLayout(device.vkDevice(), createInfo, null, handle),
				"Couldn't create Arc task terrain pipeline layout"
			);
			return handle.get(0);
		}
	}

	private static long createGraphicsPipeline(
		final VulkanDevice device,
		final long pipelineLayout,
		final long taskModule,
		final long meshModule,
		final long fragmentModule,
		final GpuFormat colorFormat,
		final GpuFormat depthFormat
	) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer entryPoint = stack.UTF8("main");
			VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(3, stack);
			stages.get(0).sType$Default().stage(TASK_STAGE).module(taskModule).pName(entryPoint);
			stages.get(1).sType$Default().stage(MESH_STAGE).module(meshModule).pName(entryPoint);
			stages.get(2).sType$Default().stage(FRAGMENT_STAGE).module(fragmentModule).pName(entryPoint);

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
			VkPipelineColorBlendAttachmentState.Buffer attachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
				.blendEnable(false)
				.colorWriteMask(
					VK12.VK_COLOR_COMPONENT_R_BIT
						| VK12.VK_COLOR_COMPONENT_G_BIT
						| VK12.VK_COLOR_COMPONENT_B_BIT
						| VK12.VK_COLOR_COMPONENT_A_BIT
				);
			VkPipelineColorBlendStateCreateInfo blend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
				.sType$Default()
				.pAttachments(attachment);
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
				"Couldn't create Arc task terrain pipeline"
			);
			return handle.get(0);
		}
	}

	private static long compileShaderModule(
		final VulkanDevice device,
		final String resource,
		final int shaderKind,
		final String... macros
	) {
		ByteBuffer spirv = ArcShaderCompiler.compileResource(resource, shaderKind, macros);
		try {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
				LongBuffer handle = stack.callocLong(1);
				VulkanUtils.crashIfFailure(
					device,
					VK12.vkCreateShaderModule(device.vkDevice(), createInfo, null, handle),
					"Couldn't create task terrain shader module for " + resource
				);
				return handle.get(0);
			}
		} finally {
			MemoryUtil.memFree(spirv);
		}
	}

	private static TaskLimits queryLimits(final VulkanDevice device) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDevicePushDescriptorPropertiesKHR push = VkPhysicalDevicePushDescriptorPropertiesKHR.calloc(stack).sType$Default();
			VkPhysicalDeviceMeshShaderPropertiesEXT mesh = VkPhysicalDeviceMeshShaderPropertiesEXT.calloc(stack).sType$Default();
			VkPhysicalDeviceProperties2 properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(push);
			// Query the two extension-property structures separately. Some Windows
			// Intel drivers populate the first extension in a properties2 chain but
			// leave a second, otherwise-valid chained structure at its zero defaults.
			VK12.vkGetPhysicalDeviceProperties2(device.vkDevice().getPhysicalDevice(), properties);
			properties.pNext(mesh);
			VK12.vkGetPhysicalDeviceProperties2(device.vkDevice().getPhysicalDevice(), properties);
			long xLimit = Integer.toUnsignedLong(mesh.maxTaskWorkGroupCount(0));
			long totalLimit = Integer.toUnsignedLong(mesh.maxTaskWorkGroupTotalCount());
			int maxDirect = (int)Math.min(Integer.MAX_VALUE, Math.min(xLimit, totalLimit));
			return new TaskLimits(
				mesh.maxTaskWorkGroupInvocations(),
				mesh.maxTaskWorkGroupSize(0),
				mesh.maxTaskPayloadSize(),
				mesh.maxTaskSharedMemorySize(),
				mesh.maxTaskPayloadAndSharedMemorySize(),
				mesh.maxPreferredTaskWorkGroupInvocations(),
				maxDirect,
				push.maxPushDescriptors()
			);
		}
	}

	private static int divideRoundUp(final int value, final int divisor) {
		return (value + divisor - 1) / divisor;
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

	private record TaskLimits(
		int maxTaskInvocations,
		int maxTaskSizeX,
		int maxTaskPayloadBytes,
		int maxTaskSharedBytes,
		int maxTaskPayloadAndSharedBytes,
		int preferredTaskInvocations,
		int maxDirectTaskGroups,
		int maxPushDescriptors
	) {
		private void requireSupported() {
			int combined = REQUIRED_PAYLOAD_BYTES + REQUIRED_SHARED_BYTES;
			if (this.maxTaskInvocations < TASKS_PER_WORKGROUP
				|| this.maxTaskSizeX < TASKS_PER_WORKGROUP
				|| this.maxTaskPayloadBytes < REQUIRED_PAYLOAD_BYTES
				|| this.maxTaskSharedBytes < REQUIRED_SHARED_BYTES
				|| this.maxTaskPayloadAndSharedBytes < combined
				|| this.maxDirectTaskGroups < 1
				|| this.maxPushDescriptors < 10) {
				throw new IllegalStateException(
					"Task shader limits are too small: invocations=" + this.maxTaskInvocations
						+ ", sizeX=" + this.maxTaskSizeX
						+ ", payload=" + this.maxTaskPayloadBytes
						+ ", shared=" + this.maxTaskSharedBytes
						+ ", combined=" + this.maxTaskPayloadAndSharedBytes
						+ ", directGroups=" + this.maxDirectTaskGroups
						+ ", pushDescriptors=" + this.maxPushDescriptors
				);
			}
		}
	}
}
