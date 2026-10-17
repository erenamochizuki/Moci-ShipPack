package data.scripts.weapons;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import org.boxutil.base.BaseControlData;
import org.boxutil.base.api.RenderDataAPI;
import org.boxutil.define.BoxEnum;
import org.boxutil.manager.CombatRenderingManager;
import org.boxutil.units.standard.attribute.NodeData;
import org.boxutil.units.standard.entity.CurveEntity;
import org.boxutil.units.standard.entity.SegmentEntity;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.util.Misc;

import de.unkrig.commons.nullanalysis.NotNull;

/**
 * MSN-02 LineBit光束武器效果插件（通用版本）
 * 功能：当光束武器击中敌方目标且距离过远或超出射击角度时，触发模块分离攻击
 * 模块会脱离母舰，自主飞向目标进行攻击，完成后返回母舰
 * 
 * 槽位命名规则：
 * - 武器槽位以 _L 或 _R 结尾（例如：WEAPON_L, WEAPON_R）
 * - 对应的控制模块槽位为 _WPL 或 _WPR（例如：WEAPON_WPL, WEAPON_WPR）
 * - 对应的父级模块槽位为 _LEFT 或 _RIGHT（例如：WEAPON_LEFT, WEAPON_RIGHT）
 * - 或者使用共享父级模块 _MAIN（例如：WEAPON_MAIN），多个武器可共享同一个父级模块
 * - 或者使用装甲模块 _ARMOR（例如：WEAPON_ARMOR），作为备选父级模块
 * 
 * 父级模块查找优先级（互斥关系）：
 * 1. 优先查找 _LEFT 或 _RIGHT（独立父级模块）
 * 2. 如果未找到，则查找 _MAIN（共享父级模块）
 * 3. 如果 _MAIN 也未找到，则查找 _ARMOR（装甲模块）
 * 4. 只判断实际找到的那一个模块的状态
 * 
 * 重构说明：
 * - 移除了 BeamEffectPlugin 接口，简化为只使用 EveryFrameWeaponEffectPlugin
 * - 直接通过 weapon.getBeam() 获取光束目标，无需静态Map共享数据
 * - 代码更简洁，逻辑更清晰，性能更好
 */
public class Moci_MSN_02_LineBit implements EveryFrameWeaponEffectPlugin {
    private static final int RANGE_THRESHOLD = 1000; // 触发模块分离的距离阈值
    private static final float DEFAULT_RETURN_DELAY = 20f; // 默认模块自由活动时间（秒）
    private static final String TAG_PREFIX = "MOCI_LINEBIT_"; // 活动时间标签前缀

    // 连线效果参数配置
    private static final Color CURVE_CORE_COLOR_START = new Color(250, 250, 250, 255);
    private static final Color CURVE_CORE_COLOR_END = new Color(250, 250, 250, 255);
    private static final Color CURVE_FRINGE_COLOR_START = new Color(255, 255, 255, 255);
    private static final Color CURVE_FRINGE_COLOR_END = new Color(255, 255, 255, 255);
    private static final float CURVE_WIDTH_START = 20f;
    private static final float CURVE_WIDTH_END = 20f;
    private static final float CURVE_TEXTURE_SPEED = 0f; // 关闭纹理滚动，获得静态均匀效果
    private static final String CURVE_CORE_SPRITE_PATH = "graphics/fx/Moci_linebeamcore.png";
    private static final String CURVE_FRINGE_SPRITE_PATH = "graphics/fx/Moci_linebeamcore.png";

    private boolean init = false; // 初始化标记
    ShipAPI module = null; // 目标模块引用
    private ShipAPI parentModule = null; // 父级模块引用（_LEFT/_RIGHT 或 _MAIN 或 _ARMOR，互斥关系）
    private boolean weaponDisabled = false; // 武器是否已禁用
    private boolean isRepairing = false; // 模块是否正在修复中

    private static final boolean EMERGENCY = false;

    // 静态Map用于跟踪模块修复状态
    private static final java.util.Map<String, Boolean> MODULE_REPAIR_STATUS = new java.util.HashMap<>();
    private static final java.util.Map<String, Float> MODULE_COOLDOWN_REMAINING = new java.util.HashMap<>();
    private static final float POST_RETURN_COOLDOWN = 2f;

    static {
    }

    public Moci_MSN_02_LineBit() {
    }

    /**
     * EveryFrameWeaponEffectPlugin接口
     * 负责持续控制模块和处理攻击逻辑
     * 直接从光束获取目标信息，无需BeamEffectPlugin
     */
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 引擎为空或暂停时直接返回
        if (engine == null || engine.isPaused()) {
            return;
        }

