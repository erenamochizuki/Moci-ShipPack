package data.scripts.weapons;

/* MSZ-006 背包变形控制器 —— 我方本体代码。
 *
 * 历史：这段逻辑原本就是我们的 Moci_MechTransformController；合并版把它 fork 成
 * xi.arsenal.ZetaTransformController 并让 Moci_MSZ_006_backpack.wpn 改指 fork。
 * 本轮把 fork 完整收编回本类，保留合并版带来的改进（事件驱动切换、连续变形进度、
 * Wave Rider 移动参数、武器组可见性/开火控制、迟到姿态应用、只读探针、资源清理），
 * 但按用户要求恢复我方原本的 4 秒变形冷却。
 */

import java.util.Map;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPluginWithAdvanceAfter;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

import xi.arsenal.RifleFiringPose;
import xi.arsenal.ZetaMobility;
import xi.arsenal.ZetaRifleModes;
import xi.arsenal.ZetaTetherPose;
import xi.arsenal.ZetaWeaponGroups;

public final class Moci_MechTransformController implements EveryFrameWeaponEffectPluginWithAdvanceAfter, AutoCloseable {
    private static final String HULL_ID = "Moci_MSZ_006";
    private static final String BACKPACK_SLOT = "BACKPACK";

    private static final String STATUS_KEY = "MOCI_MSZ_006_TRANSFORM_STATUS";
    private static final String STAT_ID = "MOCI_MSZ_006_TRANSFORM_STATS";
    public static final String CUSTOM_TRANSFORMED = "MOCI_MSZ_006_TRANSFORMED";
    public static final String CUSTOM_TRANSFORM_STATE = "MOCI_MSZ_006_TRANSFORM_STATE";
    public static final String CUSTOM_TRANSFORM_PREPARE = "MOCI_MSZ_006_TRANSFORM_PREPARE";
    public static final String CUSTOM_PARTS_HIDDEN = "MOCI_MSZ_006_PARTS_HIDDEN";


    private static final float COOLDOWN_TIME = 4f;
    private static final float TRANSFORM_ANIM_TIME = 0.55f;
    private static final float REVERT_ANIM_TIME = 0.55f;

    private static final int NORMAL_FRAME = 0;
    private static final int RESTORE_PARTS_FRAME = 1;
    private static final int BLANK_FRAME = 1;
    private static final int BACKPACK_TRANSFORMED_FRAME = 11;

    private static final float NORMAL_MANEUVER_BONUS = 35f;
    private static final float TRANSFORMED_SPEED_BONUS = 45f;

    private static final String[] TWO_FRAME_PART_SLOTS = {
            "BODY",
            "HEAD",
            "HEAD_SOURCE",
            "ARM_L",
            "ARM_R",
            "ARM_L_SOURCE",
            "ARM_R_SOURCE",
            "SHIELD_L_COVER",
            "SHOULDER_L",
            "SHOULDER_R"
    };

    private static final String[] MS_COMBAT_WEAPON_SLOTS = {
            "HEAD_SOURCE",
            "ARM_L_SOURCE",
            "ARM_L_FOLLOW",
            "ARM_R_SOURCE",
            "ZETA_RIFLE_SNIPER",
            "ZETA_WRIST_L",
            "ZETA_WRIST_R"
    };

    private enum State {
        NORMAL,
        TRANSFORMING,
        TRANSFORMED,
        REVERTING,
        REVERT_COMPLETE
    }

    private State state = State.NORMAL;
    private WeaponAPI boundBackpack;
    private CombatEngineAPI boundEngine;
    private ZetaMobility mobility;
    private boolean closed;
    private boolean initialized = false;

    private boolean partsHidden = false;

    private float cooldownTimer = 0f;
    private float animationTimer = 0f;
    private float transformProgress;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (closed || engine == null || engine.isPaused() || weapon == null) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        if (!ZetaMobility.supports(ship)) {
            return;
        }

        if (weapon.getSlot() == null || !BACKPACK_SLOT.equals(weapon.getSlot().getId())) {
            return;
        }

        if (!ship.isAlive() || ship.isHulk()) {
            close();
            return;
        }

        if (!initialized) {
            initialized = true;
            boundBackpack = weapon; boundEngine = engine; mobility = new ZetaMobility(ship);
            setFrame(weapon, NORMAL_FRAME);
            showParts(ship);
        }

        if (boundBackpack != weapon || boundEngine != engine) throw new IllegalStateException("Zeta transform callback reused across weapons/engines" );
        if (cooldownTimer > 0f) {
            cooldownTimer = Math.max(0f, cooldownTimer - amount);
        }

