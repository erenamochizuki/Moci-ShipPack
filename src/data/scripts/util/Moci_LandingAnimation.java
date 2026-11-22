package data.scripts.util;

import java.awt.Color;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.apache.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.graphics.SpriteAPI;

/**
 * 原版着陆 / 起飞动画（ShipAPI.beginLandingAnimation / setAnimatedLaunch / abortLanding）的可控复刻。
 *
 * <h3>原版做了什么（0.98a com.fs.starfarer.combat.entities.Ship）</h3>
 * 原版只有一个计时器 sinceLaunch 和三个标记 isAnimatedLaunch / isLanding / isLiftingOffOrLanding：
 * <ul>
 * <li>setAnimatedLaunch()：sinceLaunch = 0，之后每帧 += dt（起飞）。</li>
 * <li>beginLandingAnimation(carrier)：收盾、关闭自动开火的武器组，sinceLaunch = 3，之后每帧 -= dt，减到 0 即 isFinishedLanding()。</li>
 * <li>abortLanding()：保留当前 sinceLaunch，改为起飞方向。母舰为空或死亡时原版每帧自动调用它。</li>
 * <li>sinceLaunch <= 1 时：getCollisionClass() 强制返回 NONE，giveCommand() 直接丢弃指令；
 *     起飞方向还会在 Ship.advance 里清空指令并提前 return（武器、引擎、幅能、战术系统这一帧都不推进）。</li>
 * <li>渲染：sinceLaunch < 1 时整艘船 glScalef(0.5 + 0.5 * t)，透明度 = min(1, 2 * t)；
 *     sinceLaunch < 4 时画阴影：用自身贴图、颜色 (0,0,0,100)，向屏幕下方偏移 min(75 * t, 112.5) 像素，
 *     并用模板缓冲裁剪到母舰贴图的不透明区域；t 在 3~4 之间阴影淡出。</li>
 * </ul>
 * 所以原版着陆固定 3 秒（2 秒悬停 + 1 秒下沉），起飞固定 1 秒上升 + 3 秒阴影，全部写死且外部可以随时打断。
 *
 * <h3>这个复刻版</h3>
 * 自己维护同一条时钟（clock，含义等同原版 sinceLaunch：0 = 完全沉入，1 = 恢复原大小，3 = 着陆起点，4 = 起飞结束），
 * 但各段时长、缩放下限、透明度曲线、阴影颜色 / 方向 / 距离 / 裁剪、收盾 / 停火 / 锁指令 / 碰撞等都可以配置，
 * 并支持暂停、变速、拖动进度、着陆中途反向起飞、入库后保持、回调。
 * <ul>
 * <li>状态完全由本类持有，不使用原版的 isLanding 标记，所以原版 AI（FighterAI.abortLanding）和 Ship.advance 的
 *     「母舰死亡自动中止」都打断不了它。相应地 ship.isLanding() / isFinishedLanding() 在这里始终为 false，
 *     请改用本类的 isLanding() / isLanded() / isLiftingOff()。</li>
 * <li>公开 API 里没有缩放整艘船的办法（船体、武器、引擎光焰是分开画的，只能缩它们共用的矩阵）。
 *     这里的做法与 Moci_Util.ShipCopyBridge 画残影相同：需要缩放的那几帧把本体透明度压到 0，
 *     再由自己的渲染插件套上 glScalef 调用舰船的三参数 render（唯一用到的非 API 方法，经 MethodHandle 调用）。
 *     完全不碰原版的 sinceLaunch，所以没有原版缩放段「Ship.advance 提前 return」的副作用，缩放比例也不受 0.5 的限制，
 *     带模块的船可以绕本体中心整体收拢。渲染方法对不上时自动停用缩放，只做淡入淡出。
 *     两种做法的比较见 docs/Moci_Util_着陆起飞动画使用说明.md。</li>
 * <li>透明度使用 setExtraAlphaMult2()，开始时记录原值、结束时还原。动画期间不要让别的逻辑同时写这个值。</li>
 * <li>阴影不用原版的（原版阴影的颜色、方向都改不了），由同一个渲染插件按同样的模板裁剪方式自绘。</li>
 * </ul>
 *
 * <h3>用法</h3>
 * <pre>
 * // 原版默认参数着陆，落定后保持，直到手动起飞
 * Moci_LandingAnimation anim = Moci_LandingAnimation.land(ship, carrier, null);
 * if (anim.isLanded()) anim.beginLaunch(carrier, null);
 *
 * // 自定义
 * Moci_LandingAnimation.land(ship, carrier, new Moci_LandingAnimation.Params()
 *         .setLandingTimes(0.6f, 0.8f)          // 悬停 0.6 秒 + 下沉 0.8 秒
 *         .setMinScale(0.3f)                    // 缩到 30%（原版 50%）
 *         .setShadow(new Color(0, 0, 0, 140), 160f, 250f)
 *         .setCallback(new Moci_LandingAnimation.Callback() {
 *             public void onLanded(Moci_LandingAnimation a) { ... }
 *         }));
 * </pre>
 * 需要自定义缩放 / 透明度 / 阴影曲线时继承 Params 覆写 computeScale / computeAlpha / computeShadowAlpha / computeShadowOffset。
 */
public final class Moci_LandingAnimation implements AdvanceableListener {
    public static final String DATA_KEY = "Moci_LandingAnimation";

    /** 时钟：完全沉入（缩到最小、完全透明） */
    public static final float CLOCK_SUNK = 0f;
    /** 时钟：恢复原大小。小于等于此值时原版视为「正在起降」 */
    public static final float CLOCK_SURFACE = 1f;
    /** 时钟：着陆起点，也是起飞后阴影开始淡出的时刻 */
    public static final float CLOCK_SHADOW_FADE = 3f;
    /** 时钟：起飞动画结束 */
    public static final float CLOCK_END = 4f;

    /** 原版沉到底时的缩放 */
    public static final float VANILLA_MIN_SCALE = 0.5f;

    private static final float MIN_RENDER_SCALE = 0.01f;
    private static final float MAX_RENDER_SCALE = 4f;
    private static final ShipCommand[] ALL_COMMANDS = ShipCommand.values();

    private static final Logger LOG = Global.getLogger(Moci_LandingAnimation.class);
    private static final String LOG_PREFIX = "[Moci_LandingAnim] ";
    private static final int MAX_ERROR_REPORTS = 5;
    private static int errorReports = 0;
    /** 舰船的三参数 render 是否可用；对不上当前游戏版本时置 false，之后所有动画只做淡入淡出 */
    private static boolean scaleRenderAvailable = true;

