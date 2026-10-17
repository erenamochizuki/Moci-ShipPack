package data.scripts.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.OfficerDataAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.impl.campaign.DevMenuOptions;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Sounds;
import com.fs.starfarer.api.impl.campaign.rulecmd.AddRemoveCommodity;
import com.fs.starfarer.api.impl.campaign.rulecmd.DumpMemory;
import com.fs.starfarer.api.impl.campaign.rulecmd.FireAll;
import com.fs.starfarer.api.util.Misc;
import data.campaign.skills.Moci_AugmentedHumanSkill;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Moci_FlanaganInstituteDialogPlugin implements InteractionDialogPlugin {

    public static final String NEWTYPE_SKILL_ID = "Moci_PlayerSkill1";
    public static final String AUGMENTED_HUMAN_SKILL_ID = Moci_AugmentedHumanSkill.SKILL_ID;
    public static final int ALPHA_CORE_COST = 1;

    private enum OptionId {
        MAIN,
        BACK
    }

    private static class UnitOption {
        final PersonAPI person;
        final boolean player;

        UnitOption(PersonAPI person, boolean player) {
            this.person = person;
            this.player = player;
        }
    }

    private final InteractionDialogPlugin originalPlugin;
    private final Map<String, MemoryAPI> memoryMap;
    private InteractionDialogAPI dialog;
    private TextPanelAPI text;
    private OptionPanelAPI options;

    public Moci_FlanaganInstituteDialogPlugin(InteractionDialogPlugin originalPlugin, Map<String, MemoryAPI> memoryMap) {
        this.originalPlugin = originalPlugin;
        this.memoryMap = memoryMap;
    }

    @Override
    public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        this.text = dialog.getTextPanel();
        this.options = dialog.getOptionPanel();
        showMain(true);
    }

    @Override
    public void optionSelected(String optionText, Object optionData) {
        if (optionText != null) {
            dialog.addOptionSelectedText(optionData);
        }

        if (optionData == DumpMemory.OPTION_ID) {
            new DumpMemory().execute(null, dialog, null, getMemoryMap());
            return;
        }
        if (DevMenuOptions.isDevOption(optionData)) {
            DevMenuOptions.execute(dialog, (String) optionData);
            return;
        }

        if (optionData == OptionId.MAIN) {
            showMain(false);
            return;
        }
        if (optionData == OptionId.BACK) {
            returnToMarket();
            return;
        }
        if (optionData instanceof UnitOption) {
            augment((UnitOption) optionData);
        }
    }

    private void showMain(boolean withIntro) {
        if (withIntro) {
            text.addPara("弗拉纳冈机构的地下区划被层层隔离门封闭。值勤人员确认了你的授权后，将一份适格者名单投到终端上。");
            text.addPara("每次强化人培育需要消耗 %s 个 Alpha Core。培育完成后，目标会获得强化人技能，可无视米诺夫斯基粒子的干扰效果，但不会获得新人类的时流能力。",
                    Misc.getTextColor(), Misc.getHighlightColor(), String.valueOf(ALPHA_CORE_COST), "强化人");
        }

        options.clearOptions();
        List<UnitOption> targets = getTargets();
        if (targets.isEmpty()) {
            text.addPara("当前舰队里没有可进行培育的对象。");
        }

        for (UnitOption target : targets) {
            options.addOption(getOptionText(target), target);
            String unavailable = getUnavailableReason(target.person);
            if (unavailable != null) {
                options.setEnabled(target, false);
                options.setTooltip(target, unavailable);
            }
        }

        if (targets.isEmpty() || !hasAlphaCore()) {
            String optionText = targets.isEmpty() ? "没有可培育对象" : "需要 Alpha Core";
            options.addOption(optionText, OptionId.MAIN);
            options.setEnabled(OptionId.MAIN, false);
            options.setTooltip(OptionId.MAIN, targets.isEmpty()
                    ? "玩家和所有可用军官都已经是新人类、强化人，或不是可培育对象。"
                    : "货舱中需要至少 1 个 Alpha Core。");
        }

        options.addOption("返回市场", OptionId.BACK);
        options.setShortcut(OptionId.BACK, Keyboard.KEY_ESCAPE, false, false, false, true);
        if (Global.getSettings().isDevMode()) {
            DevMenuOptions.addOptions(dialog);
        }
    }

    private void augment(UnitOption target) {
        if (target == null || target.person == null) {
            showMain(false);
            return;
        }

        String unavailable = getUnavailableReason(target.person);
        if (unavailable != null) {
            text.addPara(unavailable, Misc.getNegativeHighlightColor());
            showContinue();
            return;
        }

        CargoAPI cargo = getPlayerCargo();
        if (cargo == null || cargo.getCommodityQuantity(Commodities.ALPHA_CORE) < ALPHA_CORE_COST) {
            text.addPara("货舱中没有可用的 Alpha Core。", Misc.getNegativeHighlightColor());
            showContinue();
            return;
        }

        cargo.removeCommodity(Commodities.ALPHA_CORE, ALPHA_CORE_COST);
        AddRemoveCommodity.addCommodityLossText(Commodities.ALPHA_CORE, ALPHA_CORE_COST, text);
        AddRemoveCommodity.updatePlayerMemoryQuantity(Commodities.ALPHA_CORE);

        target.person.getStats().setSkillLevel(AUGMENTED_HUMAN_SKILL_ID, 1f);
        target.person.getStats().refreshCharacterStatsEffects();
        if (Global.getSector().getPlayerFleet() != null) {
            Global.getSector().getPlayerFleet().getFleetData().setSyncNeeded();
            Global.getSector().getPlayerFleet().getFleetData().syncIfNeeded();
        }

        if (Global.getSoundPlayer() != null) {
            Global.getSoundPlayer().playUISound(Sounds.STORY_POINT_SPEND_TECHNOLOGY, 1f, 1f);
        }

        String name = getPersonDisplayName(target);
        text.addPara("%s 已完成强化人培育，获得技能：%s。",
                Misc.getTextColor(), Misc.getHighlightColor(), name, "强化人");
        showContinue();
    }

    private void showContinue() {
        options.clearOptions();
        options.addOption("继续", OptionId.MAIN);
        options.addOption("返回市场", OptionId.BACK);
        options.setShortcut(OptionId.BACK, Keyboard.KEY_ESCAPE, false, false, false, true);
        if (Global.getSettings().isDevMode()) {
            DevMenuOptions.addOptions(dialog);
        }
    }

    private void returnToMarket() {
        if (originalPlugin == null) {
            dialog.dismiss();
            return;
        }

        dialog.setPlugin(originalPlugin);
        options.clearOptions();
        Map<String, MemoryAPI> previousMemory = originalPlugin.getMemoryMap();
        if (previousMemory != null && FireAll.fire(null, dialog, previousMemory, "PopulateOptions")
                && options.hasOptions()) {
            return;
        }
        originalPlugin.init(dialog);
    }

    private static List<UnitOption> getTargets() {
        LinkedHashMap<String, UnitOption> targets = new LinkedHashMap<>();

        if (Global.getSector() != null && Global.getSector().getPlayerPerson() != null) {
            PersonAPI player = Global.getSector().getPlayerPerson();
            targets.put(getStablePersonKey(player), new UnitOption(player, true));
        }

        CampaignFleetAPI fleet = Global.getSector() == null ? null : Global.getSector().getPlayerFleet();
        if (fleet != null && fleet.getFleetData() != null) {
            for (OfficerDataAPI officer : fleet.getFleetData().getOfficersCopy()) {
                if (officer == null || officer.getPerson() == null) {
                    continue;
                }
                PersonAPI person = officer.getPerson();
                targets.put(getStablePersonKey(person), new UnitOption(person, false));
            }
        }

        return new ArrayList<>(targets.values());
    }

    public static boolean hasEligibleTarget() {
        for (UnitOption target : getTargets()) {
            if (getUnavailableReason(target.person) == null) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasAlphaCore() {
        CargoAPI cargo = getPlayerCargo();
        return cargo != null && cargo.getCommodityQuantity(Commodities.ALPHA_CORE) >= ALPHA_CORE_COST;
    }

    private static String getUnavailableReason(PersonAPI person) {
        if (person == null || person.isDefault()) {
            return "该对象没有可修改的军官数据。";
        }
        if (person.isAICore()) {
            return "AI 核心无法进行强化人培育。";
        }
        if (hasSkill(person, NEWTYPE_SKILL_ID)) {
            return "该对象已经是新人类，不需要强化人培育。";
        }
        if (hasSkill(person, AUGMENTED_HUMAN_SKILL_ID)) {
            return "该对象已经是强化人。";
        }
        if (!hasAlphaCore()) {
            return "需要 1 个 Alpha Core。";
        }
        return null;
    }

    private static boolean hasSkill(PersonAPI person, String skillId) {
        return person != null
                && person.getStats() != null
                && person.getStats().getSkillLevel(skillId) > 0f;
    }

    private static CargoAPI getPlayerCargo() {
        CampaignFleetAPI fleet = Global.getSector() == null ? null : Global.getSector().getPlayerFleet();
        return fleet == null ? null : fleet.getCargo();
    }

    private static String getStablePersonKey(PersonAPI person) {
        if (person == null) {
            return "null";
        }
        String id = person.getId();
        if (id != null && !id.isEmpty()) {
            return id;
        }
        return person.getNameString();
    }

    private String getOptionText(UnitOption target) {
        return "培育：" + getPersonDisplayName(target);
    }

    private static String getPersonDisplayName(UnitOption target) {
        if (target == null || target.person == null) {
            return "未知对象";
        }
        if (target.player) {
            return "你自己（" + target.person.getNameString() + "）";
        }
        String rank = target.person.getRank();
        if (rank != null && !rank.isEmpty()) {
            return Misc.ucFirst(rank) + " " + target.person.getNameString();
        }
        return target.person.getNameString();
    }

    @Override
    public void optionMousedOver(String optionText, Object optionData) {
    }

    @Override
    public void advance(float amount) {
    }

    @Override
    public void backFromEngagement(EngagementResultAPI battleResult) {
    }

    @Override
    public Object getContext() {
        return null;
    }

    @Override
    public Map<String, MemoryAPI> getMemoryMap() {
        return memoryMap;
    }
}
