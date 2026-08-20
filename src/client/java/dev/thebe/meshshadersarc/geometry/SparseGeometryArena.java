package dev.thebe.meshshadersarc.geometry;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import dev.thebe.meshshadersarc.ArcMeshConfig;
import dev.thebe.meshshadersarc.MeshShadersWithArcClient;
import dev.thebe.meshshadersarc.render.ArcMeshTerrainRenderer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VkBindSparseInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;
import org.lwjgl.vulkan.VkSparseBufferMemoryBindInfo;
import org.lwjgl.vulkan.VkSparseMemoryBind;
import org.lwjgl.vulkan.VkTimelineSemaphoreSubmitInfo;

/**
 * A storage-buffer arena with a stable virtual address range and lazily committed Vulkan pages.
 * All sparse queue operations occur on Minecraft's render thread/graphics queue.
 */
final class SparseGeometryArena implements GeometryArena {
	private static final long DEFAULT_VIRTUAL_BYTES = 1024L * 1024L * 1024L;
	private static final long SLAB_BYTES = 64L * 1024L * 1024L;
	private static final long SUBALLOCATION_ALIGNMENT = PackedVertexEncoder.PACKED_VERTEX_BYTES * 4L;

	private final VulkanDevice device;
	private final ArcSparseGpuBuffer buffer;
	private final long pageBytes;
	private final long physicalBudget;
	private final int memoryTypeIndex;
	private final long timelineSemaphore;
	private final TreeMap<Long, Long> freeVirtualRanges = new TreeMap<>();
	private final Map<Long, ResidentPage> residentPages = new HashMap<>();
	private final List<RetiredAllocation> retired = new ArrayList<>();
	private final List<MemorySlab> slabs = new ArrayList<>();
	private long allocatedPhysicalBytes;
	private long timelineValue;
	private boolean closed;

	SparseGeometryArena(final VulkanDevice device) {
		this.device = device;
		long vkBuffer = 0L;
		long semaphore = 0L;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
			VK12.vkGetPhysicalDeviceProperties(device.vkDevice().getPhysicalDevice(), properties);
			long configuredPhysical = (long)ArcMeshConfig.resolveMaxGpuMemoryMiB(device) * 1024L * 1024L;
			long defaultVirtual = Math.max(DEFAULT_VIRTUAL_BYTES, configuredPhysical);
			long configuredVirtual = propertyMib("meshShadersWithArc.sparseVirtualMiB", defaultVirtual);
			long maxStorageRange = Integer.toUnsignedLong(properties.limits().maxStorageBufferRange());
			long requestedBytes = Math.min(configuredVirtual, maxStorageRange);

			VkBufferCreateInfo createInfo = VkBufferCreateInfo.calloc(stack)
				.sType$Default()
				.flags(VK12.VK_BUFFER_CREATE_SPARSE_BINDING_BIT | VK12.VK_BUFFER_CREATE_SPARSE_RESIDENCY_BIT)
				.size(requestedBytes)
				.usage(VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT)
				.sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE);
			LongBuffer bufferHandle = stack.callocLong(1);
			VulkanUtils.crashIfFailure(device, VK12.vkCreateBuffer(device.vkDevice(), createInfo, null, bufferHandle), "Couldn't create sparse terrain buffer");
			vkBuffer = bufferHandle.get(0);

			VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
			VK12.vkGetBufferMemoryRequirements(device.vkDevice(), vkBuffer, requirements);
			this.pageBytes = requirements.alignment();
			long usableBytes = alignDown(requestedBytes, this.pageBytes);
			if (usableBytes < this.pageBytes) {
				throw new IllegalStateException("Sparse terrain buffer is smaller than one residency page");
			}
			this.memoryTypeIndex = findDeviceLocalMemoryType(device, requirements.memoryTypeBits(), stack);
			this.physicalBudget = Math.min(
				usableBytes,
				Math.max(this.pageBytes, alignDown(configuredPhysical, this.pageBytes))
			);

