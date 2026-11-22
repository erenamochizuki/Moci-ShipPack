#version 120
// Moci BlastSphere: shades a flat disc as the camera-facing half of a glowing sphere. Keep this file ASCII-only.
// Textures (c) CCP Games, EVE Online: firetile_02a / firemask_01a.
//   z      = sqrt(1 - d^2), the sphere's depth at this pixel (1 at the center, 0 at the limb)
//   rim    = (1 - z)^rimPower   -> Fresnel-like limb brightening, reads as a 3D shell
//   core   = z^corePower        -> bright center (fireball) or a broad soft disc (haze)
//   q      = dir * asin(d) / (pi/2): planar texture coords bent onto the sphere, compressed toward the limb,
//            so scrolling the texture looks like the surface is rotating. No pole pinch at the center.
//   edge   = soft silhouette, optionally pushed in/out by the texture so a fireball has a billowing outline.
// Output is additive (ONE, ONE); alpha is 0.
uniform sampler2D fineTex;
uniform sampler2D bandTex;
uniform vec4 fineXform;   // xy = planar scale, zw = scroll per second
uniform vec4 bandXform;
uniform vec2 seed;
uniform float time;
uniform vec2 radii;       // y = sphere radius (world units)
uniform vec3 rimColor;    // already multiplied by intensity and current brightness
uniform vec3 coreColor;
uniform vec4 shape;       // x = rim power, y = core power, z = texture amount 0..1, w = edge softness (fraction of radius)
uniform float texBias;    // mip bias for both textures: higher = softer, cloudier surface
uniform float edgeNoise;  // texture brightness perturbs the silhouette: 0 = perfect circle, ~0.3 = billowing cloud edge

varying vec2 localPos;

void main() {
    float r = length(localPos);
    float d = clamp(r / max(radii.y, 0.001), 0.0, 1.0);
    vec2 dir = r > 0.0001 ? localPos / r : vec2(0.0, 0.0);
    float z = sqrt(max(1.0 - d * d, 0.0));
    vec2 q = dir * (asin(d) * 0.63661977);
    vec3 fine = texture2D(fineTex, (q + seed) * fineXform.xy + time * fineXform.zw, texBias).rgb;
    vec3 band = texture2D(bandTex, (q + seed.yx) * bandXform.xy + time * bandXform.zw, texBias).rgb;
    vec3 raw = fine * band * 5.0;
    vec3 tex = mix(vec3(1.0), raw, clamp(shape.z, 0.0, 1.0));
    float rim = pow(1.0 - z, max(shape.x, 0.01));
    float core = pow(z, max(shape.y, 0.01));
    float lum = dot(raw, vec3(0.333));
    float edge = 1.0 - smoothstep(1.0 - max(shape.w, 0.001), 1.0, d + edgeNoise * (0.4 - lum));
    gl_FragColor = vec4((rimColor * rim + coreColor * core) * tex * edge, 0.0);
}