    public enum Phase {
        /** 没有动画在进行 */
        IDLE,
        /** 着陆中：时钟从 3 走向 0 */
        LANDING,
        /** 已落定：保持不可见，等待 beginLaunch() */
        LANDED,
        /** 起飞中（包括着陆被中止后的反向）：时钟走向 4 */
        LAUNCHING
    }

    public enum AbortReason {
        /** 调用了 abortLanding() */
        REQUESTED,
        /** 母舰死亡、变成残骸或离场 */
        CARRIER_LOST
    }

    /** 回调，全部是可选的 */
    public interface Callback {
        /** 着陆完成（时钟到 0）。可以在这里把舰船移出战场，或直接调用 beginLaunch() */
        default void onLanded(Moci_LandingAnimation anim) {
        }

        /** 着陆被中止并转为起飞 */
        default void onLandingAborted(Moci_LandingAnimation anim, AbortReason reason) {
        }

        /** 起飞时舰船恢复原大小（时钟到 1），此后可以操控、恢复碰撞；阴影还会停留一段时间 */
        default void onLiftoffComplete(Moci_LandingAnimation anim) {
        }

        /** 动画彻底结束，舰船状态已还原。cancelled = true 表示是 cancel() 或舰船死亡导致的 */
        default void onFinished(Moci_LandingAnimation anim, boolean cancelled) {
        }
    }

    /**
     * 动画参数，默认值全部等同原版。字段可以直接改，也可以用 setXxx 链式设置；动画进行中修改会实时生效。
     * 一个 Params 可以给多艘船共用（只读配置，不含每艘船的状态）。
     */
    public static class Params {
        /** 着陆：下沉之前保持原大小的时间（时钟 3 → 1）。原版 2 秒 */
        public float landingHoverTime = 2f;
        /** 着陆：缩小并淡出的时间（时钟 1 → 0）。原版 1 秒 */
        public float landingSinkTime = 1f;
        /** 起飞：放大并淡入的时间（时钟 0 → 1）。原版 1 秒 */
        public float launchRiseTime = 1f;
        /** 起飞：恢复原大小后阴影继续停留的时间（时钟 1 → 3）。原版 2 秒 */
        public float launchShadowHoldTime = 2f;
        /** 起飞：阴影淡出的时间（时钟 3 → 4）。原版 1 秒 */
        public float launchShadowFadeTime = 1f;
        /** 落定后自动起飞前的等待时间（秒）；小于 0 表示一直保持，直到调用 beginLaunch() */
        public float landedHoldTime = -1f;

        /** 沉到底时的缩放，0 ~ 1，1 表示不缩放。原版 0.5 */
        public float minScale = VANILLA_MIN_SCALE;
        /** 下沉进度（0 = 沉到底，1 = 原大小）达到多少时完全不透明。原版 0.5，即下沉过半才开始淡出 */
        public float alphaFullAt = 0.5f;
        /** 是否缩放。关闭后只有淡入淡出，全程由引擎正常渲染，不使用任何非 API 方法 */
        public boolean scaleEnabled = true;
        /** 是否同时作用于子模块 */
        public boolean includeModules = true;
        /**
         * 子模块的缩放中心：true = 绕本体中心，整艘船作为一个整体收拢；
         * false = 各自绕自己的中心（原版对每个模块分别调用动画时的效果，模块之间会拉开缝隙）
         */
        public boolean scaleModulesAroundRoot = true;
        /** true = 按全局战斗时间计时；false = 按舰船自身时间计时（原版行为，受时间流速加成影响） */
        public boolean ignoreShipTimeMult = false;

        /** 是否绘制阴影 */
        public boolean shadowEnabled = true;
        /** 阴影颜色，alpha 通道即阴影浓度。原版 (0,0,0,100) */
        public Color shadowColor = new Color(0, 0, 0, 100);
        /** 阴影偏移随时钟增长的速度（像素 / 时钟单位）。原版 75 */
        public float shadowOffsetPerClock = 75f;
        /** 阴影最大偏移（像素）。原版 112.5 */
        public float shadowMaxOffset = 112.5f;
        /** 阴影偏移方向（世界角度，0 = +X）。原版固定朝屏幕下方，即 270 */
        public float shadowAngle = 270f;
        /** 是否把阴影裁剪到母舰贴图上（原版行为）。开启时没有母舰就不画阴影 */
        public boolean shadowClipToCarrier = true;
        /** 裁剪范围是否包含母舰的子模块贴图（原版只用母舰本体） */
        public boolean shadowClipIncludesModules = false;
        /** 着陆开始时阴影的淡入时间（秒）。原版 0，阴影直接出现 */
        public float landingShadowFadeIn = 0f;
        /** 阴影所在渲染层；null = 自动选择（舰船所在层的下一层，且不低于母舰所在层） */
        public CombatEngineLayers shadowLayer = null;

        /** 开始着陆时关闭护盾（原版行为，只在开始时做一次） */
        public boolean dropShieldOnLanding = true;
        /** 开始着陆时关闭所有自动开火的武器组（原版行为，只在开始时做一次） */
        public boolean turnOffAutofireOnLanding = true;
        /** 着陆和落定期间每帧禁止开火。原版由 AI 判断 isLanding() 来停火，这里 isLanding() 不为真，所以默认开启 */
        public boolean holdFireWhileLanding = true;
        /** 时钟 <= 1（缩放段和落定期间）屏蔽所有操控指令（原版行为） */
        public boolean lockCommandsWhileSunk = true;
        /** 着陆悬停段也屏蔽操控指令。原版不屏蔽，AI 这段时间还在对准降落点 */
        public boolean lockCommandsDuringHover = false;
        /** 时钟 <= 1 时把碰撞设为 NONE，之后还原（原版行为）。碰撞由别的系统统一管理时请关闭 */
        public boolean manageCollision = true;
        /** 母舰死亡 / 变残骸 / 离场时自动中止着陆并反向起飞（原版行为） */
        public boolean abortIfCarrierLost = true;

        public Callback callback = null;

        /** 着陆时长：悬停（原大小，只有阴影）+ 下沉（缩小淡出）。原版 2 + 1 */
        public Params setLandingTimes(float hoverTime, float sinkTime) {
            this.landingHoverTime = hoverTime;
            this.landingSinkTime = sinkTime;
            return this;
        }

        /** 起飞时长：上升（放大淡入）+ 阴影停留 + 阴影淡出。原版 1 + 2 + 1 */
        public Params setLaunchTimes(float riseTime, float shadowHoldTime, float shadowFadeTime) {
            this.launchRiseTime = riseTime;
            this.launchShadowHoldTime = shadowHoldTime;
            this.launchShadowFadeTime = shadowFadeTime;
            return this;
        }

        public Params setLandedHoldTime(float seconds) {
            this.landedHoldTime = seconds;
            return this;
        }

        public Params setMinScale(float minScale) {
            this.minScale = minScale;
            return this;
        }

