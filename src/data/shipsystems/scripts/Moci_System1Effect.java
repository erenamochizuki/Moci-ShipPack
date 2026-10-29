package data.shipsystems.scripts;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.impl.campaign.ids.Personalities;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import data.scripts.combat.Moci_TimeFlow;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicRender;
import com.fs.starfarer.api.combat.CombatEngineLayers;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;

public class Moci_System1Effect extends BaseShipSystemScript {
    private boolean effected = true;
    String p = null;

    // 系统参数配置
    public static final float INCOMING_DAMAGE_MULT = 0.3f;  // 70%减伤
    public static final float TIME_MULT = 3f;               // 时间流速倍率
    public static final float SPEED_BONUS = 150f;           // 速度加成
    public static final float ACCEL_BONUS = 300f;           // 加速度加成
    public static final float TURN_PENALTY = -50f;          // 转向惩罚
    public static final float DECEL_PENALTY = -50f;          // 减速惩罚

    // 新增残影相关参数
    private static final float AFTERIMAGE_INTERVAL = 0.35f; // 残影产生间隔(秒)
    private static final float AFTERIMAGE_FADE_IN = 0f;    // 残影淡入时间
    private static final float AFTERIMAGE_FADE_OUT = 0.6f; // 残影淡出时间
    private static final float AFTERIMAGE_ALPHA_START = 0.8f; // 初始透明度
    private static final float AFTERIMAGE_ALPHA_END = 0f;   // 结束透明度
    private float afterimageAccumulator = 0f;

    protected Object STATUSKEY1 = new Object();

    // 时间流的申请名字（只作 Handle 名；属性键由 Moci_TimeFlow 统一持有，全模组只有一个 id）
    private static final String TIME_HANDLE_ID = "Moci_System1";
    private Moci_TimeFlow.Handle timeHandle;
    private boolean timeHandleIsBullet;
    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        // 1. 首先初始化ship和player变量
        ShipAPI ship = null;
        boolean player = false;

        // 2. 从stats获取ship实例，确保后续操作有有效的ship对象
        if (stats.getEntity() instanceof ShipAPI) {
            ship = (ShipAPI) stats.getEntity();
            player = ship == Global.getCombatEngine().getPlayerShip();
        }

        // 4. 时间流速控制
        // 原实现自己往引擎/船属性表挂 id、unapply 时自己摘；系统被换乘或舰船离场打断时
        // 世界倍率 1/timeMult 会永久留场（「进了时缓出不来」）。现在只向 Moci_TimeFlow 申请句柄，
        // 失效由权威统一回收，本类只负责「还在驱动时每帧刷新倍率」。
        float timeMult = 1f + (TIME_MULT - 1f) * effectLevel;
        applyTimeFlow(ship, timeMult);

        // 5. 伤害减免效果
        stats.getHullDamageTakenMult().modifyMult(id, 1f - (1f - INCOMING_DAMAGE_MULT) * effectLevel);
        stats.getArmorDamageTakenMult().modifyMult(id, 1f - (1f - INCOMING_DAMAGE_MULT) * effectLevel);
        stats.getEmpDamageTakenMult().modifyMult(id, 1f - (1f - INCOMING_DAMAGE_MULT) * effectLevel);

        // 6. 机动性调整
        stats.getMaxSpeed().modifyFlat(id, SPEED_BONUS * effectLevel);
        stats.getAcceleration().modifyFlat(id, ACCEL_BONUS * effectLevel);
        stats.getTurnAcceleration().modifyPercent(id, TURN_PENALTY * effectLevel);
        stats.getDeceleration().modifyPercent(id, DECEL_PENALTY * effectLevel);
        stats.getMaxTurnRate().modifyPercent(id, TURN_PENALTY * effectLevel);

