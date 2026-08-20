package dev.thebe.meshshadersarc.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import dev.thebe.meshshadersarc.ArcMeshConfig;
import dev.thebe.meshshadersarc.MeshShadersWithArcClient;
import dev.thebe.meshshadersarc.config.ArcSettings;
import dev.thebe.meshshadersarc.geometry.PackedGeometryManager;
import dev.thebe.meshshadersarc.geometry.PackedGeometryManager.SodiumAllocation;
import dev.thebe.meshshadersarc.geometry.PackedGeometryManager.SodiumTerrainLayer;
import dev.thebe.meshshadersarc.mixin.GpuDeviceAccessor;
import dev.thebe.meshshadersarc.mixin.RenderPassAccessor;
import dev.thebe.meshshadersarc.mixin.VulkanRenderPassAccessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.client.util.iterator.ByteIterator;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Vulkan mesh-shader backend for Sodium 0.9.1's accepted geometry and visible render lists.
 * Sodium continues uploading its normal arenas so any failed preflight can fall back without terrain holes.
 */
public final class SodiumArcTerrainRenderer {
	private static final int QUADS_PER_WORKGROUP = 32;
	private static final int OPAQUE_TASK_BYTES = 16;
	private static final int TRANSLUCENT_TASK_BYTES = 32;
	private static final int CHUNK_WORDS = 24;
	private static final int CHUNK_BYTES = CHUNK_WORDS * Integer.BYTES;
	private static final int SODIUM_GLOBALS_MIN_BYTES = 176;
	private static final int MIN_RING_BYTES = 64 * 1024;
	private static final int FORMAT_SODIUM_PACKED = 3;
	private static final int FORMAT_SODIUM_COMPACT = 2;
	private static final int INDEX_UINT = 1;
	private static final int INDEX_DIRECT_QUADS = 2;

	private static @Nullable VulkanDevice activeDevice;
	private static @Nullable GpuFormat activeColorFormat;
	private static @Nullable GpuFormat activeDepthFormat;
	private static @Nullable ArcMeshPipeline opaquePipeline;
	private static @Nullable ArcTaskMeshPipeline taskPipeline;
	private static @Nullable ArcTranslucentMeshPipeline translucentPipeline;
	private static @Nullable ArcOcclusionPyramid occlusionPyramid;
	private static @Nullable MappableRingBuffer opaqueTaskRing;
	private static @Nullable MappableRingBuffer translucentTaskRing;
	private static @Nullable MappableRingBuffer chunkRing;
	private static int opaqueTaskCapacity;
	private static int translucentTaskCapacity;
	private static int chunkCapacity;
	private static boolean disabledAfterError;
	private static boolean optionalTaskPathDisabled;
	private static boolean announcedOpaque;
	private static boolean announcedTranslucent;
	private static boolean announcedTaskCulling;
	private static long opaquePassCount;
	private static long translucentPassCount;
	private static int lastOpaqueTaskCount;
	private static int lastPackedTaskCount;
	private static long retainedOpaqueTaskCount;
	private static int lastTranslucentTaskCount;
	private static int lastPackedTranslucentTaskCount;

	private SodiumArcTerrainRenderer() {
	}

