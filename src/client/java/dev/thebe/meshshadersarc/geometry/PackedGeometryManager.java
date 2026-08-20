package dev.thebe.meshshadersarc.geometry;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.thebe.meshshadersarc.ArcMeshConfig;
import dev.thebe.meshshadersarc.MeshShadersWithArcClient;
import dev.thebe.meshshadersarc.mixin.GpuDeviceAccessor;
import dev.thebe.meshshadersarc.render.ArcOcclusionInvalidation;
import dev.thebe.meshshadersarc.render.ArcMeshTerrainRenderer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Persistent packed terrain storage. Geometry is encoded once while the compiler's CPU buffer is alive,
 * uploaded into 64 MiB storage pages, and associated with vanilla's relocatable uber-buffer slice.
 */
public final class PackedGeometryManager {
	private static final long PAGE_BYTES = 64L * 1024L * 1024L;
	private static final long ALLOCATION_ALIGNMENT = PackedVertexEncoder.PACKED_VERTEX_BYTES * 4L;

	private static final IdentityHashMap<SectionMesh, EnumMap<ChunkSectionLayer, PendingGeometry>> PENDING = new IdentityHashMap<>();
	private static final IdentityHashMap<SectionMesh, EnumMap<ChunkSectionLayer, PackedAllocation>> ALLOCATIONS = new IdentityHashMap<>();
	private static final IdentityHashMap<GpuBuffer, Long2ObjectOpenHashMap<PackedAllocation>> SOURCE_LOOKUP = new IdentityHashMap<>();
	private static final Map<SodiumSectionKey, EnumMap<SodiumTerrainLayer, SodiumAllocation>> SODIUM_ALLOCATIONS = new HashMap<>();
	private static final List<GeometryArena> ARENAS = new ArrayList<>();
	private static long allocatedPageBytes;
	private static boolean arenaLimitAnnounced;
	private static boolean sparseInitializationFailed;
	private static volatile boolean disabledAfterError;
	private static long successfulLookups;
	private static long successfulSodiumUploads;

	private PackedGeometryManager() {
	}

	/**
	 * Captures only build outputs which Sodium has already accepted for upload.
	 * This is invoked at the head of {@code RenderRegionManager.uploadResults},
	 * synchronously before Sodium frees each output's native vertex buffers.
	 */
	public static synchronized void acceptSodiumUploads(final Collection<BuilderTaskOutput> results) {
		// HZB history must be invalidated for every accepted Sodium rebuild, even
		// when the optional 16-byte packed mirror is disabled.
		if (results.stream().anyMatch(result -> result instanceof ChunkBuildOutput && !result.section.isDisposed())) {
			ArcOcclusionInvalidation.terrainGeometryChanged();
		}
		if (disabledAfterError
			|| !ArcMeshConfig.vulkanMeshFeatureEnabled()
			|| !ArcMeshConfig.packedGeometryEnabled()
			|| !isActiveVulkanMeshDevice()) {
			return;
		}

		for (BuilderTaskOutput result : results) {
			if (result instanceof ChunkBuildOutput buildOutput && !result.section.isDisposed()) {
				acceptSodiumBuild(buildOutput);
			}
		}
	}

