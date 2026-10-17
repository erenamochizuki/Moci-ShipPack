package data.scripts.campaign.bar;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BaseBarEvent;
import com.fs.starfarer.api.util.Misc;
import data.scripts.campaign.missionplugin.Moci_YurikaMissionPlugin;
import data.scripts.impl.Moci_NadesicoStoryRouteManager;
import data.scripts.impl.Moci_NergalHeavyIndustriesColonyManager;
import data.scripts.impl.Moci_YurikaStoryManager;
import data.scripts.util.Moci_TextLoader;

import java.util.Map;

public class Moci_YurikaBarEvent extends BaseBarEvent {
    private static final String LOTUS_MARKET_ID = "market_moci_nergal_lotus";
    private static final boolean DEBUG_SHOW_ROUTE_STATS = false;

    private enum OptionId {
        MAIN,
        ACCEPT_REMNANT,
        ACCEPT_STORY,
        SHOW_REPORT,
        LEAVE
    }

    private MarketAPI market;
    private PersonAPI contact;

    @Override
    public boolean shouldShowAtMarket(MarketAPI market) {
        return market != null
                && LOTUS_MARKET_ID.equals(market.getId())
                && getPrimaryContact(market) != null;
    }

    @Override
    public void addPromptAndOption(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap) {
        PersonAPI promptContact = getPrimaryContact(dialog.getInteractionTarget().getMarket());
        if (promptContact == null) {
            return;
        }
        String contactName = promptContact.getNameString();
        dialog.getTextPanel().addPara(formatText("bar.prompt",
                "Lotus 酒吧的安静包厢里，{contact} 似乎正等着和你谈一笔只属于尼尔加内部渠道的委托。",
                "contact", contactName), Misc.getTextColor(), Misc.getHighlightColor(), contactName);
        dialog.getOptionPanel().addOption(formatText("bar.option_talk", "与{contact}交谈", "contact", contactName), this);
    }

    @Override
    public void init(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap) {
        super.init(dialog, memoryMap);
        done = false;
        noContinue = false;
        Moci_YurikaStoryManager.ensureLoaded();
        Moci_NadesicoStoryRouteManager.ensureRouteState();

        market = dialog.getInteractionTarget().getMarket();
        contact = getPrimaryContact(market);
        if (contact != null) {
            dialog.getInteractionTarget().setActivePerson(contact);
            dialog.getVisualPanel().saveCurrentVisual();
            dialog.getVisualPanel().showPersonInfo(contact, false, true);
        }
        showMainScreen();
    }

    @Override
    public void optionSelected(String optionText, Object optionData) {
        super.optionSelected(optionText, optionData);
        if (!(optionData instanceof OptionId)) {
            return;
        }

        OptionId option = (OptionId) optionData;
        if (option == OptionId.MAIN) {
            showMainScreen();
            return;
        }
        if (option == OptionId.ACCEPT_REMNANT) {
            boolean started = Moci_YurikaMissionPlugin.startMission(dialog, Moci_YurikaMissionPlugin.MissionType.REMNANT);
            if (started) {
                playMissionAcceptedSound();
            }
            String contactName = getContactName();
            text.addPara(started
                    ? formatText("bar.accept_remnant_success",
                            "{contact} 迅速把一段新的余辉活动轨迹发到了你的终端。目标已经被标记，随时可以出发。",
                            "contact", contactName)
                    : text("bar.accept_remnant_failed", "当前无法建立新的追踪窗口，这份委托稍后再来拿。"),
                    Misc.getTextColor(), Misc.getHighlightColor(), contactName);
            showReturnOnly();
            return;
        }
        if (option == OptionId.ACCEPT_STORY) {
            Moci_YurikaStoryManager.StoryMissionType storyType = Moci_YurikaStoryManager.getNextAvailableStoryMissionType();
            Moci_YurikaMissionPlugin.MissionType missionType = mapStoryMission(storyType);
            boolean started = missionType != null && Moci_YurikaMissionPlugin.startMission(dialog, missionType);
            if (started) {
                playMissionAcceptedSound();
                String contactName = getContactName();
                text.addPara(getStoryAcceptText(storyType), Misc.getTextColor(), Misc.getHighlightColor(), contactName);
            } else {
                text.addPara(text("bar.accept_story_failed", "这条行动线暂时无法启动，可能已经有同阶段任务正在执行。"),
                        Misc.getNegativeHighlightColor());
            }
            showReturnOnly();
            return;
        }
        if (option == OptionId.SHOW_REPORT) {
            String report = Moci_YurikaStoryManager.getPendingReportText();
            if (report != null && !report.isEmpty()) {
                text.addPara(report);
            } else {
                text.addPara(text("bar.no_report", "目前没有新的行动总结。"));
            }
            Moci_YurikaStoryManager.clearPendingReport();
            showReturnOnly();
            return;
        }
        if (option == OptionId.LEAVE) {
            if (dialog != null && dialog.getInteractionTarget() != null) {
                dialog.getInteractionTarget().setActivePerson(null);
            }
            done = true;
            noContinue = true;
        }
    }

