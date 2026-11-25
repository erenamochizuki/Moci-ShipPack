#version 110

// Source emission only. The later screen-space lens bends this disk and the scene together.
// v_pos is the quad-local position in -1..1, so every radius here is relative to the quad half size.
// Keep this file ASCII only: non-ASCII bytes can break shader compilation.

uniform float u_time;
uniform float u_phase;   // accumulated orbital angle for this hole, radians
uniform vec4 u_shape;    // x: horizon radius, y: cloud outer radius, z: death progress 0..1, w: random seed
uniform float u_alpha;   // overall brightness (fade in/out, viewport alpha)
uniform float u_spin;    // 1.0 = counter-clockwise, -1.0 = clockwise
uniform vec3 u_colCore;
uniform vec3 u_colMid;
uniform vec3 u_colEdge;
uniform float u_growth;  // 0 at launch size, approaches 1 as the hole grows (mass)

varying vec2 v_pos;

// Near-horizon emission. Kept moderate on purpose: this pass is added straight into the frame
// before the lens captures it, so anything brighter saturates to flat white and buries both the
// spiral structure and the shadow edge. The lens draws the bright photon ring itself.
const float GLOW_FALLOFF = 0.9;        // inner glow e-folding distance, in horizon radii
const float GLOW_BASE = 0.10;
const float GLOW_DENSITY = 0.42;
const float LIP_MIN = 0.25;            // inner lip brightness on the cool side
const float LIP_MAX = 0.80;            // inner lip brightness on the hot side

const float PI = 3.14159265;
const float TAU = 6.28318531;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(vec2 p) {
    float sum = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 4; i++) {
        sum += amp * vnoise(p);
        p = p * 2.03 + vec2(17.3, 9.1);
        amp *= 0.5;
    }
    return sum;
}

vec2 rot(vec2 p, float a) {
    float c = cos(a);
    float s = sin(a);
    return vec2(c * p.x - s * p.y, s * p.x + c * p.y);
}

// The hot side moves much less than the material orbiting it.
vec2 brightDir(float t, float seed) {
    float a = PI + 0.35 * sin(t * 0.23 + seed);
    return vec2(cos(a), sin(a));
}

vec3 diskPass(vec2 p, float r, vec2 dir, float rs, float cloudEnd, float death, float seed, float flash) {
    float t = u_time;
    float rn = r / rs;
    float ang = atan(p.y, p.x + 0.000001);

    // Log-polar radius: the texture scale grows with distance, so the arms look alike at every radius.
    float rho = rn;
    if (rn > 1.0) rho = 1.0 + 1.25 * log(rn);
    vec2 q = dir * rho;

    // Two cloud layers twisted into spirals by radius; each one rotates rigidly at its own speed.
    vec2 q1 = rot(q, u_spin * (1.30 * rho - u_phase));
    vec2 q2 = rot(q, u_spin * (0.70 * rho - 0.58 * u_phase) + 1.7);
    float n1 = fbm(q1 * 1.35 + vec2(seed, seed * 0.37));
    float n2 = fbm(q2 * 2.90 + vec2(seed * 1.31, t * 0.05));

    // Three main arms that follow the first cloud layer and are perturbed by it.
    float arms = 0.5 + 0.5 * cos(3.0 * (u_spin * ang + 1.30 * rho - u_phase) + 5.0 * (n1 - 0.47));
    arms = arms * arms * (3.0 - 2.0 * arms);

    float cloudTex = 0.55 * n1 + 0.45 * n2;
    float dens = smoothstep(0.24, 0.68, cloudTex) * mix(0.45, 1.0, arms) + 0.15 * arms + 0.08;

    // Radial envelopes: x is 0 at the horizon and 1 at the cloud edge.
    float x = clamp((r - rs) / max(cloudEnd - rs, 0.0001), 0.0, 1.0);
    float envCloud = (1.0 - x) * (1.0 - x);
    float glow = exp(-max(rn - 1.0, 0.0) / GLOW_FALLOFF);

    // One hot inner lip that falls off into the disk, not a second detached band.
    float lop = 0.5 + 0.5 * dot(dir, brightDir(t, seed));
    float hotSide = lop * lop;
    float lip = exp(-max(rn - 1.0, 0.0) / mix(0.12, 0.37, hotSide));
    lip *= mix(LIP_MIN, LIP_MAX, hotSide) * (1.0 + 0.35 * sin(PI * death));

    vec3 cloudCol = mix(u_colEdge, u_colMid, smoothstep(0.0, 0.55, envCloud));
    vec3 rgb = cloudCol * envCloud * dens * 0.92
             + mix(u_colMid, u_colCore, 0.65) * glow * (GLOW_BASE + GLOW_DENSITY * dens)
             + u_colCore * lip;

    // Anamorphic lens-flare streak through the centre (always screen-horizontal), kept at least
    // ~1.5 pixels thick so it does not shimmer when the hole is small or the view is zoomed out.
    // It grows stronger with mass: brighter core line, wider soft wing; the quad (and so the
    // streak length) is also lengthened on the Java side. The burst flash briefly blows it out.
    float g = clamp(u_growth, 0.0, 1.0);
    float gEase = g * g * (3.0 - 2.0 * g);
    float px = fwidth(v_pos.y);
    float h = max((0.10 + 0.05 * gEase) * rs, 1.5 * px);
    float ay = abs(p.y);
    float lenFall = 1.0 - smoothstep(0.0, 1.0, abs(p.x));
    float wing = 0.22 + 0.45 * gEase;
    float streak = (exp(-ay / h) + wing * exp(-ay / (h * 5.0))) * lenFall * lenFall;
    float streakGain = mix(0.25, 1.15, gEase) * (1.0 + 2.5 * flash);
    rgb += mix(u_colMid, u_colCore, 0.8) * streak * streakGain;

    // Filaments: short-lived lightning-like wisps that strike two opposite sectors at a time.
    float slotLen = 0.8;
    float slot = floor(t / slotLen);
    float strike = step(0.4, hash(vec2(slot, seed)));
    if (strike > 0.5) {
        float ph = fract(t / slotLen);
        float secAng = hash(vec2(slot, seed + 3.1)) * TAU;
        float dA = abs(mod(ang - secAng + PI, TAU) - PI);
        dA = min(dA, PI - dA);
        float sector = 1.0 - smoothstep(0.22, 0.55, dA);
        float radial = smoothstep(1.3, 2.0, rn) * (1.0 - smoothstep(0.55, 0.9, x));
        float flick = sin(PI * ph) * (0.65 + 0.35 * sin(t * 80.0));
        float n3 = fbm(q1 * 2.6 + vec2(slot * 7.7, seed));
        float wisp = 1.0 - smoothstep(0.0, 0.03, abs(n3 - 0.47));
        rgb += u_colCore * wisp * sector * radial * flick * 0.9;
    }

    // Fade to nothing before the quad border so the quad itself never shows. Uses the real quad
    // position (v_pos), because during the burst p is a shrunken sampling position.
    float border = 1.0 - smoothstep(0.90, 1.0, max(abs(v_pos.x), abs(v_pos.y)));
    // A faint, continuous source sheet: cutting a second black silhouette into the
    // input projects a spurious black crescent. Only the lens owns the shadow.
    float inner = 1.0 - smoothstep(0.12, 1.35, rn);
    rgb *= 1.0 - 0.48 * inner;
    return border * rgb / (1.0 + 0.40 * inner * rgb);
}