	private static void acceptSodiumBuild(final ChunkBuildOutput output) {
		RenderSection section = output.section;
		SodiumSectionKey key = new SodiumSectionKey(section.getChunkX(), section.getChunkY(), section.getChunkZ());

		for (SodiumTerrainLayer layer : SodiumTerrainLayer.values()) {
			BuiltSectionMeshParts mesh = output.getMesh(layer.pass());
			if (mesh == null) {
				removeSodiumLayer(key, layer);
				continue;
			}

			PackedVertexEncoder.EncodedGeometry encoded;
			try {
				encoded = PackedVertexEncoder.encodeSodiumCompact(mesh.getVertexData().getDirectBuffer());
			} catch (RuntimeException exception) {
				removeSodiumLayer(key, layer);
				MeshShadersWithArcClient.LOGGER.debug(
					"Skipping Sodium terrain geometry at [{}, {}, {}] which could not be repacked",
					key.x,
					key.y,
					key.z,
					exception
				);
				continue;
			}
			int[] vertexSegments = mesh.getVertexSegments().clone();
			long describedVertexCount = 0L;
			for (int segment = 0; segment < vertexSegments.length; segment += 2) {
				describedVertexCount += vertexSegments[segment];
			}
			if (describedVertexCount != (long)encoded.quadCount() * 4L) {
				MemoryUtil.memFree(encoded.data());
				removeSodiumLayer(key, layer);
				MeshShadersWithArcClient.LOGGER.warn(
					"Skipping Sodium terrain geometry at [{}, {}, {}]: segment metadata describes {} vertices but the buffer contains {}",
					key.x,
					key.y,
					key.z,
					describedVertexCount,
					(long)encoded.quadCount() * 4L
				);
				continue;
			}

			try (PendingGeometry pending = new PendingGeometry(encoded.data(), encoded.quadCount())) {
				PackedAllocation allocation = uploadPending(pending);
				if (allocation == null) {
					removeSodiumLayer(key, layer);
					continue;
				}

				SodiumAllocation sodiumAllocation = new SodiumAllocation(key, layer, allocation, vertexSegments);
				EnumMap<SodiumTerrainLayer, SodiumAllocation> perLayer = SODIUM_ALLOCATIONS.computeIfAbsent(
					key,
					ignored -> new EnumMap<>(SodiumTerrainLayer.class)
				);
				SodiumAllocation replaced = perLayer.put(layer, sodiumAllocation);
				if (replaced != null) {
					retire(replaced.allocation);
				}
				successfulSodiumUploads++;
			} catch (Throwable throwable) {
				disableSodiumUploadsAfterError(throwable);
				return;
			}
		}

		ArcOcclusionInvalidation.terrainGeometryChanged();
	}

	private static @Nullable PackedAllocation uploadPending(final PendingGeometry pending) {
		long bytes = pending.data.remaining();
		PageAllocation pageAllocation = allocate(bytes);
		if (pageAllocation == null) {
			if (!arenaLimitAnnounced) {
				arenaLimitAnnounced = true;
				MeshShadersWithArcClient.LOGGER.warn(
					"Packed terrain arena reached its {} MiB configured GPU-memory limit",
					maxArenaBytes() / (1024L * 1024L)
				);
			}
			return null;
		}

		try {
			RenderSystem.getDevice().createCommandEncoder().writeToBuffer(
				pageAllocation.arena.buffer().slice(pageAllocation.offset, bytes),
				pending.data.duplicate()
			);
		} catch (Throwable throwable) {
			pageAllocation.arena.discard(pageAllocation.offset, bytes);
			throw throwable;
		}

		return new PackedAllocation(pageAllocation.arena, pageAllocation.offset, bytes, pending.quadCount);
	}

	private static void disableSodiumUploadsAfterError(final Throwable throwable) {
		disabledAfterError = true;
		// Never leave older packed copies addressable after a newer accepted
		// Sodium build failed to upload. Future draws must use Sodium's current
		// compact arena rather than stale terrain from the failed replacement.
		clearSodiumSections();
		MeshShadersWithArcClient.LOGGER.error(
			"Disabling new packed Sodium terrain uploads after an arena/upload failure; Sodium terrain remains available",
			throwable
		);
	}

	public static void stage(final CompiledSectionMesh mesh, final SectionCompiler.Results results) {
		if (disabledAfterError || !ArcMeshConfig.vulkanMeshFeatureEnabled() || !ArcMeshConfig.packedGeometryEnabled()) {
			return;
		}

		EnumMap<ChunkSectionLayer, PendingGeometry> staged = new EnumMap<>(ChunkSectionLayer.class);
		for (Map.Entry<ChunkSectionLayer, MeshData> entry : results.renderedLayers.entrySet()) {
			ChunkSectionLayer layer = entry.getKey();
			MeshData source = entry.getValue();
			MeshData.DrawState state = source.drawState();
			if (state.format() != layer.vertexFormat()
				|| state.primitiveTopology() != PrimitiveTopology.QUADS
				|| state.vertexCount() == 0
				|| state.vertexCount() % 4 != 0) {
				continue;
			}

			try {
				PackedVertexEncoder.EncodedGeometry encoded = PackedVertexEncoder.encode(source.vertexBuffer());
				staged.put(layer, new PendingGeometry(encoded.data(), encoded.quadCount()));
			} catch (RuntimeException exception) {
				MeshShadersWithArcClient.LOGGER.debug("Skipping terrain geometry that could not be packed", exception);
			}
		}

		if (staged.isEmpty()) {
			return;
		}
		synchronized (PackedGeometryManager.class) {
			EnumMap<ChunkSectionLayer, PendingGeometry> replaced = PENDING.put(mesh, staged);
			if (replaced != null) {
				replaced.values().forEach(PendingGeometry::close);
			}
		}
	}

