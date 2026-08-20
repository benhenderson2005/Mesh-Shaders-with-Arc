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
import dev.thebe.meshshadersarc.geometry.PackedGeometryManager;
import dev.thebe.meshshadersarc.geometry.PackedGeometryManager.PackedAllocation;
import dev.thebe.meshshadersarc.mixin.GpuDeviceAccessor;
import dev.thebe.meshshadersarc.mixin.RenderPassAccessor;
import dev.thebe.meshshadersarc.mixin.VulkanRenderPassAccessor;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Batches vanilla's already-compiled opaque quad meshes into mesh workgroups.
 * Vanilla remains responsible for section compilation, visibility, uploads, and translucent sorting.
 */
public final class ArcMeshTerrainRenderer {
	public static final int USAGE_STORAGE = 1 << 10;
	private static final int QUADS_PER_WORKGROUP = 32;
	private static final int VANILLA_VERTEX_BYTES = 28;
	private static final int TASK_BYTES = 16;
	private static final int MIN_TASK_BUFFER_BYTES = 64 * 1024;

	private static @Nullable VulkanDevice activeDevice;
	private static @Nullable ArcMeshPipeline pipeline;
	private static @Nullable ArcTaskMeshPipeline taskPipeline;
	private static @Nullable ArcOcclusionPyramid occlusionPyramid;
	private static @Nullable GpuFormat activeColorFormat;
	private static @Nullable GpuFormat activeDepthFormat;
	private static @Nullable MappableRingBuffer taskRing;
	private static int taskRingCapacity;
	private static boolean disabledAfterError;
	private static boolean taskPathDisabledAfterError;
	private static boolean announcedActive;
	private static boolean announcedTaskCulling;
	private static int lastTaskCount;
	private static int lastPackedTaskCount;
	private static int lastTaskCullingCandidateCount;
	private static int lastTaskDispatchCount;
	private static long successfulPassCount;
	private static long taskCulledPassCount;

	private ArcMeshTerrainRenderer() {
	}

