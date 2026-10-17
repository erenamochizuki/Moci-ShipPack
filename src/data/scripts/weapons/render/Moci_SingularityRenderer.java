package data.scripts.weapons.render;

import java.awt.Color;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL12;
import org.lwjgl.util.vector.Vector2f;
import org.lwjgl.BufferUtils;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import org.apache.log4j.Logger;

/**
 * 通用奇点（黑洞）渲染器：
 * - 在 JUST_BELOW_WIDGETS 层统一渲染事件视界与引力透镜（无吸积盘）
 * - 可跟踪多枚投射物，按屏幕可见性与寿命进行增量更新
 */
public final class Moci_SingularityRenderer extends BaseCombatLayeredRenderingPlugin {

    public static final String RENDER_KEY = "Moci_SingularityRenderer";
    private static final Logger LOG = Global.getLogger(Moci_SingularityRenderer.class);

    private final CombatEngineAPI engine;
    private final Map<DamagingProjectileAPI, SingularityData> active = new HashMap<DamagingProjectileAPI, SingularityData>();
    private final Map<Object, SingularityData> manual = new HashMap<Object, SingularityData>();

    // 着色器与采样纹理资源
    private int program = 0;
    private int[] uniform = null; // 0: srcTex, 1: center, 2: rs_rlens_strength, 3: texSize, 4: darkAlpha
    private int screenTexId = 0;
    private int screenTexW = 0;
    private int screenTexH = 0;
    private final Vector2f renderAnchor = new Vector2f();

    // 可配置默认参数（保持通用性）
    private static final float DEFAULT_EVENT_HORIZON_RADIUS = 180f;      // 事件视界半径（世界坐标，供换算像素）
    private static final float DEFAULT_LENS_STRENGTH = 50f;              // 折射强度缩放（像素尺度）
    private static final float DEFAULT_LENS_SIZE_MULT = 2.8f;            // 折射影响半径 = rs * 此倍数
    private static final float DEFAULT_DARK_ALPHA = 1.0f;                // 常态完全接管；蒸发时单独衰减
    private static final float CAPTURE_MARGIN = 4f;                      // 采样边缘安全余量（像素）

    // 成长参数（质量驱动）
    private static final float MASS_GROWTH_PER_KILL = 0.35f;             // 每吞噬一艘舰船增加的质量系数
    private static final float MASS_GROWTH_PER_SECOND = 0.05f;           // 飞行中每秒增长
    private static final float MAX_MASS_MULT = 3.5f;                     // 上限
    // 质量同化（缓存 -> 增长）
    private static final float ASSIMILATION_BASE_FRAC_PER_SEC = 0.25f;   // 基础同化比例/秒
    private static final float ASSIMILATION_SQRT_MULT = 0.65f;           // 随增长质量开根的附加比例
    private static final float ASSIMILATION_MAX_FRAC = 0.95f;            // 限制单秒内同化上限，避免一次性清空

    // 像素制半径参数（默认）
    private static final float DEFAULT_BASE_RADIUS_PX = 10f;             // 初始像素半径
    private static final float DEFAULT_GROWTH_PX_PER_SEC = 2f;           // 每秒像素增长
    private static final float EINSTEIN_SCALE = 0.03f;                   // θE ~ sqrt(mass) * scale（像素）

    // 物理定标常量（屏幕像素尺度，折叠距离比）
    private static final float MASS_NORMALIZER = 10000f;                 // M0，巡洋级结构量级
    private static final float RS_PIXELS_PER_MHAT = 40f;                 // k_px：r_s 像素/单位 M̂（可调）
    private static final float SHADOW_MULT = 2.6f;                       // 阴影=2.6 r_s
    private static final float THETA_E_COEFF = 90f;                      // θE = 90 * sqrt(M̂) 像素（可调）
    private static final float RMAX_COEFF = 3.5f;                         // rMax = 3.5 * θE
    private static final float COLLAPSE_THRESHOLD_PX = 100f;              // 达到此事件视界像素后坍塌
    private static final float COLLAPSE_SHRINK_PX_PER_SEC = 140f;         // 坍塌完成后向 0px 收缩速度
    private static final float TRAVEL_MASS_PER_WORLD = 2f;                // 每世界单位位移增加的质量
    private static final float RADIUS_SMOOTH_LERP = 5f;                   // 半径平滑时间常数（秒）
    // 关闭坍塌阶段时：允许继续膨胀到上限并在弹体结束时蒸发
    private static final float NO_COLLAPSE_MAX_RS_PX = 250f;              // 无坍塌上限像素半径
    private static boolean COLLAPSE_ENABLED = false;                       // 全局坍塌开关（默认关闭）
    // 合并/爆炸参数
    private static final float MERGE_DISTANCE_PX = 20f;                   // 视界中心间像素距离小于此阈值视为相撞
    private static final float SUPER_NOVA_MASS_THRESHOLD = 5f * MASS_NORMALIZER; // 超爆的质量阈值（M̂ ~5）

    // 运行时
    private float elapsed = 0f; // 预留将来用作时间驱动参数
    private static final float LOG_INTERVAL = 0.5f;

    private static final class SingularityData {
        DamagingProjectileAPI projectile;
        Object token;
        float eventHorizonRadius;
        float lensStrength;
        int texId;
        int texW;
        int texH;
        float massMult;
        float massCache;                 // 缓存质量：新增质量先进入此处
        float massGrowth;                // 增长质量：半径/透镜/伤害依据
        float baseRadiusPx;              // 初始像素半径
        float growthPxPerSec;            // 每秒像素增长
        float currentRadiusPx;           // 当前像素半径
        float smoothedRsPx;              // 渲染使用的平滑半径
        boolean collapsed;
        boolean collapsing;
        float collapseProgress;          // 0..1
        float collapseStartRsPx;
        Vector2f manualCenter = new Vector2f();
        float lastLogAt = -999f;
        int collapseStepLogged = -1;
        boolean freezeCenter;
        final Vector2f frozenCenter = new Vector2f();
        float afterCollapseHold = 0f;
        boolean lastCenterInit;
        final Vector2f lastCenterWorld = new Vector2f();
        boolean evaporating;             // 蒸发阶段：投射物生命周期结束后进入，质量与半径衰减
        float evaporateProgress;         // 0..1
        final Set<ShipAPI> affectedShips = new HashSet<ShipAPI>();  // 被此奇点影响过的舰船