	public static synchronized void completeUpload(
		final SectionMesh mesh,
		final ChunkSectionLayer layer,
		final SectionRenderDispatcher.@Nullable RenderSectionBufferSlice sourceSlice
	) {
		PendingGeometry pending = removePending(mesh, layer);
		if (pending == null) {
			return;
		}

		try (pending) {
			if (disabledAfterError
				|| sourceSlice == null
				|| !ArcMeshConfig.vulkanMeshFeatureEnabled()
				|| !isActiveVulkanMeshDevice()) {
				return;
			}

			long bytes = pending.data.remaining();
			PageAllocation pageAllocation = allocate(bytes);
			if (pageAllocation == null) {
				if (!arenaLimitAnnounced) {
					arenaLimitAnnounced = true;
					MeshShadersWithArcClient.LOGGER.warn(
						"Packed terrain arena reached its {} MiB configured GPU-memory limit",
						maxArenaBytes() / (1024L * 1024L)
					);
				}
				return;
			}

			try {
				RenderSystem.getDevice().createCommandEncoder().writeToBuffer(
					pageAllocation.arena.buffer().slice(pageAllocation.offset, bytes),
					pending.data.duplicate()
				);
			} catch (Throwable throwable) {
				pageAllocation.arena.discard(pageAllocation.offset, bytes);
				throw throwable;
			}

			PackedAllocation allocation = new PackedAllocation(
				pageAllocation.arena,
				pageAllocation.offset,
				bytes,
				pending.quadCount
			);

			EnumMap<ChunkSectionLayer, PackedAllocation> perLayer = ALLOCATIONS.computeIfAbsent(
				mesh,
				ignored -> new EnumMap<>(ChunkSectionLayer.class)
			);
			PackedAllocation replaced = perLayer.put(layer, allocation);
			if (replaced != null) {
				retire(replaced);
			}
			bindSource(allocation, sourceSlice);
		} catch (Throwable throwable) {
			disabledAfterError = true;
			MeshShadersWithArcClient.LOGGER.error(
				"Disabling new packed terrain uploads after an arena/upload failure; vanilla terrain uploads remain active",
				throwable
			);
		}
	}

	public static synchronized void bindSource(
		final SectionMesh mesh,
		final ChunkSectionLayer layer,
		final SectionRenderDispatcher.@Nullable RenderSectionBufferSlice sourceSlice
	) {
		EnumMap<ChunkSectionLayer, PackedAllocation> perLayer = ALLOCATIONS.get(mesh);
		if (perLayer == null || sourceSlice == null) {
			return;
		}
		PackedAllocation allocation = perLayer.get(layer);
		if (allocation != null) {
			bindSource(allocation, sourceSlice);
		}
	}

	private static void bindSource(
		final PackedAllocation allocation,
		final SectionRenderDispatcher.RenderSectionBufferSlice sourceSlice
	) {
		if (allocation.sourceBuffer != null
			&& (allocation.sourceBuffer != sourceSlice.vertexBuffer() || allocation.sourceOffset != sourceSlice.vertexBufferOffset())) {
			Long2ObjectOpenHashMap<PackedAllocation> oldMappings = SOURCE_LOOKUP.get(allocation.sourceBuffer);
			if (oldMappings != null && oldMappings.get(allocation.sourceOffset) == allocation) {
				oldMappings.remove(allocation.sourceOffset);
				if (oldMappings.isEmpty()) {
					SOURCE_LOOKUP.remove(allocation.sourceBuffer);
				}
			}
		}
		allocation.sourceBuffer = sourceSlice.vertexBuffer();
		allocation.sourceOffset = sourceSlice.vertexBufferOffset();
		SOURCE_LOOKUP.computeIfAbsent(sourceSlice.vertexBuffer(), ignored -> new Long2ObjectOpenHashMap<>())
			.put(sourceSlice.vertexBufferOffset(), allocation);
	}

