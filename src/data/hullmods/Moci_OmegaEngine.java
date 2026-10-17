package data.hullmods;

import java.awt.Color;
import java.util.Random;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.combat.listeners.WeaponBaseRangeModifier;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.impl.campaign.ids.Ranks;
import com.fs.starfarer.api.impl.campaign.ids.Skills;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.WeightedRandomPicker;
/**
 * 奥米茄引擎船体插件
 * 功能：
 * 1. 提供100%硬通量消散效率
 * 2. 生成特殊的引擎粒子特效
 * 3. 为所有基础射程低于1000的武器将射程提升至1000
 */
public class
Moci_OmegaEngine extends BaseHullMod {

    // 引擎粒子颜色（金色调）
    private static final Color Engine_color = new Color(170, 147, 255, 255);

    public static final int HARD_FLUX_DISSIPATION_PERCENT = 100;// 硬幅能消散百分比
    public static float PROJECTILE_SPEED_BONUS = 50f; // 弹速加成百分比
    // 最小射程保证
    public static final float MIN_WEAPON_RANGE = 1000f;

    /**
     * 在飞船创建前应用效果
     * @param hullSize 船体尺寸
     * @param stats 可变的飞船状态
     * @param id 插件ID
     */
    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        stats.getHardFluxDissipationFraction().modifyFlat(id, (float)HARD_FLUX_DISSIPATION_PERCENT * 0.01f); // 硬幅能消散百分比
        stats.getBallisticProjectileSpeedMult().modifyPercent(id, PROJECTILE_SPEED_BONUS); // 弹速加成百分比
    }

    /**
     * 获取描述参数
     * @param index 参数索引
     * @param hullSize 船体尺寸
     * @return 参数值字符串
     */
    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        if (index == 0) return "" + HARD_FLUX_DISSIPATION_PERCENT + "%";
        if (index == 1) return "" + (int)MIN_WEAPON_RANGE;
        if (index == 2) return "" + (int)MIN_WEAPON_RANGE;
        if (index == 3) return "" + (int)PROJECTILE_SPEED_BONUS + "%";
        return null;
    }


    @Override
    public void applyEffectsToFighterSpawnedByShip(ShipAPI fighter, ShipAPI ship, String id) {
        // 创建AI核心
        String aiCoreId = "alpha_core";
        PersonAPI aiCore = Global.getFactory().createPerson();
        
        // 安全地获取母舰的faction，参考CjyToolClass的实现
        String factionId = "player"; // 默认值
        if (ship != null && ship.getFleetMember() != null
                && ship.getFleetMember().getFleetData() != null
                && ship.getFleetMember().getFleetData().getFleet() != null
                && ship.getFleetMember().getFleetData().getFleet().getFaction() != null) {
            factionId = ship.getFleetMember().getFleetData().getFleet().getFaction().getId();
        }
        
        aiCore.setFaction(factionId);
        aiCore.setAICoreId(aiCoreId);
        aiCore.setPortraitSprite("graphics/portraits/portrait_ai2b.png");
        aiCore.getStats().setLevel(7);
        aiCore.getStats().setSkillLevel(Skills.TARGET_ANALYSIS, 2);
        aiCore.getStats().setSkillLevel(Skills.HELMSMANSHIP, 2);
        aiCore.getStats().setSkillLevel(Skills.FIELD_MODULATION, 2);
        aiCore.getStats().setSkillLevel(Skills.MISSILE_SPECIALIZATION, 2);
        aiCore.getStats().setSkillLevel(Skills.CYBERNETIC_AUGMENTATION, 1);
        aiCore.getStats().setSkillLevel(Skills.PHASE_CORPS, 1);
        aiCore.getStats().setSkillLevel(Skills.COMBAT_ENDURANCE, 2);
        aiCore.setPersonality("reckless");
        aiCore.setRankId(Ranks.SPACE_CAPTAIN);
        aiCore.setPostId((String)null);
        // 应用AI到战斗机
        fighter.setCaptain(aiCore);

        // 如果AI有导弹专精技能，重置导弹武器弹药
        if (aiCore.getStats().hasSkill("missile_specialization")) {
            for (WeaponAPI weapon : fighter.getAllWeapons()) {
                if (weapon.getType() == WeaponAPI.WeaponType.MISSILE) {
                    weapon.resetAmmo(); // 重置弹药
                }
            }
        }
    }


    /**
     * 在舰船创建后应用效果
     * @param ship 目标飞船
     * @param id 插件ID
     */
    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 添加射程修正监听器
        ship.addListener(new OmegaEngineRangeModifier());
    }

    /**
     * 射程修正监听器类
     */
    public static class OmegaEngineRangeModifier implements WeaponBaseRangeModifier {
        
        @Override
        public float getWeaponBaseRangePercentMod(ShipAPI ship, WeaponAPI weapon) {
            return 0f; // 不使用百分比修正
        }
        
        @Override
        public float getWeaponBaseRangeMultMod(ShipAPI ship, WeaponAPI weapon) {
            return 1f; // 不使用乘数修正
        }
        
        @Override
        public float getWeaponBaseRangeFlatMod(ShipAPI ship, WeaponAPI weapon) {
            // 获取武器基础射程
            float baseRange = weapon.getSpec().getMaxRange();
            
            // 如果基础射程低于1000，返回差值
            if (baseRange < MIN_WEAPON_RANGE) {
                return MIN_WEAPON_RANGE - baseRange;
            }
            
            // 否则不修改射程
            return 0f;
        }
    }

    /**
     * 在战斗中持续更新
     * @param ship 目标飞船
     * @param amount 时间增量
     */
    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        if (Global.getCombatEngine() == null) return;
        if (ship.isAlive()) {
            // 确保粒子监听器存在
            if (!ship.hasListenerOfClass(Moci_Omega_engine_particallistener.class)) {
                ship.addListener(new Moci_Omega_engine_particallistener(ship));
            }
        }
    }

    //======================== 引擎粒子系统 ========================//

    /**
     * 引擎粒子效果监听器
     * 负责定时生成引擎粒子效果
     */
    private class Moci_Omega_engine_particallistener implements AdvanceableListener {
        private final ShipAPI ship;
        private final Vector2f shipVelocity = new Vector2f();
        // 粒子生成间隔控制器（0.1-0.2秒随机）
        private final IntervalUtil interv = new IntervalUtil(0.1f, 0.2f);
        // 加权随机选择器（用于随机选择引擎位置）
        private final WeightedRandomPicker<ShipEngineControllerAPI.ShipEngineAPI> engines = new WeightedRandomPicker<>();

        public Moci_Omega_engine_particallistener(ShipAPI ship) {
            this.ship = ship;
        }

        @Override
        public void advance(float amount) {
            if (!ship.isAlive() || ship.isHulk()) {
                ship.removeListenerOfClass(this.getClass());
                return;
            }

            // 更新飞船速度
            shipVelocity.set(ship.getVelocity());

            // 收集所有引擎位置
            for (ShipEngineControllerAPI.ShipEngineAPI engine : ship.getEngineController().getShipEngines()) {
                engines.add(engine);
            }

            // 按间隔生成粒子
            interv.advance(amount);
            if (interv.intervalElapsed() && !engines.isEmpty()) {
                Vector2f engineLoc = engines.pick().getLocation();
                Global.getCombatEngine().addLayeredRenderingPlugin(
                        new Moci_Omega_engine_partical_1(ship, engineLoc, new Vector2f(shipVelocity))
                );
            }
            engines.clear();
        }
    }

    /**
     * 单个粒子效果实例
     * 处理粒子的生命周期和渲染
     */
    private class Moci_Omega_engine_partical_1 extends BaseCombatLayeredRenderingPlugin {
        private final ShipAPI ship;
        private final Vector2f engine_location;
        private final Vector2f shipVelocity;
        private CombatEngineAPI engine;
        // 粒子生命周期控制器（固定4秒）
        private final IntervalUtil interv = new IntervalUtil(4f, 4f);
        // 粒子属性参数
        private final Random random = new Random();
        private final SpriteAPI particleSprite = Global.getSettings().getSprite("fx", "Moci_engine_partical");
        private final float particleSize;    // 粒子尺寸（20-30像素）

        // 动态参数
        private float rotate_1 = 0f;         // 当前旋转角度
        private float fadeout1 = 100f;       // 透明度（100-0）
        private boolean should_expired = false; // 过期标志
        private Vector2f shiplocation = new Vector2f();

        public Moci_Omega_engine_partical_1(ShipAPI ship, Vector2f engine_location, Vector2f shipVelocity) {
            this.ship = ship;
            this.engine_location = engine_location;
            this.shipVelocity = shipVelocity;
            this.layer = CombatEngineLayers.UNDER_SHIPS_LAYER; // 渲染层（舰船下层）

            // 初始化随机参数
            this.particleSize = 20f + random.nextFloat() * 10f;
        }

        // 暂停判断逻辑
        public boolean shouldPause() {
            return engine == null || engine.isPaused() || !engine.isEntityInPlay(ship);
        }

        @Override
        public float getRenderRadius() {
            return Float.MAX_VALUE; // 始终渲染
        }

        @Override
        public boolean isExpired() {
            return should_expired; // 根据计时器判断是否过期
        }

        @Override
        public void advance(float amount) {
            engine = Global.getCombatEngine();
            interv.advance(amount);

            // 更新时间控制
            if (interv.intervalElapsed()) {
                should_expired = true;
            }

            // 更新飞船位置
            if (ship != null) {
                shiplocation.set(ship.getLocation());
            }

            if (shouldPause()) return;

            // 更新旋转角度
            rotate_1 += 15f * amount;

            // 更新淡出效果
            if (fadeout1 < 3) {
                fadeout1 = 0;
            } else {
                fadeout1 -= 25f * amount; // 线性淡出
            }
        }

        @Override
        public void render(CombatEngineLayers layer, ViewportAPI viewport) {
            // 设置粒子渲染参数
            particleSprite.setAdditiveBlend();       // 叠加混合模式
            particleSprite.setSize(particleSize, particleSize); //粒子尺寸
            particleSprite.setColor(Engine_color);   // 设置预设颜色
            particleSprite.setAngle(rotate_1);  // 设置基础旋转
            particleSprite.setAlphaMult(fadeout1 / 100f); // 设置透明度
            particleSprite.renderAtCenter(engine_location.x, engine_location.y); // 在引擎位置渲染
        }
    }
}