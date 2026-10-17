package data.scripts.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FleetEncounterContextPlugin;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.procgen.SalvageEntityGenDataSpec.DropData;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.SalvageEntity;
import com.fs.starfarer.api.util.Misc;

import data.scripts.Moci_bounty_plugin;

/**
 * Moci 的全局战后掉落监听器。
 *
 * 这个监听器只在原版已经生成“战后打捞货物”之后运行，然后向 loot 中追加 Moci 自己的额外掉落。
 * 这样做的好处是：掉落不会预先挂在目标舰队实体上，玩家脱离遭遇或没有真正获胜时，不应该提前吐出奖励。
 */
public class Moci_CombatLootListener extends BaseCampaignEventListener {
    // 三种抽卡珠子是 commodity，对 loot 使用 addCommodity()。
    private static final String VORTTEAR = "Moci_Vorttear";
    private static final String MOURNSCALE = "Moci_Mournscale";
    private static final String DRACARYX = "Moci_Dracaryx";

    // 失活的漩涡核心是 special item，对 loot 使用 addItems(SPECIAL, SpecialItemData, amount)。
    private static final String VORTEX_CORE = "Moci_vortexcore_Inactivation";

    // MociShipPack/data/campaign/procgen/drop_groups.csv 中已有的三档涡族珠子掉落组。
    private static final String PEARL_LOW = "Moci_pearl1";
    private static final String PEARL_MID = "Moci_pearl2";
    private static final String PEARL_HIGH = "Moci_pearl3";

    // 涡族舰队按舰队规模分档：小于 12 走低级组，12-24 走中级组，24 以上走高级组。
    private static final float LOW_DROP_GROUP = 12f;
    private static final float MID_DROP_GROUP = 24f;

    // 漩涡核心基础概率：0.0025 = 0.25%。
    // 实际概率还会乘以舰队强度缩放和玩家贡献度，详见 addVortexFleetDrops()。
    private static final float VORTEX_CORE_BASE_CHANCE = 0.0025f;

    public Moci_CombatLootListener() {
        super(false);
    }

    @Override
    public void reportEncounterLootGenerated(FleetEncounterContextPlugin plugin, CargoAPI loot) {
        // 总开关在 Moci_bounty_plugin 中，方便临时关闭整套额外战斗掉落。
        if (!Moci_bounty_plugin.isCombatLootDropsEnabled()) {
            return;
        }
        // 只处理玩家参与的战斗掉落；AI 内战和非战斗打捞不参与。
        if (plugin == null || loot == null || plugin.getBattle() == null || !plugin.getBattle().isPlayerInvolved()) {
            return;
        }

        // reportEncounterLootGenerated() 在战后 loot 界面出现前调用。
        // 若玩家不是胜利方，则移除本监听器管理的 Moci 掉落，防止舰队旧式 addDropRandom 残留导致脱离也掉物品。
        boolean playerWon = didPlayerWin(plugin);
        if (!playerWon) {
            removeManagedDrops(loot);
            return;
        }

        // 玩家贡献度用于避免玩家蹭 NPC 战斗时满额拿奖励。
        // 单挑通常为 1；有盟友参战时会低于 1。
        float playerContrib = Math.max(0f, Math.min(1f, plugin.computePlayerContribFraction()));
        if (playerContrib <= 0f) {
            return;
        }

        BattleAPI battle = plugin.getBattle();
        boolean vortexCoreDroppedThisEncounter = false;
        // 使用战斗开始时的非玩家侧 snapshot，包含被战斗消灭后已经从实体现状中消失的舰队。
        for (CampaignFleetAPI loser : battle.getNonPlayerSideSnapshot()) {
            if (loser == null) {
                continue;
            }

            // 用打捞种子派生随机数，保证同一场战斗的掉落结果稳定，不随 UI 刷新反复变化。
            Random random = Misc.getRandom(Misc.getSalvageSeed(loser), 4117);
            if (loser.getMemoryWithoutUpdate().getBoolean(Moci_GachaMobManager.MemKey)) {
                boolean droppedCore = addVortexFleetDrops(
                        loser,
                        loot,
                        random,
                        playerContrib,
                        !vortexCoreDroppedThisEncounter
                );
                vortexCoreDroppedThisEncounter = vortexCoreDroppedThisEncounter || droppedCore;
            } else {
                addVanillaFactionDrops(loser, loot, random, playerContrib);
            }
        }
    }

