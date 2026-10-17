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
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.util.Misc;

import de.unkrig.commons.nullanalysis.NotNull;

/**
 * MSN-02 LineBit光束武器效果插件（增强版本）
 * 
 * 新增功能：
 * 1. 挂载时隐藏引擎，发射时显示引擎
 * 2. 发射时使用蛇形曲线动画，随后逐渐变成正常曲线
 * 
 * 基础功能：当光束武器击中敌方目标且距离过远或超出射击角度时，触发模块分离攻击
 * 模块会脱离母舰，自主飞向目标进行攻击，完成后返回母舰
 */
public class Moci_MSN_02_LineBit_Advanced implements EveryFrameWeaponEffectPlugin {
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
    private static final float CURVE_TEXTURE_SPEED = 0f;
    private static final String CURVE_CORE_SPRITE_PATH = "graphics/fx/Moci_linebeamcore.png";
    private static final String CURVE_FRINGE_SPRITE_PATH = "graphics/fx/Moci_linebeamcore.png";

    // 蛇形曲线动画参数
    private static final float SNAKE_ANIMATION_DURATION = 2f; // 蛇形动画持续时间（秒）
    private static final int SNAKE_CURVE_SEGMENTS = 7; // 蛇形曲线的段数（每段需要2个节点）
    private static final float SNAKE_AMPLITUDE = 25f; // 蛇形摆动幅度（垂直于连线方向的偏移距离）
    
    // 弹射效果参数
    private static final float LAUNCH_SPEED = 400f; // 弹射初始速度
    private static final float RETURN_ALIGNMENT_RANGE = 420f; // 返回途中开始尝试对准挂点角度的距离
    private static final float RETURN_DOCKING_RANGE = 240f; // 吸附辅助/精确对接范围
    private static final float RETURN_DOCKING_DISTANCE = 28f; // 实际回收判定距离
    private static final float RETURN_DOCKING_ANGLE_TOLERANCE = 12f; // 实际回收判定角度
    private static final float RETURN_PULL_ACCELERATION = 1800f; // 返回吸附基础加速度
    private static final float RETURN_LATERAL_DAMPING = 7f; // 近距离削掉横向速度
    private static final float RETURN_AWAY_DAMPING = 10f; // 近距离削掉远离挂点的速度
    private static final float RETURN_MIN_APPROACH_SPEED = 85f; // 精确对接最低吸附速度
    private static final float RETURN_MAX_APPROACH_SPEED = 280f; // 精确对接默认最高接近速度

    private boolean init = false;
    ShipAPI module = null;
    private ShipAPI parentModule = null;
    private boolean weaponDisabled = false;
    private boolean isRepairing = false;
    private boolean hasLaunched = false; // 模块是否已经发射过（发射后引擎就不再隐藏）

    // 静态Map用于跟踪模块修复状态
    private static final java.util.Map<String, Boolean> MODULE_REPAIR_STATUS = new java.util.HashMap<>();
    private static final java.util.Map<String, Float> MODULE_COOLDOWN_REMAINING = new java.util.HashMap<>();
    private static final float POST_RETURN_COOLDOWN = 2f;

