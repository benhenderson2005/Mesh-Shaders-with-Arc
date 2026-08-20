package dev.thebe.meshshadersarc.mixin;

import dev.thebe.meshshadersarc.geometry.PackedGeometryManager;
import java.util.Collection;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures Sodium-approved CPU meshes immediately before Sodium uploads them. */
@Mixin(value = RenderRegionManager.class, remap = false)
abstract class SodiumRenderRegionManagerMixin {
	@Inject(
		method = "uploadResults(Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V",
		at = @At("HEAD"),
		remap = false
	)
	private void meshShadersWithArc$captureAcceptedBuildOutputs(
		final Collection<BuilderTaskOutput> results,
		final UniformBufferManager uniforms,
		final CallbackInfo ci
	) {
		PackedGeometryManager.acceptSodiumUploads(results);
	}
}