        SingularityData(DamagingProjectileAPI p, float r, float lens, Color diskColor) {
            this.projectile = p;
            this.token = null;
            this.eventHorizonRadius = r;
            this.lensStrength = lens;
            this.texId = 0;
            this.texW = 0;
            this.texH = 0;
            this.massMult = 1f;
            this.massCache = 0f;
            this.massGrowth = 0f;
            this.baseRadiusPx = DEFAULT_BASE_RADIUS_PX;
            this.growthPxPerSec = DEFAULT_GROWTH_PX_PER_SEC;
            this.currentRadiusPx = this.baseRadiusPx;
            this.smoothedRsPx = 1.0f; // 从极小值开始，平滑插值到目标大小，避免瞬间膨胀
            this.collapsed = false;
            this.collapsing = false;
            this.collapseProgress = 0f;
            this.collapseStartRsPx = this.currentRadiusPx;
            this.freezeCenter = false;
            this.afterCollapseHold = 0f;
            this.lastCenterInit = false;
            this.evaporating = false;
            this.evaporateProgress = 0f;
        }
    }

    public static Moci_SingularityRenderer getOrCreate() {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return null;
        Object existing = engine.getCustomData().get(RENDER_KEY);
        if (existing instanceof Moci_SingularityRenderer) {
            return (Moci_SingularityRenderer) existing;
        }
        Moci_SingularityRenderer renderer = new Moci_SingularityRenderer();
        engine.addLayeredRenderingPlugin(renderer);
        engine.getCustomData().put(RENDER_KEY, renderer);
        return renderer;
    }

    private Moci_SingularityRenderer() {
        this.engine = Global.getCombatEngine();
        this.layer = CombatEngineLayers.JUST_BELOW_WIDGETS; // UI 正下方
        initShader();
    }

    /**
     * 通用接口：开始跟踪一枚奇点投射物
     */
    public void trackProjectile(DamagingProjectileAPI proj) {
        trackProjectile(proj, DEFAULT_EVENT_HORIZON_RADIUS, DEFAULT_LENS_STRENGTH, new Color(255, 180, 80, 255));
        if (proj != null) {
            LOG.info("[MOCI_SINGULARITY] trackProjectile id=" + proj.getProjectileSpecId());
        } else {
            LOG.warn("[MOCI_SINGULARITY] trackProjectile null projectile");
        }
    }

    /**
     * 高级接口：自定义半径/强度/颜色
     */
    public void trackProjectile(DamagingProjectileAPI proj, float eventHorizonRadius, float lensStrength, Color accretionColor) {
        if (proj == null) return;
        if (active.containsKey(proj)) return;
        active.put(proj, new SingularityData(proj, eventHorizonRadius, lensStrength, accretionColor));
    }

    // 手动注册：以自定义 token + 初始中心跟踪奇点（无真实投射物）
    public void trackAt(Object token, Vector2f initialCenter) {
        if (token == null || initialCenter == null) return;
        if (manual.containsKey(token)) return;
        SingularityData s = new SingularityData(null, DEFAULT_EVENT_HORIZON_RADIUS, DEFAULT_LENS_STRENGTH, new Color(255,180,80,255));
        s.token = token;
        s.manualCenter.set(initialCenter);
        manual.put(token, s);
    }

    public void updateCenter(Object token, Vector2f center) {
        SingularityData s = manual.get(token);
        if (s != null && center != null) s.manualCenter.set(center);
    }

    private void updateRenderAnchor() {
        for (SingularityData s : active.values()) {
            Vector2f center = getCenterForRender(s);
            if (center != null) {
                renderAnchor.set(center);
                return;
            }
        }
        for (SingularityData s : manual.values()) {
            Vector2f center = getCenterForRender(s);
            if (center != null) {
                renderAnchor.set(center);
                return;
            }
        }
        CombatEngineAPI combatEngine = Global.getCombatEngine();
        if (combatEngine != null && combatEngine.getViewport() != null) {
            ViewportAPI viewport = combatEngine.getViewport();
            renderAnchor.set(viewport.getLLX() + viewport.getVisibleWidth() * 0.5f,
                    viewport.getLLY() + viewport.getVisibleHeight() * 0.5f);
        }
    }

    private Vector2f getCenterForRender(SingularityData s) {
        if (s == null) return null;
        if (s.freezeCenter) return s.frozenCenter;
        if (s.projectile != null) return s.projectile.getLocation();
        return s.manualCenter;
    }

