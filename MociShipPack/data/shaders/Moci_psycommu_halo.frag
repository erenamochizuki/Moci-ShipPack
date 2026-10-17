#version 420

in vec2 fragUV;

uniform sampler2D maskTex;
uniform sampler2D patternTex;
uniform sampler2D fogTex;
uniform vec4 outlineColor;
uniform float outlineAlpha;
uniform float outlineIntensity;
uniform float outlineSize;
uniform float time;
uniform vec4 patternUvRect;
uniform vec4 fogUvRect;

out vec4 fragColor;

vec2 remapAtlasUv(vec2 localUv, vec4 uvRect) {
    return vec2(
        mix(uvRect.x, uvRect.z, localUv.x),
        mix(uvRect.y, uvRect.w, localUv.y)
    );
}

void main() {
    float maskSample = texture(maskTex, fragUV).r;

    if (maskSample < 0.5) {
        discard;
    }

    float maxOutline = clamp(outlineSize, 0.001, 1.0);
    float outsideDistance = clamp((maskSample - 0.5) * 2.0, 0.0, 1.0);
    if (outsideDistance >= maxOutline) {
        discard;
    }

    float liftFromHull = smoothstep(0.0, maxOutline * 0.20, outsideDistance);
    float outerFade = 1.0 - smoothstep(maxOutline * 0.42, maxOutline, outsideDistance);
    float haloShape = liftFromHull * outerFade;
    if (haloShape <= 0.001) {
        discard;
    }

    vec2 patternUvLocal = fract(fragUV * 1.08 + vec2(time * 0.010, -time * 0.007));
    vec2 fogUvLocal = fract(fragUV * 1.55 + vec2(-time * 0.005, time * 0.008));
    vec2 patternUv = remapAtlasUv(patternUvLocal, patternUvRect);
    vec2 fogUv = remapAtlasUv(fogUvLocal, fogUvRect);

    vec4 pattern = texture(patternTex, patternUv);
    vec4 fog = texture(fogTex, fogUv);

    float patternLum = dot(pattern.rgb, vec3(0.3333333));
    float fogLum = dot(fog.rgb, vec3(0.3333333));
    float ripple = mix(patternLum, fogLum, 0.35);
    float textureAlpha = mix(0.78, 1.0, mix(pattern.a, fog.a, 0.45));

    float intensity = clamp(outlineIntensity, 0.0, 2.0);
    float alpha = outlineAlpha * haloShape * textureAlpha * (0.72 + intensity * 0.22);

    vec3 haloRgb = outlineColor.rgb * (0.88 + ripple * 0.18) * (0.82 + intensity * 0.18);
    fragColor = vec4(clamp(haloRgb, 0.0, 1.0), clamp(alpha, 0.0, 1.0));
}
