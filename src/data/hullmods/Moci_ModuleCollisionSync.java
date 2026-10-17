package data.hullmods;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.loading.DamagingExplosionSpec;
import data.scripts.util.Moci_TextLoader;

/**
 * Moci_ModuleCollisionSync - 模块碰撞同步器
 * 
 * 专门用于同步模块舰船与母舰的碰撞类型，并提供模块保护功能
 * 解决模块在各种功能中需要单独处理碰撞同步的问题
 * 
 * 核心功能：
 * - 自动检测当前舰船是否为模块
 * - 每帧实时同步母舰的碰撞类型到模块
 * - 提供模块脱离保护和殉爆伤害减免
 * - 安全的母舰状态验证
 * 
 * 使用场景：
 * - 着陆AI系统中的碰撞切换
 * - 机动战士ID卡的碰撞修改
 * - 其他需要模块跟随母舰碰撞状态的功能
 * 
 * 技术特点：
 * - 每帧直接同步，无延迟响应
 * - 完整的安全检查，防止空指针异常
 * - 自动识别模块身份，无需手动配置
 * - 集成殉爆和传感器保护功能
 */
public class Moci_ModuleCollisionSync extends BaseHullMod {
    private static final String TEXT_ID = "Moci_ModuleCollisionSync";
    public static float PROFILE_MULT = 0.1f; // 传感器信号乘数
    public static final float DAMAGE_MULT = 0f; // 无殉爆伤害

    // 护卫舰特殊规则相关常量（继承自机动兵器ID卡）
    public static float FRIGATE_PD_DAMAGE_MULT = 1.5f; // 点防伤害乘数

    // 数据键定义
    private static final String FRIGATE_RULES_KEY = "moci_module_frigate_rules_active";
    
    // 模块隐藏相关常量
    public static final String MODULE_HIDE_TIMER_KEY = "moci_module_hide_timer";
    public static final float MODULE_HIDE_DURATION = 1f; // 隐藏持续时间（秒）
    
    // 整备期间模块隐藏标记
    public static final String MODULE_REPAIRING_KEY = "moci_module_repairing";
    static final String MODULE_HIDE_ALPHA_KEY = "moci_module_hide_original_alpha";

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 模块脱离几率增加100%
        stats.getDynamic().getStat(Stats.MODULE_DETACH_CHANCE_MULT).modifyFlat(id, 100f);
        // 殉爆伤害减免
        stats.getDynamic().getStat(Stats.EXPLOSION_DAMAGE_MULT).modifyMult(id, DAMAGE_MULT);
        // EMP伤害免疫
        stats.getEmpDamageTakenMult().modifyMult(id, 0f);
        // 传感器信号降低
        stats.getSensorProfile().modifyMult(id, PROFILE_MULT);
    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 检查母舰是否为护卫舰且安装了机动兵器ID卡
        ShipAPI parentShip = ship.getParentStation();
        if (parentShip != null && parentShip.getHullSize() == HullSize.FRIGATE &&
                parentShip.getVariant().hasHullMod("Moci_MobileSuitsIDcard")) {

            // 检查母舰是否应该应用护卫舰特殊规则
            if (shouldParentApplyFrigateRules(parentShip)) {
                // 标记模块需要应用护卫舰规则
                ship.setCustomData(FRIGATE_RULES_KEY, true);
            }
        }

