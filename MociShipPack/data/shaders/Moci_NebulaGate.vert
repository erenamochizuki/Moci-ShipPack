#version 120
varying vec2 p;
void main() {
    p = gl_Vertex.xy;
    gl_Position = ftransform();
}