    @Override
    public void advance(float amount) {
        if (engine == null || engine.isPaused()) return;

        elapsed += amount;
        updateRenderAnchor();

        // 清理过期投射物
        for (Iterator<Map.Entry<DamagingProjectileAPI, SingularityData>> it = active.entrySet().iterator(); it.hasNext();) {
            Map.Entry<DamagingProjectileAPI, SingularityData> e = it.next();
            DamagingProjectileAPI p = e.getKey();
            SingularityData s = e.getValue();
            boolean allowLifecycleCleanup = !s.collapsing && (!s.collapsed || (s.afterCollapseHold <= 0f && s.smoothedRsPx <= 0.5f));
            boolean projEnded = (p != null) && (p.isFading() || p.isExpired() || p.getBrightness() <= 0f);
            // 若投射物结束且尚未进入蒸发阶段，则转入蒸发而不立即清理
            if (projEnded && !s.evaporating && !s.collapsing && !s.collapsed) {
                s.evaporating = true;
                s.evaporateProgress = 0f;
                if (p != null) { s.frozenCenter.set(p.getLocation()); s.freezeCenter = true; }
            }
            boolean evapDone = s.evaporating && (s.evaporateProgress >= 0.999f || s.smoothedRsPx <= 0.5f);
            boolean shouldCleanup = (p == null) || p.didDamage() || evapDone ||
                    (allowLifecycleCleanup && projEnded && !s.evaporating);
            if (shouldCleanup) {
                if (s.texId != 0) {
                    try { GL11.glDeleteTextures(s.texId); } catch (Exception ex) { }
                }
                // 在清理前，对所有受影响的舰船应用速度阻尼，避免被抛出战场
                applyVelocityDampingToAffectedShips(s);
                String reason = (p == null) ? "null" :
                        (p.didDamage() ? "didDamage" :
                        (allowLifecycleCleanup && p.isFading() ? "isFading" :
                        (allowLifecycleCleanup && p.isExpired() ? "isExpired" :
                        (allowLifecycleCleanup && p.getBrightness() <= 0f ? "noBrightness" : "unknown"))));
                String pid = (p != null ? p.getProjectileSpecId() : "null");
                LOG.info("[MOCI_SINGULARITY] cleanup projectile id=" + pid + " reason=" + reason + " texId=" + s.texId + " collapsing=" + s.collapsing + " collapsed=" + s.collapsed + " rsPx=" + s.smoothedRsPx);
                it.remove();
            }
        }
        // 手动项不自动清理，由外部 stopTracking 调用

        // 基于时间的质量增长
        for (SingularityData s : active.values()) {
            s.massMult = Math.min(MAX_MASS_MULT, s.massMult + MASS_GROWTH_PER_SECOND * amount);
            s.currentRadiusPx += s.growthPxPerSec * amount;
            // 移动距离驱动的质量增长（坍塌阶段冻结中心后不再增长）
            try {
                if (!s.freezeCenter && s.projectile != null) {
                    Vector2f cur = s.projectile.getLocation();
                    if (!s.lastCenterInit) {
                        s.lastCenterWorld.set(cur);
                        s.lastCenterInit = true;
                    } else {
                        float dx = cur.x - s.lastCenterWorld.x;
                        float dy = cur.y - s.lastCenterWorld.y;
                        float dist = (float)Math.sqrt(dx*dx + dy*dy);
                        if (dist > 0f) {
                            s.lastCenterWorld.set(cur);
                            s.massCache += dist * TRAVEL_MASS_PER_WORLD;
                        }
                    }
                }
            } catch (Throwable ignore) { }
            // 缓存质量 -> 增长质量 同化：按增长质量规模渐强
            {
                float mHatGrowth = Math.max(0f, s.massGrowth) / MASS_NORMALIZER;
                float rate = ASSIMILATION_BASE_FRAC_PER_SEC * (1f + ASSIMILATION_SQRT_MULT * (float)Math.sqrt(Math.max(0f, mHatGrowth)));
                rate = Math.min(ASSIMILATION_MAX_FRAC, Math.max(0f, rate));
                float moved = Math.min(s.massCache, s.massCache * rate * amount);
                if (moved > 0f) { s.massCache -= moved; s.massGrowth += moved; }
            }
            // 平滑过渡：指数趋近
            float lerp = 1f - (float)Math.exp(-amount / Math.max(0.001f, RADIUS_SMOOTH_LERP));
            float mHat = Math.max(0f, s.massGrowth) / MASS_NORMALIZER;
            float mHatRender = Math.min(mHat, 25f);
            float rsPhysicalPx = SHADOW_MULT * (RS_PIXELS_PER_MHAT * mHatRender);
            float growthPx = s.currentRadiusPx * s.massMult;
            float targetPx;
            if (s.collapsed) {
                targetPx = 10f;
            } else if (s.collapsing) {
                targetPx = s.smoothedRsPx;
            } else if (s.evaporating) {
                // 蒸发阶段：质量与半径逐步衰减，中心冻结
                s.evaporateProgress = Math.min(1f, s.evaporateProgress + amount * 0.6f);
                s.massGrowth = Math.max(0f, s.massGrowth * (1f - 0.6f * amount));
                s.massCache = Math.max(0f, s.massCache * (1f - 0.6f * amount));
                float targetEvap = Math.max(0f, s.smoothedRsPx * (1f - 0.7f * amount));
                targetPx = Math.min(s.smoothedRsPx, targetEvap);
            } else {
                targetPx = Math.max(growthPx, rsPhysicalPx);
                if (!COLLAPSE_ENABLED) targetPx = Math.min(targetPx, NO_COLLAPSE_MAX_RS_PX);
            }
            s.smoothedRsPx = s.smoothedRsPx + (targetPx - s.smoothedRsPx) * lerp;
            // 坍塌触发（仅在开启时）
            if (COLLAPSE_ENABLED && !s.collapsed && !s.collapsing && s.smoothedRsPx >= COLLAPSE_THRESHOLD_PX) {
                s.collapsing = true;
                s.collapseStartRsPx = s.smoothedRsPx;
                s.collapseProgress = 0f;
                if (s.projectile != null) { s.frozenCenter.set(s.projectile.getLocation()); s.freezeCenter = true; }
                LOG.info("[MOCI_SINGULARITY] collapse start (projectile) rsPx=" + s.smoothedRsPx);
            }
            // 坍塌过程：逐步向 10px 收缩；坍塌阶段不再提供引力
            if (s.collapsing && !s.collapsed) {
                float target = 10f;
                s.smoothedRsPx = s.smoothedRsPx + (target - s.smoothedRsPx) * Math.min(1f, lerp * 2.5f);
                float span = Math.max(1f, s.collapseStartRsPx - 10f);
                s.collapseProgress = Math.min(1f, 1f - (s.smoothedRsPx - 10f) / span);
                int step = (int)Math.floor(s.collapseProgress * 5f);
                if (step > s.collapseStepLogged) {
                    s.collapseStepLogged = step;
                    LOG.info("[MOCI_SINGULARITY] collapse progress (projectile) step=" + step + "/5 rsPx=" + s.smoothedRsPx + " progress=" + s.collapseProgress);
                }
                if (s.smoothedRsPx <= 10.5f) {
                    s.smoothedRsPx = 10f;
                    s.collapseProgress = 1f;
                    s.collapsing = false;
                    s.collapsed = true;
                    s.afterCollapseHold = 0.5f; // 留给喷发/收尾的缓冲时间
                    LOG.info("[MOCI_SINGULARITY] collapse completed (projectile)");
                }
            }
            // 坍塌完成后的延迟清理：当弹体失效且缓冲时间结束时，允许清理
            if (s.collapsed) {
                // 坍塌完成后继续向 0px 收缩
                if (s.smoothedRsPx > 0f) {
                    s.smoothedRsPx = Math.max(0f, s.smoothedRsPx - COLLAPSE_SHRINK_PX_PER_SEC * amount);
                }
                if (s.afterCollapseHold > 0f) s.afterCollapseHold -= amount;
                DamagingProjectileAPI p = s.projectile;
                if (p != null && s.afterCollapseHold <= 0f) {
                    // 允许其被生命周期回收：不在此处删除实体，只解除保护
                    // do nothing here; cleanup 阶段的 allowLifecycleCleanup 将起效
                }
            }
            // 周期性诊断
            if (elapsed - s.lastLogAt >= LOG_INTERVAL) {
                s.lastLogAt = elapsed;
                mHat = Math.max(0f, s.massGrowth) / MASS_NORMALIZER;
                rsPhysicalPx = SHADOW_MULT * (RS_PIXELS_PER_MHAT * Math.min(mHat, 25f));
                LOG.debug("[MOCI_SINGULARITY] diag P rsSmooth=" + s.smoothedRsPx + " rsPhys=" + rsPhysicalPx + " mGrowth=" + s.massGrowth + " mCache=" + s.massCache + " mHat=" + mHat + " collapsing=" + s.collapsing + " collapsed=" + s.collapsed);
            }
        }
        // 相撞检测：两两检测，合并或超爆
        java.util.List<SingularityData> list = new java.util.ArrayList<SingularityData>(active.values());
        for (int i=0; i<list.size(); i++) {
            SingularityData a = list.get(i);
            if (a.evaporating || a.collapsing || a.collapsed) continue;
            Vector2f ca = a.freezeCenter ? a.frozenCenter : (a.projectile!=null? a.projectile.getLocation(): null);
            if (ca == null) continue;
            for (int j=i+1; j<list.size(); j++) {
                SingularityData b = list.get(j);
                if (b.evaporating || b.collapsing || b.collapsed) continue;
                Vector2f cb = b.freezeCenter ? b.frozenCenter : (b.projectile!=null? b.projectile.getLocation(): null);
                if (cb == null) continue;
                float dx = ca.x - cb.x, dy = ca.y - cb.y;
                float dist = (float)Math.sqrt(dx*dx + dy*dy);
                // 转换为屏幕像素距离以比较阈值
                float pixelsPerWorldUnit = screenTexW / Math.max(1f, Global.getCombatEngine().getViewport().getVisibleWidth());
                float dpix = dist * pixelsPerWorldUnit;
                if (dpix <= MERGE_DISTANCE_PX) {
                    float totalGrowth = a.massGrowth + b.massGrowth;
                    if (totalGrowth >= SUPER_NOVA_MASS_THRESHOLD) {
                        triggerSuperExplosion(a, b, ca);
                    } else {
                        mergeSingularities(a, b, ca, cb);
                    }
                }
            }
        }
        // 坍塌完成或蒸发完成后，主动清理跟踪条目与纹理，避免视觉残留
        for (Iterator<Map.Entry<DamagingProjectileAPI, SingularityData>> it2 = active.entrySet().iterator(); it2.hasNext();) {
            Map.Entry<DamagingProjectileAPI, SingularityData> en = it2.next();
            SingularityData s = en.getValue();
            boolean collapseClean = s.collapsed && s.afterCollapseHold <= 0f && s.smoothedRsPx <= 0.5f;
            boolean evapClean = s.evaporating && (s.evaporateProgress >= 0.999f || s.smoothedRsPx <= 0.5f);
            if (collapseClean || evapClean) {
                if (s.texId != 0) { 
                    try { 
                        GL11.glDeleteTextures(s.texId); 
                        LOG.info("[MOCI_SINGULARITY] deleted singularity texId=" + s.texId);
                    } catch (Exception ignore) {} 
                }
                // 在清理前，对所有受影响的舰船应用速度阻尼，避免被抛出战场
                applyVelocityDampingToAffectedShips(s);
                it2.remove();
                // 屏幕纹理是共享的，不应该在单个奇点清理时刷新
                // 它会在每帧render时重新捕获，或在cleanup时删除
            }
        }
        for (SingularityData s : manual.values()) {
            s.massMult = Math.min(MAX_MASS_MULT, s.massMult + MASS_GROWTH_PER_SECOND * amount);
            s.currentRadiusPx += s.growthPxPerSec * amount;
            // 同化
            {
                float mHatGrowth = Math.max(0f, s.massGrowth) / MASS_NORMALIZER;
                float rate = ASSIMILATION_BASE_FRAC_PER_SEC * (1f + ASSIMILATION_SQRT_MULT * (float)Math.sqrt(Math.max(0f, mHatGrowth)));
                rate = Math.min(ASSIMILATION_MAX_FRAC, Math.max(0f, rate));
                float moved = Math.min(s.massCache, s.massCache * rate * amount);
                if (moved > 0f) { s.massCache -= moved; s.massGrowth += moved; }
            }
            float lerp = 1f - (float)Math.exp(-amount / Math.max(0.001f, RADIUS_SMOOTH_LERP));
            float mHat = Math.max(0f, s.massGrowth) / MASS_NORMALIZER;
            float mHatRender = Math.min(mHat, 25f);
            float rsPhysicalPx = SHADOW_MULT * (RS_PIXELS_PER_MHAT * mHatRender);
            float growthPx = (s.collapsing || s.collapsed) ? s.smoothedRsPx : s.currentRadiusPx * s.massMult;
            float targetPx = s.collapsed ? 10f : Math.max(growthPx, rsPhysicalPx);
            if (!COLLAPSE_ENABLED) targetPx = Math.min(targetPx, NO_COLLAPSE_MAX_RS_PX);
            s.smoothedRsPx = s.smoothedRsPx + (targetPx - s.smoothedRsPx) * lerp;
            if (COLLAPSE_ENABLED && !s.collapsed && !s.collapsing && s.smoothedRsPx >= COLLAPSE_THRESHOLD_PX) {
                s.collapsing = true; s.collapseStartRsPx = s.smoothedRsPx; s.collapseProgress = 0f;
                LOG.info("[MOCI_SINGULARITY] collapse start (manual) rsPx=" + s.smoothedRsPx);
            }
            if (s.collapsing && !s.collapsed) {
                float target = 10f;
                s.smoothedRsPx = s.smoothedRsPx + (target - s.smoothedRsPx) * Math.min(1f, lerp * 2.5f);
                float span = Math.max(1f, s.collapseStartRsPx - 10f);
                s.collapseProgress = Math.min(1f, 1f - (s.smoothedRsPx - 10f) / span);
                int step = (int)Math.floor(s.collapseProgress * 5f);
                if (step > s.collapseStepLogged) { s.collapseStepLogged = step; LOG.info("[MOCI_SINGULARITY] collapse progress (manual) step=" + step + "/5 rsPx=" + s.smoothedRsPx + " progress=" + s.collapseProgress); }
                if (s.smoothedRsPx <= 10.5f) { s.smoothedRsPx = 10f; s.collapseProgress = 1f; s.collapsing=false; s.collapsed=true; LOG.info("[MOCI_SINGULARITY] collapse completed (manual)"); }
            }
            if (s.collapsed) {
                if (s.smoothedRsPx > 0f) s.smoothedRsPx = Math.max(0f, s.smoothedRsPx - COLLAPSE_SHRINK_PX_PER_SEC * amount);
            }
            if (elapsed - s.lastLogAt >= LOG_INTERVAL) {
                s.lastLogAt = elapsed;
                mHat = Math.max(0f, s.massGrowth) / MASS_NORMALIZER;
                rsPhysicalPx = SHADOW_MULT * (RS_PIXELS_PER_MHAT * Math.min(mHat, 25f));
                LOG.debug("[MOCI_SINGULARITY] diag M rsSmooth=" + s.smoothedRsPx + " rsPhys=" + rsPhysicalPx + " mGrowth=" + s.massGrowth + " mCache=" + s.massCache + " mHat=" + mHat + " collapsing=" + s.collapsing + " collapsed=" + s.collapsed);
            }
        }
    }

