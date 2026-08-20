package dev.thebe.meshshadersarc.render;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A conservative global terrain revision used to reject stale temporal depth.
 * Section compilation can complete off the render thread, so this deliberately
 * contains no Vulkan work.
 */
public final class ArcOcclusionInvalidation {
	private static final AtomicLong GEOMETRY_EPOCH = new AtomicLong();

	private ArcOcclusionInvalidation() {
	}

	public static long geometryEpoch() {
		return GEOMETRY_EPOCH.get();
	}

	public static void terrainGeometryChanged() {
		GEOMETRY_EPOCH.incrementAndGet();
	}
}
