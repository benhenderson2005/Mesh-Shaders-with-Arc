package dev.thebe.meshshadersarc.mixin;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.thebe.meshshadersarc.render.ArcMeshTerrainRenderer;
import dev.thebe.meshshadersarc.render.ArcTranslucentTerrainRenderer;
import dev.thebe.meshshadersarc.render.SodiumArcTerrainRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanDevice.class)
abstract class VulkanDeviceMixin {
	@Inject(method = "close", at = @At("HEAD"))
	private void meshShadersWithArc$releaseRenderer(final CallbackInfo ci) {
		VulkanDevice device = (VulkanDevice)(Object)this;
		// Queue-idle does not cover commands that Minecraft has recorded but has
		// not submitted yet. Flush its final builder before destroying raw Vulkan
		// pipelines, sparse bindings, or buffers referenced by those commands.
		device.createCommandEncoder().submit();
		device.graphicsQueue().waitIdle();
		SodiumArcTerrainRenderer.onDeviceClosing(device);
		ArcTranslucentTerrainRenderer.onDeviceClosing(device);
		ArcMeshTerrainRenderer.onDeviceClosing(device);
	}
}