	public static synchronized @Nullable PackedAllocation find(final GpuBuffer sourceBuffer, final long sourceByteOffset) {
		Long2ObjectOpenHashMap<PackedAllocation> allocations = SOURCE_LOOKUP.get(sourceBuffer);
		PackedAllocation allocation = allocations == null ? null : allocations.get(sourceByteOffset);
		if (allocation != null) {
			successfulLookups++;
		}
		return allocation;
	}

	public static synchronized @Nullable SodiumAllocation findSodium(
		final int sectionX,
		final int sectionY,
		final int sectionZ,
		final TerrainRenderPass pass
	) {
		SodiumTerrainLayer layer = SodiumTerrainLayer.from(pass);
		if (layer == null) {
			return null;
		}
		EnumMap<SodiumTerrainLayer, SodiumAllocation> perLayer = SODIUM_ALLOCATIONS.get(
			new SodiumSectionKey(sectionX, sectionY, sectionZ)
		);
		SodiumAllocation allocation = perLayer == null ? null : perLayer.get(layer);
		if (allocation != null) {
			successfulLookups++;
		}
		return allocation;
	}

	public static synchronized @Nullable SodiumAllocation findSodium(
		final RenderSection section,
		final TerrainRenderPass pass
	) {
		return findSodium(section.getChunkX(), section.getChunkY(), section.getChunkZ(), pass);
	}

	/** Returns a stable snapshot of both live and retained allocations for a pass. */
	public static synchronized List<SodiumAllocation> sodiumAllocationsSnapshot(final SodiumTerrainLayer layer) {
		List<SodiumAllocation> snapshot = new ArrayList<>();
		for (EnumMap<SodiumTerrainLayer, SodiumAllocation> perLayer : SODIUM_ALLOCATIONS.values()) {
			SodiumAllocation allocation = perLayer.get(layer);
			if (allocation != null) {
				snapshot.add(allocation);
			}
		}
		return List.copyOf(snapshot);
	}

	/** Returns only allocations whose Sodium RenderSection has been disposed. */
	public static synchronized List<SodiumAllocation> detachedSodiumAllocationsSnapshot(final SodiumTerrainLayer layer) {
		List<SodiumAllocation> snapshot = new ArrayList<>();
		for (EnumMap<SodiumTerrainLayer, SodiumAllocation> perLayer : SODIUM_ALLOCATIONS.values()) {
			SodiumAllocation allocation = perLayer.get(layer);
			if (allocation != null && !allocation.attached) {
				snapshot.add(allocation);
			}
		}
		return List.copyOf(snapshot);
	}

	/**
	 * Marks geometry as no longer owned by a live Sodium section. With the
	 * normal keep distance it is released immediately; larger values retain it
	 * by immutable section coordinates so it survives Sodium object disposal.
	 */
	public static synchronized void detachSodiumSection(final RenderSection section) {
		SodiumSectionKey key = new SodiumSectionKey(section.getChunkX(), section.getChunkY(), section.getChunkZ());
		EnumMap<SodiumTerrainLayer, SodiumAllocation> perLayer = SODIUM_ALLOCATIONS.get(key);
		if (perLayer == null) {
			return;
		}

		if (ArcMeshConfig.keepDistanceChunks() <= 32) {
			removeSodiumSection(key);
			return;
		}

		perLayer.values().forEach(allocation -> allocation.attached = false);
	}