	public static boolean render(
		final ChunkRenderMatrices matrices,
		final ChunkRenderListIterable renderLists,
		final TerrainRenderPass renderPass,
		final CameraTransform camera,
		final FogParameters fogParameters,
		final boolean indexedRenderingEnabled,
		final GpuSampler terrainSampler,
		final GpuBufferSlice sodiumUniformData,
		final GpuBuffer sectionTimeInfo
	) {
		if (!ArcMeshConfig.enabled() || disabledAfterError || !isCompatibleDevice()) {
			invalidateOcclusion();
			return false;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (SharedConstants.DEBUG_HOTKEYS && minecraft.wireframe) {
			invalidateOcclusion();
			return false;
		}

		if (renderPass.isTranslucent()) {
			return ArcMeshConfig.customTranslucencyEnabled()
				&& renderTranslucent(matrices, renderLists, renderPass, camera, indexedRenderingEnabled, terrainSampler, sodiumUniformData);
		}

		if (renderPass != DefaultTerrainRenderPasses.SOLID && renderPass != DefaultTerrainRenderPasses.CUTOUT) {
			return false;
		}
		return renderOpaque(matrices, renderLists, renderPass, camera, terrainSampler, sodiumUniformData);
	}

	private static boolean renderOpaque(
		final ChunkRenderMatrices matrices,
		final ChunkRenderListIterable renderLists,
		final TerrainRenderPass renderPass,
		final CameraTransform camera,
		final GpuSampler terrainSampler,
		final GpuBufferSlice projection
	) {
		if (renderPass == DefaultTerrainRenderPasses.SOLID) {
			PackedGeometryManager.pruneDetachedSodiumSections(
				SectionPos.blockToSectionCoord(Mth.floor(camera.x)),
				SectionPos.blockToSectionCoord(Mth.floor(camera.z))
			);
		}

		OpaquePlan plan = OpaquePlan.create(matrices, renderLists, renderPass, camera);
		if (plan == null) {
			invalidateOcclusion();
			return false;
		}
		boolean buildHzb = renderPass == DefaultTerrainRenderPasses.CUTOUT;
		if (plan.tasks.isEmpty() && !buildHzb) {
			return true;
		}

		RenderResources resources = RenderResources.capture(renderPass.getTarget(), projection);
		if (resources == null) {
			invalidateOcclusion();
			return false;
		}

		boolean customPassAttempted = false;
		try {
			ensureOpaquePipeline(resources.device, resources.target);
			ensureOptionalTaskPath(resources.device, resources.target);
			GpuBuffer chunkBuffer = writeChunks(plan.chunks, matrices.modelView());
			GpuBuffer taskBuffer = plan.tasks.isEmpty() ? null : writeOpaqueTasks(plan.tasks);
			opaquePipeline.requireStorageRange(chunkBuffer, "Sodium chunk metadata");
			if (taskBuffer != null) {
				opaquePipeline.requireStorageRange(taskBuffer, "Sodium mesh tasks");
				for (OpaqueBatch batch : plan.batches) {
					opaquePipeline.requireStorageRange(batch.geometryBuffer, "Sodium terrain geometry");
				}
			}

			ArcOcclusionPyramid.ReadResources occlusionRead = null;
			if (!optionalTaskPathDisabled
				&& ArcMeshConfig.taskCullingEnabled()
				&& ArcMeshConfig.occlusionCullingEnabled()
				&& occlusionPyramid != null) {
				occlusionPyramid.ensureSizeAndWorld(resources.target.width, resources.target.height, Minecraft.getInstance().level);
				occlusionRead = occlusionPyramid.readable(plan.geometryEpoch, Minecraft.getInstance().level);
			}
			boolean useTaskCulling = taskBuffer != null
				&& taskPipeline != null
				&& occlusionRead != null
				&& !optionalTaskPathDisabled
				&& ArcMeshConfig.taskCullingEnabled()
				&& ArcMeshConfig.occlusionCullingEnabled();
			if (useTaskCulling) {
				announcedTaskCulling = true;
			}
			GpuSampler hzbSampler = useTaskCulling
				? RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, true)
				: null;

			VkCommandBuffer commandBuffer;
			try (RenderPass pass = createTerrainPass("Arc Sodium opaque terrain", resources.target)) {
				commandBuffer = commandBuffer(pass);
				// From this point onward raw commands may change the pass, so a
				// caught failure must cancel Sodium to avoid double rendering.
				customPassAttempted = true;
				if (taskBuffer != null) {
					if (useTaskCulling) {
						taskPipeline.begin(commandBuffer);
					} else {
						opaquePipeline.begin(commandBuffer);
					}

					// Match Sodium 0.9.1's terrain shader defines exactly: CUTOUT
					// rejects alpha below 0.5, while SOLID does not discard.
					float alphaCutout = renderPass.supportsFragmentDiscard() ? 0.5F : 0.0F;
					for (OpaqueBatch batch : plan.batches) {
						if (useTaskCulling) {
							taskPipeline.draw(
								commandBuffer, batch.geometryBuffer, taskBuffer, chunkBuffer,
								resources.globals.slice(), resources.projection, resources.fog,
								renderPass.getAtlas(), terrainSampler, resources.lightmap, resources.lightmapSampler,
								occlusionRead, hzbSampler, batch.firstTask, batch.taskCount,
								alphaCutout, batch.geometryFormat
							);
						} else {
							opaquePipeline.draw(
								commandBuffer, batch.geometryBuffer, taskBuffer, chunkBuffer,
								resources.globals.slice(), resources.projection, resources.fog,
								renderPass.getAtlas(), terrainSampler, resources.lightmap, resources.lightmapSampler,
								batch.firstTask, batch.taskCount, alphaCutout, batch.geometryFormat
							);
						}
					}
				}
			}

			if (buildHzb
				&& ArcMeshConfig.taskCullingEnabled()
				&& ArcMeshConfig.occlusionCullingEnabled()
				&& !optionalTaskPathDisabled
				&& occlusionPyramid != null) {
				GpuTextureView depth = resources.target.getDepthTextureView();
				if (depth != null) {
					try {
						occlusionPyramid.recordBuild(
							commandBuffer, depth, chunkBuffer, 0,
							resources.globals.slice(), resources.projection,
							plan.geometryEpoch, plan.stableGeometry, Minecraft.getInstance().level
						);
					} catch (Throwable throwable) {
						disableOptionalTaskPathAfterRecording(throwable);
					}
				}
			}

			if (taskBuffer != null) {
				opaqueTaskRing.rotate();
			}
			chunkRing.rotate();
			opaquePassCount++;
			lastOpaqueTaskCount = plan.tasks.size();
			lastPackedTaskCount = plan.packedTaskCount();
			retainedOpaqueTaskCount += plan.retainedTaskCount;
			if (!announcedOpaque && !plan.tasks.isEmpty()) {
				announcedOpaque = true;
				MeshShadersWithArcClient.LOGGER.info(
					"Sodium terrain is now using the Arc Vulkan mesh-shader backend ({} workgroups, {} packed)",
					plan.tasks.size(), plan.packedTaskCount()
				);
			}
			return true;
		} catch (Throwable throwable) {
			disabledAfterError = true;
			MeshShadersWithArcClient.LOGGER.error(
				"Disabling the Sodium mesh-shader bridge after a failure; Sodium's normal terrain renderer remains available",
				throwable
			);
			return customPassAttempted;
		}
	}

