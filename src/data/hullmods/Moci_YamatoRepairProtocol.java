package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

import java.util.HashMap;
import java.util.Map;

/**
 * 波动引擎修复协议（一次性船插）
 *
 * 设计目标：
 * 1. 仅允许安装在指定三艘船及其 D-mod 版本上；
 * 2. 安装时消耗一个“波动引擎”特殊物品；
 * 3. 安装后在战役层将舰体替换为 repaired 版本；
 * 4. 完成替换后船插自我移除（不保留任何持续效果）。
 *
 * 说明：
 * - 实际替换逻辑优先在 {@link #applyEffectsBeforeShipCreation(ShipAPI.HullSize, MutableShipStatsAPI, String)} 执行，
 *   以实现安装后立即生效；同时保留战役层兜底处理。
 */
public class Moci_YamatoRepairProtocol extends BaseHullMod {
    private static final String TEXT_ID = "Moci_YamatoRepairProtocol";

    /** 本船插 ID（需与 hull_mods.csv 中配置一致） */
    private static final String HULLMOD_ID = "Moci_YamatoRepairProtocol";
    /** 安装本船插所需的特殊物品 ID */
    private static final String REQUIRED_ITEM_ID = "Moci_wave_motion_engine";

    /**
     * 支持的“原始舰体 -> 修复舰体”映射表。
     * Key：允许安装的基础舰体 ID
     * Value：安装后替换到的 repaired 舰体 ID
     */
    private static final Map<String, String> SOURCE_TO_TARGET = new HashMap<String, String>();
    static {
        SOURCE_TO_TARGET.put("Moci_Yamato", "Moci_Yamato_repaired");
        SOURCE_TO_TARGET.put("Moci_Yamato_ET", "Moci_Yamato_ET_repaired");
        SOURCE_TO_TARGET.put("Moci_Syunran", "Moci_Syunran_repaired");
    }