	public static boolean renderOpaque(final ChunkSectionsToRender sections, final GpuSampler atlasSampler) {
		if (!ArcMeshConfig.enabled() || disabledAfterError || !isCompatibleDevice()) {
			invalidateOcclusion();
			return false;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (SharedConstants.DEBUG_HOTKEYS && minecraft.wireframe) {
			invalidateOcclusion();
			return false;
		}

		RenderPlan plan = RenderPlan.create(sections);
		if (plan == null) {
			invalidateOcclusion();
			return false;
		}

		if (plan.tasks.isEmpty()) {
			invalidateOcclusion();
			return true;
		}

		GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();
		GpuBufferSlice fog = RenderSystem.getShaderFog();
		GpuBuffer globals = RenderSystem.getGlobalSettingsUniform();
		if (projection == null || fog == null || globals == null) {
			invalidateOcclusion();
			return false;
		}

		GpuDeviceBackend backend = ((GpuDeviceAccessor)(Object)RenderSystem.getDevice()).meshShadersWithArc$getBackend();
		if (!(backend instanceof VulkanDevice device)) {
			invalidateOcclusion();
			return false;
		}

		RenderTarget target = ChunkSectionLayerGroup.OPAQUE.outputTarget();
		GpuTextureView depthView = target.getDepthTextureView();
		if (depthView == null) {
			invalidateOcclusion();
			return false;
		}
		boolean customPassAttempted = false;
		try {
			ArcMeshPipeline activePipeline = getOrCreatePipeline(device, target);
			ensureOptionalTaskPath(device, target);
			GpuBuffer taskBuffer = writeTasks(plan.tasks);
			activePipeline.requireStorageRange(taskBuffer, "Task");
			activePipeline.requireStorageRange(plan.chunkBuffer, "Chunk uniform heap");
			for (Batch batch : plan.batches) {
				activePipeline.requireStorageRange(batch.vertexBuffer, "Terrain vertex heap");
			}
			GpuTextureView lightmap = minecraft.gameRenderer.lightmap();
			GpuSampler lightmapSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
			ArcOcclusionPyramid.ReadResources occlusionRead = null;
			if (!taskPathDisabledAfterError && occlusionPyramid != null) {
				occlusionPyramid.ensureSizeAndWorld(target.width, target.height, minecraft.level);
				occlusionRead = occlusionPyramid.readable(plan.geometryEpoch, minecraft.level);
			}
			boolean useTaskCulling = !taskPathDisabledAfterError && taskPipeline != null && occlusionRead != null;
			GpuSampler hzbSampler = useTaskCulling
				? RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, true)
				: null;
			int taskDispatches = 0;
			VkCommandBuffer commandBuffer = null;

			customPassAttempted = true;
			try (RenderPass renderPass = RenderSystem.getDevice()
				.createCommandEncoder()
				.createRenderPass(
					() -> "Arc mesh-shader opaque terrain",
					target.getColorTextureView(),
					Optional.empty(),
					target.getDepthTextureView(),
					OptionalDouble.empty()
				)) {
				RenderPassBackend passBackend = ((RenderPassAccessor)(Object)renderPass).meshShadersWithArc$getBackend();
				if (!(passBackend instanceof VulkanRenderPass)) {
					return false;
				}

				VulkanRenderPassAccessor pass = (VulkanRenderPassAccessor)(Object)passBackend;
				commandBuffer = pass.meshShadersWithArc$getCommandBuffer();
				if (useTaskCulling) {
					taskPipeline.begin(commandBuffer);
				} else {
					activePipeline.begin(commandBuffer);
				}

				for (Batch batch : plan.batches) {
					float alphaCutout = batch.layer == ChunkSectionLayer.CUTOUT ? 0.5F : 0.0F;
					if (useTaskCulling) {
						taskDispatches += taskPipeline.draw(
							commandBuffer,
							batch.vertexBuffer,
							taskBuffer,
							plan.chunkBuffer,
							globals.slice(),
							projection,
							fog,
							sections.textureView(),
							atlasSampler,
							lightmap,
							lightmapSampler,
							occlusionRead,
							hzbSampler,
							batch.firstTask,
							batch.taskCount,
							alphaCutout,
							batch.packed ? 1 : 0
						);
					} else {
						activePipeline.draw(
							commandBuffer,
							batch.vertexBuffer,
							taskBuffer,
							plan.chunkBuffer,
							globals.slice(),
							projection,
							fog,
							sections.textureView(),
							atlasSampler,
							lightmap,
							lightmapSampler,
							batch.firstTask,
							batch.taskCount,
							alphaCutout,
							batch.packed ? 1 : 0
						);
					}
				}
			}
			successfulPassCount++;

			if (!taskPathDisabledAfterError && occlusionPyramid != null && commandBuffer != null) {
				try {
					occlusionPyramid.recordBuild(
						commandBuffer,
						depthView,
						plan.chunkBuffer,
						plan.referenceChunkWordOffset,
						globals.slice(),
						projection,
						plan.geometryEpoch,
						plan.stableGeometry,
						minecraft.level
					);
				} catch (Throwable throwable) {
					disableOptionalTaskPathAfterRecording(throwable);
				}
			}

			taskRing.rotate();
			if (!announcedActive) {
				announcedActive = true;
				MeshShadersWithArcClient.LOGGER.info(
					"Active on {} with {} mesh workgroups this frame",
					RenderSystem.getDevice().getDeviceInfo().name(),
					plan.tasks.size()
				);
			}
			lastTaskCount = plan.tasks.size();
			lastPackedTaskCount = plan.packedTaskCount();
			lastTaskCullingCandidateCount = useTaskCulling ? plan.tasks.size() : 0;
			lastTaskDispatchCount = useTaskCulling ? taskDispatches : 0;
			if (useTaskCulling) {
				taskCulledPassCount++;
				if (!announcedTaskCulling) {
					announcedTaskCulling = true;
					MeshShadersWithArcClient.LOGGER.info(
						"Task/HZB occlusion active with {} candidates across {} task dispatches ({} HZB mips)",
						plan.tasks.size(),
						taskDispatches,
						occlusionPyramid == null ? 0 : occlusionPyramid.mipLevels()
					);
				}
			}
			return true;
		} catch (Throwable throwable) {
			disabledAfterError = true;
			MeshShadersWithArcClient.LOGGER.error(
				"Disabling the mesh-shader path after an initialization/rendering failure; vanilla terrain will be used from the next safe draw",
				throwable
			);
			return customPassAttempted;
		}
	}

