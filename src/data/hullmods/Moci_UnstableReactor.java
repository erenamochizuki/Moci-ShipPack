package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.combat.listeners.DamageListener;
import com.fs.starfarer.api.impl.combat.ShipExplosionFlareVisual;
import com.fs.starfarer.api.loading.DamagingExplosionSpec;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;
import org.lazywizard.lazylib.combat.CombatUtils;
import org.lwjgl.util.vector.Vector2f;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;

/**
 * 不稳定反应堆 - Unstable Reactor
 * 
 * 正面效果：增加500点幅能容量
 * 负面效果：船只被摧毁后会失控并向杀死它的敌人发动自杀式攻击，
 * 根据舰船的最大结构值设置伤害，根据舰船尺寸设置爆炸范围，
 * 造成一次不分敌我的大爆炸。
 */
public class Moci_UnstableReactor extends BaseHullMod {
    private static final String TEXT_ID = "Moci_UnstableReactor";

    /** 幅能容量增加值 */
    public static final float FLUX_CAPACITY_BONUS = 500f;

    /** 不同尺寸舰船的爆炸基础伤害倍数（相对最大结构值） */
    public static Map<ShipAPI.HullSize, Float> DAMAGE_MULTIPLIER = new HashMap<ShipAPI.HullSize, Float>();
    static {
        DAMAGE_MULTIPLIER.put(ShipAPI.HullSize.FIGHTER, 1.5f);
        DAMAGE_MULTIPLIER.put(ShipAPI.HullSize.FRIGATE, 2.0f);
        DAMAGE_MULTIPLIER.put(ShipAPI.HullSize.DESTROYER, 2.5f);
        DAMAGE_MULTIPLIER.put(ShipAPI.HullSize.CRUISER, 2.5f);
        DAMAGE_MULTIPLIER.put(ShipAPI.HullSize.CAPITAL_SHIP, 2.5f);
    }

    /** 不同尺寸舰船的爆炸范围 */
    public static Map<ShipAPI.HullSize, Float> EXPLOSION_RADIUS = new HashMap<ShipAPI.HullSize, Float>();
    static {
        EXPLOSION_RADIUS.put(ShipAPI.HullSize.FIGHTER, 150f);
        EXPLOSION_RADIUS.put(ShipAPI.HullSize.FRIGATE, 200f);
        EXPLOSION_RADIUS.put(ShipAPI.HullSize.DESTROYER, 250f);
        EXPLOSION_RADIUS.put(ShipAPI.HullSize.CRUISER, 300f);
        EXPLOSION_RADIUS.put(ShipAPI.HullSize.CAPITAL_SHIP, 350f);
    }