        // 7. 残影效果和AI性格设置
        if (ship != null) {
            // 残影效果
            afterimageAccumulator += effectLevel * Global.getCombatEngine().getElapsedInLastFrame();
            if (afterimageAccumulator > AFTERIMAGE_INTERVAL) {
                createAdvancedAfterimages(ship);
                afterimageAccumulator = 0f;
            }

            if (!effected) {
                effected = true;
                // 一次激活限定一次重装弹药
                for (WeaponAPI weapon : ship.getAllWeapons()) {
                    if (weapon.getMaxAmmo() != Integer.MAX_VALUE) {
                        weapon.setAmmo(weapon.getMaxAmmo());
                    }
                }
            }

            // 设置鲁莽性格
            if (ship.getShipAI() != null && ship.getShipAI().getConfig() != null) {
                if (p == null && ship.getShipAI().getConfig().personalityOverride != null) {
                    p = ship.getShipAI().getConfig().personalityOverride;
                }
                ship.getShipAI().getConfig().personalityOverride = Personalities.RECKLESS;
            }
        }

        // 8. 玩家状态显示
        if (player && ship != null) {
            ShipSystemAPI system = getSystem(ship);
            if (system != null) {
                float percent = (1f - INCOMING_DAMAGE_MULT) * effectLevel * 100;
                Global.getCombatEngine().maintainStatusForPlayerShip(STATUSKEY1,
                        system.getSpecAPI().getIconSpriteName(), system.getDisplayName(),
                        (int) Math.round(percent) + "% 伤害降低", false);
            }
        }
    }

    /**
     * 申请/刷新本系统的时间流。
     *
     * <p>形态与原语义一致：玩家舰拿到「船 ×timeMult + 世界 ×1/timeMult」的成对子弹时间，
     * 非玩家舰只有船侧。世界半的有无是结构性的（换乘会在两种形态间翻转），所以形态一变
     * 就必须重建句柄——只 {@code set()} 不会凭空多出或少掉世界那一半。</p>
     *
     * <p>原实现在 {@code ship == null}（stats 不属于任何舰船）时仍会直接写 {@code stats.getTimeMult()}；
     * 那种输入对船用系统脚本不可达，这里退化为「没有可申请的作用域就收回申请」，
     * 不会留下无主的修饰符。</p>
     */
    private void applyTimeFlow(ShipAPI ship, float timeMult) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || ship == null) {
            releaseTimeFlow("没有可申请的作用域");
            return;
        }
        boolean bullet = ship == engine.getPlayerShip();
        if (timeHandle != null && timeHandle.active() && timeHandleIsBullet == bullet) {
            timeHandle.set(timeMult);
            return;
        }
        releaseTimeFlow("时间申请形态变化");
        timeHandle = bullet
                ? Moci_TimeFlow.openBulletTime(ship, TIME_HANDLE_ID, timeMult)
                : Moci_TimeFlow.openShip(ship, TIME_HANDLE_ID, timeMult);
        timeHandleIsBullet = bullet;
    }

    /** 收回时间申请；幂等。 */
    private void releaseTimeFlow(String why) {
        Moci_TimeFlow.Handle handle = timeHandle;
        timeHandle = null;
        if (handle == null) {
            return;
        }
        if (Moci_TimeFlow.TRACE) {
            Global.getLogger(Moci_System1Effect.class).info("[MOCI-TIME] Moci_System1 收回时间申请 原因=" + why);
        }
        handle.release();
    }

    // 新的残影创建方法
    private void createAdvancedAfterimages(ShipAPI ship) {
        // 获取偏移量
        SpriteAPI sprite = ship.getSpriteAPI();
        float offsetX = sprite.getWidth()/2 - sprite.getCenterX();
        float offsetY = sprite.getHeight()/2 - sprite.getCenterY();

        // 计算旋转后的偏移量
        float trueOffsetX = (float)Math.cos(Math.toRadians(ship.getFacing()-90f))*offsetX -
                (float)Math.sin(Math.toRadians(ship.getFacing()-90f))*offsetY;
        float trueOffsetY = (float)Math.sin(Math.toRadians(ship.getFacing()-90f))*offsetX +
                (float)Math.cos(Math.toRadians(ship.getFacing()-90f))*offsetY;

        // 为所有武器创建残影
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.getSprite() == null) continue;

            // 检查武器是否被设置为完全透明
            boolean isHidden = false;
            if (weapon.getSprite() != null && weapon.getSprite().getColor().getAlpha() == 0) {
                isHidden = true;
            }
            if (weapon.getBarrelSpriteAPI() != null && weapon.getBarrelSpriteAPI().getColor().getAlpha() == 0) {
                isHidden = true;
            }
            if (weapon.getUnderSpriteAPI() != null && weapon.getUnderSpriteAPI().getColor().getAlpha() == 0) {
                isHidden = true;
            }
            if (weapon.getGlowSpriteAPI() != null && weapon.getGlowSpriteAPI().getColor().getAlpha() == 0) {
                isHidden = true;
            }

            if (isHidden) continue;

            // 获取武器贴图路径
            String spritePath;
            if (weapon.getAnimation() != null) {
                // 对于动画武器，获取当前帧的贴图路径
                int frame = weapon.getAnimation().getFrame();
                String basePath = weapon.getSlot().isTurret() ?
                        weapon.getSpec().getTurretSpriteName() :
                        weapon.getSpec().getHardpointSpriteName();
                // 移除.png后缀
                if (basePath.endsWith(".png")) {
                    basePath = basePath.substring(0, basePath.length() - 7);
                }
                // 添加帧号和后缀
                spritePath = String.format("%s_%02d.png", basePath, frame);
/*
                // 添加漂浮文字显示贴图路径
                Global.getCombatEngine().addFloatingText(
                        weapon.getLocation(),
                        spritePath,
                        20f,
                        new Color(255, 255, 255, 255),
                        ship,
                        1f,
                        1f
                );

 */
            } else {
                // 无动画的武器使用默认贴图
                spritePath = weapon.getSlot().isTurret() ?
                        weapon.getSpec().getTurretSpriteName() :
                        weapon.getSpec().getHardpointSpriteName();
            }

            // 获取新的Sprite实例
            SpriteAPI weaponSprite = Global.getSettings().getSprite(spritePath);
            weaponSprite.setSize(weapon.getSprite().getWidth(), weapon.getSprite().getHeight());

            // 创建残影
            MagicRender.battlespace(
                    weaponSprite,
                    new Vector2f(weapon.getLocation().x + trueOffsetX, weapon.getLocation().y + trueOffsetY),
                    new Vector2f(0, 0),
                    new Vector2f(weapon.getSprite().getWidth(), weapon.getSprite().getHeight()),
                    new Vector2f(0, 0),
                    weapon.getCurrAngle()-90f,
                    0f,
                    new Color(255, 150, 150, (int)(AFTERIMAGE_ALPHA_START * 255)),
                    true,
                    0f,  // jitterRange
                    0f,  // jitterTilt
                    0f,  // flickerRange
                    0f,  // flickerMedian
                    0f,  // maxDelay
                    AFTERIMAGE_FADE_IN,   // fadein
                    0f,  // full - 完全显示时间
                    AFTERIMAGE_FADE_OUT,  // fadeout
                    CombatEngineLayers.BELOW_SHIPS_LAYER
            );
        }
    }
    /**
     * 获取对应系统  把系统id填入检索条件
     * @param ship
     * @return
     */
    public static ShipSystemAPI getSystem(ShipAPI ship) {
        ShipSystemAPI system = ship.getPhaseCloak();
        if (system != null && system.getId().equals("Moci_system1")) return system;
        return ship.getSystem();
    }

    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
        effected = false;
        // 移除所有状态修饰器；时间倍率只经句柄收回，不再直接触碰 time mult 属性表
        releaseTimeFlow("unapply");

        stats.getMaxSpeed().unmodify(id);
        stats.getAcceleration().unmodify(id);
        stats.getTurnAcceleration().unmodify(id);
        stats.getDeceleration().unmodify(id);
        stats.getMaxTurnRate().unmodify(id);

        stats.getHullDamageTakenMult().unmodify(id);
        stats.getArmorDamageTakenMult().unmodify(id);
        stats.getEmpDamageTakenMult().unmodify(id);


        if (stats.getEntity() instanceof ShipAPI) {
            ShipAPI ship = (ShipAPI) stats.getEntity();
            if (ship.getShipAI() != null && ship.getShipAI().getConfig() != null) {
                if (p != null) {
                    ship.getShipAI().getConfig().personalityOverride = p; //还原性格
                }
            }
        }
    }
}
