package data.hullmods;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.FighterLaunchBayAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import data.scripts.util.Moci_TextLoader;

public class Moci_MSRepairBay extends BaseHullMod {
    private static final String TEXT_ID = "Moci_MSRepairBay";

    // MS战机的标签 - 只对有这些标签的战机生效
    private static final String MOCI_MS_TAG = "moci_ms";
    private static final String MOD_ID = "moci_ms_repair_bay";

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 重置响应标记，确保每次部署时都能正常工作
        stats.getFighterRefitTimeMult().unmodify(MOD_ID);
    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 检查是否装备了MS战机
        boolean hasMSWings = false;
        for (String wingId : ship.getVariant().getFittedWings()) {
            if (wingId != null && !wingId.isEmpty()) {
                FighterWingSpecAPI spec = Global.getSettings().getFighterWingSpec(wingId);
                if (spec != null && spec.hasTag(MOCI_MS_TAG)) {
                    hasMSWings = true;
                    break;
                }
            }
        }

        if (hasMSWings) {
            // 添加MS整备监控系统
            if (!ship.hasListenerOfClass(MSRepairBayListener.class)) {
                ship.addListener(new MSRepairBayListener(ship));
            }
        }
    }

    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        if (ship.getOriginalOwner() == -1) {
            return; // 在改装界面中不执行
        }

        boolean allDeployed = true;
        boolean hasMSWings = false;

        // 检查所有战机是否都已部署完毕
        for (FighterLaunchBayAPI bay : ship.getLaunchBaysCopy()) {
            if (bay.getWing() != null) {
                FighterWingSpecAPI wingSpec = bay.getWing().getSpec();
                int deployed = bay.getWing().getWingMembers().size();
                int maxTotal = wingSpec.getNumFighters();

                if (isMSFighter(wingSpec)) {
                    hasMSWings = true;
                }

                if (deployed < maxTotal) {
                    allDeployed = false;
                }
            }
        }

        // 当所有战机都部署完毕且有MS战机时，设置响应标记
        if (ship.getMutableStats().getFighterRefitTimeMult().getPercentStatMod(MOD_ID) == null &&
                allDeployed && hasMSWings) {
            ship.getMutableStats().getFighterRefitTimeMult().modifyPercent(MOD_ID, 0.1f);
        }
    }

    /**
     * 检查战机是否是MS类型
     */
    private boolean isMSFighter(FighterWingSpecAPI wingSpec) {
        return wingSpec.hasTag(MOCI_MS_TAG);
    }

    /**
     * MS整备库监控系统
     * 监控MS战机缺失情况，提供智能快速补充和冷却管理
     */
    public static class MSRepairBayListener implements AdvanceableListener {
        protected ShipAPI ship;
        protected java.util.Map<FighterLaunchBayAPI, Float> bayCooldowns = new java.util.HashMap<>();

        public MSRepairBayListener(ShipAPI ship) {
            this.ship = ship;
        }

        public void advance(float amount) {
            if (ship.getCurrentCR() < 0)
                return;

            // 检查是否已经设置了响应标记（所有战机部署完毕）
            if (ship.getMutableStats().getFighterRefitTimeMult().getPercentStatMod(MOD_ID) == null) {
                return; // 还未开始响应
            }

            // 更新所有机库的冷却时间
            java.util.Iterator<java.util.Map.Entry<FighterLaunchBayAPI, Float>> iterator = bayCooldowns.entrySet()
                    .iterator();
            while (iterator.hasNext()) {
                java.util.Map.Entry<FighterLaunchBayAPI, Float> entry = iterator.next();
                float newCooldown = entry.getValue() - amount;
                if (newCooldown <= 0) {
                    iterator.remove(); // 冷却结束，移除记录
                } else {
                    entry.setValue(newCooldown); // 更新冷却时间
                }
            }

            // 检查MS机库状态并进行快速补充
            for (FighterLaunchBayAPI bay : ship.getLaunchBaysCopy()) {
                if (bay.getWing() == null)
                    continue;

                FighterWingSpecAPI wingSpec = bay.getWing().getSpec();
                if (!wingSpec.hasTag(MOCI_MS_TAG))
                    continue; // 只处理MS战机

                // 检查是否在冷却中
                if (bayCooldowns.containsKey(bay))
                    continue;

                // 检查是否有缺失的MS战机
                int deployed = bay.getWing().getWingMembers().size();
                int maxTotal = wingSpec.getNumFighters();
                if (deployed < maxTotal) {
                    // 快速补充1架MS战机
                    bay.setFastReplacements(1);

                    // 计算冷却时间：(基础整备时间 * 舰船整备加成) * 0.5f
                    float baseRefitTime = wingSpec.getRefitTime();
                    float shipRefitMult = ship.getMutableStats().getFighterRefitTimeMult().getModifiedValue();
                    float cooldownTime = (baseRefitTime * shipRefitMult) * 0.5f;

                    // 设置该机库的冷却时间
                    bayCooldowns.put(bay, cooldownTime);
                }
            }

            // 为玩家舰船显示状态
            if (Global.getCurrentState() == GameState.COMBAT &&
                    Global.getCombatEngine() != null && Global.getCombatEngine().getPlayerShip() == ship) {

                int activeCooldowns = bayCooldowns.size();
                String status = activeCooldowns > 0
                        ? Moci_TextLoader.getTextWithReplacements(TEXT_ID, "status.cooldown",
                                Moci_TextLoader.mapOf("bay_count", String.valueOf(activeCooldowns)))
                        : Moci_TextLoader.getText(TEXT_ID, "status.idle");

                Global.getCombatEngine().maintainStatusForPlayerShip("moci_ms_repair",
                        Global.getSettings().getSpriteName("ui", "icon_tactical_cr_penalty"),
                        Moci_TextLoader.getText(TEXT_ID, "status.title"), status, false);
            }
        }
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        switch (index) {
            case 0:
            return "50%"; // 整备时间倍率
            case 1:
            return "50%"; // 每次快速补充间隔
            default:
                return null;
        }
    }
}
