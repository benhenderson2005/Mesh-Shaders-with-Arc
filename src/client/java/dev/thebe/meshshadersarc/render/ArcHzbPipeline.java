package dev.thebe.meshshadersarc.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Records the reversed-Z minimum reduction and exact previous-frame transform snapshot. */
final class ArcHzbPipeline implements AutoCloseable {
	private static final String COMPUTE_SHADER = "/assets/mesh-shaders-with-arc/shaders/hzb.comp.glsl";
	private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
	private static final int PUSH_CONSTANT_BYTES = 8;
	private static final int LOCAL_SIZE = 8;

	private final VulkanDevice device;
	private final long descriptorSetLayout;
	private final long pipelineLayout;
	private final long pipeline;
	private boolean closed;

	private ArcHzbPipeline(
		final VulkanDevice device,
		final long descriptorSetLayout,
		final long pipelineLayout,
		final long pipeline
	) {
		this.device = device;
		this.descriptorSetLayout = descriptorSetLayout;
		this.pipelineLayout = pipelineLayout;
		this.pipeline = pipeline;
	}

	static ArcHzbPipeline create(final VulkanDevice device) {
		long descriptorSetLayout = 0L;
		long pipelineLayout = 0L;
		long pipeline = 0L;
		long module = 0L;
		try {
			descriptorSetLayout = createDescriptorSetLayout(device);
			pipelineLayout = createPipelineLayout(device, descriptorSetLayout);
			module = compileShaderModule(device);
			pipeline = createComputePipeline(device, pipelineLayout, module);
			return new ArcHzbPipeline(device, descriptorSetLayout, pipelineLayout, pipeline);
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
			if (module != 0L) {
				VK12.vkDestroyShaderModule(device.vkDevice(), module, null);
			}
		}
	}

	void recordBuild(
		final VkCommandBuffer commandBuffer,
		final GpuTextureView sourceDepth,
		final GpuTextureView[] destinationMips,
		final GpuSampler nearestSampler,
		final GpuBuffer snapshot,
		final GpuBuffer chunkBuffer,
		final int referenceChunkWordOffset,
		final GpuBufferSlice globals,
		final GpuBufferSlice projection,
		final int baseWidth,
		final int baseHeight
	) {
		barrierBeforeBuild(commandBuffer);
		VK12.vkCmdBindPipeline(commandBuffer, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);

		GpuTextureView source = sourceDepth;
		for (int mip = 0; mip < destinationMips.length; mip++) {
			GpuTextureView destination = destinationMips[mip];
			pushDescriptors(
				commandBuffer,
				source,
				nearestSampler,
				destination,
				snapshot,
				chunkBuffer,
				globals,
				projection
			);
			try (MemoryStack stack = MemoryStack.stackPush()) {
				ByteBuffer pushConstants = stack.malloc(PUSH_CONSTANT_BYTES);
				pushConstants.putInt(0, mip == 0 ? 1 : 0);
				pushConstants.putInt(4, referenceChunkWordOffset);
				VK12.vkCmdPushConstants(commandBuffer, this.pipelineLayout, COMPUTE_STAGE, 0, pushConstants);
			}

			int width = Math.max(1, baseWidth >> mip);
			int height = Math.max(1, baseHeight >> mip);
			VK12.vkCmdDispatch(commandBuffer, divideRoundUp(width, LOCAL_SIZE), divideRoundUp(height, LOCAL_SIZE), 1);
			if (mip + 1 < destinationMips.length) {
				barrierComputeWriteToRead(commandBuffer);
				source = destination;
			}
		}

		barrierBuildToNextTask(commandBuffer);
	}

	private void pushDescriptors(
		final VkCommandBuffer commandBuffer,
		final GpuTextureView source,
		final GpuSampler sampler,
		final GpuTextureView destination,
		final GpuBuffer snapshot,
		final GpuBuffer chunkBuffer,
		final GpuBufferSlice globals,
		final GpuBufferSlice projection
	) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(6, stack);
			setSampledImageWrite(stack, writes.get(0), 0, source, sampler);
			setStorageImageWrite(stack, writes.get(1), 1, destination);
			setBufferWrite(stack, writes.get(2), 2, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, snapshot.slice());
			setBufferWrite(stack, writes.get(3), 3, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, chunkBuffer.slice());
			setBufferWrite(stack, writes.get(4), 4, VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, globals);
			setBufferWrite(stack, writes.get(5), 5, VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, projection);
			KHRPushDescriptor.vkCmdPushDescriptorSetKHR(
				commandBuffer,
				VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
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

	private static void setSampledImageWrite(
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

	private static void setStorageImageWrite(
		final MemoryStack stack,
		final VkWriteDescriptorSet write,
		final int binding,
		final GpuTextureView view
	) {
		VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack)
			.imageView(((VulkanGpuTextureView)view).vkImageView())
			.imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
		write.sType$Default()
			.dstBinding(binding)
			.descriptorCount(1)
			.descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
			.pImageInfo(info);
	}

	private static long createDescriptorSetLayout(final VulkanDevice device) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(6, stack);
			setLayoutBinding(bindings.get(0), 0, VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
			setLayoutBinding(bindings.get(1), 1, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
			setLayoutBinding(bindings.get(2), 2, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
			setLayoutBinding(bindings.get(3), 3, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
			setLayoutBinding(bindings.get(4), 4, VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
			setLayoutBinding(bindings.get(5), 5, VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);

			VkDescriptorSetLayoutCreateInfo createInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
				.sType$Default()
				.flags(KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR)
				.pBindings(bindings);
			LongBuffer handle = stack.callocLong(1);
			VulkanUtils.crashIfFailure(
				device,
				VK12.vkCreateDescriptorSetLayout(device.vkDevice(), createInfo, null, handle),
				"Couldn't create Arc HZB descriptor layout"
			);
			return handle.get(0);
		}
	}

	private static void setLayoutBinding(
		final VkDescriptorSetLayoutBinding binding,
		final int index,
		final int descriptorType
	) {
		binding.binding(index).descriptorType(descriptorType).descriptorCount(1).stageFlags(COMPUTE_STAGE);
	}

	private static long createPipelineLayout(final VulkanDevice device, final long descriptorSetLayout) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack)
				.stageFlags(COMPUTE_STAGE)
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
				"Couldn't create Arc HZB pipeline layout"
			);
			return handle.get(0);
		}
	}

