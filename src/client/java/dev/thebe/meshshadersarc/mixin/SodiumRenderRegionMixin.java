package dev.thebe.meshshadersarc.mixin;

import dev.thebe.meshshadersarc.geometry.PackedGeometryManager;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Detaches packed allocations before Sodium discards its RenderSection object. */
@Mixin(value = RenderRegion.class, remap = false)
abstract class SodiumRenderRegionMixin {
	@Inject(method = "removeSection", at = @At("HEAD"), remap = false)
	private void meshShadersWithArc$detachPackedSection(final RenderSection section, final CallbackInfo ci) {
		PackedGeometryManager.detachSodiumSection(section);
	}
}
