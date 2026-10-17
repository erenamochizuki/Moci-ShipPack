#version 420

in vec2 fragUV;

uniform sampler2D tex;
uniform vec4 outlineColor;
uniform float outlineIntensity;
uniform float outlineSize;

out vec4 fragColor;

void main() {
    vec4 texColor = texture(tex, fragUV);

    float sdf = texColor.r;
    sdf -= 0.5;
    sdf = clamp(sdf * 2.0, 0.0, 1.0);
    sdf = smoothstep(1.0, 0.0, sdf);

    float threshold = 1.0 - outlineSize;
    if (sdf <= threshold) discard;

    float rate = (1.0 - outlineIntensity * threshold) * sdf / (1.0 - threshold)
        + threshold * (outlineIntensity - 1.0) / (1.0 - threshold);
    fragColor = clamp(outlineColor * rate, 0.0, 1.0);
}