    private void showMainScreen() {
        contact = getPrimaryContact(market);
        if (contact != null) {
            dialog.getInteractionTarget().setActivePerson(contact);
            dialog.getVisualPanel().showPersonInfo(contact, false, true);
        }

        text.addPara(getIntroText(), Misc.getTextColor(), Misc.getHighlightColor(), getContactName());
        if (DEBUG_SHOW_ROUTE_STATS) {
            text.addPara(formatText("bar.debug_route", "当前路线：{route}", "route", getRouteText()),
                    Misc.getTextColor(), Misc.getHighlightColor(), getRouteText());
            String affinity = String.valueOf((int) Moci_YurikaStoryManager.getAffinity());
            text.addPara(formatText("bar.debug_affinity", "当前好感：{affinity}", "affinity", affinity),
                    Misc.getTextColor(), Misc.getHighlightColor(), affinity);
            String jobs = String.valueOf(Moci_YurikaStoryManager.getRemnantJobCount());
            text.addPara(formatText("bar.debug_remnant_jobs", "已完成余辉委托：{count}", "count", jobs),
                    Misc.getTextColor(), Misc.getHighlightColor(), jobs);
        }

        options.clearOptions();
        if (hasPendingReport()) {
            options.addOption(text("bar.option_show_report", "听取最近一次行动汇报"), OptionId.SHOW_REPORT);
            dialog.setOptionColor(OptionId.SHOW_REPORT, Misc.getHighlightColor());
        }
        if (!Moci_YurikaMissionPlugin.hasActiveMissionForType(Moci_YurikaMissionPlugin.MissionType.REMNANT)) {
            options.addOption(text("bar.option_accept_remnant", "接取余辉清剿委托"), OptionId.ACCEPT_REMNANT);
        }

        Moci_YurikaStoryManager.StoryMissionType nextStory = Moci_YurikaStoryManager.getNextAvailableStoryMissionType();
        Moci_YurikaMissionPlugin.MissionType missionType = mapStoryMission(nextStory);
        if (missionType != null && !Moci_YurikaMissionPlugin.hasActiveMissionForType(missionType)) {
            options.addOption(getStoryOptionText(nextStory), OptionId.ACCEPT_STORY);
            dialog.setOptionColor(OptionId.ACCEPT_STORY, Misc.getHighlightColor());
        }

        options.addOption(text("bar.option_leave", "返回酒吧"), OptionId.LEAVE);
    }

    private void showReturnOnly() {
        options.clearOptions();
        options.addOption(text("bar.option_continue", "继续"), OptionId.MAIN);
        options.addOption(text("bar.option_leave", "返回酒吧"), OptionId.LEAVE);
    }

    private boolean hasPendingReport() {
        String report = Moci_YurikaStoryManager.getPendingReportText();
        return report != null && !report.isEmpty();
    }

    private void playMissionAcceptedSound() {
        if (Global.getSoundPlayer() == null) {
            return;
        }
        Global.getSoundPlayer().playUISound("ui_contact_developed", 1f, 1f);
    }

    private String getIntroText() {
        if (Moci_YurikaStoryManager.shouldUseProspectorAsPrimaryContact()) {
            return formatText("bar.intro_prospector",
                    "{contact} 已经把财务报表、停靠许可和截获的航线碎片并排投在桌面上。对尼尔加来说，每一次外勤都既是风险控制，也是预算问题。",
                    "contact", getContactName());
        }
        return formatText("bar.intro_yurika",
                "{contact} 靠在窗边，面前展开的是整套战术投影和传感器回放。她说话依旧温和，但明显已经把你当成了尼尔加这条线里最可靠的外部执行者。",
                "contact", getContactName());
    }

