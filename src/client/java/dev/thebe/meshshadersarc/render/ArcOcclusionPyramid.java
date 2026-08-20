package dev.thebe.meshshadersarc.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFormatProperties;

/** Double-buffered, previous-frame reversed-Z hierarchy and matching camera snapshot. */
public final class ArcOcclusionPyramid implements AutoCloseable {
	public static final int USAGE_STORAGE_IMAGE = 1 << 10;
	private static final int SNAPSHOT_BYTES = 176;
	private static final int SLOT_COUNT = 2;

	private final VulkanDevice device;
	private final ArcHzbPipeline pipeline;
	private final Slot[] slots = new Slot[SLOT_COUNT];
	private int screenWidth;
	private int screenHeight;
	private int baseWidth;
	private int baseHeight;
	private int mipLevels;
	private int readSlot = -1;
	private @Nullable Object worldIdentity;
	private long buildCount;
	private long invalidationCount;
	private boolean closed;

	private ArcOcclusionPyramid(final VulkanDevice device, final ArcHzbPipeline pipeline) {
		this.device = device;
		this.pipeline = pipeline;
	}

	static ArcOcclusionPyramid create(final VulkanDevice device) {
		requireHzbFormat(device);
		return new ArcOcclusionPyramid(device, ArcHzbPipeline.create(device));
	}

