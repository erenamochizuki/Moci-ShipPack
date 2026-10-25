package data.campaign;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CoreUITabId;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.listeners.RefitScreenListener;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.util.Misc;
import data.campaign.fighter.Moci_FighterLoadoutService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Defers refit callbacks until native refit UI no longer owns variants and cargo. */
public class Moci_AirSquadronCoordinator implements EveryFrameScript, RefitScreenListener {
    private final Set<String> pending = new LinkedHashSet<>();
    private final Map<String, String> reportedFailures = new HashMap<>();
    private float elapsed = 1f;
    private boolean processing;

    public static void register() {
        if (Global.getSector() == null) return;
        // Both collections are transient; also clear any older persisted version when loading a save.
        Global.getSector().getListenerManager().removeListenerOfClass(Moci_AirSquadronCoordinator.class);
        Global.getSector().removeScriptsOfClass(Moci_AirSquadronCoordinator.class);
        Global.getSector().removeTransientScriptsOfClass(Moci_AirSquadronCoordinator.class);
        Moci_AirSquadronCoordinator coordinator = new Moci_AirSquadronCoordinator();
        Global.getSector().getListenerManager().addListener(coordinator, true);
        Global.getSector().addTransientScript(coordinator);
    }

    @Override
    public void reportFleetMemberVariantSaved(FleetMemberAPI member, MarketAPI dockedAt) {
        if (!processing && member != null && member.getId() != null) pending.add(member.getId());
    }

    @Override
    public void advance(float amount) {
        if (processing || Global.getSector() == null) return;
        CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
        if (fleet == null || fleet.getBattle() != null || Global.getCurrentState() == GameState.COMBAT) return;
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        // Market refit is nested inside an interaction dialog. The public API cannot inspect that
        // nested core tab, so wait for the dialog to close as well as checking the space refit tab.
        if (ui == null || ui.getCurrentCoreTab() == CoreUITabId.REFIT || ui.isShowingDialog()) return;
        elapsed += Math.max(0f, amount);
        boolean audit = elapsed >= 1f;
        if (!audit && pending.isEmpty()) return;
        if (audit) elapsed = 0f;
        processing = true;
        try {
            Set<String> currentMembers = new LinkedHashSet<>();
            for (FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
                currentMembers.add(member.getId());
                if (audit && needsRestore(member)) pending.add(member.getId());
            }
            reportedFailures.keySet().retainAll(currentMembers);
            for (String id : new ArrayList<>(pending)) {
                pending.remove(id);
                FleetMemberAPI member = Moci_FighterLoadoutService.findMember(id);
                if (!needsRestore(member)) {
                    reportedFailures.remove(id);
                    continue;
                }
                String reason;
                try {
                    reason = Moci_FighterLoadoutService.restoreWithoutHullmod(member);
                } catch (RuntimeException ex) {
                    reason = "装配恢复异常，已保留舰船及库存：" + ex.getClass().getSimpleName();
                    if (!reason.equals(reportedFailures.get(id))) {
                        Global.getLogger(Moci_AirSquadronCoordinator.class).warn(reason, ex);
                    }
                }
                if (reason == null) {
                    reportedFailures.remove(id);
                } else if (!reason.equals(reportedFailures.put(id, reason))) {
                    Global.getLogger(Moci_AirSquadronCoordinator.class).warn("航空中队恢复失败 " + id + ": " + reason);
                    ui.addMessage("航空中队管理：" + member.getShipName() + " — " + reason,
                            Misc.getNegativeHighlightColor());
                }
            }
        } finally {
            processing = false;
        }
    }

    private boolean needsRestore(FleetMemberAPI member) {
        if (member == null || member.getVariant() == null
                || member.getVariant().hasHullMod(Moci_FighterLoadoutService.HULLMOD_ID)) return false;
        for (String wingId : member.getVariant().getWings()) {
            if (Moci_FighterLoadoutService.isCustomWingId(wingId)) return true;
        }
        return false;
    }

    @Override
    public boolean isDone() { return false; }

    @Override
    public boolean runWhilePaused() { return true; }
}
