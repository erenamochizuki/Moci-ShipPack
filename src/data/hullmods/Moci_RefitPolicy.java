package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * 机动兵器的整备资格与触发阈值。
 *
 * 所有比例都使用 0～1 的数值，例如 0.4f 表示 40%。
 * 部署 CR、部署结构值来自游戏记录的出场状态，首次查询后保存在本机体上，
 * 因此战斗中受伤或完成维修，都不会重新计算本场的部署资格。
 *
 * 主要规则：
 * 1. 部署 CR 低于 40% 时，本场禁止整备；恰好 40% 允许。
 *    战斗中的 CR 返航阈值另按 max(部署 CR × 50%, 40%) 计算。
 * 2. 部署结构低于 50% 时，本场禁止整备。
 *    部署结构为 50%～不足 70% 时，结构触发线为 50%；达到 70% 时触发线为 70%。
 * 3. 整备完成或中止后，必须经过 RepairTracker 维护的 3 秒冷却。
 *
 * 本类提供资格和阈值；LandingSequence 使用当前 CR、结构和弹药判断是否需要返航，
 * LandingAI 与 RepairBayScript 负责实际起降和维修。
 */
public final class Moci_RefitPolicy {
    // 部署快照存放在每台机体自己的 customData 中，不会与其他机体共用。
    private static final String DEPLOYMENT_KEY = "Moci_RefitDeploymentState";

    /** 本场允许整备的最低部署 CR；低于 40% 禁止，等于 40% 允许。 */
    public static final float MIN_DEPLOYMENT_CR = 0.4f;

    /** 部署结构至少为 70% 的机体，当前结构不高于 70% 时触发整备。 */
    public static final float DEFAULT_HULL_THRESHOLD = 0.7f;

    /** 带伤部署机体的 50% 触发线，同时也是允许整备的最低部署结构。 */
    public static final float DAMAGED_DEPLOYMENT_HULL_THRESHOLD = 0.5f;

    /** 提供给导航提示的禁止原因；NONE 表示没有部署资格或冷却方面的限制。 */
    public enum BlockReason {
        NONE,                      // 部署资格合格，且没有剩余冷却
        DEPLOYMENT_CR_TOO_LOW,      // 部署 CR 过低，本场禁止整备
        DEPLOYMENT_HULL_TOO_LOW,    // 部署结构不足 50%，本场禁止整备
        COOLDOWN                   // 上次整备结束或中止后的冷却尚未结束
    }

    /** 首次查询时建立的部署快照；其中的阈值和永久禁止原因在本场内保持不变。 */
    private static final class DeploymentState {
        final float crThreshold;
        final float hullThreshold;
        final BlockReason blockReason;

        DeploymentState(ShipAPI ship) {
            // 使用游戏保存的部署值，而非查询这一刻的剩余 CR 和结构。
            float deploymentCR = ship.getCRAtDeployment();
            float deploymentHull = ship.getHullLevelAtDeployment();

            // 部署 CR 为 70% / 80% / 90% / 100% 时，阈值分别为 40% / 40% / 45% / 50%。
            // 战斗中的 CR 返航条件是“当前 CR 严格低于阈值”，等于阈值不会因 CR 返航。
            crThreshold = Math.max(deploymentCR * 0.5f, 0.4f);

            // 例如部署结构 60%，之后降至 50% 才因结构触发，避免出场即按 70% 线返航。
            // 部署结构恰好 50% 仍有整备资格；恰好 70% 使用默认 70% 线。
            hullThreshold = deploymentHull < DEFAULT_HULL_THRESHOLD
                    ? DAMAGED_DEPLOYMENT_HULL_THRESHOLD : DEFAULT_HULL_THRESHOLD;
            // 两项部署限制都不受之后的修复影响；同时不合格时优先提示 CR 原因。
            // NaN、无穷大等异常数据也按不合格处理，避免绕过比较检查。
            if (!Float.isFinite(deploymentCR) || deploymentCR < MIN_DEPLOYMENT_CR) {
                blockReason = BlockReason.DEPLOYMENT_CR_TOO_LOW;
            } else if (!Float.isFinite(deploymentHull)
                    || deploymentHull < DAMAGED_DEPLOYMENT_HULL_THRESHOLD) {
                blockReason = BlockReason.DEPLOYMENT_HULL_TOO_LOW;
            } else {
                blockReason = BlockReason.NONE;
            }
        }
    }

    // 工具类通过静态方法使用，无需创建实例。
    private Moci_RefitPolicy() { }

    /** 读取本机体的部署快照；首次调用时创建，后续复用。调用方须保证 ship 非空。 */
    private static DeploymentState deployment(ShipAPI ship) {
        Object existing = ship.getCustomData().get(DEPLOYMENT_KEY);
        if (existing instanceof DeploymentState) {
            return (DeploymentState) existing;
        }
        DeploymentState state = new DeploymentState(ship);
        ship.setCustomData(DEPLOYMENT_KEY, state);
        return state;
    }

    /**
     * 检查本场战斗的部署资格。
     * 维修流程进行中也可以调用；冷却、当前是否正在整备由其他入口单独检查。
     */
    public static boolean isEligibleThisBattle(ShipAPI ship) {
        return ship != null && deployment(ship).blockReason == BlockReason.NONE;
    }

    /**
     * 获取用于提示的禁止原因：优先返回整场有效的部署限制，再检查临时冷却。
     * 空对象返回 NONE，是否存在机体、是否存活等由 canStartRefit 负责拦截。
     */
    public static BlockReason getBlockReason(ShipAPI ship) {
        if (ship == null) return BlockReason.NONE;
        BlockReason reason = deployment(ship).blockReason;
        if (reason != BlockReason.NONE) return reason;
        Moci_MobileSuitRepairTracker tracker = Moci_MobileSuitRepairTracker.get(ship);
        return tracker != null && tracker.getCooldownRemaining() > 0f
                ? BlockReason.COOLDOWN : BlockReason.NONE;
    }

    /**
     * 手动、自动和僚机入口共用的“能否开始一次新整备”检查。
     * 机体必须存活且仍在战场中、部署资格合格、冷却结束，并且没有进行中的整备。
     * 该方法只判断能否开始；具体是否缺血、缺 CR、缺弹由 LandingSequence 判断。
     */
    public static boolean canStartRefit(ShipAPI ship) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (ship == null || engine == null || !ship.isAlive() || ship.isHulk()
                || !engine.isEntityInPlay(ship) || getBlockReason(ship) != BlockReason.NONE) {
            return false;
        }
        Moci_MobileSuitRepairTracker tracker = Moci_MobileSuitRepairTracker.get(ship);
        // 接近、维修和起飞阶段都算进行中，防止同一机体重复申请泊位。
        return tracker == null || !tracker.isActive();
    }

    /** 返回固定的 CR 触发阈值；空对象使用 40% 作为安全默认值。 */
    public static float getCRRefitThreshold(ShipAPI ship) {
        return ship == null ? 0.4f : deployment(ship).crThreshold;
    }

    /** 返回固定的结构触发阈值（50% 或 70%）；实际使用前还须通过部署资格检查。 */
    public static float getHullRefitThreshold(ShipAPI ship) {
        return ship == null ? DEFAULT_HULL_THRESHOLD : deployment(ship).hullThreshold;
    }
}
