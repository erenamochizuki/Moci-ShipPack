#version 420

layout (location = 0) in vec2 vertex;
layout (std140, binding = 0) uniform BUtilGlobalData
{
	mat4 gameViewport;
	vec4 gameScreenBorder;
};

uniform mat4 modelMatrix;
uniform vec2 size;
uniform vec2 offset;
uniform vec2 uv;

out vec2 fragUV;

void main() {
	vec2 uvRotateMap[] = vec2[](vec2(uv.x, 0.0), uv, vec2(0.0, 0.0), vec2(0.0, uv.y));
	vec2 rotatedUv = uvRotateMap[gl_VertexID];

	fragUV = rotatedUv;

	vec4 resized = vec4(vertex * size * 0.5 + offset.xy, 0.0, 1.0);
	gl_Position = gameViewport * vec4((modelMatrix * resized).xy, 0.0, 1.0);
}