        public Params setAlphaFullAt(float sinkLevel) {
            this.alphaFullAt = sinkLevel;
            return this;
        }

        public Params setScaleEnabled(boolean scaleEnabled) {
            this.scaleEnabled = scaleEnabled;
            return this;
        }

        public Params setIncludeModules(boolean includeModules) {
            this.includeModules = includeModules;
            return this;
        }

        public Params setScaleModulesAroundRoot(boolean scaleModulesAroundRoot) {
            this.scaleModulesAroundRoot = scaleModulesAroundRoot;
            return this;
        }

        public Params setIgnoreShipTimeMult(boolean ignoreShipTimeMult) {
            this.ignoreShipTimeMult = ignoreShipTimeMult;
            return this;
        }

        public Params setShadowEnabled(boolean shadowEnabled) {
            this.shadowEnabled = shadowEnabled;
            return this;
        }

        /** 阴影颜色（alpha 即浓度）、最大偏移（像素）、偏移方向（世界角度，270 = 屏幕下方） */
        public Params setShadow(Color color, float maxOffset, float angle) {
            if (color != null) this.shadowColor = color;
            this.shadowMaxOffset = maxOffset;
            this.shadowAngle = angle;
            return this;
        }

        public Params setShadowOffsetPerClock(float pixelsPerClock) {
            this.shadowOffsetPerClock = pixelsPerClock;
            return this;
        }

        public Params setShadowClip(boolean clipToCarrier, boolean includeCarrierModules) {
            this.shadowClipToCarrier = clipToCarrier;
            this.shadowClipIncludesModules = includeCarrierModules;
            return this;
        }

        public Params setLandingShadowFadeIn(float seconds) {
            this.landingShadowFadeIn = seconds;
            return this;
        }

        public Params setShadowLayer(CombatEngineLayers layer) {
            this.shadowLayer = layer;
            return this;
        }

        /** 开始着陆时是否收盾、是否关闭自动开火武器组、着陆期间是否持续禁止开火 */
        public Params setLandingWeaponRules(boolean dropShield, boolean turnOffAutofire, boolean holdFire) {
            this.dropShieldOnLanding = dropShield;
            this.turnOffAutofireOnLanding = turnOffAutofire;
            this.holdFireWhileLanding = holdFire;
            return this;
        }

        /** 缩放段是否屏蔽指令（原版 true）、悬停段是否也屏蔽（原版 false） */
        public Params setCommandLock(boolean whileSunk, boolean duringHover) {
            this.lockCommandsWhileSunk = whileSunk;
            this.lockCommandsDuringHover = duringHover;
            return this;
        }

        public Params setManageCollision(boolean manageCollision) {
            this.manageCollision = manageCollision;
            return this;
        }

        public Params setAbortIfCarrierLost(boolean abortIfCarrierLost) {
            this.abortIfCarrierLost = abortIfCarrierLost;
            return this;
        }

        public Params setCallback(Callback callback) {
            this.callback = callback;
            return this;
        }

        /**
         * 下沉进度 → 缩放。sinkLevel：0 = 沉到底，1 = 原大小。原版是线性的 0.5 + 0.5 * sinkLevel。
         * 返回值可以大于 1（例如做「从高处落下」），实际渲染时限制在 0.01 ~ 4。
         */
        public float computeScale(float sinkLevel) {
            float min = clamp(minScale, 0f, 1f);
            return min + (1f - min) * sinkLevel;
        }

        /** 下沉进度 → 舰船透明度。原版是 min(1, sinkLevel / 0.5) */
        public float computeAlpha(float sinkLevel) {
            if (!(alphaFullAt > 0f)) return sinkLevel > 0f ? 1f : 0f;
            return clamp(sinkLevel / alphaFullAt, 0f, 1f);
        }

        /**
         * 时钟 → 阴影透明度（还会再乘 shadowColor 的 alpha）。
         * 原版：缩放段与舰船透明度相同，时钟 1~3 为 1，3~4 线性淡出。
         */
        public float computeShadowAlpha(float clock, float shipAlpha) {
            if (clock < CLOCK_SURFACE) return shipAlpha;
            if (clock < CLOCK_SHADOW_FADE) return 1f;
            return clamp(CLOCK_END - clock, 0f, 1f);
        }

        /** 时钟 → 阴影偏移（像素，缩放段还会再乘当前缩放）。原版 min(75 * clock, 112.5) */
        public float computeShadowOffset(float clock) {
            return Math.max(0f, Math.min(clock * shadowOffsetPerClock, shadowMaxOffset));
        }
    }

    /** 参与动画的一个部件（本体或子模块）及其被接管前的状态 */
    private static final class Part {
        final ShipAPI ship;
        final float baseAlpha;
        /** 被本类改成 NONE 之前的碰撞类型；只在真正改写的那一刻记录 */
        CollisionClass savedCollision;
        boolean collisionOverridden;
        /** 部件在动画途中被击毁，状态已经交还，之后不再处理 */
        boolean released;

        Part(ShipAPI ship) {
            this.ship = ship;
            baseAlpha = ship.getExtraAlphaMult2();
        }
    }

    private final ShipAPI ship;
    private final List<Part> parts = new ArrayList<Part>();
    private Params params = new Params();
    private ShipAPI carrier;
    private Phase phase = Phase.IDLE;
    private float clock = CLOCK_END;
    private float phaseElapsed;
    private float speedMult = 1f;
    private boolean paused;
    private boolean liftoffNotified;
    private boolean attached;
    private float currentScale = 1f;
    private float currentAlpha = 1f;
    /** 本帧是否接管了舰船的渲染：本体透明度被压到 0，由 renderPlugin 缩放后重画 */
    private boolean takeover;
    private RenderPlugin renderPlugin;

    private Moci_LandingAnimation(ShipAPI ship) {
        this.ship = ship;
    }

    // ==================== 静态入口 ====================

    /** 取这艘船已有的动画对象；从未播放过时返回 null */
    public static Moci_LandingAnimation get(ShipAPI ship) {
        if (ship == null) return null;
        Object existing = ship.getCustomData().get(DATA_KEY);
        return existing instanceof Moci_LandingAnimation ? (Moci_LandingAnimation) existing : null;
    }

    /** 取这艘船的动画对象，没有就创建（创建本身不产生任何效果） */
    public static Moci_LandingAnimation getOrCreate(ShipAPI ship) {
        if (ship == null) return null;
        Moci_LandingAnimation anim = get(ship);
        if (anim == null) {
            anim = new Moci_LandingAnimation(ship);
            ship.setCustomData(DATA_KEY, anim);
        }
        return anim;
    }

