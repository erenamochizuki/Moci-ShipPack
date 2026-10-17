package com.fs.starfarer.api.impl.campaign.rulecmd;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.campaign.missionplugin.Moci_OkitaMissionPlugin;
import data.scripts.impl.Moci_CampaignFeedback;

import java.util.List;
import java.util.Map;

/**
 * 规则系统到任务插件的桥接命令。
 *
 * 用法（rules.csv）：
 * Moci_OkitaMissionCommand start 1
 * Moci_OkitaMissionCommand start 2
 * Moci_OkitaMissionCommand start 3
 *
 * 这里不处理具体任务逻辑，只负责：
 * 1. 参数校验；
 * 2. 防重复启动；
 * 3. 构造对应阶段的任务插件。
 */
public class Moci_OkitaMissionCommand extends BaseCommandPlugin {
    /** 阶段运行锁：$moci_okita_mission_running_1/2/3。 */
    private static final String RUNNING_PREFIX = "$moci_okita_mission_running_";

    @Override
    public boolean execute(String ruleId, InteractionDialogAPI dialog, List<Misc.Token> params,
                           Map<String, MemoryAPI> memoryMap) {
        // 参数至少应包含主命令（如 start）
        if (params == null || params.isEmpty()) {
            return false;
        }

        String cmd = params.get(0).getString(memoryMap);
        // 目前仅支持 start 子命令
        if (!"start".equals(cmd)) {
            return false;
        }
        // 至少要带阶段号
        if (params.size() < 2) {
            return false;
        }

        int stage = params.get(1).getInt(memoryMap);
        // 仅允许 1~3 阶段
        if (stage < 1 || stage > 3) {
            return false;
        }

        String runningKey = RUNNING_PREFIX + stage;
        // 如果该阶段已在运行，先检查是否真有活动任务；若只是残留锁则自动清理。
        if (Global.getSector().getMemoryWithoutUpdate().getBoolean(runningKey)) {
            if (Moci_OkitaMissionPlugin.hasActiveMissionForStage(stage)) {
                return true;
            }
            Global.getSector().getMemoryWithoutUpdate().set(runningKey, false);
        }

        // 上锁并启动阶段任务插件
        Global.getSector().getMemoryWithoutUpdate().set(runningKey, true);
        Moci_OkitaMissionPlugin mission = new Moci_OkitaMissionPlugin(dialog, stage);
        if (!mission.hasSpawnedMissionTarget()) {
            Global.getSector().getMemoryWithoutUpdate().set(runningKey, false);
            return false;
        }
        Moci_CampaignFeedback.playMissionAccepted();
        return true;
    }
}
