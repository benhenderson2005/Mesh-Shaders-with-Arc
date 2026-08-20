package dev.thebe.meshshadersarc.mixin;

import com.mojang.blaze3d.textures.GpuSampler;
import dev.thebe.meshshadersarc.render.ArcMeshTerrainRenderer;
import dev.thebe.meshshadersarc.render.ArcTranslucentTerrainRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkSectionsToRender.class)
abstract class ChunkSectionsToRenderMixin {
	@Inject(method = "renderGroup", at = @At("HEAD"), cancellable = true)
	private void meshShadersWithArc$renderTerrainWithMeshShaders(
		final ChunkSectionLayerGroup group,
		final GpuSampler sampler,
		final CallbackInfo ci
	) {
		if (group == ChunkSectionLayerGroup.OPAQUE
			&& ArcMeshTerrainRenderer.renderOpaque((ChunkSectionsToRender)(Object)this, sampler)) {
			ci.cancel();
		} else if (group == ChunkSectionLayerGroup.TRANSLUCENT
			&& ArcTranslucentTerrainRenderer.render((ChunkSectionsToRender)(Object)this, sampler)) {
			ci.cancel();
		}
	}
}
