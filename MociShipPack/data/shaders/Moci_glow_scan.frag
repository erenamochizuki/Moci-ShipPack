#version 420

// 片段着色器 - 发光扫描效果
// 创建一条从上到下扫描的发光线

// 从顶点着色器传入的UV坐标
in vec2 fragUV;

// SDF纹理（Signed Distance Field）
uniform sampler2D tex;
// 扫描光颜色
uniform vec4 scanColor;
// 扫描线位置（0-1）
uniform float scanLine;
// 扫描线宽度
uniform float scanWidth;

// 输出颜色
out vec4 fragColor;

void main() {

    // 当前像素的Y坐标
    float y = fragUV.y;
    
    // 调整扫描线位置（考虑宽度）
    float adjustedCurrentLine = (1.0 + 2.0 * scanWidth) * scanLine - scanWidth;
    
    // 计算当前像素到扫描线的距离
    float pos = abs(y - adjustedCurrentLine);
    
    // 如果距离超过扫描线宽度，丢弃该像素
    if (pos > scanWidth) discard;
    
    // 从SDF纹理采样
    vec4 texColor = texture(tex, fragUV);
    float sdf = texColor.r;
    
    // 处理SDF值
    sdf -= 0.5;
    sdf = clamp(sdf * 2.0, 0.0, 1.0);
    sdf = smoothstep(1.0, 0.0, sdf);
    
    // 计算扫描线的渐变透明度
    float extraAlpha = 1.0 - smoothstep(0.0, 1.0, pos / scanWidth);
    
    // 最终颜色 = 扫描光颜色 * SDF遮罩 * 渐变透明度
    fragColor = scanColor * sdf * extraAlpha;
}
