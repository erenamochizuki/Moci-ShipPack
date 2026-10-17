package data.campaign.customstart;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.Script;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.rules.MemKeys;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.CharacterCreationData;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.rulecmd.AddRemoveCommodity;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import exerelin.campaign.ExerelinSetupData;
import exerelin.campaign.PlayerFactionStore;
import exerelin.campaign.customstart.CustomStart;
import exerelin.utilities.StringHelper;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class Moci_CustomStart extends CustomStart {
    // 主力机体列表 - 高达系列
    private static final List<String> MAIN_SUITS = new ArrayList<String>(Arrays.asList(
            "Moci_RX_78_1_Standard",
            "Moci_RX_78_2_Standard",
            "Moci_RX_78_3_Standard"));

    // 量产机列表 - 吉姆系列
    private static final List<String> MASS_PRODUCTION_SUITS = new ArrayList<String>(Arrays.asList(
            "Moci_RGM_79_Standard",
            "Moci_RGM_79_Cannon",
            "Moci_RMS_179_Beam",
            "Moci_RMS_179_Standard"));

    // 燃料补给舰
    private static final List<String> SUPPORT_UNITS = new ArrayList<String>(Arrays.asList(
            "phaeton_Standard"));

    // 货船
    private static final List<String> LOGISTICS_UNITS = new ArrayList<String>(Arrays.asList(
            "buffalo_Standard",
            "buffalo_tritachyon_Standard",
            "buffalo_hegemony_Standard"));

    // 舰船类型配置 - 每种类型选择一艘
    private static final List<List<String>> SHIP_CATEGORIES = new ArrayList<List<String>>(Arrays.asList(
            MAIN_SUITS, // 第1艘：主力机体
            MASS_PRODUCTION_SUITS, // 第2艘：量产机
            SUPPORT_UNITS, // 第3艘：支援机体
            LOGISTICS_UNITS // 第4艘：后勤单位
    ));

    public static final String memKey = "$moci_customStart";
    private static final int STARTING_CREDITS = 150000;
    
    // 主题色
    private static final Color MOCI_COLOR = new Color(100, 149, 237); // 蓝色主题

    @Override
    public void execute(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap) {
        CharacterCreationData data = (CharacterCreationData) memoryMap.get(MemKeys.LOCAL).get("$characterData");

        // 清理之前添加的舰船（防止重复选择时叠加）
        data.clearAdditionalShips();

        // 设置Exerelin相关配置
        PlayerFactionStore.setPlayerFactionIdNGC(Factions.PLAYER);
        ExerelinSetupData.getInstance().freeStart = true;

        // 添加故事文本
        dialog.getTextPanel().addPara("你带领着一支小型舰队，装备着来自旧时代的机动战士技术。");
        dialog.getTextPanel().addPara("虽然舰队规模不大，但这些机体蕴含着强大的战斗潜力。是时候在星海中开创属于你的传奇了。");

        // 创建特性展示tooltip
        TooltipMakerAPI tooltip = dialog.getTextPanel().beginTooltip();
        tooltip.addSpacer(5f);

        // 资源展示
        TooltipMakerAPI resourceTooltip = tooltip.beginImageWithText(
                "graphics/icons/cargo/supplies.png", 48f);
        resourceTooltip.addPara("获得充足的初始资源和启动资金。", 0f,
                Misc.getTextColor(), Misc.getHighlightColor(), "初始资源");
        tooltip.addImageWithText(0f);

        tooltip.addSpacer(5f);
        dialog.getTextPanel().addTooltip();

        // 从每个类别中随机选择一艘舰船
        List<String> selectedShips = new ArrayList<String>();
        for (int categoryIndex = 0; categoryIndex < SHIP_CATEGORIES.size(); categoryIndex++) {
            List<String> category = SHIP_CATEGORIES.get(categoryIndex);
            if (category.isEmpty()) {
                continue;
            }

            // 从当前类别中随机选择一艘舰船
            int randomIndex = (int) (Math.random() * category.size());
            String variantId = category.get(randomIndex);
            selectedShips.add(variantId);
        }

        // 自定义舰队生成（不使用NGCAddStartingShipsByFleetType以避免资源重复）
        dialog.getTextPanel().addPara("获得起始舰队:");
        CampaignFleetAPI tempFleet = generateCustomFleet(dialog, data, selectedShips);

        // 显示技能获得信息
        dialog.getTextPanel().addPara("获得额外技能:");
        PersonAPI person = Global.getFactory().createPerson();
        person.setName(new FullName("", "", FullName.Gender.ANY));
        person.getStats().setSkillLevel("Moci_PlayerSkill1", 2);
        dialog.getTextPanel().addSkillPanel(person, false);

        // 添加脚本在游戏开始后执行
        data.addScript(new Script() {
            public void run() {
                // 设置内存标记
                Global.getSector().getMemoryWithoutUpdate().set(memKey, true);
                
                // 给予玩家技能
                Global.getSector().getPlayerPerson().getStats().setSkillLevel("Moci_PlayerSkill1", 2);

                CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();

                // 重新指定旗舰为主力机体（如果有的话）
                for (FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
                    if (member.getVariant().getHullVariantId().contains("RX_78")) {
                        member.setFlagship(true);
                        fleet.getFleetData().setFlagship(member);
                        break; // 找到第一个就退出
                    }
                }

                // 确保有旗舰（如果没有RX-78，使用默认旗舰）
                fleet.getFleetData().ensureHasFlagship();

                // 同步舰队数据
                fleet.getFleetData().setSyncNeeded();
                fleet.getFleetData().syncIfNeeded();
            }
        });

        data.getStartingCargo().getCredits().add(STARTING_CREDITS);
        AddRemoveCommodity.addCreditsGainText(STARTING_CREDITS, dialog.getTextPanel());

        dialog.getVisualPanel().showFleetInfo(StringHelper.getString("exerelin_ngc", "playerFleet", true),
                tempFleet, null, null);
        dialog.getOptionPanel().addOption(StringHelper.getString("done", true), "nex_NGCDone");
        dialog.getOptionPanel().addOption(StringHelper.getString("back", true), "nex_NGCStartBack");
    }

    /**
     * 自定义舰队生成方法，精确控制资源量
     */
    private CampaignFleetAPI generateCustomFleet(InteractionDialogAPI dialog, CharacterCreationData data, List<String> variantIds) {
        int crew = 0;
        int supplies = 0;
        int machinery = 0;
        int fuel = 0;
        CampaignFleetAPI tempFleet = FleetFactoryV3.createEmptyFleet(
                PlayerFactionStore.getPlayerFactionIdNGC(), FleetTypes.PATROL_SMALL, null);
        FleetMemberAPI flagship = null;

        // 添加舰船并计算资源
        for (String variantId : variantIds) {
            // 添加到起始舰队
            data.addStartingFleetMember(variantId, FleetMemberType.SHIP);

            // 创建临时成员用于计算
            FleetMemberAPI temp = Global.getFactory().createFleetMember(FleetMemberType.SHIP, variantId);
            temp.getRepairTracker().setCR(0.7f);
            tempFleet.getFleetData().addFleetMember(temp);
            if (flagship == null || variantId.contains("RX_78")) {
                flagship = temp;
            }

            // 计算资源（减半以避免超出货舱）
            crew += (int) (temp.getMinCrew() * 0.8f); // 100%最小船员
            supplies += (int) (temp.getCargoCapacity() * 0.4f); // 25%货舱容量作为补给
            machinery += (int) (temp.getCargoCapacity() * 0.06f); // 6%货舱容量作为机械
            fuel += (int) Math.min(temp.getFuelUse() * 30, temp.getFuelCapacity()); // 燃料保持不变

            // 显示获得的舰船
            AddRemoveCommodity.addFleetMemberGainText(temp.getVariant(), dialog.getTextPanel());
        }

        if (flagship != null) {
            tempFleet.getFleetData().setFlagship(flagship);
            flagship.setCaptain(data.getPerson());
        }
        tempFleet.getFleetData().setSyncNeeded();
        tempFleet.getFleetData().syncIfNeeded();
        tempFleet.forceSync();

        // 添加资源到起始货物
        if (crew > 0) {
            data.getStartingCargo().addCommodity(Commodities.CREW, crew);
            AddRemoveCommodity.addCommodityGainText(Commodities.CREW, crew, dialog.getTextPanel());
        }
        if (supplies > 0) {
            data.getStartingCargo().addCommodity(Commodities.SUPPLIES, supplies);
            AddRemoveCommodity.addCommodityGainText(Commodities.SUPPLIES, supplies, dialog.getTextPanel());
        }
        if (machinery > 0) {
            data.getStartingCargo().addCommodity(Commodities.HEAVY_MACHINERY, machinery);
            AddRemoveCommodity.addCommodityGainText(Commodities.HEAVY_MACHINERY, machinery, dialog.getTextPanel());
        }
        if (fuel > 0) {
            data.getStartingCargo().addCommodity(Commodities.FUEL, fuel);
            AddRemoveCommodity.addCommodityGainText(Commodities.FUEL, fuel, dialog.getTextPanel());
        }
        return tempFleet;
    }
}
