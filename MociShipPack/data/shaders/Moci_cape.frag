#version 420

in vec2 fragUV;
in float waveOffset;

uniform sampler2D tex;
uniform vec4 colorTint;
uniform vec2 widthRange;  // x: front width ratio, y: tail width ratio
uniform float foldCount;
uniform float time;

out vec4 fragColor;

void main() {
    // Width at the current position (gradient from front to tail)
    float widthMult = mix(widthRange.x, widthRange.y, fragUV.x);
    
    // Distance from center, used to clip into a trapezoid
    vec2 centeredUV = fragUV - vec2(0.5, 0.5);
    float maxY = widthMult * 0.5;
    
    // Clip the part beyond the width
    if (abs(centeredUV.y) > maxY) {
        discard;
    }
    
    // Remap UV to fit the varying width
    vec2 mappedUV = vec2(fragUV.x, (centeredUV.y / widthMult) + 0.5);
    
    // Add fold shading
    float foldShading = sin(fragUV.x * foldCount * 3.14159 + time * 0.5) * 0.15 + 0.85;
    foldShading = mix(1.0, foldShading, fragUV.x * 0.7); // folds are subtle near the front
    
    // Edge fade
    float edgeFade = 1.0 - pow(abs(centeredUV.y) / maxY, 2.0);
    
    // Length fade (tail is more transparent)
    float lengthFade = 1.0 - pow(fragUV.x, 1.5) * 0.3;
    
    // Sample the texture
    vec4 texColor = texture(tex, mappedUV);
    
    // Combine all effects
    vec4 finalColor = texColor * colorTint;
    finalColor.rgb *= foldShading;
    finalColor.a *= edgeFade * lengthFade;
    
    fragColor = finalColor;
}
