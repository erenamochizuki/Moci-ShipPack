#version 420

in vec2 fragUV;
in float waveOffset;

uniform sampler2D tex;
uniform vec4 colorTint;
uniform vec2 widthRange;  // x: 前端宽度比例, y: 末端宽度比例
uniform float foldCount;
uniform float time;

out vec4 fragColor;

void main() {
    // 计算当前位置的宽度（从前端到末端的渐变）
    float widthMult = mix(widthRange.x, widthRange.y, fragUV.x);
    
    // 计算中心距离，用于裁剪成梯形
    vec2 centeredUV = fragUV - vec2(0.5, 0.5);
    float maxY = widthMult * 0.5;
    
    // 裁剪超出宽度的部分
    if (abs(centeredUV.y) > maxY) {
        discard;
    }
    
    // 重新映射UV以适应变化的宽度
    vec2 mappedUV = vec2(fragUV.x, (centeredUV.y / widthMult) + 0.5);
    
    // 添加褶皱阴影效果
    float foldShading = sin(fragUV.x * foldCount * 3.14159 + time * 0.5) * 0.15 + 0.85;
    foldShading = mix(1.0, foldShading, fragUV.x * 0.7); // 前端褶皱不明显
    
    // 边缘渐变
    float edgeFade = 1.0 - pow(abs(centeredUV.y) / maxY, 2.0);
    
    // 长度渐变（末端更透明）
    float lengthFade = 1.0 - pow(fragUV.x, 1.5) * 0.3;
    
    // 采样纹理
    vec4 texColor = texture(tex, mappedUV);
    
    // 组合所有效果
    vec4 finalColor = texColor * colorTint;
    finalColor.rgb *= foldShading;
    finalColor.a *= edgeFade * lengthFade;
    
    fragColor = finalColor;
}
