#version 110

uniform sampler2D shieldTex;
uniform sampler2D fxTex;
uniform float type;
uniform vec4 state;
uniform vec3 gnColor;
uniform float fluxLevel;
uniform sampler2D normalTex;
uniform vec4 normalCtrl;
uniform vec3 normalLightDir;

varying vec2 fragUV;

const vec3 LOW_FLUX_COLOR = vec3(55.0, 145.0, 255.0) / 255.0;
const vec3 HIGH_FLUX_COLOR = vec3(255.0, 20.0, 45.0) / 255.0;

vec2 recoverShieldUV(float size, vec2 uv, vec2 rel) {
    return (rel * 256.0 + (uv - 0.5) * 2.0 * size) / 256.0;
}

vec4 applyNormalRelief(vec2 uv, vec3 baseColor) {
    float alphaMul = 1.0;
    if (normalCtrl.x <= 0.5) {
        return vec4(baseColor, alphaMul);
    }

    vec3 n = texture2D(normalTex, uv).xyz * 2.0 - 1.0;
    float lenN = max(length(n), 0.0001);
    n = n / lenN;

    vec3 l = normalize(normalLightDir);
    float ndl = dot(n, l);
    float flatNdl = l.z;
    float relief = ndl - flatNdl;

    float reliefSignal = 1.0 + relief * normalCtrl.y;
    float lighting = clamp(reliefSignal, 0.75, 2.0);
    alphaMul = clamp(1.0 + max(relief, 0.0) * normalCtrl.y, 1.0, 2.0);
    return vec4(baseColor * lighting, alphaMul);
}

void main() {
    vec4 col = texture2D(shieldTex, fragUV);
    float alpha = 0.0;
    vec3 finalColor = vec3(0.2, 0.4, 1.0);

    if (type <= 0.5) {
        col.w = 0.3;
        float life = state.x; // 0到1的扩散动画进度
        float shieldRadius = state.y; // 扩散带目标半径，决定最远扩散到哪里
        float quadRadius = state.z; // 当前护盾quad的半径，用于把UV距离换算成世界距离
        float combinedBaseAlpha = state.w; // 当前护盾整体透明度

        // Java侧会把fragUV对应到承载护盾的quad半径，这里再按护盾半径推进扩散带。
        float dist = length((fragUV - 0.5) * 2.0) * quadRadius; // 当前像素到护盾中心的世界距离
        float bandSize = max(1.0, clamp(shieldRadius * 0.2, 12.0, 48.0) * ((life < 0.1) ? life * 10.0 : 1.0)); // 扩散带宽度

        float effectiveLife = life; // 把前半程映射为外扩、后半程映射为回收
        float brightness = 1.0; // 回收阶段略微提亮，避免视觉上太闷
        if (life <= 0.5) {
            effectiveLife = life * 2.0;
        } else {
            effectiveLife = 2.0 - life * 2.0;
            brightness = 1.3;
        }

        float startDist = effectiveLife * shieldRadius; // 扩散带起始位置
        alpha = sin(3.1415926 * (dist - startDist) / bandSize);
        if (dist < startDist || dist > startDist + bandSize) {
            alpha = 0.0;
        }
        alpha = alpha * combinedBaseAlpha * brightness;
        if (alpha > 1.0) {
            alpha = 1.0;
        }

        finalColor = mix(LOW_FLUX_COLOR, HIGH_FLUX_COLOR, clamp(fluxLevel, 0.0, 1.0));
        if (life > 0.5) {
            finalColor = mix(finalColor, vec3(1.0, 1.0, 1.0), 0.2);
        }
    } else if (type <= 2.5) {
        float life = state.x;
        float size = state.y;
        vec2 relUV = state.zw;
        float combinedShieldAlphaBase = type - 1.0;
        vec2 sampleUV = recoverShieldUV(size * 0.5, fragUV, relUV);
        col = texture2D(shieldTex, sampleUV);
        vec4 col2 = texture2D(fxTex, fragUV);
        col = col * col2;
        float dist = length(fragUV - 0.5) * size;
        alpha = sin(3.1415926 * (dist - 0.5 * life * size) / 0.5 * life * size);
        if (dist > size) {
            alpha = 0.0;
        }
        if (dist <= 0.5 * life * size) {
            alpha = 1.0;
        }
        alpha = alpha * combinedShieldAlphaBase;
        if (alpha > 1.0) {
            alpha = 1.0;
        }

        finalColor = mix(LOW_FLUX_COLOR, HIGH_FLUX_COLOR, clamp(fluxLevel, 0.0, 1.0));
    } else {
        alpha = 1.0;
        finalColor = gnColor + vec3(fluxLevel * 0.0001);
    }

    vec4 relief = applyNormalRelief(fragUV, finalColor);
    gl_FragColor = vec4(relief.rgb * col.xyz, col.w * alpha * relief.a);
}