    /**
     * 开始着陆。
     *
     * @param carrier 母舰，决定阴影裁剪范围和「母舰丢失自动中止」；可以为 null
     * @param params  参数，null = 沿用上一次的（首次为原版默认值）
     * @return 动画对象；ship 为 null 时返回 null
     */
    public static Moci_LandingAnimation land(ShipAPI ship, ShipAPI carrier, Params params) {
        Moci_LandingAnimation anim = getOrCreate(ship);
        if (anim != null) anim.beginLanding(carrier, params);
        return anim;
    }

    /** 开始起飞，参数含义同 land() */
    public static Moci_LandingAnimation launch(ShipAPI ship, ShipAPI carrier, Params params) {
        Moci_LandingAnimation anim = getOrCreate(ship);
        if (anim != null) anim.beginLaunch(carrier, params);
        return anim;
    }

    /** 这艘船是否有动画在进行（着陆、落定保持、起飞任一阶段） */
    public static boolean isAnimating(ShipAPI ship) {
        Moci_LandingAnimation anim = get(ship);
        return anim != null && anim.isActive();
    }

    // ==================== 控制 ====================

    /**
     * 开始着陆，对应原版 beginLandingAnimation()。
     * 已经在着陆或已落定时只更新母舰和参数，不会重新计时；正在起飞时从当前进度反向着陆。
     */
    public void beginLanding(ShipAPI carrier, Params params) {
        if (!isOperational(ship)) return;
        boolean wasActive = phase != Phase.IDLE;
        configure(carrier, params, wasActive);
        if (phase == Phase.LANDING || phase == Phase.LANDED) {
            ensureRenderPlugin();
            apply();
            return;
        }
        clock = wasActive ? Math.min(clock, CLOCK_SHADOW_FADE) : CLOCK_SHADOW_FADE;
        phase = Phase.LANDING;
        phaseElapsed = 0f;
        liftoffNotified = false;
        ensureRenderPlugin();
        for (Part part : parts) {
            if (!isOperational(part.ship)) continue;
            if (this.params.dropShieldOnLanding) {
                ShieldAPI shield = part.ship.getShield();
                if (shield != null && shield.isOn()) shield.toggleOff();
            }
            if (this.params.turnOffAutofireOnLanding) {
                for (WeaponGroupAPI group : part.ship.getWeaponGroupsCopy()) {
                    if (group.isAutofiring()) group.toggleOff();
                }
            }
        }
        apply();
    }

    /**
     * 开始起飞，对应原版 setAnimatedLaunch()。
     * 着陆中调用时从当前进度反向（不触发 onLandingAborted，需要回调请用 abortLanding()）；已落定时从时钟 0 开始。
     */
    public void beginLaunch(ShipAPI carrier, Params params) {
        if (!isOperational(ship)) return;
        boolean wasActive = phase != Phase.IDLE;
        configure(carrier, params, wasActive);
        if (phase != Phase.LAUNCHING) {
            if (!wasActive) clock = CLOCK_SUNK;
            startLaunchPhase();
        }
        ensureRenderPlugin();
        apply();
    }

    /**
     * 中止着陆并从当前进度反向起飞，对应原版 abortLanding()。
     *
     * @return 是否确实中止了一次着陆（不在着陆 / 落定阶段时返回 false）
     */
    public boolean abortLanding() {
        return abortLanding(AbortReason.REQUESTED);
    }

    /** 立即结束动画并还原舰船状态（透明度、碰撞、缩放），不播放任何过渡 */
    public void cancel() {
        finish(true);
    }

    /** 直接跳到落定状态（会触发 onLanded）；不在着陆阶段时无效 */
    public void skipToLanded() {
        if (phase != Phase.LANDING) return;
        clock = CLOCK_SUNK;
        enterLanded();
        if (phase != Phase.IDLE) apply();
    }

    /** 暂停 / 继续计时（画面保持在当前进度） */
    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    /** 播放速度倍率，1 = 正常；可在动画进行中随时调整 */
    public void setSpeedMult(float speedMult) {
        this.speedMult = Float.isFinite(speedMult) && speedMult > 0f ? speedMult : 0f;
    }

    /** 直接设置时钟（0 ~ 4），用于手动拖动进度；不改变当前阶段，也不触发回调 */
    public void setClock(float clock) {
        if (phase == Phase.IDLE || !Float.isFinite(clock)) return;
        this.clock = clamp(clock, CLOCK_SUNK, CLOCK_END);
        apply();
    }

    // ==================== 查询 ====================

    public ShipAPI getShip() {
        return ship;
    }

    public ShipAPI getCarrier() {
        return carrier;
    }

    public Params getParams() {
        return params;
    }

    public Phase getPhase() {
        return phase;
    }

    public boolean isActive() {
        return phase != Phase.IDLE;
    }

    /** 着陆中或已落定，对应原版 isLanding() */
    public boolean isLanding() {
        return phase == Phase.LANDING || phase == Phase.LANDED;
    }

    /** 已落定，对应原版 isFinishedLanding() */
    public boolean isLanded() {
        return phase == Phase.LANDED;
    }

    /** 起飞中且尚未恢复原大小，对应原版 isLiftingOff() */
    public boolean isLiftingOff() {
        return phase == Phase.LAUNCHING && clock <= CLOCK_SURFACE;
    }

    /** 时钟 <= 1：处于缩放段或落定状态，此时无碰撞、不可操控（原版的 isLiftingOffOrLanding） */
    public boolean isSunk() {
        return phase != Phase.IDLE && clock <= CLOCK_SURFACE;
    }

    public boolean isPaused() {
        return paused;
    }

    /** 当前时钟，含义等同原版 sinceLaunch：0 = 沉到底，1 = 原大小，3 = 着陆起点，4 = 结束 */
    public float getClock() {
        return clock;
    }

    /** 当前下沉进度：0 = 沉到底，1 = 原大小 */
    public float getSinkLevel() {
        return clamp(clock, CLOCK_SUNK, CLOCK_SURFACE);
    }

    public float getScale() {
        return currentScale;
    }

    public float getAlpha() {
        return currentAlpha;
    }

    /**
     * 当前阶段按正常速度还需要多少秒：着陆 = 到落定；起飞 = 到恢复原大小（已恢复则为到动画结束）；
     * 落定 = 到自动起飞（一直保持时返回 -1）；空闲 = 0。
     */
    public float getTimeRemaining() {
        switch (phase) {
            case LANDING:
                return clockTime(clock, CLOCK_SUNK, false);
            case LANDED:
                return params.landedHoldTime >= 0f ? Math.max(0f, params.landedHoldTime - phaseElapsed) : -1f;
            case LAUNCHING:
                return clock < CLOCK_SURFACE ? clockTime(clock, CLOCK_SURFACE, true)
                        : clockTime(clock, launchEndClock(), true);
            default:
                return 0f;
        }
    }