    private boolean didPlayerWin(FleetEncounterContextPlugin plugin) {
        CampaignFleetAPI winner = plugin.getWinner();
        BattleAPI battle = plugin.getBattle();
        return winner != null && battle != null && battle.onPlayerSide(winner);
    }

    private boolean addVortexFleetDrops(CampaignFleetAPI fleet, CargoAPI loot, Random random, float playerContrib,
            boolean canDropVortexCore) {
        float fleetSize = getFleetSize(fleet);
        List<DropData> dropRandom = new ArrayList<DropData>();

        // 涡族舰队继续沿用原有的 Moci_pearl1/2/3 掉落组，只是改为战后即时生成，而不是预先挂在舰队上。
        // chances 越高，掉落组会被抽取越多次；掉落组内部的 nothing 行控制每次抽取的空奖概率。
        DropData pearls = new DropData();
        if (fleetSize < LOW_DROP_GROUP) {
            pearls.group = PEARL_LOW;
            pearls.chances = Math.max(1, (int) fleetSize * 15);
        } else if (fleetSize < MID_DROP_GROUP) {
            pearls.group = PEARL_MID;
            pearls.chances = Math.max(1, (int) fleetSize * 8);
        } else {
            pearls.group = PEARL_HIGH;
            pearls.chances = Math.max(1, (int) fleetSize * 4);
        }
        dropRandom.add(pearls);

        CargoAPI extra = SalvageEntity.generateSalvage(random, 1f, 1f, 1f, 1f, null, dropRandom);
        loot.addAll(extra);

        // 漩涡核心实际概率 = 0.25% * 强度缩放 * 玩家贡献度。
        // 同一场遭遇最多只会加入 1 个核心；多支涡族舰队参战时，前面已经成功掉核心后，后续舰队不再 roll。
        // 强度缩放 = clamp(0.4 + fleetPoints / 240, 0.4, 2.5)。
        // 例子：
        // - 60 FP 舰队：scale = 0.65，单挑概率约 0.1625%。
        // - 120 FP 舰队：scale = 0.90，单挑概率约 0.225%。
        // - 240 FP 舰队：scale = 1.40，单挑概率约 0.35%。
        // - 480 FP 舰队：scale = 2.40，单挑概率约 0.60%。
        float strengthScale = clamp(0.4f + fleet.getFleetPoints() / 240f, 0.4f, 2.5f);
        if (canDropVortexCore && random.nextFloat() < VORTEX_CORE_BASE_CHANCE * strengthScale * playerContrib) {
            loot.addItems(CargoAPI.CargoItemType.SPECIAL, new SpecialItemData(VORTEX_CORE, null), 1);
            return true;
        }
        return false;
    }

    private void addVanillaFactionDrops(CampaignFleetAPI fleet, CargoAPI loot, Random random, float playerContrib) {
        // 只有 getDropProfile() 中列出的势力会掉珠子，其它势力完全不参与。
        DropProfile profile = getDropProfile(fleet.getFaction().getId());
        if (profile == null) {
            return;
        }

        // 原版势力珠子的实际概率 = 势力基础概率 * 舰队强度缩放 * 玩家贡献度。
        // 强度缩放 = clamp(0.5 + fleetPoints / 200, 0.5, 2)。
        // 约 100 FP 的舰队 scale=1；约 300 FP 以上封顶 scale=2。
        float scale = clamp(0.5f + fleet.getFleetPoints() / 200f, 0.5f, 2f) * playerContrib;
        rollCommodity(loot, random, VORTTEAR, profile.vorttearChance * scale);
        rollCommodity(loot, random, MOURNSCALE, profile.mournscaleChance * scale);
        rollCommodity(loot, random, DRACARYX, profile.dracaryxChance * scale);
    }

