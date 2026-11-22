#version 110

uniform sampler2D maskTex, normalTex;
uniform float strength;
varying vec2 maskUV, normalUV;
varying vec4 ribbon;

void main() {
    vec4 sampleN = texture2D(normalTex, normalUV);
    vec3 n = sampleN.rgb * 2.0 - 1.0;
    n /= max(length(n), 0.0001);
    // FilmShield's steady (not hit-flash) relief, relative to an unperturbed hull.
    vec3 lightDir = normalize(vec3(0.35, -0.75, 0.75));
    float relief = (dot(n, lightDir) - lightDir.z) * strength * sampleN.a;
    float lighting = clamp(1.0 + relief, 0.75, 2.0);
    float alphaGain = clamp(1.0 + max(relief, 0.0), 1.0, 2.0);
    float alpha = min(1.0, 2.0 * texture2D(maskTex, maskUV).a * ribbon.a);
    gl_FragColor = vec4(ribbon.rgb * lighting, min(1.0, alpha * alphaGain));
}
