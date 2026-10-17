package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.util.Misc;
import org.boxutil.units.standard.attribute.NodeData;
import org.boxutil.units.standard.entity.SegmentEntity;
import org.boxutil.util.CurveUtil;
import org.lwjgl.util.vector.Vector2f;
import org.lwjgl.util.vector.Vector3f;

import java.awt.Color;
import java.util.Random;

/**
 * 曲线光束武器控制器
 * 基于 BoxUtil 的 CurveEntity 系统实现贝塞尔曲线光束攻击
 * 参考 Moci_HSITunnelController 的目标识别逻辑
 */
public class Moci_CurvedBeamWeaponController implements EveryFrameWeaponEffectPlugin {

    // 武器参数
    private static final float BEAM_DURATION = 1.5f; // 光束持续时间
    private static final float BEAM_WIDTH = 8.0f; // 光束宽度
    private static final float CURVE_INTENSITY = 0.3f; // 曲线弯曲强度
    private static final float RANDOM_OFFSET_RANGE = 50.0f; // 随机偏移范围
    private static final int CURVE_INTERPOLATION = 32; // 曲线插值精度
    private static final float TEXTURE_SPEED = 150.0f; // 纹理流动速度

    // 伤害参数
    private static final float DAMAGE_PER_SECOND = 200.0f; // 每秒伤害
    private static final DamageType DAMAGE_TYPE = DamageType.ENERGY;

