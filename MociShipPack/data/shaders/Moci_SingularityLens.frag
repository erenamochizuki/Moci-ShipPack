#version 110
uniform sampler2D srcTex;
uniform vec2 center;
uniform vec3 rs_rlens_strength;
uniform vec2 texSize;
uniform float darkAlpha;
uniform float magStrength;
uniform vec3 horizonColor;
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
    vec2 px = uv * texSize;
    vec2 c = center * texSize;
    float r = length(px - c);

    vec3 base = texture2D(srcTex, uv).rgb;
    float fade = clamp(darkAlpha, 0.0, 1.0);
    if (r <= rsPx) {
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
    gl_FragColor = vec4(outRGB, 1.0);
}
