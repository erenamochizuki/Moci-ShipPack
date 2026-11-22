package data.shipsystems.scripts;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import data.scripts.util.Moci_Util;
import com.fs.starfarer.combat.CombatViewport;
import com.fs.starfarer.combat.entities.Ship;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.util.vector.Vector2f;
import java.awt.Color;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.DoubleBuffer;
import java.util.*;
import static data.shipsystems.scripts.Moci_NebulaGateMotion.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;

/** 星岚门一次入场；旅行驱动提前关闭时仍让舰体完整驶出。 */
final class Moci_NebulaGateTransit extends BaseCombatLayeredRenderingPlugin {
    private static final Color FRINGE = new Color(85,146,255,195), CORE = new Color(216,238,255,240); // 电弧外晕、亮芯
    // 星岚门闪电调参：粗细、传播速度和生成频率互不影响。
    private static final float MIN_GATE_RADIUS = 28f;          // 门半径下限，也是闪电开始加粗的起点（世界单位）
    private static final float ARC_FULL_WIDTH_RADIUS = 110f;   // 达到最粗闪电的门半径；调大可让更大的舰船才用满宽度，应大于 MIN_GATE_RADIUS
    private static final float ARC_WIDTH_MIN = 5f;             // 最小门的最终闪电宽度（世界单位），不再乘倍率
    private static final float ARC_WIDTH_MAX = 25f;            // 大门的最终闪电宽度；更大的门也不会超过此值
    private static final float ARC_SPEED_RADII_PER_SEC = 1.5f; // 每战斗秒走几个门半径；越大越快，与宽度独立
    private static final float ARC_INTERVAL_BEFORE_OPEN = .12f; // 开门前两道闪电的基础间隔（战斗秒）；越小越密
    private static final float ARC_INTERVAL_AFTER_OPEN = .24f;  // 开始开门后的基础间隔（战斗秒）；越小越密
    private static final float ARC_INTERVAL_JITTER = .12f;      // 每次间隔额外随机增加 0 到此值（战斗秒）；设为 0 则固定间隔
    private static final Color SCORCH = new Color(80,109,222), FLASH = new Color(163,228,255), RIM = new Color(145,215,255); // 焦边、横闪、门圈
    // 开场十字光斑：以下只改白蓝闪光，不改变外围焦边、烟雾、门圈或动画时间。
    private static final float FLASH_SIZE_MULT = 1f;         // 两版共用的整体尺寸倍率（以门半径为基准）；1 为原尺寸，1.5 放大 50%，0 隐藏光斑
    private static final float FLASH_CORE_SIZE_MULT = 1f;    // 两版的中心亮芯尺寸倍率（0~4）；2 使亮芯宽、高翻倍，不拉长十字光芒；0 仅留光芒
    private static final float FLASH_BRIGHTNESS_MULT = 1f;   // 两版的光斑亮度倍率；0 熄灭，0.7 减弱，1 为原亮度，大于 1 增亮

