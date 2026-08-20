package dev.thebe.meshshadersarc.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuSampler;
import dev.thebe.meshshadersarc.render.SodiumArcTerrainRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces Sodium's terrain draw only after the Vulkan mesh backend has accepted the pass. */
@Mixin(value = DefaultChunkRenderer.class, remap = false)
abstract class SodiumDefaultChunkRendererMixin {
	@Inject(
		method = "render(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderListIterable;Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;Lnet/caffeinemc/mods/sodium/client/render/viewport/CameraTransform;Lnet/caffeinemc/mods/sodium/client/util/FogParameters;ZLcom/mojang/blaze3d/textures/GpuSampler;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/buffers/GpuBuffer;)V",
		at = @At("HEAD"),
		cancellable = true,
		remap = false
	)
	private void meshShadersWithArc$renderSodiumTerrain(
		final ChunkRenderMatrices matrices,
		final ChunkRenderListIterable renderLists,
		final TerrainRenderPass pass,
		final CameraTransform camera,
		final FogParameters fog,
		final boolean indexedRenderingEnabled,
		final GpuSampler terrainSampler,
		final GpuBufferSlice uniformData,
		final GpuBuffer sectionTimeInfo,
		final CallbackInfo ci
	) {
		if (SodiumArcTerrainRenderer.render(
			matrices, renderLists, pass, camera, fog, indexedRenderingEnabled,
			terrainSampler, uniformData, sectionTimeInfo
		)) {
			ci.cancel();
		}
	}
}
