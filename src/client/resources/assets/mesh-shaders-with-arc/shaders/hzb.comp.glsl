#version 460

layout(local_size_x = 8, local_size_y = 8) in;

layout(set = 0, binding = 0) uniform sampler2D sourceDepth;
layout(set = 0, binding = 1, r32f) uniform writeonly image2D destinationDepth;

layout(set = 0, binding = 2, std430) buffer PreviousFrameData {
    mat4 previousProjection;
    mat4 previousModelView;
    ivec4 previousCameraBlock;
    vec4 previousCameraSub;
    vec4 previousViewport;
} previousFrame;

layout(set = 0, binding = 3, std430) readonly buffer ChunkWords {
    uint chunkWords[];
};

layout(set = 0, binding = 4, std140) uniform GlobalsData {
    ivec3 cameraBlockPosition;
    vec3 cameraSubBlockOffset;
    vec2 screenSize;
    float glintAlpha;
    float gameTime;
    int menuBlurRadius;
    int useRgss;
} globals;

layout(set = 0, binding = 5, std140) uniform ProjectionData {
    mat4 projectionMatrix;
} projection;

layout(push_constant) uniform BuildConstants {
    uint baseLevel;
    uint chunkWordOffset;
} buildConstants;

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

float fetchBaseDepth(ivec2 coordinate, ivec2 sourceSize) {
    return any(lessThan(coordinate, ivec2(0))) || any(greaterThanEqual(coordinate, sourceSize))
        ? 0.0
        : texelFetch(sourceDepth, coordinate, 0).r;
}

float fetchReducedDepth(ivec2 coordinate, ivec2 sourceSize) {
    return texelFetch(sourceDepth, clamp(coordinate, ivec2(0), sourceSize - 1), 0).r;
}

void main() {
    ivec2 destination = ivec2(gl_GlobalInvocationID.xy);
    ivec2 destinationSize = imageSize(destinationDepth);
    if (any(greaterThanEqual(destination, destinationSize))) {
        return;
    }

    ivec2 sourceSize = textureSize(sourceDepth, 0);
    ivec2 source = destination * 2;
    float d0;
    float d1;
    float d2;
    float d3;
    if (buildConstants.baseLevel != 0u) {
        // Outside the real viewport is background in reversed-Z and must remain zero.
        d0 = fetchBaseDepth(source, sourceSize);
        d1 = fetchBaseDepth(source + ivec2(1, 0), sourceSize);
        d2 = fetchBaseDepth(source + ivec2(0, 1), sourceSize);
        d3 = fetchBaseDepth(source + ivec2(1, 1), sourceSize);
    } else {
        // Vulkan clamps a one-texel mip dimension; repeat that edge during reduction.
        d0 = fetchReducedDepth(source, sourceSize);
        d1 = fetchReducedDepth(source + ivec2(1, 0), sourceSize);
        d2 = fetchReducedDepth(source + ivec2(0, 1), sourceSize);
        d3 = fetchReducedDepth(source + ivec2(1, 1), sourceSize);
    }
    imageStore(destinationDepth, destination, vec4(min(min(d0, d1), min(d2, d3))));

    if (buildConstants.baseLevel != 0u && all(equal(destination, ivec2(0)))) {
        previousFrame.previousProjection = projection.projectionMatrix;
        previousFrame.previousModelView = chunkModelView(buildConstants.chunkWordOffset);
        previousFrame.previousCameraBlock = ivec4(globals.cameraBlockPosition, 0);
        previousFrame.previousCameraSub = vec4(globals.cameraSubBlockOffset, 0.0);
        previousFrame.previousViewport = vec4(globals.screenSize, vec2(destinationSize));
    }
}
