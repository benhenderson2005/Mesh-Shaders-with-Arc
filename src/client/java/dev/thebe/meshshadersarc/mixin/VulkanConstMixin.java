package dev.thebe.meshshadersarc.mixin;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanConst;
import dev.thebe.meshshadersarc.ArcMeshConfig;
import dev.thebe.meshshadersarc.render.ArcMeshTerrainRenderer;
import dev.thebe.meshshadersarc.render.ArcOcclusionPyramid;
import org.lwjgl.vulkan.VK12;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VulkanConst.class)
abstract class VulkanConstMixin {
	@Inject(method = "textureUsageToVk", at = @At("RETURN"), cancellable = true)
	private static void meshShadersWithArc$addStorageImageUsage(
		final @GpuTexture.Usage int usage,
		final GpuFormat format,
		final CallbackInfoReturnable<Integer> cir
	) {
		if (ArcMeshConfig.vulkanTaskFeatureEnabled() && (usage & ArcOcclusionPyramid.USAGE_STORAGE_IMAGE) != 0) {
			cir.setReturnValue(cir.getReturnValueI() | VK12.VK_IMAGE_USAGE_STORAGE_BIT);
		}
	}

	@Inject(method = "bufferUsageToVk", at = @At("RETURN"), cancellable = true)
	private static void meshShadersWithArc$addStorageUsage(
		final @GpuBuffer.Usage int usage,
		final CallbackInfoReturnable<Integer> cir
	) {
		int readableTerrainUsages = GpuBuffer.USAGE_VERTEX
			| GpuBuffer.USAGE_INDEX
			| GpuBuffer.USAGE_UNIFORM
			| ArcMeshTerrainRenderer.USAGE_STORAGE;
		if (ArcMeshConfig.vulkanMeshFeatureEnabled() && (usage & readableTerrainUsages) != 0) {
			cir.setReturnValue(cir.getReturnValueI() | VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
		}
	}
}
