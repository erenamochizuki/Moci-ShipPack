package data.hullmods;

import java.awt.Color;
import java.util.List;

import org.lwjgl.util.vector.Vector2f;
import org.apache.log4j.Logger;
import org.magiclib.subsystems.MagicSubsystem;
import org.magiclib.subsystems.MagicSubsystemsManager;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;

import data.scripts.Moci_CollisionStateManager;
import data.scripts.util.Moci_TextLoader;
import data.scripts.visual.Moci_PsycommuGlowVisual;
import data.subsystems.Moci_NTDSubsystem;

/**
 * 精神感应框架系统
 * 当舰船开启战术系统时激活，提供以下效果：
 * 
 * 所有舰船：
 * 1. 添加绿色 Halo 发光效果（包含船体、模块和可见武器）
 * 2. 持续生成绿色边缘白色核心的粒子特效
 * 
 * 护卫舰/驱逐舰/巡洋舰：
 * 3. 降低50%的武器赋能消耗
 * 4. 提高50%的武器射速
 * 5. 将碰撞方式更改为战机
 * 
 * 主力舰：
 * 3. 提高100点最大速度
 * 4. 大幅提升机动性能（加速度、转向速度等）
 * 5. 引擎光效增强（绿色光芒和尾焰延长）
 * 
 * 使用碰撞状态管理器避免与其他功能的碰撞冲突
 */
public class Moci_PsycommuSystem extends BaseHullMod {
    private static final Logger LOG = Global.getLogger(Moci_PsycommuSystem.class);
    private static final String LOG_PREFIX = "[MOCI-TIME] ";

    private static final String TEXT_ID = "Moci_PsycommuSystem";
    
    // 碰撞状态管理
    protected static final String HULLMOD_ID = "Moci_PsycommuSystem";
    private static final String COLLISION_MODIFIER_ID = HULLMOD_ID;
    private static final int COLLISION_PRIORITY = 200; // 精神感应系统的碰撞优先级（低于着陆AI）
    private static final String VISUAL_MOD_ID = "Moci_PsycommuSystem_Halo";
    private static final String NEWTYPE_SKILL_ID = "Moci_PlayerSkill1";
    private Moci_CollisionStateManager collisionManager = Moci_CollisionStateManager.getInstance();
    private static final boolean USE_NTD_SUBSYSTEM_COLOR = true;

    // 视觉效果参数
    private static final Color PARTICLE_EDGE_COLOR = new Color(80, 255, 0, 197); // 默认粒子边缘颜色
    private static final Color NTD_SYSTEM_COLOR = new Color(255, 0, 0, 255); // NTD固定红色
    private static final float PARTICLE_INTERVAL = 0.1f; // 粒子生成间隔时间（秒）
    private static final String PARTICLE_TIMER_KEY = "moci_psycommu_particle_timer"; // 粒子计时器存储键
    private static final float HALO_SIZE_MULT = 1.9f;
    private static final float HALO_INTENSITY_MULT = 3.4f;
    private static final float HALO_ALPHA_MULT = 3.2f;
    private static final float HALO_FLASH_MULT = 2.0f;
    private static final float HALO_SHIFT_MULT = 1.6f;
    private static final float HALO_COLOR_SHIFT = 0.85f;
    private static final float NEWTYPE_TIME_MULT = 1.2f;
    /** 本舰时缓状态在 {@code ship.getCustomData()} 里的键：模组效果是单例，状态只能挂舰上。 */
    private static final String TIME_STATE_KEY = "moci_psycommu_time_state";
    
