#version 120
// Reference reconstruction of the non-BlastWave nodes, not extracted EVE materials.
// Sphere intersections give the near and far walls different material coordinates.
uniform sampler3D clouds;
uniform vec2 radii;
uniform vec2 seed;
uniform float time;
uniform int part;          // 1: halo; 2: hot core; 3: cloud; 4: intersecting oblique fronts
uniform vec3 fronts;
uniform vec4 lowTint, steepTint, crossTint, crestTint;
uniform vec3 lowShape, steepShape, crossShape;
uniform vec4 lowTime, steepTime, crossTime;
uniform vec3 rimColor;
uniform vec3 coreColor;
uniform float gain;
varying vec2 localPos;

vec3 materialPoint(vec3 p) {
    float a = seed.x * 6.283185 + 0.20*time + 0.33*p.y;
    float c = cos(a), s = sin(a);
    p.xz = mat2(c,s,-s,c)*p.xz;
    return p*(part==2 ? .76 : 1.12) + vec3(seed.y,seed.x,seed.x*0.57) + time*vec3(.024,-.017,.031);
}

// Intersect the orthographic view ray with a bowed band in its own inclined plane.
// Return emission, view-space Z (towards viewer), extinction, hot-edge fraction.
vec4 sheet(vec2 xy, vec3 shape, vec4 timing, float id) {
    float spin=shape.x, opening=shape.y, radius=shape.z, delay=timing.x, end=timing.y;
    if (time < delay || time >= end || radius <= 0.0) return vec4(0.0);
    float attack = timing.z > 0.0 ? smoothstep(delay,delay+timing.z,time) : 1.0;
    float decay = timing.w > 0.0 ? 1.0-smoothstep(end-timing.w,end,time) : 1.0;
    float life = attack*decay;
    if (life < .002) return vec4(0.0);
    float a = spin + (seed.x-.5)*.36 + (.05+.025*id)*sin(time*1.8+id);
    float cosI = clamp(opening + .030*sin(time*2.1+id*1.7),.001,1.0);
    float sinI = sqrt(1.0-cosI*cosI);
    vec2 major = vec2(cos(a),sin(a));
    float u = dot(xy,major), h = dot(xy,vec2(-major.y,major.x));
    float size = radius * (.70 + .30*(1.0-exp(-3.0*max(time-delay,0.0))));
    float bow = .06*size*sin(2.9*u/size+id+.44*time);
    float v = (h-bow*sinI)/cosI;
    vec2 p = vec2(u,v)/size;
    float r = length(p);
    float aa = min(.045,.65*fwidth(r));
    if (r < .46 || r > 1.13) return vec4(0.0);
    vec2 d = p/r;
    float z = v*sinI - bow*cosI;
    vec3 lookup = vec3(p*.59+vec2(seed.x,.31*id), .32*id+.22*seed.y+time*.11);
    vec3 n = texture3D(clouds,lookup).rgb;
    vec3 fine = texture3D(clouds,lookup*2.65-vec3(.03*time,.08*time,0.0)).rgb;
    float edge = .89 + .072*(n.r-.5) + .047*sin(3.0*d.x+2.0*d.y+id+time*.51);
    float dist = r-edge;
    float crest = exp(-abs(dist)/(.017+aa));
    float veil = exp(-abs(dist+.080)/.096);
    float lit = smoothstep(.13,.82,n.r+.25*(fine.b-.5));
    float window = .12+.88*smoothstep(-.76,.64,dot(d,vec2(cos(1.9*id+.5),sin(1.9*id+.5))) + .75*(n.g-.5));
    float nearSide = smoothstep(-.38,.54,z/size);
    float back = mix(.28,1.0,nearSide);
    float folds = exp(-8.0*abs(n.g-.5+.27*(fine.r-.5)));
    float wake = exp(-abs(dist+.16+.055*(n.r-.5))/.13)*smoothstep(.33,.75,fine.g);
    float energy = life*window*back*(crest*(.65+1.1*lit) + veil*(.21+1.1*lit)*folds + .39*wake);
    return vec4(energy,z,clamp(life*nearSide*window*(.65*crest+.27*veil),0.0,.78), .30+.65*crest);
}

struct Front { vec3 light; float depth; float opacity; };
Front tinted(vec4 s, vec4 tint) {
    vec3 rgb=mix(tint.rgb,crestTint.rgb,s.w*crestTint.a);
    return Front(rgb*(s.x*tint.a),s.y,s.z*tint.a);
}
void orderZ(inout Front a,inout Front b) { if(a.depth>b.depth){Front tmp=a; a=b; b=tmp;} }
vec3 overSheet(vec3 behind,Front s) { return behind*(1.0-s.opacity) + s.light; }