	private static boolean renderTranslucent(
		final ChunkRenderMatrices matrices,
		final ChunkRenderListIterable renderLists,
		final TerrainRenderPass renderPass,
		final CameraTransform camera,
		final boolean indexedRenderingEnabled,
		final GpuSampler terrainSampler,
		final GpuBufferSlice projection
	) {
		TranslucentPlan plan = TranslucentPlan.create(renderLists, renderPass, camera, indexedRenderingEnabled);
		if (plan == null) {
			return false;
		}
		if (plan.tasks.isEmpty()) {
			return true;
		}

		RenderResources resources = RenderResources.capture(renderPass.getTarget(), projection);
		if (resources == null) {
			return false;
		}

		boolean customPassAttempted = false;
		try {
			ensureTranslucentPipeline(resources.device, resources.target);
			GpuBuffer chunkBuffer = writeChunks(plan.chunks, matrices.modelView());
			GpuBuffer taskBuffer = writeTranslucentTasks(plan.tasks);
			translucentPipeline.requireStorageRange(chunkBuffer, "Sodium translucent chunk metadata");
			translucentPipeline.requireStorageRange(taskBuffer, "Sodium translucent tasks");
			for (TranslucentBatch batch : plan.batches) {
				translucentPipeline.requireStorageRange(batch.geometryBuffer, "Sodium translucent geometry");
				translucentPipeline.requireStorageRange(batch.indexBuffer, "Sodium translucent indices");
			}

			try (RenderPass pass = createTerrainPass("Arc Sodium translucent terrain", resources.target)) {
				VkCommandBuffer commandBuffer = commandBuffer(pass);
				// Pass construction/accessor failures happen before custom work and
				// can still fall back to Sodium for this same pass.
				customPassAttempted = true;
				translucentPipeline.begin(commandBuffer);
				for (TranslucentBatch batch : plan.batches) {
					translucentPipeline.draw(
						commandBuffer, batch.geometryBuffer, batch.indexBuffer,
						taskBuffer, chunkBuffer, resources.globals.slice(), resources.projection, resources.fog,
						renderPass.getAtlas(), terrainSampler, resources.lightmap, resources.lightmapSampler,
						batch.firstTask, batch.taskCount, batch.geometryFormat
					);
				}
			}

			translucentTaskRing.rotate();
			chunkRing.rotate();
			translucentPassCount++;
			lastTranslucentTaskCount = plan.tasks.size();
			lastPackedTranslucentTaskCount = plan.packedTaskCount();
			if (!announcedTranslucent) {
				announcedTranslucent = true;
				MeshShadersWithArcClient.LOGGER.info(
					"Sodium sorted translucency is now using the Arc Vulkan mesh-shader backend ({} workgroups)",
					plan.tasks.size()
				);
			}
			return true;
		} catch (Throwable throwable) {
			disabledAfterError = true;
			MeshShadersWithArcClient.LOGGER.error(
				"Disabling the Sodium mesh-shader bridge after a translucent rendering failure; Sodium remains the fallback",
				throwable
			);
			return customPassAttempted;
		}
	}

	private static RenderPass createTerrainPass(final String label, final RenderTarget target) {
		return RenderSystem.getDevice().createCommandEncoder().createRenderPass(
			() -> label,
			target.getColorTextureView(), Optional.empty(),
			target.getDepthTextureView(), OptionalDouble.empty()
		);
	}

	private static VkCommandBuffer commandBuffer(final RenderPass pass) {
		RenderPassBackend backend = ((RenderPassAccessor)(Object)pass).meshShadersWithArc$getBackend();
		if (!(backend instanceof VulkanRenderPass)) {
			throw new IllegalStateException("Sodium terrain pass is not backed by Vulkan");
		}
		return ((VulkanRenderPassAccessor)(Object)backend).meshShadersWithArc$getCommandBuffer();
	}

	private static void ensureOpaquePipeline(final VulkanDevice device, final RenderTarget target) {
		ensureDeviceAndFormats(device, target);
		if (opaquePipeline == null) {
			opaquePipeline = ArcMeshPipeline.create(device, activeColorFormat, activeDepthFormat);
		}
	}

	private static void ensureTranslucentPipeline(final VulkanDevice device, final RenderTarget target) {
		ensureDeviceAndFormats(device, target);
		if (translucentPipeline == null) {
			translucentPipeline = ArcTranslucentMeshPipeline.create(device, activeColorFormat, activeDepthFormat);
		}
	}

	private static void ensureDeviceAndFormats(final VulkanDevice device, final RenderTarget target) {
		GpuFormat color = target.getColorTexture().getFormat();
		GpuFormat depth = target.getDepthTexture().getFormat();
		if (activeDevice != device || activeColorFormat != color || activeDepthFormat != depth) {
			if (activeDevice != null) {
				activeDevice.graphicsQueue().waitIdle();
			}
			closePipelines();
			activeDevice = device;
			activeColorFormat = color;
			activeDepthFormat = depth;
		}
	}

	private static void ensureOptionalTaskPath(final VulkanDevice device, final RenderTarget target) {
		if (optionalTaskPathDisabled
			|| taskPipeline != null
			|| !ArcMeshConfig.taskCullingEnabled()
			|| !ArcMeshConfig.occlusionCullingEnabled()
			|| !ArcMeshConfig.vulkanTaskFeatureEnabled()) {
			return;
		}
		try {
			taskPipeline = ArcTaskMeshPipeline.create(device, target.getColorTexture().getFormat(), target.getDepthTexture().getFormat());
			occlusionPyramid = ArcOcclusionPyramid.create(device);
		} catch (Throwable throwable) {
			optionalTaskPathDisabled = true;
			MeshShadersWithArcClient.LOGGER.warn("Sodium task/HZB culling is unavailable; using direct mesh draws", throwable);
			closeOptionalTaskPath();
		}
	}

	private static void disableOptionalTaskPathAfterRecording(final Throwable throwable) {
		optionalTaskPathDisabled = true;
		MeshShadersWithArcClient.LOGGER.warn(
			"Disabling Sodium task/HZB culling after a command-recording failure; direct mesh rendering remains active",
			throwable
		);
		invalidateOcclusion();
	}

	private static void invalidateOcclusion() {
		if (occlusionPyramid != null) {
			occlusionPyramid.invalidate();
		}
	}