void main() {
    vec2 p = v_pos;
    float r = length(p);
    float rs = max(u_shape.x, 0.0001);
    float cloudEnd = u_shape.y;
    float death = clamp(u_shape.z, 0.0, 1.0);
    float seed = u_shape.w;
    vec2 dir = p / max(r, 0.00001);

    // Burst timeline (death 0..1): a sharp flash at the very start, everything else eases out.
    float e = 1.0 - pow(1.0 - death, 3.0);
    float alive = 1.0 - death;
    float onset = smoothstep(0.0, 0.04, death);
    float flash = onset * exp(-death * 9.0);

    // Accretion matter is flung outward: sample the disk at a shrunken position so it expands,
    // and let it thin out faster than the shock ring.
    float expand = 1.0 + 1.6 * e;
    vec3 rgb = diskPass(p / expand, r / expand, dir, rs, cloudEnd, death, seed, flash) * alive * alive;

    if (death > 0.0) {
        float border = 1.0 - smoothstep(0.90, 1.0, max(abs(p.x), abs(p.y)));

        // Core flash.
        float core = exp(-max(r - 0.5 * rs, 0.0) / (2.5 * rs + 0.04));
        rgb += u_colCore * core * flash * 2.2;

        // Ragged shock ring racing out from the horizon past the old cloud edge.
        float ringR = mix(rs, cloudEnd * 2.6, e);
        float ringW = mix(0.02, 0.09, e);
        float ragged = 0.55 + 0.9 * fbm(dir * 3.0 + vec2(seed, death * 2.0));
        float dr = (r - ringR) / ringW;
        float ring = exp(-dr * dr) * ragged * pow(alive, 1.4) * onset;
        rgb += mix(u_colMid, u_colCore, 0.7) * ring * 1.4 * border;

        // Radial debris jets inside the ring: noise picks uneven directions, widths and lengths,
        // so it never reads as evenly spaced spokes.
        float jn = fbm(dir * 6.0 + vec2(seed * 0.7, seed));
        float jet = smoothstep(0.56, 0.78, jn);
        float jetLen = ringR * (0.45 + 0.55 * smoothstep(0.5, 0.85, jn));
        float spRadial = smoothstep(rs, rs * 1.5, r) * (1.0 - smoothstep(jetLen * 0.6, jetLen, r));
        rgb += u_colCore * jet * spRadial * onset * alive * alive * 0.9 * border;
    }

    gl_FragColor = vec4(rgb * u_alpha, 1.0);
}