        // 初始化：根据武器槽位后缀查找对应的模块
        if (!init) {
            String weaponSlotId = weapon.getSlot().getId();
            String targetModuleId = null;
            String parentModuleId = null;
            String sharedParentModuleId = null;
            String armorModuleId = null;

            // 调试日志：记录初始化开始
            // 已移除调试日志以简化代码

            // 通用槽位命名规则：
            // 武器 id 为 weapId
            // 武器槽位以 _L 或 _R 结尾
            // 对应的控制模块为 _WPL 或 _WPR
            // 对应的父级模块为 _LEFT 或 _RIGHT（独立父级）
            // 或者使用 _MAIN（共享父级，多个武器共用）
            // 或者使用 _ARMOR（装甲模块，作为备选父级）
            // 查找优先级：_LEFT/_RIGHT > _MAIN > _ARMOR（互斥关系，只使用找到的第一个）
            if (weaponSlotId.endsWith("_L")) {
                // 提取前缀（去掉 _L）
                String prefix = weaponSlotId.substring(0, weaponSlotId.length() - 2);
                targetModuleId = prefix + "_WPL";
                parentModuleId = prefix + "_LEFT";
                sharedParentModuleId = prefix + "_MAIN";
                armorModuleId = prefix + "_ARMOR";
            } else if (weaponSlotId.endsWith("_R")) {
                // 提取前缀（去掉 _R）
                String prefix = weaponSlotId.substring(0, weaponSlotId.length() - 2);
                targetModuleId = prefix + "_WPR";
                parentModuleId = prefix + "_RIGHT";
                sharedParentModuleId = prefix + "_MAIN";
                armorModuleId = prefix + "_ARMOR";
            } else {
                module = null;
                init = true;
                return;
            }

            // 如果找到了对应的目标模块槽位ID，则搜索该模块
            if (targetModuleId != null) {
                for (ShipAPI childModule : weapon.getShip().getChildModulesCopy()) {
                    if (childModule.getStationSlot() != null
                            && childModule.getStationSlot().getId().equals(targetModuleId)) {
                        this.module = childModule;

                        // 查找父级模块（按优先级，互斥关系）
                        // 优先级1：查找独立父级模块（_LEFT 或 _RIGHT）
                        if (this.parentModule == null) {
                            for (ShipAPI parentMod : weapon.getShip().getChildModulesCopy()) {
                                if (parentMod.getStationSlot() != null
                                        && parentMod.getStationSlot().getId().equals(parentModuleId)) {
                                    this.parentModule = parentMod;
                                    break;
                                }
                            }
                        }

                        // 优先级2：如果未找到独立父级，则查找共享父级模块（_MAIN）
                        if (this.parentModule == null) {
                            for (ShipAPI parentMod : weapon.getShip().getChildModulesCopy()) {
                                if (parentMod.getStationSlot() != null
                                        && parentMod.getStationSlot().getId().equals(sharedParentModuleId)) {
                                    this.parentModule = parentMod;
                                    break;
                                }
                            }
                        }

                        // 优先级3：如果 _MAIN 也未找到，则查找装甲模块（_ARMOR）
                        if (this.parentModule == null) {
                            for (ShipAPI armorMod : weapon.getShip().getChildModulesCopy()) {
                                if (armorMod.getStationSlot() != null
                                        && armorMod.getStationSlot().getId().equals(armorModuleId)) {
                                    this.parentModule = armorMod;
                                    break;
                                }
                            }
                        }

                        init = true;
                        break;
                    }
                }
            }

            init = true;
        }

        // 未找到目标模块则返回
        if (module == null) {
            return;
        }

        // 检查模块状态和武器禁用逻辑
        checkModuleStatus(engine, weapon);

        // 如果武器已禁用，直接返回
        if (weaponDisabled) {
            weapon.setForceNoFireOneFrame(true);
            return;
        }

        // 同步母舰的能量武器伤害加成到模块
        syncEnergyDamageFromParent(weapon);

        // 检查模块是否正在修复中
        String moduleKey = null;
        if (module.getStationSlot() != null) {
            moduleKey = module.getStationSlot().getId();
        } else {
            // 模块分离状态时，根据武器槽位推导模块ID
            String weaponSlotId = weapon.getSlot().getId();
            if (weaponSlotId.endsWith("_L")) {
                String prefix = weaponSlotId.substring(0, weaponSlotId.length() - 2);
                moduleKey = prefix + "_WPL";
            } else if (weaponSlotId.endsWith("_R")) {
                String prefix = weaponSlotId.substring(0, weaponSlotId.length() - 2);
                moduleKey = prefix + "_WPR";
            }
        }
        Boolean repairStatus = MODULE_REPAIR_STATUS.get(moduleKey);
        isRepairing = (repairStatus != null) ? repairStatus : false;
        float cooldownRemaining = tickModuleCooldown(moduleKey, amount);

        // 如果模块正在修复或回收冷却中，不响应出击指令
        if (isRepairing || cooldownRemaining > 0f) {
            return;
        }

        // 只有在武器开火时才处理目标攻击逻辑
        if (!weapon.isFiring()) {
            return; // 武器未开火时直接返回，避免模块误触发
        }

        // 直接从光束获取目标信息（替代BeamEffectPlugin）
        CombatEntityAPI beamTarget = null;
        if (weapon.isBeam()) {
            List<com.fs.starfarer.api.combat.BeamAPI> beams = weapon.getBeams();
            if (!beams.isEmpty()) {
                com.fs.starfarer.api.combat.BeamAPI beam = beams.get(0);
                beamTarget = beam.getDamageTarget();
            }
        }

