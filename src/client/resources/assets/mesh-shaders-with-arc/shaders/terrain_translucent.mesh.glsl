#version 460
#extension GL_EXT_mesh_shader : require

layout(local_size_x = 32) in;
layout(triangles, max_vertices = 128, max_primitives = 64) out;

layout(set = 0, binding = 0, std430) readonly buffer GeometryWords {
    uint geometryWords[];
};

struct TranslucentTask {
    uint baseVertex;
    uint firstIndex;
    uint quadCount;
    uint chunkWordOffset;
    uint indexType;
    uint reserved0;
    uint reserved1;
    uint reserved2;
};

layout(set = 0, binding = 1, std430) readonly buffer TranslucentTasks {
    TranslucentTask tasks[];
};

layout(set = 0, binding = 2, std430) readonly buffer ChunkWords {
    uint chunkWords[];
};

layout(set = 0, binding = 3, std140) uniform GlobalsData {
    ivec3 cameraBlockPosition;
    vec3 cameraSubBlockOffset;
    vec2 screenSize;
    float glintAlpha;
    float gameTime;
    int menuBlurRadius;
    int useRgss;
} globals;

layout(set = 0, binding = 4, std140) uniform ProjectionData {
    mat4 projectionMatrix;
    mat4 sodiumModelView;
    vec4 sodiumFogColor;
    vec2 sodiumEnvironmentFog;
    vec2 sodiumRenderFog;
    vec2 sodiumTexelSize;
    vec2 sodiumTexCoordShrink;
} projection;

layout(set = 0, binding = 7) uniform sampler2D lightmapTexture;

layout(set = 0, binding = 8, std430) readonly buffer SortedIndexWords {
    uint indexWords[];
};

layout(push_constant) uniform DrawConstants {
    uint firstTask;
    float alphaCutout;
    uint packedGeometry;
} drawConstants;

layout(location = 0) out float sphericalDistance[];
layout(location = 1) out float cylindricalDistance[];
layout(location = 2) out vec4 litVertexColor[];
layout(location = 3) out vec2 blockTextureCoordinate[];
layout(location = 4) flat out float sectionVisibility[];

float chunkFloat(uint base, uint offset) {
    return uintBitsToFloat(chunkWords[base + offset]);
}

mat4 chunkModelView(uint base) {
    return mat4(
        vec4(chunkFloat(base, 0), chunkFloat(base, 1), chunkFloat(base, 2), chunkFloat(base, 3)),
        vec4(chunkFloat(base, 4), chunkFloat(base, 5), chunkFloat(base, 6), chunkFloat(base, 7)),
        vec4(chunkFloat(base, 8), chunkFloat(base, 9), chunkFloat(base, 10), chunkFloat(base, 11)),
        vec4(chunkFloat(base, 12), chunkFloat(base, 13), chunkFloat(base, 14), chunkFloat(base, 15))
    );
}

vec4 sampleLightmap(ivec2 lightCoordinate) {
    vec2 coordinate = clamp(
        (vec2(lightCoordinate) + vec2(8.0)) / 256.0,
        vec2(0.03125),
        vec2(0.96875)
    );
    return texture(lightmapTexture, coordinate);
}

uint readSortedQuadBase(TranslucentTask task, uint lane) {
    if (task.indexType == 2u) {
        return lane * 4u;
    }
    uint element = task.firstIndex + lane * 6u;
    if (task.indexType == 0u) {
        uint packedIndices = indexWords[element >> 1u];
        return (packedIndices >> ((element & 1u) * 16u)) & 0xffffu;
    }
    return indexWords[element];
}