	public static boolean hasRenderedTerrain() {
		return announcedActive;
	}

	public static int lastTaskCount() {
		return lastTaskCount;
	}

	public static int lastPackedTaskCount() {
		return lastPackedTaskCount;
	}

	public static long successfulPassCount() {
		return successfulPassCount;
	}

	public static boolean hasRenderedTaskCulling() {
		return announcedTaskCulling;
	}

	public static int lastTaskCullingCandidateCount() {
		return lastTaskCullingCandidateCount;
	}

	public static int lastTaskDispatchCount() {
		return lastTaskDispatchCount;
	}

	public static long taskCulledPassCount() {
		return taskCulledPassCount;
	}

	public static boolean isHzbValid() {
		return occlusionPyramid != null && occlusionPyramid.hasValidRead();
	}

	public static long hzbBuildCount() {
		return occlusionPyramid == null ? 0L : occlusionPyramid.buildCount();
	}

	private static boolean isCompatibleDevice() {
		if (!ArcMeshConfig.vulkanMeshFeatureEnabled()) {
			return false;
		}

		GpuDeviceBackend backend = ((GpuDeviceAccessor)(Object)RenderSystem.getDevice()).meshShadersWithArc$getBackend();
		return backend instanceof VulkanDevice device && device.vkDevice().getCapabilities().VK_EXT_mesh_shader;
	}

	private static ArcMeshPipeline getOrCreatePipeline(final VulkanDevice device, final RenderTarget target) {
		GpuFormat colorFormat = target.getColorTexture().getFormat();
		GpuFormat depthFormat = target.getDepthTexture().getFormat();
		if (pipeline == null
			|| activeDevice != device
			|| activeColorFormat != colorFormat
			|| activeDepthFormat != depthFormat) {
			if (pipeline != null) {
				activeDevice.graphicsQueue().waitIdle();
				pipeline.close();
			}
			closeOptionalTaskPath();

			pipeline = ArcMeshPipeline.create(
				device,
				colorFormat,
				depthFormat
			);
			activeDevice = device;
			activeColorFormat = colorFormat;
			activeDepthFormat = depthFormat;
		}

		return pipeline;
	}

	private static void ensureOptionalTaskPath(final VulkanDevice device, final RenderTarget target) {
		if (taskPathDisabledAfterError
			|| !ArcMeshConfig.taskCullingEnabled()
			|| !ArcMeshConfig.occlusionCullingEnabled()
			|| !ArcMeshConfig.vulkanTaskFeatureEnabled()
			|| taskPipeline != null) {
			return;
		}

		try {
			taskPipeline = ArcTaskMeshPipeline.create(
				device,
				target.getColorTexture().getFormat(),
				target.getDepthTexture().getFormat()
			);
			occlusionPyramid = ArcOcclusionPyramid.create(device);
		} catch (Throwable throwable) {
			disableOptionalTaskPath(throwable);
		}
	}

	private static void disableOptionalTaskPath(final Throwable throwable) {
		taskPathDisabledAfterError = true;
		MeshShadersWithArcClient.LOGGER.warn(
			"Disabling optional task/HZB occlusion after a failure; the direct mesh-shader path remains active",
			throwable
		);
		closeOptionalTaskPath();
	}