        if(state==State.NORMAL||state==State.TRANSFORMED)ship.removeCustomData(CUSTOM_TRANSFORM_PREPARE);
        updateState(amount, ship, weapon);
        updateWeaponMode(ship);
        ship.setCustomData(CUSTOM_TRANSFORM_STATE, state.name());
        applyStats(ship);
        mobility.apply(state == State.TRANSFORMED || state == State.REVERTING || state == State.REVERT_COMPLETE);
        updatePlayerStatus(engine, ship);
    }

    public record Request(boolean accepted, String reason) { }

    /** Late cosmetic reconciliation only; the native advance above remains the sole transform clock. */
    @Override public void advanceAfter(float amount,CombatEngineAPI engine,WeaponAPI weapon){
        if(closed||engine==null||engine.isPaused()||weapon==null||weapon.getSlot()==null||!BACKPACK_SLOT.equals(weapon.getSlot().getId()))return;
        RifleFiringPose.apply(weapon);ZetaTetherPose.apply(weapon);
    }

    /** Root forwards one unconsumed real X key-down event here and owns consumption of that control.
     * This class never polls Keyboard and never advances an extra original transform callback.
     * Modes may call the same public command; cooldown/state checks cannot be bypassed. */
    public Request requestTransform() {
        if (closed) return new Request(false, "Z变形控制器已关闭");
        if (!initialized || boundBackpack == null || boundEngine == null) return new Request(false, "Z背包尚未完成原生初始化");
        ShipAPI ship = boundBackpack.getShip();
        if (com.fs.starfarer.api.Global.getCombatEngine() != boundEngine || !ZetaMobility.supports(ship) || !ship.isAlive() || ship.isHulk() || ship.wasRemoved()
                || !boundEngine.isEntityInPlay(ship) || boundEngine.isCombatOver()) return new Request(false, "Z本体已离场或失效");
        if (boundEngine.isPaused()) return new Request(false, "暂停时不能开始变形");
        if(ship.controlsLocked()||ship.getFluxTracker()!=null&&ship.getFluxTracker().isOverloaded())return new Request(false,"机体当前无法执行变形");
        beginTransition();
        return new Request(true, state == State.TRANSFORMING ? "开始冲浪者变形" : "开始复归MS形态");
    }

    public String state() { return state.name(); }
    /** Continuous 0..1 visual travel shared by the held rifle effect; requests reverse its direction without resetting it. */
    public float transformProgress(){return transformProgress;}
    public boolean waveRiderReady() {
        return !closed && initialized && state == State.TRANSFORMED && boundBackpack != null
                && boundBackpack.getAnimation() != null && boundBackpack.getAnimation().getFrame() == BACKPACK_TRANSFORMED_FRAME;
    }
    public Map<String, Object> status() {
        return Map.of("state", state.name(), "closed", closed, "initialized", initialized,
                "cooldownRemaining", cooldownTimer, "animationTime", animationTimer,
                "backpackFrame", boundBackpack == null || boundBackpack.getAnimation() == null ? -1 : boundBackpack.getAnimation().getFrame(),
                "waveRiderReady", waveRiderReady(), "transformProgress",transformProgress,"input", "public requestTransform; real X events owned by root",
                "mobility", mobility == null ? Map.of() : mobility.status());
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        if (mobility != null) mobility.close();
        if (boundBackpack != null) {
            ShipAPI ship = boundBackpack.getShip();
            if (ship != null) {
                clearStats(ship); restoreNormalVisuals(ship, boundBackpack);transformProgress=0;state=State.NORMAL;
                ship.removeCustomData(CUSTOM_TRANSFORM_STATE); ship.removeCustomData(CUSTOM_TRANSFORM_PREPARE);
            }
        }
    }

    private void beginTransition() {
        animationTimer=0f;cooldownTimer=0f;
        state=state==State.TRANSFORMED||state==State.TRANSFORMING?State.REVERTING:State.TRANSFORMING;
        ShipAPI ship=boundBackpack.getShip();
        ship.setCustomData(CUSTOM_TRANSFORM_PREPARE,Boolean.TRUE);
        ship.setCustomData(CUSTOM_TRANSFORM_STATE,state.name());
        RifleFiringPose.clear(ship); // Release only the pose lease, not the rifle's held state.
    }

    private void returnAllWeaponsToDefaultAngle(ShipAPI ship) {
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon == null || weapon.getSlot() == null) {
                continue;
            }
            float targetAngle = ship.getFacing() + weapon.getSlot().getAngle();
            weapon.setCurrAngle(approachAngle(weapon.getCurrAngle(), targetAngle, 5f));
        }
    }

    private float approachAngle(float current, float target, float maxStep) {
        float diff = getShortestRotation(current, target);
        if (Math.abs(diff) <= maxStep) {
            return target;
        }
        return current + Math.signum(diff) * maxStep;
    }

    private float getShortestRotation(float from, float to) {
        float diff = (to - from) % 360f;
        if (diff > 180f) {
            diff -= 360f;
        } else if (diff < -180f) {
            diff += 360f;
        }
        return diff;
    }

    private void updateState(float amount,ShipAPI ship,WeaponAPI backpack) {
        switch(state){
            case TRANSFORMING,REVERTING -> {
                animationTimer+=amount;
                boolean forward=state==State.TRANSFORMING;
                transformProgress=Math.max(0,Math.min(1,transformProgress+(forward?1:-1)*amount/TRANSFORM_ANIM_TIME));
                returnAllWeaponsToDefaultAngle(ship);
                setFrame(backpack,Math.round(transformProgress*getBackpackMidFrame(backpack)));
                if(transformProgress>.0001f)hideParts(ship);else showParts(ship);
                ship.setCustomData(CUSTOM_TRANSFORM_PREPARE,Boolean.TRUE);
                if(transformProgress>=1){state=State.TRANSFORMED;animationTimer=0;setFrame(backpack,getBackpackMidFrame(backpack));ship.setCustomData(CUSTOM_TRANSFORMED,Boolean.TRUE);ship.removeCustomData(CUSTOM_TRANSFORM_PREPARE);}
                else if(transformProgress<=0){state=State.NORMAL;animationTimer=0;restoreNormalVisuals(ship,backpack);ship.removeCustomData(CUSTOM_TRANSFORM_PREPARE);}
            }
            case TRANSFORMED -> {transformProgress=1;hideParts(ship);setFrame(backpack,getBackpackMidFrame(backpack));ship.setCustomData(CUSTOM_TRANSFORMED,Boolean.TRUE);}
            case REVERT_COMPLETE,NORMAL -> {state=State.NORMAL;transformProgress=0;showParts(ship);setFrame(backpack,NORMAL_FRAME);ship.removeCustomData(CUSTOM_TRANSFORMED);ship.removeCustomData(CUSTOM_TRANSFORM_PREPARE);}
        }
    }

    private void setBackpackFrame(WeaponAPI backpack, int startFrame, int endFrame, float progress) {
        float clamped = Math.max(0f, Math.min(1f, progress));
        int frame = startFrame + Math.round((endFrame - startFrame) * clamped);
        setFrame(backpack, frame);
    }

    private boolean isBackpackPastNormalFrame(WeaponAPI backpack) {
        if (backpack == null || backpack.getAnimation() == null) {
            return false;
        }
        return backpack.getAnimation().getFrame() > NORMAL_FRAME;
    }

    private boolean isBackpackAtRestorePartsFrame(WeaponAPI backpack) {
        if (backpack == null || backpack.getAnimation() == null) {
            return false;
        }
        return backpack.getAnimation().getFrame() <= RESTORE_PARTS_FRAME;
    }

    private void hideParts(ShipAPI ship) {
        if (!partsHidden) {
            partsHidden = true;
            ship.setCustomData(CUSTOM_PARTS_HIDDEN, Boolean.TRUE);
        }

        for (int i = 0; i < TWO_FRAME_PART_SLOTS.length; i++) {
            setSlotFrame(ship, TWO_FRAME_PART_SLOTS[i], BLANK_FRAME);
        }
    }

    private void showParts(ShipAPI ship) {
        if (!partsHidden) {
            return;
        }

        partsHidden = false;
        ship.removeCustomData(CUSTOM_PARTS_HIDDEN);
        for (int i = 0; i < TWO_FRAME_PART_SLOTS.length; i++) {
            setSlotFrame(ship, TWO_FRAME_PART_SLOTS[i], NORMAL_FRAME);
        }
    }

    private void restoreNormalVisuals(ShipAPI ship, WeaponAPI backpack) {
        showParts(ship);
        setFrame(backpack, NORMAL_FRAME);
        ship.removeCustomData(CUSTOM_TRANSFORMED);
    }

    private void applyStats(ShipAPI ship) {
        clearStats(ship);
        if (state == State.TRANSFORMED || state == State.REVERTING || state == State.REVERT_COMPLETE) {
            ship.getMutableStats().getMaxSpeed().modifyPercent(STAT_ID, TRANSFORMED_SPEED_BONUS);
            ship.getMutableStats().getAcceleration().modifyPercent(STAT_ID, TRANSFORMED_SPEED_BONUS);
            ship.getMutableStats().getDeceleration().modifyPercent(STAT_ID, TRANSFORMED_SPEED_BONUS);
        } else {
            ship.getMutableStats().getAcceleration().modifyPercent(STAT_ID, NORMAL_MANEUVER_BONUS);
            ship.getMutableStats().getDeceleration().modifyPercent(STAT_ID, NORMAL_MANEUVER_BONUS);
            ship.getMutableStats().getTurnAcceleration().modifyPercent(STAT_ID, NORMAL_MANEUVER_BONUS);
            ship.getMutableStats().getMaxTurnRate().modifyPercent(STAT_ID, NORMAL_MANEUVER_BONUS);
        }
    }

    private void clearStats(ShipAPI ship) {
        ship.getMutableStats().getMaxSpeed().unmodify(STAT_ID);
        ship.getMutableStats().getAcceleration().unmodify(STAT_ID);
        ship.getMutableStats().getDeceleration().unmodify(STAT_ID);
        ship.getMutableStats().getTurnAcceleration().unmodify(STAT_ID);
        ship.getMutableStats().getMaxTurnRate().unmodify(STAT_ID);
    }

    private void updatePlayerStatus(CombatEngineAPI engine, ShipAPI ship) {
        if (engine.getPlayerShip() != ship) {
            return;
        }

        String title = "Z高达变形系统";
        String data;
        boolean isDebuff = false;

        if (state == State.TRANSFORMING) {
            data = "变形中";
        } else if (state == State.REVERTING || state == State.REVERT_COMPLETE) {
            data = "复归中";
        } else if (cooldownTimer > 0f) {
            data = String.format("冷却 %.1f秒", cooldownTimer);
            isDebuff = true;
        } else if (state == State.TRANSFORMED) {
            data = "Wave Rider: 基准最高330 / 加速度650 / 转向60，X切换复归";
        } else {
            data = "MS形态: 加速度220 / 刹车400 / 转向100，X切换变形";
        }

        engine.maintainStatusForPlayerShip(STATUS_KEY,
                "graphics/icons/hullsys/maneuvering_jets.png",
                title,
                data,
                isDebuff);
    }

    private void setSlotFrame(ShipAPI ship, String slotId, int frame) {
        for (WeaponAPI part : ship.getAllWeapons()) {
            if (part.getSlot() != null && slotId.equals(part.getSlot().getId())) {
                setFrame(part, frame);
                return;
            }
        }
    }

    private void setFrame(WeaponAPI weapon, int frame) {
        if (weapon == null || weapon.getAnimation() == null) {
            return;
        }
        weapon.getAnimation().pause();
        int maxFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
        weapon.getAnimation().setFrame(Math.max(0, Math.min(frame, maxFrame)));
    }

    private int getBackpackMidFrame(WeaponAPI backpack) {
        return Math.min(BACKPACK_TRANSFORMED_FRAME, getBackpackLastFrame(backpack));
    }

    private int getBackpackLastFrame(WeaponAPI backpack) {
        if (backpack == null || backpack.getAnimation() == null) {
            return 0;
        }
        return Math.max(0, backpack.getAnimation().getNumFrames() - 1);
    }

    private void updateWeaponMode(ShipAPI ship) {
        // Weapon callbacks may execute inside a native live WeaponGroup iterator.
        // Only the outer combat-plugin actor may prepare groups. Until it exists,
        // suppress the managed attacks; callbacks never compose or reindex groups.
        if(ZetaRifleModes.instance(ship)==null){
            for(WeaponAPI current:ship.getAllWeapons())if(current.getSlot()!=null){
                String slot=current.getSlot().getId();
                for(String managed:MS_COMBAT_WEAPON_SLOTS)if(managed.equals(slot)){current.setForceNoFireOneFrame(true);break;}
            }
            return;
        }
        boolean wrMode = state == State.TRANSFORMED;
        boolean suppressFire = state == State.TRANSFORMING
                || state == State.REVERTING
                || state == State.REVERT_COMPLETE;
        ZetaWeaponGroups.applyVisibilityAndFireControl(ship,wrMode,suppressFire);
    }
}