    // 主力舰机动性加成参数
    private static final float CAPITAL_SPEED_BONUS = 50f; // 主力舰速度加成
    private static final float CAPITAL_TURN_BONUS = 50f; // 主力舰转向加成
    private static final Color CAPITAL_ENGINE_COLOR = new Color(80, 255, 0, 255); // 默认主力舰引擎颜色

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine != null && ship != null) {
            Moci_PsycommuGlowVisual.getInstance(engine, ship, true);
        }
    }

    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        if (ship == null) return;

        // 这是本船插唯一的每帧入口（Ship.advance 里遍历船插无条件调用，对残骸也照调）。
        // 引擎不会在舰船被打爆时回头摘掉修饰符，所以「船没了就摘」只能在这里自己发现；
        // 先于 getSystem() 判，是因为残骸上系统已不可用，但仍可能挂着上一帧的时缓。
        NewtypeTimeState state = timeStateOf(ship);
        if (state != null && dead(ship)) {
            releaseForDead(ship, state);
            return;
        }

        if (ship.getSystem() == null) return;

        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return;
        
        // 确保碰撞状态已初始化
        collisionManager.initializeDefaultCollision(ship);

        Moci_PsycommuGlowVisual visual = Moci_PsycommuGlowVisual.getInstance(engine, ship, true);
        float ntdColorBlend = getNTDSubsystemColorBlend(ship);
        if (visual != null) {
            updateHaloVisual(visual, ship.getSystem().getEffectLevel(), ntdColorBlend);
        }
        
        // 检查系统是否激活
        boolean systemActive = ship.getSystem().isActive();
        
        // 检查是否为主力舰
        boolean isCapitalShip = ship.getHullSize() == HullSize.CAPITAL_SHIP;

        // 系统激活时的效果
        if (systemActive) {
            MutableShipStatsAPI stats = ship.getMutableStats();
            
            if (isCapitalShip) {
                // 主力舰获得机动性和速度加成
                stats.getMaxSpeed().modifyFlat(getHullModId(), CAPITAL_SPEED_BONUS * getEffectMult()); // 最大速度加成
                stats.getAcceleration().modifyPercent(getHullModId(), CAPITAL_SPEED_BONUS * 2f * getEffectMult()); // 加速度加成
                stats.getDeceleration().modifyPercent(getHullModId(), CAPITAL_SPEED_BONUS * 2f * getEffectMult()); // 减速度加成
                stats.getTurnAcceleration().modifyFlat(getHullModId(), CAPITAL_TURN_BONUS * getEffectMult()); // 转向加速度加成
                stats.getTurnAcceleration().modifyPercent(getHullModId(), CAPITAL_TURN_BONUS * 3f * getEffectMult()); // 转向加速度百分比加成
                stats.getMaxTurnRate().modifyFlat(getHullModId(), 15f * getEffectMult()); // 最大转向速度加成
                stats.getMaxTurnRate().modifyPercent(getHullModId(), 50f * getEffectMult()); // 最大转向速度百分比加成
                
                // 主力舰引擎光效调整
                ship.getEngineController().fadeToOtherColor(this, getCapitalEngineColor(ntdColorBlend), new Color(0,0,0,0), 1f, 0.67f);
                ship.getEngineController().extendFlame(this, 2f, 1.5f, 1.5f);
            } else {
                // 非主力舰获得射速、幅能消耗降低和战机碰撞效果
                stats.getEnergyWeaponFluxCostMod().modifyMult(getHullModId(), getWeaponFluxCostMult()); // 武器赋能消耗降低
                stats.getBallisticWeaponFluxCostMod().modifyMult(getHullModId(), getWeaponFluxCostMult()); // 实弹武器赋能消耗降低
                stats.getEnergyRoFMult().modifyMult(getHullModId(), getWeaponRoFMult()); // 能量武器射速提高
                stats.getBallisticRoFMult().modifyMult(getHullModId(), getWeaponRoFMult()); // 实弹武器射速提高
                
                // 设置碰撞方式为战机（使用碰撞管理器）
                CollisionClass defaultCollision = collisionManager.getDefaultCollision(ship);
                if (defaultCollision != null && defaultCollision != CollisionClass.NONE) {
                    collisionManager.setCollisionModifier(ship, getCollisionModifierId(), COLLISION_PRIORITY, CollisionClass.FIGHTER);
                }
            }

            applyNewtypeTimeMult(ship, stats, engine);

            // 处理粒子效果生成 - 每个舰船独立的计时器
            if (shouldSpawnParticles()) {
                IntervalUtil particleInterval = (IntervalUtil) ship.getCustomData().get(PARTICLE_TIMER_KEY);
                if (particleInterval == null) {
                    particleInterval = new IntervalUtil(PARTICLE_INTERVAL, PARTICLE_INTERVAL);
                    ship.setCustomData(PARTICLE_TIMER_KEY, particleInterval);
                }
                particleInterval.advance(amount);
                if (particleInterval.intervalElapsed()) {
                    spawnParticles(ship, getParticleColor(ntdColorBlend));
                }
            }
        } else {
            // 系统关闭时移除所有效果
            // 恢复原始碰撞类别（使用碰撞管理器）
            collisionManager.removeCollisionModifier(ship, getCollisionModifierId());
            
            MutableShipStatsAPI stats = ship.getMutableStats();
            // 移除所有可能的效果修改
            stats.getEnergyWeaponFluxCostMod().unmodify(getHullModId()); // 移除能量武器赋能消耗修改
            stats.getBallisticWeaponFluxCostMod().unmodify(getHullModId()); // 移除实弹武器赋能消耗修改
            stats.getEnergyRoFMult().unmodify(getHullModId()); // 移除能量武器射速修改
            stats.getBallisticRoFMult().unmodify(getHullModId()); // 移除实弹武器射速修改
            // 移除主力舰机动性效果
            stats.getMaxSpeed().unmodify(getHullModId()); // 移除最大速度修改
            stats.getAcceleration().unmodify(getHullModId()); // 移除加速度修改
            stats.getDeceleration().unmodify(getHullModId()); // 移除减速度修改
            stats.getTurnAcceleration().unmodify(getHullModId()); // 移除转向加速度修改
            stats.getMaxTurnRate().unmodify(getHullModId()); // 移除最大转向速度修改
            unapplyNewtypeTimeMult(ship, stats, engine);
        }
    }

    private void updateHaloVisual(Moci_PsycommuGlowVisual visual, float effectLevel, float ntdColorBlend) {
        float clampedLevel = Math.max(0f, Math.min(1f, effectLevel));

        visual.setOutlineColorOverride(getHaloColor(ntdColorBlend));
        visual.getOutlineSizeStat().modifyMult(getVisualModId(), clampedLevel * HALO_SIZE_MULT);
        visual.getOutlineIntensityStat().modifyMult(getVisualModId(), clampedLevel * HALO_INTENSITY_MULT);
        visual.getOutlineAlphaMultStat().modifyMult(getVisualModId(), clampedLevel * HALO_ALPHA_MULT);
        visual.getOutlineFlashMultStat().modifyMult(getVisualModId(), clampedLevel * HALO_FLASH_MULT);
        visual.getOutlineShiftMultStat().modifyMult(getVisualModId(), clampedLevel * HALO_SHIFT_MULT);
        visual.getColorShiftMultStat().modifyFlat(getVisualModId(), clampedLevel * HALO_COLOR_SHIFT);
    }

    /**
     * 生成粒子特效
     * @param ship 目标舰船
     */
    private void spawnParticles(ShipAPI ship, Color particleColor) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return;
        
        Vector2f shipLocation = ship.getLocation();
        Vector2f shipVelocity = ship.getVelocity();

        // 每次生成20个粒子
        for (int i = 0; i < 20; i++) {
            // 计算随机角度
            float angle = (float) (Math.random() * 360);
            // 计算起始位置(距离舰船中心100f)
            Vector2f offset = new Vector2f(
                    (float) Math.cos(Math.toRadians(angle)) * 50f,
                    (float) Math.sin(Math.toRadians(angle)) * 50f
            );
            Vector2f loc = Vector2f.add(shipLocation, offset, null);

            // 计算速度(向外扩散并加上舰船速度)
            Vector2f vel = new Vector2f(offset.x, offset.y);
            vel.normalise();
            vel.scale(150f); // 基础扩散速度
            Vector2f.add(vel, shipVelocity, vel); // 叠加舰船速度

            // 生成绿色边缘粒子
            engine.addHitParticle(
                    loc, // 从距离舰船100f的位置开始
                    vel, // 包含舰船速度的粒子速度
                    5, // 粒子大小
                    2f, // 粒子亮度
                    0.75f, // 持续时间
                    particleColor
            );
        }
    }

    private float getNTDSubsystemColorBlend(ShipAPI ship) {
        if (!USE_NTD_SUBSYSTEM_COLOR || ship == null) {
            return 0f;
        }

        List<MagicSubsystem> subsystems = MagicSubsystemsManager.getSubsystemsForShipCopy(ship);
        if (subsystems == null) {
            return 0f;
        }

        for (MagicSubsystem subsystem : subsystems) {
            if (subsystem instanceof Moci_NTDSubsystem) {
                return Math.max(0f, Math.min(1f, ((Moci_NTDSubsystem) subsystem).getCurrentEffectLevel()));
            }
        }

        return 0f;
    }

    private Color getHaloColor(float blend) {
        Color baseColor = getBaseHaloColor();
        if (blend <= 0f) {
            return baseColor;
        }
        if (baseColor == null) {
            baseColor = Moci_PsycommuGlowVisual.BASE_OUTLINE_COLOR;
        }
        return Misc.interpolateColor(baseColor, NTD_SYSTEM_COLOR, blend);
    }

    private Color getParticleColor(float blend) {
        if (blend <= 0f) {
            return PARTICLE_EDGE_COLOR;
        }
        Color blended = Misc.interpolateColor(toOpaqueColor(PARTICLE_EDGE_COLOR), NTD_SYSTEM_COLOR, blend);
        return withAlpha(blended, PARTICLE_EDGE_COLOR.getAlpha());
    }

    private Color getCapitalEngineColor(float blend) {
        Color baseColor = getCapitalEngineBaseColor();
        if (blend <= 0f) {
            return baseColor;
        }
        Color blended = Misc.interpolateColor(toOpaqueColor(baseColor), NTD_SYSTEM_COLOR, blend);
        return withAlpha(blended, baseColor.getAlpha());
    }

    private Color toOpaqueColor(Color color) {
        return withAlpha(color, 255);
    }

    private Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    protected String getTextId() {
        return TEXT_ID;
    }

    protected String getHullModId() {
        return HULLMOD_ID;
    }

    protected String getCollisionModifierId() {
        return COLLISION_MODIFIER_ID;
    }

    protected String getVisualModId() {
        return VISUAL_MOD_ID;
    }

    protected float getEffectMult() {
        return 1f;
    }

    protected boolean shouldSpawnParticles() {
        return true;
    }

    protected Color getBaseHaloColor() {
        return null;
    }

    protected Color getCapitalEngineBaseColor() {
        return CAPITAL_ENGINE_COLOR;
    }

    protected Color getHeadingTextColor() {
        return new Color(151, 255, 0, 255);
    }

    protected Color getHeadingBackgroundColor() {
        return new Color(160, 255, 0, 50);
    }

    private float getWeaponFluxCostMult() {
        return 1f - 0.5f * getEffectMult();
    }

    private float getWeaponRoFMult() {
        return 1f + 0.5f * getEffectMult();
    }

    private void applyNewtypeTimeMult(ShipAPI ship, MutableShipStatsAPI stats, CombatEngineAPI engine) {
        if (!hasNewtypePilot(ship)) {
            unapplyNewtypeTimeMult(ship, stats, engine);
            return;
        }

        String timeId = getTimeModifierId(ship);
        stats.getTimeMult().modifyMult(timeId, NEWTYPE_TIME_MULT);
        // 世界那一半只给玩家舰，且必须还活着：舰船被打爆后引擎不会再回调本船改回 1 倍速，
        // 于是「开着系统被打死」会把全局拖慢一辈子 —— 这就是「进了时缓出不来」。
        if (engine != null && ship == engine.getPlayerShip() && ship.isAlive() && !ship.wasRemoved()) {
            engine.getTimeMult().modifyMult(timeId, 1f / NEWTYPE_TIME_MULT);
        } else if (engine != null) {
            engine.getTimeMult().unmodify(timeId);
        }
        NewtypeTimeState state = timeState(ship, timeId);
        // 重新挂上就是新的一次挂载，允许「船没了」再报一次
        state.releaseLogged = false;
    }

    /**
     * 取（或建）本舰的时缓状态。
     *
     * <p>{@code HullModSpec.getEffect()} 是全模组单例、所有船共用一个实例，
     * 状态必须挂在 {@code ship.getCustomData()} 上，否则两艘船会互相覆盖对方记的 id。</p>
     */
    private NewtypeTimeState timeState(ShipAPI ship, String timeId) {
        Object raw = ship.getCustomData().get(TIME_STATE_KEY);
        if (raw instanceof NewtypeTimeState && timeId.equals(((NewtypeTimeState) raw).id)) {
            return (NewtypeTimeState) raw;
        }
        if (raw instanceof NewtypeTimeState) {
            // id 变了（换船/换模组实例）：旧状态先摘干净再换
            ((NewtypeTimeState) raw).release();
            ship.setCustomData(TIME_STATE_KEY, null);
        }
        NewtypeTimeState state = new NewtypeTimeState(ship, timeId);
        ship.setCustomData(TIME_STATE_KEY, state);
        return state;
    }

    /**
     * 这艘船的时缓还该不该存在：判「船没了」。
     *
     * <p>为什么判这四条：被打爆（{@code isHulk}）与自然消失（{@code wasRemoved}）都要摘；
     * 换场后 {@code Global.getCombatEngine()} 会换人或为 null，旧战场的属性表已经没意义；
     * {@code isEntityInPlay} 覆盖「还活着但已被移出场」这种中间态。</p>
     */
    private static boolean dead(ShipAPI ship) {
        if (ship == null) return true;
        if (!ship.isAlive() || ship.isHulk() || ship.wasRemoved()) return true;
        CombatEngineAPI engine = Global.getCombatEngine();
        return engine == null || !engine.isEntityInPlay(ship);
    }

    /** 只取本舰已有的时缓状态，不建新的：判断「要不要摘」时不该顺手造状态。 */
    private NewtypeTimeState timeStateOf(ShipAPI ship) {
        if (ship == null) return null;
        Object raw = ship.getCustomData() == null ? null : ship.getCustomData().get(TIME_STATE_KEY);
        return raw instanceof NewtypeTimeState ? (NewtypeTimeState) raw : null;
    }

    /** 船没了：把本条时缓摘干净并留一条日志。摘除本身幂等，重复调用无害。 */
    private void releaseForDead(ShipAPI ship, NewtypeTimeState state) {
        state.release();
        ship.setCustomData(TIME_STATE_KEY, null);
        if (!state.releaseLogged) {
            state.releaseLogged = true;
            LOG.warn(LOG_PREFIX + "PSYCOMMU 宿主舰已死亡或离场，摘掉时缓（ship="
                    + (ship == null ? "-" : ship.getId()) + "）");
        }
    }

    private void unapplyNewtypeTimeMult(ShipAPI ship, MutableShipStatsAPI stats, CombatEngineAPI engine) {
        String timeId = getTimeModifierId(ship);
        Object raw = ship.getCustomData() == null ? null : ship.getCustomData().get(TIME_STATE_KEY);
        if (raw instanceof NewtypeTimeState) {
            // 状态里记的才是真正挂上去的 id：参数变过也摘得掉
            timeId = ((NewtypeTimeState) raw).id;
            ((NewtypeTimeState) raw).release();
            ship.setCustomData(TIME_STATE_KEY, null);
        }
        stats.getTimeMult().unmodify(timeId);
        if (engine != null) {
            engine.getTimeMult().unmodify(timeId);
        }
    }

    private String getTimeModifierId(ShipAPI ship) {
        return getHullModId() + "_TimeMult_" + ship.getId();
    }

    /**
     * 单舰的时缓状态。
     *
     * <p>为什么状态要挂在舰上而不是模组实例上：{@code HullModSpec.getEffect()} 是全模组单例，
     * 所有船共用一个实例；状态放实例字段会让两艘船互相覆盖对方的 id。</p>
     *
     * <p>摘除的时机有两条，缺一不可：①{@code advanceInCombat} 里发现船已死（见 {@link #dead}）；
     * ②系统关闭那一帧的 {@code unapplyNewtypeTimeMult}。世界 1/f 必须两条路都能摘掉，
     * 否则「开着系统被打死」就会把全局永久拖慢。</p>
     */
    private final class NewtypeTimeState {
        final ShipAPI ship;
        final String id;
        /** 本次挂载是否已经为「船没了」打过日志；只为避免每帧刷屏，重新挂载时复位。 */
        boolean releaseLogged;

        NewtypeTimeState(ShipAPI ship, String id) {
            this.ship = ship;
            this.id = id;
        }

        /** 幂等、绝不抛；摘除都在这里收口。 */
        void release() {
            if (ship != null) {
                try {
                    ship.getMutableStats().getTimeMult().unmodify(id);
                } catch (RuntimeException | LinkageError ignored) {
                    // 舰船已被回收：这条属性表不存在了，没有可摘的东西
                }
            }
            try {
                CombatEngineAPI engine = Global.getCombatEngine();
                if (engine != null) engine.getTimeMult().unmodify(id);
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
    }

    private boolean hasNewtypePilot(ShipAPI ship) {
        return ship != null
                && ship.getCaptain() != null
                && !ship.getCaptain().isDefault()
                && ship.getCaptain().getStats() != null
                && ship.getCaptain().getStats().getSkillLevel(NEWTYPE_SKILL_ID) > 0f;
    }

    @Override
    public boolean shouldAddDescriptionToTooltip(HullSize hullSize, ShipAPI ship, boolean isForModSpec) {
        return false;
    }

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
        float pad = 3f;
        float opad = 10f;
        Color h = Misc.getHighlightColor();
        Color good = Misc.getPositiveHighlightColor();

        tooltip.addPara(Moci_TextLoader.getText(getTextId(), "description.intro"), opad);
        tooltip.addSectionHeading(Moci_TextLoader.getText(getTextId(), "description.heading"),
                getHeadingTextColor(), //文字颜色
                getHeadingBackgroundColor(), //背景颜色
                Alignment.MID, // 文字位置
                opad); // 与其他元素之间的间距

        // 检查是否为主力舰
        boolean isCapitalShip = hullSize == HullSize.CAPITAL_SHIP;
        
        if (isCapitalShip) {
            // 主力舰获得机动性和速度加成
            tooltip.addPara(
                    Moci_TextLoader.getTextWithReplacements(getTextId(), "description.capital_speed",
                            Moci_TextLoader.mapOf("speed_bonus", String.valueOf(Math.round(CAPITAL_SPEED_BONUS * getEffectMult())))),
                    pad, good,
                    Moci_TextLoader.getHighlightsWithReplacements(getTextId(), "description.capital_speed_highlights",
                            Moci_TextLoader.mapOf("speed_bonus", String.valueOf(Math.round(CAPITAL_SPEED_BONUS * getEffectMult())))).toArray(new String[0]));
            tooltip.addPara(
                    Moci_TextLoader.getText(getTextId(), "description.capital_maneuver"),
                    pad, good,
                    Moci_TextLoader.getHighlights(getTextId(), "description.capital_maneuver_highlights").toArray(new String[0]));
        } else {
            // 非主力舰获得射速、幅能消耗降低和穿越能力
            tooltip.addPara(
                    Moci_TextLoader.getText(getTextId(), "description.rof_bonus"),
                    pad, good,
                    Moci_TextLoader.getHighlights(getTextId(), "description.rof_bonus_highlights").toArray(new String[0]));
            tooltip.addPara(
                    Moci_TextLoader.getText(getTextId(), "description.flux_reduction"),
                    pad, good,
                    Moci_TextLoader.getHighlights(getTextId(), "description.flux_reduction_highlights").toArray(new String[0]));
            
            // 检查是否为护卫舰且安装了机动兵器ID卡
            boolean isFrigateWithIDCard = (hullSize == HullSize.FRIGATE) && 
                                         (ship != null && ship.getVariant() != null && 
                                          ship.getVariant().hasHullMod("Moci_MobileSuitsIDcard"));
            
            // 只有非护卫舰或未安装机动兵器ID卡的护卫舰才显示穿越能力
            if (!isFrigateWithIDCard) {
                tooltip.addPara(
                        Moci_TextLoader.getText(getTextId(), "description.phase_collision"),
                        pad, h,
                        Moci_TextLoader.getHighlights(getTextId(), "description.phase_collision_highlights").toArray(new String[0]));
            }
        }

        tooltip.addPara(
                Moci_TextLoader.getText(getTextId(), "description.newtype_time"),
                pad, good,
                Moci_TextLoader.getHighlights(getTextId(), "description.newtype_time_highlights").toArray(new String[0]));

/*
        if (!isForModSpec && ship != null) {
            if (!ship.getCaptain().isDefault()) {
                tooltip.addPara("该舰船已有军官，可以获得上述效果。", opad, good);
            } else {
                tooltip.addPara("该舰船没有军官，无法激活精神感应框架系统。", opad, Misc.getNegativeHighlightColor());
            }
        }

 */
    }
}