    /**
     * 装配界面实时回调：
     * 在这里执行“一次性替换并自删”，实现点击安装后立即生效。
     */
    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        applyProtocolInRefit(stats);
    }

    /**
     * 战役层每帧回调：
     * 作为兜底执行，防止旧存档/特殊路径未触发装配回调。
     */
    @Override
    public void advanceInCampaign(FleetMemberAPI member, float amount) {
        applyProtocolNow(member, member != null ? member.getVariant() : null);
    }

    private void applyProtocolInRefit(MutableShipStatsAPI stats) {
        if (stats == null || stats.getVariant() == null) {
            return;
        }

        ShipVariantAPI current = stats.getVariant();
        boolean hasThisMod = current.hasHullMod(HULLMOD_ID)
                || (current.getPermaMods() != null && current.getPermaMods().contains(HULLMOD_ID));
        if (!hasThisMod) {
            return;
        }

        ShipHullSpecAPI spec = current.getHullSpec();
        String sourceHullId = resolveSupportedSourceHullId(
                spec != null ? spec.getHullId() : null,
                spec != null ? spec.getDParentHullId() : null
        );
        String targetHullId = SOURCE_TO_TARGET.get(sourceHullId);
        if (targetHullId == null) {
            current.removePermaMod(HULLMOD_ID);
            current.removeMod(HULLMOD_ID);
            return;
        }

        try {
            FleetMemberAPI member = stats.getFleetMember();
            if (member != null && member.getVariant() != null) {
                ShipVariantAPI v2 = member.getVariant().clone();
                v2.setSource(VariantSource.REFIT);
                v2.setHullVariantId(Misc.genUID());
                member.setVariant(v2, false, false);
                current = stats.getVariant();
                if (current == null) {
                    return;
                }
            }

            current.setHullSpecAPI(Global.getSettings().getHullSpec(targetHullId));
            current.removePermaMod(HULLMOD_ID);
            current.removeMod(HULLMOD_ID);
            consumeRequiredItemFromCargo();

            if (Global.getSector() != null && Global.getSector().getPlayerFleet() != null) {
                Global.getSector().getPlayerFleet().forceSync();
            }
        } catch (Exception ex) {
            Global.getLogger(Moci_YamatoRepairProtocol.class).warn("Failed to apply wave motion engine protocol in refit", ex);
        }
    }

    private void applyProtocolNow(FleetMemberAPI member, ShipVariantAPI current) {
        // 基础空值保护，避免 NPE
        if (current == null) {
            return;
        }
        // 只处理玩家舰队，避免影响 NPC 舰队
        if (member == null || member.getHullSpec() == null
                || member.getFleetData() == null || member.getFleetData().getFleet() == null
                || !member.getFleetData().getFleet().isPlayerFleet()) {
            return;
        }


        // 只有当前舰体确实带有本船插时才继续
        boolean hasThisMod = current.hasHullMod(HULLMOD_ID)
                || (current.getPermaMods() != null && current.getPermaMods().contains(HULLMOD_ID));
        if (!hasThisMod) {
            return;
        }

        // 解析当前舰体对应的“基础源 hullId”（兼容 D-mod）
        String sourceHullId = resolveSupportedSourceHullId(member.getHullSpec().getHullId(), member.getHullSpec().getDParentHullId());
        String targetHullId = SOURCE_TO_TARGET.get(sourceHullId);
        if (targetHullId == null) {
            // 理论上不会走到这里（因为 isApplicableToShip 已限制），
            // 但为了容错：若未匹配到目标舰体，则只移除本船插，避免卡死。
            removeSelfOnly(member);
            return;
        }

        try {
            // 克隆 variant，避免直接修改原对象带来的引用副作用
            ShipVariantAPI updated = current.clone();
            updated.setSource(VariantSource.REFIT);
            updated.setHullVariantId(Misc.genUID());

            // 将舰体规格替换为 repaired 版本
            updated.setHullSpecAPI(Global.getSettings().getHullSpec(targetHullId));

            // 本船插自删除：无持续效果，仅承担“一次性转换”职责
            updated.removePermaMod(HULLMOD_ID);
            updated.removeMod(HULLMOD_ID);

            // 应用新 variant
            member.setVariant(updated, false, true);
            consumeRequiredItemFromCargo();

            // 强制同步玩家舰队数据，确保 UI 和战役数据立即一致
            if (Global.getSector() != null && Global.getSector().getPlayerFleet() != null) {
                Global.getSector().getPlayerFleet().forceSync();
            }
        } catch (Exception ex) {
            Global.getLogger(Moci_YamatoRepairProtocol.class).warn("Failed to apply wave motion engine protocol", ex);
        }
    }

    /**
     * 容错移除：当无法识别目标舰体时，仅移除本船插，避免异常循环。
     */
    private void removeSelfOnly(FleetMemberAPI member) {
        try {
            ShipVariantAPI current = member.getVariant();
            ShipVariantAPI updated = current.clone();
            updated.setSource(VariantSource.REFIT);
            updated.setHullVariantId(Misc.genUID());
            updated.removePermaMod(HULLMOD_ID);
            updated.removeMod(HULLMOD_ID);
            member.setVariant(updated, false, true);
        } catch (Exception ex) {
            Global.getLogger(Moci_YamatoRepairProtocol.class).warn("Failed to remove wave motion engine protocol", ex);
        }
    }

    private void consumeRequiredItemFromCargo() {
        try {
            if (Global.getSector() == null || Global.getSector().getPlayerFleet() == null
                    || Global.getSector().getPlayerFleet().getCargo() == null) {
                return;
            }
            CargoAPI cargo = Global.getSector().getPlayerFleet().getCargo();
            cargo.removeItems(
                    CargoAPI.CargoItemType.SPECIAL,
                    new SpecialItemData(REQUIRED_ITEM_ID, null),
                    1f
            );
        } catch (Exception ex) {
            Global.getLogger(Moci_YamatoRepairProtocol.class).warn("Failed to consume required wave motion engine", ex);
        }
    }

    /**
     * 安装需求物品：波动引擎（特殊物品）。
     */
    @Override
    public CargoStackAPI getRequiredItem() {
        return Global.getSettings().createCargoStack(
                CargoAPI.CargoItemType.SPECIAL,
                new SpecialItemData(REQUIRED_ITEM_ID, null),
                null
        );
    }

    /**
     * 装配限制：仅允许配置在目标三舰及其 D-mod 版本上。
     */
    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return getApplicableSourceHullId(ship) != null;
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        if (isApplicableToShip(ship)) {
            return null;
        }
        return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_reason");
    }

    /**
     * 从 ShipAPI 解析可用的基础源 hullId。
     * 返回 null 代表不在允许列表中。
     */
    private String getApplicableSourceHullId(ShipAPI ship) {
        if (ship == null || ship.getHullSpec() == null) {
            return null;
        }
        return resolveSupportedSourceHullId(ship.getHullSpec().getHullId(), ship.getHullSpec().getDParentHullId());
    }

    /**
     * 解析支持的源 hullId：
     * 1. 优先使用 dParentHullId（官方 D-hull 的可靠来源）；
     * 2. 否则尝试对 hullId 去除常见 D-mod 后缀再匹配。
     */
    private String resolveSupportedSourceHullId(String hullId, String dParentHullId) {
        if (dParentHullId != null && SOURCE_TO_TARGET.containsKey(dParentHullId)) {
            return dParentHullId;
        }
        String normalized = normalizeHullId(hullId);
        if (SOURCE_TO_TARGET.containsKey(normalized)) {
            return normalized;
        }
        return null;
    }

    /**
     * 归一化 hullId：
     * - 兼容 _default_D / _D / _d 结尾；
     * - 其余情况原样返回。
     */
    private String normalizeHullId(String hullId) {
        if (hullId == null) {
            return null;
        }
        if (hullId.endsWith("_default_D")) {
            return hullId.substring(0, hullId.length() - "_default_D".length());
        }
        if (hullId.endsWith("_D")) {
            return hullId.substring(0, hullId.length() - "_D".length());
        }
        if (hullId.endsWith("_d")) {
            return hullId.substring(0, hullId.length() - "_d".length());
        }
        return hullId;
    }
}
