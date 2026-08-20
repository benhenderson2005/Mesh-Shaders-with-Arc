package dev.thebe.meshshadersarc.geometry;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryUtil;

/** Packs terrain vertices into the renderer's 16-byte storage representation. */
final class PackedVertexEncoder {
	static final int SOURCE_VERTEX_BYTES = 28;
	static final int SODIUM_SOURCE_VERTEX_BYTES = 20;
	static final int PACKED_VERTEX_BYTES = 16;
	private static final int VERTICES_PER_QUAD = 4;
	private static final float SODIUM_POSITION_SCALE = 32.0F / (1 << 20);
	private static final float SODIUM_POSITION_OFFSET = -8.0F;

	private PackedVertexEncoder() {
	}

	static EncodedGeometry encode(final ByteBuffer source) {
		ByteBuffer input = source.duplicate().order(ByteOrder.nativeOrder());
		int sourceBytes = input.remaining();
		int sourceQuadBytes = SOURCE_VERTEX_BYTES * VERTICES_PER_QUAD;
		if (sourceBytes == 0 || sourceBytes % sourceQuadBytes != 0) {
			throw new IllegalArgumentException("Terrain vertex data is not a whole number of quads: " + sourceBytes + " bytes");
		}

		int vertexCount = sourceBytes / SOURCE_VERTEX_BYTES;
		ByteBuffer packed = MemoryUtil.memAlloc(Math.multiplyExact(vertexCount, PACKED_VERTEX_BYTES))
			.order(ByteOrder.nativeOrder());
		try {
			int inputBase = input.position();
			for (int vertex = 0; vertex < vertexCount; vertex++) {
				int offset = inputBase + vertex * SOURCE_VERTEX_BYTES;
				int fixedX = packFixedPosition(input.getFloat(offset));
				int fixedY = packFixedPosition(input.getFloat(offset + 4));
				int fixedZ = packFixedPosition(input.getFloat(offset + 8));
				int blockLight = packLight(input.getShort(offset + 24));
				int skyLight = packLight(input.getShort(offset + 26));

				packed.putInt(fixedX & 0xffff | (fixedY & 0xffff) << 16);
				packed.putInt(fixedZ & 0xffff | blockLight << 16 | skyLight << 24);
				packed.putInt(input.getInt(offset + 12));
				packed.putInt(packUnorm16(input.getFloat(offset + 16)) | packUnorm16(input.getFloat(offset + 20)) << 16);
			}
			packed.flip();
			return new EncodedGeometry(packed, vertexCount / VERTICES_PER_QUAD);
		} catch (Throwable throwable) {
			MemoryUtil.memFree(packed);
			throw throwable;
		}
	}

	/**
	 * Repackages Sodium's 20-byte {@code CompactChunkVertex} stream. The source
	 * buffer belongs to a {@code ChunkBuildOutput}, so this method always makes
	 * an independent native copy before Sodium destroys that output.
	 */
	static EncodedGeometry encodeSodiumCompact(final ByteBuffer source) {
		ByteBuffer input = source.duplicate().order(ByteOrder.nativeOrder());
		int sourceBytes = input.remaining();
		int sourceQuadBytes = SODIUM_SOURCE_VERTEX_BYTES * VERTICES_PER_QUAD;
		if (sourceBytes == 0 || sourceBytes % sourceQuadBytes != 0) {
			throw new IllegalArgumentException("Sodium terrain data is not a whole number of quads: " + sourceBytes + " bytes");
		}

		int vertexCount = sourceBytes / SODIUM_SOURCE_VERTEX_BYTES;
		ByteBuffer packed = MemoryUtil.memAlloc(Math.multiplyExact(vertexCount, PACKED_VERTEX_BYTES))
			.order(ByteOrder.nativeOrder());
		try {
			int inputBase = input.position();
			for (int vertex = 0; vertex < vertexCount; vertex++) {
				int offset = inputBase + vertex * SODIUM_SOURCE_VERTEX_BYTES;
				int positionHigh = input.getInt(offset);
				int positionLow = input.getInt(offset + 4);
				int fixedX = packFixedPosition(decodeSodiumPosition(positionHigh, positionLow, 0));
				int fixedY = packFixedPosition(decodeSodiumPosition(positionHigh, positionLow, 10));
				int fixedZ = packFixedPosition(decodeSodiumPosition(positionHigh, positionLow, 20));

				int texture = input.getInt(offset + 12);

				int lightAndData = input.getInt(offset + 16);
				// Sodium stores texel-centred light coordinates (8..248), while
				// the Arc shader adds the texel-centre offset while sampling.
				int blockLight = Math.clamp((lightAndData & 0xff) - 8, 0, 240);
				int skyLight = Math.clamp(((lightAndData >>> 8) & 0xff) - 8, 0, 240);

				packed.putInt(fixedX & 0xffff | (fixedY & 0xffff) << 16);
				packed.putInt(fixedZ & 0xffff | blockLight << 16 | skyLight << 24);
				packed.putInt(input.getInt(offset + 8));
				// Preserve Sodium's 15-bit coordinates and per-axis bias bits.
				// The mesh shader combines these with u_TexCoordShrink from Sodium's
				// globals UBO, matching Sodium's atlas-edge sampling exactly.
				packed.putInt(texture);
			}
			packed.flip();
			return new EncodedGeometry(packed, vertexCount / VERTICES_PER_QUAD);
		} catch (Throwable throwable) {
			MemoryUtil.memFree(packed);
			throw throwable;
		}
	}

	private static float decodeSodiumPosition(final int high, final int low, final int shift) {
		int quantized = ((high >>> shift) & 0x3ff) << 10 | (low >>> shift) & 0x3ff;
		return quantized * SODIUM_POSITION_SCALE + SODIUM_POSITION_OFFSET;
	}

	private static int packFixedPosition(final float value) {
		if (!Float.isFinite(value)) {
			throw new IllegalArgumentException("Non-finite terrain position");
		}
		long fixed = Math.round(value * 1024.0F);
		if (fixed < Short.MIN_VALUE || fixed > Short.MAX_VALUE) {
			throw new IllegalArgumentException("Terrain position is outside the packed fixed-point range: " + value);
		}
		return (int)fixed;
	}

	private static int packLight(final short value) {
		int unsigned = Short.toUnsignedInt(value);
		if (unsigned > 255) {
			throw new IllegalArgumentException("Terrain light coordinate is outside the packed byte range: " + unsigned);
		}
		return unsigned;
	}

	private static int packUnorm16(final float value) {
		if (!Float.isFinite(value) || value < -0.0001F || value > 1.0001F) {
			throw new IllegalArgumentException("Terrain UV is outside the packed UNORM range: " + value);
		}
		return Math.round(Math.clamp(value, 0.0F, 1.0F) * 65535.0F);
	}

	record EncodedGeometry(ByteBuffer data, int quadCount) {
	}
}