    /** 爆炸效果颜色 */
    public static Color EXPLOSION_COLOR = new Color(255, 100, 50, 200);

    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 增加幅能容量
        stats.getFluxCapacity().modifyFlat(id, FLUX_CAPACITY_BONUS);
    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        if (ship.isStationModule())
            return; // 跳过空间站模块
        if (ship.isFighter())
            return; // 跳过战斗机
        ship.addListener(new UnstableReactorExplosion(ship));
    }

    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        if (index == 0)
            return "" + (int) FLUX_CAPACITY_BONUS;
        if (index == 1)
            return "" + Math.round(DAMAGE_MULTIPLIER.get(hullSize) * 100f) + "%";
        if (index == 2)
            return "" + Math.round(EXPLOSION_RADIUS.get(hullSize))
                    + Moci_TextLoader.getText(TEXT_ID, "description.param_radius_suffix");
        return null;
    }

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, ShipAPI.HullSize hullSize, ShipAPI ship, float width,
            boolean isForModSpec) {
        float pad = 10f;
        Color h = Misc.getHighlightColor();
        Color bad = Misc.getNegativeHighlightColor();
        Color good = Misc.getPositiveHighlightColor();

        tooltip.addPara(Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.flux_bonus",
                        Moci_TextLoader.mapOf("flux_bonus", String.valueOf((int) FLUX_CAPACITY_BONUS))),
                pad, good,
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.flux_bonus_highlights",
                        Moci_TextLoader.mapOf("flux_bonus", String.valueOf((int) FLUX_CAPACITY_BONUS))).toArray(new String[0]));

        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.warning"), pad);

        tooltip.addPara(Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.damage",
                        Moci_TextLoader.mapOf("damage_percent", String.valueOf(Math.round(DAMAGE_MULTIPLIER.get(hullSize) * 100f)))),
                pad, new Color[] { bad, h },
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.damage_highlights",
                        Moci_TextLoader.mapOf("damage_percent", String.valueOf(Math.round(DAMAGE_MULTIPLIER.get(hullSize) * 100f)))).toArray(new String[0]));
    }

    @Override
    public Color getBorderColor() {
        return new Color(255, 100, 50, 150);
    }

    @Override
    public Color getNameColor() {
        return new Color(255, 150, 100);
    }

    /**
     * 不稳定反应堆爆炸脚本类
     * 处理舰船被摧毁后的失控冲撞和爆炸逻辑
     */
    public static class UnstableReactorExplosion implements AdvanceableListener, DamageListener {
        /** 受影响的船只 */
        public ShipAPI ship;

        /** 杀死这艘船的船只，如果有的话 */
        public ShipAPI target = null;

        /** 用于爆炸的隐藏地雷 */
        public MissileAPI mine;

        /** 是否正在失控 */
        public boolean outOfControl = false;

        /** 失控进度 */
        public float controlLossProgress = 0f;

        /** 是否已经冲刺 */
        public boolean hasRammed = false;

        /** 下次电弧效果的时间 */
        public float timeUntilNextSpark = 0.3f;

        /**
         * 构造函数
         */
        public UnstableReactorExplosion(ShipAPI ship) {
            this.ship = ship;
        }

        /**
         * 伤害应用报告
         * 记录杀死这艘船的敌人，用于后续的报复攻击
         */
        @Override
        public void reportDamageApplied(Object source, CombatEntityAPI target, ApplyDamageResultAPI result) {
            if (ship == null || !ship.isAlive()) {
                return;
            }
            if (source == null) {
                return;
            }

            // 识别伤害来源
            if (source instanceof ShipAPI) {
                this.target = (ShipAPI) source;
            }

            if (this.target == null) {
                return;
            }

            // 如果是载机，找到载机的母舰
            if (this.target.getWing() != null) {
                if (this.target.getWing().getSourceShip() != null) {
                    this.target = this.target.getWing().getSourceShip();
                }
            }
        }

        /**
         * 每帧更新逻辑
         * 处理失控状态的视觉效果、移动和最终爆炸
         */
        @Override
        public void advance(float amount) {
            if (ship == null)
                return;
            String id = "moci_unstable_reactor";

            // 检查船只是否变成残骸
            if (!outOfControl && ship.isHulk()) {
                outOfControl = true;
                // 安全检查 - 确保位置和速度不为null
                Vector2f shipLoc = ship.getLocation();
                Vector2f shipVel = ship.getVelocity();
                if (shipLoc != null && shipVel != null) {
                    Global.getSoundPlayer().playSound("engine_accelerate", 1.0f, 0.6f, shipLoc, new Vector2f(0f, 0f));
                }
            }

            if (outOfControl) {
                // 失控状态的逻辑
                float timeMult = ship.getMutableStats().getTimeMult().getModifiedValue();
                Color reactorColor = EXPLOSION_COLOR.brighter();

                // 首次失控时的初始化
                if (controlLossProgress == 0f) {
                    Vector2f shipLoc = ship.getLocation();
                    if (shipLoc != null) {
                        // 显示浮动文本
                        Global.getCombatEngine().addFloatingTextAlways(shipLoc,
                                Moci_TextLoader.getText(TEXT_ID, "status.floating_text"),
                                20f,
                                reactorColor,
                                ship,
                                12f * timeMult,
                                1f / timeMult,
                                1f / timeMult,
                                0.5f,
                                0.5f,
                                1f);

                        // 生成隐藏地雷用于爆炸
                        mine = (MissileAPI) Global.getCombatEngine().spawnProjectile(ship, null,
                                "reaper", // 使用收割者鱼雷作为基础
                                shipLoc,
                                0f, null);

                        if (mine != null) {
                            // 计算爆炸伤害
                            float explosionDamage = ship.getMaxHitpoints() * DAMAGE_MULTIPLIER.get(ship.getHullSize());
                            mine.setDamageAmount(explosionDamage);

                            // 为AI设置数据标志
                            mine.setMinePrimed(true);
                            mine.setUntilMineExplosion(0.1f);
                            // 爆炸范围 = 基础范围 + 舰船碰撞半径
                            float mineExplosionRange = EXPLOSION_RADIUS.get(ship.getHullSize())
                                    + ship.getCollisionRadius();
                            mine.setMineExplosionRange(mineExplosionRange);

                            // 设置为无法被摧毁
                            mine.setHitpoints(9999f);
                            mine.setEmpResistance(9999);
                        }

                        // 如果有目标，显示电弧效果
                        if (target != null && target.getLocation() != null) {
                            for (int i = 0; i < 3; i++) {
                                Global.getCombatEngine().spawnEmpArcVisual(
                                        Misc.getPointWithinRadius(shipLoc, ship.getCollisionRadius() * 0.25f),
                                        ship,
                                        Misc.getPointWithinRadius(target.getLocation(),
                                                target.getCollisionRadius() * 0.25f),
                                        target,
                                        25f,
                                        reactorColor,
                                        Color.WHITE);
                            }
                        }
                    }
                }

                // 显示状态信息
                if (Global.getCombatEngine().getPlayerShip() != null) {
                    if (Global.getCombatEngine().getPlayerShip().equals(target)) {
                        Global.getCombatEngine().maintainStatusForPlayerShip(id,
                                "graphics/icons/hullsys/entropy_amplifier.png",
                                Moci_TextLoader.getText(TEXT_ID, "status.status_title"),
                                Moci_TextLoader.getText(TEXT_ID, "status.status_target"), true);
                    } else if (Global.getCombatEngine().getPlayerShip().equals(ship)) {
                        Global.getCombatEngine().maintainStatusForPlayerShip(id,
                                "graphics/icons/hullsys/entropy_amplifier.png",
                                Moci_TextLoader.getText(TEXT_ID, "status.status_title"),
                                Moci_TextLoader.getText(TEXT_ID, "status.status_self"), false);
                    }
                }

                // 同步地雷与船只位置
                if (mine != null && ship.getLocation() != null && ship.getVelocity() != null) {
                    mine.getLocation().set(ship.getLocation().x, ship.getLocation().y);
                    mine.getVelocity().set(ship.getVelocity().x, ship.getVelocity().y);
                }

                // 电火花效果
                timeUntilNextSpark -= amount;
                if (timeUntilNextSpark <= 0 && ship.getLocation() != null) {
                    float thickness = 15f + (10f * (float) Math.random());
                    Vector2f sparkTo = Misc.getPointAtRadius(ship.getLocation(), 100f + (100f * (float) Math.random()));
                    EmpArcEntityAPI arc = Global.getCombatEngine().spawnEmpArcVisual(
                            ship.getShieldCenterEvenIfNoShield(),
                            ship,
                            sparkTo,
                            ship,
                            thickness,
                            EXPLOSION_COLOR,
                            Color.WHITE);
                    arc.setSingleFlickerMode();

                    float randomFactor = (float) Math.random();
                    timeUntilNextSpark = 0.2f + (0.1f * randomFactor);
                    Vector2f shipVel = ship.getVelocity();
                    if (shipVel != null) {
                        Global.getCombatEngine().addHitParticle(ship.getLocation(), shipVel, 40f + (20f * randomFactor),
                                1f, 0.4f, EXPLOSION_COLOR.brighter());
                    }
                }

                // 抖动效果
                ship.setJitter(this, reactorColor, controlLossProgress / 1.5f, 8, 3f);

                controlLossProgress += amount;

                // 冲撞攻击！
                if (controlLossProgress >= 1.5f && !hasRammed && target != null && target.getLocation() != null
                        && ship.getLocation() != null) {
                    // 向目标施加力量
                    CombatUtils.applyForce(ship, Misc.getAngleInDegrees(ship.getLocation(), target.getLocation()),
                            30f * ship.getMassWithModules());

                    // 安全播放声音
                    Vector2f shipLoc = ship.getLocation();
                    Vector2f shipVel = ship.getVelocity();
                    if (shipLoc != null && shipVel != null) {
                        Global.getSoundPlayer().playSound("engine_accelerate", 1.2f, 0.8f, shipLoc, shipVel);
                    }

                    // 添加爆炸闪光视觉效果
                    Global.getCombatEngine().addLayeredRenderingPlugin(
                            new ShipExplosionFlareVisual(
                                    new ShipExplosionFlareVisual.ShipExplosionFlareParams(
                                            ship,
                                            EXPLOSION_COLOR,
                                            ship.getShieldRadiusEvenIfNoShield(),
                                            ship.getShieldRadiusEvenIfNoShield())));

                    hasRammed = true;
                }

                // 判断是否应该爆炸
                boolean shouldExplode = false;
                if (controlLossProgress >= 3.5f) {
                    shouldExplode = true;
                }
                if (target != null && target.getLocation() != null && ship.getLocation() != null) {
                    float explosionTriggerRange = ship.getCollisionRadius()
                            + target.getShieldRadiusEvenIfNoShield() + 50f;
                    if (Misc.getDistanceSq(ship.getLocation(), target.getLocation()) <= explosionTriggerRange * explosionTriggerRange) {
                        shouldExplode = true;
                    }
                }

                // 反应堆爆炸！！！
                if (shouldExplode) {
                    if (mine != null && ship.getLocation() != null) {
                        // 计算最终伤害
                        float explosionDamage = ship.getMaxHitpoints() * DAMAGE_MULTIPLIER.get(ship.getHullSize());
                        float baseExplosionRange = EXPLOSION_RADIUS.get(ship.getHullSize());
                        // 爆炸范围 = 基础范围 + 舰船碰撞半径
                        float explosionRange = baseExplosionRange + ship.getCollisionRadius();

                        // 生成主爆炸效果
                        Global.getCombatEngine().spawnExplosion(ship.getLocation(), new Vector2f(0f, 0f),
                                EXPLOSION_COLOR, explosionRange * 1.25f, 3f);

                        // 创建伤害爆炸规格
                        DamagingExplosionSpec explosionSpec = DamagingExplosionSpec.explosionSpecForShip(ship);

                        // 根据爆炸伤害计算粒子数量和大小
                        int particleCount = (int) (explosionDamage * 0.05f);
                        float particleSize = particleCount * 0.02f;
                        float particleSizeRandom = explosionDamage * 0.03f;

                        explosionSpec.setMinDamage(explosionDamage * 0.7f);
                        explosionSpec.setMaxDamage(explosionDamage);
                        explosionSpec.setCoreRadius(explosionRange * 0.6f);
                        explosionSpec.setRadius(explosionRange);
                        explosionSpec.setCollisionClass(CollisionClass.MISSILE_FF);
                        explosionSpec.setCollisionClassByFighter(CollisionClass.MISSILE_FF);
                        explosionSpec.setDamageType(DamageType.HIGH_EXPLOSIVE);

                        explosionSpec.setParticleDuration(3f);
                        explosionSpec.setParticleCount(particleCount);
                        explosionSpec.setParticleSizeMin(particleSize);
                        explosionSpec.setParticleSizeRange(particleSizeRandom);
                        explosionSpec.setParticleColor(Misc.scaleColorSaturate(EXPLOSION_COLOR.brighter(), 0.8f));

                        explosionSpec.setUseDetailedExplosion(false);
                        explosionSpec.setExplosionColor(Misc.scaleColorSaturate(EXPLOSION_COLOR, 0.7f));
                        explosionSpec.setDetailedExplosionRadius(explosionRange);
                        explosionSpec.setDetailedExplosionFlashRadius(0.8f);
                        explosionSpec.setDetailedExplosionFlashColorCore(Color.WHITE);
                        explosionSpec.setDetailedExplosionFlashColorFringe(EXPLOSION_COLOR.darker());
                        explosionSpec.setShowGraphic(true);
                        explosionSpec.setSoundSetId("disabled_large");

                        // 再次添加爆炸闪光视觉效果，范围与爆炸范围一致
                        Global.getCombatEngine().addLayeredRenderingPlugin(
                                new ShipExplosionFlareVisual(
                                        new ShipExplosionFlareVisual.ShipExplosionFlareParams(
                                                ship,
                                                Color.WHITE,
                                                explosionRange,
                                                explosionRange)));

                        // 最终摧毁船只并触发爆炸
                        Global.getCombatEngine().applyDamage(ship, mine.getLocation(), 100000f,
                                DamageType.HIGH_EXPLOSIVE, 0f, true, false, ship, false);
                        ship.splitShip();
                        Global.getCombatEngine().spawnDamagingExplosion(explosionSpec, ship, ship.getLocation(), false); // false表示不分敌我
                        Global.getCombatEngine().removeEntity(mine);
                        ship.removeListener(this);
                        Global.getCombatEngine().removeEntity(ship);
                    }
                }
            }
        }
    }
}