	/**
	 * Evicts detached geometry outside the configured horizontal square. A
	 * four-chunk hysteresis matches Nvidium's broad keep-distance behaviour and
	 * avoids allocation churn at the boundary. The 257 sentinel means keep all.
	 */
	public static synchronized int pruneDetachedSodiumSections(final int cameraSectionX, final int cameraSectionZ) {
		int keepDistance = ArcMeshConfig.keepDistanceChunks();
		if (keepDistance >= 257) {
			return 0;
		}

		int removed = 0;
		int threshold = keepDistance <= 32 ? 0 : keepDistance + 4;
		for (Iterator<Map.Entry<SodiumSectionKey, EnumMap<SodiumTerrainLayer, SodiumAllocation>>> iterator = SODIUM_ALLOCATIONS.entrySet().iterator(); iterator.hasNext();) {
			Map.Entry<SodiumSectionKey, EnumMap<SodiumTerrainLayer, SodiumAllocation>> entry = iterator.next();
			boolean detached = entry.getValue().values().stream().noneMatch(SodiumAllocation::attached);
			if (!detached) {
				continue;
			}

			SodiumSectionKey key = entry.getKey();
			if (threshold == 0
				|| Math.abs(key.x - cameraSectionX) > threshold
				|| Math.abs(key.z - cameraSectionZ) > threshold) {
				entry.getValue().values().forEach(allocation -> retire(allocation.allocation));
				iterator.remove();
				removed++;
			}
		}

		if (removed != 0) {
			ArcOcclusionInvalidation.terrainGeometryChanged();
		}
		return removed;
	}

	public static synchronized void releaseSodiumSection(final int sectionX, final int sectionY, final int sectionZ) {
		removeSodiumSection(new SodiumSectionKey(sectionX, sectionY, sectionZ));
	}

	public static synchronized void clearSodiumSections() {
		if (SODIUM_ALLOCATIONS.isEmpty()) {
			return;
		}
		SODIUM_ALLOCATIONS.values().forEach(perLayer ->
			perLayer.values().forEach(allocation -> retire(allocation.allocation))
		);
		SODIUM_ALLOCATIONS.clear();
		ArcOcclusionInvalidation.terrainGeometryChanged();
	}

	/**
	 * Renderer reload is the synchronization point for Disable and GPU-memory
	 * changes from Sodium's options screen. Recreate the arena so its immutable
	 * sparse physical budget is read from the newly applied settings.
	 */
	public static synchronized void closeForSodiumRendererReload() {
		if (!ARENAS.isEmpty()) {
			GpuDeviceBackend backend = ((GpuDeviceAccessor)(Object)RenderSystem.getDevice()).meshShadersWithArc$getBackend();
			if (backend instanceof VulkanDevice device) {
				device.createCommandEncoder().submit();
				device.graphicsQueue().waitIdle();
			}
		}
		close();
		ArcOcclusionInvalidation.terrainGeometryChanged();
	}

	private static void removeSodiumLayer(final SodiumSectionKey key, final SodiumTerrainLayer layer) {
		EnumMap<SodiumTerrainLayer, SodiumAllocation> perLayer = SODIUM_ALLOCATIONS.get(key);
		if (perLayer == null) {
			return;
		}
		SodiumAllocation removed = perLayer.remove(layer);
		if (removed != null) {
			retire(removed.allocation);
		}
		if (perLayer.isEmpty()) {
			SODIUM_ALLOCATIONS.remove(key);
		}
	}

	private static void removeSodiumSection(final SodiumSectionKey key) {
		EnumMap<SodiumTerrainLayer, SodiumAllocation> removed = SODIUM_ALLOCATIONS.remove(key);
		if (removed != null) {
			removed.values().forEach(allocation -> retire(allocation.allocation));
			ArcOcclusionInvalidation.terrainGeometryChanged();
		}
	}

	public static synchronized long successfulLookupCount() {
		return successfulLookups;
	}

	public static synchronized long successfulSodiumUploadCount() {
		return successfulSodiumUploads;
	}

	public static synchronized boolean sparseArenaActive() {
		return ARENAS.stream().anyMatch(arena -> arena instanceof SparseGeometryArena);
	}

