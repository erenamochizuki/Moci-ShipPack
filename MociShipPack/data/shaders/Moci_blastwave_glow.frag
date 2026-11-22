#version 120
// Moci BlastWave glow pass. Keep this file ASCII-only.
// Textures (c) CCP Games, EVE Online: firetile_02a / firemask_01a / lasergradient_01f.
// Three inputs are MULTIPLIED (not added, not used to perturb UV), as in the original ubershader:
//   fineTex - firetile_02a, own UV scale + scroll. Tiny V scale => thin around, long across => radial streaks.
//   bandTex - firemask_01a, scrolls opposite to fine; dark segments cut the streaks.
//   maskTex - lasergradient_01f on raw UV, never scrolls: texture flows, silhouette stays put.
// Output is additive (ONE, ONE); alpha is 0 so the framebuffer alpha is untouched.
//
// The same shader also draws a cheap bloom pass: the ring geometry is widened, ringUV.y is remapped back to
// the original band coordinate, every lookup uses a mip bias (blur), and light spilling past the outer edge fades out.
// The sharp pass may use a small mip bias too: the original renders at 960x960 offscreen and upscales, which softens detail.
uniform sampler2D fineTex;
uniform sampler2D bandTex;
uniform sampler2D maskTex;
uniform sampler2D warpTex;
uniform sampler2D holeTex;
uniform float selfWarp;    // combo: sample displaced emission, never sample the battlefield
uniform vec4 fineXform;   // xy = UV scale, zw = scroll per second
uniform vec4 bandXform;
uniform vec2 seed;        // per-blast random phase
uniform float time;
uniform vec3 color;       // already multiplied by intensity and current brightness
uniform vec4 bloom;       // x = mip bias, y/z = band remap (v = ringUV.y * y + z), w = outer spill width in band units

varying vec2 ringUV;

void main() {
    float v = ringUV.y * bloom.y + bloom.z;
    vec2 uv = vec2(ringUV.x, v);
    if (selfWarp > 0.0) {
        vec3 n = texture2D(warpTex,3.0*uv + time*vec2(.05,-.05)).rgb*2.0-1.0;
        vec2 slope = normalize(vec3(n.xy,n.z*.1)).xy;
        float field = texture2D(holeTex,uv+vec2(0.,1.)).r * sin(3.14159*clamp(v,0.,1.));
        uv -= selfWarp * slope * field * vec2(.0024,.055);
    }
    vec3 fine = texture2D(fineTex, (uv + seed) * fineXform.xy + time * fineXform.zw, bloom.x).rgb;
    vec3 band = texture2D(bandTex, (uv + seed.yx) * bandXform.xy + time * bandXform.zw, bloom.x).rgb;
    // maskTex uses CLAMP_TO_EDGE on V (Java side): with REPEAT, blurred mips would bleed the bright outer row onto the inner edge.
    vec3 edge = texture2D(maskTex, vec2(uv.x, clamp(uv.y, 0.0, 1.0)), bloom.x).rgb;
    float spill = 1.0 - smoothstep(0.0, max(bloom.w, 0.0001), v - 1.0);
    gl_FragColor = vec4(fine * band * edge * color * spill, 0.0);
}