    private static float flashSizeMult(boolean ribbon) { return FLASH_SIZE_MULT*(ribbon ? Moci_FluxRibbonDrive.RIBBON_FLASH_SIZE_MULT : 1f); }
    // 焦边调参：只影响横闪周围的烧蚀轮廓，不改变横闪、烟雾或椭圆门沿。
    private static final float SCORCH_STYLE_START = 0.25f;              // 起始风格：0=v3 整块，1=v2 碎齿；0.25 表示在大块上混入四分之一旧轮廓
    private static final float SCORCH_STYLE_END = 0.75f; // 默认跟随起始值；另填 0~1，就会从起始风格平滑过渡到该值
    private static final float SCORCH_BLEND_SECONDS = .35f;          // 风格过渡用时（战斗秒）；越小变化越早，0 表示立即采用结束风格。焦边约 0.60 秒后消失
    private static final float SCORCH_MOTION_AMOUNT = 0.1f;            // 局部起伏幅度：0 关闭；0.10 表示最多偏移当前半径的 10%，上限 0.35
    private static final float SCORCH_MOTION_HZ = 2f;                // 局部起伏频率（周期/战斗秒）；2 表示每 0.5 秒一轮，0 关闭；不影响整体扩张速度
    private static final int SCORCH_BLOCKS = 8;                      // v3 主块数（2~12）；越少，每块的平坦边缘越长
    private static final float SCORCH_DEPTH = .35f;                  // v3 大块凹凸深度（当前半径比例，0~0.70）；越大，块与块之间的谷越深
    private static final float SCORCH_CHIPS = .15f;                 // v3 局部小缺口深度（当前半径比例，0~0.25）；设 0 只留整块
    private static final float SCORCH_V2_ROUGHNESS = .80f;           // v2 锯齿强度（0~1）；0 为圆，0.80 为旧版默认；纯 v3 时无效
    private static final float SCORCH_RADIUS_START = .07f;           // 开始扩张时的半径（门半径倍数）
    private static final float SCORCH_RADIUS_END = .98f;             // 完全展开时的半径（门半径倍数）
    private static final float SCORCH_EXPAND_SECONDS = .59f;        // 整体扩张用时（战斗秒）；越小越快，0 直接使用结束半径；不延长淡出
    private static final float SCORCH_BAND = .18f;                   // 向内的焦带宽度占当前半径的比例（0.01~0.50），不改变轮廓形状
    record SmokeSettings(float minDiameter,float maxDiameter,int perBurst,float chargeInterval,float emergeInterval,float closeInterval,Color... palette) {
        SmokeSettings {
            minDiameter=Math.max(0f,minDiameter); maxDiameter=Math.max(minDiameter,maxDiameter);
            perBurst=Math.max(0,Math.min(16,perBurst));
        }
        float interval(float at) {
            float seconds=at<OPEN ? chargeInterval : at<HANDOFF ? emergeInterval : closeInterval;
            return Float.isFinite(seconds) ? Math.max(.01f,seconds) : .16f;
        }
        Moci_Util.NebulaPuffStyle style(float rate) { return new Moci_Util.NebulaPuffStyle(
            .10f, 1.35f,   // 烟心内、外半径（门半径倍数）；内圈靠近中心，外圈扩大云团范围
            minDiameter, maxDiameter,
            1.75f,         // 消散时的直径倍率
            1.35f/rate, 1.85f/rate,  // 基础寿命换算为战斗秒
            .075f*rate, .060f*rate,  // 径向外漂、切向旋转同步换速，保持漂移距离
            .22f, .20f);   // 淡入、保持亮度各占寿命的比例，剩余时段淡出
        }
        int emitBurst(CombatEngineAPI engine,Vector2f gate,float radius,float seed,int index,float at,Random random,Moci_Util.NebulaPuffStyle puff) {
            if (palette.length==0) return index;
            for (int i=0;i<perBurst;i++) emitSmoke(engine,gate,radius,seed,index++,at,random,puff,palette);
            return index;
        }
    }
    static SmokeSettings smokeSettings(boolean ribbon) { return ribbon ? Moci_FluxRibbonDrive.RIBBON_SMOKE : Moci_NebulaGate.GATE_SMOKE; }
    static Moci_Util.ScorchContour makeScorch(float seed) {
        return Moci_Util.createScorchContour(seed, // 这一次跃迁的固定种子；不会逐帧改变
                SCORCH_BLOCKS,SCORCH_DEPTH,SCORCH_CHIPS, // v3 主块数、大块凹凸、小缺口
                SCORCH_V2_ROUGHNESS);                    // v2 碎齿起伏强度
    }
    private static final ShipCommand[] BLOCK = {ShipCommand.FIRE, ShipCommand.VENT_FLUX, ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK,
            ShipCommand.ACCELERATE, ShipCommand.ACCELERATE_BACKWARDS, ShipCommand.DECELERATE, ShipCommand.STRAFE_LEFT,
            ShipCommand.STRAFE_RIGHT, ShipCommand.TURN_LEFT, ShipCommand.TURN_RIGHT};
    // Native render() writes this cache even for a visual copy. External thrusters read it separately from extraAlpha.
    private static final VarHandle COMBINED = combinedAlpha();
    private static VarHandle combinedAlpha() {
        try { return MethodHandles.privateLookupIn(Ship.class, MethodHandles.lookup()).findVarHandle(Ship.class,"combinedAlphaMult",float.class); }
        catch (ReflectiveOperationException ex) { Global.getLogger(Moci_NebulaGateTransit.class).warn("No native alpha cache; external engine glows may remain visible",ex); return null; }
    }