	public static synchronized long sparseResidentBytes() {
		return ARENAS.stream()
			.filter(arena -> arena instanceof SparseGeometryArena)
			.mapToLong(arena -> ((SparseGeometryArena)arena).residentBytes())
			.sum();
	}

	public static synchronized void release(final SectionMesh mesh) {
		EnumMap<ChunkSectionLayer, PendingGeometry> pending = PENDING.remove(mesh);
		if (pending != null) {
			pending.values().forEach(PendingGeometry::close);
		}

		EnumMap<ChunkSectionLayer, PackedAllocation> allocations = ALLOCATIONS.remove(mesh);
		if (allocations != null) {
			allocations.values().forEach(PackedGeometryManager::retire);
		}
	}

	public static synchronized void close() {
		PENDING.values().forEach(perLayer -> perLayer.values().forEach(PendingGeometry::close));
		PENDING.clear();
		ALLOCATIONS.clear();
		SOURCE_LOOKUP.clear();
		SODIUM_ALLOCATIONS.clear();
		ARENAS.forEach(GeometryArena::close);
		ARENAS.clear();
		allocatedPageBytes = 0L;
		arenaLimitAnnounced = false;
		sparseInitializationFailed = false;
		disabledAfterError = false;
		successfulLookups = 0L;
		successfulSodiumUploads = 0L;
	}

	private static @Nullable PendingGeometry removePending(final SectionMesh mesh, final ChunkSectionLayer layer) {
		EnumMap<ChunkSectionLayer, PendingGeometry> perLayer = PENDING.get(mesh);
		if (perLayer == null) {
			return null;
		}
		PendingGeometry pending = perLayer.remove(layer);
		if (perLayer.isEmpty()) {
			PENDING.remove(mesh);
		}
		return pending;
	}

	private static @Nullable PageAllocation allocate(final long requestedBytes) {
		long bytes = align(requestedBytes, ALLOCATION_ALIGNMENT);
		for (GeometryArena arena : ARENAS) {
			long offset = arena.allocate(bytes);
			if (offset >= 0L) {
				return new PageAllocation(arena, offset);
			}
		}

		boolean hasRetired = ARENAS.stream().anyMatch(GeometryArena::hasRetired);
		if (hasRetired) {
			GpuDeviceBackend backend = ((GpuDeviceAccessor)(Object)RenderSystem.getDevice()).meshShadersWithArc$getBackend();
			if (backend instanceof VulkanDevice device) {
				device.createCommandEncoder().submit();
				device.graphicsQueue().waitIdle();
				ARENAS.forEach(GeometryArena::reclaimRetired);
				for (GeometryArena arena : ARENAS) {
					long offset = arena.allocate(bytes);
					if (offset >= 0L) {
						return new PageAllocation(arena, offset);
					}
				}
			}
		}

		GpuDeviceBackend backend = ((GpuDeviceAccessor)(Object)RenderSystem.getDevice()).meshShadersWithArc$getBackend();
		if (!sparseInitializationFailed
			&& ArcMeshConfig.vulkanSparseResidencyEnabled()
			&& ARENAS.stream().noneMatch(arena -> arena instanceof SparseGeometryArena)
			&& backend instanceof VulkanDevice device) {
			GeometryArena sparse = null;
			try {
				sparse = new SparseGeometryArena(device);
				long offset = sparse.allocate(bytes);
				if (offset >= 0L) {
					ARENAS.add(0, sparse);
					return new PageAllocation(sparse, offset);
				}
				sparse.close();
			} catch (Throwable throwable) {
				if (sparse != null) {
					try {
						sparse.close();
					} catch (Throwable closeFailure) {
						throwable.addSuppressed(closeFailure);
					}
				}
				sparseInitializationFailed = true;
				MeshShadersWithArcClient.LOGGER.warn("Sparse packed terrain is unavailable; using dense arena pages", throwable);
			}
		}

		long pageBytes = Math.max(PAGE_BYTES, align(bytes, PAGE_BYTES));
		if (committedArenaBytes() + pageBytes > maxArenaBytes()) {
			return null;
		}
		ArenaPage page = new ArenaPage(pageBytes, ARENAS.size());
		ARENAS.add(page);
		allocatedPageBytes += pageBytes;
		return new PageAllocation(page, page.allocate(bytes));
	}

