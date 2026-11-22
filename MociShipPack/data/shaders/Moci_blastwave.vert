#version 120
// Moci BlastWave shared vertex shader. Keep this file ASCII-only (some drivers reject non-ASCII comments).
// gl_Vertex carries ring UV only: x = around the ring 0..1 (seam vertex duplicated), y = across the band 0..1 (inner 0, outer 1).
// World position is rebuilt from center/radii, so expansion only changes uniforms and texture density stays locked to UV.
// With radii.x = 0 the same mesh is a filled disc (used by the sphere / fireball / haze layers).
uniform vec2 center;
uniform vec2 radii;      // x = inner radius, y = outer radius (world units)

varying vec2 ringUV;
varying vec2 radialDir;
varying vec2 localPos;   // offset from center in world units; exact per-pixel radius for disc shading

void main() {
    float angle = gl_Vertex.x * 6.28318530718;
    vec2 dir = vec2(cos(angle), sin(angle));
    float r = mix(radii.x, radii.y, gl_Vertex.y);
    gl_Position = gl_ModelViewProjectionMatrix * vec4(center + dir * r, 0.0, 1.0);
    ringUV = gl_Vertex.xy;
    radialDir = dir;
    localPos = dir * r;
}
