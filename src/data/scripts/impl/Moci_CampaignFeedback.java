package data.scripts.impl;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.util.Misc;

public final class Moci_CampaignFeedback {
    private static final String SOUND_MISSION_ACCEPTED = "ui_contact_developed";
    private static final String SOUND_MISSION_COMPLETED = "ui_rep_raise";
    private static final String SOUND_MISSION_FAILED = "ui_rep_drop";
    private static final String SOUND_AFFINITY_GAINED = "ui_rep_raise";
    private static final String SOUND_AFFINITY_LOST = "ui_rep_drop";
    private static final String SOUND_REWARD_RECEIVED = "ui_rep_raise";
    private static final String SOUND_BLUEPRINT_RECEIVED = "ui_acquired_blueprint";
    private static final String SOUND_CONTACT_RECEIVED = "ui_contact_developed";

    private Moci_CampaignFeedback() {
    }

    public static void playMissionAccepted() {
        playUISound(SOUND_MISSION_ACCEPTED);
    }

    public static void playMissionCompleted() {
        playUISound(SOUND_MISSION_COMPLETED);
    }

    public static void playMissionFailed() {
        playUISound(SOUND_MISSION_FAILED);
    }

    public static void playRewardReceived() {
        playUISound(SOUND_REWARD_RECEIVED);
    }

    public static void playBlueprintReceived() {
        playUISound(SOUND_BLUEPRINT_RECEIVED);
    }

    public static void playContactReceived() {
        playUISound(SOUND_CONTACT_RECEIVED);
    }

    public static void playAffinityChanged(float amount) {
        if (amount > 0f) {
            playUISound(SOUND_AFFINITY_GAINED);
        } else if (amount < 0f) {
            playUISound(SOUND_AFFINITY_LOST);
        }
    }

    public static void addMissionCompletedMessage(String missionName) {
        addMissionCompletedMessage(missionName, null);
    }

    public static void addMissionCompletedMessage(String missionName, String rewardSummary) {
        CampaignUIAPI ui = Global.getSector() == null ? null : Global.getSector().getCampaignUI();
        if (ui != null) {
            String message = "任务完成: " + missionName;
            if (rewardSummary != null && !rewardSummary.trim().isEmpty()) {
                message += "。" + rewardSummary;
            }
            ui.addMessage(message, Misc.getHighlightColor());
        }
        playMissionCompleted();
    }

    public static void addMissionFailedMessage(String missionName) {
        CampaignUIAPI ui = Global.getSector() == null ? null : Global.getSector().getCampaignUI();
        if (ui != null) {
            ui.addMessage("任务失败: " + missionName, Misc.getNegativeHighlightColor());
        }
        playMissionFailed();
    }

    private static void playUISound(String soundId) {
        if (Global.getSoundPlayer() == null) {
            return;
        }
        Global.getSoundPlayer().playUISound(soundId, 1f, 1f);
    }
}
