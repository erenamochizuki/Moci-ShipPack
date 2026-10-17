package data.scripts.weapons;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;
import org.lwjgl.util.vector.Vector3f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;

import data.scripts.weapons.render.Moci_EngineRender;

/**
 * 矢量引擎控制器 - 渲染版本
 * 
 * 基于GWC矢量喷口系统改造，用于控制渲染引擎的朝向和火焰强度
 * 不控制真实引擎，只控制视觉效果
 * 
 * 使用方法：
 * 1. 在武器槽位上设置arc（可旋转角度）和angle（中心朝向）
 * 2. 武器使用此脚本
 * 3. 系统会自动根据舰船运动指令调整渲染引擎的朝向和强度
 */
public class Moci_VectorEngineController implements EveryFrameWeaponEffectPlugin {
    
    // 引擎渲染参数
    private static final Color DEFAULT_ENGINE_COLOR = new Color(43, 149, 255, 195);
    private static final float ENGINE_LENGTH_MULT = 2f;
    private static final float ENGINE_WIDTH_MULT = 3f;
    private static final float GLOW_SIZE_MULT = 1.0f;
    

    private static final float ANGLE_CONTROL_GAIN = 2f;  // 角度控制增益（降低以减少灵敏度）
    private static final float FLAME_CONTROL_GAIN = 3f;  // 火焰控制增益（降低以减少灵敏度）
    
    // 默认参数
    private static final float DEFAULT_ROTATION_WEIGHT = 0.4f;  // 默认旋转权重
    private static final float DEFAULT_ERR_TOLERANCE = 10f;     // 角度误差容忍度
    private static final float MIN_FLAME_LEVEL = 0.05f;         // 最小火焰等级
    
    private boolean initialized = false;
    private List<Moci_EngineRender> renders = new ArrayList<>();
    private WeaponAPI controlledWeapon;      // 保存武器引用
    
    // 武器级别的数据（整个武器一起旋转）
    private Vector2f weaponLocalPosition;    // 武器位置（相对舰船）
    private float slotArc;                   // 槽位可旋转角度
    private float slotAngleOffset;           // 槽位中心角度
    private float weaponAngleOffset;         // 武器默认角度
    private float rotationWeight;            // 旋转权重
    
    // 当前状态
    private float currentAngle;              // 当前角度
    private float currentFlameLevel;         // 当前火焰等级
    