    // ==================== 每帧推进 ====================

    /** 由 Ship.advance 开头调用（早于本帧的指令处理和渲染），amount 为舰船自身时间 */
    @Override
    public void advance(float amount) {
        if (phase == Phase.IDLE) {
            detach();
            return;
        }
        if (!isOperational(ship)) {
            finish(true);
            return;
        }
        try {
            float dt = paused ? 0f : amount * speedMult;
            if (params.ignoreShipTimeMult && ship.getMutableStats() != null) {
                float timeMult = ship.getMutableStats().getTimeMult().getModifiedValue();
                if (timeMult > 0f) dt /= timeMult;
            }
            if (dt > 0f && Float.isFinite(dt)) step(dt);
            if (phase != Phase.IDLE) apply();
        } catch (RuntimeException e) {
            reportError("动画推进异常，已取消该动画 ship=" + ship.getId(), e);
            finish(true);
        }
    }

    private void step(float dt) {
        if (phase == Phase.LANDING || phase == Phase.LANDED) {
            if (params.abortIfCarrierLost && isCarrierLost()) abortLanding(AbortReason.CARRIER_LOST);
        }
        switch (phase) {
            case LANDING:
                phaseElapsed += dt;
                clock = stepClock(clock, dt, false, CLOCK_SUNK);
                if (clock <= CLOCK_SUNK) enterLanded();
                break;
            case LANDED:
                phaseElapsed += dt;
                if (params.landedHoldTime >= 0f && phaseElapsed >= params.landedHoldTime) startLaunchPhase();
                break;
            case LAUNCHING:
                phaseElapsed += dt;
                float end = launchEndClock();
                clock = stepClock(clock, dt, true, end);
                if (!liftoffNotified && clock >= CLOCK_SURFACE) {
                    liftoffNotified = true;
                    Callback callback = params.callback;
                    if (callback != null) callback.onLiftoffComplete(this);
                }
                if (phase == Phase.LAUNCHING && clock >= end) finish(false);
                break;
            default:
                break;
        }
    }

    /**
     * 沿时钟前进 dt 秒。时钟分三段（0~1、1~3、3~4），每段按配置的时长换算速度，
     * 所以改时长只改变快慢，各段的画面含义不变。时长为 0 的段直接跳过。
     */
    private float stepClock(float value, float dt, boolean up, float limit) {
        for (int guard = 0; guard < 4 && dt > 0f; guard++) {
            if (up ? value >= limit : value <= limit) break;
            int zone = zoneOf(value, up);
            float edge = up ? Math.min(zoneHigh(zone), limit) : Math.max(zoneLow(zone), limit);
            float duration = zoneDuration(zone, up);
            float rate = duration > 0f ? (zoneHigh(zone) - zoneLow(zone)) / duration : Float.POSITIVE_INFINITY;
            float needed = Math.abs(edge - value) / rate;
            if (needed > dt) {
                return value + (up ? rate : -rate) * dt;
            }
            value = edge;
            dt -= needed;
        }
        return value;
    }

    private float clockTime(float from, float to, boolean up) {
        float total = 0f;
        float value = from;
        for (int guard = 0; guard < 4; guard++) {
            if (up ? value >= to : value <= to) break;
            int zone = zoneOf(value, up);
            float edge = up ? Math.min(zoneHigh(zone), to) : Math.max(zoneLow(zone), to);
            total += Math.abs(edge - value) / (zoneHigh(zone) - zoneLow(zone)) * zoneDuration(zone, up);
            value = edge;
        }
        return total;
    }

    private static int zoneOf(float value, boolean up) {
        if (up) return value < CLOCK_SURFACE ? 0 : value < CLOCK_SHADOW_FADE ? 1 : 2;
        return value > CLOCK_SHADOW_FADE ? 2 : value > CLOCK_SURFACE ? 1 : 0;
    }

    private static float zoneLow(int zone) {
        return zone == 0 ? CLOCK_SUNK : zone == 1 ? CLOCK_SURFACE : CLOCK_SHADOW_FADE;
    }

    private static float zoneHigh(int zone) {
        return zone == 0 ? CLOCK_SURFACE : zone == 1 ? CLOCK_SHADOW_FADE : CLOCK_END;
    }

    private float zoneDuration(int zone, boolean up) {
        float duration;
        if (zone == 0) duration = up ? params.launchRiseTime : params.landingSinkTime;
        else if (zone == 1) duration = up ? params.launchShadowHoldTime : params.landingHoverTime;
        else duration = params.launchShadowFadeTime;
        return Float.isFinite(duration) && duration > 0f ? duration : 0f;
    }

    /** 起飞动画的终点：有阴影要画就走到 4，否则恢复原大小（时钟 1）即结束 */
    private float launchEndClock() {
        return isShadowWanted() ? CLOCK_END : CLOCK_SURFACE;
    }

    private boolean isShadowWanted() {
        return params.shadowEnabled && (carrier != null || !params.shadowClipToCarrier);
    }

    // ==================== 阶段切换 ====================

    private void configure(ShipAPI carrier, Params params, boolean wasActive) {
        if (params != null) this.params = params;
        this.carrier = carrier;
        if (!wasActive) {
            parts.clear();
            parts.add(new Part(ship));
            if (this.params.includeModules) {
                for (ShipAPI module : ship.getChildModulesCopy()) {
                    if (isOperational(module)) parts.add(new Part(module));
                }
            }
            paused = false;
            speedMult = 1f;
        }
        attach();
    }

    private void enterLanded() {
        clock = CLOCK_SUNK;
        phase = Phase.LANDED;
        phaseElapsed = 0f;
        Callback callback = params.callback;
        if (callback != null) callback.onLanded(this);
    }

    private void startLaunchPhase() {
        phase = Phase.LAUNCHING;
        phaseElapsed = 0f;
        // 即使是在悬停段（还没缩小）反向起飞，也照常通知一次 onLiftoffComplete，调用方不用区分两种情况。
        liftoffNotified = false;
    }

    private boolean abortLanding(AbortReason reason) {
        if (phase != Phase.LANDING && phase != Phase.LANDED) return false;
        startLaunchPhase();
        Callback callback = params.callback;
        if (callback != null) callback.onLandingAborted(this, reason);
        if (phase != Phase.IDLE) apply();
        return true;
    }

    private void finish(boolean cancelled) {
        if (phase == Phase.IDLE) return;
        phase = Phase.IDLE;
        clock = CLOCK_END;
        currentScale = 1f;
        currentAlpha = 1f;
        takeover = false;
        for (Part part : parts) {
            ShipAPI s = part.ship;
            s.setExtraAlphaMult2(part.baseAlpha);
            if (part.collisionOverridden && part.savedCollision != null) s.setCollisionClass(part.savedCollision);
        }
        parts.clear();
        renderPlugin = null;
        detach();
        Callback callback = params.callback;
        if (callback != null) callback.onFinished(this, cancelled);
    }

