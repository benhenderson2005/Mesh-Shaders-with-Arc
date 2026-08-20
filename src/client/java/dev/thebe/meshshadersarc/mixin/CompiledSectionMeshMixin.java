package dev.thebe.meshshadersarc.mixin;

import dev.thebe.meshshadersarc.geometry.PackedGeometryManager;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CompiledSectionMesh.class)
abstract class CompiledSectionMeshMixin {
	@Inject(method = "<init>", at = @At("RETURN"))
	private void meshShadersWithArc$stagePackedGeometry(
		final TranslucencyPointOfView pointOfView,
		final SectionCompiler.Results results,
		final CallbackInfo ci
	) {
		PackedGeometryManager.stage((CompiledSectionMesh)(Object)this, results);
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void meshShadersWithArc$retirePackedGeometry(final CallbackInfo ci) {
		PackedGeometryManager.release((CompiledSectionMesh)(Object)this);
	}
}