    private final CombatEngineAPI engine;
    private final String transitKey;
    private final boolean ribbon;
    private final float speed;
    private final SmokeSettings smoke;
    private final Moci_Util.NebulaPuffStyle[] puffs;
    private double ribbonElapsed;
    private final ShipAPI ship;
    private final ArrayList<Part> parts = new ArrayList<>();
    private final Vector2f dest;
    final Vector2f gate;
    private final float axisX, axisY, facing, radius, seed;
    private final Random random = new Random();
    private final Moci_Util.ScorchContour scorch;
    private int smokeIndex;
    private final DoubleBuffer plane = BufferUtils.createDoubleBuffer(4);
    private final Moci_NebulaGateMask mask;
    private final EnumSet<CombatEngineLayers> layers = EnumSet.of(CombatEngineLayers.UNDER_SHIPS_LAYER);
    private float nose, tail, width, age, nextSmoke = SMOKE, nextArc = LIGHTNING;
    private boolean released, expired, systemEnded;

    Moci_NebulaGateTransit(CombatEngineAPI engine, ShipAPI ship, Vector2f to) {
        this(engine,ship,to,Moci_NebulaGate.EVENT,false,Moci_NebulaGate.ANIMATION_SPEED);
    }
    Moci_NebulaGateTransit(CombatEngineAPI engine, ShipAPI ship, Vector2f to,String key,boolean ribbon,float speed) {
        this.transitKey=key; this.ribbon=ribbon; this.speed=speed;
        smoke=smokeSettings(ribbon);
        // 原生粒子的时钟是战斗秒：寿命和漂移速度一起换算，慢放时云团仍能衔接。
        puffs=new Moci_Util.NebulaPuffStyle[ribbon?4:1];
        for (int i=0;i<puffs.length;i++) {
            float rate=ribbon ? Moci_FluxRibbonDrive.TIMING.rate(i) : speed;
            puffs[i]=smoke.style(rate);
        }
        this.engine = engine; this.ship = ship; this.dest = new Vector2f(to);
        facing = ship.getFacing(); double a = Math.toRadians(facing);
        axisX = (float)Math.cos(a); axisY = (float)Math.sin(a);
        parts.add(new Part(ship));
        for (ShipAPI child : ship.getChildModulesCopy()) {
            if (child.getParentStation() == ship && child.isAlive() && engine.isEntityInPlay(child)) parts.add(new Part(child));
        }
        for (Part part : parts) {
            if (part.hidden) continue;
            ShipAPI s = part.s;
            measure(s.getSpriteAPI(), s.getLocation(), s.getFacing());
            for (WeaponAPI w : s.getAllWeapons()) {
                measure(w.getSprite(), w.getLocation(), w.getCurrAngle());
                measure(w.getBarrelSpriteAPI(), w.getLocation(), w.getCurrAngle());
                measure(w.getUnderSpriteAPI(), w.getLocation(), w.getCurrAngle());
                measure(w.getGlowSpriteAPI(), w.getLocation(), w.getCurrAngle());
            }
            layers.add(s.getLayer());
            if (s.getActiveLayers().contains(CombatEngineLayers.STATION_WEAPONS_LAYER)) layers.add(CombatEngineLayers.STATION_WEAPONS_LAYER);
        }
        radius = Math.max(MIN_GATE_RADIUS, width*1.14f+5f);
        gate = new Vector2f(to.x-axisX*(tail+GAP), to.y-axisY*(tail+GAP));
        mask = new Moci_NebulaGateMask(gate,axisX,axisY,radius);
        plane.put(axisX).put(axisY).put(0d).put(-(axisX*(double)gate.x+axisY*(double)gate.y)).flip();
        seed = random.nextFloat()*6.28f;
        scorch = makeScorch(seed);
    }

    // Project the four sprite corners; ship and weapon pivots need not be centred, or aligned with each other.
    private void measure(SpriteAPI sp, Vector2f p, float angle) {
        if (sp == null || sp.getColor().getAlpha() == 0 || sp.getAlphaMult() <= 0f) return;
        float dx = p.x-ship.getLocation().x, dy = p.y-ship.getLocation().y;
        double a = Math.toRadians(angle-facing-90f), c = Math.cos(a), s = Math.sin(a);
        for (int i=0;i<4;i++) {
            float x=((i&1)==0?0:sp.getWidth())-sp.getCenterX(), y=((i&2)==0?0:sp.getHeight())-sp.getCenterY();
            float fore = dx*axisX+dy*axisY+(float)(x*c-y*s), side = dy*axisX-dx*axisY+(float)(x*s+y*c);
            nose=Math.max(nose,fore); tail=Math.max(tail,-fore); width=Math.max(width,Math.abs(side));
        }
    }