	private static void disableOptionalTaskPathAfterRecording(final Throwable throwable) {
		taskPathDisabledAfterError = true;
		MeshShadersWithArcClient.LOGGER.warn(
			"Disabling optional task/HZB occlusion after a command-recording failure; resources will be retained until a safe device cleanup",
			throwable
		);
		invalidateOcclusion();
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

	private static void invalidateOcclusion() {
		if (occlusionPyramid != null) {
			occlusionPyramid.invalidate();
		}
	}

	private static GpuBuffer writeTasks(final List<Task> tasks) {
		int bytes = Math.multiplyExact(tasks.size(), TASK_BYTES);
		ensureTaskCapacity(bytes);
		GpuBuffer buffer = taskRing.currentBuffer();

		try (GpuBufferSlice.MappedView mapping = buffer.slice(0L, bytes).map(false, true)) {
			ByteBuffer data = mapping.data().order(ByteOrder.nativeOrder());
			for (Task task : tasks) {
				data.putInt(task.baseVertex);
				data.putInt(task.quadCount);
				data.putInt(task.chunkWordOffset);
				data.putInt(0);
			}
		}

		return buffer;
	}

	private static void ensureTaskCapacity(final int requiredBytes) {
		if (taskRing != null && taskRingCapacity >= requiredBytes) {
			return;
		}

		int capacity = MIN_TASK_BUFFER_BYTES;
		while (capacity < requiredBytes) {
			capacity = Math.multiplyExact(capacity, 2);
		}

		if (taskRing != null) {
			taskRing.close();
		}

		taskRing = new MappableRingBuffer(
			() -> "Arc mesh terrain tasks",
			GpuBuffer.USAGE_MAP_WRITE | USAGE_STORAGE,
			capacity
		);
		taskRingCapacity = capacity;
	}

	public static void onDeviceClosing(final VulkanDevice device) {
		if (activeDevice != null && activeDevice != device) {
			return;
		}

		if (activeDevice == device) {
			device.graphicsQueue().waitIdle();
		}
		if (taskRing != null) {
			taskRing.close();
			taskRing = null;
			taskRingCapacity = 0;
		}

		if (pipeline != null) {
			pipeline.close();
			pipeline = null;
		}
		closeOptionalTaskPath();

		activeDevice = null;
		activeColorFormat = null;
		activeDepthFormat = null;
		disabledAfterError = false;
		taskPathDisabledAfterError = false;
		announcedActive = false;
		announcedTaskCulling = false;
		lastTaskCount = 0;
		lastPackedTaskCount = 0;
		lastTaskCullingCandidateCount = 0;
		lastTaskDispatchCount = 0;
		successfulPassCount = 0L;
		taskCulledPassCount = 0L;
		PackedGeometryManager.close();
		ArcMeshConfig.setVulkanMeshFeatureEnabled(false);
		ArcMeshConfig.setVulkanTaskFeatureEnabled(false);
		ArcMeshConfig.setVulkanSparseResidencyEnabled(false);
	}

	private static @Nullable GpuBufferSlice captureChunkUniform(
		final RenderPass.Draw<GpuBufferSlice[]> draw,
		final GpuBufferSlice[] sectionUniforms
	) {
		if (draw.uniformUploaderConsumer() == null) {
			return null;
		}

		GpuBufferSlice[] captured = new GpuBufferSlice[1];
		draw.uniformUploaderConsumer().accept(sectionUniforms, (name, value) -> {
			if ("ChunkSection".equals(name)) {
				captured[0] = value;
			}
		});
		return captured[0];
	}

	private static final class RenderPlan {
		private final List<Task> tasks;
		private final List<Batch> batches;
		private final GpuBuffer chunkBuffer;
		private final int referenceChunkWordOffset;
		private final long geometryEpoch;
		private final boolean stableGeometry;

		private RenderPlan(
			final List<Task> tasks,
			final List<Batch> batches,
			final GpuBuffer chunkBuffer,
			final int referenceChunkWordOffset,
			final long geometryEpoch,
			final boolean stableGeometry
		) {
			this.tasks = tasks;
			this.batches = batches;
			this.chunkBuffer = chunkBuffer;
			this.referenceChunkWordOffset = referenceChunkWordOffset;
			this.geometryEpoch = geometryEpoch;
			this.stableGeometry = stableGeometry;
		}

		private static @Nullable RenderPlan create(final ChunkSectionsToRender sections) {
			long geometryEpochBefore = ArcOcclusionInvalidation.geometryEpoch();
			List<Task> tasks = new ArrayList<>();
			List<Batch> batches = new ArrayList<>();
			GpuBuffer chunkBuffer = null;
			int referenceChunkWordOffset = -1;

			for (ChunkSectionLayer layer : ChunkSectionLayerGroup.OPAQUE.layers()) {
				List<PendingBatch> pendingBatches = new ArrayList<>();
				Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>> groups = sections.drawGroupsPerLayer().get(layer);
				for (List<RenderPass.Draw<GpuBufferSlice[]>> draws : groups.values()) {
					for (RenderPass.Draw<GpuBufferSlice[]> draw : draws) {
						if (draw.indexBuffer() != null
							|| draw.indexType() != null
							|| draw.firstIndex() != 0
							|| draw.indexCount() % 6 != 0) {
							return null;
						}

						GpuBufferSlice chunkUniform = captureChunkUniform(draw, sections.chunkSectionInfos());
						if (chunkUniform == null || chunkUniform.offset() % Integer.BYTES != 0L) {
							return null;
						}

						if (chunkBuffer == null) {
							chunkBuffer = chunkUniform.buffer();
							referenceChunkWordOffset = Math.toIntExact(chunkUniform.offset() / Integer.BYTES);
						} else if (chunkBuffer != chunkUniform.buffer()) {
							return null;
						}

						int remainingQuads = draw.indexCount() / 6;
						long sourceByteOffset = Math.multiplyExact((long)draw.baseVertex(), VANILLA_VERTEX_BYTES);
						PackedAllocation packedAllocation = PackedGeometryManager.find(draw.vertexBuffer(), sourceByteOffset);
						boolean packed = packedAllocation != null && packedAllocation.quadCount() >= remainingQuads;
						GpuBuffer geometryBuffer = packed ? packedAllocation.buffer() : draw.vertexBuffer();

						PendingBatch pending = null;
						for (PendingBatch candidate : pendingBatches) {
							if (candidate.geometryBuffer == geometryBuffer && candidate.packed == packed) {
								pending = candidate;
								break;
							}
						}

						if (pending == null) {
							pending = new PendingBatch(geometryBuffer, packed);
							pendingBatches.add(pending);
						}

						int baseVertex = packed ? packedAllocation.firstVertex() : draw.baseVertex();
						int chunkWordOffset = Math.toIntExact(chunkUniform.offset() / Integer.BYTES);
						while (remainingQuads > 0) {
							int quadCount = Math.min(remainingQuads, QUADS_PER_WORKGROUP);
							pending.tasks.add(new Task(baseVertex, quadCount, chunkWordOffset));
							baseVertex += quadCount * 4;
							remainingQuads -= quadCount;
						}
					}
				}

				for (PendingBatch pending : pendingBatches) {
					int firstTask = tasks.size();
					tasks.addAll(pending.tasks);
					batches.add(new Batch(layer, pending.geometryBuffer, firstTask, pending.tasks.size(), pending.packed));
				}
			}

			if (tasks.isEmpty()) {
				GpuBuffer fallback = sections.chunkSectionInfos().length == 0 ? null : sections.chunkSectionInfos()[0].buffer();
				long geometryEpochAfter = ArcOcclusionInvalidation.geometryEpoch();
				return new RenderPlan(tasks, batches, fallback, -1, geometryEpochAfter, geometryEpochBefore == geometryEpochAfter);
			}

			long geometryEpochAfter = ArcOcclusionInvalidation.geometryEpoch();
			return chunkBuffer == null || referenceChunkWordOffset < 0
				? null
				: new RenderPlan(
					tasks,
					batches,
					chunkBuffer,
					referenceChunkWordOffset,
					geometryEpochAfter,
					geometryEpochBefore == geometryEpochAfter
				);
		}

		private int packedTaskCount() {
			int count = 0;
			for (Batch batch : this.batches) {
				if (batch.packed) {
					count += batch.taskCount;
				}
			}
			return count;
		}
	}

	private record Task(int baseVertex, int quadCount, int chunkWordOffset) {
	}

	private record Batch(ChunkSectionLayer layer, GpuBuffer vertexBuffer, int firstTask, int taskCount, boolean packed) {
	}

	private static final class PendingBatch {
		private final GpuBuffer geometryBuffer;
		private final boolean packed;
		private final List<Task> tasks = new ArrayList<>();

		private PendingBatch(final GpuBuffer geometryBuffer, final boolean packed) {
			this.geometryBuffer = geometryBuffer;
			this.packed = packed;
		}
	}
}