void writeVertex(uint outputIndex, uint sourceVertex, uint chunkBase, mat4 modelView, ivec3 chunkPosition, float visibility) {
    uint word;
    vec3 localPosition;
    vec2 uv;
    uint packedColor;
    ivec2 lightCoordinate;
    bool sodiumCompact = drawConstants.packedGeometry == 2u;
    bool sodiumPacked = drawConstants.packedGeometry == 3u;
    if (drawConstants.packedGeometry == 1u || sodiumPacked) {
        word = sourceVertex * 4u;
        uint packedXY = geometryWords[word];
        uint packedZAndLight = geometryWords[word + 1u];
        localPosition = vec3(
            float(bitfieldExtract(int(packedXY), 0, 16)),
            float(bitfieldExtract(int(packedXY), 16, 16)),
            float(bitfieldExtract(int(packedZAndLight), 0, 16))
        ) / 1024.0;
        packedColor = geometryWords[word + 2u];
        if (sodiumPacked) {
            uint encodedUv = geometryWords[word + 3u];
            uvec2 uvWords = uvec2(encodedUv & 0xffffu, encodedUv >> 16u);
            vec2 encodingBias = mix(vec2(-1.0), vec2(1.0), bvec2(uvWords >> 15u));
            uv = vec2(uvWords & 0x7fffu) / 32768.0
                + encodingBias * projection.sodiumTexCoordShrink;
        } else {
            uv = unpackUnorm2x16(geometryWords[word + 3u]);
        }
        lightCoordinate = ivec2(
            int((packedZAndLight >> 16u) & 0xffu),
            int((packedZAndLight >> 24u) & 0xffu)
        );
    } else if (sodiumCompact) {
        word = sourceVertex * 5u;
        uint positionHigh = geometryWords[word];
        uint positionLow = geometryWords[word + 1u];
        uvec3 quantizedPosition = (
            uvec3(
                bitfieldExtract(positionHigh, 0, 10),
                bitfieldExtract(positionHigh, 10, 10),
                bitfieldExtract(positionHigh, 20, 10)
            ) << 10u
        ) | uvec3(
            bitfieldExtract(positionLow, 0, 10),
            bitfieldExtract(positionLow, 10, 10),
            bitfieldExtract(positionLow, 20, 10)
        );
        localPosition = vec3(quantizedPosition) * (32.0 / 1048576.0) - vec3(8.0);
        packedColor = geometryWords[word + 2u];
        uint encodedUv = geometryWords[word + 3u];
        uvec2 uvWords = uvec2(encodedUv & 0xffffu, encodedUv >> 16u);
        vec2 encodingBias = mix(vec2(-1.0), vec2(1.0), bvec2(uvWords >> 15u));
        uv = vec2(uvWords & 0x7fffu) / 32768.0
            + encodingBias * projection.sodiumTexCoordShrink;
        uint lightAndData = geometryWords[word + 4u];
        lightCoordinate = ivec2(int(lightAndData & 0xffu), int((lightAndData >> 8u) & 0xffu));
    } else {
        word = sourceVertex * 7u;
        localPosition = vec3(
            uintBitsToFloat(geometryWords[word]),
            uintBitsToFloat(geometryWords[word + 1u]),
            uintBitsToFloat(geometryWords[word + 2u])
        );
        packedColor = geometryWords[word + 3u];
        uv = vec2(
            uintBitsToFloat(geometryWords[word + 4u]),
            uintBitsToFloat(geometryWords[word + 5u])
        );
        uint packedLight = geometryWords[word + 6u];
        lightCoordinate = ivec2(
            int(packedLight & 0xffffu),
            int((packedLight >> 16u) & 0xffffu)
        );
    }

    vec3 cameraRelativePosition = localPosition
        + vec3(chunkPosition - globals.cameraBlockPosition)
        + globals.cameraSubBlockOffset;
    gl_MeshVerticesEXT[outputIndex].gl_Position = projection.projectionMatrix
        * modelView
        * vec4(cameraRelativePosition, 1.0);
    sphericalDistance[outputIndex] = length(cameraRelativePosition);
    cylindricalDistance[outputIndex] = max(length(cameraRelativePosition.xz), abs(cameraRelativePosition.y));
    vec4 lightColor = sodiumCompact
        ? texture(lightmapTexture, vec2(lightCoordinate) / 256.0)
        : sampleLightmap(lightCoordinate);
    litVertexColor[outputIndex] = unpackUnorm4x8(packedColor) * lightColor;
    blockTextureCoordinate[outputIndex] = uv;
    sectionVisibility[outputIndex] = visibility;
}

void main() {
    uint lane = gl_LocalInvocationIndex;
    TranslucentTask task = tasks[drawConstants.firstTask + gl_WorkGroupID.x];
    SetMeshOutputsEXT(task.quadCount * 4u, task.quadCount * 2u);
    if (lane >= task.quadCount) {
        return;
    }

    uint chunkBase = task.chunkWordOffset;
    mat4 modelView = chunkModelView(chunkBase);
    float visibility = chunkFloat(chunkBase, 16u);
    ivec3 chunkPosition = ivec3(
        int(chunkWords[chunkBase + 20u]),
        int(chunkWords[chunkBase + 21u]),
        int(chunkWords[chunkBase + 22u])
    );
    uint outputVertex = lane * 4u;
    uint sourceVertex = task.baseVertex + readSortedQuadBase(task, lane);

    writeVertex(outputVertex, sourceVertex, chunkBase, modelView, chunkPosition, visibility);
    writeVertex(outputVertex + 1u, sourceVertex + 1u, chunkBase, modelView, chunkPosition, visibility);
    writeVertex(outputVertex + 2u, sourceVertex + 2u, chunkBase, modelView, chunkPosition, visibility);
    writeVertex(outputVertex + 3u, sourceVertex + 3u, chunkBase, modelView, chunkPosition, visibility);

    uint primitive = lane * 2u;
    gl_PrimitiveTriangleIndicesEXT[primitive] = uvec3(outputVertex, outputVertex + 1u, outputVertex + 2u);
    gl_PrimitiveTriangleIndicesEXT[primitive + 1u] = uvec3(outputVertex + 2u, outputVertex + 3u, outputVertex);
}