	private static void closeOptionalTaskPath() {
		if (occlusionPyramid != null) {
			occlusionPyramid.close();
			occlusionPyramid = null;
		}
		if (taskPipeline != null) {
			taskPipeline.close();
			taskPipeline = null;
		}
	}

	private static void closePipelines() {
		if (opaquePipeline != null) {
			opaquePipeline.close();
			opaquePipeline = null;
		}
		if (translucentPipeline != null) {
			translucentPipeline.close();
			translucentPipeline = null;
		}
		closeOptionalTaskPath();
	}

	private static GpuBuffer writeOpaqueTasks(final List<OpaqueTask> tasks) {
		int bytes = Math.multiplyExact(tasks.size(), OPAQUE_TASK_BYTES);
		ensureOpaqueTaskCapacity(bytes);
		GpuBuffer buffer = opaqueTaskRing.currentBuffer();
		try (GpuBufferSlice.MappedView mapped = buffer.slice(0L, bytes).map(false, true)) {
			ByteBuffer data = mapped.data().order(ByteOrder.nativeOrder());
			for (OpaqueTask task : tasks) {
				data.putInt(task.baseVertex).putInt(task.quadCount).putInt(task.chunkWordOffset).putInt(0);
			}
		}
		return buffer;
	}

	private static GpuBuffer writeTranslucentTasks(final List<TranslucentTask> tasks) {
		int bytes = Math.multiplyExact(tasks.size(), TRANSLUCENT_TASK_BYTES);
		ensureTranslucentTaskCapacity(bytes);
		GpuBuffer buffer = translucentTaskRing.currentBuffer();
		try (GpuBufferSlice.MappedView mapped = buffer.slice(0L, bytes).map(false, true)) {
			ByteBuffer data = mapped.data().order(ByteOrder.nativeOrder());
			for (TranslucentTask task : tasks) {
				data.putInt(task.baseVertex).putInt(task.firstIndex).putInt(task.quadCount).putInt(task.chunkWordOffset);
				data.putInt(task.indexType).putInt(0).putInt(0).putInt(0);
			}
		}
		return buffer;
	}

	private static GpuBuffer writeChunks(final List<ChunkRecord> records, final Matrix4fc modelView) {
		int count = Math.max(records.size(), 1);
		int bytes = Math.multiplyExact(count, CHUNK_BYTES);
		ensureChunkCapacity(bytes);
		GpuBuffer buffer = chunkRing.currentBuffer();
		try (GpuBufferSlice.MappedView mapped = buffer.slice(0L, bytes).map(false, true)) {
			ByteBuffer data = mapped.data().order(ByteOrder.nativeOrder());
			if (records.isEmpty()) {
				putChunk(data, modelView, new ChunkRecord(0, 0, 0));
			} else {
				for (ChunkRecord record : records) {
					putChunk(data, modelView, record);
				}
			}
		}
		return buffer;
	}

	private static void putChunk(final ByteBuffer data, final Matrix4fc matrix, final ChunkRecord chunk) {
		data.putFloat(matrix.m00()).putFloat(matrix.m01()).putFloat(matrix.m02()).putFloat(matrix.m03());
		data.putFloat(matrix.m10()).putFloat(matrix.m11()).putFloat(matrix.m12()).putFloat(matrix.m13());
		data.putFloat(matrix.m20()).putFloat(matrix.m21()).putFloat(matrix.m22()).putFloat(matrix.m23());
		data.putFloat(matrix.m30()).putFloat(matrix.m31()).putFloat(matrix.m32()).putFloat(matrix.m33());
		data.putFloat(1.0F).putInt(0).putInt(0).putInt(0);
		data.putInt(chunk.blockX).putInt(chunk.blockY).putInt(chunk.blockZ).putInt(0);
	}

	private static void ensureOpaqueTaskCapacity(final int required) {
		if (opaqueTaskRing != null && opaqueTaskCapacity >= required) {
			return;
		}
		opaqueTaskCapacity = growCapacity(required);
		if (opaqueTaskRing != null) opaqueTaskRing.close();
		opaqueTaskRing = new MappableRingBuffer(() -> "Arc Sodium opaque tasks", GpuBuffer.USAGE_MAP_WRITE | ArcMeshTerrainRenderer.USAGE_STORAGE, opaqueTaskCapacity);
	}

	private static void ensureTranslucentTaskCapacity(final int required) {
		if (translucentTaskRing != null && translucentTaskCapacity >= required) {
			return;
		}
		translucentTaskCapacity = growCapacity(required);
		if (translucentTaskRing != null) translucentTaskRing.close();
		translucentTaskRing = new MappableRingBuffer(() -> "Arc Sodium translucent tasks", GpuBuffer.USAGE_MAP_WRITE | ArcMeshTerrainRenderer.USAGE_STORAGE, translucentTaskCapacity);
	}

	private static void ensureChunkCapacity(final int required) {
		if (chunkRing != null && chunkCapacity >= required) {
			return;
		}
		chunkCapacity = growCapacity(required);
		if (chunkRing != null) chunkRing.close();
		chunkRing = new MappableRingBuffer(() -> "Arc Sodium chunk metadata", GpuBuffer.USAGE_MAP_WRITE | ArcMeshTerrainRenderer.USAGE_STORAGE, chunkCapacity);
	}

	private static int growCapacity(final int required) {
		int capacity = MIN_RING_BYTES;
		while (capacity < required) {
			capacity = Math.multiplyExact(capacity, 2);
		}
		return capacity;
	}

	private static boolean isCompatibleDevice() {
		if (!ArcMeshConfig.vulkanMeshFeatureEnabled()) {
			return false;
		}
		GpuDeviceBackend backend = ((GpuDeviceAccessor)(Object)RenderSystem.getDevice()).meshShadersWithArc$getBackend();
		return backend instanceof VulkanDevice device && device.vkDevice().getCapabilities().VK_EXT_mesh_shader;
	}