    private final class Part {
        final ShipAPI s;
        final Vector2f at;
        final float heading, alpha, combined;
        final boolean hidden;
        final CollisionClass collision;
        final MutableStat[] protection;
        boolean held;
        Part(ShipAPI s) {
            this.s = s; heading=s.getFacing(); hidden=s.isDoNotRender(); alpha=s.getExtraAlphaMult();
            combined=s.getCombinedAlphaMult(); collision=s.getCollisionClass();
            at=new Vector2f(dest.x+(s.getLocation().x-ship.getLocation().x),dest.y+(s.getLocation().y-ship.getLocation().y));
            MutableShipStatsAPI m=s.getMutableStats();
            protection=new MutableStat[]{m.getHullDamageTakenMult(),m.getArmorDamageTakenMult(),m.getEmpDamageTakenMult(),
                    m.getShieldDamageTakenMult(),m.getWeaponDamageTakenMult(),m.getEngineDamageTakenMult()};
        }
        void take() {
            held=true;
            for (MutableStat stat : protection) stat.modifyMult(transitKey,0f);
            pin();
        }
        void pin() {
            s.setDoNotRender(true); s.setExtraAlphaMult(0f); cache(0f);
            s.setCollisionClass(CollisionClass.NONE); s.getLocation().set(at); s.setFacing(heading);
            s.getVelocity().set(0f,0f); s.setAngularVelocity(0f); s.setHoldFireOneFrame(true);
            for (ShipCommand cmd : BLOCK) s.blockCommandForOneFrame(cmd);
            for (WeaponAPI w : s.getAllWeapons()) w.setForceNoFireOneFrame(true);
        }
        void cache(float value) { if (COMBINED != null && s instanceof Ship nativeShip) COMBINED.set(nativeShip,value); }
        void release() {
            if (!held) return;
            held=false; s.setExtraAlphaMult(alpha); cache(combined); s.setDoNotRender(hidden); s.setCollisionClass(collision);
            for (MutableStat stat : protection) stat.unmodify(transitKey);
        }
        void draw(CombatEngineLayers layer, CombatViewport viewport) {
            if (!held || hidden || !(s instanceof Ship nativeShip) || !s.getActiveLayers().contains(layer)
                    || (layer != s.getLayer() && layer != CombatEngineLayers.STATION_WEAPONS_LAYER)) return;
            try {
                s.setDoNotRender(false); s.setExtraAlphaMult(alpha);
                nativeShip.render(layer,viewport,true);
            } finally { s.setDoNotRender(true); s.setExtraAlphaMult(0f); cache(0f); }
        }
    }

    void start() { for (Part p : parts) p.take(); }
    void registered(CombatEntityAPI host) {
        if (host!=null) host.getLocation().set(gate);
        start();
    }
    void stop() { systemEnded=true; if (ship.isHulk()) cleanup(); }
    boolean locked() { return !released; }
    private void release() {
        if (released) return;
        for (Part p : parts) p.release();
        released=true;
    }
    @Override public void cleanup() {
        release(); expired=true;
        // 只有旅行驱动和入场演出均结束，才释放本轮标记。
        if (systemEnded && ship.getCustomData().get(transitKey)==this) ship.removeCustomData(transitKey);
    }
    @Override public boolean isExpired() { return expired; }
    @Override public float getRenderRadius() {
        var p=puffs[0];
        return Math.max(2.6f*flashSizeMult(ribbon),p.outerRadius()*(1f+p.radialDrift()*p.maxLife())+.5f*p.maxDiameter()*p.endSize()+.2f)*radius + nose + tail + 16f;
    }
    @Override public EnumSet<CombatEngineLayers> getActiveLayers() { return layers; }

