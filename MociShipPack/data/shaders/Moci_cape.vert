#version 420

layout (location = 0) in vec2 vertex;
layout (std140, binding = 0) uniform BUtilGlobalData
{
    mat4 gameViewport;
    vec4 gameScreenBorder;
};

uniform mat4 modelMatrix;
uniform vec2 size;  // x: length, y: width
uniform float time;
uniform float foldCount;  // 褶皱数量
uniform float foldSpeed;  // 卷动速度
uniform float inertia;    // 惯性/质量

out vec2 fragUV;
out float waveOffset;

void main() {
    fragUV = max(vertex, vec2(0.0));
    
    // 计算沿披风长度的位置 (0=前端, 1=末端)
    float lengthPos = fragUV.x;
    
    // 基于惯性的延迟效果 - 末端延迟更多
    float delay = lengthPos * inertia;
    float delayedTime = time - delay;
    
    // 褶皱波动效果
    float wave = sin(lengthPos * foldCount * 3.14159 + delayedTime * foldSpeed) * 0.1;
    wave *= lengthPos; // 前端波动小，末端波动大
    
    // 横向波动（模拟飘动）
    float sideWave = sin(delayedTime * foldSpeed * 0.5 + lengthPos * 2.0) * lengthPos * 0.05;
    
    waveOffset = wave;
    
    // 应用变形
    vec2 deformedVertex = vertex;
    deformedVertex.x += wave;
    deformedVertex.y += sideWave;
    
    vec4 resized = vec4(deformedVertex * size * 0.5, 0.0, 1.0);
    gl_Position = gameViewport * vec4((modelMatrix * resized).xy, 0.0, 1.0);
}
