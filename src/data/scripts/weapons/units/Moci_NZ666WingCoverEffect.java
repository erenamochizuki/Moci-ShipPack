package data.scripts.weapons.units;

import com.fs.starfarer.api.AnimationAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

import java.util.List;

public class Moci_NZ666WingCoverEffect implements EveryFrameWeaponEffectPlugin {
    private boolean controllerActive;
    private String moduleSlotId;
    private ShipAPI module;
    private Moci_NZ666WingPose pose;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused() || weapon == null || weapon.getShip() == null
                || weapon.getSlot() == null || weapon.getAnimation() == null) {
            return;
        }
        if (!controllerActive) {
            sync(weapon, weapon.getShip().getChildModulesCopy(), Moci_NZ666FullBodyAnimationController.NEUTRAL_MOVEMENT_FRAME);
        } else if (!isLiveInSlot(module, weapon.getShip())) {
            showFrame(weapon.getAnimation(), weapon.getAnimation().getNumFrames() - 1);
        }
    }

    void setMovementFrame(WeaponAPI cover, List<ShipAPI> children, int frame) {
        controllerActive = true;
        sync(cover, children, frame);
    }

    private void sync(WeaponAPI cover, List<ShipAPI> children, int frame) {
        AnimationAPI animation = cover.getAnimation();
        if (animation == null || animation.getNumFrames() < 2) return;
        if (moduleSlotId == null) {
            String coverSlot = cover.getSlot().getId();
            if (!coverSlot.endsWith("_COVER")) return;
            moduleSlotId = coverSlot.substring(0, coverSlot.length() - "_COVER".length());
        }

        ShipAPI match = null;
        for (ShipAPI child : children) {
            if (isLiveInSlot(child, cover.getShip())) {
                match = child;
                break;
            }
        }
        int blank = animation.getNumFrames() - 1;
        if (match == null) {
            showFrame(animation, blank);
            return;
        }
        if (match != module) {
            module = match;
            pose = Moci_NZ666WingPose.forModule(moduleSlotId, match.getHullSpec().getHullId());
        }
        if (frame < 0 || frame >= blank) return;
        showFrame(animation, pose == null ? frame : pose.apply(module, frame));
    }

    private boolean isLiveInSlot(ShipAPI child, ShipAPI parent) {
        return child != null && child.getParentStation() == parent && child.getStationSlot() != null
                && moduleSlotId.equals(child.getStationSlot().getId())
                && child.isAlive() && !child.isHulk() && child.getHitpoints() > 0f;
    }

    private static void showFrame(AnimationAPI animation, int frame) {
        animation.pause();
        if (animation.getFrame() != frame) animation.setFrame(frame);
    }
}