	void ensureSizeAndWorld(final int width, final int height, final @Nullable Object world) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("Invalid HZB source size " + width + "x" + height);
		}

		if (this.screenWidth != width || this.screenHeight != height || this.slots[0] == null) {
			closeSlots();
			this.screenWidth = width;
			this.screenHeight = height;
			this.baseWidth = nextPowerOfTwo((width + 1) / 2);
			this.baseHeight = nextPowerOfTwo((height + 1) / 2);
			this.mipLevels = 1 + Integer.numberOfTrailingZeros(Integer.highestOneBit(Math.max(this.baseWidth, this.baseHeight)));
			for (int i = 0; i < SLOT_COUNT; i++) {
				this.slots[i] = Slot.create(i, this.baseWidth, this.baseHeight, this.mipLevels);
			}
			this.readSlot = -1;
		}

		if (!Objects.equals(this.worldIdentity, world)) {
			this.worldIdentity = world;
			invalidate();
		}
	}

	@Nullable ReadResources readable(final long geometryEpoch, final @Nullable Object world) {
		if (this.readSlot < 0 || !Objects.equals(this.worldIdentity, world)) {
			return null;
		}

		Slot slot = this.slots[this.readSlot];
		if (!slot.valid || slot.geometryEpoch != geometryEpoch) {
			return null;
		}
		return new ReadResources(slot.sampledView, slot.snapshot);
	}

	void recordBuild(
		final VkCommandBuffer commandBuffer,
		final GpuTextureView sourceDepth,
		final GpuBuffer chunkBuffer,
		final int referenceChunkWordOffset,
		final GpuBufferSlice globals,
		final GpuBufferSlice projection,
		final long geometryEpoch,
		final boolean stableGeometry,
		final @Nullable Object world
	) {
		int writeSlot = this.readSlot < 0 ? 0 : 1 - this.readSlot;
		Slot slot = this.slots[writeSlot];
		GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		this.pipeline.recordBuild(
			commandBuffer,
			sourceDepth,
			slot.mipViews,
			nearest,
			slot.snapshot,
			chunkBuffer,
			referenceChunkWordOffset,
			globals,
			projection,
			this.baseWidth,
			this.baseHeight
		);

		this.buildCount++;
		slot.geometryEpoch = geometryEpoch;
		slot.valid = stableGeometry
			&& geometryEpoch == ArcOcclusionInvalidation.geometryEpoch()
			&& Objects.equals(this.worldIdentity, world);
		this.readSlot = writeSlot;
	}

	void invalidate() {
		this.invalidationCount++;
		this.readSlot = -1;
		for (Slot slot : this.slots) {
			if (slot != null) {
				slot.valid = false;
			}
		}
	}

	boolean hasValidRead() {
		return this.readSlot >= 0
			&& this.slots[this.readSlot].valid
			&& this.slots[this.readSlot].geometryEpoch == ArcOcclusionInvalidation.geometryEpoch();
	}

	int mipLevels() {
		return this.mipLevels;
	}

	long buildCount() {
		return this.buildCount;
	}

	long invalidationCount() {
		return this.invalidationCount;
	}

	private static void requireHzbFormat(final VulkanDevice device) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkFormatProperties properties = VkFormatProperties.calloc(stack);
			VK12.vkGetPhysicalDeviceFormatProperties(
				device.vkDevice().getPhysicalDevice(),
				VulkanConst.toVk(GpuFormat.R32_FLOAT),
				properties
			);
			int required = VK12.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK12.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
			if ((properties.optimalTilingFeatures() & required) != required) {
				throw new IllegalStateException("R32_FLOAT cannot be both sampled and storage-backed on this Vulkan device");
			}
		}
	}

	private static int nextPowerOfTwo(final int value) {
		if (value <= 1) {
			return 1;
		}
		if (value > 1 << 30) {
			throw new IllegalArgumentException("HZB dimension is too large: " + value);
		}
		return Integer.highestOneBit(value - 1) << 1;
	}

	private void closeSlots() {
		for (int i = 0; i < this.slots.length; i++) {
			if (this.slots[i] != null) {
				this.slots[i].close();
				this.slots[i] = null;
			}
		}
		this.readSlot = -1;
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		closeSlots();
		this.pipeline.close();
	}

	record ReadResources(GpuTextureView pyramid, GpuBuffer snapshot) {
	}

	private static final class Slot implements AutoCloseable {
		private final GpuTexture texture;
		private final GpuTextureView sampledView;
		private final GpuTextureView[] mipViews;
		private final GpuBuffer snapshot;
		private boolean valid;
		private long geometryEpoch;

		private Slot(
			final GpuTexture texture,
			final GpuTextureView sampledView,
			final GpuTextureView[] mipViews,
			final GpuBuffer snapshot
		) {
			this.texture = texture;
			this.sampledView = sampledView;
			this.mipViews = mipViews;
			this.snapshot = snapshot;
		}

		private static Slot create(final int index, final int width, final int height, final int mipLevels) {
			GpuTexture texture = RenderSystem.getDevice().createTexture(
				() -> "Arc HZB #" + index,
				GpuTexture.USAGE_TEXTURE_BINDING | USAGE_STORAGE_IMAGE,
				GpuFormat.R32_FLOAT,
				width,
				height,
				1,
				mipLevels
			);
			GpuTextureView sampledView = null;
			GpuTextureView[] mipViews = new GpuTextureView[mipLevels];
			GpuBuffer snapshot = null;
			try {
				sampledView = RenderSystem.getDevice().createTextureView(texture);
				for (int mip = 0; mip < mipLevels; mip++) {
					mipViews[mip] = RenderSystem.getDevice().createTextureView(texture, mip, 1);
				}
				snapshot = RenderSystem.getDevice().createBuffer(
					() -> "Arc previous-frame snapshot #" + index,
					ArcMeshTerrainRenderer.USAGE_STORAGE,
					SNAPSHOT_BYTES
				);
				return new Slot(texture, sampledView, mipViews, snapshot);
			} catch (Throwable throwable) {
				if (snapshot != null) {
					snapshot.close();
				}
				for (GpuTextureView view : mipViews) {
					if (view != null) {
						view.close();
					}
				}
				if (sampledView != null) {
					sampledView.close();
				}
				texture.close();
				throw throwable;
			}
		}

		@Override
		public void close() {
			this.snapshot.close();
			for (GpuTextureView view : this.mipViews) {
				view.close();
			}
			this.sampledView.close();
			this.texture.close();
		}
	}
}
