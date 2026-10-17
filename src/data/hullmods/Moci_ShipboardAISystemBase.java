package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import java.awt.Color;

/**
 * 舰载AI系统通用船插基类。
 */
public abstract class Moci_ShipboardAISystemBase extends BaseHullMod {

    private static final Color SKILL_NORMAL_COLOR = new Color(255, 215, 0);
    private static final Color SKILL_ELITE_COLOR = Misc.getPositiveHighlightColor();
    private static final String SHIPBOARD_AI_LOG_PREFIX = "[MOCI_SHIPBOARD_AI]";
    private static final String PLAYER_CONTROL_TRACK_KEY = "$moci_shipboard_ai_player_control_track";
    private static final String PLAYER_CONTROL_CAPTAIN_KEY = "$moci_shipboard_ai_player_control_prev_captain";
    private static final String NO_CAPTAIN_MARKER = "$moci_shipboard_ai_no_captain";

    protected abstract String getHullmodId();

    protected boolean shouldHideAIDetailsBeforeAssignment() {
        return false;
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return !Moci_ShipboardAISystemRegistry.hasConflict(ship, getHullmodId());
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        String reason = Moci_ShipboardAISystemRegistry.getConflictReason(ship, getHullmodId());
        return reason != null ? reason : super.getUnapplicableReason(ship);
    }

