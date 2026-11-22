#version 120
// Moci BlastWave refraction pass. Keep this file ASCII-only.
// Textures (c) CCP Games, EVE Online: warptunnel4_n (direction) / radial_hole_01 (amplitude, R channel).
// Direction and amplitude are separate inputs, wired like the original distortion ubershader
// (note the original sampler names are swapped: the "mask" slot holds the normal map and vice versa):
//   direction - warptunnel4_n xy expanded on (T = around the ring, B = radial); z is divided by flatten before normalizing.
//   amplitude - radial_hole_01.r sampled on raw ring UV (+ static phase), multiplied by a band window so both band edges are 0.
// sceneTex is a copy of the current window pixels; sample at gl_FragCoord plus an offset.
uniform sampler2D sceneTex;
uniform sampler2D normalTex;
uniform sampler2D ampTex;
uniform vec4 normalXform;   // xy = UV scale, zw = scroll per second
uniform vec4 ampXform;      // xy = UV scale, zw = static offset
uniform vec2 seed;
uniform float time;
uniform vec2 invSceneSize;  // 1 / scene copy size in pixels
uniform float offsetPx;     // max offset in window pixels, already scaled by zoom and brightness
uniform float flatten;      // normal z squash factor
uniform float radialPush;   // 0 = pure normal direction (original), 1 = pure radial push (lens bulge)
uniform float chroma;       // R/B offset multiplier difference vs G, 0 = off

varying vec2 ringUV;
varying vec2 radialDir;

void main() {
    vec3 n = texture2D(normalTex, (ringUV + seed) * normalXform.xy + time * normalXform.zw).rgb * 2.0 - 1.0;
    n = normalize(vec3(n.xy, n.z / max(flatten, 0.001)));
    vec2 b = normalize(radialDir);
    vec2 t = vec2(-b.y, b.x);
    vec2 dir = mix(n.x * t + n.y * b, b, clamp(radialPush, 0.0, 1.0));

    float v = clamp(ringUV.y, 0.0, 1.0);
    float window = sin(3.14159265 * v);
    float amp = texture2D(ampTex, ringUV * ampXform.xy + ampXform.zw).r * window;

    vec2 base = gl_FragCoord.xy * invSceneSize;
    vec2 offs = dir * (amp * offsetPx) * invSceneSize;
    vec4 c;
    c.g = texture2D(sceneTex, base - offs).g;
    c.r = texture2D(sceneTex, base - offs * (1.0 + chroma)).r;
    c.b = texture2D(sceneTex, base - offs * (1.0 - chroma)).b;
    c.a = texture2D(sceneTex, base).a;
    gl_FragColor = c;
}
