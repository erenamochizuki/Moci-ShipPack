package data.scripts.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FleetEncounterContextPlugin;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
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

    // 只保存不足一个涡珠的进度，跨势力、战斗和存读档累积，不预发物品。
    private static final String VORTTEAR_PROGRESS = "$Moci_CombatLootVorttearProgress";
    private transient CargoAPI lastProcessedLoot;

    // 失活的漩涡核心是 special item，对 loot 使用 addItems(SPECIAL, SpecialItemData, amount)。
    private static final String VORTEX_CORE = "Moci_vortexcore_Inactivation";

    // MociShipPack/data/campaign/procgen/drop_groups.csv 中已有的三档涡族珠子掉落组。
    private static final String PEARL_LOW = "Moci_pearl1";
    private static final String PEARL_MID = "Moci_pearl2";
    private static final String PEARL_HIGH = "Moci_pearl3";

    // 涡族按实际损失的舰体规模分档：小于 12 走低级组，12 至不足 24 走中级组，24 以上走高级组。
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
        // 同一份战利品重复通知时，不重复奖励或推进累计进度。
        if (lastProcessedLoot == loot) {
            return;
        }
        lastProcessedLoot = loot;

        BattleAPI battle = plugin.getBattle();
        boolean vortexCoreDroppedThisEncounter = false;
        // 侧列表只保存舰队引用；战损必须另从舰船快照中读取。
        for (CampaignFleetAPI loser : battle.getNonPlayerSideSnapshot()) {
            if (loser == null) {
                continue;
            }

            List<FleetMemberAPI> lost = Misc.getSnapshotMembersLost(loser);
            float defeatedFP = getDefeatedFleetPoints(lost);
            if (defeatedFP <= 0f) {
                continue;
            }
            // 仍可复现同一场战损的结果，但同一舰队分多次交战不会反复使用同一随机序列。
            long seed = Misc.getSalvageSeed(loser);
            for (FleetMemberAPI member : lost) {
                seed = 31L * seed + member.getId().hashCode();
            }
            Random random = Misc.getRandom(seed, 4117);
            if (loser.getMemoryWithoutUpdate().getBoolean(Moci_GachaMobManager.MemKey)) {
                boolean droppedCore = addVortexFleetDrops(
                        lost,
                        defeatedFP,
                        loot,
                        random,
                        playerContrib,
                        !vortexCoreDroppedThisEncounter
                );
                vortexCoreDroppedThisEncounter = vortexCoreDroppedThisEncounter || droppedCore;
            } else {
                addVanillaFactionDrops(loser, defeatedFP, loot, random, playerContrib);
            }
        }
    }

    private boolean didPlayerWin(FleetEncounterContextPlugin plugin) {
        CampaignFleetAPI winner = plugin.getWinner();
        BattleAPI battle = plugin.getBattle();
        return winner != null && battle != null && battle.onPlayerSide(winner);
    }

    private boolean addVortexFleetDrops(List<FleetMemberAPI> lost, float defeatedFP, CargoAPI loot, Random random, float playerContrib,
            boolean canDropVortexCore) {
        float fleetSize = getFleetSize(lost);
        List<DropData> dropRandom = new ArrayList<DropData>();

        // 涡族舰队继续沿用原有的 Moci_pearl1/2/3 掉落组，只是改为战后即时生成，而不是预先挂在舰队上。
        // chances 越高，掉落组会被抽取越多次；掉落组内部的 nothing 行控制每次抽取的空奖概率。
        DropData pearls = new DropData();
        if (fleetSize < LOW_DROP_GROUP) {
            pearls.group = PEARL_LOW;
            pearls.chances = (int) fleetSize * 15;
        } else if (fleetSize < MID_DROP_GROUP) {
            pearls.group = PEARL_MID;
            pearls.chances = (int) fleetSize * 8;
        } else {
            pearls.group = PEARL_HIGH;
            pearls.chances = (int) fleetSize * 4;
        }
        // 对抽取次数作随机取整，低贡献不会被强制保底为一次。
        pearls.chances = rollAmount(random, pearls.chances * playerContrib);
        dropRandom.add(pearls);

        if (pearls.chances > 0) {
            CargoAPI extra = SalvageEntity.generateSalvage(random, 1f, 1f, 1f, 1f, null, dropRandom);
            loot.addAll(extra);
        }

        // 漩涡核心实际概率 = 0.25% * 强度缩放 * 玩家贡献度。
        // 同一场遭遇最多只会加入 1 个核心；多支涡族舰队参战时，前面已经成功掉核心后，后续舰队不再 roll。
        // 强度缩放 = clamp(0.4 + defeatedFP / 240, 0.4, 2.5)。
        // 例子：
        // - 60 FP 舰队：scale = 0.65，单挑概率约 0.1625%。
        // - 120 FP 舰队：scale = 0.90，单挑概率约 0.225%。
        // - 240 FP 舰队：scale = 1.40，单挑概率约 0.35%。
        // - 480 FP 舰队：scale = 2.40，单挑概率约 0.60%。
        float strengthScale = clamp(0.4f + defeatedFP / 240f, 0.4f, 2.5f);
        if (canDropVortexCore && random.nextFloat() < VORTEX_CORE_BASE_CHANCE * strengthScale * playerContrib) {
            loot.addItems(CargoAPI.CargoItemType.SPECIAL, new SpecialItemData(VORTEX_CORE, null), 1);
            return true;
        }
        return false;
    }

    private void addVanillaFactionDrops(CampaignFleetAPI fleet, float defeatedFP, CargoAPI loot, Random random, float playerContrib) {
        // 只有 getDropProfile() 中列出的势力会掉珠子，其它势力完全不参与。
        DropProfile profile = getDropProfile(fleet.getFaction().getId());
        if (profile == null) {
            return;
        }

        // 每击毁 100 FP 按下方产出结算；无最低规模保底，也不按敌方幸存规模发奖。
        float scale = defeatedFP / 100f * playerContrib;
        addVorttearProgress(loot, profile.vorttearAmount * scale);
        addRolledCommodity(loot, random, MOURNSCALE, profile.mournscaleAmount * scale);
        addRolledCommodity(loot, random, DRACARYX, profile.dracaryxAmount * scale);
    }

    private DropProfile getDropProfile(String factionId) {
        // 每击毁 100 FP、满贡献时的产出。20 涡珠 / 5 涡鳞珠 / 1 涡龙珠可抽一次。
        // 涡珠稳定累计；中高级珠子保留随机惊喜。海盗约 10 场、左径约 8 场攒够 20 涡珠。
        if (Factions.PIRATES.equals(factionId)) {
            return new DropProfile(2f, 0.10f, 0f);
        }
        if (Factions.LUDDIC_PATH.equals(factionId)) {
            return new DropProfile(2.5f, 0.12f, 0f);
        }
        if (Factions.REMNANTS.equals(factionId)) {
            return new DropProfile(1.5f, 0.25f, 0.025f);
        }
        if (Factions.DERELICT.equals(factionId)) {
            return new DropProfile(1f, 0.15f, 0.01f);
        }
        if (Factions.HEGEMONY.equals(factionId)
                || Factions.PERSEAN.equals(factionId)
                || Factions.INDEPENDENT.equals(factionId)) {
            return new DropProfile(0.75f, 0.05f, 0f);
        }
        if (Factions.TRITACHYON.equals(factionId)) {
            // 与霸主相同的基础产出，另有每 100 FP、满贡献 1% 的涡龙珠掉落。
            return new DropProfile(0.75f, 0.05f, 0.01f);
        }
        return null;
    }

    private void addVorttearProgress(CargoAPI loot, float amount) {
        MemoryAPI memory = Global.getSector().getMemoryWithoutUpdate();
        Object saved = memory.get(VORTTEAR_PROGRESS);
        double progress = saved instanceof Number ? ((Number) saved).doubleValue() : 0d;
        if (!Double.isFinite(progress) || progress < 0d) {
            progress = 0d;
        }
        progress += amount;
        int whole = (int) Math.floor(progress + 0.000001d);
        memory.set(VORTTEAR_PROGRESS, Math.max(0d, progress - whole));
        if (whole > 0) {
            loot.addCommodity(VORTTEAR, whole);
        }
    }

    private void addRolledCommodity(CargoAPI loot, Random random, String commodityId, float amount) {
        int whole = rollAmount(random, amount);
        if (whole > 0) {
            loot.addCommodity(commodityId, whole);
        }
    }

    private int rollAmount(Random random, float amount) {
        int whole = (int) amount;
        float fraction = amount - whole;
        return whole + (fraction > 0f && random.nextFloat() < fraction ? 1 : 0);
    }

    private float getDefeatedFleetPoints(List<FleetMemberAPI> lost) {
        float points = 0f;
        for (FleetMemberAPI member : lost) {
            if (!member.isFighterWing()) {
                points += member.getFleetPointCost();
            }
        }
        return points;
    }

    private float getFleetSize(List<FleetMemberAPI> lost) {
        float fleetSize = 0f;
        for (FleetMemberAPI member : lost) {
            if (!member.isFighterWing()) {
                fleetSize += Misc.getSizeNum(member.getHullSpec().getHullSize());
            }
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
        private final float vorttearAmount;
        private final float mournscaleAmount;
        private final float dracaryxAmount;

        private DropProfile(float vorttearAmount, float mournscaleAmount, float dracaryxAmount) {
            this.vorttearAmount = vorttearAmount;
            this.mournscaleAmount = mournscaleAmount;
            this.dracaryxAmount = dracaryxAmount;
        }
    }
}