    @Override
    public void advanceInCampaign(FleetMemberAPI member, float amount) {
        if (member == null || member.getFleetData() == null || member.getFleetData().getFleet() == null) {
            return;
        }
        Moci_ShipboardAISystemRegistry.enforceSingleAISystem(member, getHullmodId());
        if (!member.getFleetData().getFleet().isPlayerFleet()) {
            return;
        }
        if (!Moci_ShipboardAIConfigLoader.hasShipboardAISystem(member)) {
            return;
        }
        Moci_ShipboardAIManager.refreshCampaignMember(member);
    }

    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        if (ship == null || !ship.isAlive()) {
            return;
        }
        if (ship.getOriginalOwner() != 0 && ship.getOwner() != 0) {
            return;
        }
        if (ship.getFleetMember() == null) {
            return;
        }
        if (!Moci_ShipboardAIConfigLoader.hasShipboardAISystem(ship.getFleetMember())) {
            return;
        }
        handlePlayerControlTransition(ship);
        Moci_ShipboardAIManager.updateCombatState(ship, getEffectModId());
    }

    protected String getEffectModId() {
        return getClass().getSimpleName();
    }

    protected void enforceSingleAISystem(FleetMemberAPI member) {
        Moci_ShipboardAISystemRegistry.enforceSingleAISystem(member, getHullmodId());
    }

    protected void enforceSingleAISystemInRefit(MutableShipStatsAPI stats) {
        Moci_ShipboardAISystemRegistry.enforceSingleAISystemInRefit(stats, getHullmodId());
    }

    protected boolean isPlayerShip(ShipAPI ship) {
        if (ship == null) {
            return false;
        }
        if (Global.getSector() == null || ship.getFleetMember() == null || ship.getFleetMember().getFleetData() == null
                || ship.getFleetMember().getFleetData().getFleet() == null) {
            return ship.getOwner() == 0 || ship.getOriginalOwner() == 0;
        }
        return ship.getFleetMember().getFleetData().getFleet().isPlayerFleet();
    }

    protected void handlePlayerControlTransition(ShipAPI ship) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            clearPlayerControlTracking(ship);
            return;
        }

        boolean playerControlling = isPlayerControllingShip(ship, engine);
        boolean tracked = Boolean.TRUE.equals(ship.getCustomData().get(PLAYER_CONTROL_TRACK_KEY));

        if (playerControlling) {
            if (!tracked) {
                rememberCaptainBeforePlayerControl(ship);
                ship.setCustomData(PLAYER_CONTROL_TRACK_KEY, true);
            }
            return;
        }

        if (!tracked) {
            return;
        }

        restoreCaptainAfterPlayerControl(ship);
        clearPlayerControlTracking(ship);
    }

    protected boolean isPlayerControllingShip(ShipAPI ship, CombatEngineAPI engine) {
        if (ship == null || engine == null) {
            return false;
        }
        if (ship == engine.getPlayerShip()) {
            return true;
        }
        if (ship == engine.getShipPlayerIsTransferringCommandTo()) {
            return true;
        }
        return ship == engine.getShipPlayerIsTransferringCommandFrom();
    }

    protected void rememberCaptainBeforePlayerControl(ShipAPI ship) {
        if (ship == null) {
            return;
        }

        PersonAPI captain = ship.getCaptain();
        if (captain == null) {
            ship.setCustomData(PLAYER_CONTROL_CAPTAIN_KEY, NO_CAPTAIN_MARKER);
            return;
        }

        ship.setCustomData(PLAYER_CONTROL_CAPTAIN_KEY, captain);
    }

    protected void restoreCaptainAfterPlayerControl(ShipAPI ship) {
        if (ship == null) {
            return;
        }

        PersonAPI currentCaptain = ship.getCaptain();
        if (currentCaptain != null && !currentCaptain.isDefault() && !currentCaptain.isPlayer()) {
            return;
        }

        Object saved = ship.getCustomData().get(PLAYER_CONTROL_CAPTAIN_KEY);
        if (saved instanceof PersonAPI) {
            syncCaptain(ship, (PersonAPI) saved);
            return;
        }

        if (NO_CAPTAIN_MARKER.equals(saved)) {
            syncCaptain(ship, Global.getFactory().createPerson());
        }
    }

    protected void clearPlayerControlTracking(ShipAPI ship) {
        if (ship == null) {
            return;
        }
        ship.removeCustomData(PLAYER_CONTROL_TRACK_KEY);
        ship.removeCustomData(PLAYER_CONTROL_CAPTAIN_KEY);
    }

    protected void syncCaptain(ShipAPI ship, PersonAPI captain) {
        if (ship == null) {
            return;
        }
        ship.setCaptain(captain);
        if (ship.getFleetMember() != null) {
            ship.getFleetMember().setCaptain(captain);
        }
    }

    protected void addShipboardAIDescriptionSection(TooltipMakerAPI tooltip, ShipAPI ship, String aiTypeId) {
        float pad = 8f;
        float opad = 10f;
        FleetMemberAPI member = ship != null ? ship.getFleetMember() : null;
        String placeholderAiTypeId = Moci_ShipboardAIConfigLoader.getCanonicalAiTypeId(aiTypeId);
        Moci_ShipboardAIManager.ShipboardAIData data = null;
        String currentAiTypeId = null;
        boolean hasAssignedAi = false;
        boolean showAiDetails = false;
        String resolvedAiTypeId = placeholderAiTypeId;
        Moci_ShipboardAIConfigLoader.AITypeDefinition type = null;

        try {
            data = member != null ? Moci_ShipboardAIManager.getDataByMember(member) : null;
            currentAiTypeId = data != null
                    ? Moci_ShipboardAIConfigLoader.getCanonicalAiTypeId(data.currentAiTypeId)
                    : null;
            hasAssignedAi = currentAiTypeId != null
                    && !currentAiTypeId.trim().isEmpty()
                    && !currentAiTypeId.equals(placeholderAiTypeId);
            showAiDetails = !shouldHideAIDetailsBeforeAssignment() || hasAssignedAi;
            resolvedAiTypeId = hasAssignedAi ? currentAiTypeId : placeholderAiTypeId;

            type = Moci_ShipboardAIConfigLoader.getAiType(resolvedAiTypeId);
            if (type == null) {
                logShipboardAIWarn("tooltip_missing_type", ship, member, aiTypeId, resolvedAiTypeId, data, null,
                        "showAiDetails=" + showAiDetails + " | hasAssignedAi=" + hasAssignedAi);
                tooltip.addPara(Moci_ShipboardAITextRegistry.getUiText("tooltip_missing_config", "舰载AI配置缺失。"),
                        opad, Misc.getNegativeHighlightColor());
                return;
            }

            String displayName = type.name;
            int level = 1;
            float currExp = Moci_ShipboardAIManager.getExperienceForLevel(1);
            float nextExp = Moci_ShipboardAIManager.getExperienceForLevel(2);
            String personality = showAiDetails
                    ? Moci_ShipboardAIManager.getPersonalityLabel(type.personality)
                    : Moci_ShipboardAITextRegistry.getUiText("tooltip_unknown_personality", "未知");

            if (data != null && showAiDetails) {
                displayName = (data.customName != null && !data.customName.trim().isEmpty()) ? data.customName : type.name;
                level = Moci_ShipboardAIManager.getCurrentLevel(data);
                currExp = Moci_ShipboardAIManager.getCurrentExp(data);
                nextExp = Moci_ShipboardAIManager.getNextLevelExp(data);
            }

            String safeDisplayName = escapeTooltipFormat(displayName);
            String safePersonality = escapeTooltipFormat(personality);

            tooltip.addSectionHeading(Moci_ShipboardAITextRegistry.getUiText("tooltip_section_ai", "舰载AI"),
                    Alignment.MID, opad);
            TooltipMakerAPI aiInfo = tooltip.beginImageWithText(type.portrait, 64f);
            aiInfo.addPara(Moci_ShipboardAITextRegistry.formatTemplate(
                    Moci_ShipboardAITextRegistry.getUiText("tooltip_label_name", "名称: {value}"),
                    Moci_ShipboardAITextRegistry.mapOf("value", safeDisplayName)), 0f, Misc.getHighlightColor(), safeDisplayName);
            aiInfo.addPara(Moci_ShipboardAITextRegistry.formatTemplate(
                    Moci_ShipboardAITextRegistry.getUiText("tooltip_label_level", "等级: {current} / {max}"),
                    Moci_ShipboardAITextRegistry.mapOf("current", String.valueOf(level), "max", String.valueOf(type.levelCap))),
                    2f, Misc.getTextColor());
            aiInfo.addPara(Moci_ShipboardAITextRegistry.formatTemplate(
                    Moci_ShipboardAITextRegistry.getUiText("tooltip_label_exp", "经验: {current} / {next}"),
                    Moci_ShipboardAITextRegistry.mapOf("current", String.valueOf((int) currExp), "next", String.valueOf((int) nextExp))),
                    2f, Misc.getTextColor());
            aiInfo.addPara(Moci_ShipboardAITextRegistry.formatTemplate(
                    Moci_ShipboardAITextRegistry.getUiText("tooltip_label_personality", "性格: {value}"),
                    Moci_ShipboardAITextRegistry.mapOf("value", safePersonality)), 2f, Misc.getTextColor(), safePersonality);
            tooltip.addImageWithText(opad);

            if (showAiDetails) {
                tooltip.addSectionHeading(Moci_ShipboardAITextRegistry.getUiText("tooltip_section_skills", "技能"),
                        Alignment.MID, 6f);
                if (data != null && !data.learnedSkills.isEmpty()) {
                    for (String skillId : data.learnedSkills) {
                        String skillName = getShipboardSkillName(skillId);
                        boolean elite = data.eliteSkills.contains(skillId);
                        Color c = elite ? SKILL_ELITE_COLOR : SKILL_NORMAL_COLOR;
                        String safeSkillName = escapeTooltipFormat(skillName);
                        tooltip.addPara(safeSkillName + (elite
                                        ? Moci_ShipboardAITextRegistry.getUiText("tooltip_elite_suffix", " [精英]")
                                        : ""),
                                pad, c, safeSkillName);
                    }
                } else {
                    for (String skillId : type.defaultSkills) {
                        String safeSkillName = escapeTooltipFormat(getShipboardSkillName(skillId));
                        tooltip.addPara(safeSkillName, pad, SKILL_NORMAL_COLOR, safeSkillName);
                    }
                }

                tooltip.addSectionHeading(Moci_ShipboardAITextRegistry.getUiText("tooltip_section_aux", "辅助效果"),
                        Alignment.MID, 6f);
                if (data != null && !data.learnedAuxSkills.isEmpty()) {
                    for (String auxId : data.learnedAuxSkills) {
                        String auxName = getAuxEffectDisplayName(auxId);
                        float lv = (data.eliteSkills != null && data.eliteSkills.contains(auxId)) ? 2f : 1f;
                        String desc = Moci_ShipboardAIAuxEffectRegistry.getEffectDescription(auxId, lv);
                        String safeAuxName = escapeTooltipFormat(auxName);
                        String safeDesc = escapeTooltipFormat(desc);
                        tooltip.addPara(safeAuxName + " : " + safeDesc, pad, Misc.getTextColor(), safeAuxName);
                    }
                } else {
                    for (String auxId : type.defaultAuxEffects) {
                        String auxName = getAuxEffectDisplayName(auxId);
                        String desc = Moci_ShipboardAIAuxEffectRegistry.getEffectDescription(auxId, 1f);
                        String safeAuxName = escapeTooltipFormat(auxName);
                        String safeDesc = escapeTooltipFormat(desc);
                        tooltip.addPara(safeAuxName + " : " + safeDesc, pad, Misc.getTextColor(), safeAuxName);
                    }
                }
            }
        } catch (Exception ex) {
            logShipboardAIError("tooltip_render_exception", ship, member, aiTypeId, resolvedAiTypeId, data, type, ex);
            tooltip.addPara(Moci_ShipboardAITextRegistry.getUiText("tooltip_render_error", "舰载AI信息渲染失败，请查看日志。"),
                    opad, Misc.getNegativeHighlightColor());
        }
    }

    protected void addShipboardAIControlDescriptionSection(TooltipMakerAPI tooltip, ShipAPI ship,
                                                           String fallbackAiTypeId, String fallbackDisplayName) {
        float opad = 10f;
        String displayName = getShipboardAIControlDisplayName(ship, fallbackAiTypeId, fallbackDisplayName);
        String safeDisplayName = escapeTooltipFormat(displayName);
        tooltip.addPara(Moci_ShipboardAITextRegistry.formatTemplate(
                        Moci_ShipboardAITextRegistry.getUiText("tooltip_control_description",
                                "{value}将在机体没有分配军官时接管舰船的控制。"),
                        Moci_ShipboardAITextRegistry.mapOf("value", safeDisplayName)),
                opad, Misc.getHighlightColor(), safeDisplayName);
    }

    protected String getShipboardSkillName(String skillId) {
        try {
            return Global.getSettings().getSkillSpec(skillId).getName();
        } catch (Exception ex) {
            return skillId;
        }
    }

    protected String getAuxEffectDisplayName(String skillId) {
        return Moci_ShipboardAIAuxEffectRegistry.getDisplayName(skillId);
    }

    protected String escapeTooltipFormat(String text) {
        return text == null ? "" : text.replace("%", "%%");
    }

    protected String getShipboardAIControlDisplayName(ShipAPI ship, String fallbackAiTypeId, String fallbackDisplayName) {
        FleetMemberAPI member = ship != null ? ship.getFleetMember() : null;
        Moci_ShipboardAIManager.ShipboardAIData data = member != null
                ? Moci_ShipboardAIManager.getDataByMember(member)
                : null;

        if (data != null) {
            String customName = data.customName;
            if (customName != null && !customName.trim().isEmpty()) {
                return customName.trim();
            }

            String currentAiTypeId = Moci_ShipboardAIConfigLoader.getCanonicalAiTypeId(data.currentAiTypeId);
            Moci_ShipboardAIConfigLoader.AITypeDefinition currentType =
                    Moci_ShipboardAIConfigLoader.getAiType(currentAiTypeId);
            if (currentType != null && currentType.name != null && !currentType.name.trim().isEmpty()) {
                return currentType.name.trim();
            }
        }

        String canonicalFallbackAiTypeId = Moci_ShipboardAIConfigLoader.getCanonicalAiTypeId(fallbackAiTypeId);
        Moci_ShipboardAIConfigLoader.AITypeDefinition fallbackType =
                Moci_ShipboardAIConfigLoader.getAiType(canonicalFallbackAiTypeId);
        if (fallbackType != null && fallbackType.name != null && !fallbackType.name.trim().isEmpty()) {
            return fallbackType.name.trim();
        }

        return fallbackDisplayName == null || fallbackDisplayName.trim().isEmpty()
                ? "舰载AI"
                : fallbackDisplayName.trim();
    }

    protected void logShipboardAIWarn(String stage, ShipAPI ship, FleetMemberAPI member,
                                      String requestedAiTypeId, String resolvedAiTypeId,
                                      Moci_ShipboardAIManager.ShipboardAIData data,
                                      Moci_ShipboardAIConfigLoader.AITypeDefinition type,
                                      String extra) {
        Global.getLogger(this.getClass()).warn(buildShipboardAIContext(stage, ship, member,
                requestedAiTypeId, resolvedAiTypeId, data, type, extra));
    }

    protected void logShipboardAIError(String stage, ShipAPI ship, FleetMemberAPI member,
                                       String requestedAiTypeId, String resolvedAiTypeId,
                                       Moci_ShipboardAIManager.ShipboardAIData data,
                                       Moci_ShipboardAIConfigLoader.AITypeDefinition type,
                                       Throwable ex) {
        Global.getLogger(this.getClass()).error(buildShipboardAIContext(stage, ship, member,
                requestedAiTypeId, resolvedAiTypeId, data, type, null), ex);
    }

    protected String buildShipboardAIContext(String stage, ShipAPI ship, FleetMemberAPI member,
                                             String requestedAiTypeId, String resolvedAiTypeId,
                                             Moci_ShipboardAIManager.ShipboardAIData data,
                                             Moci_ShipboardAIConfigLoader.AITypeDefinition type,
                                             String extra) {
        StringBuilder sb = new StringBuilder(SHIPBOARD_AI_LOG_PREFIX)
                .append(' ')
                .append(stage)
                .append(" | hullmod=").append(safeLogValue(getHullmodId()))
                .append(" | effectMod=").append(safeLogValue(getEffectModId()))
                .append(" | requestedAiTypeId=").append(safeLogValue(requestedAiTypeId))
                .append(" | resolvedAiTypeId=").append(safeLogValue(resolvedAiTypeId));

        if (ship != null) {
            sb.append(" | shipId=").append(safeLogValue(ship.getId()))
                    .append(" | shipName=").append(safeLogValue(ship.getName()))
                    .append(" | shipOwner=").append(ship.getOwner());
            if (ship.getFleetMember() != null && ship.getFleetMember().getHullSpec() != null) {
                sb.append(" | hullId=").append(safeLogValue(ship.getFleetMember().getHullSpec().getHullId()));
            }
        } else {
            sb.append(" | ship=<null>");
        }

        if (member != null) {
            sb.append(" | memberId=").append(safeLogValue(member.getId()))
                    .append(" | memberShipName=").append(safeLogValue(member.getShipName()))
                    .append(" | hasShipboardAISystem=").append(Moci_ShipboardAIConfigLoader.hasShipboardAISystem(member));
        } else {
            sb.append(" | member=<null>");
        }

        if (data != null) {
            sb.append(" | data.currentAiTypeId=").append(safeLogValue(data.currentAiTypeId))
                    .append(" | data.baseAiTypeId=").append(safeLogValue(data.baseAiTypeId))
                    .append(" | learnedSkills=").append(data.learnedSkills != null ? data.learnedSkills.size() : -1)
                    .append(" | eliteSkills=").append(data.eliteSkills != null ? data.eliteSkills.size() : -1)
                    .append(" | learnedAux=").append(data.learnedAuxSkills != null ? data.learnedAuxSkills.size() : -1)
                    .append(" | customName=").append(safeLogValue(data.customName));
        } else {
            sb.append(" | data=<null>");
        }

        if (type != null) {
            sb.append(" | type.aiId=").append(safeLogValue(type.aiId))
                    .append(" | type.name=").append(safeLogValue(type.name))
                    .append(" | type.portrait=").append(safeLogValue(type.portrait))
                    .append(" | type.levelCap=").append(type.levelCap);
        } else {
            sb.append(" | type=<null>");
        }

        if (extra != null && !extra.trim().isEmpty()) {
            sb.append(" | ").append(extra.trim());
        }

        return sb.toString();
    }

    protected String safeLogValue(String value) {
        return value == null ? "<null>" : value;
    }
}
