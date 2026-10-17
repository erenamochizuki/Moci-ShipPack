package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * 模块护甲动画控制器 版本2
 *
 * <p>这个脚本同样挂在一个“覆盖层/外壳”武器上，用武器动画帧表现对应子模块是否还活着。
 * 与 {@link Moci_ModuleCover1} 的主要区别是槽位命名规则不同：版本 2 假设当前武器本身带
 * "_COVER" 后缀，去掉该后缀后就是被监控的子模块槽位。</p>
 *
 * 根据关联装甲模块的存活状态切换武器动画帧
 * 查找规则：当前槽位ID 删除 "_COVER" 后缀
 * 例如：BACKPACK_R_COVER -> BACKPACK_R
 *       BACKPACK_L_COVER -> BACKPACK_L
 *       WS0001_COVER -> WS0001
 *
 * <p>适用的槽位命名结构：</p>
 * <ul>
 *     <li>当前 COVER 武器槽位：BACKPACK_R_COVER</li>
 *     <li>被监控的装甲/背包/模块槽位：BACKPACK_R</li>
 * </ul>
 *
 * <p>动画帧约定：</p>
 * <ul>
 *     <li>第 0 帧：关联模块存在且存活，显示完整外观。</li>
 *     <li>第 1 帧：关联模块不存在或已被摧毁，显示损坏/缺失外观。</li>
 * </ul>
 */
public class Moci_ModuleCover2 implements EveryFrameWeaponEffectPlugin {
    // 是否已经完成过一次槽位扫描。找到的模块会被缓存，避免每帧遍历所有子模块。
    private boolean init = false;

    // 当前覆盖层武器关联的装甲/部件子模块。
    private ShipAPI armorModule = null;

    /**
     * Starsector 每帧调用的武器效果入口。
     *
     * @param amount 本帧经过时间；本脚本没有计时动画，所以不直接使用
     * @param engine 当前战斗引擎；为空或暂停时跳过处理
     * @param weapon 挂载本脚本的 COVER 覆盖层武器
     */
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 暂停时不切换帧，保持暂停画面稳定。
        if (engine == null || engine.isPaused()) {
            return;
        }

        // 初始化：第一次运行时根据当前 COVER 武器槽位推导对应子模块槽位。
        if (!init) {
            init = true;
            ShipAPI ship = weapon.getShip();
            
            // 获取当前武器的槽位 ID。没有槽位就无法按命名规则建立关联。
            String currentSlotId = weapon.getSlot() != null ? weapon.getSlot().getId() : null;
            if (currentSlotId == null) {
                return;
            }
            
            // 版本 2 的命名规则：如果当前武器槽位以 _COVER 结尾，就去掉该后缀。
            // 例如 BACKPACK_R_COVER 会关联到子模块槽位 BACKPACK_R。
            // 如果没有 _COVER 后缀，则退化为使用当前槽位本身作为查找目标。
            String armorSlotId = currentSlotId;
            if (currentSlotId.endsWith("_COVER")) {
                armorSlotId = currentSlotId.substring(0, currentSlotId.length() - "_COVER".length());
            }
            
            // 子模块在 API 中表现为 ShipAPI，通过 station slot id 和推导出的 armorSlotId 匹配。
            for (ShipAPI module : ship.getChildModulesCopy()) {
                if (module.getStationSlot() != null && 
                    module.getStationSlot().getId().equals(armorSlotId)) {
                    armorModule = module;
                    break;
                }
            }
        }

        // 控制动画帧：找到了活着的模块就显示完整帧，否则显示损坏帧。
        if (weapon.getAnimation() != null) {
            int maxFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
            if (armorModule != null && armorModule.isAlive()) {
                // 装甲模块存活：显示完整状态（第0帧）
                weapon.getAnimation().setFrame(0);
            } else {
                // 装甲模块损坏或不存在：显示损坏状态（第1帧）
                weapon.getAnimation().setFrame(Math.min(1, maxFrame));
            }
        }
    }
}
