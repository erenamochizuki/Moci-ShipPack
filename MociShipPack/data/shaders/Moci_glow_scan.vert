#version 420

// Vertex shader - glow scan effect
// Transforms 2D quad vertices into screen space

layout (location = 0) in vec2 vertex;

// BoxUtil global data (viewport matrix, etc.)
layout (std140, binding = 0) uniform BUtilGlobalData
{
	mat4 gameViewport;
	vec4 gameScreenBorder;
};

// Model transform matrix
uniform mat4 modelMatrix;
// Sprite size
uniform vec2 size;
// Position offset
uniform vec2 offset;
// UV coordinate scale
uniform vec2 uv;

// UV coordinates passed to the fragment shader
out vec2 fragUV;

void main() {

	// UV mapping (by vertex ID)
	vec2 uvRotateMap[] = vec2[](vec2(uv.x, 0.0), uv, vec2(0.0, 0.0), vec2(0.0, uv.y));
	vec2 rotatedUv = uvRotateMap[gl_VertexID];

	fragUV = rotatedUv;

	// Compute vertex position
	vec4 resized = vec4(vertex * size * 0.5 + offset.xy, 0.0, 1.0);
	gl_Position = gameViewport * vec4((modelMatrix * resized).xy, 0.0, 1.0);
}
