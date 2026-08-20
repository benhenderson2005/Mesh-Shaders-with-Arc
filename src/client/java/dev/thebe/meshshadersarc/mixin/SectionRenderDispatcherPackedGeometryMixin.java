package dev.thebe.meshshadersarc.mixin;

import dev.thebe.meshshadersarc.geometry.PackedGeometryManager;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionRenderDispatcher.class)
abstract class SectionRenderDispatcherPackedGeometryMixin {
	@Inject(method = "getRenderSectionSlice", at = @At("RETURN"))
	private void meshShadersWithArc$trackRelocatedGeometry(
		final SectionMesh mesh,
		final ChunkSectionLayer layer,
		final CallbackInfoReturnable<SectionRenderDispatcher.RenderSectionBufferSlice> cir
	) {
		PackedGeometryManager.bindSource(mesh, layer, cir.getReturnValue());
	}
}
