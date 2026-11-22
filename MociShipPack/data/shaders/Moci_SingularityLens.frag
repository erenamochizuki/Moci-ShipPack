#version 110
uniform sampler2D srcTex;
uniform vec2 center;
uniform vec3 rs_rlens_strength;
uniform vec2 texSize;
uniform float darkAlpha;
uniform float magStrength;
uniform vec3 horizonColor;
uniform float accretionLens;
varying vec2 v_uv;

vec2 deflectToSourceUV(vec2 uv, float lensWeight){
    float thetaE = rs_rlens_strength.z;
    vec2 px = uv * texSize;
    vec2 c = center * texSize;
    vec2 d = px - c;
    float r = length(d);
    if (r < 1.0) return uv;
    vec2 rhat = d / r;
    float alphaPx = (thetaE * thetaE) / r;
    alphaPx *= lensWeight;
    vec2 srcPx = px - rhat * alphaPx;
    return srcPx / texSize;
}

void main(){
    float rsPx = rs_rlens_strength.x;
    float rMaxPx = rs_rlens_strength.y;
    float thetaE = rs_rlens_strength.z;

    vec2 uv = gl_FragCoord.xy / texSize;
    if (accretionLens > 0.5) uv = v_uv;
    vec2 px = uv * texSize;
    vec2 c = center * texSize;
    float r = length(px - c);

    // Corners of the bounding quad lie outside the lens; skip them.
    if (r > rMaxPx + 1.0) discard;

    vec3 base = texture2D(srcTex, uv).rgb;
    float fade = clamp(darkAlpha, 0.0, 1.0);
    if (accretionLens > 0.5) {
        // Trace into this hole's capture, which already contains ships and the accretion disk.
        // The inner rays fold towards one side rather than focusing a whole ring onto one texel.
        float rs = max(rsPx, 1.0);
        vec2 q = (px - c) / rs;
        float rn = r / rs;
        float ray = thetaE / max(r, rs);
        vec2 outer = q * (1.0 - min(0.32, ray * ray));
        vec2 pivot = vec2(-0.46, 0.15);
        vec2 d = q - pivot;
        vec2 shoulder = d * vec2(1.50, 1.06);
        float angle = -1.9 * exp(-1.85 * dot(shoulder, shoulder));
        float cs = cos(angle), sn = sin(angle);
        vec2 folded = pivot + vec2(cs * d.x - sn * d.y, sn * d.x + cs * d.y);
        vec2 farSide = vec2(1.58, 0.0) + folded * vec2(0.66, 1.17);
        float core = 1.0 - smoothstep(0.65, 1.72, rn);
        float rim = 1.0 - smoothstep(rMaxPx * 0.60, max(1.0, rMaxPx), r);
        vec2 offset = mix(q, mix(outer, farSide, core), rim) * rs;
        // Only the local box was copied. Taper the displacement if a ray would leave the screen
        // or the capture, instead of stretching the last copied pixel into a stripe.
        vec2 delta = offset - (px - c);
        vec2 lo = max(vec2(0.5), c - rMaxPx - 0.5);
        vec2 hi = min(texSize - 0.5, c + rMaxPx + 0.5);
        vec2 room = mix(px - lo, hi - px, step(vec2(0.0), delta));
        vec2 reach = (max(room, vec2(0.0)) + 0.0001) / max(abs(delta), vec2(0.0001));
        float travel = clamp(min(reach.x, reach.y), 0.0, 1.0);
        vec2 samplePx = clamp(px + delta * travel, lo, hi);
        vec3 refracted = texture2D(srcTex, samplePx / texSize).rgb;
        float shadow = length((q - vec2(0.20, -0.18)) * vec2(0.96, 1.22));
        float transmission = smoothstep(0.14, 1.02, shadow);
        gl_FragColor = vec4(mix(base, refracted * transmission, fade), 1.0);
        return;
    }
    // ~2px anti-aliased horizon edge: solid horizon color inside, blends into the lens outside.
    float horizonMask = 1.0 - smoothstep(rsPx - 1.0, rsPx + 1.0, r);
    if (horizonMask >= 0.999) {
        vec3 outRGB = mix(base, horizonColor, fade);
        gl_FragColor = vec4(outRGB, 1.0);
        return;
    }

    float band = max(1.0, rMaxPx - rsPx);
    float innerFeather = max(2.0, rsPx * 0.06);
    float outerFeather = max(8.0, min(band * 0.6, rMaxPx * 0.5));
    float overlapPx = max(0.6, rsPx * 0.02);
    float shrinkPx  = max(1.0, band * 0.03);
    float rsInnerStart = rsPx - overlapPx;
    float rMaxEff = max(rsPx + innerFeather + 1.0, rMaxPx - shrinkPx);
    float wIn  = smoothstep(rsInnerStart, rsPx + innerFeather, r);
    float wOut = 1.0 - smoothstep(rMaxEff - outerFeather, rMaxEff, r);
    float lensWRaw = clamp(wIn * wOut, 0.0, 1.0);
    float lensW = lensWRaw * lensWRaw * (3.0 - 2.0 * lensWRaw);

    vec2 suv = deflectToSourceUV(uv, lensW);
    suv = clamp(suv, vec2(0.0), vec2(1.0));
    vec3 rgb = texture2D(srcTex, suv).rgb;

    float x = thetaE / max(r, 1.0);
    float invDet = abs(1.0 - x*x*x*x);
    float mu = 1.0 / max(0.25, invDet);
    float muClamped = min(mu, 4.0);
    float boost = mix(1.0, muClamped, clamp(magStrength, 0.0, 1.0) * lensW);
    rgb = min(vec3(1.0), rgb * boost);

    float coreTakeover = smoothstep(rsPx + innerFeather * 0.2, rsPx + innerFeather * 0.6, r);
    float strength = clamp(max(lensW, 1.0 - coreTakeover) * fade, 0.0, 1.0);
    vec3 outRGB = mix(base, rgb, strength);
    outRGB = mix(outRGB, horizonColor, horizonMask * fade);
    gl_FragColor = vec4(outRGB, 1.0);
}
