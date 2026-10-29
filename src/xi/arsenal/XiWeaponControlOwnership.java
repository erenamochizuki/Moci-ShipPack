package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import java.util.HashSet;
import java.util.Set;

/**
 * 控制器缺失时的「禁火归属」判据（单一职责：只回答一句话）。
 *
 * <p>背景：xi.arsenal 的静态钩子（{@link UnicornMagnumModes#beforeWeapon}、
 * {@link ZetaRifleModes#beforeWeapon}、{@link HiNuHandCannon#beforeCannon}、
 * {@link ZetaDefensiveMechanisms#guardCounterWeapon}）并不是全局钩子，而是由各武器
 * {@code .wpn} 的 {@code everyFrameEffect} 逐武器回调。它们原本在「控制器不存在」时
 * 无条件 {@code setForceNoFireOneFrame(true)}：对「控制器独占」武器这是无害的安全网，
 * 但对「本体装在可见槽、AI 本应原生开火」的真实武器就等于永久哑火（P1 缺陷类别）。</p>
 *
 * <p>判据只有一条：这门武器是否存在控制器之外的原生开火路径。</p>
 * <ul>
 *   <li>隐藏槽（{@code mount: HIDDEN}）与装饰槽/装饰武器不进武器组，AI 永远无法原生开火，
 *       只能由控制器用 {@code setForceFireOneFrame} 触发 → 禁火是无害安全网，保留。</li>
 *   <li>可见槽（TURRET / HARDPOINT / BUILT_IN 且非隐藏）武器由引擎原生 AI 驱动 → 控制器缺席时
 *       必须放行，否则就是永久哑火。</li>
 * </ul>
 *
 * <p>本类不写任何状态、不推进时钟、不接触武器组：唯一副作用是既定的禁火旗与一行
 * {@code [MOCI-GUARD]} 排查日志（按「船 + 槽 + 武器」去重，不刷屏）。</p>
 */
public final class XiWeaponControlOwnership {
    /** 常驻排查开关：只在真的「放行」时输出一行，便于复现与验收；关掉即完全静默。 */
    public static final boolean LOG_RELEASED = true;
    /** 项目专用日志前缀（禁止使用 devMode 作为开关）。 */
    public static final String LOG_PREFIX = "[MOCI-GUARD] ";
    private static final String REPORT_KEY = "xi.arsenal.weaponControlOwnership.reported";

    private XiWeaponControlOwnership() {}

    /** 控制器缺失时是否仍应禁火：true = 控制器独占武器（禁火无害），false = 应放行原生开火。 */
    public static boolean keepMutedWithoutController(WeaponAPI weapon) {
        return !nativeFirePossible(weapon);
    }

    /** 该武器是否存在控制器之外的原生开火路径。无法证明时返回 false（保守：当作独占）。 */
    public static boolean nativeFirePossible(WeaponAPI weapon) {
        if (weapon == null) return false;
        WeaponSlotAPI slot = weapon.getSlot();
        if (slot == null) return false;
        if (weapon.isDecorative() || slot.isDecorative()) return false;
        return !slot.isHidden();
    }

    /**
     * 控制器缺失时的统一收尾：独占武器继续禁火，可见槽真实武器把开火权交还引擎。
     *
     * @return true = 本帧已禁火（控制器独占）；false = 已放行原生开火
     */
    public static boolean settleWithoutController(WeaponAPI weapon) {
        if (keepMutedWithoutController(weapon)) {
            if (weapon != null) weapon.setForceNoFireOneFrame(true);
            return true;
        }
        reportReleased(weapon);
        return false;
    }

    /** 每艘船每把武器只记一次，证明「放行」确实发生在可控范围内。 */
    private static void reportReleased(WeaponAPI weapon) {
        if (!LOG_RELEASED) return;
        try {
            ShipAPI ship = weapon.getShip();
            if (ship == null) return;
            Object raw = ship.getCustomData().get(REPORT_KEY);
            Set<String> seen;
            if (raw instanceof Set<?> set) {
                @SuppressWarnings("unchecked") Set<String> cast = (Set<String>) set;
                seen = cast;
            } else {
                seen = new HashSet<>();
                ship.setCustomData(REPORT_KEY, seen);
            }
            String slot = weapon.getSlot() == null ? "?" : weapon.getSlot().getId();
            String key = slot + " / " + weapon.getId();
            if (!seen.add(key)) return;
            if (Global.getLogger(XiWeaponControlOwnership.class) != null) {
                Global.getLogger(XiWeaponControlOwnership.class).info(LOG_PREFIX
                        + "no controller on ship " + ship.getHullSpec().getHullId()
                        + " -> native fire released for " + key + " (visible slot, not controller-exclusive)");
            }
        } catch (RuntimeException ignored) {
            // 排查日志绝不允许影响开火判定。
        }
    }
}
