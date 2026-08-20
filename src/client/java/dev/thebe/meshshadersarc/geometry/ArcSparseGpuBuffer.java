package dev.thebe.meshshadersarc.geometry;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;

/** Lightweight Blaze3D view of a raw sparse VkBuffer owned by {@link SparseGeometryArena}. */
final class ArcSparseGpuBuffer extends VulkanGpuBuffer {
	private boolean closed;

	ArcSparseGpuBuffer(final long vkBuffer, final int usage, final long size) {
		super(vkBuffer, usage, size);
	}

	@Override
	public void destroy() {
		// SparseGeometryArena owns and destroys the raw handle after the queue is idle.
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		this.closed = true;
	}

	@Override
	public GpuBufferSlice.MappedView map(
		final long offset,
		final long length,
		final boolean read,
		final boolean write
	) {
		throw new UnsupportedOperationException("Sparse terrain geometry is device-local and cannot be mapped");
	}
}