        // 添加殉爆伤害监听器
        if (!ship.hasListenerOfClass(ExplosionDamageListener.class)) {
            ship.addListener(new ExplosionDamageListener());
        }
    }

    /**
     * 检查母舰是否应该应用护卫舰特殊规则
     * 复制自Moci_MobileSuitsIDcard的逻辑
     */
    private boolean shouldParentApplyFrigateRules(ShipAPI parentShip) {
        // 检查船体尺寸是否为护卫舰
        if (parentShip.getHullSize() != HullSize.FRIGATE) {
            return false;
        }

        // 检查是否安装了禁用特殊规则的船插
        String[] disableHullmods = { "Moci_EinstWeapon", "Moci_SuperAlloyArmor", "Moci_PsycommuSystem" };
        for (String hullMod : disableHullmods) {
            if (parentShip.getVariant().hasHullMod(hullMod)) {
                return false;
            }
        }

        // 检查是否安装了完全禁用的船插
        String[] disableAllHullmods = { "strikeCraft", "armaa_strikeCraftFrig" };
        for (String hullMod : disableAllHullmods) {
            if (parentShip.getVariant().hasHullMod(hullMod)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        // 基础安全检查
        if (ship == null || !ship.isAlive()) {
            return;
        }

        // 检查是否为模块舰船
        if (!ship.isStationModule()) {
            return; // 不是模块，无需同步
        }

        // 获取父舰船（母舰）
        ShipAPI parentShip = ship.getParentStation();

        // 只在“父舰已经死亡”时强制杀死模块。
        // 不能把 parent 为 null、hulk 或其它无效态都当成模块自毁条件。
        if (parentShip == null) {
            return;
        }
        if (!parentShip.isAlive() || parentShip.getHitpoints() <= 0f) {
            if (ship.isAlive() && ship.getHitpoints() > 0f) {
                ship.setHitpoints(0f);
            }
            return;
        }

        // 每帧直接同步碰撞类型：将模块的碰撞类型设置为与母舰相同
        if (ship.getCollisionClass() != parentShip.getCollisionClass()) {
            ship.setCollisionClass(parentShip.getCollisionClass());
        }
        
        // 处理模块隐藏逻辑
        handleModuleHiding(ship, amount);
    }
    
    /**
     * 处理模块隐藏逻辑
     * 支持两种模式：
     * 1. 定时隐藏（MODULE_HIDE_TIMER_KEY）- 用于短时间隐藏（如第一次起飞）
     * 2. 整备隐藏（MODULE_REPAIRING_KEY）- 用于整备期间持续隐藏
     * 
     * 技术说明：
     * - 使用 setExtraAlphaMult() 而不是 setAlphaMult()
     * - 因为着陆/起飞动画会覆盖 alphaMult 的值
     * - extraAlphaMult 在动画计算之后应用，不会被覆盖
     * 
     * @param ship 模块舰船
     * @param amount 时间增量
     */
    private void handleModuleHiding(ShipAPI ship, float amount) {
        boolean shouldHide = false;
        
        // 检查是否在整备中（优先级更高）
        Object repairingObj = ship.getCustomData().get(MODULE_REPAIRING_KEY);
        if (repairingObj instanceof Boolean && (Boolean) repairingObj) {
            shouldHide = true;
        }
        
        // 检查是否有定时隐藏
        Object timerObj = ship.getCustomData().get(MODULE_HIDE_TIMER_KEY);
        if (timerObj instanceof Float) {
            float timer = (Float) timerObj;
            
            if (timer > 0f) {
                shouldHide = true;
                
                // 更新计时器
                timer -= amount;
                ship.setCustomData(MODULE_HIDE_TIMER_KEY, timer);
                
                // 如果计时器结束，清除标记
                if (timer <= 0f) {
                    ship.removeCustomData(MODULE_HIDE_TIMER_KEY);
                }
            }
        }
        
        // 应用隐藏状态
        // 使用 setExtraAlphaMult 而不是 setAlphaMult，避免被着陆动画覆盖
        if (shouldHide) {
            if (!ship.getCustomData().containsKey(MODULE_HIDE_ALPHA_KEY)) {
                ship.setCustomData(MODULE_HIDE_ALPHA_KEY, ship.getExtraAlphaMult());
            }
            ship.setExtraAlphaMult(0f);
        } else {
            Object originalAlpha = ship.getCustomData().get(MODULE_HIDE_ALPHA_KEY);
            if (originalAlpha instanceof Number) {
                ship.setExtraAlphaMult(((Number) originalAlpha).floatValue());
                ship.removeCustomData(MODULE_HIDE_ALPHA_KEY);
            }
        }
    }
    
    /**
     * 启动模块隐藏（定时模式）
     * 由Moci_MSLaunchTravelDrive调用，用于短时间隐藏
     * 
     * @param ship 模块舰船
     */
    public static void startModuleHiding(ShipAPI ship) {
        if (ship != null && ship.isStationModule()) {
            ship.setCustomData(MODULE_HIDE_TIMER_KEY, MODULE_HIDE_DURATION);
        }
    }
    
    /**
     * 开始整备期间的模块隐藏
     * 由Moci_LandingAI调用，在整备期间持续隐藏
     * 
     * @param ship 模块舰船
     */
    public static void startRepairingHiding(ShipAPI ship) {
        if (ship != null && ship.isStationModule()) {
            ship.setCustomData(MODULE_REPAIRING_KEY, true);
        }
    }
    
    /**
     * 结束整备期间的模块隐藏
     * 由Moci_LandingAI调用，起飞完成后恢复显示
     * 
     * @param ship 模块舰船
     */
    public static void endRepairingHiding(ShipAPI ship) {
        if (ship != null && ship.isStationModule()) {
            ship.removeCustomData(MODULE_REPAIRING_KEY);
        }
    }

    public static class ExplosionDamageListener implements DamageTakenModifier {
        private boolean isAreaExplosion(Object param) {
            if (!(param instanceof DamagingProjectileAPI)) {
                return false;
            }
            DamagingProjectileAPI projectile = (DamagingProjectileAPI) param;
            return projectile.getExplosionSpecIfExplosion() != null;
        }

        private void markParentAndSiblingModulesAsAlreadyHit(ShipAPI ship, DamagingProjectileAPI explosion) {
            if (ship == null || explosion == null || !ship.isStationModule()) {
                return;
            }

            ShipAPI parentShip = ship.getParentStation();
            if (parentShip == null) {
                return;
            }

            explosion.addDamagedAlready(parentShip);
            for (ShipAPI module : parentShip.getChildModulesCopy()) {
                if (module == null || module == ship) {
                    continue;
                }
                explosion.addDamagedAlready(module);
            }
        }

        @Override
        public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage, Vector2f point,
                boolean shieldHit) {
            if (target instanceof ShipAPI) {
                ShipAPI ship = (ShipAPI) target;
                DamagingProjectileAPI projectile = param instanceof DamagingProjectileAPI
                        ? (DamagingProjectileAPI) param
                        : null;
                boolean areaExplosion = projectile != null && projectile.getExplosionSpecIfExplosion() != null;

                if (ship.isStationModule() && areaExplosion) {
                    markParentAndSiblingModulesAsAlreadyHit(ship, projectile);
                }

                // 检查是否启用了护卫舰规则（基于母舰状态）
                boolean hasFrigateRules = (ship.getCustomData().get(FRIGATE_RULES_KEY) != null);

                if (hasFrigateRules) {
                    // 检查点防武器攻击
                    if (projectile != null) {
                        WeaponAPI weapon = projectile.getWeapon();

                        // 检查武器是否有PD提示或者发射源舰船的军官有点防专精
                        boolean isPDAttack = false;

                        // 检查武器是否有PD提示
                        if (weapon != null && weapon.hasAIHint(WeaponAPI.AIHints.PD)) {
                            isPDAttack = true;
                        }

                        // 检查发射源舰船的军官是否有点防专精
                        if (projectile.getSource() != null && projectile.getSource().getCaptain() != null) {
                            PersonAPI captain = projectile.getSource().getCaptain();
                            if (captain.getStats().getSkillLevel("point_defense") > 0) {
                                isPDAttack = true;
                            }
                        }

                        // 如果是点防攻击，增加50%伤害
                        if (isPDAttack) {
                            damage.getModifier().modifyMult(this.getClass().getName() + "_pd", FRIGATE_PD_DAMAGE_MULT);
                            return Moci_TextLoader.getText(TEXT_ID, "combat.pd_attack_inherited");
                        }
                    }
                }

                // 处理殉爆伤害
                if (projectile != null) {
                    // 检查source是否为null，防止空指针异常
                    if (projectile.getSource() != null) {
                        float explosionRadius = DamagingExplosionSpec.getShipExplosionRadius(projectile.getSource());
                        if (projectile.getCollisionRadius() == explosionRadius) {
                            // 殉爆伤害减免
                            damage.getModifier().modifyMult(this.getClass().getName(), 0f);
                        }
                    }
                }

                // 处理碰撞伤害 - 动能伤害且param为null才是碰撞伤害
                // 但是需要确保目标是模块舰船才应用此效果
                if (param == null && damage.getType() == DamageType.KINETIC && ship.isStationModule()) {
                    // 碰撞伤害减免
                    damage.getModifier().modifyMult(this.getClass().getName() + "_collision", 0f);
                }
            }
            return null;
        }
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        switch (index) {
            case 0:
                return Moci_TextLoader.getText(TEXT_ID, "description.param_module");
            case 1:
                return Moci_TextLoader.getText(TEXT_ID, "description.param_parent");
            case 2:
                return Moci_TextLoader.getText(TEXT_ID, "description.param_collision_type");
            case 3:
                return "" + (int) Math.round((1f - PROFILE_MULT) * 100f) + "%"; // 信号降低
            case 4:
                return "" + (int) ((FRIGATE_PD_DAMAGE_MULT - 1f) * 100f) + "%"; // 点防增伤
            default:
                return null;
        }
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        // 只对模块舰船生效
        return ship != null && ship.isStationModule();
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        if (ship == null) {
            return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_invalid_ship");
        }
        if (!ship.isStationModule()) {
            return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_module_only");
        }
        return null;
    }
}