	private static long maxArenaBytes() {
		GpuDeviceBackend backend = ((GpuDeviceAccessor)(Object)RenderSystem.getDevice()).meshShadersWithArc$getBackend();
		int mebibytes = backend instanceof VulkanDevice device
			? ArcMeshConfig.resolveMaxGpuMemoryMiB(device)
			: ArcMeshConfig.maxGpuMemoryMiB();
		return Math.multiplyExact((long)mebibytes, 1024L * 1024L);
	}

	private static long committedArenaBytes() {
		long sparseBytes = ARENAS.stream()
			.filter(arena -> arena instanceof SparseGeometryArena)
			.mapToLong(arena -> ((SparseGeometryArena)arena).residentBytes())
			.sum();
		return Math.addExact(allocatedPageBytes, sparseBytes);
	}

	private static void retire(final PackedAllocation allocation) {
		for (Iterator<Map.Entry<GpuBuffer, Long2ObjectOpenHashMap<PackedAllocation>>> iterator = SOURCE_LOOKUP.entrySet().iterator(); iterator.hasNext();) {
			Long2ObjectOpenHashMap<PackedAllocation> mapped = iterator.next().getValue();
			mapped.values().removeIf(candidate -> candidate == allocation);
			if (mapped.isEmpty()) {
				iterator.remove();
			}
		}
		allocation.arena.retire(allocation.offset, allocation.bytes);
	}

	private static long align(final long value, final long alignment) {
		return Math.addExact(value, alignment - 1L) / alignment * alignment;
	}

	private static boolean isActiveVulkanMeshDevice() {
		GpuDeviceBackend backend = ((GpuDeviceAccessor)(Object)RenderSystem.getDevice()).meshShadersWithArc$getBackend();
		return backend instanceof VulkanDevice device && device.vkDevice().getCapabilities().VK_EXT_mesh_shader;
	}

	public enum SodiumTerrainLayer {
		SOLID(DefaultTerrainRenderPasses.SOLID),
		CUTOUT(DefaultTerrainRenderPasses.CUTOUT),
		TRANSLUCENT(DefaultTerrainRenderPasses.TRANSLUCENT);

		private final TerrainRenderPass pass;

		SodiumTerrainLayer(final TerrainRenderPass pass) {
			this.pass = pass;
		}

		public TerrainRenderPass pass() {
			return this.pass;
		}

		public static @Nullable SodiumTerrainLayer from(final TerrainRenderPass pass) {
			for (SodiumTerrainLayer layer : values()) {
				if (layer.pass == pass) {
					return layer;
				}
			}
			return null;
		}
	}

	/** Immutable render metadata plus a mutable live/retained lifetime marker. */
	public static final class SodiumAllocation {
		private final SodiumSectionKey key;
		private final SodiumTerrainLayer layer;
		private final PackedAllocation allocation;
		private final int[] vertexSegments;
		private boolean attached = true;

		private SodiumAllocation(
			final SodiumSectionKey key,
			final SodiumTerrainLayer layer,
			final PackedAllocation allocation,
			final int[] vertexSegments
		) {
			this.key = key;
			this.layer = layer;
			this.allocation = allocation;
			this.vertexSegments = vertexSegments;
		}

		public GpuBuffer buffer() {
			return this.allocation.buffer();
		}

		public int firstVertex() {
			return this.allocation.firstVertex();
		}

		public int quadCount() {
			return this.allocation.quadCount();
		}

		public int sectionX() {
			return this.key.x;
		}

		public int sectionY() {
			return this.key.y;
		}

		public int sectionZ() {
			return this.key.z;
		}

		public SodiumTerrainLayer layer() {
			return this.layer;
		}

		public boolean attached() {
			return this.attached;
		}

		public int segmentCapacity() {
			return this.vertexSegments.length / 2;
		}

		public int segmentVertexCount(final int segment) {
			return this.vertexSegments[Math.multiplyExact(segment, 2)];
		}

