#version 120
varying vec2 p;
uniform int mode;             // 0 scorch, 1 cross flare, 2 portal
uniform vec4 params;          // band/aspect, opening, seconds, seed
uniform vec4 tint;            // RGB and intensity
uniform vec4 contour[24];     // 96 radii shared with the fixed-function fallback

float sampleRadius(int i) {
    return contour[i/4][i-(i/4)*4];
}
void main() {
    vec3 light=vec3(0.0); float cover=0.0;
    if (mode==0) {
        float u=fract(atan(p.y,p.x+.000001)/6.2831853+1.0)*96.0;
        int i=int(floor(u));
        float edge=mix(sampleRadius(i),sampleRadius(i==95?0:i+1),fract(u));
        float d=length(p)-edge, w=params.x;
        cover=.93*(1.0-smoothstep(-.012,.018,d));
        // A continuous inner crust and a narrow luminous outer lip.
        float crust=smoothstep(-w,-.18*w,d)*(1.0-smoothstep(-.12*w,.015,d));
        light=tint.rgb*(.43*crust+.90*exp(-abs(d)*70.0)+.28*exp(-abs(d)*14.0));
    } else if (mode==1) {
        float streak=exp(-abs(p.y)*82.0-abs(p.x)*2.7), spike=exp(-abs(p.x)*85.0-abs(p.y)*8.3);
        float core=exp(-dot(p/vec2(.115,.056),p/vec2(.115,.056)));
        light=tint.rgb*(1.65*streak+.95*spike)+mix(tint.rgb,vec3(1.0),.82)*core;
    } else {
        vec2 e=p/vec2(params.x*(.18644+.81356*params.y),.01+.99*params.y);
        float a=atan(e.y,e.x+.000001), t=params.z, seed=params.w;
        float ripple=smoothstep(0.0,.38,e.x)*(.012*sin(8.0*a-3.5*t+seed)+.008*sin(17.0*a+4.0*t));
        float d=length(e)-1.0-ripple, v=smoothstep(0.0,.17,params.y);
        cover=v*.96*(1.0-smoothstep(-.06,.02,d));
        float crest=.70+.30*sin(3.0*a-4.0*t+seed);
        light=v*tint.rgb*(exp(-abs(d)*56.0)+mix(vec3(.60,.14,.54),vec3(.22,.56,.83),crest)*exp(-abs(d)*10.0)
               +vec3(.20,.17,.31)*exp(-abs(d+.13+.025*sin(5.0*a+t))*22.0));
    }
    gl_FragColor=vec4(light,cover)*tint.a;
}