    // 目标状态（用于PID控制）
    private float targetAngle;
    private float targetFlameLevel;
    
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }
        
        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive()) {
            return;
        }
        
        // 初始化
        if (!initialized) {
            controlledWeapon = weapon;
            initializeVectorEngines(engine, weapon, ship);
            initialized = true;
        }
        
        // 获取舰船运动指令
        Vector3f shipMotionCmd = getShipMotionCommand(ship);
        
        // 计算目标推力方向和强度（整个武器）
        calculateThrustCommand(shipMotionCmd, ship);
        
        // PID控制平滑过渡
        updateEngineWithPID(amount);
        
        // 每帧强制设置武器角度（覆盖任何其他控制）
        weapon.setCurrAngle(ship.getFacing() + currentAngle);
        
        // 更新引擎渲染状态
        updateEngineRenders();
    }
    
    /**
     * 初始化矢量引擎系统
     */
    private void initializeVectorEngines(CombatEngineAPI engine, WeaponAPI weapon, ShipAPI ship) {
        if (ship.getEngineController() == null) {
            return;
        }
        
        // 获取模板引擎
        ShipEngineControllerAPI.ShipEngineAPI templateEngine = getTemplateEngine(ship);
        if (templateEngine == null) {
            return;
        }
        
        // 加载精灵图
        SpriteAPI glowSprite = Global.getSettings().getSprite("fx", "engineglow32b");
        SpriteAPI flameSprite = Global.getSettings().getSprite("fx", "MBSglowOutline");
        SpriteAPI circleSprite = Global.getSettings().getSprite("fx", "MBSEngineglowCricleNothing");
        
        // 获取武器槽位信息（整个武器级别）
        slotArc = weapon.getSlot().getArc();
        slotAngleOffset = weapon.getSlot().getAngle();
        weaponAngleOffset = weapon.getSlot().getAngle(); // 武器默认角度
        
        // 计算武器位置（相对舰船，使用第一个开火点作为代表）
        List<Vector2f> firePoints = weapon.getSpec().getTurretFireOffsets();
        if (firePoints.size() > 0) {
            weaponLocalPosition = getLocalPosition(firePoints.get(0), weapon, ship);
        } else {
            weaponLocalPosition = new Vector2f(0, 0);
        }
        
        // 计算旋转权重（距离中心越远，越倾向于响应旋转）
        rotationWeight = calculateRotationWeight(weaponLocalPosition, ship);
        
        // 初始状态
        currentAngle = weaponAngleOffset;
        currentFlameLevel = 0f;
        targetAngle = weaponAngleOffset;
        targetFlameLevel = 0f;
        
        // 为每个开火点创建渲染器
        List<Float> firePointAngles = weapon.getSpec().getTurretAngleOffsets();
        
        for (int i = 0; i < firePoints.size(); i++) {
            // 创建渲染器
            Moci_EngineRender render = new Moci_EngineRender(
                ship,
                templateEngine,
                weapon,
                glowSprite,
                flameSprite,
                circleSprite,
                CombatEngineLayers.UNDER_SHIPS_LAYER,
                CombatEngineLayers.UNDER_SHIPS_LAYER,
                true
            );
            
            render.setFirePointIndex(i);
            render.setGlowSizeMult(GLOW_SIZE_MULT);
            render.setLengthMult(ENGINE_LENGTH_MULT);
            render.setWidthMult(ENGINE_WIDTH_MULT);
            
            // 设置引擎颜色
            Color engineColor = DEFAULT_ENGINE_COLOR;
            if (templateEngine.getEngineColor() != null) {
                engineColor = new Color(
                    templateEngine.getEngineColor().getRed(),
                    templateEngine.getEngineColor().getGreen(),
                    templateEngine.getEngineColor().getBlue(),
                    DEFAULT_ENGINE_COLOR.getAlpha()
                );
            }
            render.setColorOverride(engineColor);
            
            engine.addLayeredRenderingPlugin(render);
            renders.add(render);
        }
    }

    /**
     * 获取舰船运动指令（3D矢量）
     */
    private Vector3f getShipMotionCommand(ShipAPI ship) {
        float acceX = 0f, acceY = 0f, acceZ = 0f;
        
        ShipEngineControllerAPI controller = ship.getEngineController();
        
        // X轴：前后加速
        if (controller.isAccelerating()) {
            acceX = 1f;
        } else if (controller.isAcceleratingBackwards()) {
            acceX = -1f;
        }
        
        // Y轴：左右平移
        if (controller.isStrafingLeft()) {
            acceY = 1f;
        }
        if (controller.isStrafingRight()) {
            acceY -= 1f;
        }
        
        // Z轴：旋转（右手坐标系，逆时针为正）
        if (controller.isTurningLeft()) {
            acceZ = 1f;
        }
        if (controller.isTurningRight()) {
            acceZ -= 1f;
        }
        
        return new Vector3f(acceX, acceY, acceZ);
    }
    
    /**
     * 计算推力指令（整个武器级别）
     */
    private void calculateThrustCommand(Vector3f shipMotionCmd, ShipAPI ship) {
        // 如果是零矢量（待机），回到默认位置
        if (isZeroVector(shipMotionCmd)) {
            targetAngle = weaponAngleOffset;
            targetFlameLevel = 0f;
            return;
        }
        
        // 计算旋转产生的推力矢量
        Vector2f rotationThrust = calculateRotationThrust(shipMotionCmd.z);
        
        // 计算平移推力矢量
        Vector2f translationThrust = new Vector2f(
            shipMotionCmd.x * (1f - rotationWeight),
            shipMotionCmd.y * (1f - rotationWeight)
        );
        
        // 合成最终推力矢量
        Vector2f totalThrust = new Vector2f(
            rotationThrust.x + translationThrust.x,
            rotationThrust.y + translationThrust.y
        );
        
        // 如果是零矢量，回到默认位置
        if (isZeroVector(totalThrust)) {
            targetAngle = weaponAngleOffset;
            targetFlameLevel = 0f;
            return;
        }
        
        // 计算目标角度
        float angle = VectorUtils.getFacing(totalThrust);
        
        // 检查是否在限位范围内
        float angleFromCenter = Math.abs(MathUtils.getShortestRotation(
            slotAngleOffset, angle
        ));
        
        // 如果超出限位 + 容忍度
        if (angleFromCenter > slotArc / 2f + DEFAULT_ERR_TOLERANCE) {
            targetAngle = weaponAngleOffset;
            targetFlameLevel = 0f;
            return;
        }
        
        // 限制在槽位范围内
        targetAngle = limitAngleToArc(angle, slotAngleOffset, slotArc);
        
        // 计算火焰强度（简化版，基于推力矢量长度）
        float thrustMagnitude = (float) Math.sqrt(
            totalThrust.x * totalThrust.x + totalThrust.y * totalThrust.y
        );
        targetFlameLevel = Math.min(thrustMagnitude, 1f);
    }
    
    /**
     * 计算旋转产生的推力矢量
     */
    private Vector2f calculateRotationThrust(float rotationCmd) {
        if (Math.abs(rotationCmd) < 0.001f) {
            return new Vector2f(0f, 0f);
        }
        
        // 3D叉乘：旋转力矩 × 引擎位置 = 推力方向
        Vector2f thrust = new Vector2f(
            -rotationCmd * weaponLocalPosition.y,
            rotationCmd * weaponLocalPosition.x
        );
        
        // 归一化并乘以旋转权重
        float length = thrust.length();
        if (length > 0.001f) {
            thrust.x = thrust.x / length * rotationWeight;
            thrust.y = thrust.y / length * rotationWeight;
        }
        
        return thrust;
    }
    
    /**
     * PID控制更新引擎状态
     */
    private void updateEngineWithPID(float amount) {
        // 角度控制（考虑360度循环）
        float angleError = MathUtils.getShortestRotation(currentAngle, targetAngle);
        float angleChange = angleError * ANGLE_CONTROL_GAIN * amount;
        currentAngle = normalizeAngle(currentAngle + angleChange);
        
        // 火焰强度控制
        float flameError = targetFlameLevel - currentFlameLevel;
        float flameChange = flameError * FLAME_CONTROL_GAIN * amount;
        currentFlameLevel = Math.max(0f, Math.min(1f, currentFlameLevel + flameChange));
    }
    
    /**
     * 更新引擎渲染状态
     */
    private void updateEngineRenders() {
        // 设置所有引擎的激活状态和火焰强度
        boolean shouldRender = currentFlameLevel > MIN_FLAME_LEVEL;
        
        for (Moci_EngineRender render : renders) {
            render.setActive(shouldRender);
            
            if (shouldRender) {
                render.overrideEngineLevel(
                    MIN_FLAME_LEVEL + (1f - MIN_FLAME_LEVEL) * currentFlameLevel
                );
            }
        }
    }
    
    // ==================== 工具方法 ====================
    
    /**
     * 获取模板引擎
     */
    private ShipEngineControllerAPI.ShipEngineAPI getTemplateEngine(ShipAPI ship) {
        for (ShipEngineControllerAPI.ShipEngineAPI engine : ship.getEngineController().getShipEngines()) {
            if (engine != null) {
                return engine;
            }
        }
        return null;
    }
    
    /**
     * 获取局部位置（相对舰船中心）
     */
    private Vector2f getLocalPosition(Vector2f firePoint, WeaponAPI weapon, ShipAPI ship) {
        // 开火点是相对武器的，需要转换到相对舰船
        Vector2f worldPos = weapon.getFirePoint(weapon.getSpec().getTurretFireOffsets().indexOf(firePoint));
        Vector2f shipPos = ship.getLocation();
        
        // 世界坐标差
        Vector2f diff = new Vector2f(worldPos.x - shipPos.x, worldPos.y - shipPos.y);
        
        // 旋转到舰船坐标系
        float angle = -ship.getFacing();
        float cos = (float) Math.cos(Math.toRadians(angle));
        float sin = (float) Math.sin(Math.toRadians(angle));
        
        return new Vector2f(
            diff.x * cos - diff.y * sin,
            diff.x * sin + diff.y * cos
        );
    }
    
    /**
     * 计算旋转权重
     */
    private float calculateRotationWeight(Vector2f localPos, ShipAPI ship) {
        // 距离中心越远，越倾向于响应旋转指令
        float distance = localPos.length();
        float radius = ship.getCollisionRadius();
        
        if (radius < 1f) {
            return DEFAULT_ROTATION_WEIGHT;
        }
        
        float weight = distance / radius;
        
        // 限制在合理范围
        return Math.max(0.05f, Math.min(0.95f, weight));
    }
    
    /**
     * 限制角度到槽位范围内
     */
    private float limitAngleToArc(float angle, float centerAngle, float arc) {
        float halfArc = arc / 2f;
        float diff = MathUtils.getShortestRotation(centerAngle, angle);
        
        if (diff > halfArc) {
            return normalizeAngle(centerAngle + halfArc);
        } else if (diff < -halfArc) {
            return normalizeAngle(centerAngle - halfArc);
        }
        
        return angle;
    }
    
    /**
     * 归一化角度到-180~180
     */
    private float normalizeAngle(float angle) {
        while (angle > 180f) {
            angle -= 360f;
        }
        while (angle < -180f) {
            angle += 360f;
        }
        return angle;
    }
    
    /**
     * 判断是否为零矢量
     */
    private boolean isZeroVector(Vector3f vec) {
        return Math.abs(vec.x) < 0.001f && 
               Math.abs(vec.y) < 0.001f && 
               Math.abs(vec.z) < 0.001f;
    }
    
    /**
     * 判断是否为零矢量（2D）
     */
    private boolean isZeroVector(Vector2f vec) {
        return Math.abs(vec.x) < 0.001f && Math.abs(vec.y) < 0.001f;
    }
}