    @Override
    public void render(CombatEngineLayers layer, ViewportAPI viewport) {
        if (layer != CombatEngineLayers.JUST_BELOW_WIDGETS) return;
        if (active.isEmpty() && manual.isEmpty()) return;

        // 检测战术指挥UI状态：当显示指挥地图时跳过渲染，避免地图上的舰船图标被引力透镜扭曲
        if (engine != null && engine.getCombatUI() != null && engine.getCombatUI().isShowingCommandUI()) {
            LOG.debug("[MOCI_SINGULARITY] skipping render: command UI showing");
            return;
        }
        
        // 额外检测：其他对话框（部署界面等）
        if (engine != null && engine.getCombatUI() != null && engine.getCombatUI().isShowingDeploymentDialog()) {
            LOG.debug("[MOCI_SINGULARITY] skipping render: deployment dialog showing");
            return;
        }

        float screenW = Global.getSettings().getScreenWidth();
        float screenH = Global.getSettings().getScreenHeight();

        // 1) 拷贝整屏已渲染的世界画面（UI 仍未绘制），使用实际 GL 视口尺寸，避免缩放/像素比例偏差
        IntBuffer vpBuf = BufferUtils.createIntBuffer(16);
        GL11.glGetInteger(GL11.GL_VIEWPORT, vpBuf);
        int vpX = vpBuf.get(0);
        int vpY = vpBuf.get(1);
        int vpW = vpBuf.get(2);
        int vpH = vpBuf.get(3);

        ensureScreenTexture(vpW, vpH);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, screenTexId);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, vpX, vpY, vpW, vpH);

        ViewportAPI vp = Global.getCombatEngine().getViewport();

        // 2) 对每个奇点执行一次全屏折射叠加（UI 不受影响）。坍塌阶段不渲染透镜。
        for (SingularityData s : active.values()) {
            Vector2f world = s.freezeCenter ? s.frozenCenter : s.projectile.getLocation();
            float sx = vp.convertWorldXtoScreenX(world.x);
            float sy = vp.convertWorldYtoScreenY(world.y);
            // 将世界长度换算为以捕获纹理像素为单位的长度，避免逻辑分辨率/像素分辨率不一致
            float pixelsPerWorldUnit = screenTexW / vp.getVisibleWidth();
            float mHat = Math.max(0f, s.massGrowth) / MASS_NORMALIZER;
            float mHatRender = Math.min(mHat, 25f);
            float rsPx = s.smoothedRsPx; // 使用已平滑的最终半径
            // 爱因斯坦半径与影响半径
            float thetaEPx = THETA_E_COEFF * (float)Math.sqrt(Math.max(0f, mHatRender));
            // 保证 θE 不小于 rs 的一定比例，避免极小质量时形变不连续
            thetaEPx = Math.max(rsPx * 0.9f, thetaEPx);
            float rMaxPx;
            if (s.collapsing || s.collapsed) {
                // 坍塌阶段：禁用透镜，仅显示视界（rMax=rs）
                rMaxPx = rsPx;
            } else {
                rMaxPx = Math.max(rsPx * 1.2f, RMAX_COEFF * thetaEPx);
            }
            // 按屏幕尺寸强制上限，防止覆盖全屏
            float screenMinPx = Math.min(screenTexW, screenTexH);
            rsPx = Math.min(rsPx, screenMinPx * 0.45f);
            rMaxPx = Math.min(rMaxPx, screenMinPx * 0.49f);
            thetaEPx = Math.max(rsPx * 0.9f, Math.min(thetaEPx, rMaxPx * 0.95f));
            if (rMaxPx - rsPx < 6f) {
                LOG.debug("[MOCI_SINGULARITY] lens band too thin; rsPx=" + rsPx + " rMaxPx=" + rMaxPx + " thetaE=" + thetaEPx);
            }

            if (program != 0 && uniform != null) {
                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                try {
                GL20.glUseProgram(program);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

                GL11.glBindTexture(GL11.GL_TEXTURE_2D, screenTexId);
                GL20.glUniform1i(uniform[0], 0);

                // 使用可见世界窗口的归一化坐标，消除相机平移/缩放影响
                float centerUx = (world.x - vp.getLLX()) / vp.getVisibleWidth();
                float centerUy = (world.y - vp.getLLY()) / vp.getVisibleHeight();
                GL20.glUniform2f(uniform[1], centerUx, centerUy);

                GL20.glUniform3f(uniform[2], rsPx, rMaxPx, thetaEPx);
                GL20.glUniform2f(uniform[3], (float)screenTexW, (float)screenTexH);
                // 蒸发阶段按进度淡出；常态用默认 alpha
                float fade = 1f;
                if (s.evaporating) fade = Math.max(0f, 1f - s.evaporateProgress);
                GL20.glUniform1f(uniform[4], DEFAULT_DARK_ALPHA * fade);
                // 可配置的放大强度，0 = 仅几何形变（不提亮），1 = 完整雅可比放大
                int magLocation = GL20.glGetUniformLocation(program, "magStrength");
                if (magLocation >= 0) GL20.glUniform1f(magLocation, 0.0f);
                // 事件视界颜色：坍塌后为白色，否则黑色
                int hc = GL20.glGetUniformLocation(program, "horizonColor");
                if (hc >= 0) {
                    if (s.collapsed) {
                        GL20.glUniform3f(hc, 1f, 1f, 1f);
                    } else if (s.collapsing) {
                        float t = Math.min(1f, Math.max(0f, s.collapseProgress));
                        float c = t; // 0黑->1白
                        GL20.glUniform3f(hc, c, c, c);
                    } else {
                        GL20.glUniform3f(hc, 0f, 0f, 0f);
                    }
                }

                // 切换到屏幕空间的正交投影，绘制全屏四边形，避免与世界矩阵混用导致的采样偏移
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPushMatrix();
                GL11.glLoadIdentity();
                GL11.glOrtho(0, (double)screenTexW, 0, (double)screenTexH, -1, 1);
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPushMatrix();
                GL11.glLoadIdentity();

                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(0f, 0f);
                GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(0f, screenTexH);
                GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(screenTexW, screenTexH);
                GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(screenTexW, 0f);
                GL11.glEnd();

                // 恢复矩阵
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPopMatrix();

                GL20.glUseProgram(0);
                } finally {
                    GL11.glPopAttrib();
                    GL11.glMatrixMode(GL11.GL_MODELVIEW);
                    GL20.glUseProgram(0);
                }
            } else {
                LOG.warn("[MOCI_SINGULARITY] shader program missing; program=" + program + " uniformNull=" + (uniform==null));
            }
        }
        // 手动项渲染（坍塌阶段不渲染透镜）
        for (SingularityData s : manual.values()) {
            Vector2f world = s.freezeCenter ? s.frozenCenter : s.manualCenter;
            float sx = vp.convertWorldXtoScreenX(world.x);
            float sy = vp.convertWorldYtoScreenY(world.y);

            float pixelsPerWorldUnit = screenTexW / vp.getVisibleWidth();
            float mHat = Math.max(0f, s.massGrowth) / MASS_NORMALIZER;
            float mHatRender = Math.min(mHat, 25f);
            float rsPx = s.smoothedRsPx;
            float thetaEPx = THETA_E_COEFF * (float)Math.sqrt(Math.max(0f, mHatRender));
            thetaEPx = Math.max(rsPx * 0.9f, thetaEPx);
            float rMaxPx;
            if (s.collapsing || s.collapsed) {
                rMaxPx = rsPx;
            } else {
                rMaxPx = Math.max(rsPx * 1.2f, RMAX_COEFF * thetaEPx);
            }
            float screenMinPx = Math.min(screenTexW, screenTexH);
            rsPx = Math.min(rsPx, screenMinPx * 0.45f);
            rMaxPx = Math.min(rMaxPx, screenMinPx * 0.49f);
            thetaEPx = Math.max(rsPx * 0.9f, Math.min(thetaEPx, rMaxPx * 0.95f));
            if (rMaxPx - rsPx < 6f) {
                LOG.debug("[MOCI_SINGULARITY] lens band too thin (manual); rsPx=" + rsPx + " rMaxPx=" + rMaxPx + " thetaE=" + thetaEPx);
            }

            if (program != 0 && uniform != null) {
                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                try {
                GL20.glUseProgram(program);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

                GL11.glBindTexture(GL11.GL_TEXTURE_2D, screenTexId);
                GL20.glUniform1i(uniform[0], 0);

                float centerUx = (world.x - vp.getLLX()) / vp.getVisibleWidth();
                float centerUy = (world.y - vp.getLLY()) / vp.getVisibleHeight();
                GL20.glUniform2f(uniform[1], centerUx, centerUy);

                GL20.glUniform3f(uniform[2], rsPx, rMaxPx, thetaEPx);
                GL20.glUniform2f(uniform[3], (float)screenTexW, (float)screenTexH);
                float fadeM = 1f;
                if (s.evaporating) fadeM = Math.max(0f, 1f - s.evaporateProgress);
                GL20.glUniform1f(uniform[4], DEFAULT_DARK_ALPHA * fadeM);
                int magLocation = GL20.glGetUniformLocation(program, "magStrength");
                if (magLocation >= 0) GL20.glUniform1f(magLocation, 0.0f);
                int hc = GL20.glGetUniformLocation(program, "horizonColor");
                if (hc >= 0) {
                    if (s.collapsed) GL20.glUniform3f(hc, 1f, 1f, 1f);
                    else if (s.collapsing) {
                        float t = Math.min(1f, Math.max(0f, s.collapseProgress));
                        float c = t;
                        GL20.glUniform3f(hc, c, c, c);
                    } else GL20.glUniform3f(hc, 0f, 0f, 0f);
                }

                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPushMatrix();
                GL11.glLoadIdentity();
                GL11.glOrtho(0, (double)screenTexW, 0, (double)screenTexH, -1, 1);
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPushMatrix();
                GL11.glLoadIdentity();

                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(0f, 0f);
                GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(0f, screenTexH);
                GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(screenTexW, screenTexH);
                GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(screenTexW, 0f);
                GL11.glEnd();

                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPopMatrix();

                GL20.glUseProgram(0);
                } finally {
                    GL11.glPopAttrib();
                    GL11.glMatrixMode(GL11.GL_MODELVIEW);
                    GL20.glUseProgram(0);
                }
            }
        }
    }

    @Override
    public EnumSet<CombatEngineLayers> getActiveLayers() {
        return EnumSet.of(CombatEngineLayers.JUST_BELOW_WIDGETS);
    }

    @Override
    public boolean isExpired() {
        // 挂件式常驻（由引擎在战斗结束时清理）
        return false;
    }

    @Override
    public float getRenderRadius() {
        return 1000000f;
    }

    public Vector2f getLocation() {
        return renderAnchor;
    }

    @Override
    public void init(CombatEntityAPI entity) {
        // no-op
    }

    @Override
    public void cleanup() {
        // 清理所有奇点数据并删除其纹理资源
        for (SingularityData s : active.values()) {
            if (s.texId != 0) {
                try { GL11.glDeleteTextures(s.texId); } catch (Exception ignore) { }
            }
        }
        for (SingularityData s : manual.values()) {
            if (s.texId != 0) {
                try { GL11.glDeleteTextures(s.texId); } catch (Exception ignore) { }
            }
        }
        active.clear();
        manual.clear();
        
        // 清理屏幕纹理资源，防止渲染残留
        if (screenTexId != 0) {
            try {
                GL11.glDeleteTextures(screenTexId);
                LOG.info("[MOCI_SINGULARITY] deleted screenTex id=" + screenTexId);
            } catch (Exception ex) {
                LOG.error("[MOCI_SINGULARITY] failed to delete screenTex", ex);
            }
            screenTexId = 0;
            screenTexW = 0;
            screenTexH = 0;
        }
        
        // 清理着色器程序资源
        if (program != 0) {
            try {
                GL20.glDeleteProgram(program);
                LOG.info("[MOCI_SINGULARITY] deleted shader program id=" + program);
            } catch (Exception ex) {
                LOG.error("[MOCI_SINGULARITY] failed to delete shader program", ex);
            }
            program = 0;
            uniform = null;
        }
    }

    private void initShader() {
        try {
            Moci_ShaderManager mgr = Moci_ShaderManager.getInstance();
            program = mgr.createShader("data/shaders/Moci_SingularityLens.vert", "data/shaders/Moci_SingularityLens.frag", "Moci_SingularityLens");
            if (program != 0) {
                GL20.glUseProgram(program);
                uniform = new int[] {
                    GL20.glGetUniformLocation(program, "srcTex"),
                    GL20.glGetUniformLocation(program, "center"),
                    GL20.glGetUniformLocation(program, "rs_rlens_strength"),
                    GL20.glGetUniformLocation(program, "texSize"),
                    GL20.glGetUniformLocation(program, "darkAlpha")
                };
                GL20.glUseProgram(0);
            }
        } catch (Exception e) {
            program = 0;
            uniform = null;
        }
    }

    private void ensureTexture(SingularityData s, int w, int h) {
        if (s.texId != 0 && s.texW == w && s.texH == h) return;
        if (s.texId != 0) {
            try { GL11.glDeleteTextures(s.texId); } catch (Exception ignore) { }
            s.texId = 0; s.texW = 0; s.texH = 0;
        }
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
        s.texId = id; s.texW = w; s.texH = h;
    }

    private void ensureScreenTexture(int w, int h) {
        if (screenTexId != 0 && screenTexW == w && screenTexH == h) return;
        if (screenTexId != 0) {
            try { GL11.glDeleteTextures(screenTexId); } catch (Exception ignore) { }
            screenTexId = 0; screenTexW = 0; screenTexH = 0;
        }
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
        screenTexId = id; screenTexW = w; screenTexH = h;
        LOG.info("[MOCI_SINGULARITY] init screenTex " + w + "x" + h + " id=" + id);
    }

    // ===== 公共接口：质量与半径 =====
    public static void setCollapseEnabled(boolean enabled) { COLLAPSE_ENABLED = enabled; }
    public static boolean isCollapseEnabled() { return COLLAPSE_ENABLED; }

    // ===== 合并与超爆 =====
    private void mergeSingularities(SingularityData a, SingularityData b, Vector2f ca, Vector2f cb) {
        // 以质量为权的中心
        float mA = Math.max(0f, a.massGrowth + a.massCache);
        float mB = Math.max(0f, b.massGrowth + b.massCache);
        float mT = Math.max(1e-3f, mA + mB);
        float cx = (ca.x * mA + cb.x * mB) / mT;
        float cy = (ca.y * mA + cb.y * mB) / mT;
        Vector2f c = new Vector2f(cx, cy);
        // 将 b 并入 a
        // 合并质量：增长质量与缓存质量分别相加
        a.massGrowth = Math.max(0f, a.massGrowth) + Math.max(0f, b.massGrowth);
        a.massCache  = Math.max(0f, a.massCache)  + Math.max(0f, b.massCache);
        a.currentRadiusPx = Math.max(a.currentRadiusPx, b.currentRadiusPx);
        a.smoothedRsPx = Math.max(a.smoothedRsPx, b.smoothedRsPx);
        a.freezeCenter = true; a.frozenCenter.set(c);
        // b 进入快速蒸发
        b.evaporating = true; b.evaporateProgress = 0f; b.freezeCenter = true; b.frozenCenter.set(cb);
        // 记录日志
        LOG.info("[MOCI_SINGULARITY] merge singularities totalA=" + mA + " totalB=" + mB + " -> " + mT + " (growth=" + a.massGrowth + ", cache=" + a.massCache + ")");
    }

    private void triggerSuperExplosion(SingularityData a, SingularityData b, Vector2f c) {
        // 简易超爆：在中心释放强光/伤害效果（此处占位，可与游戏特效系统对接）
        try {
            CombatEngineAPI eng = Global.getCombatEngine();
            eng.spawnExplosion(c, new Vector2f(0,0), new Color(255, 240, 200, 255), 600f, 2.5f);
            eng.addSmoothParticle(c, new Vector2f(0,0), 1200f, 1f, 2.0f, Color.white);
        } catch (Throwable ignore) {}
        // 两者进入快速蒸发
        a.evaporating = true; a.evaporateProgress = 0f; a.freezeCenter = true; a.frozenCenter.set(c);
        b.evaporating = true; b.evaporateProgress = 0f; b.freezeCenter = true; b.frozenCenter.set(c);
        LOG.info("[MOCI_SINGULARITY] SUPER NOVA triggered");
    }
    public void addMass(DamagingProjectileAPI proj, float delta) {
        if (proj == null) return;
        SingularityData s = active.get(proj);
        if (s == null) return;
        s.massCache += Math.max(0f, delta);
        s.massMult = Math.max(1f, Math.min(MAX_MASS_MULT, s.massMult + delta * 0.0005f));
    }

    public void addMass(Object token, float delta) {
        SingularityData s = manual.get(token);
        if (s == null) return;
        s.massCache += Math.max(0f, delta);
        s.massMult = Math.max(1f, Math.min(MAX_MASS_MULT, s.massMult + delta * 0.0005f));
    }

    public float getEventHorizonRadiusWorld(DamagingProjectileAPI proj) {
        SingularityData s = active.get(proj);
        if (s == null) return DEFAULT_EVENT_HORIZON_RADIUS;
        ViewportAPI vp = Global.getCombatEngine().getViewport();
        float pixelsPerWorldUnit = screenTexW / Math.max(1f, vp.getVisibleWidth());
        float rsPx = Math.max(2f, s.smoothedRsPx);
        return rsPx / Math.max(1e-3f, pixelsPerWorldUnit);
    }

    public Vector2f getCenterWorld(DamagingProjectileAPI proj) {
        SingularityData s = active.get(proj);
        if (s == null) return null;
        if (s.freezeCenter) return new Vector2f(s.frozenCenter);
        return s.projectile != null ? new Vector2f(s.projectile.getLocation()) : null;
    }

    public boolean isTracked(DamagingProjectileAPI proj) {
        return active.containsKey(proj);
    }

    public float getEventHorizonRadiusWorld(Object token) {
        SingularityData s = manual.get(token);
        if (s == null) return DEFAULT_EVENT_HORIZON_RADIUS;
        ViewportAPI vp = Global.getCombatEngine().getViewport();
        float pixelsPerWorldUnit = screenTexW / Math.max(1f, vp.getVisibleWidth());
        float rsPx = Math.max(2f, s.smoothedRsPx);
        return rsPx / Math.max(1e-3f, pixelsPerWorldUnit);
    }

    public void configurePixelRadius(DamagingProjectileAPI proj, float basePx, float growthPxPerSec) {
        SingularityData s = active.get(proj);
        if (s == null) return;
        s.baseRadiusPx = Math.max(1f, basePx);
        s.growthPxPerSec = Math.max(0f, growthPxPerSec);
        s.currentRadiusPx = s.baseRadiusPx;
    }

    public void configurePixelRadius(Object token, float basePx, float growthPxPerSec) {
        SingularityData s = manual.get(token);
        if (s == null) return;
        s.baseRadiusPx = Math.max(1f, basePx);
        s.growthPxPerSec = Math.max(0f, growthPxPerSec);
        s.currentRadiusPx = s.baseRadiusPx;
    }

    public float getMassValue(DamagingProjectileAPI proj) {
        SingularityData s = active.get(proj);
        return s == null ? 0f : Math.max(0f, s.massGrowth);
    }

    // 手动项的查询
    public float getMassValue(Object token) { SingularityData s = manual.get(token); return s==null?0f:Math.max(0f, s.massGrowth); }

    public boolean isCollapsed(DamagingProjectileAPI proj) {
        SingularityData s = active.get(proj);
        return s != null && s.collapsed;
    }

    public boolean isCollapsing(DamagingProjectileAPI proj) {
        SingularityData s = active.get(proj);
        return s != null && s.collapsing;
    }
    
    public boolean isEvaporating(DamagingProjectileAPI proj) {
        SingularityData s = active.get(proj);
        return s != null && s.evaporating;
    }

    public float getCollapseProgress(DamagingProjectileAPI proj) {
        SingularityData s = active.get(proj);
        return s == null ? 0f : s.collapseProgress;
    }

    // 手动项的查询
    public boolean isCollapsed(Object token) { SingularityData s = manual.get(token); return s!=null && s.collapsed; }
    public boolean isCollapsing(Object token) { SingularityData s = manual.get(token); return s!=null && s.collapsing; }
    public boolean isEvaporating(Object token) { SingularityData s = manual.get(token); return s!=null && s.evaporating; }
    public float getCollapseProgress(Object token) { SingularityData s = manual.get(token); return s==null?0f:s.collapseProgress; }
    public void stopTracking(Object token) { SingularityData s = manual.remove(token); if (s!=null && s.texId!=0) try{GL11.glDeleteTextures(s.texId);}catch(Exception ignore){} }
    
    /**
     * 记录受到黑洞影响的舰船（供外部调用，如引力计算代码）
     */
    public void recordAffectedShip(DamagingProjectileAPI proj, ShipAPI ship) {
        if (proj == null || ship == null) return;
        SingularityData s = active.get(proj);
        if (s != null) {
            s.affectedShips.add(ship);
        }
    }
    
    /**
     * 黑洞结束时对所有受影响的舰船应用渐进式速度阻尼，避免舰船被抛出战场
     */
    private void applyVelocityDampingToAffectedShips(SingularityData s) {
        if (s == null || s.affectedShips.isEmpty()) return;
        
        int dampedCount = 0;
        for (ShipAPI ship : s.affectedShips) {
            if (ship == null || !ship.isAlive() || ship.isHulk()) continue;
            
            Vector2f vel = ship.getVelocity();
            float currentSpeed = vel.length();
            float maxSpeed = ship.getMaxSpeed();
            
            // 只对超过最大速度的舰船应用阻尼
            if (currentSpeed > maxSpeed * 1.1f) {  // 留10%容差，避免不必要的监听器
                // 添加或更新速度衰减监听器
                boolean hasListener = false;
                for (Object listener : ship.getListeners(VelocityDampingListener.class)) {
                    // 已有监听器，刷新其状态
                    if (listener instanceof VelocityDampingListener) {
                        ((VelocityDampingListener) listener).refresh();
                        hasListener = true;
                        break;
                    }
                }
                
                if (!hasListener) {
                    // 添加新的速度衰减监听器
                    ship.addListener(new VelocityDampingListener(ship));
                    dampedCount++;
                }
            }
        }
        
        if (dampedCount > 0) {
        }
        
        s.affectedShips.clear();
    }
    
    /**
     * 渐进式速度衰减监听器：让舰船速度平滑地回到其最大速度
     */
    private static class VelocityDampingListener implements AdvanceableListener {
        private final ShipAPI ship;
        private float duration;
        private static final float TOTAL_DURATION = 3.0f;  // 衰减持续时间（秒）
        private static final float DAMPING_RATE = 0.85f;   // 每秒保留85%的超速部分
        
        public VelocityDampingListener(ShipAPI ship) {
            this.ship = ship;
            this.duration = TOTAL_DURATION;
        }
        
        public void refresh() {
            // 刷新持续时间
            this.duration = TOTAL_DURATION;
        }
        
        @Override
        public void advance(float amount) {
            if (ship == null || !ship.isAlive() || ship.isHulk()) {
                ship.removeListener(this);
                return;
            }
            
            duration -= amount;
            
            Vector2f vel = ship.getVelocity();
            float currentSpeed = vel.length();
            float maxSpeed = ship.getMaxSpeed();
            
            // 计算超出的速度
            float excessSpeed = currentSpeed - maxSpeed;
            
            if (excessSpeed > 1f) {  // 超出1单位以上才继续衰减
                // 渐进式衰减：每帧按比例减少超出部分
                float dampingFactor = (float)Math.pow(DAMPING_RATE, amount);
                float newExcessSpeed = excessSpeed * dampingFactor;
                float targetSpeed = maxSpeed + newExcessSpeed;
                
                // 应用新速度
                if (currentSpeed > 1e-3f) {
                    float scale = targetSpeed / currentSpeed;
                    vel.scale(scale);
                }
                
                // 超时保护：防止阻尼持续过久
                if (duration <= 0f) {
                    ship.removeListener(this);
                }
            } else {
                // 速度已回到正常范围，立即移除监听器
                ship.removeListener(this);
            }
        }
    }
}
