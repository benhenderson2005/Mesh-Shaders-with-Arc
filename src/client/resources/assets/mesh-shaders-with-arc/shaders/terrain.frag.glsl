#version 460

layout(set = 0, binding = 5, std140) uniform FogData {
    vec4 color;
    float environmentalStart;
    float environmentalEnd;
    float renderDistanceStart;
    float renderDistanceEnd;
    float skyEnd;
    float cloudsEnd;
} fog;

layout(set = 0, binding = 3, std140) uniform GlobalsData {
    ivec3 cameraBlockPosition;
    vec3 cameraSubBlockOffset;
    vec2 screenSize;
    float glintAlpha;
    float gameTime;
    int menuBlurRadius;
    int useRgss;
} globals;

layout(set = 0, binding = 6) uniform sampler2D blockAtlas;

layout(push_constant) uniform DrawConstants {
    uint firstTask;
    float alphaCutout;
    uint packedGeometry;
} drawConstants;

layout(location = 0) in float sphericalDistance;
layout(location = 1) in float cylindricalDistance;
layout(location = 2) in vec4 litVertexColor;
layout(location = 3) in vec2 blockTextureCoordinate;
layout(location = 4) flat in float sectionVisibility;

layout(location = 0) out vec4 outputColor;

float fogAmount(float distanceValue, float startDistance, float endDistance) {
    float span = max(endDistance - startDistance, 0.0001);
    return clamp((distanceValue - startDistance) / span, 0.0, 1.0);
}

vec4 sampleTexelAwareNearest(vec2 uv, vec2 pixelSize, vec2 du, vec2 dv, vec2 screenFootprint) {
    vec2 texelCoordinate = uv / pixelSize;
    vec2 lowerCorner = round(texelCoordinate) - vec2(0.5);
    vec2 withinTexel = texelCoordinate - lowerCorner;
    vec2 safeFootprint = max(screenFootprint, vec2(0.0000001));
    withinTexel = clamp((withinTexel - vec2(0.5)) * pixelSize / safeFootprint + vec2(0.5), 0.0, 1.0);
    return textureGrad(blockAtlas, (lowerCorner + withinTexel) * pixelSize, du, dv);
}

vec4 sampleTexelAwareNearest(vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    return sampleTexelAwareNearest(uv, pixelSize, du, dv, sqrt(du * du + dv * dv));
}

vec4 sampleRotatedGrid(vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 screenFootprint = sqrt(du * du + dv * dv);
    float largestFootprint = max(screenFootprint.x, screenFootprint.y);
    float smallestPixel = min(pixelSize.x, pixelSize.y);
    float filteredWeight = smoothstep(smallestPixel, smallestPixel * 2.0, largestFootprint);

    float derivativeLow = min(length(du), length(dv));
    float derivativeHigh = max(length(du), length(dv));
    float exactLod = max(0.0, log2(max(sqrt(derivativeLow * derivativeHigh), 0.0000001) / smallestPixel));
    float lowLod = floor(exactLod);
    float highLod = lowLod + 1.0;
    float lodWeight = fract(exactLod);
    const vec2 offsets[4] = vec2[](
        vec2(0.125, 0.375),
        vec2(-0.125, -0.375),
        vec2(0.375, -0.125),
        vec2(-0.375, 0.125)
    );
    vec4 lowSamples = vec4(0.0);
    vec4 highSamples = vec4(0.0);
    for (int sampleIndex = 0; sampleIndex < 4; ++sampleIndex) {
        vec2 sampleUv = uv + offsets[sampleIndex] * pixelSize;
        lowSamples += textureLod(blockAtlas, sampleUv, lowLod);
        highSamples += textureLod(blockAtlas, sampleUv, highLod);
    }
    vec4 filtered = mix(lowSamples, highSamples, lodWeight) * 0.25;
    vec4 nearest = sampleTexelAwareNearest(uv, pixelSize, du, dv, screenFootprint);
    return mix(nearest, filtered, filteredWeight);
}

void main() {
    vec2 atlasPixelSize = 1.0 / vec2(textureSize(blockAtlas, 0));
    vec4 atlasColor = globals.useRgss == 1
        ? sampleRotatedGrid(blockTextureCoordinate, atlasPixelSize)
        : sampleTexelAwareNearest(blockTextureCoordinate, atlasPixelSize);
    vec4 shaded = atlasColor * litVertexColor;
    shaded = mix(fog.color * vec4(1.0, 1.0, 1.0, shaded.a), shaded, sectionVisibility);
    if (shaded.a < drawConstants.alphaCutout) {
        discard;
    }

    float environmentalFog = fogAmount(sphericalDistance, fog.environmentalStart, fog.environmentalEnd);
    float distanceFog = fogAmount(cylindricalDistance, fog.renderDistanceStart, fog.renderDistanceEnd);
    float blend = max(environmentalFog, distanceFog) * fog.color.a;
    outputColor = vec4(mix(shaded.rgb, fog.color.rgb, blend), shaded.a);
}
