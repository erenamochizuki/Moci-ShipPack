#version 420

// Fragment shader - glow scan effect
// Draws a glowing line that sweeps from top to bottom

// UV coordinates from the vertex shader
in vec2 fragUV;

// SDF texture (Signed Distance Field)
uniform sampler2D tex;
// Scan light color
uniform vec4 scanColor;
// Scan line position (0-1)
uniform float scanLine;
// Scan line width
uniform float scanWidth;

// Output color
out vec4 fragColor;

void main() {

    // Y coordinate of the current pixel
    float y = fragUV.y;
    
    // Adjust scan line position (accounting for width)
    float adjustedCurrentLine = (1.0 + 2.0 * scanWidth) * scanLine - scanWidth;
    
    // Distance from the current pixel to the scan line
    float pos = abs(y - adjustedCurrentLine);
    
    // Discard pixels farther than the scan line width
    if (pos > scanWidth) discard;
    
    // Sample the SDF texture
    vec4 texColor = texture(tex, fragUV);
    float sdf = texColor.r;
    
    // Process the SDF value
    sdf -= 0.5;
    sdf = clamp(sdf * 2.0, 0.0, 1.0);
    sdf = smoothstep(1.0, 0.0, sdf);
    
    // Gradient alpha of the scan line
    float extraAlpha = 1.0 - smoothstep(0.0, 1.0, pos / scanWidth);
    
    // Final color = scan color * SDF mask * gradient alpha
    fragColor = scanColor * sdf * extraAlpha;
}