			VkSemaphoreTypeCreateInfo typeInfo = VkSemaphoreTypeCreateInfo.calloc(stack)
				.sType$Default()
				.semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE)
				.initialValue(0L);
			VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(typeInfo);
			LongBuffer semaphoreHandle = stack.callocLong(1);
			VulkanUtils.crashIfFailure(device, VK12.vkCreateSemaphore(device.vkDevice(), semaphoreInfo, null, semaphoreHandle), "Couldn't create sparse residency timeline");
			semaphore = semaphoreHandle.get(0);

			this.buffer = new ArcSparseGpuBuffer(
				vkBuffer,
				GpuBuffer.USAGE_COPY_DST | ArcMeshTerrainRenderer.USAGE_STORAGE,
				usableBytes
			);
			this.timelineSemaphore = semaphore;
			this.freeVirtualRanges.put(0L, usableBytes);
			device.instance().debug().setObjectName(device.vkDevice(), VK12.VK_OBJECT_TYPE_BUFFER, vkBuffer, () -> "Arc sparse packed terrain arena");
			MeshShadersWithArcClient.LOGGER.info(
				"Created sparse packed terrain arena: {} MiB virtual, {} MiB physical budget, {} KiB residency pages",
				usableBytes / (1024L * 1024L),
				this.physicalBudget / (1024L * 1024L),
				this.pageBytes / 1024L
			);
		} catch (Throwable throwable) {
			if (semaphore != 0L) {
				VK12.vkDestroySemaphore(device.vkDevice(), semaphore, null);
			}
			if (vkBuffer != 0L) {
				VK12.vkDestroyBuffer(device.vkDevice(), vkBuffer, null);
			}
			throw throwable;
		}
	}

	@Override
	public GpuBuffer buffer() {
		return this.buffer;
	}

	@Override
	public long allocate(final long requestedBytes) {
		this.requireOpen();
		long bytes = align(requestedBytes, SUBALLOCATION_ALIGNMENT);
		long offset = this.allocateVirtual(bytes);
		if (offset < 0L) {
			return -1L;
		}

		long firstPage = offset / this.pageBytes;
		long lastPage = (offset + bytes - 1L) / this.pageBytes;
		List<BindOperation> newBindings = new ArrayList<>();
		try {
			for (long page = firstPage; page <= lastPage; page++) {
				ResidentPage resident = this.residentPages.get(page);
				if (resident == null) {
					PhysicalSlot slot = this.allocatePhysicalSlot();
					if (slot == null) {
						throw new ArenaFullException();
					}
					resident = new ResidentPage(slot);
					this.residentPages.put(page, resident);
					newBindings.add(new BindOperation(page, slot));
				}
				resident.references++;
			}

			if (!newBindings.isEmpty()) {
				this.submitBindings(newBindings, false);
			}
			return offset;
		} catch (Throwable throwable) {
			if (!(throwable instanceof UnsafeSparseRollbackException)) {
				for (long page = firstPage; page <= lastPage; page++) {
					ResidentPage resident = this.residentPages.get(page);
					if (resident != null && resident.references > 0) {
						resident.references--;
					}
				}
				for (BindOperation operation : newBindings) {
					ResidentPage removed = this.residentPages.remove(operation.virtualPage);
					if (removed != null) {
						removed.slot.slab.freeSlots.addLast(removed.slot.index);
					}
				}
				this.freeVirtual(offset, bytes);
			}
			if (throwable instanceof ArenaFullException) {
				return -1L;
			}
			throw throwable;
		}
	}

	@Override
	public void retire(final long offset, final long bytes) {
		this.retired.add(new RetiredAllocation(offset, align(bytes, SUBALLOCATION_ALIGNMENT)));
	}

	@Override
	public boolean hasRetired() {
		return !this.retired.isEmpty();
	}

	@Override
	public void reclaimRetired() {
		if (this.retired.isEmpty()) {
			return;
		}
		List<Long> pagesToUnbind = new ArrayList<>();
		for (RetiredAllocation allocation : this.retired) {
			long firstPage = allocation.offset / this.pageBytes;
			long lastPage = (allocation.offset + allocation.bytes - 1L) / this.pageBytes;
			for (long page = firstPage; page <= lastPage; page++) {
				ResidentPage resident = this.residentPages.get(page);
				if (resident != null && --resident.references == 0) {
					pagesToUnbind.add(page);
				}
			}
			this.freeVirtual(allocation.offset, allocation.bytes);
		}
		this.retired.clear();

		if (!pagesToUnbind.isEmpty()) {
			List<BindOperation> unbinds = pagesToUnbind.stream().map(page -> new BindOperation(page, null)).toList();
			this.submitBindings(unbinds, true);
			for (long page : pagesToUnbind) {
				ResidentPage resident = this.residentPages.remove(page);
				resident.slot.slab.freeSlots.addLast(resident.slot.index);
			}
		}
	}

	@Override
	public String kind() {
		return "sparse";
	}

	long residentBytes() {
		return Math.multiplyExact((long)this.residentPages.size(), this.pageBytes);
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		this.device.graphicsQueue().waitIdle();
		this.buffer.close();
		VK12.vkDestroyBuffer(this.device.vkDevice(), this.buffer.vkBuffer(), null);
		for (MemorySlab slab : this.slabs) {
			VK12.vkFreeMemory(this.device.vkDevice(), slab.memory, null);
		}
		VK12.vkDestroySemaphore(this.device.vkDevice(), this.timelineSemaphore, null);
		this.slabs.clear();
		this.residentPages.clear();
		this.retired.clear();
		this.freeVirtualRanges.clear();
	}

	private long allocateVirtual(final long bytes) {
		for (Iterator<Map.Entry<Long, Long>> iterator = this.freeVirtualRanges.entrySet().iterator(); iterator.hasNext();) {
			Map.Entry<Long, Long> range = iterator.next();
			if (range.getValue() >= bytes) {
				long offset = range.getKey();
				long remaining = range.getValue() - bytes;
				iterator.remove();
				if (remaining > 0L) {
					this.freeVirtualRanges.put(offset + bytes, remaining);
				}
				return offset;
			}
		}
		return -1L;
	}

	private void freeVirtual(final long offset, final long bytes) {
		long start = offset;
		long size = bytes;
		Map.Entry<Long, Long> lower = this.freeVirtualRanges.floorEntry(start);
		if (lower != null && lower.getKey() + lower.getValue() == start) {
			start = lower.getKey();
			size += lower.getValue();
			this.freeVirtualRanges.remove(lower.getKey());
		}
		Map.Entry<Long, Long> higher = this.freeVirtualRanges.ceilingEntry(start);
		if (higher != null && start + size == higher.getKey()) {
			size += higher.getValue();
			this.freeVirtualRanges.remove(higher.getKey());
		}
		this.freeVirtualRanges.put(start, size);
	}

	private PhysicalSlot allocatePhysicalSlot() {
		for (MemorySlab slab : this.slabs) {
			Integer slot = slab.freeSlots.pollFirst();
			if (slot != null) {
				return new PhysicalSlot(slab, slot);
			}
		}

		long remainingBudget = this.physicalBudget - this.allocatedPhysicalBytes;
		long slabBytes = Math.min(alignDown(SLAB_BYTES, this.pageBytes), alignDown(remainingBudget, this.pageBytes));
		if (slabBytes < this.pageBytes) {
			return null;
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkMemoryAllocateInfo allocationInfo = VkMemoryAllocateInfo.calloc(stack)
				.sType$Default()
				.allocationSize(slabBytes)
				.memoryTypeIndex(this.memoryTypeIndex);
			LongBuffer handle = stack.callocLong(1);
			VulkanUtils.crashIfFailure(this.device, VK12.vkAllocateMemory(this.device.vkDevice(), allocationInfo, null, handle), "Couldn't allocate sparse terrain memory");
			MemorySlab slab = new MemorySlab(handle.get(0), Math.toIntExact(slabBytes / this.pageBytes));
			this.slabs.add(slab);
			this.allocatedPhysicalBytes += slabBytes;
			return new PhysicalSlot(slab, slab.freeSlots.removeFirst());
		}
	}

	private void submitBindings(final List<BindOperation> operations, final boolean waitOnHost) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkSparseMemoryBind.Buffer binds = VkSparseMemoryBind.calloc(operations.size(), stack);
			for (int index = 0; index < operations.size(); index++) {
				BindOperation operation = operations.get(index);
				VkSparseMemoryBind bind = binds.get(index)
					.resourceOffset(operation.virtualPage * this.pageBytes)
					.size(this.pageBytes)
					.flags(0);
				if (operation.slot != null) {
					bind.memory(operation.slot.slab.memory)
						.memoryOffset((long)operation.slot.index * this.pageBytes);
				} else {
					bind.memory(VK12.VK_NULL_HANDLE).memoryOffset(0L);
				}
			}

			VkSparseBufferMemoryBindInfo.Buffer bufferBind = VkSparseBufferMemoryBindInfo.calloc(1, stack)
				.buffer(this.buffer.vkBuffer())
				.pBinds(binds);
			long value = ++this.timelineValue;
			VkTimelineSemaphoreSubmitInfo timeline = VkTimelineSemaphoreSubmitInfo.calloc(stack)
				.sType$Default()
				.pSignalSemaphoreValues(stack.longs(value));
			VkBindSparseInfo.Buffer bindInfo = VkBindSparseInfo.calloc(1, stack)
				.sType$Default()
				.pNext(timeline)
				.pBufferBinds(bufferBind)
				.pSignalSemaphores(stack.longs(this.timelineSemaphore));
			VulkanUtils.crashIfFailure(
				this.device,
				VK12.vkQueueBindSparse(this.device.graphicsQueue().vkQueue(), bindInfo, VK12.VK_NULL_HANDLE),
				"Couldn't update sparse terrain residency"
			);

			if (waitOnHost) {
				this.waitForTimeline(value);
			} else {
				try {
					((VulkanCommandEncoder)this.device.createCommandEncoder()).waitSemaphore(
						this.timelineSemaphore,
						value,
						KHRSynchronization2.VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR
					);
				} catch (Throwable waitRegistrationFailure) {
					// The sparse bind is already queued. Complete it, then undo every
					// newly-bound page before allocate() returns its physical slots to
					// the free list; otherwise a later allocation could alias memory.
					try {
						this.waitForTimeline(value);
						List<BindOperation> recoveryUnbinds = operations.stream()
							.filter(operation -> operation.slot != null)
							.map(operation -> new BindOperation(operation.virtualPage, null))
							.toList();
						if (!recoveryUnbinds.isEmpty()) {
							this.submitBindings(recoveryUnbinds, true);
						}
					} catch (Throwable recoveryFailure) {
						throw new UnsafeSparseRollbackException(waitRegistrationFailure, recoveryFailure);
					}
					throw waitRegistrationFailure;
				}
			}
		}
	}

	private void waitForTimeline(final long value) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkSemaphoreWaitInfo waitInfo = VkSemaphoreWaitInfo.calloc(stack)
				.sType$Default()
				.pSemaphores(stack.longs(this.timelineSemaphore))
				.pValues(stack.longs(value));
			VulkanUtils.crashIfFailure(
				this.device,
				VK12.vkWaitSemaphores(this.device.vkDevice(), waitInfo, Long.MAX_VALUE),
				"Couldn't wait for sparse terrain residency"
			);
		}
	}

	private static int findDeviceLocalMemoryType(final VulkanDevice device, final int compatibleBits, final MemoryStack stack) {
		VkPhysicalDeviceMemoryProperties properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
		VK12.vkGetPhysicalDeviceMemoryProperties(device.vkDevice().getPhysicalDevice(), properties);
		for (int index = 0; index < properties.memoryTypeCount(); index++) {
			if ((compatibleBits & 1 << index) != 0
				&& (properties.memoryTypes(index).propertyFlags() & VK12.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) {
				return index;
			}
		}
		throw new IllegalStateException("No device-local memory type supports the sparse terrain buffer");
	}

	private static long propertyMib(final String name, final long defaultBytes) {
		String value = System.getProperty(name);
		if (value == null) {
			return defaultBytes;
		}
		try {
			return Math.multiplyExact(Long.parseLong(value), 1024L * 1024L);
		} catch (NumberFormatException | ArithmeticException exception) {
			MeshShadersWithArcClient.LOGGER.warn("Ignoring invalid {} value '{}'", name, value);
			return defaultBytes;
		}
	}

	private static long align(final long value, final long alignment) {
		return Math.addExact(value, alignment - 1L) / alignment * alignment;
	}

	private static long alignDown(final long value, final long alignment) {
		return value / alignment * alignment;
	}

	private void requireOpen() {
		if (this.closed) {
			throw new IllegalStateException("Sparse terrain arena is closed");
		}
	}

	private static final class MemorySlab {
		private final long memory;
		private final ArrayDeque<Integer> freeSlots = new ArrayDeque<>();

		private MemorySlab(final long memory, final int pageCount) {
			this.memory = memory;
			for (int index = 0; index < pageCount; index++) {
				this.freeSlots.addLast(index);
			}
		}
	}

	private record PhysicalSlot(MemorySlab slab, int index) {
	}

	private static final class ResidentPage {
		private final PhysicalSlot slot;
		private int references;

		private ResidentPage(final PhysicalSlot slot) {
			this.slot = slot;
		}
	}

	private record BindOperation(long virtualPage, PhysicalSlot slot) {
	}

	private record RetiredAllocation(long offset, long bytes) {
	}

	private static final class ArenaFullException extends RuntimeException {
	}

	private static final class UnsafeSparseRollbackException extends RuntimeException {
		private UnsafeSparseRollbackException(final Throwable registrationFailure, final Throwable recoveryFailure) {
			super("Sparse residency rollback could not be completed safely", registrationFailure);
			this.addSuppressed(recoveryFailure);
		}
	}
}
