#version 110
varying vec2 v_uv;
void main(){
gl_Position=ftransform();
v_uv=gl_MultiTexCoord0.xy;
}
