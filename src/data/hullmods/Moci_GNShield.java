package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;

import data.scripts.weapons.render.Moci_GNShieldRendering_Shader;

import static data.scripts.weapons.render.Moci_GNShieldRendering_Shader.RENDER_KEY;

/**
 * 高性能GN护盾船插V2
 * 使用基于着色器的渲染器，性能更优，效果更佳
 */
public class Moci_GNShield extends BaseHullMod {

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 添加GN护盾脚本监听器
        if (!ship.hasListenerOfClass(Moci_GNShield_Script.class)) {
            ship.addListener(new Moci_GNShield_Script(ship));
        }
    }

    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        // 确保高性能渲染器已初始化
        if (!Global.getCombatEngine().getCustomData().containsKey(RENDER_KEY)) {
            Moci_GNShieldRendering_Shader renderer = Moci_GNShieldRendering_Shader.getInstance();

            // 输出渲染器状态信息
            if (renderer.isShaderInitialized()) {
                Global.getLogger(this.getClass()).info("GN Shield V2 using high-performance shader renderer");
            } else {
                Global.getLogger(this.getClass()).warn("GN Shield V2 shader failed, falling back to fixed pipeline");
            }
        }

        // 处理模块护盾
        if (ship.isShipWithModules()) {
            for (ShipAPI module : ship.getChildModulesCopy()) {
                if (!module.hasListenerOfClass(Moci_GNShield_Script.class)) {
                    module.addListener(new Moci_GNShield_Script(module));
                }
            }
        }
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return ship.getShield() != null && ship.getHullSpec().hasTag("moci_ms");
    }

}