    private void attach() {
        if (attached && ship.hasListener(this)) return;
        ship.addListener(this);
        attached = true;
    }

    private void detach() {
        if (!attached) return;
        ship.removeListener(this);
        attached = false;
    }

    private boolean isCarrierLost() {
        if (carrier == null) return false;
        if (!carrier.isAlive() || carrier.isHulk()) return true;
        CombatEngineAPI engine = Global.getCombatEngine();
        return engine != null && !engine.isEntityInPlay(carrier);
    }

    // ==================== 把当前进度写到舰船上 ====================

    private void apply() {
        Params p = params;
        float sink = getSinkLevel();
        float alpha = clamp(p.computeAlpha(sink), 0f, 1f);
        float scale = 1f;
        boolean scaled = false;
        if (p.scaleEnabled && alpha > 0f && isScaleRenderReady()) {
            float wanted = clamp(p.computeScale(sink), MIN_RENDER_SCALE, MAX_RENDER_SCALE);
            if (Math.abs(wanted - 1f) > 0.0001f) {
                scale = wanted;
                scaled = true;
            }
        }
        currentScale = scale;
        currentAlpha = alpha;
        takeover = scaled;

        boolean sunk = clock <= CLOCK_SURFACE;
        for (Part part : parts) {
            ShipAPI s = part.ship;
            if (!isOperational(s)) {
                // 动画途中被击毁的部件：把透明度和碰撞交还一次，之后不再管它，免得残骸一直不可见。
                releasePart(part);
                continue;
            }
            // 需要缩放时由 renderPlugin 缩放后重画，引擎自己画的那一份压到完全透明；不需要缩放时就是普通的淡入淡出。
            s.setExtraAlphaMult2(scaled ? 0f : part.baseAlpha * alpha);

            if (p.manageCollision && sunk) {
                CollisionClass current = s.getCollisionClass();
                if (current != null && current != CollisionClass.NONE) {
                    part.savedCollision = current;
                    part.collisionOverridden = true;
                    s.setCollisionClass(CollisionClass.NONE);
                }
            } else if (part.collisionOverridden) {
                s.setCollisionClass(part.savedCollision);
                part.collisionOverridden = false;
            }
        }

        boolean landingSide = phase == Phase.LANDING || phase == Phase.LANDED;
        if (p.holdFireWhileLanding && landingSide) ship.setHoldFireOneFrame(true);
        if ((p.lockCommandsWhileSunk && sunk) || (p.lockCommandsDuringHover && landingSide)) {
            for (ShipCommand command : ALL_COMMANDS) ship.blockCommandForOneFrame(command);
        }
    }

    private static void releasePart(Part part) {
        if (part.released) return;
        part.released = true;
        part.ship.setExtraAlphaMult2(part.baseAlpha);
        if (part.collisionOverridden && part.savedCollision != null) {
            part.ship.setCollisionClass(part.savedCollision);
            part.collisionOverridden = false;
        }
    }

    // ==================== 缩放渲染 ====================

    private boolean isScaleRenderReady() {
        return scaleRenderAvailable && renderPlugin != null && !renderPlugin.dead && renderPlugin.coversShipLayers;
    }

    /**
     * 把被接管的部件缩放后画出来。做法与引擎画传送残影、Moci_Util.ShipCopyBridge 画多残影相同：
     * 临时改掉舰船的显示状态，调用它自己的三参数 render，再改回去。这里改的是透明度，并在外面套一层缩放矩阵。
     * Ship.render 开头自己会 glPushMatrix 并平移到舰船位置，所以外层「平移到缩放中心 → 缩放 → 平移回来」
     * 就等于让整艘船（船体、武器、引擎光焰、护盾）绕缩放中心一起缩。
     */
    private void renderScaled(CombatEngineLayers layer, ViewportAPI viewport) throws Throwable {
        if (!takeover || viewport == null || layer == CombatEngineLayers.FF_INDICATORS_LAYER) return;
        float scale = currentScale;
        float alpha = currentAlpha;
        boolean aroundRoot = params.scaleModulesAroundRoot;
        for (Part part : parts) {
            ShipAPI s = part.ship;
            if (part.released || !isOperational(s)) continue;
            EnumSet<CombatEngineLayers> active = s.getActiveLayers();
            if (active == null || !active.contains(layer)) continue;
            Vector2f pivot = aroundRoot ? ship.getLocation() : s.getLocation();
            float hiddenAlpha = s.getExtraAlphaMult2();
            GL11.glPushMatrix();
            try {
                GL11.glTranslatef(pivot.x, pivot.y, 0f);
                GL11.glScalef(scale, scale, 1f);
                GL11.glTranslatef(-pivot.x, -pivot.y, 0f);
                s.setExtraAlphaMult2(part.baseAlpha * alpha);
                RenderBridge.render(s, layer, viewport);
            } finally {
                s.setExtraAlphaMult2(hiddenAlpha);
                GL11.glPopMatrix();
            }
        }
    }

    /** 渲染方法对不上或渲染出错：停用缩放，立刻把本体的透明度还回去，之后只做淡入淡出 */
    private void onScaleRenderFailed(Throwable error) {
        scaleRenderAvailable = false;
        LOG.error(LOG_PREFIX + "缩放渲染不可用（舰船渲染方法与当前游戏版本不匹配），之后只做淡入淡出", error);
        takeover = false;
        currentScale = 1f;
        for (Part part : parts) {
            if (part.released || !isOperational(part.ship)) continue;
            part.ship.setExtraAlphaMult2(part.baseAlpha * currentAlpha);
        }
    }

    /**
     * 唯一接触非 API 方法的地方：com.fs.starfarer.combat.entities.Ship 的三参数 render(layer, viewport, boolean)（0.98a）。
     * 引擎平时调用的两参数 render 内部就是转调它，第三个参数传 true。
     * 与 Moci_Util 里其他桥接一样用 MethodHandle 按名字查找，源码不直接引用 Ship：
     * 它的签名里带有名为 new 之类的包，IDE 的编译器解析不了。对不上时查找抛异常，由调用方捕获并停用缩放。
     */
    private static final class RenderBridge {
        private static Class<?> shipClass = null;
        private static Class<?> viewportClass = null;
        private static MethodHandle renderShip = null;

        static void prepare(ShipAPI ship, ViewportAPI viewport) throws Throwable {
            if (shipClass == ship.getClass() && viewportClass == viewport.getClass()) return;
            renderShip = MethodHandles.publicLookup().findVirtual(ship.getClass(), "render",
                    MethodType.methodType(void.class, CombatEngineLayers.class, viewport.getClass(), boolean.class));
            shipClass = ship.getClass();
            viewportClass = viewport.getClass();
        }

