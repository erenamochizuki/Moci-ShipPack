#version 110

// GN护盾顶点着色器
// 基于光环src的HSIShield.vert，针对GN护盾优化

varying vec2 fragUV;

void main() {
    // 标准的顶点变换
    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
    
    // 传递纹理坐标到片段着色器
    fragUV = gl_MultiTexCoord0.xy;
}