	public static void onDeviceClosing(final VulkanDevice device) {
		if (activeDevice != null && activeDevice != device) {
			return;
		}
		if (activeDevice == device) {
			device.graphicsQueue().waitIdle();
		}
		closePipelines();
		if (opaqueTaskRing != null) opaqueTaskRing.close();
		if (translucentTaskRing != null) translucentTaskRing.close();
		if (chunkRing != null) chunkRing.close();
		opaqueTaskRing = null;
		translucentTaskRing = null;
		chunkRing = null;
		opaqueTaskCapacity = 0;
		translucentTaskCapacity = 0;
		chunkCapacity = 0;
		activeDevice = null;
		activeColorFormat = null;
		activeDepthFormat = null;
		disabledAfterError = false;
		optionalTaskPathDisabled = false;
		announcedOpaque = false;
		announcedTranslucent = false;
		announcedTaskCulling = false;
		opaquePassCount = 0L;
		translucentPassCount = 0L;
		lastOpaqueTaskCount = 0;
		lastPackedTaskCount = 0;
		retainedOpaqueTaskCount = 0L;
		lastTranslucentTaskCount = 0;
		lastPackedTranslucentTaskCount = 0;
	}

	public static boolean hasRenderedOpaque() {
		return announcedOpaque;
	}

	public static boolean hasRenderedTranslucent() {
		return announcedTranslucent;
	}

	public static long opaquePassCount() {
		return opaquePassCount;
	}

	public static long translucentPassCount() {
		return translucentPassCount;
	}

	public static int lastOpaqueTaskCount() {
		return lastOpaqueTaskCount;
	}

	public static int lastPackedTaskCount() {
		return lastPackedTaskCount;
	}

	public static long retainedOpaqueTaskCount() {
		return retainedOpaqueTaskCount;
	}

	public static int lastTranslucentTaskCount() {
		return lastTranslucentTaskCount;
	}

	public static int lastPackedTranslucentTaskCount() {
		return lastPackedTranslucentTaskCount;
	}

	public static boolean hasRenderedTaskCulling() {
		return announcedTaskCulling;
	}

	public static boolean isHzbValid() {
		return occlusionPyramid != null && occlusionPyramid.hasValidRead();
	}

	public static long hzbBuildCount() {
		return occlusionPyramid == null ? 0L : occlusionPyramid.buildCount();
	}

	private static int visibleMask(final CameraTransform camera, final int sectionX, final int sectionY, final int sectionZ) {
		if (!SodiumClientMod.options().performance.useBlockFaceCulling) {
			return ModelQuadFacing.ALL;
		}
		return DefaultChunkRenderer.getVisibleFaces(camera.intX, camera.intY, camera.intZ, sectionX, sectionY, sectionZ);
	}

	/**
	 * Conservatively rejects section bounds against Vulkan's homogeneous clip
	 * volume. Only a box wholly behind one clip plane is rejected; ambiguous or
	 * non-finite matrix results stay visible so this test cannot create holes.
	 */
	private static boolean sectionIntersectsFrustum(
		final ChunkRenderMatrices matrices,
		final CameraTransform camera,
		final int sectionX,
		final int sectionY,
		final int sectionZ
	) {
		double originX = (double)sectionX * 16.0 - camera.x;
		double originY = (double)sectionY * 16.0 - camera.y;
		double originZ = (double)sectionZ * 16.0 - camera.z;
		Matrix4fc modelView = matrices.modelView();
		Matrix4fc projection = matrices.projection();

		double maximumLeft = Double.NEGATIVE_INFINITY;
		double maximumRight = Double.NEGATIVE_INFINITY;
		double maximumBottom = Double.NEGATIVE_INFINITY;
		double maximumTop = Double.NEGATIVE_INFINITY;
		double maximumNear = Double.NEGATIVE_INFINITY;
		double maximumFar = Double.NEGATIVE_INFINITY;
		double magnitude = 1.0;

		for (int corner = 0; corner < 8; corner++) {
			// CompactChunkVertex deliberately permits models to extend eight blocks
			// outside their owning section. Use that entire encoded domain so a
			// modded oversized model cannot be incorrectly frustum-rejected.
			double x = originX + ((corner & 1) == 0 ? -8.0 : 24.0);
			double y = originY + ((corner & 2) == 0 ? -8.0 : 24.0);
			double z = originZ + ((corner & 4) == 0 ? -8.0 : 24.0);

			double viewX = modelView.m00() * x + modelView.m10() * y + modelView.m20() * z + modelView.m30();
			double viewY = modelView.m01() * x + modelView.m11() * y + modelView.m21() * z + modelView.m31();
			double viewZ = modelView.m02() * x + modelView.m12() * y + modelView.m22() * z + modelView.m32();
			double viewW = modelView.m03() * x + modelView.m13() * y + modelView.m23() * z + modelView.m33();

			double clipX = projection.m00() * viewX + projection.m10() * viewY + projection.m20() * viewZ + projection.m30() * viewW;
			double clipY = projection.m01() * viewX + projection.m11() * viewY + projection.m21() * viewZ + projection.m31() * viewW;
			double clipZ = projection.m02() * viewX + projection.m12() * viewY + projection.m22() * viewZ + projection.m32() * viewW;
			double clipW = projection.m03() * viewX + projection.m13() * viewY + projection.m23() * viewZ + projection.m33() * viewW;
			if (!Double.isFinite(clipX) || !Double.isFinite(clipY) || !Double.isFinite(clipZ) || !Double.isFinite(clipW)) {
				return true;
			}

			maximumLeft = Math.max(maximumLeft, clipX + clipW);
			maximumRight = Math.max(maximumRight, clipW - clipX);
			maximumBottom = Math.max(maximumBottom, clipY + clipW);
			maximumTop = Math.max(maximumTop, clipW - clipY);
			maximumNear = Math.max(maximumNear, clipZ);
			maximumFar = Math.max(maximumFar, clipW - clipZ);
			magnitude = Math.max(magnitude, Math.max(Math.max(Math.abs(clipX), Math.abs(clipY)), Math.max(Math.abs(clipZ), Math.abs(clipW))));
		}

		// Expand the accepted volume slightly to cover CPU/GPU floating-point differences.
		double tolerance = 1.0e-4 + magnitude * 1.0e-5;
		return maximumLeft >= -tolerance
			&& maximumRight >= -tolerance
			&& maximumBottom >= -tolerance
			&& maximumTop >= -tolerance
			&& maximumNear >= -tolerance
			&& maximumFar >= -tolerance;
	}