        static void render(ShipAPI ship, CombatEngineLayers layer, ViewportAPI viewport) throws Throwable {
            prepare(ship, viewport);
            renderShip.invoke(ship, layer, viewport, true);
        }
    }

    // ==================== 渲染插件 ====================

    /** 按当前参数创建（或沿用）渲染插件：负责画阴影，以及缩放段重画舰船 */
    private void ensureRenderPlugin() {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return;
        if (params.scaleEnabled && scaleRenderAvailable) {
            // 提前查一次渲染方法：对不上就不接管，避免先把本体藏起来才发现画不出来。
            ViewportAPI viewport = engine.getViewport();
            if (viewport != null) {
                try {
                    RenderBridge.prepare(ship, viewport);
                } catch (Throwable e) {
                    onScaleRenderFailed(e);
                }
            }
        }
        boolean wantScale = params.scaleEnabled && scaleRenderAvailable;
        CombatEngineLayers shadowLayer = isShadowWanted() ? pickShadowLayer() : null;
        if (renderPlugin != null && !renderPlugin.dead
                && renderPlugin.shadowLayer == shadowLayer && renderPlugin.coversShipLayers == wantScale) {
            return;
        }
        if (renderPlugin != null) renderPlugin.dead = true;
        renderPlugin = null;

        EnumSet<CombatEngineLayers> layers = EnumSet.noneOf(CombatEngineLayers.class);
        if (wantScale) {
            // 插件所在的层在加入时就固定了，而舰船进出相位会换层，所以把可能用到的层都占上，画的时候再按舰船当前的层判断。
            for (Part part : parts) {
                EnumSet<CombatEngineLayers> active = part.ship.getActiveLayers();
                if (active != null) layers.addAll(active);
                if (part.ship.getLayer() != null) layers.add(part.ship.getLayer());
            }
            layers.add(CombatEngineLayers.PHASED_SHIPS_LAYER);
            // 敌我识别圈不缩放，仍由引擎画。
            layers.remove(CombatEngineLayers.FF_INDICATORS_LAYER);
        }
        if (shadowLayer != null) layers.add(shadowLayer);
        if (layers.isEmpty()) return;

        renderPlugin = new RenderPlugin(this, layers, shadowLayer, wantScale);
        CombatEntityAPI host = engine.addLayeredRenderingPlugin(renderPlugin);
        if (host != null && host.getLocation() != null) host.getLocation().set(ship.getLocation());
    }

    /**
     * 原版阴影是在舰船自己的 render 里、紧挨着贴图之前画的。这里放到舰船所在层的下一层；
     * 如果那一层比母舰还低（阴影会被母舰盖住），就退回舰船所在层：
     * 缩放段舰船由本插件重画，阴影先画、舰船后画，顺序正确；其余时间舰船由引擎先画，阴影会盖在舰船上面。
     */
    private CombatEngineLayers pickShadowLayer() {
        if (params.shadowLayer != null) return params.shadowLayer;
        CombatEngineLayers own = ship.getLayer();
        if (own == null) return CombatEngineLayers.FRIGATES_LAYER;
        int below = own.ordinal() - 1;
        CombatEngineLayers carrierLayer = carrier != null ? carrier.getLayer() : null;
        int floor = carrierLayer != null ? carrierLayer.ordinal() : 0;
        if (below < 0 || below < floor) return own;
        return CombatEngineLayers.values()[below];
    }

    private float getPluginRenderRadius() {
        float radius = 0f;
        Vector2f center = ship.getLocation();
        float scale = Math.max(1f, currentScale);
        for (Part part : parts) {
            ShipAPI s = part.ship;
            radius = Math.max(radius, distance(center, s.getLocation()) + s.getCollisionRadius() * 1.5f);
        }
        return radius * scale + Math.abs(params.shadowMaxOffset) + 50f;
    }

    // ==================== 阴影 ====================

    private float getShadowAlpha() {
        if (phase == Phase.IDLE) return 0f;
        float alpha = params.computeShadowAlpha(clock, currentAlpha);
        if (phase == Phase.LANDING && params.landingShadowFadeIn > 0f) {
            alpha *= Math.min(1f, phaseElapsed / params.landingShadowFadeIn);
        }
        return clamp(alpha, 0f, 1f);
    }

