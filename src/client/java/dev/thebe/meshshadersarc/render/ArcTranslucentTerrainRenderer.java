package dev.thebe.meshshadersarc.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
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

/**
 * Replays Minecraft's sorted translucent index stream through a mesh pipeline.
 * Draws, tasks, and primitives stay in vanilla's back-to-front order.
 */
public final class ArcTranslucentTerrainRenderer {
	private static final int QUADS_PER_WORKGROUP = 32;
	private static final int VANILLA_VERTEX_BYTES = 28;
	private static final int TASK_BYTES = 32;
	private static final int MIN_TASK_BUFFER_BYTES = 64 * 1024;

	private static @Nullable VulkanDevice activeDevice;
	private static @Nullable ArcTranslucentMeshPipeline pipeline;
	private static @Nullable GpuFormat activeColorFormat;
	private static @Nullable GpuFormat activeDepthFormat;
	private static @Nullable MappableRingBuffer taskRing;
	private static int taskRingCapacity;
	private static boolean disabledAfterError;
	private static boolean announcedActive;
	private static int lastTaskCount;
	private static int lastPackedTaskCount;
	private static int lastUnpackedTaskCount;
	private static long successfulPassCount;

	private ArcTranslucentTerrainRenderer() {
	}

	public static boolean render(final ChunkSectionsToRender sections, final GpuSampler atlasSampler) {
		if (!ArcMeshConfig.customTranslucencyEnabled() || disabledAfterError || !isCompatibleDevice()) {
			return false;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (SharedConstants.DEBUG_HOTKEYS && minecraft.wireframe) {
			return false;
		}

		RenderPlan plan = RenderPlan.create(sections);
		if (plan == null) {
			return false;
		}
		if (plan.tasks.isEmpty()) {
			return true;
		}

		GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();
		GpuBufferSlice fog = RenderSystem.getShaderFog();
		GpuBuffer globals = RenderSystem.getGlobalSettingsUniform();
		if (projection == null || fog == null || globals == null) {
			return false;
		}

		GpuDeviceBackend backend = ((GpuDeviceAccessor)(Object)RenderSystem.getDevice()).meshShadersWithArc$getBackend();
		if (!(backend instanceof VulkanDevice device)) {
			return false;
		}

		RenderTarget target = ChunkSectionLayerGroup.TRANSLUCENT.outputTarget();
		boolean customPassAttempted = false;
		try {
			ArcTranslucentMeshPipeline activePipeline = getOrCreatePipeline(device, target);
			GpuBuffer taskBuffer = writeTasks(plan.tasks);
			activePipeline.requireStorageRange(taskBuffer, "Translucent task");
			activePipeline.requireStorageRange(plan.chunkBuffer, "Chunk uniform heap");
			for (Segment segment : plan.segments) {
				activePipeline.requireStorageRange(segment.geometryBuffer, "Translucent geometry heap");
				activePipeline.requireStorageRange(segment.indexBuffer, "Translucent index heap");
			}

			GpuTextureView lightmap = minecraft.gameRenderer.lightmap();
			GpuSampler lightmapSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
			customPassAttempted = true;
			try (RenderPass renderPass = RenderSystem.getDevice()
				.createCommandEncoder()
				.createRenderPass(
					() -> "Arc mesh-shader sorted translucent terrain",
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
				activePipeline.begin(pass.meshShadersWithArc$getCommandBuffer());
				for (Segment segment : plan.segments) {
					activePipeline.draw(
						pass.meshShadersWithArc$getCommandBuffer(),
						segment.geometryBuffer,
						segment.indexBuffer,
						taskBuffer,
						plan.chunkBuffer,
						globals.slice(),
						projection,
						fog,
						sections.textureView(),
						atlasSampler,
						lightmap,
						lightmapSampler,
						segment.firstTask,
						segment.taskCount,
						segment.packed ? 1 : 0
					);
				}
			}

			taskRing.rotate();
			lastTaskCount = plan.tasks.size();
			lastPackedTaskCount = plan.taskCount(true);
			lastUnpackedTaskCount = plan.taskCount(false);
			successfulPassCount++;
			if (!announcedActive) {
				announcedActive = true;
				MeshShadersWithArcClient.LOGGER.info(
					"Sorted mesh-shader translucency active with {} workgroups this frame",
					plan.tasks.size()
				);
			}
			return true;
		} catch (Throwable throwable) {
			disabledAfterError = true;
			MeshShadersWithArcClient.LOGGER.error(
				"Disabling sorted mesh-shader translucency after a rendering failure; vanilla translucency will be used from the next safe draw",
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

	public static int lastUnpackedTaskCount() {
		return lastUnpackedTaskCount;
	}

	public static long successfulPassCount() {
		return successfulPassCount;
	}

	private static boolean isCompatibleDevice() {
		if (!ArcMeshConfig.vulkanMeshFeatureEnabled()) {
			return false;
		}
		GpuDeviceBackend backend = ((GpuDeviceAccessor)(Object)RenderSystem.getDevice()).meshShadersWithArc$getBackend();
		return backend instanceof VulkanDevice device && device.vkDevice().getCapabilities().VK_EXT_mesh_shader;
	}

	private static ArcTranslucentMeshPipeline getOrCreatePipeline(final VulkanDevice device, final RenderTarget target) {
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
			pipeline = ArcTranslucentMeshPipeline.create(device, colorFormat, depthFormat);
			activeDevice = device;
			activeColorFormat = colorFormat;
			activeDepthFormat = depthFormat;
		}
		return pipeline;
	}

	private static GpuBuffer writeTasks(final List<Task> tasks) {
		int bytes = Math.multiplyExact(tasks.size(), TASK_BYTES);
		ensureTaskCapacity(bytes);
		GpuBuffer buffer = taskRing.currentBuffer();
		try (GpuBufferSlice.MappedView mapping = buffer.slice(0L, bytes).map(false, true)) {
			ByteBuffer data = mapping.data().order(ByteOrder.nativeOrder());
			for (Task task : tasks) {
				data.putInt(task.baseVertex);
				data.putInt(task.firstIndex);
				data.putInt(task.quadCount);
				data.putInt(task.chunkWordOffset);
				data.putInt(task.indexType);
				data.putInt(0);
				data.putInt(0);
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
			() -> "Arc sorted translucent terrain tasks",
			GpuBuffer.USAGE_MAP_WRITE | ArcMeshTerrainRenderer.USAGE_STORAGE,
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
		activeDevice = null;
		activeColorFormat = null;
		activeDepthFormat = null;
		disabledAfterError = false;
		announcedActive = false;
		lastTaskCount = 0;
		lastPackedTaskCount = 0;
		lastUnpackedTaskCount = 0;
		successfulPassCount = 0L;
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
		private final List<Segment> segments;
		private final GpuBuffer chunkBuffer;

		private RenderPlan(final List<Task> tasks, final List<Segment> segments, final GpuBuffer chunkBuffer) {
			this.tasks = tasks;
			this.segments = segments;
			this.chunkBuffer = chunkBuffer;
		}

		private static @Nullable RenderPlan create(final ChunkSectionsToRender sections) {
			List<Task> tasks = new ArrayList<>();
			List<MutableSegment> pendingSegments = new ArrayList<>();
			GpuBuffer chunkBuffer = null;
			Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>> groups = sections.drawGroupsPerLayer()
				.get(ChunkSectionLayer.TRANSLUCENT);

			for (List<RenderPass.Draw<GpuBufferSlice[]>> forwardDraws : groups.values()) {
				for (RenderPass.Draw<GpuBufferSlice[]> draw : forwardDraws.reversed()) {
					if (draw.indexBuffer() == null
						|| draw.indexType() == null
						|| draw.firstIndex() < 0
						|| draw.baseVertex() < 0
						|| draw.indexCount() < 0
						|| draw.indexCount() % 6 != 0) {
						return null;
					}

					long indexEnd = Math.addExact(
						Math.multiplyExact((long)draw.firstIndex(), draw.indexType().bytes),
						Math.multiplyExact((long)draw.indexCount(), draw.indexType().bytes)
					);
					if (indexEnd > draw.indexBuffer().size()) {
						return null;
					}

					GpuBufferSlice chunkUniform = captureChunkUniform(draw, sections.chunkSectionInfos());
					if (chunkUniform == null || chunkUniform.offset() % Integer.BYTES != 0L) {
						return null;
					}
					if (chunkBuffer == null) {
						chunkBuffer = chunkUniform.buffer();
					} else if (chunkBuffer != chunkUniform.buffer()) {
						return null;
					}

					int totalQuads = draw.indexCount() / 6;
					long sourceByteOffset = Math.multiplyExact((long)draw.baseVertex(), VANILLA_VERTEX_BYTES);
					PackedAllocation packedAllocation = PackedGeometryManager.find(draw.vertexBuffer(), sourceByteOffset);
					boolean packed = packedAllocation != null && packedAllocation.quadCount() >= totalQuads;
					GpuBuffer geometryBuffer = packed ? packedAllocation.buffer() : draw.vertexBuffer();
					int baseVertex = packed ? packedAllocation.firstVertex() : draw.baseVertex();

					if (!packed) {
						long vertexEnd = Math.multiplyExact(
							Math.addExact((long)baseVertex, Math.multiplyExact((long)totalQuads, 4L)),
							VANILLA_VERTEX_BYTES
						);
						if (vertexEnd > geometryBuffer.size()) {
							return null;
						}
					}

					MutableSegment segment;
					if (!pendingSegments.isEmpty()
						&& pendingSegments.getLast().matches(geometryBuffer, draw.indexBuffer(), packed)) {
						segment = pendingSegments.getLast();
					} else {
						segment = new MutableSegment(geometryBuffer, draw.indexBuffer(), tasks.size(), packed);
						pendingSegments.add(segment);
					}

					int chunkWordOffset = Math.toIntExact(chunkUniform.offset() / Integer.BYTES);
					int processedQuads = 0;
					while (processedQuads < totalQuads) {
						int quadCount = Math.min(totalQuads - processedQuads, QUADS_PER_WORKGROUP);
						int firstIndex = Math.addExact(draw.firstIndex(), Math.multiplyExact(processedQuads, 6));
						tasks.add(
							new Task(
								baseVertex,
								firstIndex,
								quadCount,
								chunkWordOffset,
								draw.indexType() == IndexType.SHORT ? 0 : 1
							)
						);
						segment.taskCount++;
						processedQuads += quadCount;
					}
				}
			}

			List<Segment> segments = pendingSegments.stream().map(MutableSegment::freeze).toList();
			if (tasks.isEmpty()) {
				GpuBuffer fallback = sections.chunkSectionInfos().length == 0 ? null : sections.chunkSectionInfos()[0].buffer();
				return new RenderPlan(tasks, segments, fallback);
			}
			return chunkBuffer == null ? null : new RenderPlan(tasks, segments, chunkBuffer);
		}

		private int taskCount(final boolean packed) {
			int count = 0;
			for (Segment segment : this.segments) {
				if (segment.packed == packed) {
					count += segment.taskCount;
				}
			}
			return count;
		}
	}

	private record Task(int baseVertex, int firstIndex, int quadCount, int chunkWordOffset, int indexType) {
	}

	private record Segment(
		GpuBuffer geometryBuffer,
		GpuBuffer indexBuffer,
		int firstTask,
		int taskCount,
		boolean packed
	) {
	}

	private static final class MutableSegment {
		private final GpuBuffer geometryBuffer;
		private final GpuBuffer indexBuffer;
		private final int firstTask;
		private final boolean packed;
		private int taskCount;

		private MutableSegment(
			final GpuBuffer geometryBuffer,
			final GpuBuffer indexBuffer,
			final int firstTask,
			final boolean packed
		) {
			this.geometryBuffer = geometryBuffer;
			this.indexBuffer = indexBuffer;
			this.firstTask = firstTask;
			this.packed = packed;
		}

		private boolean matches(final GpuBuffer geometryBuffer, final GpuBuffer indexBuffer, final boolean packed) {
			return this.geometryBuffer == geometryBuffer && this.indexBuffer == indexBuffer && this.packed == packed;
		}

		private Segment freeze() {
			return new Segment(this.geometryBuffer, this.indexBuffer, this.firstTask, this.taskCount, this.packed);
		}
	}
}
