package dev.thebe.meshshadersarc.mixin;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VulkanRenderPass.class)
public interface VulkanRenderPassAccessor {
	@Accessor("commandBuffer")
	VkCommandBuffer meshShadersWithArc$getCommandBuffer();

	@Accessor("device")
	VulkanDevice meshShadersWithArc$getDevice();
}
