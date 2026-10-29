package data.scripts.weapons.blade;

import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * 光剑「动作层」接口：由我方光剑武器脚本定义，外部动作系统（例如 xi 的 ZetaSaberMotion）实现并注册。
 *
 * 设计目的：把「谁拥有武器回调」和「谁负责动作编排」解耦。
 * <ul>
 *   <li>武器回调、伤害结算、卡肉（{@link data.scripts.weapons.Moci_MeleeHitEffects}）、
 *       火线/烟雾/拖尾等表现始终由我方插件拥有；</li>
 *   <li>动作层只通过本接口「报告状态」，不再继承/包裹我方类，也不再临时改
 *       {@code weapon.getDamage()} 的 modifier 来压制或限流伤害；</li>
 *   <li>没有动作层时（{@link Moci_BladeMotionRegistry#get} 返回 null），我方行为与改造前完全一致。</li>
 * </ul>
 *
 * 实现方必须保证所有方法只在该武器所属的战斗线程内被调用。
 */
public interface Moci_BladeMotion {

    /**
     * 每帧一次，早于光束回调（等价于旧的 beforeWeapon 时机）。动作层在此推进自己的时钟与姿态。
     */
    void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon);

    /**
     * 每帧一次每束一次，在我方光束插件的回调里调用。动作层可在此接管刀光渲染。
     */
    void beamFrame(CombatEngineAPI engine, BeamAPI beam);

    /**
     * 引擎在回调之后仍会写一次瞄准角度；动作层可在此再压一次姿态，避免被引擎覆盖。
     */
    void lateReassert(WeaponAPI weapon);

    /**
     * 动作层是否正在接管这把武器的朝向。true 时我方不再写 {@code weapon.setCurrAngle}。
     */
    boolean ownsAim(WeaponAPI weapon);

    /**
     * 动作层当前是否处于「有效动作」状态。false 时我方不结算伤害（替代原来的 MUTE）。
     */
    boolean isActionActive(WeaponAPI weapon);

    /**
     * 本次爆发内允许结算的累计伤害比例（0~1）。我方用它对我方每目标满伤上限做乘法封顶。
     * 例如 0.25 → 单目标累计最多造成 25% 满伤；随阶段推进可继续补足到 1。
     */
    float damageBudgetFraction(WeaponAPI weapon);

    /**
     * 希望我方发布给弹丸拦截/斩切系统的光线阶段：0 = 不拦截，1 = 可命中。返回 0 也可用于「收招」。
     */
    int rayPhase(WeaponAPI weapon);

    /**
     * 希望我方发布的光线挥砍进度（0~1），供火线/接触特效使用。
     */
    float collisionProgress(WeaponAPI weapon);
}
