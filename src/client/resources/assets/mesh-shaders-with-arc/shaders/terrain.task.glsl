#version 460
#extension GL_EXT_mesh_shader : require

layout(local_size_x = 32) in;

struct TerrainTask {
    uint baseVertex;
    uint quadCount;
    uint chunkWordOffset;
    uint reserved;
};

layout(set = 0, binding = 1, std430) readonly buffer TerrainTasks {
    TerrainTask tasks[];
};

layout(set = 0, binding = 2, std430) readonly buffer ChunkWords {
    uint chunkWords[];
};

layout(set = 0, binding = 8) uniform sampler2D previousDepthPyramid;

layout(set = 0, binding = 9, std430) readonly buffer PreviousFrameData {
    mat4 previousProjection;
    mat4 previousModelView;
    ivec4 previousCameraBlock;
    vec4 previousCameraSub;
    vec4 previousViewport;
} previousFrame;

layout(push_constant) uniform DrawConstants {
    uint firstTask;
    float alphaCutout;
    uint packedGeometry;
    uint taskCount;
    uint hzbValid;
} drawConstants;

struct TerrainPayload {
    uint taskIndices[32];
};
taskPayloadSharedEXT TerrainPayload taskPayload;

shared uint visibleTaskCount;
shared uint visibleFlags[32];

// Sodium's compact terrain format permits model vertices throughout this
// section-relative domain; using the full range keeps HZB rejection conservative.
const float AABB_MIN = -8.0;
const float AABB_MAX = 24.0;
const float DEPTH_BIAS = 0.0001;

vec3 sectionCorner(uint corner) {
    return vec3(
        (corner & 1u) != 0u ? AABB_MAX : AABB_MIN,
        (corner & 2u) != 0u ? AABB_MAX : AABB_MIN,
        (corner & 4u) != 0u ? AABB_MAX : AABB_MIN
    );
}

bool sectionIsOccluded(TerrainTask task) {
    if (drawConstants.hzbValid == 0u) {
        return false;
    }

    uint chunkBase = task.chunkWordOffset;
    ivec3 chunkPosition = ivec3(
        int(chunkWords[chunkBase + 20u]),
        int(chunkWords[chunkBase + 21u]),
        int(chunkWords[chunkBase + 22u])
    );
    vec3 relativeOrigin = vec3(chunkPosition - previousFrame.previousCameraBlock.xyz)
        + previousFrame.previousCameraSub.xyz;

    vec2 minimumUv = vec2(1.0);
    vec2 maximumUv = vec2(0.0);
    float nearestDepth = 0.0;
    for (uint corner = 0u; corner < 8u; ++corner) {
        vec4 clip = previousFrame.previousProjection
            * previousFrame.previousModelView
            * vec4(relativeOrigin + sectionCorner(corner), 1.0);
        if (clip.w <= 0.01) {
            return false;
        }

        vec3 ndc = clip.xyz / clip.w;
        if (ndc.z < 0.0 || ndc.z > 1.0) {
            return false;
        }

        vec2 uv = ndc.xy * 0.5 + 0.5;
        minimumUv = min(minimumUv, uv);
        maximumUv = max(maximumUv, uv);
        nearestDepth = max(nearestDepth, ndc.z);
    }

    // Unknown pixels outside the previous viewport must never be treated as occluded.
    if (any(lessThan(minimumUv, vec2(0.0))) || any(greaterThan(maximumUv, vec2(1.0)))) {
        return false;
    }

    vec2 viewport = previousFrame.previousViewport.xy;
    vec2 baseMinimum = minimumUv * viewport * 0.5;
    vec2 baseMaximum = maximumUv * viewport * 0.5;
    vec2 extent = max(baseMaximum - baseMinimum, vec2(1.0));
    float requestedLod = ceil(log2(max(extent.x, extent.y)));
    int maxLod = max(textureQueryLevels(previousDepthPyramid) - 1, 0);
    int lod = clamp(int(requestedLod), 0, maxLod);
    ivec2 mipSize = textureSize(previousDepthPyramid, lod);
    float scale = exp2(float(lod));
    ivec2 low = clamp(ivec2(floor(baseMinimum / scale)), ivec2(0), mipSize - 1);
    ivec2 high = clamp(ivec2(floor(baseMaximum / scale)), ivec2(0), mipSize - 1);

    float minimumOccluderDepth = min(
        min(texelFetch(previousDepthPyramid, low, lod).r, texelFetch(previousDepthPyramid, ivec2(high.x, low.y), lod).r),
        min(texelFetch(previousDepthPyramid, ivec2(low.x, high.y), lod).r, texelFetch(previousDepthPyramid, high, lod).r)
    );
    return minimumOccluderDepth > 0.0 && nearestDepth + DEPTH_BIAS < minimumOccluderDepth;
}

void main() {
    uint lane = gl_LocalInvocationIndex;
    uint localTask = gl_WorkGroupID.x * 32u + lane;
    visibleFlags[lane] = 0u;
    if (localTask < drawConstants.taskCount) {
        uint taskIndex = drawConstants.firstTask + localTask;
        if (!sectionIsOccluded(tasks[taskIndex])) {
            visibleFlags[lane] = 1u;
        }
    }
    barrier();

    // Stable ascending compaction preserves the direct path's primitive order.
    if (lane == 0u) {
        uint outputCount = 0u;
        for (uint candidate = 0u; candidate < 32u; ++candidate) {
            if (visibleFlags[candidate] != 0u) {
                taskPayload.taskIndices[outputCount++] = drawConstants.firstTask
                    + gl_WorkGroupID.x * 32u
                    + candidate;
            }
        }
        visibleTaskCount = outputCount;
    }
    barrier();

    EmitMeshTasksEXT(visibleTaskCount, 1u, 1u);
}
