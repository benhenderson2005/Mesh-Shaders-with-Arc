package dev.thebe.meshshadersarc.test;

import dev.thebe.meshshadersarc.ArcMeshConfig;
import dev.thebe.meshshadersarc.geometry.PackedGeometryManager;
import dev.thebe.meshshadersarc.render.ArcOcclusionInvalidation;
import dev.thebe.meshshadersarc.render.SodiumArcTerrainRenderer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

public final class ArcTerrainClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(final ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitFor(minecraft -> minecraft.level != null && minecraft.gui.screen() == null, 2400);
			if (!ArcMeshConfig.enabled()) {
				// A disabled-backend run is a Sodium fallback smoke test.
				context.waitTicks(20);
				return;
			}
			long geometryEpochBefore = context.computeOnClient(minecraft -> ArcOcclusionInvalidation.geometryEpoch());
			singleplayer.getServer()
				.runCommand("execute at @p run fill ~-6 ~-1 ~-6 ~6 ~6 ~6 minecraft:blue_stained_glass hollow");
			singleplayer.getServer()
				.runCommand("execute at @p run fill ~-4 ~0 ~-4 ~4 ~5 ~4 minecraft:red_stained_glass hollow");
			context.waitFor(minecraft -> ArcOcclusionInvalidation.geometryEpoch() > geometryEpochBefore, 2400);

			long opaquePassBefore = context.computeOnClient(minecraft -> SodiumArcTerrainRenderer.opaquePassCount());
			long translucentPassBefore = context.computeOnClient(minecraft -> SodiumArcTerrainRenderer.translucentPassCount());
			long hzbBuildBefore = context.computeOnClient(minecraft -> SodiumArcTerrainRenderer.hzbBuildCount());
			context.waitFor(minecraft -> SodiumArcTerrainRenderer.opaquePassCount() > opaquePassBefore, 2400);
			if (ArcMeshConfig.customTranslucencyEnabled()) {
				context.waitFor(
					minecraft -> SodiumArcTerrainRenderer.translucentPassCount() > translucentPassBefore,
					2400
				);
			}
			if (ArcMeshConfig.packedGeometryEnabled()) {
				context.waitFor(minecraft -> SodiumArcTerrainRenderer.lastPackedTaskCount() > 0, 2400);
				if (ArcMeshConfig.customTranslucencyEnabled()) {
					context.waitFor(minecraft -> SodiumArcTerrainRenderer.lastPackedTranslucentTaskCount() > 0, 2400);
				}
			}
			if (ArcMeshConfig.vulkanTaskFeatureEnabled()
				&& ArcMeshConfig.taskCullingEnabled()
				&& ArcMeshConfig.occlusionCullingEnabled()) {
				context.waitFor(minecraft -> SodiumArcTerrainRenderer.hzbBuildCount() > hzbBuildBefore, 2400);
				context.waitFor(
					minecraft -> SodiumArcTerrainRenderer.hasRenderedTaskCulling() && SodiumArcTerrainRenderer.isHzbValid(),
					2400
				);
			}
			context.waitTicks(20);
			context.takeScreenshot("arc-mesh-terrain-smoke");
			int taskCount = context.computeOnClient(minecraft -> SodiumArcTerrainRenderer.lastOpaqueTaskCount());
			if (taskCount <= 0) {
				throw new AssertionError("The Arc mesh renderer reported no terrain workgroups");
			}
			if (ArcMeshConfig.customTranslucencyEnabled()) {
				int translucentTaskCount = context.computeOnClient(
					minecraft -> SodiumArcTerrainRenderer.lastTranslucentTaskCount()
				);
				if (translucentTaskCount <= 0) {
					throw new AssertionError("The sorted translucent mesh renderer reported no workgroups");
				}
			}
			if (ArcMeshConfig.packedGeometryEnabled()
				&& (SodiumArcTerrainRenderer.lastPackedTaskCount() <= 0
					|| (ArcMeshConfig.customTranslucencyEnabled()
						&& SodiumArcTerrainRenderer.lastPackedTranslucentTaskCount() <= 0))) {
				throw new AssertionError("Packed geometry was enabled but packed mesh workgroups were not submitted");
			}
			if (ArcMeshConfig.vulkanSparseResidencyEnabled()
				&& (!PackedGeometryManager.sparseArenaActive() || PackedGeometryManager.sparseResidentBytes() <= 0L)) {
				throw new AssertionError("Sparse residency was enabled but no packed terrain pages became resident");
			}

			if (Boolean.getBoolean("meshShadersWithArc.testKeepDistance")
				&& ArcMeshConfig.keepDistanceChunks() > 32) {
				long opaquePassBeforeTeleport = context.computeOnClient(
					minecraft -> SodiumArcTerrainRenderer.opaquePassCount()
				);
				long retainedTasksBeforeTeleport = context.computeOnClient(
					minecraft -> SodiumArcTerrainRenderer.retainedOpaqueTaskCount()
				);
				// Preserve the player's height while moving north; yaw zero then leaves
				// the original terrain directly in front of the camera (+Z).
				singleplayer.getServer().runCommand("execute at @p run teleport @p ~ ~ ~-192 0 0");
				context.waitFor(
					minecraft -> SodiumArcTerrainRenderer.opaquePassCount() > opaquePassBeforeTeleport
						&& SodiumArcTerrainRenderer.retainedOpaqueTaskCount() > retainedTasksBeforeTeleport,
					2400
				);
				long retainedTaskCount = context.computeOnClient(
					minecraft -> SodiumArcTerrainRenderer.retainedOpaqueTaskCount() - retainedTasksBeforeTeleport
				);
				if (retainedTaskCount <= 0) {
					throw new AssertionError("Keep Distance retained no opaque terrain workgroups after teleporting");
				}
			}
		}
	}
}
