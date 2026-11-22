#version 120
varying vec2 local;
void main() {
    local = gl_Vertex.xy;
    gl_Position = ftransform();
}
