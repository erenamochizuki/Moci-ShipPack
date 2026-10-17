package data.scripts.weapons;

import com.fs.starfarer.api.combat.*;
import org.magiclib.util.MagicFakeBeam;
import org.lwjgl.util.vector.Vector2f;
import java.awt.Color;

public class Moci_Weapon_MagicBeam implements OnFireEffectPlugin {
    boolean diffuse = false;

    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        // 获取武器所属的飞船
        ShipAPI ship = weapon.getShip();

        // 获取武器的开火点作为起点坐标
        Vector2f from = weapon.getFirePoint(0); // 使用第一个开火点

        // 设置光束的参数
        float range = 250f; // 光束的最大射程
        float angle = weapon.getCurrAngle(); // 光束的角度，与武器当前角度一致
        float width = 10f; // 光束的宽度
        float fullDuration = 0.1f; // 光束完全可见的持续时间
        float fadingDuration = 0; // 光束淡出的持续时间
        float impactSize = 30f; // 冲击光晕的大小
        Color coreColor = Color.WHITE; // 光束的核心颜色
        Color fringeColor = new Color(184, 75, 255, 236); // 光束的边缘颜色
        float normalDamage = 3000f; // 光束的基础伤害
        DamageType damageType = DamageType.ENERGY; // 伤害类型
        float empDamage = 0f; // EMP伤害

        // 调用 MagicFakeBeam 生成光束
        MagicFakeBeam.spawnFakeBeam(
                engine, // 战斗引擎实例
                from, // 光束的起点坐标
                range, // 光束的最大射程
                angle, // 光束的角度
                width, // 光束的宽度
                fullDuration, // 光束完全可见的持续时间
                fadingDuration, // 光束淡出的持续时间
                impactSize, // 冲击光晕的大小
                coreColor, // 光束的核心颜色
                fringeColor, // 光束的边缘颜色
                normalDamage, // 光束的基础伤害
                damageType, // 伤害类型
                empDamage, // EMP伤害
                ship // 伤害来源，用于计算技能和舰船伤害加成
        );

        if( diffuse ){
            MagicFakeBeam.spawnAdvancedFakeBeam(
                    engine, // 战斗引擎实例
                    from, // 光束的起点坐标
                    range, // 光束的最大射程
                    angle, // 光束的角度
                    10f, // 光束在起点的宽度
                    30f, // 光束在终点的宽度
                    5f, // 光束宽度的变化率
                    "beam_core", // 光束核心的纹理
                    "beam_fringe", // 光束边缘的纹理
                    100f, // 纹理的视觉长度
                    50f, // 纹理的滚动速度（像素/秒）
                    10f, // 光束起点的淡入长度
                    10f, // 光束终点的淡出长度
                    fullDuration, // 光束完全可见的持续时间
                    fadingDuration, // 光束淡出的持续时间
                    impactSize, // 冲击光晕的大小
                    coreColor, // 光束的核心颜色
                    fringeColor, // 光束的边缘颜色
                    normalDamage, // 光束的基础伤害
                    damageType, // 伤害类型
                    empDamage, // EMP伤害
                    ship // 伤害来源
            );
        }

        // 移除发射的弹丸（如果需要）
        engine.removeEntity(projectile);
    }
}
