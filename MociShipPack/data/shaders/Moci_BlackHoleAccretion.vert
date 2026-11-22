#version 110

// Quad-local position in -1..1, passed in through the texture coordinate.
varying vec2 v_pos;

void main() {
    v_pos = gl_MultiTexCoord0.xy;
    gl_Position = ftransform();
}