	private static long createComputePipeline(final VulkanDevice device, final long pipelineLayout, final long module) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
				.sType$Default()
				.stage(COMPUTE_STAGE)
				.module(module)
				.pName(stack.UTF8("main"));
			VkComputePipelineCreateInfo.Buffer createInfo = VkComputePipelineCreateInfo.calloc(1, stack)
				.sType$Default()
				.stage(stage)
				.layout(pipelineLayout);
			LongBuffer handle = stack.callocLong(1);
			VulkanUtils.crashIfFailure(
				device,
				VK12.vkCreateComputePipelines(device.vkDevice(), VK12.VK_NULL_HANDLE, createInfo, null, handle),
				"Couldn't create Arc HZB compute pipeline"
			);
			return handle.get(0);
		}
	}

	private static long compileShaderModule(final VulkanDevice device) {
		ByteBuffer spirv = ArcShaderCompiler.compileResource(COMPUTE_SHADER, Shaderc.shaderc_glsl_compute_shader);
		try {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
				LongBuffer handle = stack.callocLong(1);
				VulkanUtils.crashIfFailure(
					device,
					VK12.vkCreateShaderModule(device.vkDevice(), createInfo, null, handle),
					"Couldn't create Arc HZB shader module"
				);
				return handle.get(0);
			}
		} finally {
			MemoryUtil.memFree(spirv);
		}
	}

	private static void barrierBeforeBuild(final VkCommandBuffer commandBuffer) {
		long sourceStages = KHRSynchronization2.VK_PIPELINE_STAGE_2_TASK_SHADER_BIT_EXT
			| KHRSynchronization2.VK_PIPELINE_STAGE_2_MESH_SHADER_BIT_EXT
			| KHRSynchronization2.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR
			| KHRSynchronization2.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT_KHR
			| KHRSynchronization2.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT_KHR;
		long sourceAccess = KHRSynchronization2.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_SHADER_STORAGE_READ_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT_KHR;
		memoryBarrier(
			commandBuffer,
			sourceStages,
			sourceAccess,
			KHRSynchronization2.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR,
			KHRSynchronization2.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT_KHR
				| KHRSynchronization2.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT_KHR
		);
	}

	private static void barrierComputeWriteToRead(final VkCommandBuffer commandBuffer) {
		memoryBarrier(
			commandBuffer,
			KHRSynchronization2.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR,
			KHRSynchronization2.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT_KHR,
			KHRSynchronization2.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR,
			KHRSynchronization2.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT_KHR
		);
	}

	private static void barrierBuildToNextTask(final VkCommandBuffer commandBuffer) {
		memoryBarrier(
			commandBuffer,
			KHRSynchronization2.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR,
			KHRSynchronization2.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT_KHR
				| KHRSynchronization2.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT_KHR,
			KHRSynchronization2.VK_PIPELINE_STAGE_2_TASK_SHADER_BIT_EXT
				| KHRSynchronization2.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT_KHR
				| KHRSynchronization2.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT_KHR,
			KHRSynchronization2.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT_KHR
				| KHRSynchronization2.VK_ACCESS_2_SHADER_STORAGE_READ_BIT_KHR
				| KHRSynchronization2.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT_KHR
				| KHRSynchronization2.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT_KHR
		);
	}

	private static void memoryBarrier(
		final VkCommandBuffer commandBuffer,
		final long sourceStage,
		final long sourceAccess,
		final long destinationStage,
		final long destinationAccess
	) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack)
				.sType$Default()
				.srcStageMask(sourceStage)
				.srcAccessMask(sourceAccess)
				.dstStageMask(destinationStage)
				.dstAccessMask(destinationAccess);
			VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
				.sType$Default()
				.pMemoryBarriers(barrier);
			KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
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
}
