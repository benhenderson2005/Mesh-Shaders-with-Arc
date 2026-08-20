package dev.thebe.meshshadersarc.mixin;

import dev.thebe.meshshadersarc.render.ArcOcclusionInvalidation;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionRenderDispatcher.RenderSection.class)
abstract class RenderSectionOcclusionMixin {
	@Inject(method = "setSectionMesh", at = @At("HEAD"))
	private void meshShadersWithArc$invalidateOcclusionOnMeshReplacement(
		final SectionMesh sectionMesh,
		final CallbackInfoReturnable<SectionMesh> cir
	) {
		ArcOcclusionInvalidation.terrainGeometryChanged();
	}

	@Inject(method = "reset", at = @At("HEAD"))
	private void meshShadersWithArc$invalidateOcclusionOnSectionReset(final CallbackInfo ci) {
		ArcOcclusionInvalidation.terrainGeometryChanged();
	}
}