    private String getRouteText() {
        String route = Moci_NadesicoStoryRouteManager.getRoute();
        if ("moci_nadesico_route_a_to_b_to_tdd".equals(route)) {
            return "抚子A -> 抚子B -> 丹努之子";
        }
        if ("moci_nadesico_route_b_to_tdd".equals(route)) {
            return "Prospector -> 抚子A -> Yurika -> 抚子B -> 丹努之子";
        }
        return text("bar.route_uninitialized", "未初始化");
    }

    private String getStoryOptionText(Moci_YurikaStoryManager.StoryMissionType storyType) {
        switch (storyType) {
            case NADE_A:
                return text("bar.option_story_a", "接取主线：抚子A");
            case NADE_B:
                return text("bar.option_story_b", "接取主线：抚子B");
            case TDD:
                return text("bar.option_story_tdd", "接取主线：丹努之子");
            default:
                return text("bar.option_story_default", "接取主线");
        }
    }

    private String getStoryAcceptText(Moci_YurikaStoryManager.StoryMissionType storyType) {
        switch (storyType) {
            case NADE_A:
                return formatText("bar.accept_story_a",
                        "{contact} 把抚子A的识别档案推到了你面前。对方提醒你，这一步会决定后续整条试验线由谁来主导。",
                        "contact", getContactName());
            case NADE_B:
                return formatText("bar.accept_story_b",
                        "{contact} 将最新整理出的玻色子尾迹和追踪窗口同步给你。下一步行动已经没有再拖延的余地。",
                        "contact", getContactName());
            case TDD:
                return formatText("bar.accept_story_tdd",
                        "{contact} 低声说明了秘银那边刚送来的协作窗口。只要你按时抵达，最后一段行动就能展开。",
                        "contact", getContactName());
            default:
                return formatText("bar.accept_story_default",
                        "{contact} 把新的任务资料发到了你的终端。",
                        "contact", getContactName());
        }
    }

    private String getContactName() {
        return contact != null ? contact.getNameString() : text("bar.fallback_contact", "尼尔加联络员");
    }

    private static String text(String key, String fallback) {
        return Moci_TextLoader.getText("Moci_CampaignText", key, fallback);
    }

    private static String formatText(String key, String fallback, String replaceKey, String replaceValue) {
        return text(key, fallback).replace("{" + replaceKey + "}", replaceValue);
    }

    private PersonAPI getPrimaryContact(MarketAPI market) {
        if (market == null || !LOTUS_MARKET_ID.equals(market.getId())) {
            return null;
        }

        Moci_NergalHeavyIndustriesColonyManager.syncLotusStoryContacts();

        PersonAPI contact;
        if (Moci_YurikaStoryManager.shouldUseProspectorAsPrimaryContact()) {
            contact = Moci_NergalHeavyIndustriesColonyManager.getLotusAdmin();
        } else {
            contact = Moci_NergalHeavyIndustriesColonyManager.getLotusStationCommander();
        }

        if (contact == null) {
            Moci_NergalHeavyIndustriesColonyManager.initializeNergalColony();
            if (Moci_YurikaStoryManager.shouldUseProspectorAsPrimaryContact()) {
                contact = Moci_NergalHeavyIndustriesColonyManager.getLotusAdmin();
            } else {
                contact = Moci_NergalHeavyIndustriesColonyManager.getLotusStationCommander();
            }
        }
        return contact;
    }

    private Moci_YurikaMissionPlugin.MissionType mapStoryMission(Moci_YurikaStoryManager.StoryMissionType storyType) {
        if (storyType == null) {
            return null;
        }
        switch (storyType) {
            case NADE_A:
                return Moci_YurikaMissionPlugin.MissionType.STORY_A;
            case NADE_B:
                return Moci_YurikaMissionPlugin.MissionType.STORY_B;
            case TDD:
                return Moci_YurikaMissionPlugin.MissionType.STORY_TDD;
            default:
                return null;
        }
    }
}