	private static void appendOpaqueTasks(
		final List<OpaqueTask> tasks,
		final int firstVertex,
		final int vertexCount,
		final int chunkWordOffset
	) {
		if (vertexCount < 0 || vertexCount % 4 != 0) {
			throw new IllegalArgumentException("Sodium terrain segment is not a whole number of quads");
		}
		int processed = 0;
		int quads = vertexCount / 4;
		while (processed < quads) {
			int count = Math.min(QUADS_PER_WORKGROUP, quads - processed);
			tasks.add(new OpaqueTask(Math.addExact(firstVertex, processed * 4), count, chunkWordOffset));
			processed += count;
		}
	}

	private static final class OpaquePlan {
		private final List<OpaqueTask> tasks;
		private final List<OpaqueBatch> batches;
		private final List<ChunkRecord> chunks;
		private final long geometryEpoch;
		private final boolean stableGeometry;
		private final int retainedTaskCount;

		private OpaquePlan(
			List<OpaqueTask> tasks,
			List<OpaqueBatch> batches,
			List<ChunkRecord> chunks,
			long geometryEpoch,
			boolean stableGeometry,
			int retainedTaskCount
		) {
			this.tasks = tasks;
			this.batches = batches;
			this.chunks = chunks;
			this.geometryEpoch = geometryEpoch;
			this.stableGeometry = stableGeometry;
			this.retainedTaskCount = retainedTaskCount;
		}

		private static @Nullable OpaquePlan create(
			final ChunkRenderMatrices matrices,
			final ChunkRenderListIterable lists,
			final TerrainRenderPass pass,
			final CameraTransform camera
		) {
			long epochBefore = ArcOcclusionInvalidation.geometryEpoch();
			List<OpaqueTask> tasks = new ArrayList<>();
			List<OpaqueBatch> batches = new ArrayList<>();
			List<ChunkRecord> chunks = new ArrayList<>();
			int retainedTaskCount = 0;
			Iterator<ChunkRenderList> regions = lists.iterator(false);
			while (regions.hasNext()) {
				ChunkRenderList list = regions.next();
				RenderRegion region = list.getRegion();
				SectionRenderDataStorage storage = region.getStorage(pass);
				if (storage == null) continue;
				ByteIterator sections = list.sectionsWithGeometryIterator(false);
				if (sections == null) continue;

				while (sections.hasNext()) {
					int localIndex = sections.nextByteAsInt();
					int sectionX = region.getChunkX() + LocalSectionIndex.unpackX(localIndex);
					int sectionY = region.getChunkY() + LocalSectionIndex.unpackY(localIndex);
					int sectionZ = region.getChunkZ() + LocalSectionIndex.unpackZ(localIndex);
					long pointer = storage.getDataPointer(localIndex);
					int mask = SectionRenderDataUnsafe.getSliceMask(pointer) & visibleMask(camera, sectionX, sectionY, sectionZ);
					if (mask == 0) continue;

					SodiumAllocation packed = ArcMeshConfig.packedGeometryEnabled()
						? PackedGeometryManager.findSodium(sectionX, sectionY, sectionZ, pass)
						: null;
					GpuBuffer geometry;
					int format;
					if (packed != null) {
						geometry = packed.buffer();
						format = FORMAT_SODIUM_PACKED;
					} else {
						if (region.getResources() == null) return null;
						geometry = region.getResources().getGeometryBuffer();
						format = FORMAT_SODIUM_COMPACT;
					}

					int firstTask = tasks.size();
					int chunkWords = chunks.size() * CHUNK_WORDS;
					if (packed != null) {
						for (int segment = 0; segment < packed.segmentCapacity(); segment++) {
							int count = packed.segmentVertexCount(segment);
							if (count == 0) continue;
							int facing = packed.segmentFacing(segment);
							if (facing < 0 || facing >= ModelQuadFacing.COUNT) return null;
							if (((mask >>> facing) & 1) != 0) {
								appendOpaqueTasks(tasks, packed.segmentFirstVertex(segment), count, chunkWords);
							}
						}
					} else {
						long base = SectionRenderDataUnsafe.getBaseVertex(pointer);
						long facingList = SectionRenderDataUnsafe.getFacingList(pointer);
						for (int segment = 0; segment < ModelQuadFacing.COUNT; segment++) {
							int count = Math.toIntExact(SectionRenderDataUnsafe.getVertexCount(pointer, segment));
							int facing = (int)((facingList >>> (segment * 8)) & 0xffL);
							if (count != 0 && (facing < 0 || facing >= ModelQuadFacing.COUNT)) return null;
							if (count != 0 && ((mask >>> facing) & 1) != 0) {
								appendOpaqueTasks(tasks, Math.toIntExact(base), count, chunkWords);
							}
							base += count;
						}
					}
					if (tasks.size() != firstTask) {
						chunks.add(new ChunkRecord(sectionX << 4, sectionY << 4, sectionZ << 4));
						appendOpaqueBatch(batches, geometry, format, firstTask, tasks.size() - firstTask);
					}
				}
			}

			if (ArcMeshConfig.packedGeometryEnabled()) {
				SodiumTerrainLayer layer = SodiumTerrainLayer.from(pass);
				if (layer != null) {
					int keepDistance = ArcMeshConfig.keepDistanceChunks();
					int cameraSectionX = SectionPos.blockToSectionCoord(Mth.floor(camera.x));
					int cameraSectionZ = SectionPos.blockToSectionCoord(Mth.floor(camera.z));
					for (SodiumAllocation packed : PackedGeometryManager.detachedSodiumAllocationsSnapshot(layer)) {
						int sectionX = packed.sectionX();
						int sectionY = packed.sectionY();
						int sectionZ = packed.sectionZ();
						if (keepDistance < ArcSettings.KEEP_ALL_DISTANCE_CHUNKS
							&& (Math.abs((long)sectionX - cameraSectionX) > keepDistance
								|| Math.abs((long)sectionZ - cameraSectionZ) > keepDistance)) {
							continue;
						}
						if (!sectionIntersectsFrustum(matrices, camera, sectionX, sectionY, sectionZ)) {
							continue;
						}

						int mask = visibleMask(camera, sectionX, sectionY, sectionZ);
						if (mask == 0) continue;
						int firstTask = tasks.size();
						int chunkWords = chunks.size() * CHUNK_WORDS;
						for (int segment = 0; segment < packed.segmentCapacity(); segment++) {
							int count = packed.segmentVertexCount(segment);
							if (count == 0) continue;
							int facing = packed.segmentFacing(segment);
							if (facing < 0 || facing >= ModelQuadFacing.COUNT) return null;
							if (((mask >>> facing) & 1) != 0) {
								appendOpaqueTasks(tasks, packed.segmentFirstVertex(segment), count, chunkWords);
							}
						}
						if (tasks.size() != firstTask) {
							retainedTaskCount += tasks.size() - firstTask;
							chunks.add(new ChunkRecord(sectionX << 4, sectionY << 4, sectionZ << 4));
							appendOpaqueBatch(batches, packed.buffer(), FORMAT_SODIUM_PACKED, firstTask, tasks.size() - firstTask);
						}
					}
				}
			}
			long epochAfter = ArcOcclusionInvalidation.geometryEpoch();
			return new OpaquePlan(tasks, batches, chunks, epochAfter, epochBefore == epochAfter, retainedTaskCount);
		}

