package dev.thebe.meshshadersarc.mixin;

import dev.thebe.meshshadersarc.geometry.PackedGeometryManager;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SectionRenderDispatcher.RenderSection.class)
abstract class RenderSectionPackedGeometryMixin {
	@Shadow @Final private SectionRenderDispatcher this$0;

	@Inject(method = "vertexBufferUploadCallback", at = @At("TAIL"))
	private void meshShadersWithArc$uploadPackedGeometry(
		final CompiledSectionMesh mesh,
		final ChunkSectionLayer layer,
		final CallbackInfo ci
	) {
		PackedGeometryManager.completeUpload(mesh, layer, this.this$0.getRenderSectionSlice(mesh, layer));
	}
}