    public Moci_MSN_02_LineBit_Advanced() {
    }

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }

        // 初始化
        if (!init) {
            initializeModule(weapon);
            init = true;
        }

        if (module == null) {
            return;
        }

        // 引擎控制：只要模块还没发射过，就隐藏引擎
        if (!hasLaunched) {
            hideEngines();
        }

        // 检查模块状态
        checkModuleStatus(engine, weapon);

        if (weaponDisabled) {
            weapon.setForceNoFireOneFrame(true);
            return;
        }

        // 同步能量武器伤害加成
        syncEnergyDamageFromParent(weapon);

        // 检查修复状态
        String moduleKey = getModuleKey(weapon);
        Boolean repairStatus = MODULE_REPAIR_STATUS.get(moduleKey);
        isRepairing = (repairStatus != null) ? repairStatus : false;
        float cooldownRemaining = tickModuleCooldown(moduleKey, amount);

        if (isRepairing || cooldownRemaining > 0f) {
            return;
        }

        // 处理武器开火逻辑
        if (!weapon.isFiring()) {
            return;
        }

        // 获取光束目标
        CombatEntityAPI beamTarget = getBeamTarget(weapon);

        // 处理目标攻击逻辑
        if (beamTarget instanceof ShipAPI) {
            handleTargetAttack(weapon, (ShipAPI) beamTarget);
        }
    }

    /**
     * 隐藏模块引擎
     * 使用 setRenderEngines(false) 直接禁用引擎渲染
     */
    private void hideEngines() {
        if (module == null || !module.isAlive()) {
            return;
        }

        // 直接禁用引擎渲染
        module.setRenderEngines(false);
    }

    /**
     * 初始化模块
     */
    private void initializeModule(WeaponAPI weapon) {
        String weaponSlotId = weapon.getSlot().getId();
        String targetModuleId = null;
        String parentModuleId = null;
        String sharedParentModuleId = null;
        String armorModuleId = null;

        if (weaponSlotId.endsWith("_L")) {
            String prefix = weaponSlotId.substring(0, weaponSlotId.length() - 2);
            targetModuleId = prefix + "_WPL";
            parentModuleId = "ARMBIT".equals(prefix) ? "ARMBIT_SHOULDER_MODULE_L" : prefix + "_LEFT";
            sharedParentModuleId = prefix + "_MAIN";
            armorModuleId = prefix + "_ARMOR";
        } else if (weaponSlotId.endsWith("_R")) {
            String prefix = weaponSlotId.substring(0, weaponSlotId.length() - 2);
            targetModuleId = prefix + "_WPR";
            parentModuleId = "ARMBIT".equals(prefix) ? "ARMBIT_SHOULDER_MODULE_R" : prefix + "_RIGHT";
            sharedParentModuleId = prefix + "_MAIN";
            armorModuleId = prefix + "_ARMOR";
        } else {
            return;
        }

        if (targetModuleId != null) {
            for (ShipAPI childModule : weapon.getShip().getChildModulesCopy()) {
                if (childModule.getStationSlot() != null
                        && childModule.getStationSlot().getId().equals(targetModuleId)) {
                    this.module = childModule;

                    // 查找父级模块
                    findParentModule(weapon, parentModuleId, sharedParentModuleId, armorModuleId);
                    break;
                }
            }
        }
    }

    /**
     * 查找父级模块
     */
    private void findParentModule(WeaponAPI weapon, String parentModuleId, String sharedParentModuleId, String armorModuleId) {
        for (ShipAPI parentMod : weapon.getShip().getChildModulesCopy()) {
            if (parentMod.getStationSlot() != null
                    && parentMod.getStationSlot().getId().equals(parentModuleId)) {
                this.parentModule = parentMod;
                return;
            }
        }

        for (ShipAPI parentMod : weapon.getShip().getChildModulesCopy()) {
            if (parentMod.getStationSlot() != null
                    && parentMod.getStationSlot().getId().equals(sharedParentModuleId)) {
                this.parentModule = parentMod;
                return;
            }
        }

        for (ShipAPI armorMod : weapon.getShip().getChildModulesCopy()) {
            if (armorMod.getStationSlot() != null
                    && armorMod.getStationSlot().getId().equals(armorModuleId)) {
                this.parentModule = armorMod;
                return;
            }
        }
    }

    /**
     * 获取模块键值
     */
    private String getModuleKey(WeaponAPI weapon) {
        if (module.getStationSlot() != null) {
            return module.getStationSlot().getId();
        }

        String weaponSlotId = weapon.getSlot().getId();
        if (weaponSlotId.endsWith("_L")) {
            String prefix = weaponSlotId.substring(0, weaponSlotId.length() - 2);
            return prefix + "_WPL";
        } else if (weaponSlotId.endsWith("_R")) {
            String prefix = weaponSlotId.substring(0, weaponSlotId.length() - 2);
            return prefix + "_WPR";
        }
        return null;
    }

    /**
     * 获取光束目标
     */
    private CombatEntityAPI getBeamTarget(WeaponAPI weapon) {
        if (weapon.isBeam()) {
            List<com.fs.starfarer.api.combat.BeamAPI> beams = weapon.getBeams();
            if (!beams.isEmpty()) {
                com.fs.starfarer.api.combat.BeamAPI beam = beams.get(0);
                return beam.getDamageTarget();
            }
        }
        return null;
    }

    /**
     * 处理目标攻击逻辑
     */
    private void handleTargetAttack(WeaponAPI weapon, ShipAPI targetShip) {
        WeaponSlotAPI slot = weapon.getSlot();
        String id = "Moci_ModuleStrike" + slot.getId();
        ShipAPI source = weapon.getShip();

        boolean isEnemy = targetShip.getOwner() + source.getOwner() == 1;

        if (isEnemy) {
            boolean alreadyTriggered = source.getCustomData().containsKey(id);

            if (!alreadyTriggered) {
                float dist = Misc.getDistance(weapon.getLocation(), targetShip.getLocation())
                        + targetShip.getCollisionRadius();

                float shipFacing = source.getFacing();
                float targetAngle = VectorUtils.getAngle(source.getLocation(), targetShip.getLocation());
                float angleDiff = Math.abs(MathUtils.getShortestRotation(shipFacing, targetAngle));
                boolean inShipArc = angleDiff <= 80f;

                if (dist > RANGE_THRESHOLD || !inShipArc) {
                    source.setCustomData(id, true);
                    
                    // 标记模块已发射，停止隐藏引擎
                    hasLaunched = true;
                    
                    float returnDelay = getReturnDelayFromTags(weapon);
                    
                    moduleController controller = new moduleController(id, module, source, weapon,
                            targetShip, returnDelay, this);
                    module.addListener(controller);
                } else if (dist <= RANGE_THRESHOLD && inShipArc) {
                    float moduleTargetAngle = VectorUtils.getAngle(module.getLocation(), targetShip.getLocation());
                    float currentFacing = module.getFacing();
                    float moduleAngleDiff = MathUtils.getShortestRotation(currentFacing, moduleTargetAngle);

                    if (Math.abs(moduleAngleDiff) > 5f) {
                        float turnRate = module.getMaxTurnRate();
                        float maxTurn = turnRate * Global.getCombatEngine().getElapsedInLastFrame();
                        float turnAmount = Math.min(Math.abs(moduleAngleDiff), maxTurn);

                        float turnDirection = Math.signum(moduleAngleDiff);
                        float newFacing = currentFacing + (turnAmount * turnDirection);

                        module.setFacing(newFacing);
                    }
                }
            }
        }
    }

    /**
     * 从武器tags中获取模块活动时间
     */
    private float getReturnDelayFromTags(WeaponAPI weapon) {
        if (weapon == null || weapon.getSpec() == null || weapon.getSpec().getTags() == null) {
            return DEFAULT_RETURN_DELAY;
        }

        for (String tag : weapon.getSpec().getTags()) {
            if (tag != null && tag.startsWith(TAG_PREFIX)) {
                try {
                    String timeStr = tag.substring(TAG_PREFIX.length());
                    float time = Float.parseFloat(timeStr);
                    
                    if (time >= 1f && time <= 60f) {
                        return time;
                    }
                } catch (NumberFormatException e) {
                    // 继续查找
                }
            }
        }

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
     */
    private void syncEnergyDamageFromParent(WeaponAPI weapon) {
        if (module == null || !module.isAlive()) {
            return;
        }

        ShipAPI parentShip = weapon.getShip();
        if (parentShip == null || !parentShip.isAlive()) {
            return;
        }

        module.getMutableStats().getEnergyWeaponDamageMult().unmodify();
        module.getMutableStats().getEnergyWeaponDamageMult()
                .applyMods(parentShip.getMutableStats().getEnergyWeaponDamageMult());
    }

    /**
     * 检查模块状态并处理武器禁用逻辑
     */
    private void checkModuleStatus(CombatEngineAPI engine, WeaponAPI weapon) {
        boolean shouldDisable = false;

        if (!module.isAlive()) {
            shouldDisable = true;
        } else if (parentModule != null && !parentModule.isAlive()) {
            shouldDisable = true;

            if (module.isAlive()) {
                killModule(module);
            }
        }

        if (shouldDisable && !weaponDisabled) {
            disableWeapon(engine, weapon);
        } else if (!shouldDisable && weaponDisabled) {
            enableWeapon(weapon);
        }
    }

    /**
     * 禁用武器并从武器组中移除
     */
    private void disableWeapon(CombatEngineAPI engine, WeaponAPI weapon) {
        weaponDisabled = true;
        weapon.disable(true);

        ShipAPI ship = weapon.getShip();
        if (ship != null && engine != null) {
            // 使用安全方法移除武器（通过临时全局插件）
            data.scripts.util.Moci_WeaponGroupSafetyUtil.safelyRemoveWeaponFromGroups(
                engine, ship, weapon, false
            );
        }
    }

    /**
     * 启用武器
     */
    private void enableWeapon(WeaponAPI weapon) {
        weaponDisabled = false;
        weapon.disable(false);
    }

    /**
     * 彻底杀死模块
     */
    private void killModule(ShipAPI module) {
        if (module == null || !module.isAlive()) {
            return;
        }

        module.setHitpoints(0f);
        // 应用伤害确保触发死亡逻辑
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine != null) {
            engine.applyDamage(module, module.getLocation(), 1f,
                    com.fs.starfarer.api.combat.DamageType.ENERGY, 0f, false, false, module);
        }
    }

    /**
     * 模块控制器内部类（增强版）
     * 新增功能：蛇形曲线动画效果
     */
    private static class moduleController implements AdvanceableListener {
        private final String id;
        private final ShipAPI ship;
        private final ShipAPI source;
        private final WeaponAPI weapon;
        private final ShipAPI target;
        private final ShipAIPlugin originAI;
        private final WeaponSlotAPI stationSlot;
        private final SegmentEntity connectionLine;
        private final int curveDirection;
        private final int strafeDirection;
        private final float returnDelay;
        private final Moci_MSN_02_LineBit_Advanced parentInstance; // 外部类实例引用

        private boolean speedBoosted = false;
        private float timer = 0;
        private boolean returned = false;
        
        // 蛇形动画相关
        private float snakeAnimationTimer = 0f; // 蛇形动画计时器
        private boolean snakeAnimationActive = true; // 蛇形动画是否激活

        private moduleController(String id, ShipAPI ship, ShipAPI source, WeaponAPI weapon, ShipAPI target, 
                float returnDelay, Moci_MSN_02_LineBit_Advanced parentInstance) {
            this.id = id;
            this.ship = ship;
            this.source = source;
            this.weapon = weapon;
            this.target = target;
            this.originAI = ship.getShipAI();
            this.returnDelay = returnDelay;
            this.parentInstance = parentInstance; // 保存外部类实例引用

            String weaponSlotId = weapon.getSlot().getId();
            if (weaponSlotId.endsWith("_L")) {
                this.curveDirection = 1;
                this.strafeDirection = 1;
            } else if (weaponSlotId.endsWith("_R")) {
                this.curveDirection = -1;
                this.strafeDirection = -1;
            } else {
                this.curveDirection = -1;
                this.strafeDirection = 1;
            }

            ship.ensureClonedStationSlotSpec();
            this.stationSlot = ship.getStationSlot();
            
            ship.setCollisionClass(CollisionClass.FIGHTER);
            ship.setLayer(CombatEngineLayers.FIGHTERS_LAYER);
            ship.setStationSlot(null);
            ship.setShipAI(null);

            // 设置武器自动开火
            List<WeaponGroupAPI> weaponGroups = ship.getWeaponGroupsCopy();
            int i = 0;
            for (WeaponGroupAPI g : weaponGroups) {
                if (!g.isAutofiring()) {
                    ship.giveCommand(ShipCommand.TOGGLE_AUTOFIRE, g, i);
                }
                i++;
            }
            if (i <= 7) {
                ship.giveCommand(ShipCommand.SELECT_GROUP, i, i);
            }

            this.timer = returnDelay;

            // 启用引擎渲染（模块发射后显示引擎）
            ship.setRenderEngines(true);

            // 创建连接线
            this.connectionLine = this.createConnectionLine();
            startConnectionLine(connectionLine);
            
            // 启动蛇形动画
            this.snakeAnimationTimer = SNAKE_ANIMATION_DURATION;
            this.snakeAnimationActive = true;
            
            // 应用弹射效果：给模块一个朝向当前方向的初始速度
            applyLaunchImpulse();
        }
        
        /**
         * 应用弹射效果
         * 在模块分离时给予一个朝向当前方向的初始速度推力
         */
        private void applyLaunchImpulse() {
            float facing = ship.getFacing();
            float speedAmount = LAUNCH_SPEED;
            
            // 计算朝向方向的速度向量
            Vector2f velocity = new Vector2f(
                (float) Math.cos(Math.toRadians(facing)) * speedAmount,
                (float) Math.sin(Math.toRadians(facing)) * speedAmount
            );
            
            // 设置模块的初始速度
            ship.getVelocity().set(velocity);
        }

        @Override
        public void advance(float amount) {
            if (!source.isAlive() || source.isHulk() || !ship.isAlive() || ship.isHulk()) {
                if (!returned) {
                    cleanupController("模块或母舰死亡");
                }
                return;
            }

            if (returned) {
                return;
            }

            // 更新蛇形动画计时器
            if (snakeAnimationActive) {
                snakeAnimationTimer -= amount;
                if (snakeAnimationTimer <= 0f) {
                    snakeAnimationActive = false;
                }
            }

            // 更新连接线（带蛇形动画效果）
            if (data.scripts.Moci_bounty_plugin.isBoxUtilAvailable() && connectionLine != null) {
                updateConnectionLineWithSnake(connectionLine, weapon.getLocation(), ship.getLocation(), 
                        weapon.getCurrAngle(), curveDirection);
            }

            if (!target.isAlive()) {
                timer = 0;
            }

            if (timer > 0) {
                timer -= amount;
                flyToTarget(target.getLocation(), target.getLocation(), RANGE_THRESHOLD * 0.45f, RANGE_THRESHOLD,
                        amount, false);
            } else {
                // 返回阶段：禁止所有武器开火
                disableAllWeapons();
                
                applyReturnSpeedBoost();
                flyToTarget(
                        getInterceptTargetLocSimple(ship.getMaxSpeed(), ship.getLocation(), weapon.getLocation(),
                                source.getVelocity(), 1),
                        weapon.getLocation(), 0, 0, amount, true);
            }
        }
        
        /**
         * 禁用模块上的所有武器（返回阶段防止误伤）
         */
        private void disableAllWeapons() {
            if (ship == null || !ship.isAlive()) {
                return;
            }
            
            // 禁用所有武器组
            List<WeaponGroupAPI> weaponGroups = ship.getWeaponGroupsCopy();
            for (WeaponGroupAPI group : weaponGroups) {
                if (group.isAutofiring()) {
                    // 关闭自动开火
                    ship.giveCommand(ShipCommand.TOGGLE_AUTOFIRE, null, 0);
                }
            }
            
            // 强制所有武器停火
            for (WeaponAPI w : ship.getAllWeapons()) {
                if (w != null) {
                    w.setForceNoFireOneFrame(true);
                }
            }
        }

        /**
         * 更新连接线（带S形蛇形动画效果）
         * 发射时使用S形蛇形曲线，随后逐渐变成直线
         * 每两个节点形成一段，段与段之间首尾相接
         */
        private void updateConnectionLineWithSnake(SegmentEntity segment, Vector2f start, Vector2f end,
                float mainFacing, int sig) {
            if (segment.hasDelete()) {
                return;
            }

            List<NodeData> nodes = segment.getNodes();
            if (nodes == null || nodes.size() < 2) {
                return;
            }

            // 计算动画进度（0到1，0为开始蛇形，1为完全直线）
            float animationProgress = 1f;
            if (snakeAnimationActive && snakeAnimationTimer > 0f) {
                animationProgress = 1f - (snakeAnimationTimer / SNAKE_ANIMATION_DURATION);
            }

            // 计算起点到终点的向量
            Vector2f direction = Vector2f.sub(end, start, null);
            float totalDistance = direction.length();
            
            if (totalDistance < 1f) {
                // 距离太近，直接设置为直线
                for (int i = 0; i < nodes.size(); i++) {
                    NodeData node = nodes.get(i);
                    node.setLocation(start);
                    node.setTangentLeft(0f, 0f);
                    node.setTangentRight(0f, 0f);
                }
                segment.submitNodes();
                return;
            }

            // 标准化方向向量
            direction.scale(1f / totalDistance);
            
            // 计算垂直于连线的方向（用于S形摆动）
            Vector2f perpendicular = new Vector2f(-direction.y, direction.x);
            perpendicular.scale((float) sig); // 根据左右方向调整

            // 计算当前的摆动幅度（发射时最大，随后逐渐减小到0）
            float currentAmplitude = SNAKE_AMPLITUDE * (1f - animationProgress);

            // 计算实际的控制点数量（每段2个节点，所以控制点数 = 节点数/2 + 1）
            int segmentCount = nodes.size() / 2; // 段数
            int controlPointCount = segmentCount + 1; // 控制点数（段数+1）
            
            // 先计算所有控制点的位置
            List<Vector2f> controlPoints = new ArrayList<>(controlPointCount);
            for (int i = 0; i < controlPointCount; i++) {
                // 计算控制点在连线上的位置（0到1）
                float t = (float) i / (controlPointCount - 1);
                
                // 计算控制点在连线上的基础位置
                Vector2f basePos = new Vector2f(
                    start.x + direction.x * totalDistance * t,
                    start.y + direction.y * totalDistance * t
                );
                
                // 计算S形偏移
                float offset = 0f;
                
                // 只有中间控制点才有S形偏移（起点和终点保持直线）
                if (i > 0 && i < controlPointCount - 1) {
                    // 使用正弦波
                    float sineWave = (float) Math.sin(t * Math.PI * 4.0); // 2个完整S形
                    offset = sineWave * currentAmplitude;
                }
                
                // 应用垂直偏移
                Vector2f finalPos = new Vector2f(
                    basePos.x + perpendicular.x * offset,
                    basePos.y + perpendicular.y * offset
                );
                
                controlPoints.add(finalPos);
            }
            
            // 根据控制点设置节点位置（每段的起点和终点）
            // 使用标准贝塞尔曲线切线计算，确保流畅
            for (int segmentIndex = 0; segmentIndex < segmentCount; segmentIndex++) {
                Vector2f segmentStart = controlPoints.get(segmentIndex);
                Vector2f segmentEnd = controlPoints.get(segmentIndex + 1);
                
                // 每段的两个节点
                NodeData startNode = nodes.get(segmentIndex * 2);
                NodeData endNode = nodes.get(segmentIndex * 2 + 1);
                
                // 设置节点位置
                startNode.setLocation(segmentStart);
                endNode.setLocation(segmentEnd);
                
                // 计算段的方向和长度
                Vector2f segmentDir = Vector2f.sub(segmentEnd, segmentStart, null);
                float segmentLength = segmentDir.length();
                
                if (segmentLength > 0) {
                    // 标准贝塞尔曲线切线长度：段长的1/3
                    float tangentLength = segmentLength / 3f;
                    
                    // 切线方向就是段的方向
                    segmentDir.scale(tangentLength / segmentLength);
                    
                    // 设置切线（左右对称）
                    startNode.setTangentLeft(-segmentDir.x, -segmentDir.y);
                    startNode.setTangentRight(segmentDir.x, segmentDir.y);
                    endNode.setTangentLeft(-segmentDir.x, -segmentDir.y);
                    endNode.setTangentRight(segmentDir.x, segmentDir.y);
                } else {
                    startNode.setTangentLeft(0f, 0f);
                    startNode.setTangentRight(0f, 0f);
                    endNode.setTangentLeft(0f, 0f);
                    endNode.setTangentRight(0f, 0f);
                }
            }

            // 标记需要刷新所有节点
            segment.setNodeRefreshAllFromCurrentIndex();
            segment.submitNodes();
        }

        /**
         * 简单拦截目标位置计算
         */
        private Vector2f getInterceptTargetLocSimple(float interceptSpeed, Vector2f location, Vector2f targetLocation,
                Vector2f targetVelocity, int MoreAccuracy) {
            if (interceptSpeed <= 0) {
                return targetLocation;
            } else {
                float dist = Misc.getDistance(location, targetLocation);
                float t = dist / interceptSpeed;

                if (MoreAccuracy < 0) {
                    MoreAccuracy = 0;
                }

                float t2 = t;
                for (int i = 0; i < MoreAccuracy + 1; i++) {
                    Vector2f newLoc = new Vector2f(targetLocation.x + targetVelocity.x * t2,
                            targetLocation.y + targetVelocity.y * t2);
                    dist = Misc.getDistance(location, newLoc);
                    t = t2;
                    t2 = dist / interceptSpeed;
                }

                t = (t + t2) * 0.5f;
                return new Vector2f(targetLocation.x + targetVelocity.x * t, targetLocation.y + targetVelocity.y * t);
            }
        }

        /**
         * 模块飞行控制方法
         */
        private void flyToTarget(Vector2f target, Vector2f trueTarget, float distMin, float distMax, float amount,
                boolean returning) {
            float dist = Misc.getDistance(ship.getLocation(), target);

            if (returning) {
                float distToSlot = Misc.getDistance(ship.getLocation(), trueTarget);

                applyReturnDockingAssist(trueTarget, distToSlot, amount);

                if (distToSlot < RETURN_DOCKING_RANGE) {
                    // 接近母舰时的精确对接
                    float targetFacing = weapon.getCurrAngle();
                    float rotation = MathUtils.getShortestRotation(ship.getFacing(), targetFacing);
                    float turnRate = ship.getMaxTurnRate();
                    float facing;

                    if (Math.abs(rotation) > turnRate * amount) {
                        facing = ship.getFacing() + Math.signum(rotation) * turnRate * amount;
                    } else {
                        facing = ship.getFacing() + rotation;
                    }

                    float rangeProgress = clamp(distToSlot / RETURN_DOCKING_RANGE, 0f, 1f);
                    float currentSpeed = ship.getVelocity().length();
                    float assistedSpeed = Math.max(RETURN_MAX_APPROACH_SPEED, currentSpeed * 0.75f);
                    float vel = Misc.interpolate(RETURN_MIN_APPROACH_SPEED, assistedSpeed, rangeProgress) * amount;
                    float alignAssist = 1f - rangeProgress;
                    float assistedRotation = MathUtils.getShortestRotation(facing, targetFacing) * alignAssist;
                    facing += assistedRotation;
                    float angleAfterTurn = Math.abs(MathUtils.getShortestRotation(facing, targetFacing));
                    float catchDistance = Math.max(RETURN_DOCKING_DISTANCE, currentSpeed * amount * 1.5f);

                    if (distToSlot <= catchDistance
                            && angleAfterTurn <= RETURN_DOCKING_ANGLE_TOLERANCE) {
                        cleanupController("正常返回对接");
                    }

                    float p = distToSlot > 0f ? Math.max(0, (distToSlot - vel) / distToSlot) : 0f;
                    ship.setFacing(facing);
                    ship.getLocation().set(Misc.interpolateVector(trueTarget, ship.getLocation(), p));
                } else {
                    // 距离较远时先追踪回收点，接近后逐渐转向挂点角度
                    float targetFacing = getReturnApproachFacing(target, distToSlot);
                    float rotation = MathUtils.getShortestRotation(ship.getFacing(), targetFacing);
                    float turnRate = ship.getMaxTurnRate();
                    float facing;

                    if (Math.abs(rotation) > turnRate * amount) {
                        facing = ship.getFacing() + Math.signum(rotation) * turnRate * amount;
                    } else {
                        facing = ship.getFacing() + rotation;
                    }

                    ship.setFacing(facing);
                    ship.giveCommand(ShipCommand.ACCELERATE, null, 0);
                }
            } else {
                // 攻击阶段
                float targetFacing = VectorUtils.getAngle(ship.getLocation(), target);
                float rotation = MathUtils.getShortestRotation(ship.getFacing(), targetFacing);
                float turnRate = ship.getMaxTurnRate();
                float facing;

                if (Math.abs(rotation) > turnRate * amount) {
                    facing = ship.getFacing() + Math.signum(rotation) * turnRate * amount;
                } else {
                    facing = ship.getFacing() + rotation;
                }

                ship.setFacing(facing);

                float vFacing = VectorUtils.getFacing(ship.getVelocity());
                rotation = MathUtils.getShortestRotation(vFacing, facing);

                boolean farDistance = dist > 2 * distMax;
                boolean needRotation = Math.abs(rotation) > 5;

                if (farDistance && needRotation) {
                    if (rotation > 0) {
                        if (strafeDirection > 0) {
                            ship.giveCommand(ShipCommand.STRAFE_LEFT, null, 0);
                        } else {
                            ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
                        }
                    } else if (rotation < 0) {
                        if (strafeDirection > 0) {
                            ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
                        } else {
                            ship.giveCommand(ShipCommand.STRAFE_LEFT, null, 0);
                        }
                    }
                }

                if (dist < distMin) {
                    ship.giveCommand(ShipCommand.ACCELERATE_BACKWARDS, null, 0);
                } else if (dist > distMax) {
                    ship.giveCommand(ShipCommand.ACCELERATE, null, 0);
                } else {
                    ship.giveCommand(ShipCommand.DECELERATE, null, 0);
                    tryUseShipSystem(ship);

                    if (strafeDirection > 0) {
                        ship.giveCommand(ShipCommand.STRAFE_LEFT, null, 0);
                    } else {
                        ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
                    }
                }
            }
        }

        private void applyReturnDockingAssist(Vector2f slotLocation, float distToSlot, float amount) {
            Vector2f toSlot = Vector2f.sub(slotLocation, ship.getLocation(), null);
            float forceDistance = toSlot.length();
            if (forceDistance <= 0f) {
                return;
            }

            toSlot.scale(1f / forceDistance);

            Vector2f currentVel = new Vector2f(ship.getVelocity());
            float maxSpeed = ship.getMaxSpeed();
            float dockingProgress = 1f - clamp(distToSlot / RETURN_DOCKING_RANGE, 0f, 1f);
            float forceStrength = Math.max(RETURN_PULL_ACCELERATION, maxSpeed * (5f + dockingProgress * 3f));
            forceStrength = Math.min(forceStrength, Math.max(RETURN_PULL_ACCELERATION, maxSpeed * 8f));

            Vector2f forceVel = new Vector2f(toSlot);
            forceVel.scale(forceStrength * amount);
            Vector2f.add(currentVel, forceVel, currentVel);

            float approachSpeed = currentVel.x * toSlot.x + currentVel.y * toSlot.y;
            Vector2f approachVel = new Vector2f(toSlot);
            approachVel.scale(approachSpeed);
            Vector2f lateralVel = Vector2f.sub(currentVel, approachVel, null);

            float lateralDamp = clamp(amount * (RETURN_LATERAL_DAMPING + dockingProgress * 10f), 0f, 1f);
            lateralVel.scale(1f - lateralDamp);

            if (approachSpeed < 0f) {
                float awayDamp = clamp(amount * (RETURN_AWAY_DAMPING + dockingProgress * 14f), 0f, 1f);
                approachSpeed *= 1f - awayDamp;
            }

            if (dockingProgress > 0f && approachSpeed > 0f) {
                float allowedApproachSpeed = Misc.interpolate(RETURN_MIN_APPROACH_SPEED,
                        Math.max(RETURN_MAX_APPROACH_SPEED, maxSpeed * 0.9f),
                        clamp(distToSlot / RETURN_DOCKING_RANGE, 0f, 1f));
                if (approachSpeed > allowedApproachSpeed) {
                    float approachDamp = clamp(amount * (6f + dockingProgress * 8f), 0f, 1f);
                    approachSpeed = Misc.interpolate(approachSpeed, allowedApproachSpeed, approachDamp);
                }
            }

            approachVel.set(toSlot);
            approachVel.scale(approachSpeed);
            Vector2f.add(approachVel, lateralVel, currentVel);

            float maxReturnSpeed = Math.max(maxSpeed * 1.15f, RETURN_MAX_APPROACH_SPEED);
            if (currentVel.length() > maxReturnSpeed) {
                currentVel.normalise();
                currentVel.scale(maxReturnSpeed);
            }

            ship.getVelocity().set(currentVel);
        }

        private float getReturnApproachFacing(Vector2f target, float distToSlot) {
            float approachFacing = VectorUtils.getAngle(ship.getLocation(), target);
            if (distToSlot >= RETURN_ALIGNMENT_RANGE) {
                return approachFacing;
            }

            float dockingFacing = weapon.getCurrAngle();
            float alignProgress = 1f - (distToSlot / RETURN_ALIGNMENT_RANGE);
            alignProgress = Math.max(0f, Math.min(1f, alignProgress));
            float rotation = MathUtils.getShortestRotation(approachFacing, dockingFacing);
            return approachFacing + rotation * alignProgress;
        }

        private static float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }

        /**
         * 尝试使用战术系统
         */
        private void tryUseShipSystem(ShipAPI ship) {
            if (ship.getSystem() == null) {
                return;
            }

            if (ship.getSystem().isActive() || ship.getSystem().isCoolingDown()) {
                return;
            }

            if (ship.getSystem().getAmmo() <= 0 && ship.getSystem().getMaxAmmo() > 0) {
                return;
            }

            ship.useSystem();
        }

        /**
         * 应用返回阶段的速度加成
         */
        private void applyReturnSpeedBoost() {
            if (speedBoosted) {
                return;
            }

            ship.getMutableStats().getMaxSpeed().modifyPercent(id + "_return_boost", 100f);
            ship.getMutableStats().getAcceleration().modifyPercent(id + "_return_boost", 100f);
            ship.getMutableStats().getDeceleration().modifyPercent(id + "_return_boost", 100f);
            ship.getMutableStats().getMaxTurnRate().modifyPercent(id + "_return_boost", 100f);
            ship.getMutableStats().getTurnAcceleration().modifyPercent(id + "_return_boost", 100f);

            speedBoosted = true;
        }

        /**
         * 移除返回阶段的速度加成
         */
        private void removeReturnSpeedBoost() {
            if (!speedBoosted) {
                return;
            }

            ship.getMutableStats().getMaxSpeed().unmodify(id + "_return_boost");
            ship.getMutableStats().getAcceleration().unmodify(id + "_return_boost");
            ship.getMutableStats().getDeceleration().unmodify(id + "_return_boost");
            ship.getMutableStats().getMaxTurnRate().unmodify(id + "_return_boost");
            ship.getMutableStats().getTurnAcceleration().unmodify(id + "_return_boost");

            speedBoosted = false;
        }

        /**
         * 清理控制器资源
         */
        private void cleanupController(String reason) {
            if (returned) {
                return;
            }

            if (ship.isAlive()) {
                removeReturnSpeedBoost();

                // 复位引擎渲染状态：返回后隐藏引擎
                ship.setRenderEngines(false);
                
                // 重置外部类的 hasLaunched 标记
                parentInstance.hasLaunched = false;

                ship.setStationSlot(stationSlot);
                ship.setShipAI(originAI);
                ship.removeListener(this);

                if (reason.equals("正常返回对接")) {
                    startModuleRepair();
                }
            }

            if (source.isAlive()) {
                source.removeCustomData(id);
            }

            if (data.scripts.Moci_bounty_plugin.isBoxUtilAvailable() && connectionLine != null && !connectionLine.hasDelete()) {
                endConnectionLine(connectionLine);
            }

            returned = true;
        }

        /**
         * 启动模块修复
         */
        private void startModuleRepair() {
            String moduleKey = ship.getStationSlot() != null ? ship.getStationSlot().getId() : null;

            if (!needsRepair(ship)) {
                startModuleCooldown(moduleKey);
                return;
            }

            if (moduleKey != null) {
                MODULE_REPAIR_STATUS.put(moduleKey, true);
            }

            ModuleRepairListener repairListener = new ModuleRepairListener(ship, moduleKey);
            ship.addListener(repairListener);
        }

        /**
         * 检查模块是否需要修复
         */
        private boolean needsRepair(ShipAPI module) {
            float currentHp = module.getHitpoints();
            float maxHp = module.getMaxHitpoints();
            boolean hpFull = (currentHp >= maxHp * 0.99f);

            boolean armorFull = true;
            float maxArmor = module.getArmorGrid().getMaxArmorInCell();

            for (int x = 0; x < module.getArmorGrid().getGrid().length; x++) {
                for (int y = 0; y < module.getArmorGrid().getGrid()[x].length; y++) {
                    float currentArmor = module.getArmorGrid().getArmorValue(x, y);
                    if (currentArmor < maxArmor * 0.99f) {
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
         * 创建连接线（多节点S形蛇形曲线）
         * SegmentEntity需要偶数个节点（每两个节点形成一段）
         * 要渲染N段连续的线，需要2*N个节点，每段的终点是下一段的起点
         */
        private SegmentEntity createConnectionLine() {
            if (!data.scripts.Moci_bounty_plugin.isBoxUtilAvailable()) {
                return null;
            }

            SegmentEntity segment = new SegmentEntity();

            // 创建多节点的S形曲线
            // SegmentEntity需要偶数个节点，每两个节点形成一段
            // SNAKE_CURVE_SEGMENTS=7段，需要14个节点（每段2个节点）
            int totalNodes = SNAKE_CURVE_SEGMENTS * 2; // 14个节点
            List<NodeData> nodes = new ArrayList<>(totalNodes);
            
            // 初始化所有节点（位置会在update中动态计算）
            for (int i = 0; i < totalNodes; i++) {
                NodeData node = new NodeData(0f, 0f, 0f, 0f, 0f, 0f);
                
                // 根据位置设置颜色（起点到终点渐变）
                // 每两个节点是一段，所以颜色按段来计算
                float t = (float) (i / 2) / (SNAKE_CURVE_SEGMENTS - 1);
                Color nodeColor = interpolateColor(CURVE_CORE_COLOR_START, CURVE_CORE_COLOR_END, t);
                Color nodeFringe = interpolateColor(CURVE_FRINGE_COLOR_START, CURVE_FRINGE_COLOR_END, t);
                float nodeWidth = Misc.interpolate(CURVE_WIDTH_START, CURVE_WIDTH_END, t);
                
                node.setColor(nodeColor);
                node.setEmissiveColor(nodeFringe);
                node.setWidth(nodeWidth);
                
                nodes.add(node);
            }
            
            segment.setNodes(nodes);
            segment.setNodeRefreshAllFromCurrentIndex();
            segment.submitNodes();

            // 设置材质和渲染参数
            SpriteAPI coreSprite = Global.getSettings().getSprite(CURVE_CORE_SPRITE_PATH);
            SpriteAPI fringeSprite = Global.getSettings().getSprite(CURVE_FRINGE_SPRITE_PATH);

            segment.getMaterialData().setDiffuse(coreSprite);
            segment.getMaterialData().setDiffuse(fringeSprite);
            
            // 设置插值精度（32是平滑和性能的平衡点）
            segment.setInterpolation((short) 32);
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
         * 颜色插值辅助方法
         */
        private static Color interpolateColor(Color start, Color end, float t) {
            int r = (int) Misc.interpolate(start.getRed(), end.getRed(), t);
            int g = (int) Misc.interpolate(start.getGreen(), end.getGreen(), t);
            int b = (int) Misc.interpolate(start.getBlue(), end.getBlue(), t);
            int a = (int) Misc.interpolate(start.getAlpha(), end.getAlpha(), t);
            return new Color(r, g, b, a);
        }

        /**
         * 启动连接线渲染（无渐入时间，立即显示）
         */
        private static void startConnectionLine(SegmentEntity segment) {
            if (segment.getGlobalTimerState() == BoxEnum.TIMER_IN ||
                    segment.getGlobalTimerState() == BoxEnum.TIMER_FULL) {
                return;
            }
            segment.setControlData(new SelfRenewControlData());
            // 直接设置为完全显示状态，无渐入时间
            segment.setGlobalTimer(0f, 1f, 0f);
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
     */
    public static class SelfRenewControlData extends BaseControlData {
        @Override
        public void controlAdvance(@NotNull RenderDataAPI renderEntity, float amount) {
            float[] timer = renderEntity.getGlobalTimer();
            if (timer[0] >= 1 && timer[0] < 2) {
                timer[0] = 2;
            } else if (timer[0] >= 0 && timer[0] < 1) {
                timer[0] = 2 + (1 - timer[0]);
            }
        }
    }

    /**
     * 模块修复监听器
     */
    private static class ModuleRepairListener implements AdvanceableListener {
        private final ShipAPI module;
        private final String moduleKey;
        private final float maxHitpoints;
        private final float maxArmor;
        private float repairTimer = 1.0f;
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
                if (moduleKey != null) {
                    MODULE_REPAIR_STATUS.remove(moduleKey);
                }
                module.removeListener(this);
                return;
            }

            repairTimer -= amount;

            if (repairTimer > 0) {
                float repairProgress = (1.0f - repairTimer);

                float currentHp = module.getHitpoints();
                float targetHp = maxHitpoints * repairProgress;
                if (currentHp < targetHp) {
                    module.setHitpoints(Math.min(targetHp, maxHitpoints));
                }

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
                module.setHitpoints(maxHitpoints);

                for (int x = 0; x < module.getArmorGrid().getGrid().length; x++) {
                    for (int y = 0; y < module.getArmorGrid().getGrid()[x].length; y++) {
                        module.getArmorGrid().setArmorValue(x, y, maxArmor);
                    }
                }

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