    private void renderShadow(ViewportAPI viewport) {
        Params p = params;
        if (!p.shadowEnabled || p.shadowColor == null) return;
        float alpha = getShadowAlpha() * (viewport != null ? viewport.getAlphaMult() : 1f);
        if (!(alpha > 0f)) return;
        boolean clip = p.shadowClipToCarrier;
        ShipAPI c = carrier;
        // 与原版一致：需要裁剪但没有母舰时不画阴影。
        if (clip && (c == null || c.getSpriteAPI() == null || isCarrierLost())) return;

        // 偏移和大小都交给缩放矩阵处理（与舰船同一个缩放中心），所以这里是未缩放的偏移；原版的偏移同样在 glScalef 之内。
        float scale = currentScale;
        float offset = p.computeShadowOffset(clock);
        double radians = Math.toRadians(p.shadowAngle);
        float offsetX = (float) Math.cos(radians) * offset;
        float offsetY = (float) Math.sin(radians) * offset;

        float maskX = 0f, maskY = 0f, maskHalf = 0f;
        if (clip) {
            Vector2f cl = c.getLocation();
            maskX = cl.x;
            maskY = cl.y;
            maskHalf = c.getCollisionRadius() * 1.5f;
            float reach = Math.max(1f, scale);
            for (Part part : parts) {
                ShipAPI s = part.ship;
                maskHalf = Math.max(maskHalf, distance(cl, ship.getLocation())
                        + (distance(ship.getLocation(), s.getLocation()) + s.getCollisionRadius() * 1.5f
                                + Math.abs(offset)) * reach);
            }
            if (p.shadowClipIncludesModules) {
                for (ShipAPI module : c.getChildModulesCopy()) {
                    maskHalf = Math.max(maskHalf,
                            distance(cl, module.getLocation()) + module.getCollisionRadius() * 1.5f);
                }
            }
        }

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT
                | GL11.GL_CURRENT_BIT | GL11.GL_TEXTURE_BIT);
        try {
            if (clip) beginCarrierClip(c, p.shadowClipIncludesModules, maskX, maskY, maskHalf);
            boolean aroundRoot = p.scaleModulesAroundRoot;
            for (Part part : parts) {
                ShipAPI s = part.ship;
                if (part.released || !isOperational(s)) continue;
                Vector2f pivot = aroundRoot ? ship.getLocation() : s.getLocation();
                drawShadowSprite(s, pivot, offsetX, offsetY, scale, p.shadowColor, alpha);
            }
            if (clip) {
                // 用完把这块模板缓冲清回 0，不给后面的渲染留痕迹。
                GL11.glStencilFunc(GL11.GL_NEVER, 0, 1);
                GL11.glStencilOp(GL11.GL_REPLACE, GL11.GL_REPLACE, GL11.GL_REPLACE);
                fillSquare(maskX, maskY, maskHalf);
            }
        } finally {
            GL11.glPopAttrib();
        }
    }

    /**
     * 复刻原版 Ship.clipToShip()：先把母舰周围的模板值清 0，再把母舰贴图不透明的像素写成 1，
     * 之后只有模板值为 1 的地方能画上东西。写模板时关闭颜色输出，多张贴图重叠也不会被重复画出来。
     */
    private static void beginCarrierClip(ShipAPI carrier, boolean includeModules, float x, float y, float half) {
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glColorMask(false, false, false, false);
        GL11.glStencilFunc(GL11.GL_NEVER, 0, 1);
        GL11.glStencilOp(GL11.GL_REPLACE, GL11.GL_REPLACE, GL11.GL_REPLACE);
        fillSquare(x, y, half);

        GL11.glAlphaFunc(GL11.GL_NOTEQUAL, 0f);
        GL11.glStencilFunc(GL11.GL_NEVER, 1, 1);
        GL11.glStencilOp(GL11.GL_REPLACE, GL11.GL_REPLACE, GL11.GL_REPLACE);
        stampSprite(carrier);
        if (includeModules) {
            for (ShipAPI module : carrier.getChildModulesCopy()) {
                if (isOperational(module)) stampSprite(module);
            }
        }

        GL11.glColorMask(true, true, true, true);
        GL11.glStencilFunc(GL11.GL_EQUAL, 1, 1);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
    }

    private static void stampSprite(ShipAPI ship) {
        SpriteAPI sprite = ship.getSpriteAPI();
        if (sprite == null) return;
        Vector2f loc = ship.getLocation();
        sprite.setAngle(ship.getFacing() - 90f);
        sprite.renderAtCenter(loc.x, loc.y);
    }

    private static void fillSquare(float x, float y, float half) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x - half, y - half);
        GL11.glVertex2f(x - half, y + half);
        GL11.glVertex2f(x + half, y + half);
        GL11.glVertex2f(x + half, y - half);
        GL11.glEnd();
    }

    /** 借用舰船自己的贴图对象画阴影（保证贴图、中心点始终与当前外观一致），画完还原改动过的属性 */
    private static void drawShadowSprite(ShipAPI ship, Vector2f pivot, float offsetX, float offsetY, float scale,
            Color color, float alpha) {
        SpriteAPI sprite = ship.getSpriteAPI();
        if (sprite == null) return;
        Color oldColor = sprite.getColor();
        float oldAlpha = sprite.getAlphaMult();
        float oldAngle = sprite.getAngle();
        boolean scaled = Math.abs(scale - 1f) > 0.0001f;
        if (scaled) {
            GL11.glPushMatrix();
            GL11.glTranslatef(pivot.x, pivot.y, 0f);
            GL11.glScalef(scale, scale, 1f);
            GL11.glTranslatef(-pivot.x, -pivot.y, 0f);
        }
        try {
            sprite.setColor(color);
            sprite.setAlphaMult(alpha);
            sprite.setAngle(ship.getFacing() - 90f);
            Vector2f loc = ship.getLocation();
            sprite.renderAtCenter(loc.x + offsetX, loc.y + offsetY);
        } finally {
            if (scaled) GL11.glPopMatrix();
            sprite.setAngle(oldAngle);
            sprite.setAlphaMult(oldAlpha);
            if (oldColor != null) sprite.setColor(oldColor);
        }
    }

    /** 渲染插件：只负责画，进度全部读自所属的动画对象 */
    private static final class RenderPlugin extends BaseCombatLayeredRenderingPlugin {
        private final Moci_LandingAnimation anim;
        private final EnumSet<CombatEngineLayers> layers;
        /** 画阴影的层；null = 不画阴影 */
        private final CombatEngineLayers shadowLayer;
        /** 是否占用了舰船所在的各层（能否承担缩放重画） */
        private final boolean coversShipLayers;
        private boolean dead;
        private boolean shadowFailed;
        private boolean seenInPlay;

        RenderPlugin(Moci_LandingAnimation anim, EnumSet<CombatEngineLayers> layers,
                CombatEngineLayers shadowLayer, boolean coversShipLayers) {
            this.anim = anim;
            this.layers = layers;
            this.shadowLayer = shadowLayer;
            this.coversShipLayers = coversShipLayers;
        }

        @Override
        public void advance(float amount) {
            if (isExpired()) return;
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine != null) {
                if (engine.isEntityInPlay(anim.ship)) {
                    seenInPlay = true;
                } else if (seenInPlay) {
                    // 舰船被移出战场后它自己的监听器不会再被调用，由这里收尾。
                    anim.cancel();
                    return;
                }
            }
            if (entity != null && entity.getLocation() != null) {
                entity.getLocation().set(anim.ship.getLocation());
            }
        }

        @Override
        public void render(CombatEngineLayers layer, ViewportAPI viewport) {
            if (isExpired()) return;
            // 阴影先画：阴影和舰船落在同一层时，舰船要盖在阴影上面。
            if (layer == shadowLayer && !shadowFailed) {
                try {
                    anim.renderShadow(viewport);
                } catch (RuntimeException e) {
                    shadowFailed = true;
                    reportError("阴影渲染异常，已停止该阴影 ship=" + anim.ship.getId(), e);
                }
            }
            if (coversShipLayers && anim.takeover) {
                try {
                    anim.renderScaled(layer, viewport);
                } catch (Throwable e) {
                    anim.onScaleRenderFailed(e);
                }
            }
        }

        @Override
        public float getRenderRadius() {
            return isExpired() ? 50f : anim.getPluginRenderRadius();
        }

        @Override
        public EnumSet<CombatEngineLayers> getActiveLayers() {
            return layers;
        }

        @Override
        public boolean isExpired() {
            if (!dead && (anim.renderPlugin != this || anim.phase == Phase.IDLE)) dead = true;
            return dead;
        }
    }

    // ==================== 工具 ====================

    private static boolean isOperational(ShipAPI ship) {
        return ship != null && ship.isAlive() && !ship.isHulk();
    }

    private static float distance(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float clamp(float value, float min, float max) {
        if (!(value >= min)) return min;
        return Math.min(value, max);
    }

    private static void reportError(String message, Throwable error) {
        if (errorReports >= MAX_ERROR_REPORTS) return;
        errorReports++;
        LOG.error(LOG_PREFIX + message, error);
    }
}