		public int segmentFacing(final int segment) {
			return this.vertexSegments[Math.multiplyExact(segment, 2) + 1];
		}

		public int segmentFirstVertex(final int segment) {
			int first = this.firstVertex();
			for (int index = 0; index < segment; index++) {
				first = Math.addExact(first, this.segmentVertexCount(index));
			}
			return first;
		}
	}

	private record SodiumSectionKey(int x, int y, int z) {
	}

	public static final class PackedAllocation {
		private final GeometryArena arena;
		private final long offset;
		private final long bytes;
		private final int quadCount;
		private @Nullable GpuBuffer sourceBuffer;
		private long sourceOffset;

		private PackedAllocation(
			final GeometryArena arena,
			final long offset,
			final long bytes,
			final int quadCount
		) {
			this.arena = arena;
			this.offset = offset;
			this.bytes = bytes;
			this.quadCount = quadCount;
		}

		public GpuBuffer buffer() {
			return this.arena.buffer();
		}

		public int firstVertex() {
			return Math.toIntExact(this.offset / PackedVertexEncoder.PACKED_VERTEX_BYTES);
		}

		public int quadCount() {
			return this.quadCount;
		}

	}

	private static final class ArenaPage implements GeometryArena {
		private final GpuBuffer buffer;
		private final TreeMap<Long, Long> free = new TreeMap<>();
		private final List<RetiredRange> retired = new ArrayList<>();

		private ArenaPage(final long bytes, final int index) {
			this.buffer = RenderSystem.getDevice().createBuffer(
				() -> "Arc packed terrain arena page " + index,
				GpuBuffer.USAGE_COPY_DST | ArcMeshTerrainRenderer.USAGE_STORAGE,
				bytes
			);
			this.free.put(0L, bytes);
		}

		@Override
		public GpuBuffer buffer() {
			return this.buffer;
		}

		@Override
		public long allocate(final long bytes) {
			for (Iterator<Map.Entry<Long, Long>> iterator = this.free.entrySet().iterator(); iterator.hasNext();) {
				Map.Entry<Long, Long> range = iterator.next();
				if (range.getValue() >= bytes) {
					long offset = range.getKey();
					long remaining = range.getValue() - bytes;
					iterator.remove();
					if (remaining > 0L) {
						this.free.put(offset + bytes, remaining);
					}
					return offset;
				}
			}
			return -1L;
		}

		@Override
		public void retire(final long offset, final long bytes) {
			this.retired.add(new RetiredRange(offset, align(bytes, ALLOCATION_ALIGNMENT)));
		}

		@Override
		public boolean hasRetired() {
			return !this.retired.isEmpty();
		}

		@Override
		public void reclaimRetired() {
			this.retired.forEach(range -> freeImmediately(range.offset, range.bytes));
			this.retired.clear();
		}

		private void freeImmediately(final long offset, final long bytes) {
			long start = offset;
			long size = align(bytes, ALLOCATION_ALIGNMENT);
			Map.Entry<Long, Long> lower = this.free.floorEntry(start);
			if (lower != null && lower.getKey() + lower.getValue() == start) {
				start = lower.getKey();
				size += lower.getValue();
				this.free.remove(lower.getKey());
			}
			Map.Entry<Long, Long> higher = this.free.ceilingEntry(start);
			if (higher != null && start + size == higher.getKey()) {
				size += higher.getValue();
				this.free.remove(higher.getKey());
			}
			this.free.put(start, size);
		}

		@Override
		public void close() {
			this.buffer.close();
		}

		@Override
		public String kind() {
			return "dense";
		}
	}

	private static final class PendingGeometry implements AutoCloseable {
		private final ByteBuffer data;
		private final int quadCount;
		private boolean closed;

		private PendingGeometry(final ByteBuffer data, final int quadCount) {
			this.data = data;
			this.quadCount = quadCount;
		}

		@Override
		public void close() {
			if (!this.closed) {
				this.closed = true;
				MemoryUtil.memFree(this.data);
			}
		}
	}

	private record PageAllocation(GeometryArena arena, long offset) {
	}

	private record RetiredRange(long offset, long bytes) {
	}
}
