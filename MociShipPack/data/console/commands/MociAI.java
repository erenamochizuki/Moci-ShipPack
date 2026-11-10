package data.console.commands;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import data.hullmods.Moci_ShipboardAIManager;
import data.hullmods.Moci_ShipboardAIManager.ShipboardAIData;
import org.lazywizard.console.BaseCommand;
import org.lazywizard.console.Console;

/** Optional ConsoleCommands entry point; loaded from commands.csv only by that mod. */
public class MociAI implements BaseCommand {
    @Override
    public CommandResult runCommand(String args, CommandContext context) {
        if (context == null || !context.isInCampaign() || context.isInCombat()
                || Global.getSector() == null) {
            Console.showMessage("请在战役地图使用 MociAI。");
            return CommandResult.WRONG_CONTEXT;
        }

        String input = args == null ? "" : args.trim();
        if (input.length() == 0 || "help".equalsIgnoreCase(input)) {
            Console.showMessage("MociAI levelup 1：旗舰舰载 AI 升一级（数量可省略或改为其他正整数）。\n"
                    + "MociAI evolve：让旗舰舰载 AI 进化。升级遵守当前形态上限，不会自动进化。");
            return CommandResult.SUCCESS;
        }
        String[] tokens = input.split("\\s+");
        boolean levelUp = "levelup".equalsIgnoreCase(tokens[0]);
        boolean evolve = "evolve".equalsIgnoreCase(tokens[0]);
        int count = 1;
        if ((!levelUp && !evolve) || (levelUp && tokens.length > 2)
                || (evolve && tokens.length != 1)) {
            return badSyntax();
        }
        if (levelUp && tokens.length == 2) {
            try {
                count = Integer.parseInt(tokens[1]);
            } catch (NumberFormatException ex) {
                return badSyntax();
            }
            if (count <= 0) return badSyntax();
        }

        FleetMemberAPI member = Global.getSector().getPlayerFleet() == null ? null
                : Global.getSector().getPlayerFleet().getFlagship();
        if (member == null) {
            Console.showMessage("当前没有旗舰。");
            return CommandResult.ERROR;
        }
        ShipboardAIData data = Moci_ShipboardAIManager.getOrCreateData(member);
        if (data == null) {
            Console.showMessage("旗舰「" + member.getShipName() + "」没有舰载 AI。");
            return CommandResult.ERROR;
        }

        if (evolve) {
            if (!Moci_ShipboardAIManager.evolveAI(member)) {
                Console.showMessage("旗舰舰载 AI 当前无法进化：等级未满足要求，或没有可用进化路线。");
                return CommandResult.ERROR;
            }
            Console.showMessage("旗舰「" + member.getShipName() + "」的舰载 AI 已进化为「"
                    + data.aiPerson.getNameString() + "」，等级 "
                    + Moci_ShipboardAIManager.getCurrentLevel(data) + "/"
                    + Moci_ShipboardAIManager.getLevelCap(data) + "。");
            return CommandResult.SUCCESS;
        }

        int before = Moci_ShipboardAIManager.getCurrentLevel(data);
        int cap = Moci_ShipboardAIManager.getLevelCap(data);
        if (before >= cap) {
            Console.showMessage("旗舰舰载 AI 已达到当前形态的等级上限：" + before + "/" + cap + "。");
            return CommandResult.SUCCESS;
        }
        int target = before + Math.min(count, cap - before);
        // Console leveling sets the requested level exactly, even when evolution retained excess XP.
        data.experience = Math.max(data.experience, Moci_ShipboardAIManager.getExperienceForLevel(target));
        data.aiPerson.getStats().setLevel(target);
        Console.showMessage("旗舰「" + member.getShipName() + "」的舰载 AI："
                + before + " → " + target + " 级（上限 " + cap + "）。");
        return CommandResult.SUCCESS;
    }

    private CommandResult badSyntax() {
        Console.showMessage("用法：MociAI levelup [正整数] 或 MociAI evolve。");
        return CommandResult.BAD_SYNTAX;
    }
}
