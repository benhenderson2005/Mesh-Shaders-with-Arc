package dev.thebe.meshshadersarc.geometry;

import com.mojang.blaze3d.buffers.GpuBuffer;

interface GeometryArena extends AutoCloseable {
	GpuBuffer buffer();

	long allocate(long bytes);

	void retire(long offset, long bytes);

	boolean hasRetired();

	void reclaimRetired();

	default void discard(final long offset, final long bytes) {
		this.retire(offset, bytes);
	}

	String kind();

	@Override
	void close();
}