    private boolean visible() {
        ShipAPI player=engine.getPlayerShip(); int side=player==null || player.getOwner()==100 ? 0 : player.getOwner();
        if (ship.getOwner()==side) return true;
        FogOfWarAPI fog=engine.getFogOfWar(side);
        return fog==null || fog.isVisible(gate);
    }
    @Override public void advance(float amount) {
        if (expired) return;
        if (!ship.isAlive() || !engine.isEntityInPlay(ship)) { cleanup(); return; }
        if (amount<=0f || engine.isPaused()) return;
        if (ship.isRetreating()) { cleanup(); return; } // 入场燃烧可能已经结束，不能等下一次 apply 才解锁
        if (ribbon) {
            ribbonElapsed+=amount;
            age=Moci_FluxRibbonDrive.TIMING.animationAt((float)ribbonElapsed);
        } else age+=amount*speed;
        if (!released) {
            for (Part p : parts) {
                if (p.s!=ship && (!p.s.isAlive() || !engine.isEntityInPlay(p.s) || p.s.getParentStation()!=ship)) p.release();
                if (p.held) p.pin();
            }
            if (age>=HANDOFF) release();
        }
        if (age>=END) { cleanup(); return; }
        boolean emit=visible() && engine.getViewport().isNearViewport(gate,getRenderRadius());
        // Fixed event times, never a burst of catch-up particles after an off-screen wait.
        while (nextSmoke<=age && nextSmoke<CLOSED) {
            if (emit) smokeIndex=smoke.emitBurst(engine,gate,radius,seed,smokeIndex,nextSmoke,random,puffs[ribbon ? Moci_FluxRibbonDrive.TIMING.phaseAt(nextSmoke) : 0]);
            nextSmoke += smoke.interval(nextSmoke);
        }
        while (nextArc<=age && nextArc<HANDOFF+.25f) {
            if (emit) lightning();
            float interval = nextArc < OPEN ? ARC_INTERVAL_BEFORE_OPEN : ARC_INTERVAL_AFTER_OPEN;
            nextArc += Math.max(.02f, interval + random.nextFloat() * ARC_INTERVAL_JITTER); // 最短间隔 0.02 秒，防止误填 0 导致循环不前进
        }
    }

    private Vector2f polar(float r, float a) { return new Vector2f(gate.x+r*(float)Math.cos(a),gate.y+r*(float)Math.sin(a)); }
    private static void emitSmoke(CombatEngineAPI engine,Vector2f gate,float radius,float seed,int index,float at,Random random,Moci_Util.NebulaPuffStyle puff,Color[] palette) {
        // 相邻烟心沿黄金角错开，小幅随机偏移打散队列，避免连续数团都堆在同侧。
        float angle=seed+index*2.399963f+(random.nextFloat()-.5f)*.48f;
        float growth=.32f+.68f*ease((at-SMOKE)/.92f); // 烟心起初铺开到最终范围的 32%，0.92 秒后铺满
        float colorBand=2.5f+1.35f*(float)Math.sin(angle*3f+seed)+random.nextFloat()*.9f;
        int color=(int)(colorBand*.25f*palette.length)%palette.length; // 沿用四色时的冷暖分区，增删颜色后按新色板长度映射
        Moci_Util.spawnNebulaPuff(engine, gate, radius, // 当前战场、云团中心、世界尺寸基准
                growth, angle, random,                 // 当前散布进度、方向（弧度）、本次随机源
                puff, palette[color]);                 // 烟团参数（已换算播放速度）、该片星云的颜色及透明度
    }
    private void lightning() {
        float a=random.nextFloat()*6.283f;
        // 起点距门心 0.48~0.96 倍半径，终点 0.65 倍半径；终点偏转 0.48~1.48 弧度。
        Vector2f from=polar(radius*(.48f+.48f*random.nextFloat()),a), to=polar(radius*.65f,a+.48f+random.nextFloat());
        emitMovingArc(engine,from,to,radius,ribbon ? Moci_FluxRibbonDrive.TIMING.rate(Moci_FluxRibbonDrive.TIMING.phaseAt(nextArc)) : speed);
    }
    static void emitMovingArc(CombatEngineAPI engine,Vector2f from,Vector2f to,float radius) {
        emitMovingArc(engine,from,to,radius,1f);
    }
    private static void emitMovingArc(CombatEngineAPI engine,Vector2f from,Vector2f to,float radius,float speed) {
        float t=Math.max(0f,Math.min(1f,(radius-MIN_GATE_RADIUS)/Math.max(.001f,ARC_FULL_WIDTH_RADIUS-MIN_GATE_RADIUS)));
        Moci_Util.spawnVisualLightning(engine,from,to, // 当前战场、电弧起点和终点（世界坐标）；抵达时不再补烟
                ARC_WIDTH_MIN+(ARC_WIDTH_MAX-ARC_WIDTH_MIN)*t, // 以门半径在两端宽度之间线性过渡
                FRINGE,CORE,                          // 外晕颜色、亮芯颜色
                radius*ARC_SPEED_RADII_PER_SEC*speed); // 门半径/动画秒换算为世界单位/战斗秒
    }

