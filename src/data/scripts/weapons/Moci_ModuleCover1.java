package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * 模块护甲动画控制器 版本1
 *
 * <p>这个脚本挂在一个“覆盖层/外壳”武器上，用武器动画帧来表现某个子模块装甲是否还存在。
 * 它本身不移动模块、不改变碰撞、不处理伤害，只负责根据关联子模块的存活状态切换当前武器的贴图帧。</p>
 *
 * 根据关联装甲模块的存活状态切换武器动画帧
 * 查找规则：当前槽位ID + "_ARMOR" 后缀
 * 例如：WS0001 -> WS0001_ARMOR
 *
 * <p>适用的槽位命名结构：</p>
 * <ul>
 *     <li>当前 COVER 武器槽位：WS0001</li>
 *     <li>被监控的装甲子模块槽位：WS0001_ARMOR</li>
 * </ul>
 *
 * <p>动画帧约定：</p>
 * <ul>
 *     <li>第 0 帧：装甲模块存在且存活，显示完整外观。</li>
 *     <li>第 1 帧：装甲模块不存在或已被摧毁，显示损坏/缺失外观。</li>
 * </ul>
 */
public class Moci_ModuleCover1 implements EveryFrameWeaponEffectPlugin {
    // 是否已经完成过一次槽位扫描。关联模块不会在战斗中频繁变化，所以只需要初始化一次。
    private boolean init = false;

    // 当前覆盖层武器关联的装甲子模块。找到后缓存，后续每帧只检查 isAlive()。
    private ShipAPI armorModule = null;

    /**
     * Starsector 每帧调用的武器效果入口。
     *
     * @param amount 本帧经过时间；本脚本没有插值/计时逻辑，因此不直接使用
     * @param engine 当前战斗引擎；为空或暂停时跳过处理
     * @param weapon 挂载本脚本的覆盖层武器
     */
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 暂停时不切换帧，避免暂停界面里状态继续被推进。
        if (engine == null || engine.isPaused()) {
            return;
        }

        // 初始化：第一次运行时根据当前武器槽位推导装甲模块槽位，并在子模块列表里查找。
        if (!init) {
            init = true;
            ShipAPI ship = weapon.getShip();
            
            // 获取当前武器的槽位 ID。没有槽位就无法按命名约定查找关联模块。
            String currentSlotId = weapon.getSlot() != null ? weapon.getSlot().getId() : null;
            if (currentSlotId == null) {
                return;
            }
            
            // 版本 1 的命名规则：当前武器槽位后面追加 _ARMOR。
            // 例如当前武器是 WS0001，则监控的子模块应挂在 WS0001_ARMOR。
            String armorSlotId = currentSlotId + "_ARMOR";
            
            // 子模块在 Starsector API 中也是 ShipAPI，通过 station slot id 与武器槽位命名关联。
            for (ShipAPI module : ship.getChildModulesCopy()) {
                if (module.getStationSlot() != null && 
                    module.getStationSlot().getId().equals(armorSlotId)) {
                    armorModule = module;
                    break;
                }
            }
        }

        // 控制动画帧：只要找不到模块或模块已死亡，都走损坏外观。
        if (weapon.getAnimation() != null) {
            int maxFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
            if (isArmorModulePresent(armorModule)) {
                // 装甲模块存活：显示完整状态（第0帧）
                weapon.getAnimation().setFrame(0);
            } else {
                // 装甲模块损坏或不存在：显示损坏状态（第1帧）
                weapon.getAnimation().setFrame(Math.min(1, maxFrame));
            }
        }
    }

    private boolean isArmorModulePresent(ShipAPI module) {
        return module != null
                && module.isAlive()
                && !module.isHulk()
                && module.getHitpoints() > 0f
                && module.getStationSlot() != null;
    }
}
