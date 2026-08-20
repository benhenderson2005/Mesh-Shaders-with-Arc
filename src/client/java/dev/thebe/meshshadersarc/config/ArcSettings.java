package dev.thebe.meshshadersarc.config;

public final class ArcSettings {
	public static final int DEFAULT_KEEP_DISTANCE_CHUNKS = 32;
	public static final int MIN_KEEP_DISTANCE_CHUNKS = 32;
	public static final int KEEP_ALL_DISTANCE_CHUNKS = 257;

	public static final int DEFAULT_MAX_GPU_MEMORY_MIB = 2_048;
	public static final int MIN_MAX_GPU_MEMORY_MIB = 512;
	public static final int MAX_MAX_GPU_MEMORY_MIB = 32_768;
	public static final int MAX_GPU_MEMORY_STEP_MIB = 512;

	private int keepDistanceChunks = DEFAULT_KEEP_DISTANCE_CHUNKS;
	private int maxGpuMemoryMiB = DEFAULT_MAX_GPU_MEMORY_MIB;
	private boolean temporalHzbCulling = true;
	private boolean automaticMemory = true;
	private boolean packedGeometry = true;
	private boolean sparseResidency = true;
	private boolean customTranslucency = true;

	public int keepDistanceChunks() {
		return keepDistanceChunks;
	}

	public void setKeepDistanceChunks(final int value) {
		keepDistanceChunks = normalizeKeepDistance(value);
	}

	public int maxGpuMemoryMiB() {
		return maxGpuMemoryMiB;
	}

	public void setMaxGpuMemoryMiB(final int value) {
		maxGpuMemoryMiB = normalizeMaxGpuMemory(value);
	}

	public boolean temporalHzbCulling() {
		return temporalHzbCulling;
	}

	public void setTemporalHzbCulling(final boolean value) {
		temporalHzbCulling = value;
	}

	public boolean automaticMemory() {
		return automaticMemory;
	}

	public void setAutomaticMemory(final boolean value) {
		automaticMemory = value;
	}

	public boolean packedGeometry() {
		return packedGeometry;
	}

	public void setPackedGeometry(final boolean value) {
		packedGeometry = value;
	}

	public boolean sparseResidency() {
		return sparseResidency;
	}

	public void setSparseResidency(final boolean value) {
		sparseResidency = value;
	}

	public boolean customTranslucency() {
		return customTranslucency;
	}

	public void setCustomTranslucency(final boolean value) {
		customTranslucency = value;
	}

	public void normalize() {
		setKeepDistanceChunks(keepDistanceChunks);
		setMaxGpuMemoryMiB(maxGpuMemoryMiB);
	}

	public static int normalizeKeepDistance(final int value) {
		return Math.max(MIN_KEEP_DISTANCE_CHUNKS, Math.min(KEEP_ALL_DISTANCE_CHUNKS, value));
	}

	public static int normalizeMaxGpuMemory(final int value) {
		final int clamped = Math.max(MIN_MAX_GPU_MEMORY_MIB, Math.min(MAX_MAX_GPU_MEMORY_MIB, value));
		return MIN_MAX_GPU_MEMORY_MIB
			+ ((clamped - MIN_MAX_GPU_MEMORY_MIB) / MAX_GPU_MEMORY_STEP_MIB) * MAX_GPU_MEMORY_STEP_MIB;
	}
}
