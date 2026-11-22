#version 110

uniform vec4 normalRect;
varying vec2 maskUV, normalUV;
varying vec4 ribbon;

void main() {
    gl_Position = ftransform();
    maskUV = gl_MultiTexCoord0.xy;
    // Input vertices are normalized hull coordinates, independent of either texture's atlas rect.
    normalUV = normalRect.xy + gl_Vertex.xy * normalRect.zw;
    ribbon = gl_Color;
}