		private int packedTaskCount() {
			int count = 0;
			for (OpaqueBatch batch : batches) if (batch.geometryFormat == FORMAT_SODIUM_PACKED) count += batch.taskCount;
			return count;
		}
	}

	private static void appendOpaqueBatch(final List<OpaqueBatch> batches, final GpuBuffer geometry, final int format, final int firstTask, final int count) {
		if (!batches.isEmpty()) {
			OpaqueBatch previous = batches.getLast();
			if (previous.geometryBuffer == geometry && previous.geometryFormat == format && previous.firstTask + previous.taskCount == firstTask) {
				batches.set(batches.size() - 1, new OpaqueBatch(geometry, previous.firstTask, previous.taskCount + count, format));
				return;
			}
		}
		batches.add(new OpaqueBatch(geometry, firstTask, count, format));
	}

	private static final class TranslucentPlan {
		private final List<TranslucentTask> tasks;
		private final List<TranslucentBatch> batches;
		private final List<ChunkRecord> chunks;

		private TranslucentPlan(List<TranslucentTask> tasks, List<TranslucentBatch> batches, List<ChunkRecord> chunks) {
			this.tasks = tasks;
			this.batches = batches;
			this.chunks = chunks;
		}

		private static @Nullable TranslucentPlan create(
			final ChunkRenderListIterable lists,
			final TerrainRenderPass pass,
			final CameraTransform camera,
			final boolean indexedRenderingEnabled
		) {
			List<TranslucentTask> tasks = new ArrayList<>();
			List<TranslucentBatch> batches = new ArrayList<>();
			List<ChunkRecord> chunks = new ArrayList<>();
			Iterator<ChunkRenderList> regions = lists.iterator(true);
			while (regions.hasNext()) {
				ChunkRenderList list = regions.next();
				RenderRegion region = list.getRegion();
				SectionRenderDataStorage storage = region.getStorage(pass);
				if (storage == null || region.getResources() == null) continue;
				GpuBuffer sodiumGeometry = region.getResources().getGeometryBuffer();
				GpuBuffer indexBuffer = region.getResources().getIndexBuffer();
				ByteIterator sections = list.sectionsWithGeometryIterator(true);
				if (sections == null) continue;

				while (sections.hasNext()) {
					int localIndex = sections.nextByteAsInt();
					int sectionX = region.getChunkX() + LocalSectionIndex.unpackX(localIndex);
					int sectionY = region.getChunkY() + LocalSectionIndex.unpackY(localIndex);
					int sectionZ = region.getChunkZ() + LocalSectionIndex.unpackZ(localIndex);
					long pointer = storage.getDataPointer(localIndex);
					int mask = SectionRenderDataUnsafe.getSliceMask(pointer) & visibleMask(camera, sectionX, sectionY, sectionZ);
					if (mask == 0) continue;

					SodiumAllocation packed = ArcMeshConfig.packedGeometryEnabled()
						? PackedGeometryManager.findSodium(sectionX, sectionY, sectionZ, pass)
						: null;
					GpuBuffer geometry = packed == null ? sodiumGeometry : packed.buffer();
					int format = packed == null ? FORMAT_SODIUM_COMPACT : FORMAT_SODIUM_PACKED;
					boolean localIndices = indexedRenderingEnabled && SectionRenderDataUnsafe.isLocalIndex(pointer);
					int indexType = localIndices ? INDEX_UINT : INDEX_DIRECT_QUADS;
					long firstElement = SectionRenderDataUnsafe.getBaseElement(pointer);
					long directBase = SectionRenderDataUnsafe.getBaseVertex(pointer);
					long facingList = SectionRenderDataUnsafe.getFacingList(pointer);
					int firstTask = tasks.size();
					int chunkWords = chunks.size() * CHUNK_WORDS;

					int segmentCount = packed == null ? ModelQuadFacing.COUNT : packed.segmentCapacity();
					for (int segment = 0; segment < segmentCount; segment++) {
						int vertexCount = packed == null
							? Math.toIntExact(SectionRenderDataUnsafe.getVertexCount(pointer, segment))
							: packed.segmentVertexCount(segment);
						int facing = packed == null
							? (int)((facingList >>> (segment * 8)) & 0xffL)
							: packed.segmentFacing(segment);
						int segmentBase = packed == null ? Math.toIntExact(directBase) : packed.segmentFirstVertex(segment);
						if (vertexCount < 0 || vertexCount % 4 != 0) return null;
						if (vertexCount != 0 && (facing < 0 || facing >= ModelQuadFacing.COUNT)) return null;
						if (vertexCount != 0 && ((mask >>> facing) & 1) != 0) {
							int quads = vertexCount / 4;
							for (int processed = 0; processed < quads; processed += QUADS_PER_WORKGROUP) {
								int count = Math.min(QUADS_PER_WORKGROUP, quads - processed);
								// Sodium's local sorted indices are relative to the start of the
								// whole facing segment. Only direct quads synthesize lane-relative
								// indices and therefore need their vertex base advanced per task.
								int taskBaseVertex = localIndices
									? segmentBase
									: Math.addExact(segmentBase, processed * 4);
								tasks.add(new TranslucentTask(
									taskBaseVertex,
									localIndices ? Math.toIntExact(firstElement + (long)processed * 6L) : 0,
									count, chunkWords, indexType
								));
							}
						}
						directBase += vertexCount;
						firstElement += (long)(vertexCount / 4) * 6L;
					}
					if (tasks.size() != firstTask) {
						chunks.add(new ChunkRecord(sectionX << 4, sectionY << 4, sectionZ << 4));
						appendTranslucentBatch(batches, geometry, indexBuffer, format, firstTask, tasks.size() - firstTask);
					}
				}
			}
			return new TranslucentPlan(tasks, batches, chunks);
		}

