#version 420

// 顶点着色器 - 发光扫描效果
// 用于将2D矩形顶点转换到屏幕空间

layout (location = 0) in vec2 vertex;

// BoxUtil全局数据（视口矩阵等）
layout (std140, binding = 0) uniform BUtilGlobalData
{
	mat4 gameViewport;
	vec4 gameScreenBorder;
};

// 模型变换矩阵
uniform mat4 modelMatrix;
// 精灵大小
uniform vec2 size;
// 位置偏移
uniform vec2 offset;
// UV坐标缩放
uniform vec2 uv;

// 输出到片段着色器的UV坐标
out vec2 fragUV;

void main() {

	// UV坐标映射（根据顶点ID）
	vec2 uvRotateMap[] = vec2[](vec2(uv.x, 0.0), uv, vec2(0.0, 0.0), vec2(0.0, uv.y));
	vec2 rotatedUv = uvRotateMap[gl_VertexID];

	fragUV = rotatedUv;

	// 计算顶点位置
	vec4 resized = vec4(vertex * size * 0.5 + offset.xy, 0.0, 1.0);
	gl_Position = gameViewport * vec4((modelMatrix * resized).xy, 0.0, 1.0);
}