    // 状态变量
    private boolean isFiring = false;
    private float fireTimer = 0.0f;
    private Vector2f lastTarget = null;
    private Random random = new Random();

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine.isPaused()) {
            return;
        }

        // 检查武器是否正在开火
        boolean shouldFire = weapon.isFiring();

        if (shouldFire && !isFiring) {
            // 开始开火
            startFiring(weapon, engine);
        } else if (!shouldFire && isFiring) {
            // 停止开火
            stopFiring();
        }

        if (isFiring) {
            // 更新开火状态
            updateFiring(amount, weapon, engine);
        }
    }

    /**
     * 开始开火
     */
    private void startFiring(WeaponAPI weapon, CombatEngineAPI engine) {
        isFiring = true;
        fireTimer = 0.0f;

        // 寻找目标
        Vector2f target = findTarget(weapon);
        if (target != null) {
            lastTarget = new Vector2f(target);
            // 创建曲线光束
            createCurvedBeam(weapon, target, engine);
        }
    }

    /**
     * 停止开火
     */
    private void stopFiring() {
        isFiring = false;
        fireTimer = 0.0f;
        lastTarget = null;
    }

    /**
     * 更新开火状态
     */
    private void updateFiring(float amount, WeaponAPI weapon, CombatEngineAPI engine) {
        fireTimer += amount;

        // 检查是否超时
        if (fireTimer >= BEAM_DURATION) {
            stopFiring();
            return;
        }
    }

    /**
     * 创建曲线光束
     */
    private void createCurvedBeam(WeaponAPI weapon, Vector2f target, CombatEngineAPI engine) {
        Vector2f weaponLoc = weapon.getLocation();
        float distance = Misc.getDistance(weaponLoc, target);

        // 创建起始节点
        NodeData startNode = new NodeData();
        startNode.setLocation(0.0f, 0.0f); // 相对于武器位置的偏移
        startNode.setColor(0.3f, 0.8f, 1.0f, 0.9f); // 蓝色基础色
        startNode.setEmissiveColor(0.8f, 1.0f, 1.0f, 1.0f); // 白色发光
        startNode.setWidth(BEAM_WIDTH);

        // 计算起始切线（沿武器朝向）
        float tangentLength = distance * 0.3f;
        startNode.setTangentRight(tangentLength, 0.0f);

        // 创建结束节点（相对坐标）
        Vector2f relativeTarget = Vector2f.sub(target, weaponLoc, null);
        NodeData endNode = new NodeData();
        endNode.setLocation(relativeTarget.x, relativeTarget.y);
        endNode.setColor(0.3f, 0.8f, 1.0f, 0.4f); // 渐变透明
        endNode.setEmissiveColor(0.8f, 1.0f, 1.0f, 0.6f); // 渐变发光
        endNode.setWidth(BEAM_WIDTH * 0.5f); // 渐变宽度

        // 计算结束切线（添加随机弯曲）
        Vector2f perpendicular = new Vector2f(-relativeTarget.y, relativeTarget.x);
        perpendicular.normalise();

        // 添加随机偏移创造弯曲效果
        float randomOffset = (random.nextFloat() - 0.5f) * RANDOM_OFFSET_RANGE;
        float curveFactor = Math.min(distance * CURVE_INTENSITY, distance * 0.4f);

        Vector2f endTangent = new Vector2f(perpendicular);
        endTangent.scale(-(curveFactor + randomOffset)); // 负号表示左切线
        endNode.setTangentLeft(endTangent);

        // 设置起始位置和角度
        Vector3f startOffset = new Vector3f(weaponLoc.x, weaponLoc.y, weapon.getCurrAngle());

        // 使用BoxUtil的spawnCurveBeam方法
        try {
            CurveUtil.spawnCurveBeam(
                    engine, // 战斗引擎
                    startOffset, // 起始位置和角度
                    startNode, // 起始节点
                    endNode, // 结束节点
                    weapon.getShip(), // 发射源
                    DAMAGE_PER_SECOND * BEAM_DURATION, // 总伤害
                    DAMAGE_TYPE, // 伤害类型
                    0.0f, // EMP伤害
                    false, // 是否造成软通量
                    Global.getSettings().getSprite("graphics/fx/beam_weave_fringe.png"), // 核心纹理
                    Color.CYAN, // 核心颜色
                    Global.getSettings().getSprite("graphics/fx/beam_rough2_core.png"), // 边缘纹理
                    Color.WHITE, // 边缘颜色
                    BEAM_WIDTH, // 宽度
                    TEXTURE_SPEED, // 纹理速度
                    0.1f, // 淡入时间
                    BEAM_DURATION - 0.2f, // 持续时间
                    0.1f, // 淡出时间
                    CombatEngineLayers.ABOVE_SHIPS_LAYER // 渲染层级
            );

            // 播放音效
            Global.getSoundPlayer().playSound("gigacannon_charge", 1.0f, 1.0f,
                    weaponLoc, new Vector2f());

        } catch (Exception e) {
            // 如果出错，记录日志
            Global.getLogger(this.getClass()).error("创建曲线光束失败: " + e.getMessage(), e);
        }
    }

    private Vector2f findTarget(WeaponAPI weapon) {
        ShipAPI ship = weapon.getShip();
        float range = weapon.getRange();
        boolean player = ship == Global.getCombatEngine().getPlayerShip();
        ShipAPI target = ship.getShipTarget();
        Vector2f targetLoc = null;

        // 检查AI标记的目标
        if (ship.getShipAI() != null && ship.getAIFlags().hasFlag(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM)) {
            target = (ShipAPI) ship.getAIFlags().getCustom(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM);
        }

        // 验证目标有效性
        if (target != null && target.getOwner() != ship.getOwner()) {
            float dist = Misc.getDistance(ship.getLocation(), target.getLocation());
            float radSum = ship.getCollisionRadius() + target.getCollisionRadius();
            if (dist > range + radSum) {
                target = null;
            }
        } else {
            if (player) {
                // 玩家控制：使用鼠标指向位置
                targetLoc = ship.getMouseTarget();
            } else {
                // AI控制：检查机动目标
                Object test = ship.getAIFlags().getCustom(ShipwideAIFlags.AIFlags.MANEUVER_TARGET);
                if (test instanceof ShipAPI) {
                    target = (ShipAPI) test;
                    float dist = Misc.getDistance(ship.getLocation(), target.getLocation());
                    float radSum = ship.getCollisionRadius() + target.getCollisionRadius();
                    if (dist > range + radSum) {
                        target = null;
                    }
                }
            }
        }

        // 如果没有目标，搜索最近的敌方目标
        if (target == null && targetLoc == null) {
            target = Misc.findClosestShipEnemyOf(ship, ship.getLocation(),
                    ShipAPI.HullSize.FIGHTER, range, true);
        }

        // 获取目标位置
        if (target != null && targetLoc == null) {
            targetLoc = target.getLocation();
        }

        return targetLoc;
    }
}