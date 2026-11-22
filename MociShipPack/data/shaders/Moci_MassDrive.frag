#version 120
uniform sampler2D fittedShip;
uniform sampler2D scene;
uniform vec2 units;
uniform vec2 gate;
uniform vec2 axis;
uniform vec4 screenRect;
uniform vec2 pixelsPerUnit;
uniform float stampWidth;
uniform float noseX;
uniform float age;
uniform float trailAge;
uniform float seed;
uniform float leaving;
uniform float fade;
varying vec2 local;
float pwHash(vec2 q) {
    return fract(sin(dot(q, vec2(127.1, 311.7))) * 43758.5453);
}
float pwCloud(vec2 p) {
    vec2 cell = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(pwHash(cell), pwHash(cell + vec2(1.0, 0.0)), f.x),
               mix(pwHash(cell + vec2(0.0, 1.0)), pwHash(cell + vec2(1.0)), f.x), f.y);
}
float mdGaussian(float x) {
    return exp(-x * x);
}
mat2 mdTurn(float a) {
    float c = cos(a), s = sin(a);
    return mat2(c,s,-s,c);
}
float mdGrain(vec2 p) {
    const mat2 shift = mat2(1.61,1.17,-1.17,1.61);
    return 0.55 * pwCloud(p) + 0.28 * pwCloud(shift*p+8.6) + 0.17 * pwCloud(shift*shift*p+21.3);
}
void mdFilm(vec2 q, float age, float spin, vec2 pattern, out vec2 offset, out vec3 light) {
    offset = vec2(0.0);
    light = vec3(0.0);
    if (age < -0.36 || age > 0.81) return;
    float release = max(age,0.0);
    float loadUp = smoothstep(-0.36,-0.015,age);
    float life = 1.0 - smoothstep(0.16,0.80,release);
    float radius = 0.10 + 0.27*loadUp + 0.52*(1.0-exp(-6.5*release));
    vec2 p = q / radius;
    float r = length(p);
    if (r > 2.25) return;
    vec2 dir = p/max(0.001,r);
    float ageTwist = spin*(1.3*age + 2.85*exp(-1.45*r));
    vec2 swept = mdTurn(ageTwist + pattern.x * 6.28318) * dir;
    float knot = mdGrain(swept*(1.85 + 0.95*pattern.y) + pattern*8.2 + vec2(2.1,0.31*age));
    // Keep the circular opening stable; let the texture and highlights carry the swirl.
    float crease = 0.72 + 0.10*(knot-0.50);
    float z = r-crease;
    vec2 sheet = mdTurn(spin*(age*0.82-3.4*z)) * swept;
    float eddies = mdGrain(sheet*4.1 + pattern*5.4 + vec2(z*3.0,1.3));
    float field = z + 0.08*(eddies-0.5) + 0.016*sin(9.0*sheet.y+7.0*eddies);
    float rag = mdGrain(sheet*(6.8+2.7*pattern.x) + vec2(2.4,age*0.45) + pattern.yx*4.3);
    float holes = smoothstep(0.22+0.26*release,0.64+0.14*release,0.47*knot+0.53*eddies+0.20*sheet.x*sheet.y);
    float sheetDensity = exp(-field*field/0.044) * holes;
    float crossFold = field + 0.085 + 0.13*(knot-0.5) + 0.025*sin(14.0*sheet.x);
    float threads = mdGaussian(field/(0.010+0.013*rag)) + 0.65*mdGaussian(crossFold/0.016);
    float ripple = mdGaussian((field-0.10*(eddies-0.3))/0.067);
    float fray = smoothstep(0.29,0.72,rag);
    float tearAge = smoothstep(0.07,0.42,release);
    float tears = mdGrain(3.1*sheet + vec2(5.8+1.4*release,z*12.0));
    float intact = mix(1.0,smoothstep(0.38,0.64,tears),0.94*tearAge);
    float power = (0.22+1.4*loadUp)*life*intact;
    float glint = threads*(0.22+1.6*fray)*holes;
    vec3 ink = mix(vec3(0.12,0.31,0.59),vec3(0.32,0.54,0.75),knot);
    light = power*(ink*sheetDensity*(0.55+1.45*rag) + mix(vec3(0.30,0.39,0.82),vec3(0.55,0.86,1.0),fray)*glint + vec3(0.24,0.49,0.74)*ripple*holes);
    float rim = mdGaussian((r-0.72)/0.065)*(0.80+0.20*fray);
    light += vec3(0.32,0.62,0.86)*rim*(0.22+1.4*loadUp)*life*mix(1.0,intact,0.5);
    float waveR = 0.20 + 2.0*release;
    float waveBand = mdGaussian((length(q)-waveR)/0.096)*exp(-4.8*release)*smoothstep(0.0,0.045,age);
    const float lensRange = 0.75;
    vec2 lensP = p/lensRange;
    float lensR = r/lensRange;
    float fieldLife = loadUp*life*(1.0-smoothstep(1.45,2.22,lensR));
    float twist = spin*fieldLife*(0.70*exp(-1.25*lensR*lensR) + 0.33*mdGaussian((lensR-0.91)/0.43));
    float radial = fieldLife*(0.22*mdGaussian((lensR-0.82)/0.47) - 0.10*mdGaussian((lensR-1.32)/0.32));
    vec2 delta = (mdTurn(twist)*lensP*(1.0+radial)-lensP)*(radius*lensRange);
    offset = delta;
    float lensWave = mdGaussian((length(q)/lensRange-waveR)/0.096);
    offset += dir*lensWave*(0.085*lensRange)*exp(-4.8*release)*smoothstep(0.0,0.045,age)
            *life*(1.0-smoothstep(1.64,2.22,lensR));
    float fleckRadius = length(p) - (crease + release*(0.55 + 0.50*knot));
    float flecks = smoothstep(0.62,0.80,mdGrain(sheet*35.0 + vec2(4.2,z*30.0)));
    light += vec3(0.36,0.63,0.90)*flecks*mdGaussian(fleckRadius/0.10)*tearAge*life;
    light += vec3(0.12,0.17,0.23)*waveBand*(0.4+0.6*knot);
}
float mdEventRandom(vec2 key) {
    vec3 p = fract(vec3(key.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}


vec3 mdParallelTrails(vec2 p, float age, float seed, float pixelWidth) {
    vec3 light = vec3(0.0);
    for (int i = 0; i < 24; i++) {
        vec2 key = vec2(float(i)+1.0,seed);
        float depth = mdEventRandom(key+2.3);
        float lane = (float(i) + mdEventRandom(key+5.27)) / 24.0;
        float y = mix(-0.64,0.64,lane);
        float phase = mdEventRandom(key+7.1)*0.82;
        float lineAge = mod(age+phase,0.82);
        float start = 0.025 + 0.12*mdEventRandom(key+3.2);
        float ageInLane = max(lineAge-start,0.0);
        float acceleration = smoothstep(0.0,0.18,lineAge);
        float end = 0.17 + 0.35*depth;
        float fade = smoothstep(0.0,0.15,ageInLane)*(1.0-smoothstep(end-0.12,end,ageInLane));
        float x = mix(-1.72,0.67,mdEventRandom(key+4.2))
            + (0.12+depth*0.28)*ageInLane + (1.65+2.3*depth)*pow(max(lineAge+0.05,0.0),2.0);
        float span = 0.03 + (0.26+1.09*depth)*(0.15+0.85*acceleration);
        float behind = (x-p.x)/span;
        float width = 0.0025+0.0037*depth;
        float aa = max(width,0.60*pixelWidth);
        float crossLine = mdGaussian((p.y-y)/aa)*width/aa;
        float taper = smoothstep(-0.055,0.045,behind)*(1.0-smoothstep(0.05,1.0,behind));
        light += mix(vec3(0.27,0.43,0.66),vec3(0.70,0.81,0.95),depth)
            *crossLine*taper*fade*(0.41+0.68*depth);
    }
    return light;
}
vec4 fitted(vec2 point) {
    vec2 uv = point / stampWidth + 0.5;
    if (any(lessThan(uv,vec2(0.0))) || any(greaterThan(uv,vec2(1.0)))) return vec4(0.0);
    return texture2D(fittedShip,uv);
}

void main() {
    float gateUnit = sqrt(units.x*units.y);
    vec2 q = (local-gate)/gateUnit;
    vec2 p = local/units;
    vec2 lens = vec2(0.0);
    vec3 film = vec3(0.0);
    vec2 pattern = vec2(mdEventRandom(vec2(seed,2.3)),mdEventRandom(vec2(seed,9.14)));
    vec2 coords = leaving < 0.5 ? q : p;
    float pixel = max(fwidth(coords.x),fwidth(coords.y));
    float launch = 0.0;
    float squeeze = 0.0;
    float alpha = 1.0;
    vec3 backLight = vec3(0.0);
    vec3 frontLight = vec3(0.0);
    float front = smoothstep(-0.28,0.34,q.x+q.y*0.35);
    if (leaving < 0.5) {
        float spin = mix(0.82,1.18,pattern.y)*(pattern.x < 0.5 ? -1.0 : 1.0);
        mdFilm(q,age,spin,pattern,lens,film);
        // Entry refracts the live scene, which already contains the native hull from the apex onward.
        alpha = 0.0;
        float head = mix(-3.5,0.0,smoothstep(-0.36,0.0,age));
        float along = q.x-head;
        float halfBeam = (0.010+0.015*exp(-abs(along)*2.2))*mix(0.85,1.18,pattern.y);
        float incoming = exp(-max(-along,0.0)*1.95)*mdGaussian(max(along,0.0)/0.053)
            *mdGaussian(q.y/max(halfBeam,pixel*0.48))*(1.0-smoothstep(0.0,0.18,age));
        float latch = smoothstep(-0.24,-0.038,age)*(1.0-smoothstep(0.0,0.075,age));
        float u = clamp(-q.x/max(mix(0.39,0.58,pattern.x)*latch,0.001),0.0,1.0);
        float lead = smoothstep(0.0,0.07,u)*(1.0-smoothstep(0.87,1.0,u))*latch;
        float flash = smoothstep(-0.046,0.0,age)*exp(-max(age,0.0)*22.0);
        backLight = film*(1.0-0.52*front) + vec3(0.54,0.76,1.0)*(1.50*incoming+
            0.82*lead*mdGaussian((q.y-0.018*sin(5.1*u)*sin(3.14159*u))/max(0.009,0.48*pixel)));
        vec2 core = q;
        frontLight = film*0.52*front + vec3(0.56,0.81,1.0)*flash*
            (2.15*exp(-dot(core,core)/0.078)+0.75*mdGaussian(q.y/0.010)*exp(-abs(q.x)*2.3));
    } else {
        launch = smoothstep(-0.015,0.13,age);
        squeeze = smoothstep(-0.015,0.17,age);
        alpha = 1.0-smoothstep(0.074,0.19,age);
        backLight = mdParallelTrails(p,trailAge,seed,pixel);
    }
    vec2 shape = vec2(1.0+0.15*launch, mix(1.0,0.067,squeeze)/(1.0+2.1*max(age,0.0)*leaving));
    vec2 src = local/shape;
    vec4 hull = vec4(0.0);
    if (leaving > 0.5) hull = fitted(src);
    vec3 metal = hull.rgb;
    if (leaving > 0.5) {
        float armor = dot(metal,vec3(0.36,0.50,0.14));
        float sweep = smoothstep(-0.38,0.14,age);
        float stripe = mdGaussian(((src.x-noseX)/units.x/1.13+1.0-mix(-0.12,1.13,sweep))/0.122);
        metal += vec3(0.34,0.62,0.98)*stripe*hull.a*(0.38+1.2*armor);
        if (launch*alpha > 0.001) {
            for (int i=1;i<=10;i++) {
                float k = float(i)/10.0;
                vec4 sampleHull = fitted(src+vec2(k*0.12*launch*1.13*units.x,0.0));
                float shine = smoothstep(0.10,0.68,dot(sampleHull.rgb,vec3(0.36,0.50,0.14)));
                backLight += mix(sampleHull.rgb,sampleHull.a*vec3(0.42,0.67,0.96),0.48)
                    *shine*(1.0-0.8*k)*0.21*launch*alpha;
            }
        }
        float behind = (noseX*shape.x-local.x)/units.x;
        float trailPos = clamp(behind/mix(0.55,2.25,smoothstep(0.052,0.31,age)),0.0,1.0);
        float trail = (1.0-smoothstep(0.02,1.0,trailPos))*mdGaussian(min(behind,0.0)/0.079);
        float waist = max(pixel*0.60,mix(0.021,0.004,trailPos));
        float nose = mdGaussian(behind/0.092)*mdGaussian(p.y/0.041);
        frontLight = smoothstep(0.026,0.143,age)*(trail*(vec3(0.78,0.87,1.0)*1.72*mdGaussian(p.y/waist)
            +vec3(0.15,0.38,0.90)*mdGaussian(p.y/(waist*2.8)))+vec3(0.68,0.83,1.0)*nose);
        float wakeAge = max(age+0.08,0.0);
        float wakeGain = smoothstep(-0.08,0.08,age)*(1.0-smoothstep(0.16,0.55,age));
        for (int i=-1; i<=1; i++) {
            float k = float(i);
            float tailX = -0.49+(1.0+0.26*abs(k))*wakeAge*wakeAge*9.0;
            float tail = (tailX-p.x)/(0.12+0.82*smoothstep(0.0,0.31,wakeAge));
            float taper = smoothstep(-0.09,0.08,tail)*(1.0-smoothstep(0.08,1.0,tail));
            backLight += vec3(0.43,0.65,0.96)*mdGaussian((p.y-k*0.183)/max(pixel*0.60,0.0085-0.0025*abs(k)))
                *taper*wakeGain*(1.0-0.24*abs(k));
        }
    }
    vec3 base = vec3(0.0);
    float coverage = hull.a*alpha;
    if (dot(lens,lens) > 0.000000001) {
        vec2 offset = lens*gateUnit;
        vec2 shift = axis*offset.x+vec2(-axis.y,axis.x)*offset.y;
        vec2 uv = (gl_FragCoord.xy-screenRect.xy+shift*pixelsPerUnit)/screenRect.zw;
        base = texture2D(scene,clamp(uv,0.5/screenRect.zw,1.0-0.5/screenRect.zw)).rgb;
        coverage = 1.0;
    }
    vec3 light = backLight*(1.0-hull.a*alpha)+frontLight;
    vec3 rgb = base*(1.0-hull.a*alpha)+metal*alpha+(1.0-exp(-1.30*light));
    gl_FragColor = vec4(rgb*fade,coverage*fade);
}
