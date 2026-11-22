package data.scripts.util;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.impl.campaign.terrain.AuroraRenderer;
import com.fs.starfarer.api.impl.campaign.terrain.RangeBlockerUtil;
import com.fs.starfarer.api.impl.campaign.terrain.RingRenderer;
import com.fs.starfarer.api.util.Misc;
import org.lazywizard.lazylib.CollisionUtils;
import org.lazywizard.lazylib.MathUtils;
import org.dark.shaders.light.LightShader;
import org.dark.shaders.light.StandardLight;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.util.glu.GLU;
import org.lwjgl.util.vector.Vector2f;
import data.scripts.weapons.render.Moci_ShaderManager;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * 通用工具类
 * 提供各种实用方法
 */
public class Moci_Util {
	// 战斗特效：draw 方法在 render 中调用，spawn 方法在 advance 中按战斗秒限频调用。
	/** 一次生成并复用整圈轮廓；不同实例不会互相改写形状。 */
	public static final class ScorchContour {
		private final FloatBuffer samples = BufferUtils.createFloatBuffer(96);
		private final float[] broad = new float[96], jagged = new float[96];
		private final float[] waveSin = new float[96], waveCos = new float[96];
		private float lastMix, lastSin, lastCos;
		private ScorchContour(float seed, int blocks, float depth, float chips, float roughness) {
			int n = Math.max(2, Math.min(12, blocks));
			float relief = Math.max(0f, Math.min(.70f, depth));
			float detail = Math.max(0f, Math.min(.25f, chips));
			roughness = Math.max(0f, Math.min(1f, roughness));
			// 先分出不等长的弧段，各段独立决定外肩高度及末端缺口，避免变成规则花瓣。
			Random rng=new Random(0x3de4ab79L*Float.floatToIntBits(seed));
			double[] edge=new double[n+1], shoulder=new double[n], notch=new double[n], turn=new double[n];
			for (int j=0;j<n;j++) {
				edge[j+1]=edge[j]+.48+1.22*rng.nextDouble();
				shoulder[j]=.14+.85*rng.nextDouble(); notch[j]=.18+.85*rng.nextDouble();
				turn[j]=.48+.26*rng.nextDouble();
			}
			double phase=rng.nextDouble();
			for (int i=0; i<96; i++) {
				double a=i*Math.PI/48, pos=((i/96.0+phase)%1)*edge[n];
				int j=0; while (j+1<n && pos>=edge[j+1]) j++;
				double u=(pos-edge[j])/(edge[j+1]-edge[j]);
				double top=shoulder[j]+.12*Math.sin(2*a-seed);
				double body=u<turn[j] ? top-(top+notch[j])*softStep(1-u/turn[j])
						: top-(top+notch[(j+1)%n])*softStep((u-turn[j])/(1-turn[j]));
				double bite=Math.max(0, Math.sin((n+3)*a+1.6*seed+.45*Math.sin(3*a))-.52)/.48;
				broad[i]=(float)(1+relief*body-detail*bite*bite);
				jagged[i]=(float)(1+roughness*v2Edge(i/96.0+.5,seed));
				// 两组绕整圈闭合的长波；角度项只算一次，各段有不同的运动方向，不会整圈一起缩放。
				waveSin[i]=(float)(.65*Math.cos(3*a+seed)+.35*Math.cos(5*a-seed));
				waveCos[i]=(float)(.65*Math.sin(3*a+seed)+.35*Math.sin(5*a-seed));
				samples.put(broad[i]);
			}
			samples.flip();
		}
		// 将肩部压平、让缺口较窄；相邻弧段的端点共用同一个谷底。
		private static double softStep(double t) { t=Math.max(0,(t-.34)/.66); return t*t*(3-2*t); }
		// v2 的原始闭环噪声：大尺度起伏上叠两层细纹，再在 11 个不等高的位置局部加尖齿。
		private static double v2Edge(double u,double seed) {
			double w=u+.10*loopNoise(u,5,seed+.7), cell=(u-Math.floor(u))*11;
			int id=(int)cell;
			double tooth=Math.max(0,1-Math.abs(cell-id-.5-.20*knot(id,seed+5.4))/(.21+.08*knot(id,seed+9.3)));
			return .39*loopNoise(w,6,seed)+.22*loopNoise(w,13,seed+2.1)+.095*loopNoise(w,31,seed+4.2)+.30*knot(id,seed+3.6)*tooth*tooth;
		}
		private static double knot(int i,double seed) {
			double h=Math.sin(i*91.731+seed*19.321)*4375.19;
			return (h-Math.floor(h))*2-1;
		}
		private static double loopNoise(double u,int n,double seed) {
			double p=(u-Math.floor(u))*n; int i=(int)p; double v=p-i, a=knot(i,seed);
			return a+(knot((i+1)%n,seed)-a)*(.45*v+.55*v*v*(3-2*v));
		}
		/** mix：0 为 v3 整块，1 为 v2 碎齿；age：战斗秒；amount：相对当前半径的最大形变；hz：每战斗秒的周期数。 */
		public void setAppearance(float mix,float age,float amount,float hz) {
			mix=Math.max(0f,Math.min(1f,mix));
			float s=0f,c=0f;
			if (amount>0f && hz>0f) {
				double t=2*Math.PI*age*hz, scale=.5*Math.min(.35f,amount);
				s=(float)(scale*Math.sin(t)); c=(float)(scale*(Math.cos(t)-1));
			}
			if (mix==lastMix && s==lastSin && c==lastCos) return;
			for (int i=0;i<96;i++) {
				float base=mix==1f ? jagged[i] : broad[i]+(jagged[i]-broad[i])*mix;
				samples.put(i,Math.max(.02f,base+s*waveSin[i]+c*waveCos[i]));
			}
			lastMix=mix; lastSin=s; lastCos=c;
		}
		/** 单位半径采样，0..95 顺序为从正 X 逆时针绕一周。 */
		public float radiusAt(int index) { return samples.get(Math.floorMod(index,96)); }
	}
	/** seed 为单次演出的固定种子；blocks 为主瓣数，depth、chips 均为相对半径的变形幅度。 */
	public static ScorchContour createScorchContour(float seed,int blocks,float depth,float chips) {
		return createScorchContour(seed,blocks,depth,chips,.80f);
	}
	/** 前四项与原方法相同；roughness 只影响 v2 端，0 为圆、0.80 为旧版默认值、1 为最大起伏。 */
	public static ScorchContour createScorchContour(float seed,int blocks,float depth,float chips,float roughness) {
		return new ScorchContour(seed,blocks,depth,chips,roughness);
	}
	/** 绘制扩张中的暗心与整圈焦边；radius 为当前世界半径，band 为相对半径的焦带宽度。 */
	public static void drawScorchRim(Vector2f center,float radius,float angle,float alpha,ScorchContour shape,float band,Color tint) {
		if (shape!=null) ProjectedFx.draw(0,center,radius,angle,alpha,tint,shape,Math.max(.01f,Math.min(.5f,band)),0,0,0);
	}
	/** 绘制横闪与竖芒；size 为世界尺寸，angle 为长轴角度，alpha 为当前亮度。 */
	public static void drawCrossFlare(Vector2f center,float size,float angle,float alpha,Color tint) {
		drawCrossFlare(center,size,angle,alpha,tint,1f,1f);
	}
	/** coreSize 只缩放中心亮芯（0~4），brightness 为整束光的亮度倍率；均为 1 时与原调用相同。 */
	public static void drawCrossFlare(Vector2f center,float size,float angle,float alpha,Color tint,float coreSize,float brightness) {
		if (!(brightness>0f) || !Float.isFinite(brightness) || !Float.isFinite(coreSize) || !Float.isFinite(size)) return;
		ProjectedFx.draw(1,center,size,angle,alpha,tint,null,Math.max(0f, Math.min(4f,coreSize))-1f,brightness,0,0);
	}
	/** 独立椭圆门圈；aspect 为全开时短轴/长轴，opening 为开度，time 为战斗秒。 */
	public static void drawPortalRim(Vector2f center,float radius,float facing,float alpha,Color tint,float aspect,float opening,float time,float seed) {
		if (opening>0) ProjectedFx.draw(2,center,radius,facing,alpha,tint,null,Math.max(.01f,Math.min(1f,aspect)),Math.min(1f,opening),time,seed);
	}
	private static final class ProjectedFx {
		private static int program, mode, params, color, contour;
		private static boolean tried;
		private static void load() {
			if (tried) return;
			tried=true;
			program=Moci_ShaderManager.getInstance().createShader("data/shaders/Moci_NebulaGate.vert","data/shaders/Moci_NebulaGate.frag","Moci_Util_projectedFx");
			if (program==0) return;
			mode=GL20.glGetUniformLocation(program,"mode"); params=GL20.glGetUniformLocation(program,"params");
			color=GL20.glGetUniformLocation(program,"tint"); contour=GL20.glGetUniformLocation(program,"contour[0]");
			if (mode<0 || params<0 || color<0 || contour<0) {
				Global.getLogger(Moci_Util.class).warn("Projected FX shader missing uniforms; using fallback");
				program=0;
			}
		}
		static void draw(int kind,Vector2f center,float size,float angle,float alpha,Color tint,ScorchContour shape,float x,float y,float time,float seed) {
			if (center==null || tint==null || !(size>0 && alpha>0) || tint.getAlpha()==0) return;
			load(); boolean gl2=GLContext.getCapabilities().OpenGL20;
			int previous=gl2?GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM):0;
			float opacity=Math.min(1,alpha)*tint.getAlpha()/255f;
			if (kind==1) opacity*=y; // 单独乘发光强度；焦边透明度和时间曲线不受影响
			GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS); GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPushMatrix();
			try {
				GL11.glTranslatef(center.x,center.y,0); GL11.glRotatef(angle,0,0,1); GL11.glScalef(size,size,1);
				GL11.glDisable(GL11.GL_TEXTURE_2D); GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDisable(GL11.GL_ALPHA_TEST);
				GL11.glDisable(GL11.GL_CULL_FACE); GL11.glEnable(GL11.GL_BLEND);
				if (program!=0) {
					GL11.glBlendFunc(GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA); GL20.glUseProgram(program);
					GL20.glUniform1i(mode,kind); GL20.glUniform4f(params,x,y,time,seed);
					GL20.glUniform4f(color,tint.getRed()/255f,tint.getGreen()/255f,tint.getBlue()/255f,opacity);
					if (shape!=null) GL20.glUniform4(contour,shape.samples);
					GL11.glBegin(GL11.GL_QUADS); GL11.glVertex2f(-2.5f,-2); GL11.glVertex2f(2.5f,-2);
					GL11.glVertex2f(2.5f,2); GL11.glVertex2f(-2.5f,2); GL11.glEnd();
				} else {
					if (gl2) GL20.glUseProgram(0);
					GL11.glBlendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA);
					GL11.glColor4f(tint.getRed()/255f,tint.getGreen()/255f,tint.getBlue()/255f,opacity);
					if (kind==1) {
						GL11.glBlendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE);
						GL11.glBegin(GL11.GL_LINES); GL11.glVertex2f(-1.5f,0); GL11.glVertex2f(1.5f,0); GL11.glVertex2f(0,-.45f); GL11.glVertex2f(0,.45f); GL11.glEnd();
						if (x>-1f) {
							// 无着色器时用中心亮、外围透明的椭圆近似亮芯。
							GL11.glShadeModel(GL11.GL_SMOOTH); GL11.glBegin(GL11.GL_TRIANGLE_FAN);
							GL11.glColor4f(1,1,1,opacity); GL11.glVertex2f(0,0); GL11.glColor4f(1,1,1,0);
							for (int i=0;i<=24;i++) { double a=i*Math.PI/12; GL11.glVertex2d(.115*(1+x)*Math.cos(a),.056*(1+x)*Math.sin(a)); }
							GL11.glEnd();
						}
					} else {
						GL11.glBegin(kind==0?GL11.GL_QUAD_STRIP:GL11.GL_LINE_LOOP);
						for (int i=0;i<=(kind==0?96:95);i++) {
							double a=i*Math.PI/48,c=Math.cos(a),s=Math.sin(a);
							if (kind==0) { float r=shape.radiusAt(i),inner=Math.max(0f,r-x); GL11.glVertex2d(c*inner,s*inner); GL11.glVertex2d(c*r,s*r); }
							else GL11.glVertex2d(c*x*(.18644+.81356*y),s*(.01+.99*y));
						}
						GL11.glEnd();
					}
				}
			} finally { if (gl2) GL20.glUseProgram(previous); GL11.glPopMatrix(); GL11.glPopAttrib(); }
		}
	}

	/** 范围与直径均为基准半径的倍数；寿命用秒，径向/切向速度用每秒位置偏移比例。 */
	public record NebulaPuffStyle(float innerRadius,float outerRadius,float minDiameter,float maxDiameter,
			float endSize,float minLife,float maxLife,float radialDrift,float swirl,float fadeIn,float hold) {}
	/** 生成一团烟；spread 只控制烟心分布，不缩小烟团，因而中心也能相互覆盖。angle 用弧度。 */
	public static void spawnNebulaPuff(CombatEngineAPI engine,Vector2f center,float radius,float spread,float angle,
			Random random,NebulaPuffStyle style,Color color) {
		if (engine==null || center==null || style==null || random==null || color==null || !(radius>0)) return;
		float r=radius*Math.max(0,spread)*(style.innerRadius+(style.outerRadius-style.innerRadius)*random.nextFloat());
		float dx=r*(float)Math.cos(angle),dy=r*(float)Math.sin(angle);
		float diameter=radius*(style.minDiameter+(style.maxDiameter-style.minDiameter)*random.nextFloat());
		float life=style.minLife+(style.maxLife-style.minLife)*random.nextFloat();
		if (!(diameter>0 && life>0)) return;
		engine.addNebulaSmokeParticle(new Vector2f(center.x+dx,center.y+dy),new Vector2f(dx*style.radialDrift-dy*style.swirl,dy*style.radialDrift+dx*style.swirl),
				diameter,style.endSize,style.fadeIn,style.hold,life,color);
	}
	/** 瞬时视觉电弧；保留旧调用。empTip 控制是否立即叠加原版终点粒子。 */
	public static void spawnVisualLightning(CombatEngineAPI engine,Vector2f from,Vector2f to,float width,Color fringe,Color core,boolean empTip) {
		if (engine==null || from==null || to==null || !(width>0)) return;
		engine.spawnEmpArcVisual(from,null,to,null,width,fringe,core);
		if (empTip) com.fs.starfarer.api.impl.combat.RealityDisruptorChargeGlow.spawnEMPParticles(
				com.fs.starfarer.api.impl.combat.RealityDisruptorChargeGlow.EMPArcHitType.DEST_NO_TARGET,null,to,null);
	}
	/** 0.98 运动电弧；speed 为世界单位/战斗秒。返回到达耗时，未生成则为 0，不自带终点粒子。 */
	public static float spawnVisualLightning(CombatEngineAPI engine,Vector2f from,Vector2f to,float width,Color fringe,Color core,float speed) {
		if (engine==null || from==null || to==null || !(width>0 && speed>0) || !Float.isFinite(speed)) return 0f;
		float dx=to.x-from.x,dy=to.y-from.y;
		float travel=(float)Math.hypot(dx,dy)/speed;
		if (!(travel>0) || !Float.isFinite(travel)) return 0f;
		EmpArcEntityAPI.EmpArcParams p=new EmpArcEntityAPI.EmpArcParams();
		p.movementDurOverride=travel;
		p.brightSpotFullFraction=.18f; p.brightSpotFadeFraction=.22f; // 移动亮芯、渐隐尾部占整条弧的比例
		p.nonBrightSpotMinBrightness=0f; p.flamesOutMissiles=false;
		EmpArcEntityAPI arc=engine.spawnEmpArcVisual(from,null,to,null,width,fringe,core,p);
		if (arc==null) return 0f;
		arc.setRenderGlowAtStart(false); arc.setRenderGlowAtEnd(false);
		arc.setSingleFlickerMode(true); // 只有带 true 的重载会启用亮段传播
		return travel;
	}
	public static final String TAG_NO_RECOIL = "NO_RECOIL";

	/** 跃迁美术尺寸，以母舰位置和朝向为坐标系；只量舰体贴图，不改变碰撞或落点判断。 */
	public static final class TravelHullBounds {
		private float nose,tail,side,radiusSq;
		private boolean hasModules;
		public float nose() { return nose; }
		public float tail() { return tail; }
		public float side() { return side; }
		public float radius() { return (float)Math.sqrt(radiusSq); }
		public boolean hasModules() { return hasModules; }

		private void include(float front,float lateral) {
			nose=Math.max(nose,front); tail=Math.max(tail,-front); side=Math.max(side,Math.abs(lateral));
			radiusSq=Math.max(radiusSq,front*front+lateral*lateral);
		}
		private boolean measure(ShipAPI root,ShipAPI part,double axisX,double axisY) {
			if(part.isDoNotRender() || part.isDoNotRenderSprite()) return false;
			SpriteAPI sp=part.getSpriteAPI();
			Vector2f offset=part.getRenderOffset();
			float dx=part.getLocation().x-root.getLocation().x+(offset==null?0:offset.x);
			float dy=part.getLocation().y-root.getLocation().y+(offset==null?0:offset.y);
			float front=(float)(dx*axisX+dy*axisY), lateral=(float)(dy*axisX-dx*axisY);
			if(sp==null || !(sp.getWidth()>0 && sp.getHeight()>0)) {
				float r=part.getCollisionRadius(); // 无贴图可量时，才退回碰撞圆
				include(front+r,lateral); include(front-r,lateral); include(front,lateral+r); include(front,lateral-r);
				float far=(float)Math.hypot(front,lateral)+r; radiusSq=Math.max(radiusSq,far*far);
				return true;
			}
			if(sp.getAlphaMult()<=0 || (sp.getColor()!=null && sp.getColor().getAlpha()==0)) return false;
			double a=Math.toRadians(part.getFacing()-root.getFacing()-90f), c=Math.cos(a), s=Math.sin(a);
			for(int i=0;i<4;i++) {
				float x=((i&1)==0?0:sp.getWidth())-sp.getCenterX(), y=((i&2)==0?0:sp.getHeight())-sp.getCenterY();
				include(front+(float)(x*c-y*s),lateral+(float)(x*s+y*c));
			}
			return true;
		}
	}
	/** 在跃迁开始时测量一次；不递归，也不遍历武器。 */
	public static TravelHullBounds measureTravelHull(ShipAPI ship,CombatEngineAPI engine) {
		TravelHullBounds b=new TravelHullBounds();
		double a=Math.toRadians(ship.getFacing()), x=Math.cos(a), y=Math.sin(a);
		b.measure(ship,ship,x,y);
		for(ShipAPI child:ship.getChildModulesCopy()) {
			if(child!=null && child.getParentStation()==ship && child.isAlive() && engine.isEntityInPlay(child))
				b.hasModules |= b.measure(ship,child,x,y);
		}
		return b;
	}

	/** A single short-lived plane: absolute birth time, then independent growth and decay in seconds. */
	public record PetalPulse(float birth,float grow,float shrink,float degrees,float reach,float halfWidth,float bias,float opacity) {
		public float end() { return birth+grow+shrink; }
		public float envelope(float time) {
			float age=time-birth;
			if(age<=0 || age>=grow+shrink) return 0;
			float u=age<grow ? age/grow : 1f-(age-grow)/shrink;
			return u*u*(3f-2f*u);
		}
	}

	/** Additive world-space FX batch. Only open inside a combat render callback. */
	public static final class LightCanvas implements AutoCloseable {
		private static final int N=72;
		private static final float[] CX=new float[N+1],CY=new float[N+1];
		static { for(int i=0;i<=N;i++){ CX[i]=(float)Math.cos(2*Math.PI*i/N); CY[i]=(float)Math.sin(2*Math.PI*i/N); } }
		private final int program,unit;
		private final boolean gl2=GLContext.getCapabilities().OpenGL20;
		public LightCanvas() {
			program=gl2 ? GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) : 0;
			unit=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
			GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
			if(gl2) GL20.glUseProgram(0);
			for(int i=Math.min(8,GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS))-1;i>=0;i--) {
				GL13.glActiveTexture(GL13.GL_TEXTURE0+i); GL11.glDisable(GL11.GL_TEXTURE_2D);
			}
			GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDepthMask(false); GL11.glDisable(GL11.GL_LIGHTING);
			GL11.glDisable(GL11.GL_ALPHA_TEST); GL11.glDisable(GL11.GL_CULL_FACE);
			GL11.glEnable(GL11.GL_BLEND); GL11.glBlendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE);
			GL11.glShadeModel(GL11.GL_SMOOTH); GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPushMatrix();
		}
		@Override public void close() {
			GL11.glPopMatrix(); GL11.glPopAttrib(); GL13.glActiveTexture(unit);
			if(gl2) GL20.glUseProgram(program);
		}
		private static void color(Color c,float a) { GL11.glColor4f(c.getRed()/255f,c.getGreen()/255f,c.getBlue()/255f,Math.min(1f,a)*c.getAlpha()/255f); }
		/** Soft band between inner/peak/outer radii. A zero peak makes a filled glow. 4 sides = diamond. */
		public void band(Vector2f at,float inner,float peak,float outer,int sides,Color c,float alpha) {
			if(alpha<=0 || outer<=0) return;
			int n=sides==4?4:N, step=N/n;
			GL11.glPushMatrix(); GL11.glTranslatef(at.x,at.y,0);
			for(int half=0;half<2;half++) {
				float r0=half==0?inner:peak,r1=half==0?peak:outer;
				if(r1<=r0) continue;
				GL11.glBegin(GL11.GL_QUAD_STRIP);
				for(int i=0;i<=N;i+=step) {
					color(c,half==0?0:alpha); GL11.glVertex2f(CX[i]*r0,CY[i]*r0);
					color(c,half==0?alpha:0); GL11.glVertex2f(CX[i]*r1,CY[i]*r1);
				}
				GL11.glEnd();
			}
			GL11.glPopMatrix();
		}
		/** Local optical bloom and horizontal streak; no screen copy or optional shader is needed. */
		public void lensBloom(Vector2f source,float radius,Color tint,float strength) {
			if(radius<=0 || strength<=0) return;
			GL11.glPushMatrix(); GL11.glTranslatef(source.x,source.y,0);
			lensLobe(radius*3.55f,radius*2.75f,tint,strength*.24f);
			lensLobe(radius*1.62f,radius*1.38f,tint,strength*.51f);
			lensLobe(radius*3.85f,radius*.10f,tint,strength*.72f);
			lensLobe(radius*2.60f,radius*.033f,Color.WHITE,strength*.90f);
			lensLobe(radius*.52f,radius*.43f,Color.WHITE,strength);
			GL11.glPopMatrix();
		}
		private static void lensLobe(float rx,float ry,Color tint,float alpha) {
			GL11.glBegin(GL11.GL_TRIANGLE_FAN); color(tint,alpha); GL11.glVertex2f(0,0); color(tint,0);
			for(int i=0;i<=N;i++) GL11.glVertex2f(rx*CX[i],ry*CY[i]);
			GL11.glEnd();
		}
		/** Translucent pointed blades with independent rotation; angle is in degrees. */
		public void petals(Vector2f at,int count,float angle,float length,float width,Color c,float alpha) {
			if(alpha<=0 || count<1) return;
			GL11.glPushMatrix(); GL11.glTranslatef(at.x,at.y,0); GL11.glRotatef(angle,0,0,1);
			for(int i=0;i<count;i++) {
				GL11.glBegin(GL11.GL_TRIANGLE_FAN);
				color(c,alpha); GL11.glVertex2f(length*.35f,0);
				color(c,alpha*.22f); GL11.glVertex2f(0,0); GL11.glVertex2f(length*.47f,-width);
				color(c,alpha*.55f); GL11.glVertex2f(length,width*.24f);
				color(c,alpha*.13f); GL11.glVertex2f(length*.52f,width); GL11.glVertex2f(0,0);
				GL11.glEnd(); GL11.glRotatef(360f/count,0,0,1);
			}
			GL11.glPopMatrix();
		}
		/** Render just the active window; repeated draws at the same simulation time are identical. */
		public void pulsingPetals(Vector2f at,PetalPulse[] pulses,int first,int last,float angle,float time,
				float radius,Color tint,float alpha) {
			if (radius<=0 || alpha<=0) return;
			for (int i=first;i<last;i++) {
				PetalPulse p=pulses[i];
				float k=p.envelope(time);
				if (k<=0) continue;
				float l=radius*p.reach()*k, w=radius*p.halfWidth()*k, bias=p.bias(), a=alpha*p.opacity()*Math.min(1f,k*2.4f);
				GL11.glPushMatrix(); GL11.glTranslatef(at.x,at.y,0); GL11.glRotatef(angle+p.degrees(),0,0,1);
				GL11.glBegin(GL11.GL_TRIANGLE_FAN);
				float cx=l*.17f, cy=w*bias;
				color(tint,a*.87f); GL11.glVertex2f(cx,cy);
				for (int j=0;j<=4;j++) petalEdge(j%4,l,w,bias,cx,cy,.82f,tint,a*.58f);
				GL11.glEnd();
				GL11.glBegin(GL11.GL_QUAD_STRIP);
				for (int j=0;j<=4;j++) {
					petalEdge(j%4,l,w,bias,cx,cy,.82f,tint,a*.58f);
					petalEdge(j%4,l,w,bias,cx,cy,1.08f,tint,0);
				}
				GL11.glEnd(); GL11.glPopMatrix();
			}
		}
		private static void petalEdge(int index,float l,float w,float bias,float cx,float cy,float scale,Color tint,float alpha) {
			float x,y;
			switch (index) {
				case 0 -> { x=-l*.23f; y=-w*.38f; }
				case 1 -> { x=l*(.40f+bias); y=-w; }
				case 2 -> { x=l; y=0; }
				default -> { x=l*(.42f-bias); y=w*.85f; }
			}
			color(tint,alpha); GL11.glVertex2f(cx+(x-cx)*scale,cy+(y-cy)*scale);
		}
		/** A parallel-sided light ribbon. The caller animates its width, not the end point. */
		public void ribbon(Vector2f nozzle,float dx,float dy,float length,float halfWidth,Color c,float alpha) {
			if(halfWidth<=0 || alpha<=0) return;
			float x=nozzle.x,y=nozzle.y,ex=x+dx*length,ey=y+dy*length;
			GL11.glBegin(GL11.GL_QUAD_STRIP);
			for(int j=-1;j<=1;j++) {
				float ox=-dy*halfWidth*j,oy=dx*halfWidth*j;
				color(c,j==0?alpha:0); GL11.glVertex2f(x+ox,y+oy); GL11.glVertex2f(ex+ox,ey+oy);
			}
			GL11.glEnd();
		}
		/** Flat white/tinted silhouette uses the real texture alpha, including the current animation frame. */
		public void echo(HullEcho echo,Vector2f at,float facing,float scale,Color tint,float alpha) {
			if(echo==null || echo.surfaces.isEmpty() || alpha<=0 || scale<=0) return;
			GL11.glPushMatrix();
			try {
				GL11.glTranslatef(at.x,at.y,0); GL11.glRotatef(facing-echo.facing,0,0,1); GL11.glScalef(scale,scale,1);
				GL11.glEnable(GL11.GL_TEXTURE_2D); GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL11.GL_TEXTURE_ENV_MODE,GL13.GL_COMBINE);
				GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_COMBINE_RGB,GL11.GL_REPLACE);
				GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_SOURCE0_RGB,GL13.GL_PRIMARY_COLOR);
				GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_OPERAND0_RGB,GL11.GL_SRC_COLOR);
				GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_COMBINE_ALPHA,GL11.GL_MODULATE);
				GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_SOURCE0_ALPHA,GL11.GL_TEXTURE);
				GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_SOURCE1_ALPHA,GL13.GL_PRIMARY_COLOR);
				GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_OPERAND0_ALPHA,GL11.GL_SRC_ALPHA);
				GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_OPERAND1_ALPHA,GL11.GL_SRC_ALPHA);
				for(var s:echo.surfaces) {
					GL11.glBindTexture(GL11.GL_TEXTURE_2D,s.texture()); GL11.glPushMatrix();
					GL11.glTranslatef(s.x()-echo.x,s.y()-echo.y,0); GL11.glRotatef(s.angle(),0,0,1);
					float x=-s.centerX(),y=-s.centerY(),u=s.texX(),v=s.texY(),w=s.texWidth(),h=s.texHeight();
					color(tint,s.alpha()*alpha); GL11.glBegin(GL11.GL_QUADS);
					GL11.glTexCoord2f(u,v); GL11.glVertex2f(x,y);
					GL11.glTexCoord2f(u+w,v); GL11.glVertex2f(x+s.width(),y);
					GL11.glTexCoord2f(u+w,v+h); GL11.glVertex2f(x+s.width(),y+s.height());
					GL11.glTexCoord2f(u,v+h); GL11.glVertex2f(x,y+s.height()); GL11.glEnd(); GL11.glPopMatrix();
				}
			} finally { GL11.glDisable(GL11.GL_TEXTURE_2D); GL11.glPopMatrix(); }
		}
	}
	/** Reuse the assembly sampler used by the Zeta coating; no paths guessed and no live sprite resized. */
	public static final class HullEcho {
		private final float x,y,facing;
		private final List<xi.arsenal.ZetaOverdriveCoating.Surface> surfaces;
		public HullEcho(ShipAPI ship) {
			x=ship.getLocation().x; y=ship.getLocation().y; facing=ship.getFacing();
			surfaces=xi.arsenal.ZetaOverdriveCoating.snapshot(ship);
		}
		public boolean isEmpty() { return surfaces.isEmpty(); }
	}
	/** 飞溅粒子的半径、速度、大小和亮度均为最小值加随机增量；寿命用最短/最长秒数。 */
	public record RadialLightStyle(float radiusMin,float radiusRandom,float speedMin,float speedRandom,
			float sizeMin,float sizeRandom,float alphaMin,float alphaRandom,float minLife,float maxLife) {}
	/** 兼容原调用：只指定寿命，其余沿用原有外观。 */
	public static void spawnRadialLights(CombatEngineAPI engine,Vector2f at,float radius,int count,Color tint,Random rng,
			float minLife,float maxLife) {
		spawnRadialLights(engine,at,radius,count,tint,rng,new RadialLightStyle(.50f,.38f,.65f,1.6f,.065f,.17f,.65f,.35f,minLife,maxLife));
	}
	/** 向四周分散飞溅；粒子由引擎管理，调用方结束后仍按各自的寿命消散。 */
	public static void spawnRadialLights(CombatEngineAPI engine,Vector2f at,float radius,int count,Color tint,Random rng,RadialLightStyle s) {
		for(int i=0;i<count;i++) {
			double a=2*Math.PI*(i+rng.nextFloat())/count; float x=(float)Math.cos(a),y=(float)Math.sin(a);
			float r=radius*(s.radiusMin+s.radiusRandom*rng.nextFloat()),v=radius*(s.speedMin+s.speedRandom*rng.nextFloat());
			engine.addHitParticle(new Vector2f(at.x+x*r,at.y+y*r),new Vector2f(x*v,y*v),
					radius*(s.sizeMin+s.sizeRandom*rng.nextFloat()),s.alphaMin+s.alphaRandom*rng.nextFloat(),s.minLife+(s.maxLife-s.minLife)*rng.nextFloat(),tint);
		}
	}
	public static final String TAG_HIDE_RECOIL = "HIDE_RECOIL";
	public static final String TAG_FOLLOW_RECOIL = "FOLLOW_RECOIL";

	/**
	 * 装饰武器后坐力状态
	 * 记录武器各层sprite的原始中心点，避免每帧累积偏移。
	 */
	public static class SpriteRecoilState {
		public static class LayerState {
			public boolean initialized = false;
			public float originalCenterX = 0f;
			public float originalCenterY = 0f;
		}

		public boolean initialized = false;
		public final LayerState main = new LayerState();
		public final LayerState barrel = new LayerState();
		public final LayerState under = new LayerState();
		public final LayerState glow = new LayerState();
	}

	/**
	 * 捕获武器sprite的原始中心点。
	 */
	public static void captureSpriteRecoilOrigin(WeaponAPI weapon, SpriteRecoilState state) {
		if (weapon == null || state == null || state.initialized) {
			return;
		}

		captureSpriteLayerOrigin(weapon.getSprite(), state.main);
		captureSpriteLayerOrigin(weapon.getBarrelSpriteAPI(), state.barrel);
		captureSpriteLayerOrigin(weapon.getUnderSpriteAPI(), state.under);
		captureSpriteLayerOrigin(weapon.getGlowSpriteAPI(), state.glow);
		state.initialized = state.main.initialized
				|| state.barrel.initialized
				|| state.under.initialized
				|| state.glow.initialized;
	}

	/**
	 * 按武器开火/冷却进度对装饰武器施加sprite后坐力。
	 * 对 burst beam 一类武器，满充进入照射的瞬间会立即进入最大后坐，
	 * 待照射结束后再按冷却进度回位。
	 * recoilX/recoilY 是相对原始中心点的本地sprite偏移量。
	 */
	public static void applyCooldownSpriteRecoil(WeaponAPI decorativeWeapon, WeaponAPI sourceWeapon,
			SpriteRecoilState state, float recoilX, float recoilY) {
		if (decorativeWeapon == null || state == null) {
			return;
		}

		captureSpriteRecoilOrigin(decorativeWeapon, state);
		if (!state.initialized) {
			return;
		}

		float recoilLevel = getWeaponRecoilLevel(sourceWeapon);

		float offsetX = recoilX * recoilLevel;
		float offsetY = recoilY * recoilLevel;
		applySpriteLayerRecoil(decorativeWeapon.getSprite(), state.main, offsetX, offsetY);
		applySpriteLayerRecoil(decorativeWeapon.getBarrelSpriteAPI(), state.barrel, offsetX, offsetY);
		applySpriteLayerRecoil(decorativeWeapon.getUnderSpriteAPI(), state.under, offsetX, offsetY);
		applySpriteLayerRecoil(decorativeWeapon.getGlowSpriteAPI(), state.glow, offsetX, offsetY);
	}

	public static float getWeaponRecoilLevel(WeaponAPI sourceWeapon) {
		if (sourceWeapon == null) {
			return 0f;
		}

		// Burst beam/持续照射武器在真正出光的瞬间就后坐，而不是等整段照射结束后才触发。
		if (sourceWeapon.isBeam() && sourceWeapon.isFiring() && sourceWeapon.getChargeLevel() >= 1f) {
			return 1f;
		}

		if (sourceWeapon.getCooldown() <= 0f) {
			return 0f;
		}

		float recoilLevel = sourceWeapon.getCooldownRemaining() / sourceWeapon.getCooldown();
		return Math.max(0f, Math.min(1f, recoilLevel));
	}

	/**
	 * 重置装饰武器sprite中心点到原始位置。
	 */
	public static void resetSpriteRecoil(WeaponAPI weapon, SpriteRecoilState state) {
		if (weapon == null || state == null) {
			return;
		}

		captureSpriteRecoilOrigin(weapon, state);
		if (!state.initialized) {
			return;
		}

		resetSpriteLayerRecoil(weapon.getSprite(), state.main);
		resetSpriteLayerRecoil(weapon.getBarrelSpriteAPI(), state.barrel);
		resetSpriteLayerRecoil(weapon.getUnderSpriteAPI(), state.under);
		resetSpriteLayerRecoil(weapon.getGlowSpriteAPI(), state.glow);
	}

	private static void captureSpriteLayerOrigin(SpriteAPI sprite, SpriteRecoilState.LayerState layerState) {
		if (sprite == null || layerState == null || layerState.initialized) {
			return;
		}

		layerState.originalCenterX = sprite.getCenterX();
		layerState.originalCenterY = sprite.getCenterY();
		layerState.initialized = true;
	}

	private static void applySpriteLayerRecoil(SpriteAPI sprite, SpriteRecoilState.LayerState layerState,
			float offsetX, float offsetY) {
		if (sprite == null || layerState == null || !layerState.initialized) {
			return;
		}

		sprite.setCenterX(layerState.originalCenterX + offsetX);
		sprite.setCenterY(layerState.originalCenterY + offsetY);
	}

	private static void resetSpriteLayerRecoil(SpriteAPI sprite, SpriteRecoilState.LayerState layerState) {
		if (sprite == null || layerState == null || !layerState.initialized) {
			return;
		}

		sprite.setCenterX(layerState.originalCenterX);
		sprite.setCenterY(layerState.originalCenterY);
	}

	public static boolean hasWeaponTag(WeaponAPI weapon, String tag) {
		return weapon != null && weapon.getSpec() != null && tag != null && weapon.getSpec().hasTag(tag);
	}

	public static boolean hasAnyWeaponTag(String tag, WeaponAPI... weapons) {
		if (tag == null || weapons == null) {
			return false;
		}

		for (WeaponAPI weapon : weapons) {
			if (hasWeaponTag(weapon, tag)) {
				return true;
			}
		}
		return false;
	}

	public static boolean shouldDisableRecoil(WeaponAPI... weapons) {
		return hasAnyWeaponTag(TAG_NO_RECOIL, weapons);
	}

	public static boolean shouldApplyHiddenSourceRecoil(WeaponAPI sourceWeapon, WeaponAPI... relatedWeapons) {
		if (shouldDisableRecoil(sourceWeapon) || shouldDisableRecoil(relatedWeapons)) {
			return false;
		}
		return hasWeaponTag(sourceWeapon, TAG_HIDE_RECOIL);
	}

	/**
	 * 判断舰船系统是否处于开启状态。
	 */
	public static boolean isShipSystemActive(ShipAPI ship) {
		return ship != null && ship.getSystem() != null && (ship.getSystem().isOn() || ship.getSystem().isActive());
	}

	/**
	 * 判断舰船是否正在散热或过载。
	 */
	public static boolean isVentingOrOverloaded(ShipAPI ship) {
		return ship != null && ship.getFluxTracker() != null &&
				(ship.getFluxTracker().isVenting() || ship.getFluxTracker().isOverloaded());
	}

	/**
	 * 系统开启时返回设定加成，否则返回 0。
	 */
	public static float getSystemActiveBonus(ShipAPI ship, float activeBonus) {
		return isShipSystemActive(ship) ? activeBonus : 0f;
	}

	/**
	 * 基础特效数值计算：base + primary * primaryBonus + additive * additiveBonus。
	 */
	public static float computeEffectValue(
			float baseValue,
			float primaryLevel,
			float primaryBonus,
			float additiveLevel,
			float additiveBonus
	) {
		return baseValue + primaryLevel * primaryBonus + additiveLevel * additiveBonus;
	}

	/**
	 * 基础特效数值计算：在单一主输入之外，额外叠加第二输入。
	 */
	public static float computeEffectValue(
			float baseValue,
			float primaryLevel,
			float primaryBonus,
			float secondaryLevel,
			float secondaryBonus,
			float additiveLevel,
			float additiveBonus
	) {
		return baseValue +
				primaryLevel * primaryBonus +
				secondaryLevel * secondaryBonus +
				additiveLevel * additiveBonus;
	}

	/**
	 * 对特效数值施加惩罚并设置下限，避免出现过低值。
	 */
	public static float applyPenaltyWithFloor(float value, boolean applyPenalty, float penalty, float minValue) {
		float result = applyPenalty ? value - penalty : value;
		return Math.max(minValue, result);
	}

	/**
	 * 基础特效数值计算：按两项输入线性叠加，并限制最大值。
	 */
	public static float computeCappedEffectValue(
			float primaryLevel,
			float primaryBonus,
			float additiveLevel,
			float additiveBonus,
			float maxValue
	) {
		return Math.min(maxValue, primaryLevel * primaryBonus + additiveLevel * additiveBonus);
	}

	/**
	 * 按每秒次数触发
	 * 用于控制粒子生成频率等
	 * 
	 * @param times 每秒触发次数
	 * @param amount 时间增量（通常是engine.getElapsedInLastFrame()）
	 * @return 是否触发
	 */
	public static boolean timesPerSec(float times, float amount) {
		return Math.random() < amount * times;
	}

	/**
	 * 获取范围内的所有弹幕和导弹
	 * 
	 * @param location 中心位置
	 * @param range 范围
	 * @return 弹幕列表
	 */
	public static List<DamagingProjectileAPI> getProjectilesAndMissilesWithinRange(Vector2f location, float range) {
		List<DamagingProjectileAPI> result = new ArrayList<>();
		
		// 获取所有弹幕
		for (DamagingProjectileAPI proj : Global.getCombatEngine().getProjectiles()) {
			if (proj.getLocation() == null) continue;
			float distance = Vector2f.sub(proj.getLocation(), location, null).length();
			if (distance <= range) {
				result.add(proj);
			}
		}
		
		// 获取所有导弹
		for (MissileAPI missile : Global.getCombatEngine().getMissiles()) {
			if (missile.getLocation() == null) continue;
			float distance = Vector2f.sub(missile.getLocation(), location, null).length();
			if (distance <= range) {
				result.add(missile);
			}
		}
		
		return result;
	}

	/**
	 * 获取范围内的敌方弹幕和导弹
	 * 
	 * @param location 中心位置
	 * @param range 范围
	 * @param owner 己方阵营
	 * @return 敌方弹幕列表
	 */
	public static List<DamagingProjectileAPI> getEnemyProjectilesAndMissilesWithinRange(Vector2f location, float range, int owner) {
		List<DamagingProjectileAPI> result = new ArrayList<>();
		
		for (DamagingProjectileAPI proj : getProjectilesAndMissilesWithinRange(location, range)) {
			if (proj.getOwner() != owner) {
				result.add(proj);
			}
		}
		
		return result;
	}

	/**
	 * 添加光源（使用GraphicsLib）
	 * 如果GraphicsLib不可用，则不执行任何操作
	 * 
	 * @param location 光源位置
	 * @param size 光源大小
	 * @param intensity 光源强度
	 * @param duration 持续时间
	 * @param color 光源颜色
	 */
	public static void addLight(Vector2f location, float size, float intensity, float duration, Color color) {
		try {
			StandardLight light = new StandardLight(location, new Vector2f(), new Vector2f(), null);
			light.setSize(size);
			light.setIntensity(intensity);
			light.setLifetime(duration);
			light.setColor(color);
			light.fadeIn(0.1f);
			light.fadeOut(duration * 0.5f);

			LightShader.addLight(light);
		} catch (Exception e) {
			// GraphicsLib初始化失败时忽略，保留战斗逻辑。
		}
	}

	/**
	 * 显示浮动文本
	 * 
	 * @param anchor 锚点实体
	 * @param at 显示位置
	 * @param text 文本内容
	 */
	public static void showText(CombatEntityAPI anchor, Vector2f at, String text) {
		Global.getCombatEngine().addFloatingText(at, text, 25f, Color.RED, anchor, 1f, 1f);
	}

	/**
	 * 显示浮动数字
	 * 
	 * @param anchor 锚点实体
	 * @param at 显示位置
	 * @param amount 数字
	 */
	public static void showText(CombatEntityAPI anchor, Vector2f at, float amount) {
		showText(anchor, at, "" + amount);
	}

	// ==================== Grid遍历工具方法 ====================

	/**
	 * 使用Grid遍历获取范围内的导弹
	 * 性能优化：使用空间索引，只检查指定区域内的导弹
	 * 
	 * @param center 中心位置
	 * @param radius 检查半径
	 * @return 范围内的导弹列表
	 */
	public static List<MissileAPI> getMissilesInRange(Vector2f center, float radius) {
		List<MissileAPI> result = new ArrayList<>();
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return result;

		float checkSize = radius * 2f;
		java.util.Iterator<Object> iter = engine.getMissileGrid().getCheckIterator(
			center, checkSize, checkSize
		);

		while (iter.hasNext()) {
			Object obj = iter.next();
			if (!(obj instanceof MissileAPI)) continue;
			
			MissileAPI missile = (MissileAPI) obj;
			if (missile.isExpired() || !engine.isEntityInPlay(missile)) continue;
			
			float dist = Vector2f.sub(missile.getLocation(), center, null).length();
			if (dist <= radius) {
				result.add(missile);
			}
		}

		return result;
	}

	/**
	 * 使用Grid遍历获取范围内的敌方导弹
	 * 
	 * @param center 中心位置
	 * @param radius 检查半径
	 * @param owner 己方阵营
	 * @return 范围内的敌方导弹列表
	 */
	public static List<MissileAPI> getEnemyMissilesInRange(Vector2f center, float radius, int owner) {
		List<MissileAPI> result = new ArrayList<>();
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return result;

		float checkSize = radius * 2f;
		java.util.Iterator<Object> iter = engine.getMissileGrid().getCheckIterator(
			center, checkSize, checkSize
		);

		while (iter.hasNext()) {
			Object obj = iter.next();
			if (!(obj instanceof MissileAPI)) continue;
			
			MissileAPI missile = (MissileAPI) obj;
			if (missile.getOwner() == owner) continue;
			if (missile.isExpired() || !engine.isEntityInPlay(missile)) continue;
			
			float dist = Vector2f.sub(missile.getLocation(), center, null).length();
			if (dist <= radius) {
				result.add(missile);
			}
		}

		return result;
	}

	/**
	 * 使用Grid遍历获取范围内的所有弹丸（包括导弹）
	 * 
	 * @param center 中心位置
	 * @param radius 检查半径
	 * @return 范围内的弹丸列表
	 */
	public static List<DamagingProjectileAPI> getAllProjectilesInRange(Vector2f center, float radius) {
		List<DamagingProjectileAPI> result = new ArrayList<>();
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return result;

		float checkSize = radius * 2f;
		java.util.Iterator<Object> iter = engine.getAllObjectGrid().getCheckIterator(
			center, checkSize, checkSize
		);

		while (iter.hasNext()) {
			Object obj = iter.next();
			if (!(obj instanceof DamagingProjectileAPI)) continue;
			
			DamagingProjectileAPI proj = (DamagingProjectileAPI) obj;
			if (proj.isExpired() || !engine.isEntityInPlay(proj)) continue;
			
			float dist = Vector2f.sub(proj.getLocation(), center, null).length();
			if (dist <= radius) {
				result.add(proj);
			}
		}

		return result;
	}

	/**
	 * 使用Grid遍历获取范围内的敌方弹丸（包括导弹）
	 * 
	 * @param center 中心位置
	 * @param radius 检查半径
	 * @param owner 己方阵营
	 * @return 范围内的敌方弹丸列表
	 */
	public static List<DamagingProjectileAPI> getEnemyProjectilesInRange(Vector2f center, float radius, int owner) {
		List<DamagingProjectileAPI> result = new ArrayList<>();
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return result;

		float checkSize = radius * 2f;
		java.util.Iterator<Object> iter = engine.getAllObjectGrid().getCheckIterator(
			center, checkSize, checkSize
		);

		while (iter.hasNext()) {
			Object obj = iter.next();
			if (!(obj instanceof DamagingProjectileAPI)) continue;
			
			DamagingProjectileAPI proj = (DamagingProjectileAPI) obj;
			if (proj.getOwner() == owner) continue;
			if (proj.isExpired() || !engine.isEntityInPlay(proj)) continue;
			
			float dist = Vector2f.sub(proj.getLocation(), center, null).length();
			if (dist <= radius) {
				result.add(proj);
			}
		}

		return result;
	}

	/**
	 * 使用Grid遍历获取范围内的舰船
	 * 
	 * @param center 中心位置
	 * @param radius 检查半径
	 * @return 范围内的舰船列表
	 */
	public static List<ShipAPI> getShipsInRange(Vector2f center, float radius) {
		List<ShipAPI> result = new ArrayList<>();
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return result;

		float checkSize = radius * 2f;
		java.util.Iterator<Object> iter = engine.getShipGrid().getCheckIterator(
			center, checkSize, checkSize
		);

		while (iter.hasNext()) {
			Object obj = iter.next();
			if (!(obj instanceof ShipAPI)) continue;
			
			ShipAPI ship = (ShipAPI) obj;
			if (ship.isHulk() || !engine.isEntityInPlay(ship)) continue;
			
			float dist = Vector2f.sub(ship.getLocation(), center, null).length();
			if (dist <= radius) {
				result.add(ship);
			}
		}

		return result;
	}

	/**
	 * 使用Grid遍历获取范围内的敌方舰船
	 * 
	 * @param center 中心位置
	 * @param radius 检查半径
	 * @param owner 己方阵营
	 * @return 范围内的敌方舰船列表
	 */
	public static List<ShipAPI> getEnemyShipsInRange(Vector2f center, float radius, int owner) {
		List<ShipAPI> result = new ArrayList<>();
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return result;

		float checkSize = radius * 2f;
		java.util.Iterator<Object> iter = engine.getShipGrid().getCheckIterator(
			center, checkSize, checkSize
		);

		while (iter.hasNext()) {
			Object obj = iter.next();
			if (!(obj instanceof ShipAPI)) continue;
			
			ShipAPI ship = (ShipAPI) obj;
			if (ship.getOwner() == owner) continue;
			if (ship.isHulk() || !engine.isEntityInPlay(ship)) continue;
			
			float dist = Vector2f.sub(ship.getLocation(), center, null).length();
			if (dist <= radius) {
				result.add(ship);
			}
		}

		return result;
	}

	/**
	 * 使用Grid遍历获取范围内的小行星
	 * 
	 * @param center 中心位置
	 * @param radius 检查半径
	 * @return 范围内的小行星列表
	 */
	public static List<CombatEntityAPI> getAsteroidsInRange(Vector2f center, float radius) {
		List<CombatEntityAPI> result = new ArrayList<>();
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return result;

		float checkSize = radius * 2f;
		java.util.Iterator<Object> iter = engine.getAsteroidGrid().getCheckIterator(
			center, checkSize, checkSize
		);

		while (iter.hasNext()) {
			Object obj = iter.next();
			if (!(obj instanceof CombatEntityAPI)) continue;
			
			CombatEntityAPI asteroid = (CombatEntityAPI) obj;
			if (!engine.isEntityInPlay(asteroid)) continue;
			
			float dist = Vector2f.sub(asteroid.getLocation(), center, null).length();
			if (dist <= radius) {
				result.add(asteroid);
			}
		}

		return result;
	}

	/**
	 * 使用Grid遍历获取矩形区域内的导弹
	 * 适用于光束、光剑等线性武器的检测
	 * 
	 * @param center 中心位置
	 * @param width 宽度
	 * @param height 高度
	 * @return 区域内的导弹列表
	 */
	public static List<MissileAPI> getMissilesInRect(Vector2f center, float width, float height) {
		List<MissileAPI> result = new ArrayList<>();
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return result;

		java.util.Iterator<Object> iter = engine.getMissileGrid().getCheckIterator(
			center, width, height
		);

		while (iter.hasNext()) {
			Object obj = iter.next();
			if (!(obj instanceof MissileAPI)) continue;
			
			MissileAPI missile = (MissileAPI) obj;
			if (missile.isExpired() || !engine.isEntityInPlay(missile)) continue;
			
			result.add(missile);
		}

		return result;
	}

	/**
	 * 获取范围内的所有舰船（不包括穿梭机）
	 * 兼容SWP_Util的方法名
	 * 
	 * @param location 中心位置
	 * @param range 范围
	 * @return 舰船列表
	 */
	public static List<ShipAPI> getShipsWithinRange(Vector2f location, float range) {
		List<ShipAPI> ships = new ArrayList<>();

		for (ShipAPI tmp : Global.getCombatEngine().getShips()) {
			// 跳过穿梭机
			if (tmp.isShuttlePod()) {
				continue;
			}

			// 检查距离
			if (MathUtils.isWithinRange(tmp, location, range)) {
				ships.add(tmp);
			}
		}

		return ships;
	}

	/**
	 * 获取最近的伤害点
	 * 用于计算碰撞检测的最近点
	 * 
	 * @param source 源点
	 * @param entity 目标实体
	 * @return 最近点
	 */
	public static Vector2f getNearestPointForDamage(Vector2f source, CombatEntityAPI entity) {
		// 如果是弹丸，直接返回位置
		if (entity instanceof DamagingProjectileAPI) {
			return entity.getLocation();
		}

		// 否则返回边界上的最近点
		return CollisionUtils.getNearestPointOnBounds(source, entity);
	}

	/**
	 * 过滤被遮挡的目标
	 * 用于区域伤害时排除被护盾或其他物体遮挡的目标
	 * 
	 * @param primaryTarget 主要目标（直接命中的目标）
	 * @param originPoint 爆炸原点
	 * @param nearbyTargets 附近目标列表（会被修改）
	 * @param filterModules 是否过滤模块（如果主目标是母舰，则移除其模块）
	 * @param filterShielded 是否过滤被护盾保护的目标
	 * @param filterBlocked 是否过滤被其他物体遮挡的目标
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static void filterObscuredTargets(CombatEntityAPI primaryTarget, Vector2f originPoint, 
											  List nearbyTargets, boolean filterModules, 
											  boolean filterShielded, boolean filterBlocked) {
		Iterator<CombatEntityAPI> iter = nearbyTargets.iterator();
		
		while (iter.hasNext()) {
			CombatEntityAPI nearbyTarget = iter.next();
			
			// 跳过无碰撞的实体
			if (nearbyTarget.getCollisionClass() == CollisionClass.NONE) {
				iter.remove();
				continue;
			}

			// 过滤模块：如果目标是主目标的模块，则移除
			if (filterModules && (nearbyTarget instanceof ShipAPI)) {
				ShipAPI ship = (ShipAPI) nearbyTarget;
				if (ship.getParentStation() == primaryTarget) {
					iter.remove();
					continue;
				}
			}

			// 获取目标上最近的伤害点
			Vector2f nearestPoint = getNearestPointForDamage(originPoint, nearbyTarget);

			boolean remove = false;
			
			// 检查是否被其他目标遮挡
			for (Object otherTarget : nearbyTargets) {
				if (!(otherTarget instanceof CombatEntityAPI)) {
					continue;
				}
				CombatEntityAPI otherEntity = (CombatEntityAPI) otherTarget;

				// 只检查同阵营的遮挡物，且不是自己
				if ((nearbyTarget.getOwner() != otherEntity.getOwner()) || (otherEntity == nearbyTarget)) {
					continue;
				}

				// 检查是否被护盾遮挡
				if (filterShielded && (otherEntity.getShield() != null)) {
					ShieldAPI shield = otherEntity.getShield();
					if (shield.isWithinArc(nearestPoint) && shield.isOn() && 
						MathUtils.isWithinRange(nearestPoint, shield.getLocation(), shield.getRadius())) {
						remove = true;
						break;
					}
				}

				// 检查是否被实体本体遮挡
				if (filterBlocked && CollisionUtils.getCollides(originPoint, nearestPoint, 
																otherEntity.getLocation(), 
																otherEntity.getCollisionRadius())) {
					// 更新边界信息（LazyLib的workaround）
					BoundsAPI bounds = otherEntity.getExactBounds();
					if (bounds != null) {
						bounds.update(otherEntity.getLocation(), otherEntity.getFacing());
					}
					
					// 精确碰撞检测
					if (CollisionUtils.getCollisionPoint(nearestPoint, originPoint, otherEntity) != null) {
						remove = true;
						break;
					}
				}
			}

			// 如果被遮挡，则移除
			if (remove) {
				iter.remove();
			}
		}
	}

	/**
	 * 使用Grid遍历获取矩形区域内的所有弹丸
	 * 适用于光束、光剑等线性武器的检测
	 * 
	 * @param center 中心位置
	 * @param width 宽度
	 * @param height 高度
	 * @return 区域内的弹丸列表
	 */
	public static List<DamagingProjectileAPI> getAllProjectilesInRect(Vector2f center, float width, float height) {
		List<DamagingProjectileAPI> result = new ArrayList<>();
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return result;

		java.util.Iterator<Object> iter = engine.getAllObjectGrid().getCheckIterator(
			center, width, height
		);

		while (iter.hasNext()) {
			Object obj = iter.next();
			if (!(obj instanceof DamagingProjectileAPI)) continue;
			
			DamagingProjectileAPI proj = (DamagingProjectileAPI) obj;
			if (proj.isExpired() || !engine.isEntityInPlay(proj)) continue;
			
			result.add(proj);
		}

		return result;
	}

	// ==================== 极光(Aurora)特效 ====================
	//
	// 原版 com.fs.starfarer.api.impl.campaign.terrain.AuroraRenderer 的战斗可用封装。
	// 原版 advance() 依赖 Global.getSector().getClock()，战斗/标题界面里不可靠，phaseAngle 又是私有字段，
	// 所以下面的 AuroraRing 按原版算法移植（双光带反向流动 + 外缘脉动 + 外缘摆动 + 纹理平铺）。
	// 参数对象 AuroraParams 仍实现原版 AuroraRenderer.AuroraRendererDelegate，也可以直接交给原版渲染器。
	//
	// 用法：
	//   // 1) 固定位置，完整显示 3 秒（另有 0.5 秒淡入、0.5 秒淡出）
	//   Moci_Util.spawnAurora(point, 80f, 260f, new Color(100, 200, 255), 3f);
	//
	//   // 2) 跟随舰船，舰船死亡/变残骸/离场时自动淡出；duration < 0 表示一直存在
	//   Moci_Util.AuroraCombatPlugin aura = Moci_Util.spawnAuroraOnEntity(ship, 60f, 220f, new Color(120, 255, 180), -1f);
	//   aura.fadeOutAndExpire(); // 需要时手动结束
	//
	//   // 3) 完整参数
	//   Moci_Util.spawnAurora(new Moci_Util.AuroraParams()
	//       .setAnchor(ship).setRadius(50f, 300f)
	//       .setColor(new Color(255, 120, 220)).setAlphaMult(0.8f)
	//       .setShortenMult(0.85f).setPhaseSpeed(30f)
	//       .setLifetime(0.3f, 5f, 1f)
	//       .setLayer(CombatEngineLayers.ABOVE_SHIPS_LAYER));
	//
	// 需要按角度变色/变粗时，继承 AuroraParams 覆写 getAuroraColorForAngle / getAuroraThicknessMult 等方法。
	// 自己的渲染器里也可以直接 new AuroraRing(params)，每帧调用 advance(amount) 和 render(alphaMult)。

	/** 原版极光纹理的 settings.json 分类（graphics/planets/aurorae.png，512x512，左右并排两条 256px 光带） */
	public static final String AURORA_TEXTURE_CATEGORY = "terrain";
	/** 原版极光纹理的 settings.json 键名 */
	public static final String AURORA_TEXTURE_KEY = "aurora";
	/** 原版相位速度（度/秒）：原版每游戏日转 180°，CampaignClock.SECONDS_PER_GAME_DAY = 10，即 18°/秒 */
	public static final float AURORA_VANILLA_PHASE_SPEED = 18f;
	/** 原版每段弧长（像素），同时是纹理平铺和外缘摆动幅度的基准 */
	public static final float AURORA_VANILLA_PIXELS_PER_SEGMENT = 50f;

	private static final int AURORA_MAX_ERROR_REPORTS = 5;
	private static int auroraErrorReports = 0;

	/**
	 * 极光参数，同时实现原版 AuroraRenderer.AuroraRendererDelegate。
	 * 默认值取自原版 SpatialAnomalyTerrainPlugin：aurorae.png 纹理、(255,165,100) 橙色、shortenMult 0.5、其余倍率 1。
	 * 原版其他预设：MagneticFieldTerrainPlugin 的 shortenMult 为 0（只流动不脉动），
	 * StarCoronaTerrainPlugin 的 shortenMult 为 0.85（脉动更强）。
	 *
	 * 外缘在 outerRadius ± (outerRadius - innerRadius) * shortenMult 范围内脉动，另有 wobble 像素的摆动。
	 * phaseSpeed、wobble、分段、alphaMult、生命周期、layer 只对 AuroraRing / AuroraCombatPlugin 生效，原版渲染器不读取。
	 * 子类覆写 getAuroraXxx(angle) 可以做按角度变化的效果，angle 为世界角度 0~360，0 指向 +X。
	 * estimateMaxExtent() 默认沿一圈取 16 个角度估算最远绘制距离；倍率在取样角之间有尖峰时请覆写它，避免被视口裁剪。
	 * 生成后修改字段会实时生效（layer 除外）。
	 */
	public static class AuroraParams implements AuroraRenderer.AuroraRendererDelegate {
		/** estimateMaxExtent 沿一圈取样的角度数 */
		private static final int EXTENT_SAMPLES = 16;

		/** 内半径 */
		public float innerRadius = 50f;
		/** 外半径（外缘脉动的中心位置） */
		public float outerRadius = 200f;
		/** 没有锚点时使用的中心点 */
		public final Vector2f center = new Vector2f();
		/** 锚点实体；设置后中心点跟随它的位置 */
		public CombatEntityAPI anchor = null;
		/** 锚点死亡、变成残骸、过期或离开战场时是否自动淡出（AuroraCombatPlugin 使用） */
		public boolean expireWithAnchor = true;

		/** 基础颜色，alpha 通道也参与计算 */
		public Color color = new Color(255, 165, 100, 255);
		/** 整体透明度倍率 */
		public float alphaMult = 1f;
		/** 外缘脉动幅度（相对内外半径差），0 = 不脉动 */
		public float shortenMult = 0.5f;
		/** 内缘随脉动内收的倍率（原版内缘位移为外缘的 10%） */
		public float innerOffsetMult = 1f;
		/** 厚度倍率 */
		public float thicknessMult = 1f;
		/** 额外厚度（像素） */
		public float thicknessFlat = 0f;
		/** 纹理沿圆周方向的平铺密度倍率 */
		public float texPerSegmentMult = 1f;
		/**
		 * 锁定整圈纹理平铺次数（以首次绘制时算出的为准，只对 AuroraRing 生效）。
		 * 平铺次数按「周长 / 厚度」取整，半径逐帧变化（例如随技能强度缩放）时会一格一格跳变，整圈纹理随之突跳；
		 * 开启后不再跳变，代价是厚度变化时纹理会沿圆周方向被拉伸/压缩。
		 */
		public boolean lockTexTiling = false;
		/** 纹理中单条光带的宽度（像素），原版 aurorae.png 为 256 */
		public float bandWidthInTexture = 256f;
		/** 可选的原版遮挡器，见 setBlocker() */
		public RangeBlockerUtil blocker = null;

		/** 相位速度（度/秒），原版为 18 */
		public float phaseSpeed = AURORA_VANILLA_PHASE_SPEED;
		/** 外缘摆动幅度（像素），原版为 50 * 0.33 = 16.5 */
		public float wobble = AURORA_VANILLA_PIXELS_PER_SEGMENT * 0.33f;
		/** 期望的每段弧长（像素），越小越圆滑；原版固定 50 */
		public float segmentLength = 20f;
		/** 最少分段数（原版没有下限，小半径时只有十几段，外缘呈锯齿状） */
		public int minSegments = 120;
		/** 最多分段数 */
		public int maxSegments = 720;

		/** 淡入时间（秒） */
		public float fadeIn = 0.5f;
		/** 完整显示时间（秒，不含淡入淡出），小于 0 表示一直存在 */
		public float duration = -1f;
		/** 淡出时间（秒） */
		public float fadeOut = 0.5f;
		/** 战斗渲染层，需要在 spawnAurora 之前设置 */
		public CombatEngineLayers layer = CombatEngineLayers.BELOW_SHIPS_LAYER;

		private String textureCategory = AURORA_TEXTURE_CATEGORY;
		private String textureKey = AURORA_TEXTURE_KEY;
		private transient SpriteAPI texture = null;
		private transient boolean textureFailed = false;

		public AuroraParams setRadius(float innerRadius, float outerRadius) {
			this.innerRadius = innerRadius;
			this.outerRadius = outerRadius;
			return this;
		}

		/** 设置固定中心点（复制坐标，之后修改传入的向量不影响极光） */
		public AuroraParams setCenter(Vector2f loc) {
			if (loc != null) center.set(loc);
			return this;
		}

		/** 设置跟随的实体，null = 不跟随 */
		public AuroraParams setAnchor(CombatEntityAPI anchor) {
			this.anchor = anchor;
			if (anchor != null && anchor.getLocation() != null) center.set(anchor.getLocation());
			return this;
		}

		public AuroraParams setExpireWithAnchor(boolean expireWithAnchor) {
			this.expireWithAnchor = expireWithAnchor;
			return this;
		}

		public AuroraParams setColor(Color color) {
			if (color != null) this.color = color;
			return this;
		}

		public AuroraParams setAlphaMult(float alphaMult) {
			this.alphaMult = alphaMult;
			return this;
		}

		/** 淡入、完整显示、淡出时间（秒）；duration 小于 0 表示一直存在 */
		public AuroraParams setLifetime(float fadeIn, float duration, float fadeOut) {
			this.fadeIn = fadeIn;
			this.duration = duration;
			this.fadeOut = fadeOut;
			return this;
		}

		public AuroraParams setLayer(CombatEngineLayers layer) {
			if (layer != null) this.layer = layer;
			return this;
		}

		public AuroraParams setShortenMult(float shortenMult) {
			this.shortenMult = shortenMult;
			return this;
		}

		public AuroraParams setThickness(float thicknessMult, float thicknessFlat) {
			this.thicknessMult = thicknessMult;
			this.thicknessFlat = thicknessFlat;
			return this;
		}

		/** 半径会逐帧变化时开启，见 lockTexTiling */
		public AuroraParams setLockTexTiling(boolean lockTexTiling) {
			this.lockTexTiling = lockTexTiling;
			return this;
		}

		public AuroraParams setPhaseSpeed(float degreesPerSecond) {
			this.phaseSpeed = degreesPerSecond;
			return this;
		}

		public AuroraParams setWobble(float wobble) {
			this.wobble = wobble;
			return this;
		}

		public AuroraParams setSegments(float segmentLength, int minSegments, int maxSegments) {
			this.segmentLength = segmentLength;
			this.minSegments = minSegments;
			this.maxSegments = maxSegments;
			return this;
		}

		/** 恢复原版分段方式：每段 50 像素、不设实际下限（小半径下会出现原版那种锯齿感） */
		public AuroraParams useVanillaSegments() {
			return setSegments(AURORA_VANILLA_PIXELS_PER_SEGMENT, 3, Integer.MAX_VALUE);
		}

		/** 按 settings.json graphics 分类 + 键名加载纹理；纹理需像 aurorae.png 一样左右并排两条光带 */
		public AuroraParams setTexture(String category, String key, float bandWidthInTexture) {
			this.textureCategory = category;
			this.textureKey = key;
			this.bandWidthInTexture = bandWidthInTexture;
			this.texture = null;
			this.textureFailed = false;
			return this;
		}

		/** 直接指定纹理（左右并排两条光带，bandWidthInTexture 为单条宽度）；null = 恢复按分类 + 键名加载 */
		public AuroraParams setTexture(SpriteAPI sprite, float bandWidthInTexture) {
			this.texture = sprite;
			this.textureFailed = false;
			this.bandWidthInTexture = bandWidthInTexture;
			return this;
		}

		/**
		 * 设置原版遮挡器。注意 new RangeBlockerUtil(...) 的各角度上限初始为 0，
		 * 需要先 updateAndSync()（依赖战役实体）或自行写入上限，否则极光宽度为 0、看不见。
		 */
		public AuroraParams setBlocker(RangeBlockerUtil blocker) {
			this.blocker = blocker;
			return this;
		}

		/** 估算最远绘制距离（视口裁剪用）：沿一圈均匀取 16 个角度的倍率取最大值；倍率随角度剧烈变化时可覆写 */
		public float estimateMaxExtent() {
			float radStart = Math.max(0f, getAuroraInnerRadius());
			float radEnd = Math.max(radStart + 10f, getAuroraOuterRadius());
			float thickness = radEnd - radStart;
			float max = radStart;
			for (int i = 0; i < EXTENT_SAMPLES; i++) {
				float angle = i * (360f / EXTENT_SAMPLES);
				float pulse = Math.abs(thickness * getAuroraShortenMult(angle));
				float outer = radStart + Math.abs(thickness * getAuroraThicknessMult(angle)) + pulse
						+ Math.abs(getAuroraThicknessFlat(angle));
				float inner = radStart + pulse * 0.1f * Math.abs(getAuroraInnerOffsetMult(angle));
				max = Math.max(max, Math.max(outer, inner));
			}
			return max + Math.abs(wobble);
		}

		@Override
		public float getAuroraInnerRadius() {
			return innerRadius;
		}

		@Override
		public float getAuroraOuterRadius() {
			return outerRadius;
		}

		@Override
		public Vector2f getAuroraCenterLoc() {
			return anchor != null ? anchor.getLocation() : center;
		}

		@Override
		public Color getAuroraColorForAngle(float angle) {
			return color;
		}

		@Override
		public float getAuroraAlphaMultForAngle(float angle) {
			return 1f;
		}

		@Override
		public float getAuroraShortenMult(float angle) {
			return shortenMult;
		}

		@Override
		public float getAuroraInnerOffsetMult(float angle) {
			return innerOffsetMult;
		}

		@Override
		public float getAuroraThicknessMult(float angle) {
			return thicknessMult;
		}

		@Override
		public float getAuroraThicknessFlat(float angle) {
			return thicknessFlat;
		}

		@Override
		public float getAuroraTexPerSegmentMult() {
			return texPerSegmentMult;
		}

		@Override
		public float getAuroraBandWidthInTexture() {
			return bandWidthInTexture;
		}

		@Override
		public SpriteAPI getAuroraTexture() {
			if (texture != null || textureFailed) return texture;
			try {
				SpriteAPI sprite = Global.getSettings().getSprite(textureCategory, textureKey);
				if (sprite != null && sprite.getTextureId() == 0) {
					// 纹理未加载时补加载一次
					Global.getSettings().loadTexture(Global.getSettings().getSpriteName(textureCategory, textureKey));
					sprite = Global.getSettings().getSprite(textureCategory, textureKey);
				}
				texture = sprite;
				if (texture == null) reportAuroraError("找不到极光纹理: " + textureCategory + "/" + textureKey, null);
			} catch (Exception e) {
				texture = null;
				reportAuroraError("加载极光纹理失败: " + textureCategory + "/" + textureKey, e);
			}
			textureFailed = texture == null;
			return texture;
		}

		@Override
		public RangeBlockerUtil getAuroraBlocker() {
			return blocker;
		}
	}

	/**
	 * 原版 AuroraRenderer 的移植版：几何、纹理平铺、双光带反向流动、外缘脉动和摆动都按原版算法计算。
	 * 与原版的区别：
	 *  - 相位按 params.phaseSpeed（度/秒）推进，不依赖 Sector 时钟，战斗、标题界面都能用；
	 *  - 分段数可调（原版固定每段 50 像素），纹理平铺仍按原版 50 像素基准计算，分段数只影响圆滑程度；
	 *  - alpha 夹在 0~255（原版直接转 byte，超过 255 会溢出变暗）；
	 *  - 在 finally 中恢复 GL 状态；几何数据复用数组，每帧不产生临时对象。
	 * 一个 AuroraRing 对应一个 AuroraParams，可以在任意渲染回调里使用。
	 */
	public static class AuroraRing {
		private static final int HARD_MAX_SEGMENTS = 4096;

		private final AuroraParams params;
		private float phaseAngle = 0f;
		private float lastMaxExtent = 0f;

		private int capacity = 0;
		private float[] innerX, innerY, outerX, outerY, texV;
		private byte[] rgba;
		private int trigSegments = -1;
		private float[] segCos, segSin;
		private float leftTX0, rightTX0, leftTX1, rightTX1;
		/** params.lockTexTiling 开启时记下的首帧平铺数据；0 = 尚未记录 */
		private float lockedTotalTex = 0f, lockedTexStart1 = 0f;

		public AuroraRing(AuroraParams params) {
			this.params = params != null ? params : new AuroraParams();
		}

		/** 推进相位，amount 为秒；暂停时传 0 或不调用 */
		public void advance(float amount) {
			if (!(amount > 0f) || !Float.isFinite(amount)) return;
			float next = phaseAngle + amount * params.phaseSpeed;
			if (Float.isFinite(next)) phaseAngle = Misc.normalizeAngle(next);
		}

		/** 绘制一帧（世界坐标），alphaMult 为额外透明度，会再乘 params.alphaMult */
		public void render(float alphaMult) {
			float alpha = alphaMult * params.alphaMult;
			if (!(alpha > 0f)) return;
			SpriteAPI tex = params.getAuroraTexture();
			if (tex == null) return;
			Vector2f loc = params.getAuroraCenterLoc();
			if (loc == null || !Float.isFinite(loc.x) || !Float.isFinite(loc.y)) return;

			int segments = buildGeometry(tex, alpha);
			if (segments <= 0) return;

			GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
			GL11.glPushMatrix();
			try {
				GL11.glTranslatef(loc.x, loc.y, 0f);
				GL11.glEnable(GL11.GL_TEXTURE_2D);
				tex.bindTexture();
				GL11.glEnable(GL11.GL_BLEND);
				GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);

				int stride = segments + 1;
				for (int iter = 0; iter < 2; iter++) {
					float leftTX = iter == 0 ? leftTX0 : leftTX1;
					float rightTX = iter == 0 ? rightTX0 : rightTX1;
					int base = iter * stride;
					GL11.glBegin(GL11.GL_QUAD_STRIP);
					for (int i = 0; i < stride; i++) {
						int k = base + i;
						int c = k * 4;
						GL11.glColor4ub(rgba[c], rgba[c + 1], rgba[c + 2], rgba[c + 3]);
						GL11.glTexCoord2f(leftTX, texV[k]);
						GL11.glVertex2f(innerX[k], innerY[k]);
						GL11.glTexCoord2f(rightTX, texV[k]);
						GL11.glVertex2f(outerX[k], outerY[k]);
					}
					GL11.glEnd();
				}
			} finally {
				GL11.glPopMatrix();
				GL11.glPopAttrib();
			}
		}

		/** 计算两条光带的顶点（相对中心）、纹理坐标和颜色，返回分段数；数据异常时返回 0，本帧不绘制 */
		private int buildGeometry(SpriteAPI tex, float alphaMult) {
			float radStart = params.getAuroraInnerRadius();
			float radEnd = params.getAuroraOuterRadius();
			if (!Float.isFinite(radStart) || !Float.isFinite(radEnd)) return 0;
			if (radStart < 0f) radStart = 0f;
			if (radEnd < radStart + 10f) radEnd = radStart + 10f;
			float thickness = radEnd - radStart;

			float circ = (float) (Math.PI * 2f * (radStart + radEnd) / 2f);
			int segments = computeSegments(circ);
			ensureCapacity(segments);
			updateTrig(segments);

			// 纹理平铺：按原版每段 50 像素的基准算出整圈平铺次数，再均分到实际分段上
			float bandWidthInTexture = params.getAuroraBandWidthInTexture();
			float imageWidth = tex.getWidth();
			float imageHeight = tex.getHeight();
			if (!(imageWidth > 0f) || !(imageHeight > 0f)) return 0;
			int refSegments = Math.max(1, Math.round(circ / AURORA_VANILLA_PIXELS_PER_SEGMENT));
			float refTexPerSegment = AURORA_VANILLA_PIXELS_PER_SEGMENT * tex.getTextureHeight() / imageHeight
					* bandWidthInTexture / thickness * params.getAuroraTexPerSegmentMult();
			float totalTex = Math.max(1f, Math.round(refTexPerSegment * refSegments));
			if (!Float.isFinite(totalTex)) return 0;
			// 原版第二条光带接着第一条的纹理进度继续，起点多走了一段
			float texStart1 = totalTex + totalTex / refSegments;
			if (params.lockTexTiling) {
				// 半径逐帧变化时沿用首帧的平铺次数，避免取整跳变导致整圈纹理突跳
				if (lockedTotalTex > 0f) {
					totalTex = lockedTotalTex;
					texStart1 = lockedTexStart1;
				} else {
					lockedTotalTex = totalTex;
					lockedTexStart1 = texStart1;
				}
			} else {
				lockedTotalTex = 0f;
			}
			float texPerSegment = totalTex / segments;

			float texWidth = tex.getTextureWidth();
			leftTX0 = texWidth * bandWidthInTexture / imageWidth;
			rightTX0 = 2f * texWidth * bandWidthInTexture / imageWidth - 0.001f;
			leftTX1 = 0f;
			rightTX1 = texWidth * bandWidthInTexture / imageWidth - 0.001f;

			RangeBlockerUtil blocker = params.getAuroraBlocker();
			float phaseRadBase = (float) Math.toRadians(phaseAngle);
			float wobble = Float.isFinite(params.wobble) ? params.wobble : 0f;
			float anglePerSegment = (float) (Math.PI * 2f) / segments;
			int stride = segments + 1;
			float maxExtent = 0f;

			for (int iter = 0; iter < 2; iter++) {
				float texStart = iter == 0 ? 0f : texStart1;
				for (int i = 0; i < stride; i++) {
					int segIndex = i % segments;
					float phaseRad;
					if (iter == 0) {
						phaseRad = phaseRadBase + (segIndex * anglePerSegment * 10f);
					} else {
						phaseRad = -phaseRadBase + (segIndex * anglePerSegment * 5f);
					}

					float angle = (float) Math.toDegrees(segIndex * anglePerSegment);
					if (iter == 1) {
						angle += 180f;
						if (angle >= 360f) angle -= 360f;
					}

					float blockerMax = 100000f;
					if (blocker != null) {
						blockerMax = blocker.getCurrMaxAt(angle) * 0.75f;
						if (blockerMax > blocker.getMaxRange()) blockerMax = blocker.getMaxRange();
					}

					float pulseSin = (float) Math.sin(phaseRad);
					float pulseCos = (float) Math.cos(phaseRad);
					float pulseMax = thickness * params.getAuroraShortenMult(angle);
					if (pulseMax > blockerMax * 0.5f) pulseMax = blockerMax * 0.5f;
					float pulseAmount = pulseSin * pulseMax;
					float pulseInner = pulseAmount * 0.1f * params.getAuroraInnerOffsetMult(angle);

					float rInner = radStart - pulseInner;
					if (rInner < radStart * 0.9f) rInner = radStart * 0.9f;
					float rOuter = radStart + thickness * params.getAuroraThicknessMult(angle) - pulseAmount
							+ params.getAuroraThicknessFlat(angle);
					if (blocker != null) {
						if (rOuter > blockerMax - pulseAmount) {
							rOuter = blockerMax - pulseAmount;
							if (rOuter < radStart) rOuter = radStart;
						}
						if (rInner > rOuter) rInner = rOuter;
					}
					if (!Float.isFinite(rInner) || !Float.isFinite(rOuter)) return 0;

					float cos = segCos[segIndex];
					float sin = segSin[segIndex];
					float x1 = cos * rInner;
					float y1 = sin * rInner;
					float x2 = cos * rOuter + pulseCos * wobble;
					float y2 = sin * rOuter + pulseSin * wobble;
					if (iter == 1) {
						// 原版在两条光带之间 glRotatef(180)，这里直接把坐标取反，效果相同
						x1 = -x1;
						y1 = -y1;
						x2 = -x2;
						y2 = -y2;
					}

					Color color = params.getAuroraColorForAngle(angle);
					if (color == null) return 0;
					float a = params.getAuroraAlphaMultForAngle(angle);
					if (blocker != null) a *= blocker.getAlphaAt(angle);
					int alphaByte = (int) (color.getAlpha() * alphaMult * a);
					if (alphaByte < 0) alphaByte = 0;
					if (alphaByte > 255) alphaByte = 255;

					int k = iter * stride + i;
					innerX[k] = x1;
					innerY[k] = y1;
					outerX[k] = x2;
					outerY[k] = y2;
					texV[k] = texStart + i * texPerSegment;
					int c = k * 4;
					rgba[c] = (byte) color.getRed();
					rgba[c + 1] = (byte) color.getGreen();
					rgba[c + 2] = (byte) color.getBlue();
					rgba[c + 3] = (byte) alphaByte;

					float extent = Math.max(Math.abs(rInner), Math.abs(rOuter) + Math.abs(wobble));
					if (extent > maxExtent) maxExtent = extent;
				}
			}
			lastMaxExtent = maxExtent;
			return segments;
		}

		private int computeSegments(float circumference) {
			int maxSeg = Math.max(3, Math.min(HARD_MAX_SEGMENTS, params.maxSegments));
			int minSeg = Math.max(3, Math.min(maxSeg, params.minSegments));
			int segments = minSeg;
			float len = params.segmentLength;
			if (len > 0f && Float.isFinite(len) && Float.isFinite(circumference)) {
				segments = Math.round(circumference / len);
			}
			if (segments < minSeg) segments = minSeg;
			if (segments > maxSeg) segments = maxSeg;
			return segments;
		}

		private void ensureCapacity(int segments) {
			int needed = (segments + 1) * 2;
			if (needed <= capacity) return;
			// 留 50% 余量，半径逐帧变化时不会每帧重新分配
			capacity = Math.min(needed + needed / 2, (HARD_MAX_SEGMENTS + 1) * 2);
			innerX = new float[capacity];
			innerY = new float[capacity];
			outerX = new float[capacity];
			outerY = new float[capacity];
			texV = new float[capacity];
			rgba = new byte[capacity * 4];
		}

		private void updateTrig(int segments) {
			if (segments == trigSegments) return;
			if (segCos == null || segCos.length < segments) {
				int len = Math.max(segments, capacity / 2);
				segCos = new float[len];
				segSin = new float[len];
			}
			float anglePerSegment = (float) (Math.PI * 2f) / segments;
			for (int i = 0; i < segments; i++) {
				float theta = anglePerSegment * i;
				segCos[i] = (float) Math.cos(theta);
				segSin[i] = (float) Math.sin(theta);
			}
			trigSegments = segments;
		}

		public AuroraParams getParams() {
			return params;
		}

		public float getPhaseAngle() {
			return phaseAngle;
		}

		/** 设置相位（度），可让多个同时生成的极光错开 */
		public void setPhaseAngle(float degrees) {
			if (Float.isFinite(degrees)) phaseAngle = Misc.normalizeAngle(degrees);
		}

		/** 上一次绘制时最远顶点到中心的距离 */
		public float getLastMaxExtent() {
			return lastMaxExtent;
		}
	}

	/**
	 * 战斗内的极光渲染插件：淡入 → 持续 → 淡出，跟随锚点，暂停时冻结，按参数估算视口裁剪半径。
	 * 一般通过 Moci_Util.spawnAurora(...) / spawnAuroraOnEntity(...) 创建。
	 */
	public static class AuroraCombatPlugin extends BaseCombatLayeredRenderingPlugin {
		private static final float RENDER_RADIUS_PADDING = 50f;
		/** 锚点可能在加入战场之前就被绑定（例如在 applyEffectsAfterShipCreation 中生成），这段时间内不因"不在场"判定死亡 */
		private static final float ANCHOR_SPAWN_GRACE = 1f;

		private final AuroraParams params;
		private final AuroraRing ring;
		private final EnumSet<CombatEngineLayers> activeLayers;
		private float elapsed = 0f;
		private boolean fadingOut = false;
		private float fadeOutElapsed = 0f;
		private float fadeOutStartAlpha = 1f;
		private boolean expired = false;
		private boolean anchorSeenInPlay = false;

		public AuroraCombatPlugin(AuroraParams params) {
			super(params != null && params.layer != null ? params.layer : CombatEngineLayers.BELOW_SHIPS_LAYER);
			this.params = params != null ? params : new AuroraParams();
			this.ring = new AuroraRing(this.params);
			this.activeLayers = EnumSet.of(layer);
		}

		@Override
		public void init(CombatEntityAPI entity) {
			super.init(entity);
			syncHostLocation();
		}

		@Override
		public void advance(float amount) {
			if (expired) return;
			CombatEngineAPI engine = Global.getCombatEngine();
			if (engine == null || engine.isPaused()) return;
			if (!(amount > 0f) || !Float.isFinite(amount)) return;

			elapsed += amount;
			ring.advance(amount);

			if (!fadingOut) {
				if (params.anchor != null && params.expireWithAnchor && !isAnchorAlive(engine, params.anchor)) {
					beginFadeOut();
				} else if (params.duration >= 0f && elapsed >= auroraNonNegative(params.fadeIn) + params.duration) {
					beginFadeOut();
				}
			} else {
				fadeOutElapsed += amount;
				if (fadeOutElapsed >= auroraNonNegative(params.fadeOut)) expired = true;
			}
			syncHostLocation();
		}

		@Override
		public void render(CombatEngineLayers currLayer, ViewportAPI viewport) {
			if (expired) return;
			float alpha = getCurrentAlpha() * (viewport != null ? viewport.getAlphaMult() : 1f);
			if (!(alpha > 0f)) return;
			try {
				ring.render(alpha);
			} catch (RuntimeException e) {
				expired = true;
				reportAuroraError("极光渲染异常，已停止该特效", e);
			}
		}

		@Override
		public float getRenderRadius() {
			if (expired) return RENDER_RADIUS_PADDING;
			try {
				float r = Math.max(params.estimateMaxExtent(), ring.getLastMaxExtent());
				if (!Float.isFinite(r) || r < 0f) r = ring.getLastMaxExtent();
				return r + RENDER_RADIUS_PADDING;
			} catch (RuntimeException e) {
				expired = true;
				reportAuroraError("极光裁剪半径计算异常，已停止该特效", e);
				return RENDER_RADIUS_PADDING;
			}
		}

		@Override
		public EnumSet<CombatEngineLayers> getActiveLayers() {
			return activeLayers;
		}

		@Override
		public boolean isExpired() {
			return expired;
		}

		/** 按 params.fadeOut 淡出后移除 */
		public void fadeOutAndExpire() {
			beginFadeOut();
		}

		/** 立即移除 */
		public void expireNow() {
			expired = true;
		}

		public boolean isFadingOut() {
			return fadingOut;
		}

		/** 当前淡入淡出进度对应的透明度（0~1） */
		public float getCurrentAlpha() {
			if (expired) return 0f;
			if (fadingOut) {
				float fadeOut = auroraNonNegative(params.fadeOut);
				if (fadeOut <= 0f) return 0f;
				return fadeOutStartAlpha * Math.max(0f, 1f - fadeOutElapsed / fadeOut);
			}
			float fadeIn = auroraNonNegative(params.fadeIn);
			if (fadeIn > 0f && elapsed < fadeIn) return elapsed / fadeIn;
			return 1f;
		}

		public AuroraParams getParams() {
			return params;
		}

		public AuroraRing getRing() {
			return ring;
		}

		private void beginFadeOut() {
			if (fadingOut || expired) return;
			fadeOutStartAlpha = getCurrentAlpha();
			fadingOut = true;
			fadeOutElapsed = 0f;
			if (!(auroraNonNegative(params.fadeOut) > 0f)) expired = true;
		}

		private void syncHostLocation() {
			setAuroraHostLocation(entity, params.getAuroraCenterLoc());
		}

		private boolean isAnchorAlive(CombatEngineAPI engine, CombatEntityAPI anchor) {
			if (anchor.isExpired()) return false;
			if (engine.isEntityInPlay(anchor)) {
				anchorSeenInPlay = true;
			} else if (anchorSeenInPlay || elapsed > ANCHOR_SPAWN_GRACE) {
				return false;
			}
			if (anchor instanceof ShipAPI) {
				ShipAPI ship = (ShipAPI) anchor;
				return ship.isAlive() && !ship.isHulk();
			}
			return true;
		}
	}

	/**
	 * 在战斗中生成极光（完整参数版）。
	 *
	 * @param params 极光参数，生成后修改字段会实时生效（layer 除外）
	 * @return 极光插件，可调用 fadeOutAndExpire() 提前结束；不在战斗中时返回 null
	 */
	public static AuroraCombatPlugin spawnAurora(AuroraParams params) {
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return null;
		AuroraCombatPlugin plugin = new AuroraCombatPlugin(params);
		CombatEntityAPI host = engine.addLayeredRenderingPlugin(plugin);
		setAuroraHostLocation(host, plugin.getParams().getAuroraCenterLoc());
		return plugin;
	}

	/**
	 * 在固定位置生成极光，淡入、淡出各 0.5 秒。
	 *
	 * @param center 中心点（复制坐标）
	 * @param innerRadius 内半径
	 * @param outerRadius 外半径
	 * @param color 颜色
	 * @param duration 完整显示时长（秒，不含淡入淡出），小于 0 表示一直存在直到 fadeOutAndExpire()
	 * @return 极光插件；不在战斗中时返回 null
	 */
	public static AuroraCombatPlugin spawnAurora(Vector2f center, float innerRadius, float outerRadius, Color color, float duration) {
		return spawnAurora(new AuroraParams()
				.setCenter(center)
				.setRadius(innerRadius, outerRadius)
				.setColor(color)
				.setLifetime(0.5f, duration, 0.5f));
	}

	/**
	 * 生成跟随实体的极光，实体死亡、变成残骸、过期或离开战场时自动淡出；淡入、淡出各 0.5 秒。
	 *
	 * @param anchor 跟随的实体（舰船、导弹、弹丸等）
	 * @param innerRadius 内半径
	 * @param outerRadius 外半径
	 * @param color 颜色
	 * @param duration 完整显示时长（秒，不含淡入淡出），小于 0 表示一直存在
	 * @return 极光插件；anchor 为 null 或不在战斗中时返回 null
	 */
	public static AuroraCombatPlugin spawnAuroraOnEntity(CombatEntityAPI anchor, float innerRadius, float outerRadius, Color color, float duration) {
		if (anchor == null) return null;
		return spawnAurora(new AuroraParams()
				.setAnchor(anchor)
				.setRadius(innerRadius, outerRadius)
				.setColor(color)
				.setLifetime(0.5f, duration, 0.5f));
	}

	private static void setAuroraHostLocation(CombatEntityAPI host, Vector2f loc) {
		if (host == null || loc == null || host.getLocation() == null) return;
		if (!Float.isFinite(loc.x) || !Float.isFinite(loc.y)) return;
		host.getLocation().set(loc);
	}

	private static float auroraNonNegative(float value) {
		return Float.isFinite(value) && value > 0f ? value : 0f;
	}

	// ==================== 圆环 / 螺旋(RingRenderer)特效 ====================
	// 原版 RingRenderer 不依赖战役时钟，直接复用其绘制算法。
	// 快捷生成：spawnRing(point, 80f, 120f, Color.CYAN, 3f);
	// 跟随实体：spawnRingOnEntity(ship, 80f, 120f, Color.CYAN, -1f);
	// 螺旋：spawnRing(new RingParams().setAnchor(ship).setRadius(0f, 240f)
	//         .setSpiral(true).setRotationSpeed(30f).setLifetime(0.3f, 3f, 0.5f));
	// 在自己的渲染回调中也可 new RingEffect(params)，自行调用 advance(amount) / render(alphaMult)。

	public static final String RING_TEXTURE_CATEGORY = "systemMap";
	public static final String RING_TEXTURE_KEY = "map_ring";
	private static int ringErrorReports = 0;

	/**
	 * 原版圆环渲染器的参数。默认使用星图圆环纹理、普通透明混合（非加法发光）。
	 * 半径为世界单位；scale 只缩放半径，不缩放中心坐标。
	 * 生成后可修改参数，layer 仅在创建插件时读取。
	 */
	public static class RingParams {
		public float innerRadius = 80f;
		public float outerRadius = 120f;
		public final Vector2f center = new Vector2f();
		public CombatEntityAPI anchor = null;
		public boolean expireWithAnchor = true;
		public Color color = Color.WHITE;
		public float alphaMult = 1f;
		/** false = 闭合圆环；true = 从外向内盘旋、两端渐隐的原版螺旋 */
		public boolean spiral = false;
		public float scale = 1f;
		/** 初始旋转角度（度）；正数逆时针 */
		public float angle = 0f;
		/** 旋转速度（度/秒），默认静止 */
		public float rotationSpeed = 0f;
		public float fadeIn = 0.5f;
		/** 完整显示时长，不含淡入淡出；小于 0 表示无限 */
		public float duration = -1f;
		public float fadeOut = 0.5f;
		public CombatEngineLayers layer = CombatEngineLayers.BELOW_SHIPS_LAYER;

		private String textureCategory = RING_TEXTURE_CATEGORY;
		private String textureKey = RING_TEXTURE_KEY;
		private transient RingRenderer renderer;
		private transient boolean textureFailed;

		public RingParams setRadius(float innerRadius, float outerRadius) {
			this.innerRadius = innerRadius;
			this.outerRadius = outerRadius;
			return this;
		}

		/** 复制固定中心坐标；已有锚点时仍优先跟随锚点，setAnchor(null) 可解除 */
		public RingParams setCenter(Vector2f loc) {
			if (loc != null) center.set(loc);
			return this;
		}

		public RingParams setAnchor(CombatEntityAPI anchor) {
			this.anchor = anchor;
			if (anchor != null && anchor.getLocation() != null) center.set(anchor.getLocation());
			return this;
		}

		public RingParams setExpireWithAnchor(boolean expireWithAnchor) {
			this.expireWithAnchor = expireWithAnchor;
			return this;
		}

		public RingParams setColor(Color color) {
			if (color != null) this.color = color;
			return this;
		}

		public RingParams setAlphaMult(float alphaMult) {
			this.alphaMult = alphaMult;
			return this;
		}

		public RingParams setSpiral(boolean spiral) {
			this.spiral = spiral;
			return this;
		}

		public RingParams setScale(float scale) {
			this.scale = scale;
			return this;
		}

		public RingParams setAngle(float degrees) {
			this.angle = degrees;
			return this;
		}

		public RingParams setRotationSpeed(float degreesPerSecond) {
			this.rotationSpeed = degreesPerSecond;
			return this;
		}

		public RingParams setLifetime(float fadeIn, float duration, float fadeOut) {
			this.fadeIn = fadeIn;
			this.duration = duration;
			this.fadeOut = fadeOut;
			return this;
		}

		public RingParams setLayer(CombatEngineLayers layer) {
			if (layer != null) this.layer = layer;
			return this;
		}

		/** settings.json 的 graphics 分类和键名，例如 systemMap/map_asteroid_belt */
		public RingParams setTexture(String category, String key) {
			textureCategory = category;
			textureKey = key;
			renderer = null;
			textureFailed = false;
			return this;
		}

		public Vector2f getCenterLoc() {
			return anchor != null ? anchor.getLocation() : center;
		}

		/** 圆环和螺旋均不超出缩放后的外半径 */
		public float estimateMaxExtent() {
			float extent = outerRadius * scale;
			return Float.isFinite(extent) && extent > 0f ? extent : 0f;
		}

		private RingRenderer getRenderer() {
			if (renderer != null || textureFailed) return renderer;
			try {
				SpriteAPI sprite = Global.getSettings().getSprite(textureCategory, textureKey);
				if (sprite != null && sprite.getTextureId() == 0) {
					Global.getSettings().loadTexture(Global.getSettings().getSpriteName(textureCategory, textureKey));
					sprite = Global.getSettings().getSprite(textureCategory, textureKey);
				}
				if (sprite == null || sprite.getTextureId() == 0
						|| !(sprite.getWidth() > 0f) || !Float.isFinite(sprite.getWidth())
						|| !(sprite.getHeight() > 0f) || !Float.isFinite(sprite.getHeight())) {
					throw new IllegalArgumentException("圆环纹理未加载或尺寸无效");
				}
				renderer = new RingRenderer(textureCategory, textureKey);
			} catch (Exception e) {
				textureFailed = true;
				reportRingError("加载圆环纹理失败: " + textureCategory + "/" + textureKey, e);
			}
			return renderer;
		}
	}

	/**
	 * 可在任意世界坐标渲染回调中使用的原版 RingRenderer 包装。
	 * advance 只推进旋转；自行使用时由调用方处理暂停、寿命和淡入淡出。
	 */
	public static class RingEffect {
		// 原版每段约 5 像素，螺旋会按圈数倍增。限制原版循环工作量，异常参数不进入 GL。
		private static final int MAX_RENDER_SEGMENTS = 65536;
		private final RingParams params;
		private final Vector2f localCenter = new Vector2f();
		private float rotation = 0f;
		private boolean invalidGeometryReported = false;

		public RingEffect(RingParams params) {
			this.params = params != null ? params : new RingParams();
		}

		public void advance(float amount) {
			if (!(amount > 0f) || !Float.isFinite(amount) || !Float.isFinite(params.rotationSpeed)) return;
			rotation = (float) ((rotation + (double) amount * params.rotationSpeed) % 360d);
		}

		/** alphaMult 再乘参数透明度和颜色 alpha，结果限制为 0~1，避免原版 byte 溢出 */
		public void render(float alphaMult) {
			float alpha = alphaMult * params.alphaMult;
			if (!(alpha > 0f) || !Float.isFinite(alpha) || params.color == null) return;
			Vector2f loc = params.getCenterLoc();
			if (loc == null || !Float.isFinite(loc.x) || !Float.isFinite(loc.y) || !Float.isFinite(params.angle)) return;
			if (!hasValidGeometry()) {
				if (!invalidGeometryReported) {
					invalidGeometryReported = true;
					reportRingError("圆环半径/缩放无效或超过 65536 段，本帧跳过绘制", null);
				}
				return;
			}
			RingRenderer renderer = params.getRenderer();
			if (renderer == null) return;

			GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_TEXTURE_BIT);
			GL11.glPushMatrix();
			try {
				GL11.glTranslatef(loc.x, loc.y, 0f);
				GL11.glRotatef((float) (((double) params.angle + rotation) % 360d), 0f, 0f, 1f);
				// 原版 factor 还会缩放 loc；使用局部原点，让 scale 只改变大小。
				renderer.render(localCenter, params.innerRadius, params.outerRadius,
						params.color, params.spiral, params.scale, Math.min(1f, alpha));
			} finally {
				GL11.glPopMatrix();
				GL11.glPopAttrib();
			}
		}

		/** 按原版 float 运算检查除数及圆环/螺旋循环上限，避免极薄螺旋产生海量迭代 */
		private boolean hasValidGeometry() {
			float min = params.innerRadius, max = params.outerRadius, scale = params.scale;
			if (!Float.isFinite(min) || !Float.isFinite(max) || !Float.isFinite(scale)
					|| min < 0f || !(max > min) || !(scale > 0f)) return false;
			float middle = (min + (max - min) / 2f) * scale;
			float thickness = (max - min) * scale;
			float radius = middle - thickness / 2f;
			float segments = Math.round((float) (Math.PI * 2f * middle) / 5f);
			if (!Float.isFinite(middle) || !Float.isFinite(thickness) || !(thickness > 0f)
					|| !Float.isFinite(radius) || radius < 0f || segments < 3f || segments > MAX_RENDER_SEGMENTS
					|| !Float.isFinite(max * scale)) return false;
			if (params.spiral) {
				float outer = radius + thickness;
				thickness = Math.min(thickness, outer * 0.25f);
				radius = outer - thickness;
				float turns = Math.max(2f, (radius - radius * 0.01f) / (thickness * 1.25f));
				return thickness > 0f && radius > 0f && Float.isFinite(turns)
						&& segments * turns <= MAX_RENDER_SEGMENTS;
			}
			return true;
		}

		public RingParams getParams() {
			return params;
		}
	}

	/** 战斗内自动推进、暂停冻结、跟随锚点、淡入淡出和视口裁剪的圆环插件 */
	public static class RingCombatPlugin extends BaseCombatLayeredRenderingPlugin {
		private final RingParams params;
		private final RingEffect ring;
		private final EnumSet<CombatEngineLayers> activeLayers;
		private float elapsed = 0f;
		private boolean fadingOut = false;
		private float fadeOutElapsed = 0f;
		private float fadeOutStartAlpha = 1f;
		private boolean expired = false;
		private boolean anchorSeenInPlay = false;

		public RingCombatPlugin(RingParams params) {
			super(params != null && params.layer != null ? params.layer : CombatEngineLayers.BELOW_SHIPS_LAYER);
			this.params = params != null ? params : new RingParams();
			this.ring = new RingEffect(this.params);
			this.activeLayers = EnumSet.of(layer);
		}

		@Override
		public void init(CombatEntityAPI entity) {
			super.init(entity);
			syncHostLocation();
		}

		@Override
		public void advance(float amount) {
			if (expired) return;
			CombatEngineAPI engine = Global.getCombatEngine();
			if (engine == null || engine.isPaused() || !(amount > 0f) || !Float.isFinite(amount)) return;
			elapsed += amount;
			ring.advance(amount);
			if (!fadingOut) {
				if (params.anchor != null && params.expireWithAnchor && !isAnchorAlive(engine)) {
					beginFadeOut();
				} else if (params.duration >= 0f && elapsed >= auroraNonNegative(params.fadeIn) + params.duration) {
					beginFadeOut();
				}
			} else {
				fadeOutElapsed += amount;
				if (fadeOutElapsed >= auroraNonNegative(params.fadeOut)) expired = true;
			}
			syncHostLocation();
		}

		@Override
		public void render(CombatEngineLayers currLayer, ViewportAPI viewport) {
			if (expired) return;
			try {
				ring.render(getCurrentAlpha() * (viewport != null ? viewport.getAlphaMult() : 1f));
			} catch (RuntimeException e) {
				expired = true;
				reportRingError("圆环渲染异常，已停止该特效", e);
			}
		}

		@Override
		public float getRenderRadius() {
			return params.estimateMaxExtent() + 50f;
		}

		@Override
		public EnumSet<CombatEngineLayers> getActiveLayers() {
			return activeLayers;
		}

		@Override
		public boolean isExpired() {
			return expired;
		}

		public void fadeOutAndExpire() {
			beginFadeOut();
		}

		public void expireNow() {
			expired = true;
		}

		public boolean isFadingOut() {
			return fadingOut;
		}

		public float getCurrentAlpha() {
			if (expired) return 0f;
			if (fadingOut) {
				float fadeOut = auroraNonNegative(params.fadeOut);
				return fadeOut > 0f ? fadeOutStartAlpha * Math.max(0f, 1f - fadeOutElapsed / fadeOut) : 0f;
			}
			float fadeIn = auroraNonNegative(params.fadeIn);
			return fadeIn > 0f && elapsed < fadeIn ? elapsed / fadeIn : 1f;
		}

		public RingParams getParams() {
			return params;
		}

		public RingEffect getRing() {
			return ring;
		}

		private void beginFadeOut() {
			if (fadingOut || expired) return;
			fadeOutStartAlpha = getCurrentAlpha();
			fadingOut = true;
			fadeOutElapsed = 0f;
			if (!(auroraNonNegative(params.fadeOut) > 0f)) expired = true;
		}

		private void syncHostLocation() {
			setAuroraHostLocation(entity, params.getCenterLoc());
		}

		private boolean isAnchorAlive(CombatEngineAPI engine) {
			CombatEntityAPI anchor = params.anchor;
			if (anchor.isExpired()) return false;
			if (engine.isEntityInPlay(anchor)) {
				anchorSeenInPlay = true;
			} else if (anchorSeenInPlay || elapsed > 1f) {
				return false;
			}
			if (anchor instanceof ShipAPI) {
				ShipAPI ship = (ShipAPI) anchor;
				return ship.isAlive() && !ship.isHulk();
			}
			return true;
		}
	}

	/** 在战斗中生成圆环/螺旋；引擎自动推进和渲染，无需调用方每帧更新；无战斗引擎时返回 null */
	public static RingCombatPlugin spawnRing(RingParams params) {
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return null;
		RingCombatPlugin plugin = new RingCombatPlugin(params);
		CombatEntityAPI host = engine.addLayeredRenderingPlugin(plugin);
		setAuroraHostLocation(host, plugin.getParams().getCenterLoc());
		return plugin;
	}

	/** 固定位置圆环；duration 为完整显示秒数，淡入/淡出各 0.5 秒，负数时手动结束 */
	public static RingCombatPlugin spawnRing(Vector2f center, float innerRadius, float outerRadius, Color color, float duration) {
		return spawnRing(new RingParams().setCenter(center).setRadius(innerRadius, outerRadius)
				.setColor(color).setLifetime(0.5f, duration, 0.5f));
	}

	/** 跟随实体的圆环；死亡、残骸、过期或离场时自动淡出；anchor 为 null 时返回 null */
	public static RingCombatPlugin spawnRingOnEntity(CombatEntityAPI anchor, float innerRadius, float outerRadius, Color color, float duration) {
		if (anchor == null) return null;
		return spawnRing(new RingParams().setAnchor(anchor).setRadius(innerRadius, outerRadius)
				.setColor(color).setLifetime(0.5f, duration, 0.5f));
	}

	// ==================== 原版闪现(Phase Skimmer)效果复用 ====================
	// 原版 DISPLACER / TELEPORTER 系统的三件事的脚本版：
	//   1. 残像交叉淡化(renderCopyDuringTeleport)  2. jitter 抖散  3. 镜头补偿后的平滑追随
	// 用于系统 type 不是 DISPLACER / TELEPORTER、由脚本自己改 ship.getLocation() 的场合。
	//   SkimmerBlink blink = new SkimmerBlink(ship);        // 每艘船持有一个实例
	//   blink.begin(dest, facingAtDest, 0.25f, 0.25f);      // 发起一次闪现(原版充能/收尾各 0.25 秒)
	//   if (blink.advance(amount)) { /* 这一帧刚完成瞬移 */ }  // 每帧调用
	//   blink.cancel();                                     // 中断时恢复外观
	// 说明文档：docs/Moci_Util_闪现效果使用说明.md

	/** 原版闪现(displacer.system)的 jitter 颜色 */
	public static final Color SKIMMER_JITTER_COLOR = new Color(100, 165, 255, 155);
	/** 原版镜头补偿的距离上限；瞬移距离达到该值时原版直接硬切镜头 */
	public static final float SKIMMER_CAMERA_MAX_DISTANCE = 2000f;
	private static boolean skimmerCameraBridgeFailed = false;

	/**
	 * 一次"原版闪现"的状态机：IN 阶段本体留在原地淡到半透明、目的地淡入残像；
	 * IN 结束的那一帧瞬移并补偿镜头；OUT 阶段本体在新位置恢复不透明、原位置残像淡出。
	 * 公开字段可在 begin() 之前随时修改。
	 * 注意：进行中会每帧改写 setExtraAlphaMult / setCopyLocation，不要与其他改写这两项的效果同时使用。
	 */
	public static class SkimmerBlink {
		public enum Phase { IDLE, IN, OUT }

		/** jitter 颜色；为 null 时不显示 jitter */
		public Color jitterColor = SKIMMER_JITTER_COLOR;
		/** jitter 强度倍率：1 为原版闪现的强度，0 为完全关闭(只剩残像交叉淡化) */
		public float jitterIntensity = 1f;
		/** jitter 份数：船体贴图被额外画多少份 */
		public int jitterCopies = 10;
		/** jitter 最大偏移 = jitterRange + jitterRangeRadiusFraction * 碰撞半径 */
		public float jitterRange = 0f;
		public float jitterRangeRadiusFraction = 0.5f;
		/** 是否显示残像并让本体半透明；false 时只保留 jitter、瞬移和镜头补偿 */
		public boolean renderCopy = true;
		/** 瞬移的是玩家舰船时，是否像原版一样补偿镜头 */
		public boolean compensateCamera = true;
		/** 进行中是否处于相位状态(原版闪现为 true)。本身带相位线圈的船不要开启 */
		public boolean phased = false;
		/** 瞬移瞬间的速度倍率：原版 DISPLACER 为 1，TELEPORTER 为 0.5 */
		public float velocityMultOnTeleport = 1f;

		private final ShipAPI ship;
		private final Object jitterKey = new Object();
		private final Vector2f destination = new Vector2f();
		private final Vector2f origin = new Vector2f();
		private Phase phase = Phase.IDLE;
		private float elapsed = 0f;
		private float inDuration = 0f;
		private float outDuration = 0f;
		private boolean hasDestinationFacing = false;
		private float destinationFacing = 0f;
		private float originFacing = 0f;
		/** 引擎副本当前的位置/朝向/透明度，公布给 collectShipCopies() 供尾巴等外部渲染跟随 */
		private final ShipCopyRenderer.ShipCopy engineCopy = new ShipCopyRenderer.ShipCopy();

		public SkimmerBlink(ShipAPI ship) {
			this.ship = ship;
		}

		/**
		 * 发起一次闪现。上一次还在 IN 阶段(尚未瞬移)时拒绝并返回 false；
		 * 上一次在 OUT 阶段时直接收尾并开始新的一次。
		 *
		 * @param dest         目的地，坐标会被复制
		 * @param facingAtDest 落地后的朝向；null 表示保持原朝向
		 * @param inSeconds    瞬移前的时长，原版 0.25
		 * @param outSeconds   瞬移后的时长，原版 0.25
		 */
		public boolean begin(Vector2f dest, Float facingAtDest, float inSeconds, float outSeconds) {
			if (ship == null || dest == null) return false;
			if (!Float.isFinite(dest.x) || !Float.isFinite(dest.y)) return false;
			if (phase == Phase.IN) return false;
			if (phase == Phase.OUT) restoreShip();
			destination.set(dest);
			hasDestinationFacing = facingAtDest != null && Float.isFinite(facingAtDest);
			destinationFacing = hasDestinationFacing ? facingAtDest : 0f;
			inDuration = Float.isFinite(inSeconds) ? Math.max(0f, inSeconds) : 0f;
			outDuration = Float.isFinite(outSeconds) ? Math.max(0f, outSeconds) : 0f;
			elapsed = 0f;
			phase = Phase.IN;
			return true;
		}

		/** IN 阶段内修正目的地(例如目标移动了)；其他阶段无效 */
		public void setDestination(Vector2f dest) {
			if (phase != Phase.IN || dest == null) return;
			if (!Float.isFinite(dest.x) || !Float.isFinite(dest.y)) return;
			destination.set(dest);
		}

		/**
		 * 每帧调用一次。
		 *
		 * @return 仅在这一帧完成了瞬移时返回 true，此时 ship.getLocation() 已是新位置
		 */
		public boolean advance(float amount) {
			if (phase == Phase.IDLE || ship == null) return false;
			CombatEngineAPI engine = Global.getCombatEngine();
			if (engine == null || !ship.isAlive() || !engine.isEntityInPlay(ship)) {
				cancel();
				return false;
			}

			boolean teleported = false;
			elapsed += Math.max(0f, amount);
			if (phase == Phase.IN && elapsed >= inDuration) {
				origin.set(ship.getLocation());
				originFacing = ship.getFacing();
				ship.getLocation().set(destination);
				if (hasDestinationFacing) ship.setFacing(destinationFacing);
				if (velocityMultOnTeleport != 1f) ship.getVelocity().scale(velocityMultOnTeleport);
				if (compensateCamera) compensateCameraForTeleport(ship, origin, destination);
				elapsed = Math.min(elapsed - inDuration, outDuration);
				phase = Phase.OUT;
				teleported = true;
			}
			if (phase == Phase.OUT && elapsed >= outDuration) {
				restoreShip();
				phase = Phase.IDLE;
				return teleported;
			}

			float level = getLevel();
			if (renderCopy) {
				if (phase == Phase.IN) {
					float shipAlpha = 1f - level * 0.5f;
					ship.setExtraAlphaMult(shipAlpha);
					engineCopy.location.set(destination);
					engineCopy.facing = hasDestinationFacing ? destinationFacing : ship.getFacing();
					engineCopy.alpha = 1f - shipAlpha;
				} else {
					ship.setExtraAlphaMult(1f - level * 0.5f);
					engineCopy.location.set(origin);
					engineCopy.facing = originFacing;
					engineCopy.alpha = level * 0.5f;
				}
				ship.setCopyLocation(engineCopy.location, engineCopy.alpha, engineCopy.facing);
				ship.setCustomData(SHIP_ENGINE_COPY_KEY, engineCopy);
			}
			if (jitterColor != null && jitterCopies > 0 && jitterIntensity > 0f) {
				ship.setJitter(jitterKey, jitterColor, level * jitterIntensity, jitterCopies, 0f,
						jitterRange + jitterRangeRadiusFraction * ship.getCollisionRadius());
			}
			if (phased) ship.setPhased(true);
			return teleported;
		}

		/** 立即中止：不瞬移，恢复透明度并清掉残像。已经瞬移过的不会回退 */
		public void cancel() {
			if (phase == Phase.IDLE) return;
			restoreShip();
			phase = Phase.IDLE;
		}

		public Phase getPhase() {
			return phase;
		}

		public boolean isBusy() {
			return phase != Phase.IDLE;
		}

		/** 当前强度 0..1：IN 阶段从 0 升到 1，OUT 阶段从 1 降到 0 */
		public float getLevel() {
			if (phase == Phase.IN) return inDuration > 0f ? Math.min(1f, elapsed / inDuration) : 1f;
			if (phase == Phase.OUT) return outDuration > 0f ? Math.max(0f, 1f - elapsed / outDuration) : 0f;
			return 0f;
		}

		/** 目的地的活引用，仅供读取 */
		public Vector2f getDestination() {
			return destination;
		}

		private void restoreShip() {
			if (ship == null) return;
			if (renderCopy) {
				ship.setExtraAlphaMult(1f);
				ship.setCopyLocation(null, 1f, ship.getFacing());
				ship.removeCustomData(SHIP_ENGINE_COPY_KEY);
			}
			if (phased) ship.setPhased(false);
		}
	}

	/**
	 * 原版闪现的镜头补偿：玩家舰船瞬移后镜头中心保持不动，再用平时鼠标平移的缓动滑向新位置。
	 * 在把 ship.getLocation() 改到 to 之后的同一帧调用。
	 * 与原版条件一致：只对玩家舰船生效；锁定目标视角、跟随其他实体、镜头被外部接管，
	 * 或瞬移距离达到 SKIMMER_CAMERA_MAX_DISTANCE 时不处理。
	 * 依赖混淆方法名，游戏版本变化导致失效时只记录一次日志并退化为不补偿(镜头硬切)。
	 *
	 * @return 是否做了补偿
	 */
	public static boolean compensateCameraForTeleport(ShipAPI ship, Vector2f from, Vector2f to) {
		if (skimmerCameraBridgeFailed || ship == null || from == null || to == null) return false;
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null || ship != engine.getPlayerShip()) return false;
		CombatUIAPI ui = engine.getCombatUI();
		if (ui == null || ui.getEntityToFollowV2() != null) return false;
		ViewportAPI viewport = engine.getViewport();
		if (viewport != null && viewport.isExternalControl()) return false;
		float dx = from.x - to.x;
		float dy = from.y - to.y;
		if (!Float.isFinite(dx) || !Float.isFinite(dy)) return false;
		if (dx * dx + dy * dy >= SKIMMER_CAMERA_MAX_DISTANCE * SKIMMER_CAMERA_MAX_DISTANCE) return false;
		try {
			return SkimmerCameraBridge.shiftViewOffset(ui, dx, dy);
		} catch (Throwable error) {
			skimmerCameraBridgeFailed = true;
			Global.getLogger(Moci_Util.class).error(
					"[Moci_Skimmer] 镜头补偿不可用(混淆方法名与当前游戏版本不匹配)，已退化为不补偿", error);
			return false;
		}
	}

	/**
	 * 镜头偏移量的访问桥。对应 0.98a：战斗界面(CombatState)的 getViewMouseOffset() 返回的对象
	 * 保存"镜头中心相对玩家舰船的偏移"，其 Ö00000() 返回当前偏移的活引用，
	 * Õ00000() 把偏移标记为"正在移动"，让它开始向鼠标决定的目标值缓动。
	 * 全部通过 MethodHandle 按名字查找，源码里不直接引用混淆类：
	 * 那些类的签名里带有名为 new 之类的包，IDE 的编译器解析不了，直接引用会让整个 Moci_Util 报错。
	 * 名字对不上时查找会抛异常，由 compensateCameraForTeleport 捕获。
	 */
	private static final class SkimmerCameraBridge {
		private static final String OFFSET_INTERFACE = "com.fs.starfarer.combat.L";
		private static final String GET_CURRENT_OFFSET = "Ö00000";
		private static final String MARK_MOVING = "Õ00000";

		private static Class<?> uiClass = null;
		private static MethodHandle isViewOnTarget = null;
		private static MethodHandle getViewMouseOffset = null;
		private static Class<?> offsetClass = null;
		private static MethodHandle getCurrentOffset = null;
		private static MethodHandle markMoving = null;

		static boolean shiftViewOffset(Object combatUI, float dx, float dy) throws Throwable {
			MethodHandles.Lookup lookup = MethodHandles.publicLookup();
			if (uiClass != combatUI.getClass()) {
				Class<?> type = combatUI.getClass();
				Class<?> offsetInterface = Class.forName(OFFSET_INTERFACE);
				isViewOnTarget = lookup.findVirtual(type, "isViewOnTarget", MethodType.methodType(boolean.class));
				getViewMouseOffset = lookup.findVirtual(type, "getViewMouseOffset",
						MethodType.methodType(offsetInterface));
				uiClass = type;
			}
			if ((boolean) isViewOnTarget.invoke(combatUI)) return false;
			Object offset = getViewMouseOffset.invoke(combatUI);
			if (offset == null) return false;
			if (offsetClass != offset.getClass()) {
				Class<?> type = offset.getClass();
				getCurrentOffset = lookup.findVirtual(type, GET_CURRENT_OFFSET, MethodType.methodType(Vector2f.class));
				markMoving = lookup.findVirtual(type, MARK_MOVING, MethodType.methodType(void.class));
				offsetClass = type;
			}
			Vector2f current = (Vector2f) getCurrentOffset.invoke(offset);
			if (current == null) return false;
			current.x += dx;
			current.y += dy;
			markMoving.invoke(offset);
			return true;
		}
	}

	// ---------- 多个整船残影 ----------
	// 引擎自带的 setCopyLocation 每艘船只有一个副本。ShipCopyRenderer 用同样的手法
	// (临时改位置/朝向/透明度后再调一次舰船自己的 render)画任意多个，武器、引擎、jitter 都在。
	//   ShipCopyRenderer renderer = Moci_Util.createShipCopyRenderer(ship);
	//   ShipCopyRenderer.ShipCopy copy = renderer.addCopy();
	//   copy.location.set(x, y); copy.facing = 90f; copy.alpha = 0.5f;   // 每帧随意改
	//   renderer.removeCopy(copy);   // 或 renderer.clear()
	//   renderer.expire();           // 不再需要时移除插件

	private static boolean shipCopyBridgeFailed = false;
	/** 舰船 customData 的键：当前挂在这艘船上的 ShipCopyRenderer */
	public static final String SHIP_COPY_RENDERER_KEY = "Moci_ShipCopyRenderer";
	/** 舰船 customData 的键：SkimmerBlink 正在显示的引擎副本(ShipCopy) */
	public static final String SHIP_ENGINE_COPY_KEY = "Moci_ShipEngineCopy";

	/**
	 * 把这艘船当前显示的所有残影(ShipCopyRenderer 的副本 + SkimmerBlink 的引擎副本)追加到 out。
	 * 给"自己画、不走舰船 render"的外部渲染用(例如纯白骑士的尾巴)：
	 * 这些东西引擎不会替它们在副本上再画一遍，需要自己照着副本的位置/朝向/透明度各画一份。
	 * 配套的两个约定：
	 *   1. 本体那一份的透明度要乘上 ship.getExtraAlphaMult()，否则本体隐藏时它还留在原地；
	 *   2. 副本那一份用 copy.alpha 代替 ship.getExtraAlphaMult()，位置用 transformToShipCopy() 换算。
	 */
	public static void collectShipCopies(ShipAPI ship, List<ShipCopyRenderer.ShipCopy> out) {
		if (ship == null || out == null) return;
		java.util.Map<String, Object> data = ship.getCustomData();
		if (data == null) return;
		Object renderer = data.get(SHIP_COPY_RENDERER_KEY);
		if (renderer instanceof ShipCopyRenderer && ((ShipCopyRenderer) renderer).isWorking()) {
			out.addAll(((ShipCopyRenderer) renderer).getCopies());
		}
		Object engineCopy = data.get(SHIP_ENGINE_COPY_KEY);
		if (engineCopy instanceof ShipCopyRenderer.ShipCopy) {
			out.add((ShipCopyRenderer.ShipCopy) engineCopy);
		}
	}

	/**
	 * 把"相对本体的世界坐标"换算到副本上：先绕本体转过(副本朝向 - 本体朝向)，再平移到副本位置。
	 * 角度类的量直接加上 copy.facing - ship.getFacing() 即可。
	 *
	 * @param worldPoint 本体上的某个世界坐标(不会被修改)
	 * @param out        结果写到这里并返回
	 */
	public static Vector2f transformToShipCopy(ShipAPI ship, ShipCopyRenderer.ShipCopy copy, Vector2f worldPoint,
			Vector2f out) {
		double delta = Math.toRadians(copy.facing - ship.getFacing());
		float cos = (float) Math.cos(delta);
		float sin = (float) Math.sin(delta);
		float dx = worldPoint.x - ship.getLocation().x;
		float dy = worldPoint.y - ship.getLocation().y;
		out.set(copy.location.x + dx * cos - dy * sin, copy.location.y + dx * sin + dy * cos);
		return out;
	}

	/**
	 * 在舰船所在的各渲染层里把整艘船额外画若干份。副本只是画面，没有碰撞和任何逻辑。
	 * 副本的透明度独立于本体：本体 setExtraAlphaMult(0) 隐藏时副本照常显示。
	 * 与引擎自带的 setCopyLocation 不同，画完后会恢复舰船的 combinedAlphaMult，
	 * 读这个值的外部渲染(自定义推进器、GraphicsLib 光照)不会在本体位置按副本的亮度显形。
	 * 舰船死亡或离场后自动失效。
	 */
	public static class ShipCopyRenderer extends BaseCombatLayeredRenderingPlugin {
		/** 一个副本；字段可随时修改，下一帧生效 */
		public static class ShipCopy {
			public final Vector2f location = new Vector2f();
			public float facing = 0f;
			/** 0..1，代替本体的 extraAlphaMult */
			public float alpha = 1f;
			/** 自身扭曲的强度 0..1：0 = 不扭曲，1 = 按渲染器的 warp 参数扭曲到最大。只扭曲这个副本的画面 */
			public float warp = 0f;
		}

		/** 自身扭曲的参数，对这个渲染器的所有副本生效；各副本用 ShipCopy.warp 控制强弱 */
		public final LocalWarpParams warpParams = new LocalWarpParams();
		/** 扭曲范围的半径，按舰船碰撞半径的倍数。要大到能罩住整个船体画面(含突出的武器) */
		public float warpExtentRadiusMult = 2.2f;

		private final ShipAPI ship;
		private final List<ShipCopy> copies = new ArrayList<>();
		private final EnumSet<CombatEngineLayers> activeLayers;
		private boolean expired = false;

		public ShipCopyRenderer(ShipAPI ship) {
			this.ship = ship;
			EnumSet<CombatEngineLayers> layers = EnumSet.noneOf(CombatEngineLayers.class);
			if (ship != null && ship.getActiveLayers() != null) layers.addAll(ship.getActiveLayers());
			// 敌我识别圈只属于本体
			layers.remove(CombatEngineLayers.FF_INDICATORS_LAYER);
			this.activeLayers = layers;
			if (ship != null) ship.setCustomData(SHIP_COPY_RENDERER_KEY, this);
		}

		public ShipCopy addCopy() {
			ShipCopy copy = new ShipCopy();
			if (ship != null) {
				copy.location.set(ship.getLocation());
				copy.facing = ship.getFacing();
			}
			copies.add(copy);
			return copy;
		}

		public void removeCopy(ShipCopy copy) {
			copies.remove(copy);
		}

		public void clear() {
			copies.clear();
		}

		public List<ShipCopy> getCopies() {
			return copies;
		}

		/** 移除插件；之后需要重新 createShipCopyRenderer */
		public void expire() {
			expired = true;
			copies.clear();
			if (ship != null && ship.getCustomData() != null
					&& ship.getCustomData().get(SHIP_COPY_RENDERER_KEY) == this) {
				ship.removeCustomData(SHIP_COPY_RENDERER_KEY);
			}
		}

		/** 副本的自身扭曲(ShipCopy.warp)是否可用；不可用时 warp 被忽略，副本照常显示 */
		public boolean isWarpAvailable() {
			return isWorking() && isLocalWarpAvailable();
		}

		/** 多副本渲染是否可用；false 表示引擎渲染方法与当前游戏版本不匹配，调用方应退回 setCopyLocation */
		public boolean isWorking() {
			return !shipCopyBridgeFailed && !expired;
		}

		@Override
		public void advance(float amount) {
			if (expired) return;
			CombatEngineAPI engine = Global.getCombatEngine();
			if (engine == null || ship == null || !ship.isAlive() || !engine.isEntityInPlay(ship)) {
				expire();
				return;
			}
			// 宿主实体跟着舰船，引擎才不会把插件当成屏幕外的东西裁掉
			if (entity != null) setAuroraHostLocation(entity, ship.getLocation());
		}

		@Override
		public void render(CombatEngineLayers currLayer, ViewportAPI viewport) {
			if (expired || shipCopyBridgeFailed || copies.isEmpty() || viewport == null) return;
			try {
				ShipCopyBridge.render(ship, currLayer, viewport, this);
			} catch (Throwable error) {
				shipCopyBridgeFailed = true;
				Global.getLogger(Moci_Util.class).error(
						"[Moci_ShipCopy] 多副本渲染不可用(引擎渲染方法与当前游戏版本不匹配)，已停用", error);
			}
		}

		@Override
		public float getRenderRadius() {
			// 副本可以离舰船很远，是否在屏幕内由舰船自己的 render 逐个判断
			return 1000000f;
		}

		@Override
		public EnumSet<CombatEngineLayers> getActiveLayers() {
			return activeLayers;
		}

		@Override
		public boolean isExpired() {
			return expired;
		}
	}

	/** 为舰船创建多副本渲染器；无战斗引擎或 ship 为 null 时返回 null */
	public static ShipCopyRenderer createShipCopyRenderer(ShipAPI ship) {
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null || ship == null) return null;
		ShipCopyRenderer plugin = new ShipCopyRenderer(ship);
		CombatEntityAPI host = engine.addLayeredRenderingPlugin(plugin);
		setAuroraHostLocation(host, ship.getLocation());
		return plugin;
	}

	/**
	 * 调用引擎舰船实现类的三参数 render(layer, viewport, boolean)，公开 API 里没有它。
	 * 做法与引擎画传送副本时完全相同(Ship.render 两参数版本的后半段)：
	 * 临时改位置、朝向、extraAlphaMult，调三参数的 render，再改回去。
	 *
	 * 比引擎多做一步：render 会把舰船的 combinedAlphaMult 留在"最后画的那一份"的透明度上，
	 * 而 Moci_EngineRender、GraphicsLib 光照等外部渲染都读 getCombinedAlphaMult()。
	 * 不恢复的话，本体隐藏时它们会按副本的亮度在本体位置画出推进器和轮廓(穿帮)。
	 * 所以画完后把 combinedAlphaMult 写回原值；该字段是私有的，用私有 MethodHandle 写。
	 *
	 * 全部通过 MethodHandle 查找，源码里不直接引用引擎实现类；对不上时查找抛异常，由调用方捕获。
	 */
	private static final class ShipCopyBridge {
		private static final String SHIP_CLASS = "com.fs.starfarer.combat.entities.Ship";
		private static final String COMBINED_ALPHA_FIELD = "combinedAlphaMult";

		private static Class<?> shipClass = null;
		private static Class<?> viewportClass = null;
		private static MethodHandle renderShip = null;
		private static MethodHandle setCombinedAlpha = null;

		static void render(final ShipAPI ship, final CombatEngineLayers layer, final ViewportAPI viewport,
				ShipCopyRenderer owner) throws Throwable {
			List<ShipCopyRenderer.ShipCopy> copies = owner.copies;
			if (shipClass != ship.getClass() || viewportClass != viewport.getClass()) {
				renderShip = MethodHandles.publicLookup().findVirtual(ship.getClass(), "render",
						MethodType.methodType(void.class, CombatEngineLayers.class, viewport.getClass(), boolean.class));
				// 私有字段只能在声明它的类上查找
				Class<?> declaring = ship.getClass();
				while (declaring != null && !SHIP_CLASS.equals(declaring.getName())) declaring = declaring.getSuperclass();
				if (declaring == null) throw new NoSuchFieldException(COMBINED_ALPHA_FIELD);
				setCombinedAlpha = MethodHandles.privateLookupIn(declaring, MethodHandles.lookup())
						.findSetter(declaring, COMBINED_ALPHA_FIELD, float.class);
				shipClass = ship.getClass();
				viewportClass = viewport.getClass();
			}

			Vector2f location = ship.getLocation();
			float x = location.x;
			float y = location.y;
			float facing = ship.getFacing();
			float alpha = ship.getExtraAlphaMult();
			float combinedAlpha = ship.getCombinedAlphaMult();
			try {
				for (int i = 0; i < copies.size(); i++) {
					ShipCopyRenderer.ShipCopy copy = copies.get(i);
					if (!(copy.alpha > 0f)) continue;
					location.set(copy.location);
					ship.setFacing(copy.facing);
					float copyAlpha = Math.min(1f, copy.alpha);
					if (copy.warp > 0.001f && isLocalWarpAvailable()) {
						// 扭曲的副本：先按完全不透明画进离屏纹理，贴回屏幕时再乘上副本的透明度
						float halfSize = ship.getCollisionRadius() * owner.warpExtentRadiusMult;
						if (!viewport.isNearViewport(copy.location, halfSize)) continue;
						ship.setExtraAlphaMult(1f);
						float time = Global.getCombatEngine().getTotalElapsedTime(false);
						boolean drawn = renderLocalWarp(new LocalWarpContent() {
							@Override
							public void draw() throws Throwable {
								renderShip.invoke(ship, layer, viewport, true);
							}
						}, copy.location, halfSize, copyAlpha, Math.min(1f, copy.warp), time, owner.warpParams);
						if (drawn) continue;
					}
					ship.setExtraAlphaMult(copyAlpha);
					renderShip.invoke(ship, layer, viewport, true);
				}
			} finally {
				location.set(x, y);
				ship.setFacing(facing);
				ship.setExtraAlphaMult(alpha);
				setCombinedAlpha.invoke(ship, combinedAlpha);
			}
		}
	}

	// ---------- 只扭曲一段画面自身的"局部扭曲" ----------
	// GraphicsLib 的 WaveDistortion 是屏幕空间的：范围内的背景、别的船、弹丸一起被扭曲。
	// 这里的做法不同：把要扭曲的那段画面先单独画进一张离屏纹理，再把这张纹理贴到一张会变形的网格上画回屏幕。
	// 只有这段画面自己变形，背景和其他东西完全不受影响；也不依赖 GraphicsLib 的着色器开关。
	// ShipCopyRenderer 已接好：给 ShipCopy.warp 赋 0..1 即可。其他画面要用，直接调 renderLocalWarp()。

	/** 局部扭曲的形状参数。三种变形可以叠加，各自乘上调用时给的强度 */
	public static class LocalWarpParams {
		/** 向中心收缩的程度 0..1：0 = 不收缩，0.8 = 中心附近的画面被吸到原来两成的范围里 */
		public float pinch = 0.6f;
		/** 中心处的最大旋转角(度)，越靠外转得越少，形成漩涡。负数反向旋转 */
		public float twistDegrees = 120f;
		/** 沿半径方向来回起伏的幅度(单位)，形成向外扩散的涟漪。0 = 没有涟漪 */
		public float rippleAmplitude = 12f;
		/** 从中心到边缘有几圈涟漪 */
		public float rippleWaves = 2.5f;
		/** 涟漪向外扩散的快慢(弧度/秒) */
		public float rippleSpeed = 12f;
		/** 变形网格每边的格数。越多越平滑、越费性能；扭转角很大时需要调高 */
		public int gridCells = 18;
	}

	/** 要被扭曲的那段绘制。在当前的世界坐标变换下照常画即可 */
	public interface LocalWarpContent {
		void draw() throws Throwable;
	}

	private static boolean localWarpFailed = false;
	private static boolean localWarpChecked = false;
	private static int localWarpFbo = 0;
	private static int localWarpTexture = 0;
	private static int localWarpWidth = 0;
	private static int localWarpHeight = 0;
	private static final FloatBuffer LOCAL_WARP_MODELVIEW = BufferUtils.createFloatBuffer(16);
	private static final FloatBuffer LOCAL_WARP_PROJECTION = BufferUtils.createFloatBuffer(16);
	private static final IntBuffer LOCAL_WARP_VIEWPORT = BufferUtils.createIntBuffer(16);
	private static final FloatBuffer LOCAL_WARP_WINDOW = BufferUtils.createFloatBuffer(3);

	/** 局部扭曲是否可用：需要 OpenGL 3.0 的帧缓冲对象，且之前没有出过错 */
	public static boolean isLocalWarpAvailable() {
		if (localWarpFailed) return false;
		try {
			return GLContext.getCapabilities().OpenGL30;
		} catch (Throwable noContext) {
			return false;
		}
	}

	/**
	 * 把 content 画的东西扭曲后显示。在渲染回调里、世界坐标变换下调用。
	 * 变形只发生在以 center 为中心、边长 2 * halfSize 的正方形内，边缘处变形为 0，所以与外面没有接缝；
	 * content 画到正方形以外的部分会被裁掉。
	 *
	 * @param alpha    整体透明度 0..1。content 自己应按完全不透明来画
	 * @param strength 扭曲强度 0..1，乘到 params 的各项上
	 * @param time     当前时间(秒)，只用于涟漪的相位
	 * @return true = 已处理(包括完全在屏幕外、什么都不用画的情况)；false = 不可用，调用方应自己照常画 content
	 */
	public static boolean renderLocalWarp(LocalWarpContent content, Vector2f center, float halfSize, float alpha,
			float strength, float time, LocalWarpParams params) throws Throwable {
		if (!isLocalWarpAvailable() || content == null || center == null || params == null) return false;
		if (!(halfSize > 0f)) return false;
		try {
			return renderLocalWarpImpl(content, center, halfSize, alpha, strength, time, params);
		} catch (LocalWarpUnsupported unsupported) {
			localWarpFailed = true;
			Global.getLogger(Moci_Util.class).error("[Moci_LocalWarp] 局部扭曲不可用，已停用：" + unsupported.getMessage());
			return false;
		}
	}

	private static final class LocalWarpUnsupported extends Exception {
		LocalWarpUnsupported(String message) {
			super(message);
		}
	}

	private static boolean renderLocalWarpImpl(LocalWarpContent content, Vector2f center, float halfSize, float alpha,
			float strength, float time, LocalWarpParams params) throws Throwable {
		// 世界坐标 -> 窗口像素 的仿射关系：取中心点和两个单位方向各投影一次
		LOCAL_WARP_MODELVIEW.clear();
		LOCAL_WARP_PROJECTION.clear();
		LOCAL_WARP_VIEWPORT.clear();
		GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, LOCAL_WARP_MODELVIEW);
		GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, LOCAL_WARP_PROJECTION);
		GL11.glGetInteger(GL11.GL_VIEWPORT, LOCAL_WARP_VIEWPORT);
		float[] origin = projectToWindow(center.x, center.y);
		float[] alongX = projectToWindow(center.x + 1f, center.y);
		float[] alongY = projectToWindow(center.x, center.y + 1f);
		if (origin == null || alongX == null || alongY == null) return false;
		float xToWinX = alongX[0] - origin[0];
		float xToWinY = alongX[1] - origin[1];
		float yToWinX = alongY[0] - origin[0];
		float yToWinY = alongY[1] - origin[1];

		int targetWidth = LOCAL_WARP_VIEWPORT.get(0) + LOCAL_WARP_VIEWPORT.get(2);
		int targetHeight = LOCAL_WARP_VIEWPORT.get(1) + LOCAL_WARP_VIEWPORT.get(3);
		if (targetWidth <= 0 || targetHeight <= 0) return false;

		// 正方形在窗口里占的矩形，只清空和绘制这一块
		float extentX = halfSize * (Math.abs(xToWinX) + Math.abs(yToWinX));
		float extentY = halfSize * (Math.abs(xToWinY) + Math.abs(yToWinY));
		int left = Math.max(0, (int) Math.floor(origin[0] - extentX) - 2);
		int bottom = Math.max(0, (int) Math.floor(origin[1] - extentY) - 2);
		int right = Math.min(targetWidth, (int) Math.ceil(origin[0] + extentX) + 2);
		int top = Math.min(targetHeight, (int) Math.ceil(origin[1] + extentY) + 2);
		if (right <= left || top <= bottom) return true;

		ensureLocalWarpTarget(targetWidth, targetHeight);

		boolean firstUse = !localWarpChecked;
		if (firstUse) {
			// 清掉别处遗留的 GL 错误标记，下面只检查自己这一轮有没有出错
			for (int i = 0; i < 16 && GL11.glGetError() != GL11.GL_NO_ERROR; i++) {
			}
		}

		// 第一步：把 content 画进离屏纹理
		int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
		GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
		try {
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, localWarpFbo);
			GL11.glEnable(GL11.GL_SCISSOR_TEST);
			GL11.glScissor(left, bottom, right - left, top - bottom);
			GL11.glClearColor(0f, 0f, 0f, 0f);
			GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
			content.draw();
		} finally {
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
			GL11.glPopAttrib();
		}

		// 第二步：把纹理贴到变形网格上画回屏幕。纹理里的颜色已经乘过透明度，所以用预乘混合
		int cells = Math.max(2, Math.min(64, params.gridCells));
		float clampedAlpha = Math.max(0f, Math.min(1f, alpha));
		float clampedStrength = Math.max(0f, Math.min(1f, strength));
		GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
		try {
			GL11.glEnable(GL11.GL_TEXTURE_2D);
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, localWarpTexture);
			GL11.glEnable(GL11.GL_BLEND);
			GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
			GL11.glColor4f(clampedAlpha, clampedAlpha, clampedAlpha, clampedAlpha);
			float step = 2f * halfSize / cells;
			float[] upper = new float[2];
			float[] lower = new float[2];
			for (int row = 0; row < cells; row++) {
				float y0 = -halfSize + row * step;
				float y1 = y0 + step;
				GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
				for (int col = 0; col <= cells; col++) {
					float x = -halfSize + col * step;
					warpOffset(x, y1, halfSize, clampedStrength, time, params, upper);
					warpOffset(x, y0, halfSize, clampedStrength, time, params, lower);
					// 贴图坐标取"变形前"的位置，顶点画在"变形后"的位置，画面就跟着网格变形
					GL11.glTexCoord2f((origin[0] + x * xToWinX + y1 * yToWinX) / localWarpWidth,
							(origin[1] + x * xToWinY + y1 * yToWinY) / localWarpHeight);
					GL11.glVertex2f(center.x + upper[0], center.y + upper[1]);
					GL11.glTexCoord2f((origin[0] + x * xToWinX + y0 * yToWinX) / localWarpWidth,
							(origin[1] + x * xToWinY + y0 * yToWinY) / localWarpHeight);
					GL11.glVertex2f(center.x + lower[0], center.y + lower[1]);
				}
				GL11.glEnd();
			}
		} finally {
			GL11.glPopAttrib();
		}

		if (firstUse) {
			localWarpChecked = true;
			int error = GL11.glGetError();
			if (error != GL11.GL_NO_ERROR) {
				throw new LocalWarpUnsupported("OpenGL 报错 0x" + Integer.toHexString(error));
			}
		}
		return true;
	}

	/**
	 * 网格上相对中心为 (x, y) 的点变形后的相对位置，写入 out。
	 * 影响力在中心为 1，到正方形的内切圆边缘降为 0，所以边缘和四角不动。
	 */
	private static void warpOffset(float x, float y, float halfSize, float strength, float time,
			LocalWarpParams params, float[] out) {
		out[0] = x;
		out[1] = y;
		float distance = (float) Math.sqrt(x * x + y * y);
		float normalized = distance / halfSize;
		if (strength <= 0f || normalized >= 1f || distance < 0.001f) return;
		float falloff = 1f - normalized * normalized;
		float influence = falloff * falloff;

		// 收缩：越靠近中心被吸得越狠
		float scale = 1f - strength * Math.max(0f, Math.min(0.95f, params.pinch)) * influence;
		// 涟漪：沿半径方向起伏，相位随时间向外推
		float ripple = strength * params.rippleAmplitude * falloff
				* (float) Math.sin(normalized * params.rippleWaves * 2f * Math.PI - time * params.rippleSpeed);
		float radius = distance * scale + ripple;
		// 漩涡：中心转得多，边缘不转
		double angle = Math.atan2(y, x) + Math.toRadians(strength * params.twistDegrees * influence);
		out[0] = radius * (float) Math.cos(angle);
		out[1] = radius * (float) Math.sin(angle);
	}

	private static float[] projectToWindow(float x, float y) {
		LOCAL_WARP_MODELVIEW.rewind();
		LOCAL_WARP_PROJECTION.rewind();
		LOCAL_WARP_VIEWPORT.rewind();
		LOCAL_WARP_WINDOW.clear();
		if (!GLU.gluProject(x, y, 0f, LOCAL_WARP_MODELVIEW, LOCAL_WARP_PROJECTION, LOCAL_WARP_VIEWPORT,
				LOCAL_WARP_WINDOW)) {
			return null;
		}
		return new float[] { LOCAL_WARP_WINDOW.get(0), LOCAL_WARP_WINDOW.get(1) };
	}

	/** 建立(或在窗口尺寸变化后重建)离屏纹理和帧缓冲。全局只有一份，所有局部扭曲轮流使用 */
	private static void ensureLocalWarpTarget(int width, int height) throws LocalWarpUnsupported {
		if (localWarpFbo != 0 && width == localWarpWidth && height == localWarpHeight) return;
		int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
		int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		try {
			if (localWarpTexture == 0) localWarpTexture = GL11.glGenTextures();
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, localWarpTexture);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA,
					GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);

			if (localWarpFbo == 0) localWarpFbo = GL30.glGenFramebuffers();
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, localWarpFbo);
			GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D,
					localWarpTexture, 0);
			int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
			if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
				throw new LocalWarpUnsupported("帧缓冲不完整，状态 0x" + Integer.toHexString(status));
			}
			localWarpWidth = width;
			localWarpHeight = height;
		} finally {
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
		}
	}

	/**
	 * 瞬移落点是否可用，判定与原版闪现一致：在地图内，且不与任何非战机舰船(含残骸)或小行星重叠。
	 * 同一空间站/模块舰的其他部分不算障碍。
	 */
	public static boolean isTeleportSpotFree(ShipAPI ship, Vector2f point) {
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null || ship == null || point == null) return false;
		if (!Float.isFinite(point.x) || !Float.isFinite(point.y)) return false;
		if (Math.abs(point.x) > engine.getMapWidth() * 0.5f) return false;
		if (Math.abs(point.y) > engine.getMapHeight() * 0.5f) return false;

		float radius = ship.getCollisionRadius();
		for (ShipAPI other : engine.getShips()) {
			if (other == ship || other.isFighter() || other.isShuttlePod()) continue;
			if (other.getParentStation() == ship || ship.getParentStation() == other) continue;
			if (ship.getParentStation() != null && other.getParentStation() == ship.getParentStation()) continue;
			float limit = other.getCollisionRadius() + radius;
			if (MathUtils.getDistanceSquared(point, other.getLocation()) < limit * limit) return false;
		}
		for (CombatEntityAPI asteroid : engine.getAsteroids()) {
			float limit = asteroid.getCollisionRadius() + radius;
			if (MathUtils.getDistanceSquared(point, asteroid.getLocation()) < limit * limit) return false;
		}
		return true;
	}

	/**
	 * 为期望落点找一个可用位置，搜索方式与原版闪现一致：
	 * 期望点可用就直接用，否则以碰撞半径为步长向外 10 圈、每圈 30 度一格，取第一个可用点。
	 *
	 * @return 新的坐标对象；全部被占时返回期望点的副本，desired 为 null 时返回 null
	 */
	public static Vector2f findFreeTeleportSpot(ShipAPI ship, Vector2f desired) {
		if (desired == null) return null;
		Vector2f result = new Vector2f(desired);
		if (ship == null || isTeleportSpotFree(ship, result)) return result;
		float radius = Math.max(1f, ship.getCollisionRadius());
		for (int ring = 1; ring <= 10; ring++) {
			for (float angle = 0f; angle < 360f; angle += 30f) {
				Vector2f candidate = MathUtils.getPointOnCircumference(desired, radius * ring, angle);
				if (isTeleportSpotFree(ship, candidate)) return candidate;
			}
		}
		return result;
	}

	/**
	 * 匀速直线假设下的拦截时间。弹体继承射手速度时(原版弹体都继承)必须传入 shooterVel。
	 *
	 * @param from        弹体出膛位置
	 * @param shooterVel  射手速度；弹体不继承速度时传 null
	 * @param projSpeed   弹体自身速度
	 * @return 命中所需秒数；追不上目标或参数非法时返回 -1
	 */
	public static float getInterceptTime(Vector2f from, Vector2f shooterVel, float projSpeed,
			Vector2f targetLoc, Vector2f targetVel) {
		if (from == null || targetLoc == null || !(projSpeed > 0f)) return -1f;
		float dx = targetLoc.x - from.x;
		float dy = targetLoc.y - from.y;
		float vx = (targetVel != null ? targetVel.x : 0f) - (shooterVel != null ? shooterVel.x : 0f);
		float vy = (targetVel != null ? targetVel.y : 0f) - (shooterVel != null ? shooterVel.y : 0f);

		// |d + v t| = s t  =>  (v.v - s^2) t^2 + 2 (d.v) t + d.d = 0
		float a = vx * vx + vy * vy - projSpeed * projSpeed;
		float b = 2f * (dx * vx + dy * vy);
		float c = dx * dx + dy * dy;
		if (c <= 0f) return 0f;

		float time;
		if (Math.abs(a) < 0.001f) {
			// 相对速度大小恰好等于弹速：方程退化为一次
			if (b >= 0f) return -1f;
			time = -c / b;
		} else {
			float disc = b * b - 4f * a * c;
			if (disc < 0f) return -1f;
			float root = (float) Math.sqrt(disc);
			float t1 = (-b - root) / (2f * a);
			float t2 = (-b + root) / (2f * a);
			float min = Math.min(t1, t2);
			float max = Math.max(t1, t2);
			time = min > 0f ? min : max;
		}
		return time > 0f && Float.isFinite(time) ? time : -1f;
	}

	/**
	 * 匀速直线假设下应当瞄准的世界坐标：炮口对准该点开火，弹体与目标同时到达。
	 * 弹体继承射手速度时返回点不是实际命中点，而是"炮口应指向的点"，直接用它算开火角即可。
	 *
	 * @return 新的坐标对象；追不上目标或参数非法时返回 null
	 */
	public static Vector2f getInterceptPoint(Vector2f from, Vector2f shooterVel, float projSpeed,
			Vector2f targetLoc, Vector2f targetVel) {
		float time = getInterceptTime(from, shooterVel, projSpeed, targetLoc, targetVel);
		if (time < 0f) return null;
		float vx = (targetVel != null ? targetVel.x : 0f) - (shooterVel != null ? shooterVel.x : 0f);
		float vy = (targetVel != null ? targetVel.y : 0f) - (shooterVel != null ? shooterVel.y : 0f);
		return new Vector2f(targetLoc.x + vx * time, targetLoc.y + vy * time);
	}

	private static void reportRingError(String message, Throwable error) {
		if (ringErrorReports >= 5) return;
		ringErrorReports++;
		Global.getLogger(Moci_Util.class).error("[Moci_Ring] " + message, error);
	}

	/** 极光相关错误最多记录 AURORA_MAX_ERROR_REPORTS 次，避免每帧刷屏 */
	private static void reportAuroraError(String message, Throwable error) {
		if (auroraErrorReports >= AURORA_MAX_ERROR_REPORTS) return;
		auroraErrorReports++;
		Global.getLogger(Moci_Util.class).error("[Moci_Aurora] " + message, error);
	}

	// ==================== 着陆 / 起飞动画 ====================
	//
	// 原版 ShipAPI.beginLandingAnimation / setAnimatedLaunch / abortLanding 的可控复刻，实现在 Moci_LandingAnimation。
	// 原版固定「2 秒悬停 + 1 秒下沉」「1 秒上升 + 3 秒阴影」，且会被原版 AI 或母舰死亡自动打断；
	// 这里各段时长、缩放下限、透明度曲线、阴影颜色 / 方向 / 距离、收盾 / 停火 / 锁指令 / 碰撞都可以配置，
	// 状态由 Moci_LandingAnimation 自己持有，支持暂停、变速、中途反向、落定后保持和回调。
	//
	// 注意：这套动画不设置原版的 isLanding 标记，ship.isLanding() / isFinishedLanding() 始终为 false，
	// 请用下面的 isLandingAnimated / isLandedAnimated / isLiftingOffAnimated 判断；
	// 也不要对同一艘船混用原版的 beginLandingAnimation / setAnimatedLaunch。
	//
	// 用法：
	//   // 1) 原版默认参数：着陆，落定后保持不可见，之后手动起飞
	//   Moci_Util.beginLandingAnimation(ship, carrier);
	//   if (Moci_Util.isLandedAnimated(ship)) Moci_Util.beginLaunchAnimation(ship, carrier);
	//
	//   // 2) 自定义参数 + 回调
	//   Moci_Util.beginLandingAnimation(ship, carrier, Moci_Util.landingAnimationParams()
	//       .setLandingTimes(0.6f, 0.8f)                      // 悬停 0.6 秒 + 下沉 0.8 秒
	//       .setMinScale(0.3f)                                // 缩到 30%（原版 50%）
	//       .setShadow(new Color(0, 0, 0, 140), 160f, 250f)   // 阴影更浓、更远、偏向左下
	//       .setCallback(new Moci_LandingAnimation.Callback() {
	//           public void onLanded(Moci_LandingAnimation anim) { /* 开始整备 */ }
	//       }));
	//
	//   // 3) 中途中止（从当前进度反向起飞）/ 立即还原
	//   Moci_Util.abortLandingAnimation(ship);
	//   Moci_Util.cancelLandingAnimation(ship);
	//
	// 暂停、变速、拖动进度、剩余时间等见 getLandingAnimation(ship) 返回的对象。

	/** 新建一份着陆 / 起飞动画参数，默认值全部等同原版 */
	public static Moci_LandingAnimation.Params landingAnimationParams() {
		return new Moci_LandingAnimation.Params();
	}

	/**
	 * 开始着陆动画（原版参数；这艘船之前用过自定义参数时沿用那一份）。
	 *
	 * @param ship 着陆的舰船
	 * @param carrier 母舰，决定阴影裁剪范围和「母舰丢失自动中止」；可以为 null（此时不画阴影）
	 * @return 动画对象；ship 为 null 时返回 null
	 */
	public static Moci_LandingAnimation beginLandingAnimation(ShipAPI ship, ShipAPI carrier) {
		return Moci_LandingAnimation.land(ship, carrier, null);
	}

	/**
	 * 开始着陆动画（自定义参数）。已经在着陆 / 已落定时只更新母舰和参数，不重新计时。
	 *
	 * @param params 参数，见 landingAnimationParams()；null = 沿用上一次的
	 */
	public static Moci_LandingAnimation beginLandingAnimation(ShipAPI ship, ShipAPI carrier, Moci_LandingAnimation.Params params) {
		return Moci_LandingAnimation.land(ship, carrier, params);
	}

	/**
	 * 开始起飞动画。已落定的舰船从完全沉入开始；着陆中途调用则从当前进度反向。
	 *
	 * @param carrier 母舰（阴影裁剪用），可以为 null
	 * @return 动画对象；ship 为 null 时返回 null
	 */
	public static Moci_LandingAnimation beginLaunchAnimation(ShipAPI ship, ShipAPI carrier) {
		return Moci_LandingAnimation.launch(ship, carrier, null);
	}

	/** 开始起飞动画（自定义参数），params 为 null 时沿用上一次的 */
	public static Moci_LandingAnimation beginLaunchAnimation(ShipAPI ship, ShipAPI carrier, Moci_LandingAnimation.Params params) {
		return Moci_LandingAnimation.launch(ship, carrier, params);
	}

	/**
	 * 中止着陆并从当前进度反向起飞（对应原版 abortLanding），会触发 onLandingAborted 回调。
	 *
	 * @return 是否确实中止了一次着陆
	 */
	public static boolean abortLandingAnimation(ShipAPI ship) {
		Moci_LandingAnimation anim = Moci_LandingAnimation.get(ship);
		return anim != null && anim.abortLanding();
	}

	/** 立即结束着陆 / 起飞动画并还原舰船的透明度、缩放和碰撞，不播放过渡 */
	public static void cancelLandingAnimation(ShipAPI ship) {
		Moci_LandingAnimation anim = Moci_LandingAnimation.get(ship);
		if (anim != null) anim.cancel();
	}

	/** 取舰船的着陆 / 起飞动画对象（暂停、变速、查询进度用）；从未播放过时返回 null */
	public static Moci_LandingAnimation getLandingAnimation(ShipAPI ship) {
		return Moci_LandingAnimation.get(ship);
	}

	/** 是否有着陆 / 起飞动画在进行（含落定后的保持阶段） */
	public static boolean isLandingAnimationActive(ShipAPI ship) {
		return Moci_LandingAnimation.isAnimating(ship);
	}

	/** 着陆中或已落定，对应原版 ship.isLanding() */
	public static boolean isLandingAnimated(ShipAPI ship) {
		Moci_LandingAnimation anim = Moci_LandingAnimation.get(ship);
		return anim != null && anim.isLanding();
	}

	/** 着陆动画已播完、处于落定状态，对应原版 ship.isFinishedLanding() */
	public static boolean isLandedAnimated(ShipAPI ship) {
		Moci_LandingAnimation anim = Moci_LandingAnimation.get(ship);
		return anim != null && anim.isLanded();
	}

	/** 起飞中且尚未恢复原大小（此时无碰撞、不可操控），对应原版 ship.isLiftingOff() */
	public static boolean isLiftingOffAnimated(ShipAPI ship) {
		Moci_LandingAnimation anim = Moci_LandingAnimation.get(ship);
		return anim != null && anim.isLiftingOff();
	}

	// ==================== 冲击波环(BlastWave)特效 ====================
	//
	// 一个从中心往外扩的发光圆环，仿 EVE Online 震荡炸弹的冲击波。纯画面，没有任何伤害判定。
	//
	// 它是怎么画出来的（只想调参可以跳过）：
	//   1. 画一个圆环。环的外半径按「半径曲线」随时间变大，环宽 = 外半径 × thickness。
	//   2. 环上的发光纹理 = 三张贴图相乘：两张火焰纹理沿着圆周往相反方向慢慢滚动，第三张负责「外缘亮、内缘暗」的渐变。
	//      纹理在径向被拉得很长，所以看起来是一根根往外放射的拉丝。
	//   3. 整个环的亮度按「亮度曲线」随时间变化（默认：一闪最亮 → 变暗 → 约 2 秒再闪一次 → 约 3.3 秒熄灭，4 秒移除）。
	//   4. 柔光(bloom)：把环加宽再用模糊过的纹理画一遍，叠在下面。
	//   5. 折射(refraction)：把环底下的屏幕画面截下来，按法线贴图偏移一下再贴回去，看起来像热浪扭曲。默认很弱，0 = 关闭。
	// 组合爆炸里的底光、火球、云壳、斜波不是这个环，见下方 BlastSphere / ShockBlast 部分。
	//
	// 贴图版权：graphics/fx/blastwave/ 下的 firetile_02a、firemask_01a、lasergradient_01f、warptunnel4_n、radial_hole_01
	// 均来自 EVE Online，版权归 CCP Games 所有，经作者确认后在本 mod 中使用。
	//
	// 用法：
	//   Moci_Util.spawnBlastWave(point, 400f, new Color(150, 225, 255));       // 外半径扩到 400，约 4 秒
	//   Moci_Util.spawnBlastWave(new Moci_Util.BlastWaveParams().setCenter(point)
	//       .setRadius(0f, 400f).setDuration(2.5f).setColor(color, 3.0f)
	//       .setBloom(1.1f, 3.5f, 1.0f).setRefraction(12f, 0.35f));
	// 显卡不支持 OpenGL 2.0 或着色器编译失败时，特效直接不画（日志前缀 [Moci_BlastWave]，只记一次），不会影响别的东西。

	public static final String BLASTWAVE_VERT = "data/shaders/Moci_blastwave.vert";
	public static final String BLASTWAVE_GLOW_FRAG = "data/shaders/Moci_blastwave_glow.frag";
	public static final String BLASTWAVE_REFRACT_FRAG = "data/shaders/Moci_blastwave_refract.frag";
	public static final String BLASTSPHERE_FRAG = "data/shaders/Moci_blastsphere.frag";
	/** 以下五张贴图来自 EVE Online，版权归 CCP Games 所有 */
	public static final String BLASTWAVE_TEX_FINE = "graphics/fx/blastwave/firetile_02a.png";
	public static final String BLASTWAVE_TEX_BAND = "graphics/fx/blastwave/firemask_01a.png";
	public static final String BLASTWAVE_TEX_MASK = "graphics/fx/blastwave/lasergradient_01f.png";
	public static final String BLASTWAVE_TEX_NORMAL = "graphics/fx/blastwave/warptunnel4_n.png";
	public static final String BLASTWAVE_TEX_AMPLITUDE = "graphics/fx/blastwave/radial_hole_01.png";

	/**
	 * 默认半径曲线，每行 {曲线秒, 值, 入切线, 出切线}。
	 * 值是相对的：以曲线终点（= 亮度曲线最后一行的时间）那一刻的值为 100%，对应 endRadius。
	 * 效果：1 秒到 endRadius 的 50%，2 秒 78%，3 秒 90%，4 秒 100%，先快后慢。
	 */
	public static final float[][] BLASTWAVE_RADIUS_CURVE = {
			{ -0.0041f, 0f, 0f, 3.3753f },
			{ 2.0f, 4.6315f, 0.7119f, 0.7201f },
			{ 6.9763f, 6.8589f, 0f, 0f } };
	/**
	 * 默认亮度曲线，每行 {曲线秒, 值, 入切线, 出切线}。值以整条曲线的最大值（2.0）为 100%。
	 * 效果：0.06 秒前不亮 → 0.08 秒瞬间最亮 → 1.8 秒降到 26% → 2.0 秒回升到 49%（第二次闪）→ 约 3.3 秒熄灭。
	 * 最后一行的时间（3.9914）同时决定了「曲线终点」，见 BlastWaveParams.duration。
	 * 倒数第二行的出切线是负的，曲线会在 3.99 秒之前就掉到 0 以下，低于 0 的部分按 0 处理，所以实际约 3.3 秒就黑了。
	 */
	public static final float[][] BLASTWAVE_BRIGHTNESS_CURVE = {
			{ -0.0025f, 0f, 0f, 0f },
			{ 0.0572f, 0f, 0f, 0f },
			{ 0.0784f, 2.0f, 0f, 0f },
			{ 1.7960f, 0.5160f, 0f, 0f },
			{ 1.9999f, 0.9841f, 0f, -1.7431f },
			{ 3.9914f, 0f, 0f, 0f } };


	/**
	 * 冲击波环的参数。半径都是世界单位。
	 * 特效生成后改这里的字段仍然会生效（每帧都会重新读），只有 layer 是创建时读一次。
	 *
	 * 【时间是怎么算的】
	 * 曲线里写的时间叫「曲线秒」。程序把亮度曲线最后一行的时间当作曲线终点，
	 * 再把整条曲线等比拉伸 / 压缩到 duration 秒里播完。
	 * 默认终点是 3.9914、duration 是 4，所以曲线秒 ≈ 真实秒；把 duration 改成 2，所有东西就快一倍。
	 */
	public static class BlastWaveParams {
		/** true = 环自己的发光纹理会带一点热浪般的抖动（只扭自己，不扭背景，几乎不费性能） */
		public boolean distortOwnLight;
		/** 组合爆炸内部用：斜波的随机相位，由 spawnShockBlast 填写 */
		private float frontPhase;
		/** 三道斜波（组合爆炸用）。null = 只画正面环 */
		public TiltedFronts fronts;
		/** 三道斜波的整体亮度倍率，0 = 不画斜波（不影响正面环） */
		public float tiltedFrontStrength = 0f;
		/** 斜波开关，按二进制位：1 = 第一道(low)，2 = 第二道(steep)，4 = 第三道(cross)；7 = 三道都画，5 = 只画第一、三道 */
		public int tiltedFrontMask = 7;
		/** 环的中心（固定位置） */
		public final Vector2f center = new Vector2f();
		/** 设置后中心每帧跟着这个实体走（比如以舰船为中心的系统脉冲）；它没了就停在最后位置把剩下的播完 */
		public CombatEntityAPI anchor = null;
		/** 一共播多少秒，到点自动移除。曲线会等比拉伸到这个时长里，见类注释 */
		public float duration = 4.0f;
		/** 环的外半径从 startRadius 扩到 endRadius。中间怎么扩由 radiusCurve 决定，曲线终点时正好等于 endRadius */
		public float startRadius = 0f;
		public float endRadius = 350f;
		/** 环宽 = 外半径 × thickness。0.36 = 内边缘在外半径的 64% 处；环越扩越宽 */
		public float thickness = 0.36f;
		/**
		 * 半径曲线 / 亮度曲线。每行 {曲线秒, 值, 入切线, 出切线}，时间必须从小到大。
		 * 值是相对的：半径曲线以终点那一刻的值为 100%；亮度曲线以最大值为 100%。所以整体乘个倍数不会有变化，只看形状。
		 * 入 / 出切线 = 曲线在这一点左边 / 右边的斜率，0 = 在这点放平；都填 0 时每两点之间是一段两头慢中间快的 S 形。
		 */
		public float[][] radiusCurve = BLASTWAVE_RADIUS_CURVE;
		public float[][] brightnessCurve = BLASTWAVE_BRIGHTNESS_CURVE;

		/** false = 不画发光（只剩折射扭曲） */
		public boolean glow = true;
		/** 环的颜色。alpha 也参与计算，相当于亮度倍率 */
		public Color color = new Color(158, 255, 251);
		/** 最亮时的亮度倍率（亮度曲线到 100% 时）。越大越亮、越容易过曝发白 */
		public float intensity = 3.0f;
		/** 环本身的轻微模糊，0 = 最锐利，越大越糊 */
		public float sharpBlur = 0.6f;
		/** 柔光：强度（0 = 关闭）、模糊程度、往外多溢出多宽（单位是环宽，1.0 = 外面再多出一个环宽） */
		public float bloomStrength = 1.1f;
		public float bloomBlur = 3.5f;
		public float bloomSpread = 1.0f;
		/**
		 * 第一张火焰纹理(firetile)：绕圈方向重复几次、径向重复几次、每秒绕圈滚动多少、每秒径向滚动多少。
		 * 绕圈次数会自动取整（不然接缝处会断开）。径向次数很小，所以纹理被拉成放射状的长丝。
		 */
		public float fineScaleU = 5f, fineScaleV = 0.15f, fineScrollU = 0.05f, fineScrollV = 0.10f;
		/** 第二张火焰纹理(firemask)：含义同上，绕圈方向和第一张反着滚 */
		public float bandScaleU = 7f, bandScaleV = 0.01f, bandScrollU = -0.05f, bandScrollV = 0.05f;

		/** 折射：背后画面最多被推开多少世界单位（环扩到 endRadius 时的值，随环变小 / 变暗而减弱）。0 = 关闭，也最省性能 */
		public float refractStrength = 4f;
		/** 折射方向：0 = 乱抖（热浪感），1 = 统一往外推（像透镜鼓包） */
		public float refractRadialPush = 0f;
		/** 折射的抖动有多「横」，越大越横向；一般不用动 */
		public float refractFlatten = 10f;
		/** 色散：红蓝通道错开的程度（彩边效果），0 = 关闭 */
		public float refractChroma = 0f;
		/** 折射用的法线纹理：重复次数与每秒滚动，含义同 fineScale 那组 */
		public float normalScaleU = 3f, normalScaleV = 3f, normalScrollU = 0.05f, normalScrollV = -0.05f;

		public CombatEngineLayers layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER;

		public BlastWaveParams setCenter(Vector2f loc) {
			if (loc != null) center.set(loc);
			return this;
		}

		public BlastWaveParams setAnchor(CombatEntityAPI anchor) {
			this.anchor = anchor;
			if (anchor != null && anchor.getLocation() != null) center.set(anchor.getLocation());
			return this;
		}

		public BlastWaveParams setRadius(float startRadius, float endRadius) {
			this.startRadius = startRadius;
			this.endRadius = endRadius;
			return this;
		}

		public BlastWaveParams setDuration(float duration) {
			this.duration = duration;
			return this;
		}

		public BlastWaveParams setThickness(float thickness) {
			this.thickness = thickness;
			return this;
		}

		/** 替换半径 / 亮度曲线；传 null 保持原样 */
		public BlastWaveParams setCurves(float[][] radiusCurve, float[][] brightnessCurve) {
			if (radiusCurve != null && radiusCurve.length > 0) this.radiusCurve = radiusCurve;
			if (brightnessCurve != null && brightnessCurve.length > 0) this.brightnessCurve = brightnessCurve;
			return this;
		}

		public BlastWaveParams setColor(Color color, float intensity) {
			if (color != null) this.color = color;
			this.intensity = intensity;
			return this;
		}

		public BlastWaveParams setGlow(boolean glow) {
			this.glow = glow;
			return this;
		}

		public BlastWaveParams setSharpBlur(float sharpBlur) {
			this.sharpBlur = sharpBlur;
			return this;
		}

		public BlastWaveParams setBloom(float strength, float blur, float spread) {
			bloomStrength = strength;
			bloomBlur = blur;
			bloomSpread = spread;
			return this;
		}

		public BlastWaveParams setFineTexture(float scaleU, float scaleV, float scrollU, float scrollV) {
			fineScaleU = scaleU;
			fineScaleV = scaleV;
			fineScrollU = scrollU;
			fineScrollV = scrollV;
			return this;
		}

		public BlastWaveParams setBandTexture(float scaleU, float scaleV, float scrollU, float scrollV) {
			bandScaleU = scaleU;
			bandScaleV = scaleV;
			bandScrollU = scrollU;
			bandScrollV = scrollV;
			return this;
		}

		public BlastWaveParams setNormalTexture(float scaleU, float scaleV, float scrollU, float scrollV) {
			normalScaleU = scaleU;
			normalScaleV = scaleV;
			normalScrollU = scrollU;
			normalScrollV = scrollV;
			return this;
		}

		/** strength 为 0 时关闭折射（也省掉屏幕拷贝） */
		public BlastWaveParams setRefraction(float strength, float radialPush) {
			refractStrength = strength;
			refractRadialPush = radialPush;
			return this;
		}

		public BlastWaveParams setRefractFlatten(float flatten) {
			refractFlatten = flatten;
			return this;
		}

		public BlastWaveParams setChroma(float chroma) {
			refractChroma = chroma;
			return this;
		}

		public BlastWaveParams setLayer(CombatEngineLayers layer) {
			if (layer != null) this.layer = layer;
			return this;
		}

		public Vector2f getCenterLoc() {
			if (anchor != null && anchor.getLocation() != null) return anchor.getLocation();
			return center;
		}

		/** 实际时长（秒），太小会被抬到 0.05 */
		public float getEffectiveDuration() {
			return Math.max(0.05f, duration);
		}

		/** 曲线终点 = 亮度曲线最后一行的时间。半径曲线比它长的部分不会播（默认半径曲线写到 6.98，实际只播到 3.99） */
		public float getCurveLife() {
			float[][] keys = brightnessCurve;
			float life = keys != null && keys.length > 0 ? keys[keys.length - 1][0] : 1f;
			return life > 0f ? life : 1f;
		}

		/**
		 * 已经播了 elapsed 秒时，环的外半径（世界单位）。
		 * = startRadius + (endRadius - startRadius) × (半径曲线当前值 / 半径曲线终点值)。
		 * 画面和冲击波伤害都用这个，保证两边对得上。
		 */
		public float getOuterRadiusAt(float elapsed) {
			float progress = Math.max(0f, Math.min(1f, elapsed / getEffectiveDuration()));
			float life = getCurveLife();
			float end = evalBlastWaveCurve(radiusCurve, life);
			float s = end != 0f ? evalBlastWaveCurve(radiusCurve, progress * life) / end : progress;
			return Math.max(0f, startRadius + (endRadius - startRadius) * s);
		}

		/** 已经播了 elapsed 秒时，环的内半径（世界单位）= 外半径 × (1 - thickness)，thickness 限制在 0.02～0.98 */
		public float getInnerRadiusAt(float elapsed) {
			float t = Math.max(0.02f, Math.min(0.98f, thickness));
			return getOuterRadiusAt(elapsed) * (1f - t);
		}
	}

	/**
	 * 读曲线：给一个时间 t，返回曲线在这一刻的值。keys 每行 {时间, 值, 入切线, 出切线}。
	 * 两个关键点之间用三次 Hermite 插值（就是「经过这两个点、并且在两端的斜率分别等于切线」的平滑曲线）。
	 * t 早于第一行就返回第一行的值，晚于最后一行就返回最后一行的值。
	 */
	public static float evalBlastWaveCurve(float[][] keys, float t) {
		if (keys == null || keys.length == 0) return 0f;
		if (t <= keys[0][0]) return keys[0][1];
		int last = keys.length - 1;
		if (t >= keys[last][0]) return keys[last][1];
		for (int i = 0; i < last; i++) {
			float[] a = keys[i], b = keys[i + 1];
			if (t > b[0]) continue;
			float d = b[0] - a[0];
			if (!(d > 0f)) return b[1];
			float u = (t - a[0]) / d, u2 = u * u, u3 = u2 * u;
			return a[1] * (2f * u3 - 3f * u2 + 1f) + b[1] * (-2f * u3 + 3f * u2)
					+ a[3] * d * (u3 - 2f * u2 + u) + b[2] * d * (u3 - u2);
		}
		return keys[last][1];
	}

	/** 冲击波环的战斗渲染插件，由 spawnBlastWave 创建；也可以在外部查询进度或提前结束 */
	public static class BlastWavePlugin extends BaseCombatLayeredRenderingPlugin {
		private final BlastWaveParams params;
		private final EnumSet<CombatEngineLayers> activeLayers;
		private final float seedU = (float) Math.random();
		private final float seedV = (float) Math.random();
		private float elapsed = 0f;
		private boolean expired = false;

		public BlastWavePlugin(BlastWaveParams params) {
			super(params != null && params.layer != null ? params.layer : CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);
			this.params = params != null ? params : new BlastWaveParams();
			this.activeLayers = EnumSet.of(layer);
		}

		@Override
		public void init(CombatEntityAPI entity) {
			super.init(entity);
			syncHostLocation();
		}

		@Override
		public void advance(float amount) {
			if (expired) return;
			CombatEngineAPI engine = Global.getCombatEngine();
			if (engine == null || engine.isPaused() || !(amount > 0f) || !Float.isFinite(amount)) return;
			elapsed += amount;
			if (params.anchor != null && (params.anchor.isExpired() || !engine.isEntityInPlay(params.anchor))) {
				// 锚点离场：停在最后位置继续播完
				params.center.set(params.anchor.getLocation());
				params.anchor = null;
			}
			if (elapsed >= getDuration()) expired = true;
			syncHostLocation();
		}

		@Override
		public void render(CombatEngineLayers currLayer, ViewportAPI viewport) {
			if (expired) return;
			float alpha = viewport != null ? viewport.getAlphaMult() : 1f;
			if (!(alpha > .001f)) return;
			float level = getBrightness() * alpha;
			if (!(level > .001f) && !(params.tiltedFrontStrength > 0f && params.fronts != null && getCurveTime() < params.fronts.endTime)) return;
			BlastWaveGL.render(this, level, alpha);
		}

		@Override
		public float getRenderRadius() {
			float r = Math.max(params.startRadius, params.endRadius);
			float extent = r * (1f + Math.max(0f, params.bloomSpread) * Math.max(0f, params.thickness)) + Math.abs(params.refractStrength);
			if (params.fronts != null && params.tiltedFrontStrength > 0f && (params.tiltedFrontMask & 7) != 0)
				extent = Math.max(extent, params.endRadius * params.fronts.maxScale);
			return extent + 50f;
		}

		@Override
		public EnumSet<CombatEngineLayers> getActiveLayers() {
			return activeLayers;
		}

		@Override
		public boolean isExpired() {
			return expired;
		}

		public void expireNow() {
			expired = true;
		}

		public BlastWaveParams getParams() {
			return params;
		}

		/** 播放进度 0..1 */
		public float getProgress() {
			return Math.max(0f, Math.min(1f, elapsed / getDuration()));
		}

		/** 当前播到曲线的第几「曲线秒」（= 进度 × 曲线终点时间） */
		public float getCurveTime() {
			return getProgress() * curveLife();
		}

		/** 当前外半径（世界单位）= startRadius + (endRadius - startRadius) × (半径曲线当前值 / 半径曲线终点值) */
		public float getOuterRadius() {
			return params.getOuterRadiusAt(elapsed);
		}

		/** 当前亮度 0..1 = 亮度曲线当前值 / 亮度曲线最大值（低于 0 按 0） */
		public float getBrightness() {
			float[][] keys = params.brightnessCurve;
			float peak = 0f;
			if (keys != null) for (float[] k : keys) peak = Math.max(peak, k[1]);
			if (!(peak > 0f)) return 0f;
			return Math.max(0f, Math.min(1f, evalBlastWaveCurve(keys, getCurveTime()) / peak));
		}

		private float getDuration() {
			return params.getEffectiveDuration();
		}

		private float curveLife() {
			return params.getCurveLife();
		}

		private void syncHostLocation() {
			if (entity != null && entity.getLocation() != null) entity.getLocation().set(params.getCenterLoc());
		}
	}

	/** 在战斗中生成冲击波环；引擎自动推进、渲染和移除；无战斗引擎时返回 null */
	public static BlastWavePlugin spawnBlastWave(BlastWaveParams params) {
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return null;
		BlastWavePlugin plugin = new BlastWavePlugin(params);
		CombatEntityAPI host = engine.addLayeredRenderingPlugin(plugin);
		if (host != null && host.getLocation() != null) host.getLocation().set(plugin.getParams().getCenterLoc());
		return plugin;
	}

	/** 固定位置冲击波环：原版曲线和时长（约 4 秒），外半径扩张到 radius */
	public static BlastWavePlugin spawnBlastWave(Vector2f center, float radius, Color color) {
		return spawnBlastWave(new BlastWaveParams().setCenter(center).setRadius(0f, radius).setColor(color, 3.0f));
	}

	// ---------- 球形发光层(BlastSphere)与组合爆炸(ShockBlast) ----------
	//
	// 组合爆炸 = 上面的冲击波环 + 三道斜波 + 三个「球形发光层」（底光 haze、火球 fireball、云壳 shell）。纯画面，没有伤害判定。
	//
	// 球形层有两种画法，用的是同一个 BlastSphereParams：
	//   1. 走 spawnShockBlast 时：用 3D 云纹理画（Moci_ShockVolumeMaterial + Moci_shockvolume.frag），
	//      这时只有下面标了【组合爆炸会用】的字段有效，其余字段（rimGain、textureAmount、edgeNoise、各种纹理缩放…）改了也没反应。
	//   2. 单独调 spawnBlastSphere，或者显卡不支持 3D 纹理时：用简单的平面贴图假装成球，所有字段都有效。
	//
	// 用法：
	//   Moci_Util.spawnShockBlast(point, params); // params 每次都要新建一份，参考 Moci_TorpedoOnHitEffect.createBlastParams
	//   Moci_Util.spawnBlastSphere(new Moci_Util.BlastSphereParams()...);        // 单独画一层

	/**
	 * 球形发光层的参数。半径都是世界单位。
	 * 特效生成后改这里的字段仍然会生效（每帧都会重新读），只有 layer 是创建时读一次。
	 *
	 * 【大小随时间】半径 = startRadius + (endRadius - startRadius) × (1 - (1 - 进度)^expansionPower)，进度 = 已播秒数 / duration
	 * 【亮度随时间】亮度 = 淡入 × 淡出
	 *     淡入：前 fadeIn 秒从 0 涨到 1
	 *     淡出：进度到 fadeStart 之前保持 1；之后 = (1 - 剩余比例)^fadePower，到 duration 时归 0
	 *     换算成秒：满亮停留 = duration × fadeStart - fadeIn；淡出用时 = duration × (1 - fadeStart)
	 */
	public static class BlastSphereParams {
		/** 组合爆炸内部用：0 = 普通球形层；1/2/3 = 用 3D 云纹理画底光/火球/云壳，由 spawnShockBlast 填写 */
		private int volumePart;
		/** 组合爆炸内部用：云纹理的随机相位，由 spawnShockBlast 填写 */
		private float volumeSeed;
		/** 【组合爆炸会用】中心（固定位置），spawnShockBlast 会自动填 */
		public final Vector2f center = new Vector2f();
		/** 设置后中心每帧跟着这个实体走；它没了就停在最后位置把剩下的播完 */
		public CombatEntityAPI anchor = null;
		/** 【组合爆炸会用】一共存在多少秒，到点直接消失 */
		public float duration = 1.5f;
		/**
		 * 【组合爆炸会用】半径从 startRadius 长到 endRadius。这是球的外框，
		 * 组合爆炸里肉眼看到的亮边大约在 0.9 倍处；底光(haze)是往外渐隐的雾，外面一半基本看不见
		 */
		public float startRadius = 10f;
		public float endRadius = 200f;
		/** 【组合爆炸会用】扩张的「先快后慢」程度：1 = 匀速；越大越是一开始就几乎涨到位、后面慢慢挪 */
		public float expansionPower = 2f;
		/** 【组合爆炸会用】淡入秒数：从全黑亮到最亮用几秒 */
		public float fadeIn = 0.05f;
		/** 【组合爆炸会用】开始变暗的时间点，是「占 duration 的比例」不是秒（0.2 = 播到 20% 开始变暗，0 = 一出现就开始变暗） */
		public float fadeStart = 0.2f;
		/** 【组合爆炸会用】变暗曲线：1 = 匀速变暗；大于 1 = 先暗得快、后面拖长尾巴；小于 1 = 一直挺亮、最后猛地消失 */
		public float fadePower = 1.5f;

		/** 【组合爆炸会用】整体亮度倍率 */
		public float intensity = 1.5f;
		/**
		 * 【组合爆炸会用 rimColor】暗部颜色。组合爆炸里是云团较暗的部分；单独画时是球的边缘光。
		 * 颜色的 alpha 也参与计算，相当于亮度倍率。底光(haze)不用这个颜色。
		 * rimGain / rimPower 只在单独画时有效：边缘光的强度、以及边缘光有多「贴边」（越大越只亮在最外圈）
		 */
		public Color rimColor = new Color(125, 200, 255);
		public float rimGain = 1f;
		public float rimPower = 2.5f;
		/**
		 * 【组合爆炸会用 coreColor】亮部颜色。组合爆炸里是云团亮的部分 + 中心辉光，底光(haze)只用它。
		 * coreGain / corePower 只在单独画时有效：中心光的强度、以及中心光有多集中（越小中心越「满」，越大越只亮在正中）
		 */
		public Color coreColor = new Color(140, 205, 255);
		public float coreGain = 0.3f;
		public float corePower = 1f;
		/** 以下全部只在单独画时有效。贴图参与程度 0..1（0 = 纯色光球）、贴图模糊程度 */
		public float textureAmount = 0.6f;
		public float textureBlur = 1f;
		/** 边缘羽化宽度（占半径的比例）、轮廓扰动（0 = 正圆，0.3~0.5 = 翻滚的云团边缘） */
		public float edgeSoftness = 0.07f;
		public float edgeNoise = 0f;
		/** 两张球面贴图的重复次数与每秒滚动 */
		public float fineScaleU = 1.6f, fineScaleV = 1.6f, fineScrollU = 0.05f, fineScrollV = 0.09f;
		public float bandScaleU = 1.2f, bandScaleV = 1.2f, bandScrollU = -0.06f, bandScrollV = 0.04f;

		public CombatEngineLayers layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER;

		public BlastSphereParams setCenter(Vector2f loc) {
			if (loc != null) center.set(loc);
			return this;
		}

		public BlastSphereParams setAnchor(CombatEntityAPI anchor) {
			this.anchor = anchor;
			if (anchor != null && anchor.getLocation() != null) center.set(anchor.getLocation());
			return this;
		}

		public BlastSphereParams setRadius(float startRadius, float endRadius) {
			this.startRadius = startRadius;
			this.endRadius = endRadius;
			return this;
		}

		public BlastSphereParams setDuration(float duration) {
			this.duration = duration;
			return this;
		}

		public BlastSphereParams setExpansionPower(float expansionPower) {
			this.expansionPower = expansionPower;
			return this;
		}

		public BlastSphereParams setFade(float fadeIn, float fadeStart, float fadePower) {
			this.fadeIn = fadeIn;
			this.fadeStart = fadeStart;
			this.fadePower = fadePower;
			return this;
		}

		public BlastSphereParams setColors(Color rimColor, Color coreColor, float intensity) {
			if (rimColor != null) this.rimColor = rimColor;
			if (coreColor != null) this.coreColor = coreColor;
			this.intensity = intensity;
			return this;
		}

		public BlastSphereParams setRim(float gain, float power) {
			rimGain = gain;
			rimPower = power;
			return this;
		}

		public BlastSphereParams setCore(float gain, float power) {
			coreGain = gain;
			corePower = power;
			return this;
		}

		public BlastSphereParams setTexture(float amount, float blur) {
			textureAmount = amount;
			textureBlur = blur;
			return this;
		}

		public BlastSphereParams setEdge(float softness, float noise) {
			edgeSoftness = softness;
			edgeNoise = noise;
			return this;
		}

		public BlastSphereParams setFineTexture(float scaleU, float scaleV, float scrollU, float scrollV) {
			fineScaleU = scaleU;
			fineScaleV = scaleV;
			fineScrollU = scrollU;
			fineScrollV = scrollV;
			return this;
		}

		public BlastSphereParams setBandTexture(float scaleU, float scaleV, float scrollU, float scrollV) {
			bandScaleU = scaleU;
			bandScaleV = scaleV;
			bandScrollU = scrollU;
			bandScrollV = scrollV;
			return this;
		}

		public BlastSphereParams setLayer(CombatEngineLayers layer) {
			if (layer != null) this.layer = layer;
			return this;
		}

		public Vector2f getCenterLoc() {
			if (anchor != null && anchor.getLocation() != null) return anchor.getLocation();
			return center;
		}
	}

	/** 球形发光层的战斗渲染插件，由 spawnBlastSphere 创建 */
	public static class BlastSpherePlugin extends BaseCombatLayeredRenderingPlugin {
		private final BlastSphereParams params;
		private final EnumSet<CombatEngineLayers> activeLayers;
		private final float seedU = (float) Math.random();
		private final float seedV = (float) Math.random();
		private float elapsed = 0f;
		private boolean expired = false;

		public BlastSpherePlugin(BlastSphereParams params) {
			super(params != null && params.layer != null ? params.layer : CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);
			this.params = params != null ? params : new BlastSphereParams();
			this.activeLayers = EnumSet.of(layer);
		}

		@Override
		public void init(CombatEntityAPI entity) {
			super.init(entity);
			syncHostLocation();
		}

		@Override
		public void advance(float amount) {
			if (expired) return;
			CombatEngineAPI engine = Global.getCombatEngine();
			if (engine == null || engine.isPaused() || !(amount > 0f) || !Float.isFinite(amount)) return;
			elapsed += amount;
			if (params.anchor != null && (params.anchor.isExpired() || !engine.isEntityInPlay(params.anchor))) {
				params.center.set(params.anchor.getLocation());
				params.anchor = null;
			}
			if (elapsed >= Math.max(0.05f, params.duration)) expired = true;
			syncHostLocation();
		}

		@Override
		public void render(CombatEngineLayers currLayer, ViewportAPI viewport) {
			if (expired) return;
			float level = getBrightness() * (viewport != null ? viewport.getAlphaMult() : 1f);
			if (!(level > 0.001f)) return;
			BlastWaveGL.renderSphere(this, level);
		}

		@Override
		public float getRenderRadius() {
			return Math.max(params.startRadius, params.endRadius) * (1f + Math.max(0f, params.edgeNoise)) + 50f;
		}

		@Override
		public EnumSet<CombatEngineLayers> getActiveLayers() {
			return activeLayers;
		}

		@Override
		public boolean isExpired() {
			return expired;
		}

		public void expireNow() {
			expired = true;
		}

		public BlastSphereParams getParams() {
			return params;
		}

		public float getProgress() {
			return Math.max(0f, Math.min(1f, elapsed / Math.max(0.05f, params.duration)));
		}

		public float getRadius() {
			float eased = 1f - (float) Math.pow(1f - getProgress(), Math.max(0.05f, params.expansionPower));
			return Math.max(0f, params.startRadius + (params.endRadius - params.startRadius) * eased);
		}

		public float getBrightness() {
			float attack = params.fadeIn > 0f ? Math.min(1f, elapsed / params.fadeIn) : 1f;
			float p = getProgress();
			float start = Math.max(0f, Math.min(0.999f, params.fadeStart));
			float decay = p <= start ? 1f
					: (float) Math.pow(Math.max(0f, 1f - (p - start) / (1f - start)), Math.max(0.05f, params.fadePower));
			return attack * decay;
		}

		private void syncHostLocation() {
			if (entity != null && entity.getLocation() != null) entity.getLocation().set(params.getCenterLoc());
		}
	}

	/** 在战斗中生成一层球形发光；无战斗引擎时返回 null */
	public static BlastSpherePlugin spawnBlastSphere(BlastSphereParams params) {
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return null;
		BlastSpherePlugin plugin = new BlastSpherePlugin(params);
		CombatEntityAPI host = engine.addLayeredRenderingPlugin(plugin);
		if (host != null && host.getLocation() != null) host.getLocation().set(plugin.getParams().getCenterLoc());
		return plugin;
	}

	/**
	 * 一道斜波：一个倾斜的椭圆波纹，假装是 3D 冲击波波面从斜角度看过去的样子。
	 * 参数依次是：
	 *   tint    颜色。alpha 越低越淡、也越透明
	 *   angle   朝向：椭圆长轴在屏幕上的方向，单位弧度（0 = 水平，1.57 ≈ 竖直）。每次爆炸还会随机再偏 ±0.18 左右
	 *   opening 开口：0～1，椭圆有多「圆」。1 = 正圆；越小越扁，越像侧着看到的一道弧
	 *   scale   尺寸：相对组半径的大小。亮边大约在「组半径 × scale × 0.89」处；刚出现时只有七成大，约 1 秒内长满
	 *   start / end      出现 / 结束时间，曲线秒（跟着所属冲击波环的时间走，见 BlastWaveParams 类注释）
	 *   fadeIn / fadeOut 淡入 / 淡出用几秒。满亮停留 = end - start - fadeIn - fadeOut
	 */
	public static final class TiltedFront {
		public Color tint;
		public final float angle, opening, scale, start, end, fadeIn, fadeOut;
		public TiltedFront(Color tint, float angle, float opening, float scale,
		                  float start, float end, float fadeIn, float fadeOut) {
			this.tint=tint; this.angle=angle; this.opening=opening; this.scale=scale;
			this.start=start; this.end=end; this.fadeIn=fadeIn; this.fadeOut=fadeOut;
		}
	}

	/**
	 * 三道斜波的组合，挂在 BlastWaveParams.fronts 上。
	 *   curve  三道共用的「组半径」曲线，每行 {曲线秒, 值, 入切线, 出切线}。
	 *          值是冲击波环 endRadius 的倍数（2.0 = 两倍 endRadius），这里不做归一化，写多少就是多少倍
	 *   crest  三道波亮边的高光色，alpha 越大亮边越偏向这个颜色
	 *   low / steep / cross  三道斜波（名字只是习惯叫法，具体形状全看各自参数）
	 * 全部斜波结束的时间 = 三道里最晚的 end。
	 */
	public static final class TiltedFronts {
		public final TiltedFront low, steep, cross;
		public Color crest;
		private final float[][] curve;
		private final float endTime, maxScale;
		public TiltedFronts(float[][] curve, Color crest, TiltedFront low, TiltedFront steep, TiltedFront cross) {
			if (curve == null || curve.length == 0 || low == null || steep == null || cross == null)
				throw new IllegalArgumentException("A tilted group requires a radius curve and three fronts");
			this.low=low; this.steep=steep; this.cross=cross; this.crest=crest;
			this.endTime=Math.max(low.end,Math.max(steep.end,cross.end));
			this.curve=new float[curve.length][];
			float bound=0f;
			for (int i=0; i<curve.length; i++) {
				float[] k=curve[i];
				if (k == null || k.length != 4 || (i>0 && !(k[0]>curve[i-1][0])))
					throw new IllegalArgumentException("Expected ordered {time,value,in,out} keys");
				this.curve[i]=k.clone();
				bound=Math.max(bound,k[1]);
				if (i==0) continue;
				// 用三次贝塞尔控制点包住 Hermite 段，避免非单调曲线在屏幕边缘被提前裁掉。
				float[] prev=curve[i-1]; float dt=(k[0]-prev[0])/3f;
				bound=Math.max(bound,Math.max(prev[1]+prev[3]*dt,k[1]-k[2]*dt));
			}
			this.maxScale=bound;
		}
	}

	/**
	 * 组合爆炸的全部参数。每次爆炸都要 new 一份新的（特效会一直拿着它，共用会互相干扰）。
	 * 哪层不想要就留 null，不画。
	 */
	public static final class ShockBlastParams {
		/** 底光、火球、云壳 */
		public BlastSphereParams haze, fireball, shell;
		/** 正面冲击波环；斜波挂在 ring.fronts 上，ring 为 null 时斜波也不画 */
		public BlastWaveParams ring;
		/** 云纹理的花纹相位 0~1。NaN = 每次随机（每次爆炸长得不一样）；填固定值 = 每次一模一样 */
		public float cloudPhase = Float.NaN;
	}

	/** spawnShockBlast 的返回值：四层各自的插件，可分别调参或提前结束；无战斗引擎时各项为 null */
	public static class ShockBlast {
		public BlastSpherePlugin haze;
		public BlastSpherePlugin fireball;
		public BlastSpherePlugin shell;
		public BlastWavePlugin ring;
	}

	/** 在 center 生成组合爆炸：把 p 里不为 null 的层各自生成出来，中心统一设成 center，几层共用同一个随机花纹相位。 */
	public static ShockBlast spawnShockBlast(Vector2f center, ShockBlastParams p) {
		if (p == null) throw new IllegalArgumentException("Missing shock blast parameters");
		ShockBlast out = new ShockBlast();
		float seed = Float.isFinite(p.cloudPhase) ? p.cloudPhase % 1f : (float) Math.random();
		if (p.haze != null) out.haze=volumeLayer(1,seed,p.haze.setCenter(center));
		if (p.fireball != null) out.fireball=volumeLayer(2,seed,p.fireball.setCenter(center));
		if (p.shell != null) out.shell=volumeLayer(3,seed,p.shell.setCenter(center));
		if (p.ring != null) {
			p.ring.frontPhase=seed;
			out.ring=spawnBlastWave(p.ring.setCenter(center));
		}
		return out;
	}

	private static BlastSpherePlugin volumeLayer(int part, float seed, BlastSphereParams p) {
		p.volumePart = part;
		p.volumeSeed = seed;
		return spawnBlastSphere(p);
	}

	/** 冲击波环的 GL 资源（着色器、贴图、环网格、屏幕拷贝）。全部懒加载，只在渲染线程访问，整个游戏进程共用一份 */
	private static final class BlastWaveGL {
		private static final int SEGMENTS = 160;
		private static boolean initTried = false;
		private static boolean failed = false;
		private static boolean mipmapped = false;
		private static boolean refractFailed = false;
		private static boolean refractChecked = false;
		private static int glowProgram = 0;
		private static int refractProgram = 0;
		private static int sphereProgram = 0;
		private static int[] glowLoc;
		private static int[] refractLoc;
		private static int[] sphereLoc;
		private static int fineTex = 0;
		private static int bandTex = 0;
		private static int maskTex = 0;
		private static int normalTex = 0;
		private static int ampTex = 0;
		private static int ringVbo = 0;
		private static int ringVertexCount = 0;
		private static int sceneTex = 0;
		private static int sceneW = 0;
		private static int sceneH = 0;
		private static final int SAVED_UNITS = 5;
		private static final FloatBuffer MODELVIEW = BufferUtils.createFloatBuffer(16);
		private static final FloatBuffer PROJECTION = BufferUtils.createFloatBuffer(16);
		private static final IntBuffer VIEWPORT = BufferUtils.createIntBuffer(16);
		private static final FloatBuffer WINDOW = BufferUtils.createFloatBuffer(3);
		private static final IntBuffer SAVED_TEXTURES = BufferUtils.createIntBuffer(16);

		private static void render(BlastWavePlugin plugin, float level, float viewportAlpha) {
			if (!ensureInit()) return;
			BlastWaveParams p = plugin.getParams();
			float outer = plugin.getOuterRadius();
			if (!(outer > 0.5f)) return;
			float thickness = Math.max(0.02f, Math.min(0.98f, p.thickness));
			float inner = outer * (1f - thickness);
			Vector2f c = p.getCenterLoc();
			float time = plugin.elapsed;

			beginDraw();
			try {
				float age = plugin.getCurveTime();
				if (p.glow && p.fronts != null && p.tiltedFrontStrength > 0f && (p.tiltedFrontMask & 7) != 0 && age < p.fronts.endTime) {
					float reach = p.endRadius * evalBlastWaveCurve(p.fronts.curve, age);
					if (reach > 0f) Moci_ShockVolumeMaterial.draw(4,p.frontPhase,age,c,reach,p.color,Color.WHITE,
							p.tiltedFrontStrength*viewportAlpha,ringVertexCount,p.tiltedFrontMask,p.fronts);
				}
				// 先画发光（泛光在下、清晰层在上），再做折射：发光会跟着折射场流动，与原版"折射自己的发光缓冲"一致
				if (p.glow && p.intensity > 0f && p.color != null) {
					drawGlow(p, plugin, c, inner, outer, time, level);
				}
				if (p.refractStrength != 0f && !refractFailed) {
					float strength = p.refractStrength * (p.endRadius > 0f ? outer / p.endRadius : 1f) * level;
					drawRefraction(p, plugin, c, inner, outer, time, strength);
				}
			} catch (RuntimeException e) {
				failed = true;
				Global.getLogger(Moci_Util.class).error("[Moci_BlastWave] 渲染异常，冲击波环已停用", e);
			} finally {
				endDraw();
			}
		}

		private static void renderSphere(BlastSpherePlugin plugin, float level) {
			if (!ensureInit()) return;
			BlastSphereParams p = plugin.getParams();
			float radius = plugin.getRadius();
			if (!(radius > 0.5f) || !(p.intensity > 0f)) return;
			Vector2f c = p.getCenterLoc();

			beginDraw();
			try {
				if (p.volumePart != 0 && Moci_ShockVolumeMaterial.draw(p.volumePart, p.volumeSeed, plugin.elapsed,
						c, radius, p.rimColor, p.coreColor, p.intensity * level, ringVertexCount, 0, null)) return;
				int[] l = sphereLoc;
				GL20.glUseProgram(sphereProgram);
				GL20.glUniform2f(l[0], c.x, c.y);
				GL20.glUniform2f(l[1], 0f, radius);
				GL20.glUniform4f(l[2], p.fineScaleU, p.fineScaleV, p.fineScrollU, p.fineScrollV);
				GL20.glUniform4f(l[3], p.bandScaleU, p.bandScaleV, p.bandScrollU, p.bandScrollV);
				GL20.glUniform2f(l[4], plugin.seedU, plugin.seedV);
				GL20.glUniform1f(l[5], plugin.elapsed);
				float k = p.intensity * level;
				setColor(l[6], p.rimColor, k * Math.max(0f, p.rimGain));
				setColor(l[7], p.coreColor, k * Math.max(0f, p.coreGain));
				GL20.glUniform4f(l[8], p.rimPower, p.corePower, p.textureAmount, Math.max(0.001f, p.edgeSoftness));
				GL20.glUniform1f(l[9], mipmapped ? Math.max(0f, p.textureBlur) : 0f);
				GL20.glUniform1f(l[10], Math.max(0f, p.edgeNoise));
				GL20.glUniform1i(l[11], 0);
				GL20.glUniform1i(l[12], 1);
				GL13.glActiveTexture(GL13.GL_TEXTURE0);
				GL11.glBindTexture(GL11.GL_TEXTURE_2D, fineTex);
				GL13.glActiveTexture(GL13.GL_TEXTURE1);
				GL11.glBindTexture(GL11.GL_TEXTURE_2D, bandTex);
				GL11.glEnable(GL11.GL_BLEND);
				GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
				GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, ringVertexCount);
			} catch (RuntimeException e) {
				failed = true;
				Global.getLogger(Moci_Util.class).error("[Moci_BlastWave] 球形发光层渲染异常，冲击波特效已停用", e);
			} finally {
				endDraw();
			}
		}

		private static void setColor(int location, Color color, float k) {
			if (color == null) {
				GL20.glUniform3f(location, 0f, 0f, 0f);
				return;
			}
			float a = k * color.getAlpha() / 255f;
			GL20.glUniform3f(location, color.getRed() / 255f * a, color.getGreen() / 255f * a, color.getBlue() / 255f * a);
		}

		private static int savedProgram, savedArrayBuffer, savedActive;

		/** 保存会被改动的 GL 状态，绑定环面网格。必须与 endDraw 成对调用（单线程，不可嵌套） */
		private static void beginDraw() {
			savedProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
			savedArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
			savedActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
			SAVED_TEXTURES.clear();
			for (int i = 0; i < SAVED_UNITS; i++) {
				GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
				SAVED_TEXTURES.put(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));
			}
			GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
			GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
			GL11.glDisable(GL11.GL_DEPTH_TEST);
			GL11.glDisable(GL11.GL_CULL_FACE);
			GL11.glDisable(GL11.GL_ALPHA_TEST);
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, ringVbo);
			GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
			GL11.glVertexPointer(2, GL11.GL_FLOAT, 0, 0L);
		}

		private static void endDraw() {
			GL11.glPopClientAttrib();
			GL11.glPopAttrib();
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, savedArrayBuffer);
			GL20.glUseProgram(savedProgram);
			for (int i = 0; i < SAVED_UNITS; i++) {
				GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
				GL11.glBindTexture(GL11.GL_TEXTURE_2D, SAVED_TEXTURES.get(i));
			}
			GL13.glActiveTexture(savedActive);
		}

		private static void drawGlow(BlastWaveParams p, BlastWavePlugin plugin, Vector2f c, float inner, float outer,
				float time, float level) {
			int[] l = glowLoc;
			GL20.glUseProgram(glowProgram);
			GL20.glUniform4f(l[2], wrapScale(p.fineScaleU), p.fineScaleV, p.fineScrollU, p.fineScrollV);
			GL20.glUniform4f(l[3], wrapScale(p.bandScaleU), p.bandScaleV, p.bandScrollU, p.bandScrollV);
			GL20.glUniform2f(l[4], p.distortOwnLight ? 0f : plugin.seedU, p.distortOwnLight ? 0f : plugin.seedV);
			GL20.glUniform1f(l[5], time);
			GL20.glUniform1i(l[8], 0);
			GL20.glUniform1i(l[9], 1);
			GL20.glUniform1i(l[10], 2);
			GL20.glUniform1i(l[11], 3);
			GL20.glUniform1i(l[12], 4);
			GL20.glUniform1f(l[13], p.distortOwnLight ? level : 0f);
			if (p.distortOwnLight) {
				GL13.glActiveTexture(GL13.GL_TEXTURE3);
				GL11.glBindTexture(GL11.GL_TEXTURE_2D, normalTex);
				GL13.glActiveTexture(GL13.GL_TEXTURE4);
				GL11.glBindTexture(GL11.GL_TEXTURE_2D, ampTex);
			}
			GL20.glUniform2f(l[0], c.x, c.y);
			GL13.glActiveTexture(GL13.GL_TEXTURE0);
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, fineTex);
			GL13.glActiveTexture(GL13.GL_TEXTURE1);
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, bandTex);
			GL13.glActiveTexture(GL13.GL_TEXTURE2);
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, maskTex);
			GL11.glEnable(GL11.GL_BLEND);
			GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
			float k = p.intensity * level * (p.color.getAlpha() / 255f);
			float r = p.color.getRed() / 255f * k, g = p.color.getGreen() / 255f * k, b = p.color.getBlue() / 255f * k;

			// 泛光：环向内外加宽，ringUV.y 映射回原环的 V，所有贴图取模糊的 mip 层级。没有 mipmap 时模糊不了，直接跳过
			if (mipmapped && p.bloomStrength > 0f) {
				float width = outer - inner;
				float spread = Math.max(0f, p.bloomSpread);
				float innerB = Math.max(0f, inner - width * 0.3f);
				float outerB = outer + width * spread;
				float s = Math.max(0f, p.bloomStrength);
				GL20.glUniform2f(l[1], innerB, outerB);
				GL20.glUniform3f(l[6], r * s, g * s, b * s);
				GL20.glUniform4f(l[7], Math.max(0f, p.bloomBlur), (outerB - innerB) / width, (innerB - inner) / width,
						Math.max(0.0001f, spread));
				GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, ringVertexCount);
			}

			GL20.glUniform2f(l[1], inner, outer);
			GL20.glUniform3f(l[6], r, g, b);
			GL20.glUniform4f(l[7], mipmapped ? Math.max(0f, p.sharpBlur) : 0f, 1f, 0f, 0.0001f);
			GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, ringVertexCount);
		}

		private static void drawRefraction(BlastWaveParams p, BlastWavePlugin plugin, Vector2f c, float inner, float outer,
				float time, float strengthWorld) {
			// 世界 -> 窗口像素：按当前变换投影中心和一个单位向量（战斗视角不旋转）
			MODELVIEW.clear();
			PROJECTION.clear();
			VIEWPORT.clear();
			GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MODELVIEW);
			GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, PROJECTION);
			GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT);
			float[] origin = project(c.x, c.y);
			float[] alongX = project(c.x + 1f, c.y);
			if (origin == null || alongX == null) return;
			float pxPerUnit = Math.abs(alongX[0] - origin[0]);
			float offsetPx = Math.abs(strengthWorld) * pxPerUnit;
			if (!(offsetPx > 0.05f)) return;
			float chroma = Math.max(-1f, Math.min(1f, p.refractChroma));

			int vpX = VIEWPORT.get(0), vpY = VIEWPORT.get(1), vpW = VIEWPORT.get(2), vpH = VIEWPORT.get(3);
			int texW = vpX + vpW, texH = vpY + vpH;
			if (vpW <= 0 || vpH <= 0) return;
			// 只拷贝环的外接矩形，外扩最大偏移，保证采样点都落在本帧拷贝里
			float extent = outer * pxPerUnit + offsetPx * (1f + Math.abs(chroma)) + 2f;
			int left = Math.max(vpX, (int) Math.floor(origin[0] - extent));
			int bottom = Math.max(vpY, (int) Math.floor(origin[1] - extent));
			int right = Math.min(texW, (int) Math.ceil(origin[0] + extent));
			int top = Math.min(texH, (int) Math.ceil(origin[1] + extent));
			if (right <= left || top <= bottom) return;

			boolean firstUse = !refractChecked;
			if (firstUse) {
				for (int i = 0; i < 16 && GL11.glGetError() != GL11.GL_NO_ERROR; i++) {
				}
			}

			GL13.glActiveTexture(GL13.GL_TEXTURE0);
			ensureSceneTexture(texW, texH);
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneTex);
			GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, left, bottom, left, bottom, right - left, top - bottom);
			GL13.glActiveTexture(GL13.GL_TEXTURE1);
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, normalTex);
			GL13.glActiveTexture(GL13.GL_TEXTURE2);
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, ampTex);

			int[] l = refractLoc;
			GL20.glUseProgram(refractProgram);
			GL20.glUniform2f(l[0], c.x, c.y);
			GL20.glUniform2f(l[1], inner, outer);
			GL20.glUniform4f(l[2], wrapScale(p.normalScaleU), p.normalScaleV, p.normalScrollU, p.normalScrollV);
			// 原版振幅贴图：原始 UV + 静态相位 (0, 1)，Repeat 下等于原 UV
			GL20.glUniform4f(l[3], 1f, 1f, 0f, 1f);
			GL20.glUniform2f(l[4], plugin.seedV, plugin.seedU);
			GL20.glUniform1f(l[5], time);
			GL20.glUniform2f(l[6], 1f / sceneW, 1f / sceneH);
			GL20.glUniform1f(l[7], strengthWorld < 0f ? -offsetPx : offsetPx);
			GL20.glUniform1f(l[8], Math.max(0.01f, p.refractFlatten));
			GL20.glUniform1f(l[9], Math.max(0f, Math.min(1f, p.refractRadialPush)));
			GL20.glUniform1f(l[10], chroma);
			GL20.glUniform1i(l[11], 0);
			GL20.glUniform1i(l[12], 1);
			GL20.glUniform1i(l[13], 2);
			// 直接覆盖：振幅在环的内外边缘为 0，此处取到的就是原像素
			GL11.glDisable(GL11.GL_BLEND);
			GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, ringVertexCount);

			if (firstUse) {
				refractChecked = true;
				int error = GL11.glGetError();
				if (error != GL11.GL_NO_ERROR) {
					refractFailed = true;
					Global.getLogger(Moci_Util.class).error("[Moci_BlastWave] 屏幕拷贝折射报 OpenGL 错误 0x"
							+ Integer.toHexString(error) + "，已停用折射，只保留发光");
				}
			}
		}

		/** U 方向缩放取整（至少 1），保证 u 从 1 回到 0 时纹理坐标正好差整数，绕圈无缝 */
		private static float wrapScale(float scaleU) {
			return Math.max(1f, Math.round(Math.abs(scaleU))) * Math.signum(scaleU == 0f ? 1f : scaleU);
		}

		private static float[] project(float x, float y) {
			MODELVIEW.rewind();
			PROJECTION.rewind();
			VIEWPORT.rewind();
			WINDOW.clear();
			if (!GLU.gluProject(x, y, 0f, MODELVIEW, PROJECTION, VIEWPORT, WINDOW)) return null;
			return new float[] { WINDOW.get(0), WINDOW.get(1) };
		}

		private static boolean ensureInit() {
			if (failed) return false;
			if (initTried) return true;
			initTried = true;
			try {
				Moci_ShaderManager shaders = Moci_ShaderManager.getInstance();
				if (!shaders.isOpenGL20Supported()) throw new IllegalStateException("OpenGL 2.0 不可用");
				glowProgram = shaders.createShader(BLASTWAVE_VERT, BLASTWAVE_GLOW_FRAG, "Moci_blastwave_glow");
				refractProgram = shaders.createShader(BLASTWAVE_VERT, BLASTWAVE_REFRACT_FRAG, "Moci_blastwave_refract");
				sphereProgram = shaders.createShader(BLASTWAVE_VERT, BLASTSPHERE_FRAG, "Moci_blastsphere");
				if (glowProgram == 0 || refractProgram == 0 || sphereProgram == 0) {
					throw new IllegalStateException("着色器编译或链接失败，详见 [MOCI_SHADER] 日志");
				}
				sphereLoc = locations(sphereProgram, "center", "radii", "fineXform", "bandXform", "seed", "time",
						"rimColor", "coreColor", "shape", "texBias", "edgeNoise", "fineTex", "bandTex");
				glowLoc = locations(glowProgram, "center", "radii", "fineXform", "bandXform", "seed", "time", "color",
						"bloom", "fineTex", "bandTex", "maskTex", "warpTex", "holeTex", "selfWarp");
				refractLoc = locations(refractProgram, "center", "radii", "normalXform", "ampXform", "seed", "time",
						"invSceneSize", "offsetPx", "flatten", "radialPush", "chroma", "sceneTex", "normalTex", "ampTex");

				mipmapped = GLContext.getCapabilities().OpenGL30;
				int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
				try {
					fineTex = loadRepeatTexture(BLASTWAVE_TEX_FINE, false);
					bandTex = loadRepeatTexture(BLASTWAVE_TEX_BAND, false);
					// 截面渐变 V 方向不能回绕：否则模糊层级会把外缘最亮的一行渗到内缘，形成一圈亮线
					maskTex = loadRepeatTexture(BLASTWAVE_TEX_MASK, true);
					normalTex = loadRepeatTexture(BLASTWAVE_TEX_NORMAL, false);
					ampTex = loadRepeatTexture(BLASTWAVE_TEX_AMPLITUDE, false);
				} finally {
					GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
				}

				// 环面网格：(u, v)，u 绕圈 0..1（首尾各一份顶点），v 内圈 0 / 外圈 1
				ringVertexCount = (SEGMENTS + 1) * 2;
				FloatBuffer ring = BufferUtils.createFloatBuffer(ringVertexCount * 2);
				for (int i = 0; i <= SEGMENTS; i++) {
					float u = i / (float) SEGMENTS;
					ring.put(u).put(0f).put(u).put(1f);
				}
				ring.flip();
				int previousBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
				ringVbo = GL15.glGenBuffers();
				GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, ringVbo);
				GL15.glBufferData(GL15.GL_ARRAY_BUFFER, ring, GL15.GL_STATIC_DRAW);
				GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBuffer);
				return true;
			} catch (Throwable e) {
				failed = true;
				Global.getLogger(Moci_Util.class).error("[Moci_BlastWave] 初始化失败，冲击波环已停用", e);
				return false;
			}
		}

		private static int[] locations(int program, String... names) {
			int[] out = new int[names.length];
			for (int i = 0; i < names.length; i++) out[i] = GL20.glGetUniformLocation(program, names[i]);
			return out;
		}

		/** 自己加载贴图：线性 RGBA8（不做 sRGB 解码）、U Repeat、V Repeat 或钳制、不翻 Y，有 OpenGL 3.0 时生成 mipmap */
		private static int loadRepeatTexture(String path, boolean clampV) throws IOException {
			BufferedImage image;
			InputStream in = Global.getSettings().openStream(path);
			try {
				image = ImageIO.read(in);
			} finally {
				in.close();
			}
			if (image == null) throw new IOException("无法解码贴图 " + path);
			int w = image.getWidth(), h = image.getHeight();
			int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
			ByteBuffer pixels = BufferUtils.createByteBuffer(w * h * 4);
			for (int value : argb) {
				pixels.put((byte) (value >> 16)).put((byte) (value >> 8)).put((byte) value).put((byte) (value >>> 24));
			}
			pixels.flip();
			int id = GL11.glGenTextures();
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, clampV ? GL12.GL_CLAMP_TO_EDGE : GL11.GL_REPEAT);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
					mipmapped ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_LINEAR);
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
			if (mipmapped) GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
			return id;
		}

		/** 屏幕拷贝纹理与窗口像素一一对应（含视口偏移），尺寸变化时重建 */
		private static void ensureSceneTexture(int w, int h) {
			if (sceneTex != 0 && sceneW == w && sceneH == h) return;
			if (sceneTex == 0) sceneTex = GL11.glGenTextures();
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneTex);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
					(ByteBuffer) null);
			sceneW = w;
			sceneH = h;
		}
	}
}
