package data.scripts.weapons;

import com.fs.starfarer.api.combat.*;
import java.util.HashMap;
import java.util.Map;

/**
 * 多尺寸机翼动画控制器
 * 只需安装在任意一个武器上，会自动控制所有SUBWING槽位的武器
 * 支持三种尺寸：S(小)、M(中)、L(大)，每种尺寸有不同的角度和速度
 */
public class Moci_MechWINGAnimationController_Multi implements EveryFrameWeaponEffectPlugin {

    // 机翼配置类
    private static class WingConfig {
        float rotationSpeed;  // 旋转速度
        float maxTurnArc;     // 最大转角
        WingSide side;        // 左右侧
        WingSize size;        // 尺寸

        WingConfig(float rotationSpeed, float maxTurnArc, WingSide side, WingSize size) {
            this.rotationSpeed = rotationSpeed;
            this.maxTurnArc = maxTurnArc;
            this.side = side;
            this.size = size;
        }
    }

    // 机翼位置枚举
    private enum WingSide {
        LEFT, RIGHT, NONE
    }

    // 机翼尺寸枚举
    private enum WingSize {
        SMALL,   // S - 最快速度，最大角度
        MEDIUM,  // M - 中等速度，中等角度
        LARGE,   // L - 最慢速度，最小角度
        NONE
    }

    // 尺寸配置：速度和角度
    private static final float SPEED_S = 20f;  // S尺寸速度
    private static final float SPEED_M = 15f;   // M尺寸速度
    private static final float SPEED_L = 10f;   // L尺寸速度

    private static final float ARC_S = 20f;    // S尺寸最大角度
    private static final float ARC_M = 12f;    // M尺寸最大角度
    private static final float ARC_L = 7f;     // L尺寸最大角度

    // 缓存所有机翼武器及其配置
    private Map<WeaponAPI, WingConfig> wingWeapons = null;
    private boolean initialized = false;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            return;
        }

        // 初始化：遍历并缓存所有SUBWING槽位的武器
        if (!initialized) {
            initializeWings(ship);
            initialized = true;
        }

        // 如果没有找到任何机翼武器，直接返回
        if (wingWeapons == null || wingWeapons.isEmpty()) {
            return;
        }

        // 获取舰船当前朝向
        float shipFacing = ship.getFacing();

        // 遍历所有缓存的机翼武器并更新它们的角度
        for (Map.Entry<WeaponAPI, WingConfig> entry : wingWeapons.entrySet()) {
            WeaponAPI wingWeapon = entry.getKey();
            WingConfig config = entry.getValue();

            // 跳过已损坏的武器
            if (wingWeapon.isDisabled() || wingWeapon.isPermanentlyDisabled()) {
                continue;
            }

            // 计算当前武器相对于舰船的偏移角度
            float currentOffset = wingWeapon.getCurrAngle() - shipFacing;
            while (currentOffset > 180) currentOffset -= 360;
            while (currentOffset < -180) currentOffset += 360;

            // 根据运动状态计算目标偏移角度
            float targetOffset = 0f;
            if (ship.getEngineController().isDecelerating() ||
                    ship.getEngineController().isAcceleratingBackwards()) {
                // 减速或后退：左翼向左，右翼向右
                targetOffset = config.side == WingSide.LEFT ? -config.maxTurnArc : config.maxTurnArc;
            } else if (ship.getEngineController().isAccelerating()) {
                // 加速：左翼向右，右翼向左
                targetOffset = config.side == WingSide.LEFT ? config.maxTurnArc : -config.maxTurnArc;
            }

            // 平滑过渡到目标角度
            if (currentOffset != targetOffset) {
                if (currentOffset < targetOffset) {
                    currentOffset = Math.min(currentOffset + config.rotationSpeed * amount, targetOffset);
                } else {
                    currentOffset = Math.max(currentOffset - config.rotationSpeed * amount, targetOffset);
                }
            }

            // 设置最终角度
            float finalAngle = shipFacing + currentOffset;
            wingWeapon.setCurrAngle(finalAngle);
        }
    }

    /**
     * 初始化：遍历舰船所有武器槽位，找到并缓存所有SUBWING武器
     */
    private void initializeWings(ShipAPI ship) {
        wingWeapons = new HashMap<WeaponAPI, WingConfig>();

        // 遍历舰船所有武器
        for (WeaponAPI w : ship.getAllWeapons()) {
            if (w.getSlot() == null) {
                continue;
            }

            String slotId = w.getSlot().getId();

            // 检查是否是SUBWING槽位
            if (slotId.startsWith("SUBWING_")) {
                WingSide side = WingSide.NONE;
                WingSize size = WingSize.NONE;

                // 判断左右
                if (slotId.contains("_LEFT_")) {
                    side = WingSide.LEFT;
                } else if (slotId.contains("_RIGHT_")) {
                    side = WingSide.RIGHT;
                }

                // 判断尺寸
                if (slotId.endsWith("_S")) {
                    size = WingSize.SMALL;
                } else if (slotId.endsWith("_M")) {
                    size = WingSize.MEDIUM;
                } else if (slotId.endsWith("_L")) {
                    size = WingSize.LARGE;
                }

                // 如果成功识别了侧面和尺寸，创建配置并缓存
                if (side != WingSide.NONE && size != WingSize.NONE) {
                    float speed = getSpeedForSize(size);
                    float arc = getArcForSize(size);
                    WingConfig config = new WingConfig(speed, arc, side, size);
                    wingWeapons.put(w, config);
                }
            }
        }
    }

    /**
     * 根据尺寸获取旋转速度
     */
    private float getSpeedForSize(WingSize size) {
        switch (size) {
            case SMALL:
                return SPEED_S;
            case MEDIUM:
                return SPEED_M;
            case LARGE:
                return SPEED_L;
            default:
                return 6f;
        }
    }

    /**
     * 根据尺寸获取最大转角
     */
    private float getArcForSize(WingSize size) {
        switch (size) {
            case SMALL:
                return ARC_S;
            case MEDIUM:
                return ARC_M;
            case LARGE:
                return ARC_L;
            default:
                return 10f;
        }
    }
}