    static void drawAtmosphere(Vector2f gate,float radius,float facing,float age,float seed,float opacity,Moci_Util.ScorchContour scorch) {
        drawAtmosphere(gate,radius,facing,age,seed,opacity,scorch,false);
    }
    static void drawAtmosphere(Vector2f gate,float radius,float facing,float age,float seed,float opacity,Moci_Util.ScorchContour scorch,boolean ribbon) {
            float burst=flash(age)*opacity;
            if (burst>0) {
                float blend=SCORCH_BLEND_SECONDS>0f ? ease(age/SCORCH_BLEND_SECONDS) : 1f;
                float expand=SCORCH_EXPAND_SECONDS>0f ? ease(age/SCORCH_EXPAND_SECONDS) : 1f;
                scorch.setAppearance(SCORCH_STYLE_START+(SCORCH_STYLE_END-SCORCH_STYLE_START)*blend, // 此刻混入 v2 的比例
                        age,SCORCH_MOTION_AMOUNT,SCORCH_MOTION_HZ); // 战斗秒、局部起伏半径比例、每秒周期数
                Moci_Util.drawScorchRim(gate,                     // 焦边中心（世界坐标）
                        radius*(SCORCH_RADIUS_START+(SCORCH_RADIUS_END-SCORCH_RADIUS_START)*expand), // 当前世界半径
                        0f,burst,scorch,                          // 朝向（度）、随横闪淡出的亮度、当前轮廓
                        SCORCH_BAND,SCORCH);                      // 焦带相对宽度、蓝紫焦边色
                Moci_Util.drawCrossFlare(gate,radius*flashSizeMult(ribbon), // 光斑中心、应用本版倍率后的整体尺寸
                        0f,burst,FLASH,                            // 长轴朝向（0 度为水平）、淡入淡出进度、闪光颜色
                        FLASH_CORE_SIZE_MULT,FLASH_BRIGHTNESS_MULT); // 独立调整亮芯大小与整束光的亮度
            }
            if (!ribbon) Moci_Util.drawPortalRim(gate,radius,facing, // 仅原版绘制门面和门圈；差分版的亮带随舰体绘制
                    opacity,RIM,.295f,                            // 透明度、门沿颜色、全开短长轴比（与柔边遮罩配对）
                    aperture(age),age,seed);                      // 当前开度 0~1、演出秒数、光晕流动种子
    }

    @Override public void render(CombatEngineLayers layer, ViewportAPI viewport) {
        if (expired || !visible() || !viewport.isNearViewport(gate,getRenderRadius())) return;
        if (layer==CombatEngineLayers.UNDER_SHIPS_LAYER) {
            drawAtmosphere(gate,radius,facing,age,seed,viewport.getAlphaMult(),scorch,ribbon);
            return;
        }
        if (released || age<EXIT || !(viewport instanceof CombatViewport nativeView)) return;
        float d=offset(age,nose,tail,radius);
        boolean allNear=nativeView.isEverythingNearViewport();
        boolean gl2=GLContext.getCapabilities().OpenGL20;
        int program=gl2?glGetInteger(GL_CURRENT_PROGRAM):0;
        boolean masking=false;
        glPushAttrib(GL_ALL_ATTRIB_BITS); glMatrixMode(GL_MODELVIEW); glPushMatrix();
        try {
            int id=ribbon ? Moci_NebulaGateMask.ribbonTexture() : Moci_NebulaGateMask.texture();
            if (gl2) glUseProgram(0);
            if (id!=0) { mask.begin(id,ribbon); masking=true; }
            else { glClipPlane(GL_CLIP_PLANE0,plane); glEnable(GL_CLIP_PLANE0); }
            glTranslatef(axisX*d,axisY*d,0);
            nativeView.setEverythingNearViewport(true); // native culling tests the real ship centre, not the translated image
            for (Part p : parts) p.draw(layer,nativeView);
        } finally {
            nativeView.setEverythingNearViewport(allNear);
            if (masking) mask.end();
            if (gl2) glUseProgram(program);
            glMatrixMode(GL_MODELVIEW); glPopMatrix(); glPopAttrib();
        }
    }
}
