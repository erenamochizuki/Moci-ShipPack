package data.shipsystems.scripts;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;

/**
 * 导弹干扰系统脚本
 * 功能：
 * 1. 被动模式：800单位范围内导弹失去转向能力，可选择是否开启友伤
 * 2. 主动模式：3000单位范围内敌舰武器射程减半，受到来自干扰源的伤害减少80%
 * 3. 提供5点电子战加成
 * 
 * 可配置选项：
 * - AFFECTS_SELF: 控制主动模式是否对干扰源自身生效
 * - ENABLE_FRIENDLY_FIRE_CONVERSION: 控制被动模式是否启用导弹友伤转换
 */
public class Moci_MissileJammerSystemScript extends BaseShipSystemScript {
    // 玩家技能豁免标签，拥有此标签的玩家舰船不受干扰影响
    public static final String playerSkillBanTag = "Moci_PlayerIgnoreJammer";

    // 控制系统是否对自身生效的开关
    // true = 对自身生效（武器射程减半，但获得伤害减免）
    // false = 对自身无效（默认，避免自我削弱）
    // 注意：此开关只影响主动模式的舰船debuff效果，不影响导弹干扰
    private static final boolean AFFECTS_SELF = false;

    // 控制是否启用导弹友伤转换的开关
    // true = 启用友伤转换（默认，无友伤导弹变为友伤导弹）
    // false = 禁用友伤转换（保持导弹原有的碰撞类型）
    // 注意：此开关影响被动模式的导弹干扰效果
    private static final boolean ENABLE_FRIENDLY_FIRE_CONVERSION = false;

    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        super.apply(stats, id, state, effectLevel);
    }

    // 初始化标志，确保监听器只添加一次
    boolean init = false;

    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
        // 在系统关闭时初始化监听器
        if (!init) {
            if (stats.getEntity() instanceof ShipAPI) {
                ShipAPI ship = (ShipAPI) stats.getEntity();
                if (!ship.hasListenerOfClass(jammerListener.class)) {
                    ship.addListener(new jammerListener(ship, id));
                    init = true;
                }
            }
        }
        // 提供5点电子战加成
        stats.getDynamic().getMod(Stats.ELECTRONIC_WARFARE_FLAT).modifyFlat(id, 5);
    }

    /**
     * 干扰器监听器 - 负责处理导弹干扰和舰船debuff效果
     */
    private static class jammerListener implements AdvanceableListener {
        final ShipAPI ship; // 干扰源舰船
        float distOff; // 被动模式作用距离的平方
        float distOn; // 主动模式作用距离的平方
        final String id; // 唯一标识符
        final String tag; // 干扰标签

        // 视觉效果相关
        private Random random = new Random();

        // 粒子效果参数 - 可调节
        // 被动模式参数
        private static final int PASSIVE_PARTICLE_COUNT = 2; // 被动模式每帧粒子数量
        private static final float PASSIVE_PARTICLE_SIZE_MIN = 8f; // 被动模式粒子最小大小
        private static final float PASSIVE_PARTICLE_SIZE_MAX = 15f; // 被动模式粒子最大大小
        private static final float PASSIVE_PARTICLE_DURATION_MIN = 0.3f; // 被动模式粒子最短持续时间（秒）
        private static final float PASSIVE_PARTICLE_DURATION_MAX = 1.0f; // 被动模式粒子最长持续时间（秒）
        private static final float PASSIVE_PARTICLE_BRIGHTNESS = 0.7f; // 被动模式粒子亮度

        // 主动模式参数
        private static final int ACTIVE_PARTICLE_COUNT = 5; // 主动模式每帧粒子数量
        private static final float ACTIVE_PARTICLE_SIZE_MIN = 20f; // 主动模式粒子最小大小
        private static final float ACTIVE_PARTICLE_SIZE_MAX = 50f; // 主动模式粒子最大大小
        private static final float ACTIVE_PARTICLE_DURATION_MIN = 0.8f; // 主动模式粒子最短持续时间（秒）
        private static final float ACTIVE_PARTICLE_DURATION_MAX = 2.5f; // 主动模式粒子最长持续时间（秒）
        private static final float ACTIVE_PARTICLE_BRIGHTNESS = 2f; // 主动模式粒子亮度

        // 通用参数
        private static final float PARTICLE_VELOCITY_MAX = 50f; // 粒子最大速度

        // 粒子颜色alpha值范围参数
        private static final int PARTICLE_ALPHA_MIN = 50; // 粒子最小透明度
        private static final int PARTICLE_ALPHA_MAX = 200; // 粒子最大透明度

        // 粒子颜色参数 (RGB值: 0-255, Alpha值将随机生成)
        // 注意：这些颜色的alpha值会被动态替换
        private static final Color PASSIVE_PARTICLE_COLOR_BASE = new Color(255, 255, 255); // 被动模式粒子基色 (白色)
        private static final Color ACTIVE_PARTICLE_COLOR_RED_BASE = new Color(240, 255, 100); // 主动模式粒子1基色
        private static final Color ACTIVE_PARTICLE_COLOR_GREEN_BASE = new Color(100, 255, 100); // 主动模式粒子2基色
        private static final Color ACTIVE_PARTICLE_COLOR_BLUE_BASE = new Color(100, 255, 201); // 主动模式粒子3基色

        private jammerListener(ShipAPI ship, String id) {
            this.ship = ship;
            this.id = id + ship.getId();
            distOff = 800 * 800; // 被动模式：800单位范围
            distOn = 3000 * 3000; // 主动模式：3000单位范围
            this.tag = "Moci_MissileJammer_" + ship.getId();
        }

        // 受影响的舰船集合（仅在主动模式下使用）
        Set<ShipAPI> effectedShips = new HashSet<>();

        @Override
        public void advance(float amount) {
            // 检查系统是否激活
            boolean systemOn = ship.getSystem() != null && ship.getSystem().isActive();
            Iterator<Object> iter;
            float sq;

            // 根据系统状态选择不同的搜索范围和目标
            if (systemOn) {
                // 主动模式：搜索所有对象，范围6000单位
                iter = Global.getCombatEngine().getAllObjectGrid().getCheckIterator(ship.getLocation(), 6000, 6000);
                sq = distOn;
            } else {
                // 被动模式：只搜索导弹，范围1600单位
                iter = Global.getCombatEngine().getMissileGrid().getCheckIterator(ship.getLocation(), 1600, 1600);
                sq = distOff;
            }

            // 处理已受影响的舰船
            if (!effectedShips.isEmpty()) {
                if (systemOn) {
                    // 主动模式：检查距离，移除超出范围的舰船
                    Iterator<ShipAPI> shipIter = effectedShips.iterator();
                    while (shipIter.hasNext()) {
                        ShipAPI target = shipIter.next();
                        if (target == ship) {
                            continue;
                        }
                        float distSq = Misc.getDistanceSq(ship.getLocation(), target.getLocation());
                        if (distSq > sq) {
                            unapply(target);
                            shipIter.remove();
                        }
                    }
                } else {
                    // 被动模式：清除所有舰船效果
                    for (ShipAPI target : effectedShips) {
                        unapply(target);
                    }
                    effectedShips.clear();
                }
            }
            // 遍历范围内的所有对象
            while (iter.hasNext()) {
                Object obj = iter.next();
                if (obj instanceof MissileAPI) {
                    MissileAPI msl = (MissileAPI) obj;
                    float distSq = Misc.getDistanceSq(ship.getLocation(), msl.getLocation());
                    if (distSq > sq) {
                        continue;
                    }

                    // 根据开关决定是否转换导弹友伤属性
                    if (ENABLE_FRIENDLY_FIRE_CONVERSION) {
                        // 将无友伤导弹转换为友伤导弹（干扰效果）
                        switch (msl.getCollisionClass()) {
                            case MISSILE_NO_FF:
                                msl.setCollisionClass(CollisionClass.MISSILE_FF);
                                break;
                            case HITS_SHIPS_ONLY_NO_FF:
                                msl.setCollisionClass(CollisionClass.HITS_SHIPS_ONLY_FF);
                                break;
                            case PROJECTILE_NO_FF:
                                msl.setCollisionClass(CollisionClass.PROJECTILE_FF);
                                break;
                            default:
                                // 如果不是无友伤类型，跳过友伤转换但仍然禁用转向
                                break;
                        }
                    }
                    // 完全禁用导弹转向能力
                    msl.getEngineStats().getMaxTurnRate().modifyMult(id, 0);

                } else if (obj instanceof ShipAPI && systemOn) {
                    // 主动模式：对所有舰船（包括友军）施加debuff效果
                    ShipAPI target = (ShipAPI) obj;
                    float distSq = Misc.getDistanceSq(ship.getLocation(), target.getLocation());

                    // 检查是否跳过此目标
                    boolean skipTarget = distSq > sq || effectedShips.contains(target);

                    // 根据开关决定是否对自身生效
                    if (!AFFECTS_SELF && target == ship) {
                        skipTarget = true;
                    }

                    if (skipTarget) {
                        continue;
                    }

                    apply(target);
                    effectedShips.add(target);
                }
            }

            // 生成视觉效果粒子
            generateVisualEffects(amount, systemOn);
        }

        /**
         * 生成视觉效果粒子
         */
        private void generateVisualEffects(float amount, boolean systemOn) {
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine == null)
                return;

            // 根据系统状态确定参数
            float range = systemOn ? (float) Math.sqrt(distOn) : (float) Math.sqrt(distOff);
            int particleCount;
            float sizeMin, sizeMax, durationMin, durationMax, brightness;

            if (systemOn) {
                // 主动模式参数
                particleCount = ACTIVE_PARTICLE_COUNT;
                sizeMin = ACTIVE_PARTICLE_SIZE_MIN;
                sizeMax = ACTIVE_PARTICLE_SIZE_MAX;
                durationMin = ACTIVE_PARTICLE_DURATION_MIN;
                durationMax = ACTIVE_PARTICLE_DURATION_MAX;
                brightness = ACTIVE_PARTICLE_BRIGHTNESS;
            } else {
                // 被动模式参数
                particleCount = PASSIVE_PARTICLE_COUNT;
                sizeMin = PASSIVE_PARTICLE_SIZE_MIN;
                sizeMax = PASSIVE_PARTICLE_SIZE_MAX;
                durationMin = PASSIVE_PARTICLE_DURATION_MIN;
                durationMax = PASSIVE_PARTICLE_DURATION_MAX;
                brightness = PASSIVE_PARTICLE_BRIGHTNESS;
            }

            // 在范围内随机生成粒子
            for (int i = 0; i < particleCount; i++) {
                // 在圆形范围内随机生成位置
                float angle = random.nextFloat() * 360f;
                float distance = random.nextFloat() * range;

                Vector2f particlePos = new Vector2f(
                        ship.getLocation().x + (float) (Math.cos(Math.toRadians(angle)) * distance),
                        ship.getLocation().y + (float) (Math.sin(Math.toRadians(angle)) * distance));

                // 随机速度
                Vector2f velocity = new Vector2f(
                        (random.nextFloat() - 0.5f) * PARTICLE_VELOCITY_MAX,
                        (random.nextFloat() - 0.5f) * PARTICLE_VELOCITY_MAX);

                // 计算随机大小和持续时间
                float particleSize = random.nextFloat() * (sizeMax - sizeMin) + sizeMin;
                float particleDuration = random.nextFloat() * (durationMax - durationMin) + durationMin;

                // 为每个粒子生成随机alpha值和颜色
                int randomAlpha = PARTICLE_ALPHA_MIN + random.nextInt(PARTICLE_ALPHA_MAX - PARTICLE_ALPHA_MIN + 1);
                Color particleColor;

                if (systemOn) {
                    // 主动模式：随机彩色（红、绿、蓝）
                    int colorChoice = random.nextInt(3);
                    Color baseColor;
                    switch (colorChoice) {
                        case 0:
                            baseColor = ACTIVE_PARTICLE_COLOR_RED_BASE;
                            break;
                        case 1:
                            baseColor = ACTIVE_PARTICLE_COLOR_GREEN_BASE;
                            break;
                        default:
                            baseColor = ACTIVE_PARTICLE_COLOR_BLUE_BASE;
                            break;
                    }
                    particleColor = new Color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(),
                            randomAlpha);
                } else {
                    // 被动模式：白色
                    Color baseColor = PASSIVE_PARTICLE_COLOR_BASE;
                    particleColor = new Color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(),
                            randomAlpha);
                }

                // 生成粒子
                engine.addSmoothParticle(
                        particlePos,
                        velocity,
                        particleSize,
                        brightness,
                        particleDuration,
                        particleColor);
            }
        }

        /**
         * 对目标舰船施加干扰效果
         */
        private void apply(ShipAPI target) {
            if (!target.hasListenerOfClass(shipDebuff.class)) {
                // 获取干扰源的系统图标和名称用于显示
                String iconSprite = ship.getSystem().getSpecAPI().getIconSpriteName();
                String systemName = ship.getSystem().getSpecAPI().getName();
                target.addListener(new shipDebuff(target, iconSprite, systemName));
            }
            target.addTag(tag);
        }

        /**
         * 移除目标舰船的干扰效果
         */
        private void unapply(ShipAPI target) {
            target.getTags().remove(tag);
        }
    }

    /**
     * 舰船Debuff效果类 - 处理武器射程减少和伤害减免
     */
    private static class shipDebuff implements AdvanceableListener, DamageTakenModifier {
        final ShipAPI ship; // 受影响的舰船

        Object key = new Object(); // 状态显示的唯一键
        final String spriteName; // 状态图标
        final String systemName; // 系统名称
        final String id = "Moci_MissileJammerListener";

        private shipDebuff(ShipAPI ship, String spriteName, String systemName) {
            this.ship = ship;
            this.spriteName = spriteName;
            this.systemName = systemName;
            // 如果舰船没有豁免标签，则应用debuff效果
            if (!ship.hasTag(playerSkillBanTag)) {
                apply();
            }
        }

        /**
         * 应用武器射程减少效果
         * 根据舰船规格应用不同的削弱倍率：
         * - 主力舰：0.5倍
         * - 巡洋舰：0.55倍
         * - 驱逐舰：0.6倍
         * - 护卫舰：0.7倍
         * - 其他：0.7倍
         */
        private void apply() {
            MutableShipStatsAPI stats = ship.getMutableStats();

            // 根据舰船规格确定削弱倍率
            float rangeMultiplier = getRangeMultiplierByHullSize(ship.getHullSize());

            // 应用射程削弱到所有武器类型
            stats.getBallisticWeaponRangeBonus().modifyMult(id, rangeMultiplier);
            stats.getEnergyWeaponRangeBonus().modifyMult(id, rangeMultiplier);
            stats.getMissileWeaponRangeBonus().modifyMult(id, rangeMultiplier);
        }

        /**
         * 根据舰船规格获取射程削弱倍率
         */
        private float getRangeMultiplierByHullSize(ShipAPI.HullSize hullSize) {
            switch (hullSize) {
                case CAPITAL_SHIP:
                    return 0.5f; // 主力舰：最强削弱
                case CRUISER:
                    return 0.55f; // 巡洋舰：强削弱
                case DESTROYER:
                    return 0.6f; // 驱逐舰：中等削弱
                case FRIGATE:
                    return 0.6f; // 护卫舰：轻度削弱
                default:
                    return 0.6f; // 其他类型：轻度削弱
            }
        }

        /**
         * 移除武器射程减少效果
         */
        private void unapply() {
            MutableShipStatsAPI stats = ship.getMutableStats();
            stats.getBallisticWeaponRangeBonus().unmodify(id);
            stats.getEnergyWeaponRangeBonus().unmodify(id);
            stats.getMissileWeaponRangeBonus().unmodify(id);
        }

        @Override
        public void advance(float amount) {
            // 为玩家舰船显示状态信息
            if (!ship.hasTag(playerSkillBanTag) && ship == Global.getCombatEngine().getPlayerShip()) {
                float rangeMultiplier = getRangeMultiplierByHullSize(ship.getHullSize());
                int reductionPercent = (int) ((1f - rangeMultiplier) * 100);
                String statusText = "武器射程降低" + reductionPercent + "%";
                Global.getCombatEngine().maintainStatusForPlayerShip(key, spriteName, systemName, statusText, true);
            }

            // 检查是否还有干扰标签，如果没有则清理监听器
            boolean done = true;
            for (String tag : ship.getTags()) {
                if (tag != null && tag.startsWith("Moci_MissileJammer_")) {
                    done = false;
                    break;
                }
            }
            if (done) {
                ship.removeListener(this);
                unapply();
            }
        }

        @Override
        public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage, Vector2f point,
                boolean shieldHit) {
            if (param != null) {
                ShipAPI source = null;

                // 获取伤害来源舰船
                if (param instanceof DamagingProjectileAPI) {
                    DamagingProjectileAPI p = (DamagingProjectileAPI) param;
                    source = p.getSource();
                } else if (param instanceof BeamAPI) {
                    BeamAPI p = (BeamAPI) param;
                    source = p.getSource();
                }

                if (source != null) {
                    // 检查伤害来源是否为干扰源
                    boolean hasTag = false;
                    for (String tag : source.getTags()) {
                        if (tag != null && tag.startsWith("Moci_MissileJammer_")) {
                            if (ship.hasTag(tag)) {
                                hasTag = true;
                                break;
                            }
                        }
                    }
                    // 如果不是来自干扰源的伤害，则减少80%伤害
                    if (!hasTag) {
                        damage.getModifier().modifyMult("Moci_MissileJammer", 0.2f);
                        return "Moci_MissileJammer";
                    }
                }
            }
            return null;
        }
    }
}
