package dev.thebe.meshshadersarc.mixin;

import dev.thebe.meshshadersarc.geometry.PackedGeometryManager;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drops geometry from the previous world when Sodium tears its renderer down. */
@Mixin(value = RenderSectionManager.class, remap = false)
abstract class SodiumRenderSectionManagerMixin {
	@Inject(method = "destroy", at = @At("HEAD"), remap = false)
	private void meshShadersWithArc$clearPackedWorld(final CallbackInfo ci) {
		PackedGeometryManager.closeForSodiumRendererReload();
	}
}
