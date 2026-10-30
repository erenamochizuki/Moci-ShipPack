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
import org.apache.log4j.Logger;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicRender;
import com.fs.starfarer.api.combat.CombatEngineLayers;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;

public class Moci_System1Effect extends BaseShipSystemScript {
    private static final Logger LOG = Global.getLogger(Moci_System1Effect.class);
    private static final String LOG_PREFIX = "[MOCI-TIME] ";

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

    /**
     * 时缓修饰符 id 前缀。带舰船 id 而不是直接用系统 id：本系统可作 phase cloak 用
     * （{@link #getSystem}），同一 id 若被两艘舰共用，后一辆车的 unmodify 会把前一辆车的时缓一起摘掉。
     */
    private static final String TIME_ID_PREFIX = "moci.system1.time.";

    /** 当前时缓挂在哪艘船上；死船自检要用（系统脚本没有 advance，只能在每帧的 apply 里看）。 */
    private ShipAPI timeShip;
    /** 与 {@link #timeShip} 对应的修饰符 id；摘除时用它，不重算，避免 id 参数变化导致摘不掉。 */
    private String timeId;
    /** 本次挂载是否已经为「船没了」打过日志；只为避免每帧刷屏，重新挂载时复位。 */
    private boolean releaseLogged;

    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        // 1. 首先初始化ship和player变量
        ShipAPI ship = null;
        boolean player = false;

        // 2. 从stats获取ship实例，确保后续操作有有效的ship对象
        if (stats.getEntity() instanceof ShipAPI) {
            ship = (ShipAPI) stats.getEntity();
            CombatEngineAPI engine = Global.getCombatEngine();
            // engine 可能为 null（换场/收场瞬间），先判空再解引用，避免在时缓路径上抛 NPE
            player = engine != null && ship == engine.getPlayerShip();
        }

        // 这是本类唯一的每帧入口：引擎的 F.advance 只要 eff>0 就调 apply，死船的 apply 照样会跑，
        // 但引擎既不会 deactivate 系统、也不会回头调 unapply ——「船没了就摘」只能在这里自己发现。
        if (ship != null && dead(ship)) {
            releaseForDead(ship);
            return;
        }

        // 4. 时间流速控制
        // 自己写、自己摘：id 带舰船 id，世界那一半只在「本舰是玩家舰且还活着」时存在。
        // ship 为 null（属性表不属于任何舰船）时无处可挂，直接跳过，绝不留无主修饰符。
        float timeMult = 1f + (TIME_MULT - 1f) * effectLevel;
        if (ship != null) {
            applyTime(ship, stats, timeMult);
        }

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
     * 写本舰时间倍率（以及玩家舰的世界 1/f）。
     *
     * <p>船属性表与引擎是两个载体、两个作用域，分开写才能分开摘；世界那一半只在玩家舰上存在，
     * 非玩家舰必须显式 {@code unmodify}，否则换乘时旧 id 会永久留场。</p>
     */
    private void applyTime(ShipAPI ship, MutableShipStatsAPI stats, float timeMult) {
        String currentId = TIME_ID_PREFIX + ship.getId();
        if (timeId != null && !timeId.equals(currentId)) {
            // 同一实例被复用到另一艘船上：先摘掉旧的，绝不留下跨船残留
            unmodifyTime(timeShip, timeId);
        }
        timeShip = ship;
        timeId = currentId;
        releaseLogged = false;
        stats.getTimeMult().modifyMult(currentId, timeMult);
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine != null) {
            if (ship == engine.getPlayerShip() && ship.isAlive() && !ship.wasRemoved()) {
                engine.getTimeMult().modifyMult(currentId, 1f / timeMult);
            } else {
                engine.getTimeMult().unmodify(currentId);
            }
        }
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

    /** 船没了：把本条时缓摘干净并留一条日志。摘除本身幂等，重复调用无害。 */
    private void releaseForDead(ShipAPI ship) {
        boolean had = timeId != null;
        releaseTime();
        if (had && !releaseLogged) {
            releaseLogged = true;
            LOG.warn(LOG_PREFIX + "SYSTEM1 宿主舰已死亡或离场，摘掉时缓（ship="
                    + (ship == null ? "-" : ship.getId()) + "）");
        }
    }

    /** 摘掉本地记录的时缓；幂等、绝不抛。 */
    private void releaseTime() {
        ShipAPI ship = timeShip;
        String currentId = timeId;
        timeShip = null;
        timeId = null;
        if (currentId != null) unmodifyTime(ship, currentId);
    }

    /** 真正的摘除动作。不依赖字段，所以「脚本实例被换掉」时 unapply 也能直接摘掉旧 id。 */
    private static void unmodifyTime(ShipAPI ship, String id) {
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
        // 移除所有状态修饰器
        // 时缓按「本船 id」摘：即使本实例没走过 apply（脚本实例被换掉）也摘得掉；
        // 世界那一半同理，否则换乘后引擎会永久压在 1/3 倍速上。
        ShipAPI entity = stats.getEntity() instanceof ShipAPI ? (ShipAPI) stats.getEntity() : null;
        String staleId = timeId != null ? timeId : entity != null ? TIME_ID_PREFIX + entity.getId() : null;
        releaseTime();
        if (staleId != null) unmodifyTime(entity, staleId);

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