        // 处理目标攻击逻辑
        if (beamTarget instanceof ShipAPI) {
            ShipAPI targetShip = (ShipAPI) beamTarget;
            WeaponSlotAPI slot = weapon.getSlot();
            String id = "Moci_ModuleStrike" + slot.getId(); // 生成唯一标识符
            ShipAPI source = weapon.getShip();

            // 检查敌对关系
            boolean isEnemy = targetShip.getOwner() + source.getOwner() == 1;

            if (isEnemy) {
                // 检查是否已经触发过模块攻击（避免重复触发）
                boolean alreadyTriggered = source.getCustomData().containsKey(id);

                if (!alreadyTriggered) {
                    // 计算到目标的距离
                    float dist = Misc.getDistance(weapon.getLocation(), beamTarget.getLocation())
                            + beamTarget.getCollisionRadius();

                    // 检查目标是否在舰船朝向的160度范围内（左右各80度）
                    float shipFacing = source.getFacing();
                    float targetAngle = VectorUtils.getAngle(source.getLocation(), beamTarget.getLocation());
                    // 使用MathUtils.getShortestRotation确保角度差计算正确（处理跨越0度的情况）
                    float angleDiff = Math.abs(MathUtils.getShortestRotation(shipFacing, targetAngle));
                    boolean inShipArc = angleDiff <= 80f;

                    // 当距离超过阈值或目标不在舰船射界内时，触发模块分离攻击
                    if (dist > RANGE_THRESHOLD || !inShipArc) {
                        source.setCustomData(id, true); // 标记已触发
                        
                        // 从武器tags获取活动时间
                        float returnDelay = getReturnDelayFromTags(weapon);
                        
                        // 创建模块控制器并添加监听器
                        moduleController controller = new moduleController(id, module, source, weapon,
                                targetShip, returnDelay);
                        module.addListener(controller);
                    } else if (dist <= RANGE_THRESHOLD && inShipArc) {
                        // 目标在射程范围内且在射击角度内时，让模块朝向目标方向
                        float moduleTargetAngle = VectorUtils.getAngle(module.getLocation(), targetShip.getLocation());
                        float currentFacing = module.getFacing();
                        // 使用MathUtils.getShortestRotation计算最短角度差
                        float moduleAngleDiff = MathUtils.getShortestRotation(currentFacing, moduleTargetAngle);

                        // 如果角度差异超过5度，则调整模块朝向
                        if (Math.abs(moduleAngleDiff) > 5f) {
                            float turnRate = module.getMaxTurnRate();
                            float maxTurn = turnRate * Global.getCombatEngine().getElapsedInLastFrame();
                            float turnAmount = Math.min(Math.abs(moduleAngleDiff), maxTurn);

                            // 计算转向方向并应用
                            float turnDirection = Math.signum(moduleAngleDiff);
                            float newFacing = currentFacing + (turnAmount * turnDirection);

                            module.setFacing(newFacing);
                        }
                    }
                }
            }
        }
    }

    /**
     * 从武器tags中获取模块活动时间
     * 查找格式为 MOCI_LINEBIT_X 的tag，其中X为秒数
     * 例如：MOCI_LINEBIT_5 表示5秒，MOCI_LINEBIT_10 表示10秒
     * 如果未找到或解析失败，返回默认值20秒
     */
    private float getReturnDelayFromTags(WeaponAPI weapon) {
        if (weapon == null || weapon.getSpec() == null || weapon.getSpec().getTags() == null) {
            return DEFAULT_RETURN_DELAY;
        }

        // 遍历武器的所有tags
        for (String tag : weapon.getSpec().getTags()) {
            if (tag != null && tag.startsWith(TAG_PREFIX)) {
                try {
                    // 提取数字部分
                    String timeStr = tag.substring(TAG_PREFIX.length());
                    float time = Float.parseFloat(timeStr);
                    
                    // 验证时间范围（1-60秒）
                    if (time >= 1f && time <= 60f) {
                        return time;
                    }
                } catch (NumberFormatException e) {
                    // 解析失败，继续查找下一个tag
                }
            }
        }

        // 未找到有效的tag，返回默认值
        return DEFAULT_RETURN_DELAY;
    }

    private static float tickModuleCooldown(String moduleKey, float amount) {
        if (moduleKey == null) {
            return 0f;
        }

        Float remaining = MODULE_COOLDOWN_REMAINING.get(moduleKey);
        if (remaining == null) {
            return 0f;
        }

        float next = remaining - amount;
        if (next <= 0f) {
            MODULE_COOLDOWN_REMAINING.remove(moduleKey);
            return 0f;
        }

        MODULE_COOLDOWN_REMAINING.put(moduleKey, next);
        return next;
    }

    private static void startModuleCooldown(String moduleKey) {
        if (moduleKey != null) {
            MODULE_COOLDOWN_REMAINING.put(moduleKey, POST_RETURN_COOLDOWN);
        }
    }

    /**
     * 同步母舰的能量武器伤害加成到模块
     * 每帧更新，确保模块继承母舰的能量武器伤害加成
     */
    private void syncEnergyDamageFromParent(WeaponAPI weapon) {
        if (module == null || !module.isAlive()) {
            return;
        }

        // 获取母舰（武器所在的舰船）
        ShipAPI parentShip = weapon.getShip();
        if (parentShip == null || !parentShip.isAlive()) {
            return;
        }

        // 清除模块之前的能量武器伤害加成修正
        module.getMutableStats().getEnergyWeaponDamageMult().unmodify();

        // 应用母舰的能量武器伤害加成到模块
        module.getMutableStats().getEnergyWeaponDamageMult()
                .applyMods(parentShip.getMutableStats().getEnergyWeaponDamageMult());
    }

    /**
     * 检查模块状态并处理武器禁用逻辑
     */
    private void checkModuleStatus(CombatEngineAPI engine, WeaponAPI weapon) {
        boolean shouldDisable = false;

        // 检查控制模块是否死亡
        if (!module.isAlive()) {
            shouldDisable = true;
            /*
            reason = "控制模块死亡";
             */
        }
        // 检查父级模块是否死亡（只判断实际找到的那一个）
        else if (parentModule != null && !parentModule.isAlive()) {
            shouldDisable = true;

            // 如果父级模块死亡，同时杀死控制模块
            if (module.isAlive()) {
                killModule(module);
            }
        }

        // 如果需要禁用武器且还未禁用
        if (shouldDisable && !weaponDisabled) {
            disableWeapon(engine, weapon);
        }
        // 如果不需要禁用但已禁用（模块复活情况，虽然不太可能）
        else if (!shouldDisable && weaponDisabled) {
            enableWeapon(weapon);
        }
    }

    /**
     * 禁用武器并从武器组中移除
     */
    private void disableWeapon(CombatEngineAPI engine, WeaponAPI weapon) {
        weaponDisabled = true;

        // 永久禁用武器（视觉上显示为损坏状态）
        weapon.disable(true);

        // 从武器组中移除（使用安全方法，通过临时全局插件）
        ShipAPI ship = weapon.getShip();
        if (ship != null && engine != null) {
            data.scripts.util.Moci_WeaponGroupSafetyUtil.safelyRemoveWeaponFromGroups(
                engine, ship, weapon, false
            );
        }
    }

    /**
     * 启用武器（理论上不会用到，但为了完整性保留）
     */
    private void enableWeapon(WeaponAPI weapon) {
        weaponDisabled = false;
        
        // 重新启用武器
        weapon.disable(false);
    }

    /**
     * 彻底杀死模块的方法
     * 使用多种方式确保模块完全死亡
     */
    private void killModule(ShipAPI module) {
        if (module == null || !module.isAlive()) {
            return;
        }

        // 设置血量为0
        module.setHitpoints(0f);
        // 应用伤害确保触发死亡逻辑
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine != null) {
            engine.applyDamage(module, module.getLocation(), 1f,
                    com.fs.starfarer.api.combat.DamageType.ENERGY, 0f, false, false, module);
        }
    }

    /**
     * 模块控制器内部类
     * 负责控制分离模块的行为：脱离母舰、攻击目标、返回母舰
     */
    private static class moduleController implements AdvanceableListener {
        private final String id; // 唯一标识符
        private final ShipAPI ship; // 分离的模块舰船
        private final ShipAPI source; // 母舰
        private final WeaponAPI weapon; // 触发武器
        private final ShipAPI target; // 攻击目标
        private final ShipAIPlugin originAI; // 模块原始AI
        private final WeaponSlotAPI stationSlot; // 模块原始停靠位置
        private final SegmentEntity connectionLine; // 连接线视觉效果（SegmentEntity也支持曲线！）
        private final int curveDirection; // 连线弯曲方向：1为右弯，-1为左弯
        private final int strafeDirection; // 侧移方向：1为左侧移，-1为右侧移
        private final float returnDelay; // 模块自由活动时间（从武器tags读取）

        private boolean speedBoosted = false; // 是否已应用速度加成

        /**
         * 模块控制器构造函数
         * 初始化模块分离状态，设置AI、碰撞、武器自动开火等
         * 
         * @param returnDelay 模块自由活动时间（秒），从武器tags中读取
         */
        private moduleController(String id, ShipAPI ship, ShipAPI source, WeaponAPI weapon, ShipAPI target, float returnDelay) {
            this.id = id;
            this.ship = ship;
            this.source = source;
            this.weapon = weapon;
            this.target = target;
            this.originAI = ship.getShipAI(); // 保存原始AI
            this.returnDelay = returnDelay; // 保存活动时间

            // 根据武器槽位确定连线弯曲方向和侧移方向
            String weaponSlotId = weapon.getSlot().getId();
            if (weaponSlotId.endsWith("_L")) {
                this.curveDirection = 1; // L模块向左弯曲
                this.strafeDirection = 1; // L模块向左侧移（保持现有逻辑）
            } else if (weaponSlotId.endsWith("_R")) {
                this.curveDirection = -1; // R模块向右弯曲
                this.strafeDirection = -1; // R模块向右侧移（与L模块相反）
            } else {
                this.curveDirection = -1; // 默认向右弯曲
                this.strafeDirection = 1; // 默认向左侧移
            }

            // 准备模块分离
            ship.ensureClonedStationSlotSpec();
            this.stationSlot = ship.getStationSlot(); // 保存原始停靠位置
            
            ship.setCollisionClass(CollisionClass.FIGHTER); // 设置为战机碰撞类型
            ship.setLayer(CombatEngineLayers.FIGHTERS_LAYER); // 设置渲染层级
            ship.setStationSlot(null); // 脱离停靠位置
            ship.setShipAI(null); // 移除AI控制

            // 设置所有武器组为自动开火
            // 缓存武器组列表，避免在循环中修改集合导致ConcurrentModificationException
            List<WeaponGroupAPI> weaponGroups = ship.getWeaponGroupsCopy();
            int i = 0;
            for (WeaponGroupAPI g : weaponGroups) {
                if (!g.isAutofiring()) {
                    ship.giveCommand(ShipCommand.TOGGLE_AUTOFIRE, g, i);
                }
                i++;
            }
            // 选择武器组
            if (i <= 7) {
                ship.giveCommand(ShipCommand.SELECT_GROUP, i, i);
            }

            this.timer = returnDelay; // 设置自由活动计时器（从武器tags读取）

            // 创建连接线效果（恢复曲线效果）
            this.connectionLine = this.createConnectionLine();
            startConnectionLine(connectionLine);
        }

        private float timer = 0; // 自由活动计时器
        private boolean returned = false; // 是否已返回标记

        /**
         * 模块控制器每帧更新方法
         * 控制模块的攻击和返回行为
         */
        @Override
        public void advance(float amount) {
            // 检查模块或母舰是否死亡
            if (!source.isAlive() || source.isHulk() || !ship.isAlive() || ship.isHulk()) {
                // 模块或母舰死亡时立即清理资源
                if (!returned) {
                    cleanupController("模块或母舰死亡");
                }
                return;
            }

            // 已返回时停止控制
            if (returned) {
                return;
            }

            // 更新连接线位置（带曲线效果）（仅在BoxUtil可用且连接线存在时）
            if (data.scripts.Moci_bounty_plugin.isBoxUtilAvailable() && connectionLine != null) {
                updateConnectionLine(connectionLine, weapon.getLocation(), ship.getLocation(), weapon.getCurrAngle(), 200f,
                        100f, curveDirection);
            }

            // 目标死亡时立即开始返回
            if (!target.isAlive()) {
                timer = 0;
            }

            // 根据计时器状态决定行为
            if (timer > 0) {
                // 自由攻击阶段：向目标飞行并攻击
                timer -= amount;
                flyToTarget(target.getLocation(), target.getLocation(), RANGE_THRESHOLD * 0.45f, RANGE_THRESHOLD,
                        amount,
                        false);
            } else {
                // 返回阶段：应用速度加成并计算拦截路径飞向母舰武器位置
                applyReturnSpeedBoost();
                flyToTarget(
                        getInterceptTargetLocSimple(ship.getMaxSpeed(), ship.getLocation(), weapon.getLocation(),
                                source.getVelocity(), 1),
                        weapon.getLocation(), 0, 0, amount, true);
            }
        }

        /**
         * 简单拦截目标位置计算
         * 预测目标未来位置，用于模块返回时的路径规划
         */
        private Vector2f getInterceptTargetLocSimple(float interceptSpeed, Vector2f location, Vector2f targetLocation,
                Vector2f targetVelocity, int MoreAccuracy) {
            if (interceptSpeed <= 0) {
                return targetLocation;
            } else {
                float dist = Misc.getDistance(location, targetLocation);
                float t = dist / interceptSpeed; // 初始飞行时间估算

                // 精度控制
                if (MoreAccuracy < 0) {
                    MoreAccuracy = 0;
                }

                // 迭代计算更精确的拦截时间
                float t2 = t;
                for (int i = 0; i < MoreAccuracy + 1; i++) {
                    Vector2f newLoc = new Vector2f(targetLocation.x + targetVelocity.x * t2,
                            targetLocation.y + targetVelocity.y * t2);
                    dist = Misc.getDistance(location, newLoc);
                    t = t2;
                    t2 = dist / interceptSpeed;
                }

                // 平均两次计算结果
                t = (t + t2) * 0.5f;
                return new Vector2f(targetLocation.x + targetVelocity.x * t, targetLocation.y + targetVelocity.y * t);
            }
        }

        /**
         * 模块飞行控制方法
         * 根据不同阶段（攻击/返回）控制模块的移动和转向
         */
        private void flyToTarget(Vector2f target, Vector2f trueTarget, float distMin, float distMax, float amount,
                boolean returning) {
            float dist = Misc.getDistance(ship.getLocation(), target);

            if (returning) {
                // 返回阶段：添加物理力辅助归位
                Vector2f slotLocation = weapon.getLocation(); // 槽位位置
                Vector2f moduleLocation = ship.getLocation(); // 模块当前位置

                // 计算从模块到槽位的方向向量
                Vector2f forceDirection = Vector2f.sub(slotLocation, moduleLocation, null);
                float forceDistance = forceDirection.length();

                if (forceDistance > 0) {
                    // 标准化方向向量
                    forceDirection.scale(1f / forceDistance);

                    // 根据距离计算力的强度（距离越远力越大，但有上限）
                    float forceStrength = Math.min(forceDistance * 2f, ship.getMaxSpeed() * 0.5f);

                    // 应用物理力（直接修改速度向量）
                    Vector2f currentVel = new Vector2f(ship.getVelocity());
                    Vector2f forceVel = new Vector2f(forceDirection);
                    forceVel.scale(forceStrength * amount);

                    // 将力添加到当前速度
                    Vector2f.add(currentVel, forceVel, currentVel);

                    // 限制最大速度
                    float maxSpeed = ship.getMaxSpeed();
                    if (currentVel.length() > maxSpeed) {
                        currentVel.normalise();
                        currentVel.scale(maxSpeed);
                    }

                    ship.getVelocity().set(currentVel);
                }

                // 返回阶段：统一处理所有返回逻辑，不分距离
                if (dist < 150) {
                    // 返回阶段：接近母舰时的精确对接
                    float targetFacing = weapon.getCurrAngle(); // 目标朝向（武器角度）
                    float rotation = MathUtils.getShortestRotation(ship.getFacing(), targetFacing);
                    float turnRate = ship.getMaxTurnRate();
                    float facing;

                    // 平滑转向控制
                    if (Math.abs(rotation) > turnRate * amount) {
                        facing = ship.getFacing() + Math.signum(rotation) * turnRate * amount;
                    } else {
                        facing = ship.getFacing() + rotation;
                    }

                    // 根据距离调整速度
                    float vel = Misc.interpolate(75, ship.getVelocity().length(), dist / 200) * amount;

                    // 距离足够近时完成对接
                    if (dist <= vel * 30) {
                        cleanupController("正常返回对接");
                    }

                    // 插值移动到目标位置（与物理力结合实现吸附效果）
                    float p = Math.max(0, (dist - vel) / dist);
                    ship.setFacing(facing);

                    // 保持原有的插值移动逻辑（不减少强度）
                    // 物理力已经在速度层面提供了额外的吸附力，与插值移动形成双重保障
                    ship.getLocation().set(Misc.interpolateVector(trueTarget, ship.getLocation(), p));
                } else {
                    // 返回阶段：距离较远时的简单飞行控制
                    float targetFacing = VectorUtils.getAngle(ship.getLocation(), target);
                    float rotation = MathUtils.getShortestRotation(ship.getFacing(), targetFacing);
                    float turnRate = ship.getMaxTurnRate();
                    float facing;

                    // 平滑转向控制
                    if (Math.abs(rotation) > turnRate * amount) {
                        facing = ship.getFacing() + Math.signum(rotation) * turnRate * amount;
                    } else {
                        facing = ship.getFacing() + rotation;
                    }

                    ship.setFacing(facing);

                    // 返回时使用前进命令配合物理力
                    ship.giveCommand(ShipCommand.ACCELERATE, null, 0);
                }
            } else {
                // 攻击阶段：向目标移动并保持攻击距离
                float targetFacing = VectorUtils.getAngle(ship.getLocation(), target);
                float rotation = MathUtils.getShortestRotation(ship.getFacing(), targetFacing);
                float turnRate = ship.getMaxTurnRate();
                float facing;

                // 平滑转向控制
                if (Math.abs(rotation) > turnRate * amount) {
                    facing = ship.getFacing() + Math.signum(rotation) * turnRate * amount;
                } else {
                    facing = ship.getFacing() + rotation;
                }

                ship.setFacing(facing);

                // 速度方向调整
                float vFacing = VectorUtils.getFacing(ship.getVelocity());
                rotation = MathUtils.getShortestRotation(vFacing, facing);

                // 远距离时的侧移调整（根据模块类型决定方向）
                boolean farDistance = dist > 2 * distMax;
                boolean needRotation = Math.abs(rotation) > 5;

                if (farDistance && needRotation) {
                    if (rotation > 0) {
                        // 目标在右侧时的侧移方向
                        if (strafeDirection > 0) {
                            ship.giveCommand(ShipCommand.STRAFE_LEFT, null, 0); // L模块向左
                        } else {
                            ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0); // R模块向右
                        }
                    } else if (rotation < 0) {
                        // 目标在左侧时的侧移方向
                        if (strafeDirection > 0) {
                            ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0); // L模块向右
                        } else {
                            ship.giveCommand(ShipCommand.STRAFE_LEFT, null, 0); // R模块向左
                        }
                    }
                }

                // 距离控制：保持在最佳攻击距离
                if (dist < distMin) {
                    ship.giveCommand(ShipCommand.ACCELERATE_BACKWARDS, null, 0); // 后退
                } else if (dist > distMax) {
                    ship.giveCommand(ShipCommand.ACCELERATE, null, 0); // 前进
                } else {
                    // 理想攻击距离：减速并根据模块类型侧移
                    ship.giveCommand(ShipCommand.DECELERATE, null, 0); // 减速

                    // 在战斗位置时尝试使用战术系统
                    tryUseShipSystem(ship);

                    if (strafeDirection > 0) {
                        ship.giveCommand(ShipCommand.STRAFE_LEFT, null, 0); // L模块向左侧移
                    } else {
                        ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0); // R模块向右侧移
                    }
                }
            }
        }

        /**
         * 尝试使用战术系统
         * 在理想攻击距离进行侧移时如果战术系统可用则激活
         */
        private void tryUseShipSystem(ShipAPI ship) {
            // 检查是否有战术系统
            if (ship.getSystem() == null) {
                return;
            }

            // 检查战术系统状态：未激活且不在冷却中
            if (ship.getSystem().isActive() || ship.getSystem().isCoolingDown()) {
                return;
            }

            // 检查战术系统是否有充能
            if (ship.getSystem().getAmmo() <= 0 && ship.getSystem().getMaxAmmo() > 0) {
                return;
            }

            // 使用战术系统
            ship.useSystem();
        }

        /**
         * 应用返回阶段的速度加成
         * 在返回阶段给模块100%的最大速度和机动性
         */
        private void applyReturnSpeedBoost() {
            if (speedBoosted) {
                return; // 已经应用过加成，避免重复
            }

            // 应用100%速度和机动性加成
            ship.getMutableStats().getMaxSpeed().modifyPercent(id + "_return_boost", 100f);
            ship.getMutableStats().getAcceleration().modifyPercent(id + "_return_boost", 100f);
            ship.getMutableStats().getDeceleration().modifyPercent(id + "_return_boost", 100f);
            ship.getMutableStats().getMaxTurnRate().modifyPercent(id + "_return_boost", 100f);
            ship.getMutableStats().getTurnAcceleration().modifyPercent(id + "_return_boost", 100f);

            speedBoosted = true;
        }

        /**
         * 移除返回阶段的速度加成
         * 恢复模块的原始性能数据
         */
        private void removeReturnSpeedBoost() {
            if (!speedBoosted) {
                return; // 没有应用过加成，无需移除
            }

            // 移除所有返回阶段的性能加成
            ship.getMutableStats().getMaxSpeed().unmodify(id + "_return_boost");
            ship.getMutableStats().getAcceleration().unmodify(id + "_return_boost");
            ship.getMutableStats().getDeceleration().unmodify(id + "_return_boost");
            ship.getMutableStats().getMaxTurnRate().unmodify(id + "_return_boost");
            ship.getMutableStats().getTurnAcceleration().unmodify(id + "_return_boost");

            speedBoosted = false;
        }

        /**
         * 清理控制器资源
         * 统一处理模块返回、死亡等情况的资源清理
         */
        private void cleanupController(String reason) {
            if (returned) {
                return; // 避免重复清理
            }

            // 恢复模块状态（如果模块还存活）
            if (ship.isAlive()) {
                // 移除返回阶段的速度加成
                removeReturnSpeedBoost();

                ship.setStationSlot(stationSlot); // 恢复停靠位置
                ship.setShipAI(originAI); // 恢复原始AI
                ship.removeListener(this); // 移除监听器

                // 如果是正常对接返回，启动生命值和护甲恢复
                if (reason.equals("正常返回对接")) {
                    startModuleRepair();
                }
            }

            // 清理母舰标记（如果母舰还存活）
            if (source.isAlive()) {
                source.removeCustomData(id); // 清除标记
            }

            // 清理渲染资源（仅在BoxUtil可用时）
            if (data.scripts.Moci_bounty_plugin.isBoxUtilAvailable() && connectionLine != null && !connectionLine.hasDelete()) {
                endConnectionLine(connectionLine);
            }

            returned = true; // 标记已清理
        }

        /**
         * 启动模块修复
         * 在对接成功后的1秒内恢复模块的生命值和护甲
         */
        private void startModuleRepair() {
            String moduleKey = ship.getStationSlot() != null ? ship.getStationSlot().getId() : null;

            // 检查模块是否需要修复
            if (!needsRepair(ship)) {
                startModuleCooldown(moduleKey);
                return;
            }

            // 标记模块为修复中状态
            if (moduleKey != null) {
                MODULE_REPAIR_STATUS.put(moduleKey, true);
            }

            // 创建修复监听器
            ModuleRepairListener repairListener = new ModuleRepairListener(ship, moduleKey);
            ship.addListener(repairListener);
        }

        /**
         * 检查模块是否需要修复
         * 
         * @param module 要检查的模块
         * @return true如果需要修复，false如果已满状态
         */
        private boolean needsRepair(ShipAPI module) {
            // 检查生命值是否满
            float currentHp = module.getHitpoints();
            float maxHp = module.getMaxHitpoints();
            boolean hpFull = (currentHp >= maxHp * 0.99f); // 允许1%的误差

            // 检查护甲是否满
            boolean armorFull = true;
            float maxArmor = module.getArmorGrid().getMaxArmorInCell();

            // 遍历所有护甲格子检查是否都接近满值
            for (int x = 0; x < module.getArmorGrid().getGrid().length; x++) {
                for (int y = 0; y < module.getArmorGrid().getGrid()[x].length; y++) {
                    float currentArmor = module.getArmorGrid().getArmorValue(x, y);
                    if (currentArmor < maxArmor * 0.99f) { // 允许1%的误差
                        armorFull = false;
                        break;
                    }
                }
                if (!armorFull)
                    break;
            }

            boolean needsRepair = !hpFull || !armorFull;

            return needsRepair;
        }

        /**
         * 创建连接线（SegmentEntity支持曲线！）
         * SegmentEntity也支持切线，可以实现曲线效果且更轻量
         */
        private SegmentEntity createConnectionLine() {
            // 如果BoxUtil不可用，返回null
            if (!data.scripts.Moci_bounty_plugin.isBoxUtilAvailable()) {
                return null;
            }

            SegmentEntity segment = new SegmentEntity();

            // 创建连接线的起点和终点节点
            List<NodeData> nodes = new ArrayList<>(2);
            NodeData startNode = new NodeData(0f, 0f, 0f, 0f, 0f, 0f);
            startNode.setColor(CURVE_CORE_COLOR_START);
            startNode.setEmissiveColor(CURVE_FRINGE_COLOR_START);
            startNode.setWidth(CURVE_WIDTH_START);
            NodeData endNode = new NodeData(0f, 0f, 0f, 0f, 0f, 0f);
            endNode.setColor(CURVE_CORE_COLOR_END);
            endNode.setEmissiveColor(CURVE_FRINGE_COLOR_END);
            endNode.setWidth(CURVE_WIDTH_END);

            nodes.add(startNode);
            nodes.add(endNode);
            segment.setNodes(nodes);
            segment.setNodeRefreshAllFromCurrentIndex();
            segment.submitNodes();

            // 设置材质和渲染参数
            SpriteAPI coreSprite = Global.getSettings().getSprite(CURVE_CORE_SPRITE_PATH);
            SpriteAPI fringeSprite = Global.getSettings().getSprite(CURVE_FRINGE_SPRITE_PATH);

            segment.getMaterialData().setDiffuse(coreSprite);
            segment.getMaterialData().setDiffuse(fringeSprite);
            segment.setInterpolation((short) 64);
            segment.setTexturePixels(256f);
            segment.setTextureSpeed(CURVE_TEXTURE_SPEED);
            segment.setLayer(CombatEngineLayers.UNDER_SHIPS_LAYER);

            segment.setFillStartAlpha(0.5f);
            segment.setFillEndAlpha(0.5f);
            segment.setFillStartFactor(1.0f);
            segment.setFillEndFactor(1.0f);

            CombatRenderingManager.addEntity(segment);

            return segment;
        }

        /**
         * 更新连接线位置（SegmentEntity版本，带曲线效果）
         * SegmentEntity也支持切线，可以实现与CurveEntity相同的曲线效果
         */
        private static void updateConnectionLine(SegmentEntity segment, Vector2f start, Vector2f end,
                float mainFacing, float startFactor, float endFactor, int sig) {
            if (segment.hasDelete()) {
                return;
            }

            NodeData startNode = segment.getNodes().get(0);
            NodeData endNode = segment.getNodes().get(1);
            startNode.setLocation(start);
            endNode.setLocation(end);

            // 计算曲线弯曲方向和切线
            float nodeAngle = VectorUtils.getAngle(startNode.getLocation(), endNode.getLocation());
            if (sig == 0) {
                sig = (int) Math.signum(MathUtils.getShortestRotation(mainFacing, nodeAngle));
            }
            float anglePlus90 = mainFacing + 90f * sig;

            // 设置切线来创建曲线效果
            startNode.setTangentLeft(0f, 0f);
            startNode.setTangentRight(MathUtils.getPoint(null, startFactor, anglePlus90));

            endNode.setTangentLeft(MathUtils.getPoint(null, endFactor, mainFacing + 180f));
            endNode.setTangentRight(0f, 0f);

            segment.submitNodes();
        }

        /**
         * 启动连接线渲染
         */
        private static void startConnectionLine(SegmentEntity segment) {
            if (segment.getGlobalTimerState() == BoxEnum.TIMER_IN ||
                    segment.getGlobalTimerState() == BoxEnum.TIMER_FULL) {
                return;
            }
            segment.setControlData(new SelfRenewControlData());
            segment.setGlobalTimer(1f, 1f, 1f);
        }

        /**
         * 结束连接线渲染
         */
        private static void endConnectionLine(SegmentEntity segment) {
            if (segment.getGlobalTimerState() == BoxEnum.TIMER_OUT ||
                    segment.getGlobalTimerState() == BoxEnum.TIMER_INVALID ||
                    segment.getGlobalTimerState() == BoxEnum.TIMER_ONCE) {
                return;
            }
            if (segment.getControlData() instanceof SelfRenewControlData) {
                segment.setControlData(null);
            }
            segment.setGlobalTimer(0f, 0f, 1f);
        }

    }

    /**
     * 自动更新控制数据类
     * 用于控制连接线效果的时间循环
     */
    public static class SelfRenewControlData extends BaseControlData {
        @Override
        public void controlAdvance(@NotNull RenderDataAPI renderEntity, float amount) {
            float[] timer = renderEntity.getGlobalTimer();
            // 时间循环控制：当计时器到达特定值时重置，实现连续效果
            if (timer[0] >= 1 && timer[0] < 2) {
                timer[0] = 2; // 设置为满值状态
            } else if (timer[0] >= 0 && timer[0] < 1) {
                timer[0] = 2 + (1 - timer[0]); // 反向计算，实现循环
            }
        }
    }

    /**
     * 创建曲线连接线（简化版本）
     * 使用默认参数创建连接线效果
     */
    public static CurveEntity createCurveLine(SpriteAPI core, Color coreColorStart, Color coreColorEnd,
            SpriteAPI fringe, Color fringeColorStart, Color fringeColorEnd, float widthStart, float widthEnd,
            float textureSpeed) {
        return createCurveLine(core, coreColorStart, coreColorEnd, fringe, fringeColorStart, fringeColorEnd, widthStart,
                widthEnd, textureSpeed, (short) 64, CombatEngineLayers.ABOVE_PARTICLES_LOWER);
    }

    /**
     * 创建曲线连接线（完整版本）
     * 创建模块与母舰之间的视觉连接线效果
     */
    public static CurveEntity createCurveLine(SpriteAPI core, Color coreColorStart, Color coreColorEnd,
            SpriteAPI fringe, Color fringeColorStart, Color fringeColorEnd, float widthStart, float widthEnd,
            float textureSpeed, short interpolation, CombatEngineLayers layer) {

        CurveEntity curveEntity = new CurveEntity();

        // 创建连接线的起点和终点节点
        List<NodeData> line = new ArrayList<>(2);
        NodeData start = new NodeData(0f, 0f, 0f, 0f, 0f, 0f);
        start.setColor(coreColorStart);
        start.setEmissiveColor(fringeColorStart);
        start.setWidth(widthStart);
        NodeData end = new NodeData(0f, 0f, 0f, 0f, 0f, 0f);
        end.setColor(coreColorEnd);
        end.setEmissiveColor(fringeColorEnd);
        end.setWidth(widthEnd);

        line.add(start);
        line.add(end);
        curveEntity.setNodes(line);
        curveEntity.setNodeRefreshAllFromCurrentIndex();
        curveEntity.submitNodes();

        curveEntity.getMaterialData().setDiffuse(core);
        curveEntity.getMaterialData().setDiffuse(fringe);
        curveEntity.setInterpolation(interpolation);
        curveEntity.setTexturePixels(256f);
        curveEntity.setTextureSpeed(textureSpeed);
        curveEntity.setLayer(CombatEngineLayers.UNDER_SHIPS_LAYER);

        /*
         * curveEntity.setAdditiveBlend();
         */

        curveEntity.setFillStartAlpha(0.5f);
        curveEntity.setFillEndAlpha(0.5f);
        // 均匀填充
        curveEntity.setFillStartFactor(1.0f);
        curveEntity.setFillEndFactor(1.0f);
        CombatRenderingManager.addEntity(curveEntity);

        return curveEntity;
    }

    /**
     * 更新曲线连接线（带弯曲效果）
     * 根据主朝向和因子参数创建弯曲的连接线
     */
    public static void updateCurveLine(CurveEntity curveEntity, Vector2f start, Vector2f end, float mainFacing,
            float startFactor, float endFactor, int sig) {

        if (curveEntity.hasDelete())
            return;

        NodeData startNode = curveEntity.getNodes().get(0);
        NodeData endNode = curveEntity.getNodes().get(1);
        startNode.setLocation(start);
        endNode.setLocation(end);

        float nodeAngle = VectorUtils.getAngle(startNode.getLocation(), endNode.getLocation());
        if (sig == 0)
            sig = (int) Math.signum(MathUtils.getShortestRotation(mainFacing, nodeAngle));
        float anglePlus90 = mainFacing + 90f * sig;

        startNode.setTangentLeft(0f, 0f);
        startNode.setTangentRight(MathUtils.getPoint(null, startFactor, anglePlus90));

        endNode.setTangentLeft(MathUtils.getPoint(null, endFactor, mainFacing + 180f));
        endNode.setTangentRight(0f, 0f);

        curveEntity.submitNodes();
    }

    public static void updateCurveLine(CurveEntity curveEntity, Vector2f start, Vector2f end) {

        if (curveEntity.hasDelete())
            return;

        NodeData startNode = curveEntity.getNodes().get(0);
        NodeData endNode = curveEntity.getNodes().get(1);
        startNode.setLocation(start);
        endNode.setLocation(end);

        curveEntity.submitNodes();
    }

    public static void startCurveLine(CurveEntity curveEntity, float in) {
        if (curveEntity.getGlobalTimerState() == BoxEnum.TIMER_IN)
            return;
        if (curveEntity.getGlobalTimerState() == BoxEnum.TIMER_FULL)
            return;
        curveEntity.setControlData(new SelfRenewControlData());
        curveEntity.setGlobalTimer(in, 1f, in);
    }

    public static void endCurveLine(CurveEntity curveEntity, float out) {
        if (curveEntity.getGlobalTimerState() == BoxEnum.TIMER_OUT)
            return;
        if (curveEntity.getGlobalTimerState() == BoxEnum.TIMER_INVALID)
            return;
        if (curveEntity.getGlobalTimerState() == BoxEnum.TIMER_ONCE)
            return;
        if (curveEntity.getControlData() instanceof SelfRenewControlData) {
            curveEntity.setControlData(null);
        }
        curveEntity.setGlobalTimer(0f, 0f, out);
    }

    public boolean controlAlphaBaseTimer(@NotNull RenderDataAPI renderEntity) {
        return true;
    }

    public boolean controlRemoveBasedTimer(@NotNull RenderDataAPI renderEntity) {
        return true;
    }

    /**
     * 模块修复监听器
     * 在模块对接成功后的1秒内逐渐恢复生命值和护甲
     */
    private static class ModuleRepairListener implements AdvanceableListener {
        private final ShipAPI module;
        private final String moduleKey;
        private final float maxHitpoints;
        private final float maxArmor;
        private float repairTimer = 1.0f; // 修复持续时间1秒
        private boolean repairCompleted = false;

        public ModuleRepairListener(ShipAPI module, String moduleKey) {
            this.module = module;
            this.moduleKey = moduleKey;
            this.maxHitpoints = module.getMaxHitpoints();
            this.maxArmor = module.getArmorGrid().getMaxArmorInCell();
        }

        @Override
        public void advance(float amount) {
            if (repairCompleted || !module.isAlive()) {
                // 修复完成或模块死亡时移除监听器并清除修复状态
                if (moduleKey != null) {
                    MODULE_REPAIR_STATUS.remove(moduleKey);
                }
                module.removeListener(this);
                return;
            }

            repairTimer -= amount;

            if (repairTimer > 0) {
                // 修复进行中：逐渐恢复生命值和护甲
                float repairProgress = (1.0f - repairTimer); // 0到1的修复进度

                // 恢复生命值
                float currentHp = module.getHitpoints();
                float targetHp = maxHitpoints * repairProgress;
                if (currentHp < targetHp) {
                    module.setHitpoints(Math.min(targetHp, maxHitpoints));
                }

                // 恢复护甲（遍历所有护甲格子）
                for (int x = 0; x < module.getArmorGrid().getGrid().length; x++) {
                    for (int y = 0; y < module.getArmorGrid().getGrid()[x].length; y++) {
                        float currentArmor = module.getArmorGrid().getArmorValue(x, y);
                        float targetArmor = maxArmor * repairProgress;
                        if (currentArmor < targetArmor) {
                            module.getArmorGrid().setArmorValue(x, y, Math.min(targetArmor, maxArmor));
                        }
                    }
                }

            } else {
                // 修复完成：确保完全恢复
                module.setHitpoints(maxHitpoints);

                // 完全恢复所有护甲
                for (int x = 0; x < module.getArmorGrid().getGrid().length; x++) {
                    for (int y = 0; y < module.getArmorGrid().getGrid()[x].length; y++) {
                        module.getArmorGrid().setArmorValue(x, y, maxArmor);
                    }
                }

                // 清除修复状态
                if (moduleKey != null) {
                    MODULE_REPAIR_STATUS.remove(moduleKey);
                    startModuleCooldown(moduleKey);
                }

                repairCompleted = true;
                module.removeListener(this);
            }
        }
    }

}