		private int packedTaskCount() {
			int count = 0;
			for (TranslucentBatch batch : batches) {
				if (batch.geometryFormat == FORMAT_SODIUM_PACKED) count += batch.taskCount;
			}
			return count;
		}
	}

	private static void appendTranslucentBatch(
		final List<TranslucentBatch> batches,
		final GpuBuffer geometry,
		final GpuBuffer index,
		final int format,
		final int firstTask,
		final int count
	) {
		if (!batches.isEmpty()) {
			TranslucentBatch previous = batches.getLast();
			if (previous.geometryBuffer == geometry && previous.indexBuffer == index && previous.geometryFormat == format
				&& previous.firstTask + previous.taskCount == firstTask) {
				batches.set(batches.size() - 1, new TranslucentBatch(geometry, index, previous.firstTask, previous.taskCount + count, format));
				return;
			}
		}
		batches.add(new TranslucentBatch(geometry, index, firstTask, count, format));
	}

	private record RenderResources(
		VulkanDevice device,
		RenderTarget target,
		GpuBuffer globals,
		GpuBufferSlice projection,
		GpuBufferSlice fog,
		GpuTextureView lightmap,
		GpuSampler lightmapSampler
	) {
		private static @Nullable RenderResources capture(final RenderTarget target, final GpuBufferSlice projection) {
			GpuBuffer globals = RenderSystem.getGlobalSettingsUniform();
			GpuBufferSlice fog = RenderSystem.getShaderFog();
			GpuTextureView depth = target.getDepthTextureView();
			GpuDeviceBackend backend = ((GpuDeviceAccessor)(Object)RenderSystem.getDevice()).meshShadersWithArc$getBackend();
			if (globals == null
				|| fog == null
				|| depth == null
				|| projection.length() < SODIUM_GLOBALS_MIN_BYTES
				|| !(backend instanceof VulkanDevice device)) return null;
			GpuTextureView lightmap = Minecraft.getInstance().gameRenderer.lightmap();
			GpuSampler lightmapSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
			return new RenderResources(device, target, globals, projection, fog, lightmap, lightmapSampler);
		}
	}

	private record ChunkRecord(int blockX, int blockY, int blockZ) {
	}

	private record OpaqueTask(int baseVertex, int quadCount, int chunkWordOffset) {
	}

	private record OpaqueBatch(GpuBuffer geometryBuffer, int firstTask, int taskCount, int geometryFormat) {
	}

	private record TranslucentTask(int baseVertex, int firstIndex, int quadCount, int chunkWordOffset, int indexType) {
	}

	private record TranslucentBatch(GpuBuffer geometryBuffer, GpuBuffer indexBuffer, int firstTask, int taskCount, int geometryFormat) {
	}
}