void main() {
    vec2 xy = localPos / max(radii.y,0.001);
    float rr = dot(xy,xy);
    if (rr >= 1.0) discard;
    if (part == 4) {
        // Java supplies the traveling radius. Keep the projected crests lit through the crossing.
        Front a=tinted(vec4(0.0),lowTint), b=a, c=a;
        if(fronts.x>.5) a=tinted(sheet(xy,lowShape,lowTime,0.0),lowTint);
        if(fronts.y>.5) b=tinted(sheet(xy,steepShape,steepTime,1.0),steepTint);
        if(fronts.z>.5) c=tinted(sheet(xy,crossShape,crossTime,2.0),crossTint);
        orderZ(a,b); orderZ(b,c); orderZ(a,b);
        vec3 light=overSheet(overSheet(overSheet(vec3(0.0),a),b),c)*gain;
        gl_FragColor=vec4(light/(1.0+.24*light),0.0);
        return;
    }
    if (part == 1) {
        float r = sqrt(rr);
        float halo = exp(-4.2*rr) * (1.0-smoothstep(.69,1.0,r));
        gl_FragColor = vec4(coreColor * (halo*gain),0.0);
        return;
    }
    bool hot = part == 2;
    float r = sqrt(rr), zOut = sqrt(1.0-rr);
    float inner = hot ? .38 : .77;
    float zIn = sqrt(max(0.0,inner*inner-rr));
    float stepZ = (zOut-zIn)*.25;
    vec3 sum = vec3(0.0);
    float transmission = 1.0;
    // Four samples on each wall. Skip the empty middle. Front gas softens far-wall light.
    for (int i=0; i<8; ++i) {
        float k = mod(float(i),4.0)+.5;
        float z = i < 4 ? zOut-k*stepZ : -zIn-k*stepZ;
        vec3 p = vec3(xy,z);
        vec3 uvw = materialPoint(p);
        vec3 n = texture3D(clouds,uvw).rgb;
        vec3 fine = texture3D(clouds,uvw*2.47 + vec3(.07,.37,-.19)-time*.016).rgb;
        float sheet = abs((n.g-.5)*2.0 + (fine.r-.5)*.29);
        float curl = exp(-12.0*sheet);
        float density = smoothstep(.30,.70,n.r + .19*(fine.b-.5));
        float surface = .89+.15*(n.r-.40)+.075*(fine.g-.5);
        float wall = 1.0-smoothstep(surface-.11,surface+.035,length(p));
        float shadow = texture3D(clouds,uvw+vec3(-.032,.044,.052)).r;
        float lit = clamp(.49 + 2.2*(n.r-shadow) + .27*z, .13,1.12);
        float optical = stepZ*wall*density*(hot ? 7.5 : 6.5);
        vec3 tint = mix(rimColor,coreColor,clamp(lit*density+curl*.23,0.0,1.0));
        sum += transmission * optical * tint * (.14+lit*.95+curl*(hot ? 1.6 : 2.6));
        transmission *= exp(-optical*(hot ? .88 : .64));
    }
    if (hot) {
        sum *= .18+.75*exp(-2.8*rr);
        sum += coreColor * (exp(-9.0*rr)*1.6 + exp(-29.0*rr)*2.0);
    } else {
        sum *= .21 + .57*exp(-2.7*rr);
        sum += coreColor * (1.26*exp(-28.0*rr) + .29*exp(-6.0*rr));
        // A small family of luminous ribbons, clipped to arcs on the near wall.
        vec3 front = vec3(xy,zOut);
        vec3 m = texture3D(clouds,materialPoint(front)).rgb;
        float a = seed.x*6.283185 + .19*time;
        vec2 dir=vec2(cos(a),sin(a));
        vec2 q=vec2(dot(xy,dir),dot(xy,vec2(-dir.y,dir.x)));
        float curve=q.x + .30*front.z-.34*q.y*q.y + .22*(m.g-.5);
        float arc=exp(-abs(curve)*96.0)*smoothstep(-.85,-.18,q.y)*(1.0-smoothstep(.27,.86,q.y));
        float fork = exp(-abs(curve-.55*q.y-.08)*125.0)*smoothstep(-.15,.11,q.y)*(1.0-smoothstep(.28,.69,q.y));
        sum += coreColor * ((arc*1.38+fork*.76) * (.55+m.r) * exp(-.48*time));
    }
    // Soft shoulder preserves the colour in bright folds; only the hot little core clips.
    vec3 light = sum*gain;
    gl_FragColor = vec4(light/(1.0+.35*light),0.0);
}
