package com.fs.starfarer.api.impl.campaign.rulecmd;

import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.util.Misc;

import data.scripts.impl.Moci_CampaignFeedback;
import data.scripts.impl.Moci_StoryOfficerManager;
import data.scripts.impl.Moci_StoryOfficerManager.StoryOfficer;

/**
 * 剧情军官招募的规则命令。
 *
 * 用法（rules.csv）：
 * Moci_StoryOfficerCommand prepareRecruit okita moci_okita_recruit
 *   —— 招募条件不满足时把该选项置灰，并在提示里写明原因。
 * Moci_StoryOfficerCommand recruit okita
 *   —— 执行招募（故事点由该选项上的 SetStoryOption 扣除）。
 *
 * 人物短名：okita / ruri / teresa。
 */
public class Moci_StoryOfficerCommand extends BaseCommandPlugin {

    @Override
    public boolean execute(String ruleId, InteractionDialogAPI dialog, List<Misc.Token> params,
                           Map<String, MemoryAPI> memoryMap) {
        if (dialog == null || params == null || params.size() < 2) {
            return false;
        }

        String command = params.get(0).getString(memoryMap);
        StoryOfficer who = StoryOfficer.fromKey(params.get(1).getString(memoryMap));
        if (who == null) {
            return false;
        }

        if ("prepareRecruit".equals(command)) {
            if (params.size() < 3 || dialog.getOptionPanel() == null) {
                return false;
            }
            String optionId = params.get(2).getString(memoryMap);
            String reason = Moci_StoryOfficerManager.getRecruitBlockReason(who);
            if (reason != null) {
                dialog.getOptionPanel().setEnabled(optionId, false);
                dialog.getOptionPanel().setTooltip(optionId, reason);
            }
            return true;
        }

        if ("recruit".equals(command)) {
            boolean recruited = Moci_StoryOfficerManager.recruit(who, dialog);
            if (recruited) {
                Moci_CampaignFeedback.playMissionAccepted();
            }
            return recruited;
        }

        return false;
    }
}
