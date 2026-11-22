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
uniform float foldCount;  // number of folds
uniform float foldSpeed;  // scroll speed
uniform float inertia;    // inertia / mass

out vec2 fragUV;
out float waveOffset;

void main() {
    fragUV = max(vertex, vec2(0.0));
    
    // Position along the cape length (0 = front, 1 = tail)
    float lengthPos = fragUV.x;
    
    // Inertia-based delay - the tail lags more
    float delay = lengthPos * inertia;
    float delayedTime = time - delay;
    
    // Fold wave effect
    float wave = sin(lengthPos * foldCount * 3.14159 + delayedTime * foldSpeed) * 0.1;
    wave *= lengthPos; // small wave at the front, large at the tail
    
    // Lateral wave (simulates fluttering)
    float sideWave = sin(delayedTime * foldSpeed * 0.5 + lengthPos * 2.0) * lengthPos * 0.05;
    
    waveOffset = wave;
    
    // Apply deformation
    vec2 deformedVertex = vertex;
    deformedVertex.x += wave;
    deformedVertex.y += sideWave;
    
    vec4 resized = vec4(deformedVertex * size * 0.5, 0.0, 1.0);
    gl_Position = gameViewport * vec4((modelMatrix * resized).xy, 0.0, 1.0);
}
