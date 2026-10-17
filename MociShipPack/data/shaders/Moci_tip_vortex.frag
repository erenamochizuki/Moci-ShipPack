#version 420

in vec2 fragUV;

uniform sampler2D tex;
uniform vec4 colorStart;
uniform vec4 colorEnd;

uniform vec2 size;
uniform float time;

out vec4 fragColor;

void main() {

    vec2 centeredUV = fragUV - vec2(0.5, 0.5);

    float a = size.y * 0.01;
    float xLimit = -a * pow(centeredUV.y, 2.0) + 0.5;

    if (centeredUV.x > xLimit) {
        discard;
    }

    float backY = centeredUV.y;
    if (xLimit < 0.5) {
        float expectedX = centeredUV.x;
        float expectedY = (expectedX - 0.5) / -a;
        expectedY = sqrt(expectedY);

        backY = centeredUV.y / expectedY * 0.5;
    }

    backY += 0.5;
    vec2 mappedUV = vec2(fragUV.x + time, backY);

    float alpha = fragUV.x;
    vec4 color = mix(colorEnd, colorStart, fragUV.x);

    fragColor = texture(tex, mappedUV) * color * alpha;
}
