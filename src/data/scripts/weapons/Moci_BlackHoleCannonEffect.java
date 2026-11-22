package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

import data.scripts.weapons.render.Moci_BlackHoleAccretionRenderer;

/**
 * 黑洞炮效果：引力炮的华丽版。
 * - 引力牵引、潮汐伤害、消散冲击等玩法完全沿用 {@link Moci_GravityCannonEffect}，数值不变
 * - 额外为每枚投射物登记 {@link Moci_BlackHoleAccretionRenderer}，画吸积云、旋臂、光子环、横向光晕和电丝
 * 用法：把武器 .wpn 的 everyFrameEffect 指向本类，接线方式与原引力炮相同。
 */
public class Moci_BlackHoleCannonEffect extends Moci_GravityCannonEffect {

    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        // 先走原流程：投射物在 Moci_SingularityRenderer 登记后，吸积云才读得到位置和视界半径
        super.onFire(projectile, weapon, engine);

        Moci_BlackHoleAccretionRenderer accretion = Moci_BlackHoleAccretionRenderer.getOrCreate();
        if (accretion != null) {
            accretion.track(projectile);
        }
    }
}