    private DropProfile getDropProfile(String factionId) {
        // 下方基础概率是“约 100 FP、玩家单独/满贡献击败一支舰队”时的概率。
        // 小舰队会被压低到最低 50%，大舰队最高翻倍。
        if (Factions.PIRATES.equals(factionId)) {
            // 海盗：涡珠 2%，涡鳞珠 0.25%，不掉涡龙珠。
            return new DropProfile(0.020f, 0.0025f, 0f);
        }
        if (Factions.LUDDIC_PATH.equals(factionId)) {
            // 卢德左径：涡珠 2.5%，涡鳞珠 0.35%，不掉涡龙珠。
            return new DropProfile(0.025f, 0.0035f, 0f);
        }
        if (Factions.REMNANTS.equals(factionId)) {
            // 余晖：涡珠 1.5%，涡鳞珠 0.75%，涡龙珠 0.10%。
            return new DropProfile(0.015f, 0.0075f, 0.0010f);
        }
        if (Factions.DERELICT.equals(factionId)) {
            // 遗弃舰队：涡珠 1%，涡鳞珠 0.30%，涡龙珠 0.05%。
            return new DropProfile(0.010f, 0.0030f, 0.0005f);
        }
        if (Factions.HEGEMONY.equals(factionId)) {
            // 霸主：涡珠 0.5%，涡鳞珠 0.10%，不掉涡龙珠。
            return new DropProfile(0.005f, 0.0010f, 0f);
        }
        return null;
    }

    private void rollCommodity(CargoAPI loot, Random random, String commodityId, float chance) {
        if (chance <= 0f) {
            return;
        }
        if (random.nextFloat() < Math.min(chance, 1f)) {
            loot.addCommodity(commodityId, 1);
        }
    }

    private float getFleetSize(CampaignFleetAPI fleet) {
        // 这里使用 hull size 转换出的规模值，而不是 FP。
        // 它只用于决定涡族珠子掉落组的档位和抽取次数，保持和旧逻辑接近。
        float fleetSize = 0f;
        for (FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
            fleetSize += Misc.getSizeNum(member.getHullSpec().getHullSize());
        }
        return Math.max(1f, fleetSize);
    }

    private void removeManagedDrops(CargoAPI loot) {
        // 保险逻辑：如果玩家不是胜利方，但旧存档或旧代码已经把这些掉落预挂到了舰队上，
        // 原版战利品生成流程可能已经把它们放进 loot。这里把本监听器管理的物品移除。
        removeCommodityIfPresent(loot, VORTTEAR);
        removeCommodityIfPresent(loot, MOURNSCALE);
        removeCommodityIfPresent(loot, DRACARYX);

        SpecialItemData core = new SpecialItemData(VORTEX_CORE, null);
        float cores = loot.getQuantity(CargoAPI.CargoItemType.SPECIAL, core);
        if (cores > 0f) {
            loot.removeItems(CargoAPI.CargoItemType.SPECIAL, core, cores);
        }
    }

    private void removeCommodityIfPresent(CargoAPI loot, String commodityId) {
        float amount = loot.getCommodityQuantity(commodityId);
        if (amount > 0f) {
            loot.removeCommodity(commodityId, amount);
        }
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static class DropProfile {
        // 每个势力的三种珠子基础概率。
        // 这些值会在 addVanillaFactionDrops() 中乘以舰队强度缩放和玩家贡献度。
        private final float vorttearChance;
        private final float mournscaleChance;
        private final float dracaryxChance;

        private DropProfile(float vorttearChance, float mournscaleChance, float dracaryxChance) {
            this.vorttearChance = vorttearChance;
            this.mournscaleChance = mournscaleChance;
            this.dracaryxChance = dracaryxChance;
        }
    }
}
