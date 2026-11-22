#version 110

// GN shield vertex shader
// Based on HSIShield.vert from the halo source, tuned for the GN shield

varying vec2 fragUV;

void main() {
    // Standard vertex transform
    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
    
    // Pass texture coordinates to the fragment shader
    fragUV = gl_MultiTexCoord0.xy;
}
