package data.console.commands;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import data.campaign.intel.Moci_CombatSquadManagerIntel;
import data.hullmods.combatsquad.Moci_PilotManager;
import java.util.Collections;
import java.util.List;
import org.lazywizard.console.BaseCommand;
import org.lazywizard.console.Console;

/** Optional ConsoleCommands entry point; applies only to the flagship commander's existing squad. */
public class MociSquad implements BaseCommand {
    private static final int MAX_LEVEL = 7;

    @Override
    public CommandResult runCommand(String args, CommandContext context) {
        if (context == null || !context.isInCampaign() || context.isInCombat()
                || Global.getSector() == null) {
            Console.showMessage("请在战役地图使用 MociSquad。");
            return CommandResult.WRONG_CONTEXT;
        }
        String input = args == null ? "" : args.trim();
        if (input.length() == 0 || "help".equalsIgnoreCase(input)) {
            Console.showMessage("MociSquad levelup [数量]：旗舰小队的机师立即升级，省略数量默认一级。\n"
                    + "最高 7 级，随机补齐普通技能；保留已有技能和精英状态，不升级队长。\n"
                    + "MociSquad exp：补齐旗舰小队机师升下一级所需的经验，在界面中手动升级和选择技能。");
            return CommandResult.SUCCESS;
        }
        String[] tokens = input.split("\\s+");
        boolean expOnly = "exp".equalsIgnoreCase(tokens[0]);
        if (expOnly ? tokens.length != 1 : !"levelup".equalsIgnoreCase(tokens[0]) || tokens.length > 2) return badSyntax();
        int count = 1;
        if (tokens.length == 2) {
            try {
                count = Integer.parseInt(tokens[1]);
            } catch (NumberFormatException ex) {
                return badSyntax();
            }
            if (count <= 0) return badSyntax();
        }
        FleetMemberAPI flagship = Global.getSector().getPlayerFleet() == null ? null
                : Global.getSector().getPlayerFleet().getFlagship();
        if (flagship == null) {
            Console.showMessage("当前没有旗舰。");
            return CommandResult.ERROR;
        }
        if (flagship.getVariant() == null || !flagship.getVariant().getHullMods().contains("Moci_CombatSquad")) {
            Console.showMessage("旗舰「" + flagship.getShipName() + "」没有作战小队系统。");
            return CommandResult.ERROR;
        }
        PersonAPI commander = flagship.getCaptain();
        if (commander == null || commander.isDefault() || commander.getId() == null) {
            Console.showMessage("旗舰尚未分配小队指挥官。");
            return CommandResult.ERROR;
        }
        String commanderId = commander.getId();
        int size = Moci_PilotManager.getCurrentSquadSize(commanderId, true);
        if (size <= 0) {
            Console.showMessage("旗舰指挥官尚未建立机师小队，请先让旗舰参与一次战斗。");
            return CommandResult.ERROR;
        }
        int upgraded = 0;
        int capped = 0;
        int missing = 0;
        for (int i = 0; i < size; i++) {
            PersonAPI pilot = Moci_PilotManager.getPilot(commanderId, i, true);
            if (pilot == null) {
                missing++;
                continue;
            }
            MutableCharacterStatsAPI stats = pilot.getStats();
            int before = stats.getLevel();
            if (before >= MAX_LEVEL) {
                capped++;
                continue;
            }
            if (expOnly) {
                float experience = Math.max(Moci_PilotManager.getExperience(commanderId, i, true),
                        Moci_CombatSquadManagerIntel.getExperienceForLevel(before + 1));
                Moci_PilotManager.savePilotData(commanderId, i, pilot,
                        Moci_PilotManager.getCallsign(commanderId, i, true), experience, true);
                Console.showMessage(pilot.getNameString() + "：升至 " + (before + 1)
                        + " 级的经验已就绪，请在小队界面手动升级并选择技能。");
                upgraded++;
                continue;
            }
            int target = before + Math.min(count, MAX_LEVEL - before);
            List<String> options = Moci_CombatSquadManagerIntel.getAvailableSkills(pilot);
            Collections.shuffle(options);
            int learned = 0;
            for (MutableCharacterStatsAPI.SkillLevelAPI skill : stats.getSkillsCopy()) {
                if (skill.getSkill() != null && skill.getLevel() > 0f
                        && skill.getSkill().isCombatOfficerSkill() && !skill.getSkill().isAptitudeEffect()) learned++;
            }
            int added = 0;
            for (String skillId : options) {
                if (learned >= target) break;
                stats.setSkillLevel(skillId, 1f);
                learned++;
                added++;
            }
            stats.setLevel(target);
            float experience = Math.max(Moci_PilotManager.getExperience(commanderId, i, true),
                    Moci_CombatSquadManagerIntel.getExperienceForLevel(target));
            Moci_PilotManager.savePilotData(commanderId, i, pilot,
                    Moci_PilotManager.getCallsign(commanderId, i, true), experience, true);
            Console.showMessage(pilot.getNameString() + "：" + before + " -> " + target + " 级，新增 " + added + " 项普通技能。");
            upgraded++;
        }
        Console.showMessage("旗舰小队：" + (expOnly ? "经验已补齐 " : "已升级 ")
                + upgraded + " 人，已满级 " + capped + " 人，缺失机师 " + missing + " 人。");
        return upgraded + capped > 0 ? CommandResult.SUCCESS : CommandResult.ERROR;
    }

    private CommandResult badSyntax() {
        Console.showMessage("用法：MociSquad levelup [正整数] 或 MociSquad exp。");
        return CommandResult.BAD_SYNTAX;
    }
}
