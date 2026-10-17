package data.scripts.ships.ai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatAssignmentType;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.CombatTaskManagerAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.DeployedFleetMemberAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.combat.ai.BasicShipAI;
import com.fs.starfarer.combat.entities.Ship;
import data.hullmods.Moci_LandingAI;
import data.hullmods.Moci_LandingSequence;
import data.hullmods.Moci_MechTurretMode;
import data.scripts.util.Moci_TextLoader;
import org.boxutil.manager.CombatRenderingManager;
import org.boxutil.units.standard.entity.SpriteEntity;
import org.boxutil.util.TransformUtil;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.util.vector.Matrix2f;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class Moci_MechTurretAI extends BasicShipAI {
    public static final String DOCK_TAG = "Moci_MechDock";
    private static final String TEXT_ID = "Moci_MechTurretAI";
    public enum PlayerNavigationMode {
        TURRET,
        REFIT
    }

    private static final String BUFF_ID = "Moci_MechTurretAI_Buff";
    private static final String MECH_AI_INSTANCE_KEY = "Moci_MechTurretAIInstance";
    private static final String ORIGIN_AI_INSTANCE_KEY = "Moci_OriginAIInstance";
    private static final String PLAYER_MODE_KEY = "Moci_PlayerNavigationMode";
    private static final String PLAYER_MODE_SWITCH_PRESSED_KEY = "Moci_PlayerNavigationModeSwitchPressed";
    private static final String MODE_STATUS_KEY = "Moci_PlayerNavigationModeStatus";
    private static final String TARGET_STATUS_KEY = "Moci_PlayerNavigationTargetStatus";
    private static final String AUTOPILOT_STATUS_KEY = "Moci_PlayerNavigationAutopilotStatus";
    public static final boolean ENABLE_TURRET_AMMO_RELOAD = false;
    private static final float MEDIUM_AI_ENGAGEMENT_RANGE = 1500f;
    private static final float MEDIUM_AI_HOST_SEARCH_RANGE = 1500f;
    private static final float MEDIUM_AI_NO_ENEMY_RELEASE_RANGE = 2000f;
    private static final float MEDIUM_AI_NO_ENEMY_RELEASE_DELAY = 1.5f;
    private static final float MEDIUM_AI_WEAK_TARGET_HULL_THRESHOLD = 0.35f;
    private static final float MEDIUM_AI_REDOCK_DELAY_AFTER_RELEASE = 5f;
    private static final float LOW_AI_HULL_FALLBACK_THRESHOLD = 0.7f;
    private static final float TARGET_SEARCH_INTERVAL_MIN = 0.8f;
    private static final float TARGET_SEARCH_INTERVAL_MAX = 1.25f;
    private static final String SLOT_INDICATOR_CIRCLE_SPRITE_PATH = "graphics/fx/Moci_circle.png";
    private static final String SLOT_INDICATOR_ARROW_SPRITE_PATH = "graphics/fx/Moci_arrow.png";
    private static final float SLOT_INDICATOR_BASE_SCALE = 0.25f;
    private static final float SLOT_INDICATOR_CIRCLE_SPIN_RATE = 72f;
    private static final float SLOT_INDICATOR_ARROW_SPIN_RATE = 180f;
    private static final float SLOT_INDICATOR_READY_ALPHA = 0.55f;
    private static final float SLOT_INDICATOR_DISMISS_DURATION = 0.25f;
    private static final float SLOT_INDICATOR_CIRCLE_DISMISS_SCALE = 0.9f;
    private static final float SLOT_INDICATOR_ARROW_DISMISS_SCALE = 0.75f;
    private static final Color SLOT_INDICATOR_READY_COLOR = new Color(170, 255, 195);
    private static final Color SLOT_INDICATOR_DISMISS_COLOR = new Color(255, 96, 96);

    /**
     * 炮塔模式下的自动补弹间隔，单位为秒。
     *
     * 只有当机体停靠在允许补弹的槽位类型上时，
     * 才会按这个时间间隔检查并补满机体的武器弹药。
     *
     * 当前配置：10 秒补一次。
     */
    public static final float AMMO_RELOAD_INTERVAL_SECONDS = 10f;
    public static final float FLUX_TRANSFER_MULT = 2f;
    public static final float HOST_VENT_BLOCK_FLUX_THRESHOLD = 0.75f;

    private static final Set<WeaponAPI.WeaponType> SLOT_TYPE = new HashSet<WeaponAPI.WeaponType>();
    private static final Set<WeaponAPI.WeaponType> RELOAD_SLOT_TYPE = new HashSet<WeaponAPI.WeaponType>();
    /**
     * 炮塔模式停靠完成后，对机体自身武器提供的射程加成表。
     *
     * 这里的数值单位是“百分比”，并且是直接传给：
     * - stats.getBallisticWeaponRangeBonus().modifyPercent(...)
     * - stats.getEnergyWeaponRangeBonus().modifyPercent(...)
     *
     * 也就是说：
     * 20f = 实弹/能量武器射程 +20%
     * 40f = 实弹/能量武器射程 +40%
     *
     * 注意：
     * 1. 这个加成只在“已经完成停靠”后生效，接近途中不会提前加。
     * 2. 当前筛选逻辑要求宿主舰至少为驱逐舰，因此 FRIGATE 这一档理论上是保留值，
     *    现版本正常流程下通常不会真正用到。
     * 3. MISSILE 射程不吃这里的表，因为这里只改实弹和能量的 range bonus。
     */
    private static final Map<ShipAPI.HullSize, Float> RANGE_BONUS = new HashMap<ShipAPI.HullSize, Float>();

    public static int releaseKey = Keyboard.KEY_B;
    public static int modeSwitchKey = Keyboard.KEY_Z;
    private static boolean lunaSettingsInitialized = false;

    static {
        SLOT_TYPE.add(WeaponAPI.WeaponType.BALLISTIC);
        SLOT_TYPE.add(WeaponAPI.WeaponType.ENERGY);
        SLOT_TYPE.add(WeaponAPI.WeaponType.MISSILE);
        SLOT_TYPE.add(WeaponAPI.WeaponType.HYBRID);
        SLOT_TYPE.add(WeaponAPI.WeaponType.SYNERGY);
        SLOT_TYPE.add(WeaponAPI.WeaponType.COMPOSITE);
        SLOT_TYPE.add(WeaponAPI.WeaponType.UNIVERSAL);

        RELOAD_SLOT_TYPE.add(WeaponAPI.WeaponType.MISSILE);
        RELOAD_SLOT_TYPE.add(WeaponAPI.WeaponType.SYNERGY);
        RELOAD_SLOT_TYPE.add(WeaponAPI.WeaponType.COMPOSITE);
        RELOAD_SLOT_TYPE.add(WeaponAPI.WeaponType.UNIVERSAL);

        // 预留：战机作为宿主没有意义，这里只是给一个安全兜底值。
        RANGE_BONUS.put(ShipAPI.HullSize.FIGHTER, 0f);
        // 预留：当前目标筛选不允许停靠护卫舰，这一档基本不会触发。
        RANGE_BONUS.put(ShipAPI.HullSize.FRIGATE, 10f);
        // 驱逐舰宿主：机体实弹/能量武器射程 +20%
        RANGE_BONUS.put(ShipAPI.HullSize.DESTROYER, 20f);
        // 巡洋舰宿主：机体实弹/能量武器射程 +40%
        RANGE_BONUS.put(ShipAPI.HullSize.CRUISER, 40f);
        // 主力舰宿主：机体实弹/能量武器射程 +60%
        RANGE_BONUS.put(ShipAPI.HullSize.CAPITAL_SHIP, 60f);
    }

    private final ShipAPI ship;
    private final Vector2f zero = new Vector2f(0f, 0f);
    private boolean useDefaultAI = true;
    private Vector2f lastTargetPosition = null;
    private float approachFactor = 0f;
    private Vector2f targetVelocity = new Vector2f(0f, 0f);

    public Moci_MechTurretAI(ShipAPI ship) {
        super((Ship) ship);
        this.ship = ship;
        ensureLunaSettingsLoaded();
    }

    public static void updateLunaSettings() {
        releaseKey = Keyboard.KEY_B;
        modeSwitchKey = Keyboard.KEY_Z;
        try {
            if (Global.getSettings().getModManager().isModEnabled("lunalib")) {
                try {
                    releaseKey = lunalib.lunaSettings.LunaSettings.getInt("moci_shippack", "Moci_MechTurretReleaseKey");
                } catch (Exception ignored) {
                    releaseKey = Keyboard.KEY_B;
                }
                try {
                    modeSwitchKey = lunalib.lunaSettings.LunaSettings.getInt("moci_shippack", "Moci_MechTurretModeSwitchKey");
                } catch (Exception ignored) {
                    modeSwitchKey = Keyboard.KEY_Z;
                }
            }
        } catch (Exception ignored) {
            releaseKey = Keyboard.KEY_B;
            modeSwitchKey = Keyboard.KEY_Z;
        }
    }

    private static void ensureLunaSettingsLoaded() {
        if (lunaSettingsInitialized) {
            return;
        }
        lunaSettingsInitialized = true;
        updateLunaSettings();
    }

    public static void loadSettings() {
        updateLunaSettings();
    }

    public static float getRangeBonusForHostHullSize(ShipAPI.HullSize hullSize) {
        Float bonus = RANGE_BONUS.get(hullSize);
        return bonus == null ? 0f : bonus;
    }

    public static PlayerNavigationMode getPlayerNavigationMode(ShipAPI ship) {
        ensureLunaSettingsLoaded();
        if (ship == null) {
            return PlayerNavigationMode.TURRET;
        }
        Object stored = ship.getCustomData().get(PLAYER_MODE_KEY);
        if (stored instanceof PlayerNavigationMode) {
            return (PlayerNavigationMode) stored;
        }
        ship.setCustomData(PLAYER_MODE_KEY, PlayerNavigationMode.TURRET);
        return PlayerNavigationMode.TURRET;
    }

    public static boolean isRefitModeSelected(ShipAPI ship) {
        return getPlayerNavigationMode(ship) == PlayerNavigationMode.REFIT;
    }

    private static void togglePlayerNavigationMode(ShipAPI ship) {
        if (ship == null) {
            return;
        }
        PlayerNavigationMode next = getPlayerNavigationMode(ship) == PlayerNavigationMode.TURRET
                ? PlayerNavigationMode.REFIT
                : PlayerNavigationMode.TURRET;
        ship.setCustomData(PLAYER_MODE_KEY, next);
    }

    public static void advancePlayerNavigationModeSwitch(ShipAPI ship, ShipAPI selectedTarget) {
        if (ship == null || Global.getCombatEngine() == null || Global.getCombatEngine().getPlayerShip() != ship) {
            return;
        }
        if (!shouldAssignTurretAI(ship)) {
            ship.removeCustomData(PLAYER_MODE_SWITCH_PRESSED_KEY);
            return;
        }

        boolean selectedFriendly = selectedTarget != null && selectedTarget.getOwner() == ship.getOwner();
        boolean pressed = Boolean.TRUE.equals(ship.getCustomData().get(PLAYER_MODE_SWITCH_PRESSED_KEY));

        if (!selectedFriendly) {
            ship.setCustomData(PLAYER_MODE_SWITCH_PRESSED_KEY, false);
            return;
        }

        if (!pressed && Keyboard.isKeyDown(modeSwitchKey)) {
            togglePlayerNavigationMode(ship);
            ship.setCustomData(PLAYER_MODE_SWITCH_PRESSED_KEY, true);
        } else if (!Keyboard.isKeyDown(modeSwitchKey)) {
            ship.setCustomData(PLAYER_MODE_SWITCH_PRESSED_KEY, false);
        }
    }

    public static boolean canUseAsTurretHost(ShipAPI ship, ShipAPI ally) {
        if (!shouldAssignTurretAI(ship) || ally == null || ally == ship) {
            return false;
        }
        if (ally.getOwner() != ship.getOwner()) {
            return false;
        }
        if (!ally.isAlive() || !Global.getCombatEngine().isEntityInPlay(ally)) {
            return false;
        }
        if (Misc.getSizeNum(ally.getHullSize()) < 2) {
            return false;
        }
        if (ally.isPhased() || ally.getCollisionClass() != CollisionClass.SHIP) {
            return false;
        }
        if (ally.getHullSpec().hasTag("Moci_CannotDock")) {
            return false;
        }
        if (ally.getMaxSpeed() * 0.75f > ship.getMaxSpeed()) {
            return false;
        }
        if (ally.getFluxTracker().isOverloadedOrVenting()) {
            return false;
        }

        DockManager manager = getDockManager(ally);
        dockListener listener = getExistingMechDockListener(ship);
        if (listener != null && listener.getTargetShip() == ally) {
            return true;
        }
        return manager.isOccupyable() && manager.getIdleSlot(getPreferredDockPoint(ship)) != null;
    }

    private static boolean isMediumTurretAIMode(ShipAPI ship) {
        return Moci_MechTurretMode.getTurretAIMode(ship) == Moci_MechTurretMode.TurretAIMode.MEDIUM;
    }

    private static boolean isLowTurretAIMode(ShipAPI ship) {
        return Moci_MechTurretMode.getTurretAIMode(ship) == Moci_MechTurretMode.TurretAIMode.LOW;
    }

    private static boolean isManualTurretAIMode(ShipAPI ship) {
        return Moci_MechTurretMode.getTurretAIMode(ship) == Moci_MechTurretMode.TurretAIMode.MANUAL;
    }

    private static ShipAPI findPreferredEnemy(ShipAPI ship, float range) {
        if (ship == null || Global.getCombatEngine() == null) {
            return null;
        }

        ShipAPI target = ship.getShipTarget();
        if (target != null
                && target.getOwner() != ship.getOwner()
                && target.isAlive()
                && !target.isHulk()
                && Global.getCombatEngine().isEntityInPlay(target)
                && Misc.getDistanceSq(ship.getLocation(), target.getLocation()) <= range * range) {
            return target;
        }

        ShipAPI best = null;
        float bestDistanceSq = Float.MAX_VALUE;
        for (ShipAPI enemy : AIUtils.getNearbyEnemies(ship, range)) {
            if (enemy == null || !enemy.isAlive() || enemy.isHulk()) {
                continue;
            }
            float distanceSq = Misc.getDistanceSq(ship.getLocation(), enemy.getLocation());
            if (best == null || distanceSq < bestDistanceSq) {
                best = enemy;
                bestDistanceSq = distanceSq;
            }
        }
        return best;
    }

    private static ShipAPI findNearbyDockableHost(ShipAPI ship, float range) {
        if (ship == null) {
            return null;
        }

        ShipAPI best = null;
        float bestDistanceSq = Float.MAX_VALUE;
        for (ShipAPI ally : AIUtils.getNearbyAllies(ship, range)) {
            if (ally == null || ally == ship) {
                continue;
            }
            if (!canUseAsTurretHost(ship, ally)) {
                continue;
            }
            float distanceSq = Misc.getDistanceSq(ship.getLocation(), ally.getLocation());
            if (best == null || distanceSq < bestDistanceSq) {
                best = ally;
                bestDistanceSq = distanceSq;
            }
        }
        return best;
    }

    private static ShipAPI findNearestDockableHost(ShipAPI ship) {
        if (ship == null) {
            return null;
        }

        ShipAPI best = null;
        float bestDistanceSq = Float.MAX_VALUE;
        for (ShipAPI ally : AIUtils.getAlliesOnMap(ship)) {
            if (ally == null || ally == ship) {
                continue;
            }
            if (!canUseAsTurretHost(ship, ally)) {
                continue;
            }
            float distanceSq = Misc.getDistanceSq(ship.getLocation(), ally.getLocation());
            if (best == null || distanceSq < bestDistanceSq) {
                best = ally;
                bestDistanceSq = distanceSq;
            }
        }
        return best;
    }

    private static ShipAPI getEscortAssignedHost(ShipAPI ship) {
        if (ship == null || Global.getCombatEngine() == null) {
            return null;
        }

        CombatFleetManagerAPI fleetManager = Global.getCombatEngine().getFleetManager(ship.getOwner());
        if (fleetManager == null) {
            return null;
        }

        CombatTaskManagerAPI taskManager = fleetManager.getTaskManager(false);
        if (taskManager == null) {
            return null;
        }

        CombatFleetManagerAPI.AssignmentInfo assignment = taskManager.getAssignmentFor(ship);
        if (assignment == null) {
            return null;
        }

        CombatAssignmentType type = assignment.getType();
        if (type != CombatAssignmentType.LIGHT_ESCORT
                && type != CombatAssignmentType.MEDIUM_ESCORT
                && type != CombatAssignmentType.HEAVY_ESCORT) {
            return null;
        }

        if (!(assignment.getTarget() instanceof DeployedFleetMemberAPI)) {
            return null;
        }

        DeployedFleetMemberAPI deployedTarget = (DeployedFleetMemberAPI) assignment.getTarget();
        return deployedTarget.getShip();
    }

    private static boolean isWeakPursuitTarget(ShipAPI enemy) {
        return enemy != null
                && (enemy.getFluxTracker().isOverloadedOrVenting()
                || enemy.getHullLevel() <= MEDIUM_AI_WEAK_TARGET_HULL_THRESHOLD);
    }

    private static boolean shouldUseLowAIFallbackTurretMode(ShipAPI ship) {
        return ship != null && ship.getHullLevel() <= LOW_AI_HULL_FALLBACK_THRESHOLD;
    }

    private static Vector2f getPreferredDockPoint(ShipAPI ship) {
        if (ship == null || Global.getCombatEngine() == null) {
            return null;
        }
        if (Global.getCombatEngine().getPlayerShip() != ship) {
            return null;
        }
        Vector2f mouseTarget = ship.getMouseTarget();
        if (mouseTarget == null) {
            return null;
        }
        return new Vector2f(mouseTarget);
    }

    private static boolean hasSelectedFriendlyTarget(ShipAPI ship) {
        return ship != null
                && ship.getShipTarget() != null
                && ship.getShipTarget().getOwner() == ship.getOwner();
    }

    public static boolean shouldAssignTurretAI(ShipAPI ship) {
        return ship != null
                && ship.getVariant() != null
                && ship.getVariant().hasHullMod(Moci_MechTurretMode.HULLMOD_ID);
    }

    public static dockListener getMechDockListener(ShipAPI ship) {
        ensureLunaSettingsLoaded();
        dockListener existing = getExistingMechDockListener(ship);
        if (existing != null) {
            return existing;
        }
        dockListener listener = new dockListener(ship);
        ship.addListener(listener);
        return listener;
    }

    public static dockListener getExistingMechDockListener(ShipAPI ship) {
        if (ship != null && ship.hasListenerOfClass(dockListener.class)) {
            return ship.getListeners(dockListener.class).get(0);
        }
        return null;
    }

    public static boolean isDocked(ShipAPI ship) {
        dockListener listener = getExistingMechDockListener(ship);
        return listener != null && listener.isDocked();
    }

    public static boolean hasActiveTurretProcess(ShipAPI ship) {
        dockListener listener = getExistingMechDockListener(ship);
        return listener != null && listener.hasTarget();
    }

    public static boolean isTurretModeActive(ShipAPI ship) {
        return hasActiveTurretProcess(ship);
    }

    public static void releaseTurretTarget(ShipAPI ship) {
        dockListener listener = getExistingMechDockListener(ship);
        if (listener != null) {
            listener.forceReleaseTarget();
        }
    }

    public static void advanceCompatibilityShell(ShipAPI ship, float amount) {
        if (ship == null || !ship.isAlive()) {
            return;
        }

        if (!shouldAssignTurretAI(ship)) {
            return;
        }

        dockListener listener = getMechDockListener(ship);
        advancePlayerNavigationModeSwitch(ship, ship.getShipTarget());
        maintainPlayerNavigationStatus(ship);

        if (ship.getShipAI() instanceof Moci_LandingAI || isLandingAIActive(ship)) {
            if (listener.hasTarget()) {
                listener.forceReleaseTarget();
            }
            return;
        }

        ShipAIPlugin ai = ship.getShipAI();
        if (listener.hasTarget() && !listener.isDocked()) {
            if (ai != null && !(ai instanceof Moci_MechTurretAI)) {
                if (Global.getCombatEngine().getPlayerShip() == ship) {
                    listener.notifyPlayerTurretAutopilotEngaged();
                }
                ShipAIPlugin dockAI;
                if (ship.getCustomData().containsKey(MECH_AI_INSTANCE_KEY)) {
                    dockAI = (ShipAIPlugin) ship.getCustomData().get(MECH_AI_INSTANCE_KEY);
                } else {
                    dockAI = new Moci_MechTurretAI(ship);
                }
                ship.setCustomData(ORIGIN_AI_INSTANCE_KEY, ai);
                ship.setShipAI(dockAI);
            }
        } else if (ai instanceof Moci_MechTurretAI) {
            if (ship.getCustomData().containsKey(ORIGIN_AI_INSTANCE_KEY)) {
                ShipAIPlugin origin = (ShipAIPlugin) ship.getCustomData().get(ORIGIN_AI_INSTANCE_KEY);
                ship.setCustomData(MECH_AI_INSTANCE_KEY, ai);
                ship.setShipAI(origin);
                ship.removeCustomData(ORIGIN_AI_INSTANCE_KEY);
            }
        }
    }

    private static boolean isLandingAIActive(ShipAPI ship) {
        Object active = ship.getCustomData().get("Moci_LandingAIActive");
        return active instanceof Boolean && (Boolean) active;
    }

    private static void maintainPlayerNavigationStatus(ShipAPI ship) {
        if (ship == null || Global.getCombatEngine() == null || Global.getCombatEngine().getPlayerShip() != ship) {
            return;
        }
        if (!hasSelectedFriendlyTarget(ship)) {
            return;
        }

        ShipAPI target = ship.getShipTarget();
        boolean canTurret = canUseAsTurretHost(ship, target);
        boolean canRefit = Moci_LandingSequence.canManualLandOnTarget(ship, target);
        String sprite = Global.getSettings().getSpriteName("ui", "icon_tactical_bdeck");
        Map<String, String> keyReplacements = Moci_TextLoader.mapOf(
                "switch_key", Keyboard.getKeyName(modeSwitchKey),
                "release_key", Keyboard.getKeyName(releaseKey)
        );

        boolean turretMode = getPlayerNavigationMode(ship) == PlayerNavigationMode.TURRET;
        String modeTitle = turretMode
                ? Moci_TextLoader.getText(TEXT_ID, "status.navigation_title_turret")
                : Moci_TextLoader.getText(TEXT_ID, "status.navigation_title_refit");
        String modeText = turretMode
                ? Moci_TextLoader.getTextWithReplacements(TEXT_ID, "status.mode_switch_to_refit", keyReplacements)
                : Moci_TextLoader.getTextWithReplacements(TEXT_ID, "status.mode_switch_to_turret", keyReplacements);
        Global.getCombatEngine().maintainStatusForPlayerShip(
                MODE_STATUS_KEY,
                sprite,
                modeTitle,
                modeText,
                turretMode);

        boolean currentModeAvailable = turretMode ? canTurret : canRefit;
        String targetTitle = currentModeAvailable
                ? Moci_TextLoader.getText(TEXT_ID, "status.target_title_available")
                : Moci_TextLoader.getText(TEXT_ID, "status.target_title_unavailable");
        String refitBlockedReason = Moci_LandingSequence.getRefitBlockedReason(ship);
        String targetStatus = !turretMode && refitBlockedReason != null ? refitBlockedReason : turretMode
                ? Moci_TextLoader.getText(TEXT_ID, "status.target_enter_turret")
                : Moci_TextLoader.getText(TEXT_ID, "status.target_enter_refit");
        Global.getCombatEngine().maintainStatusForPlayerShip(
                TARGET_STATUS_KEY,
                sprite,
                targetTitle,
                targetStatus,
                !currentModeAvailable);
    }

    @Override
    public void advance(float amount) {
        if (useDefaultAI) {
            super.advance(amount);
        }
        useDefaultAI = false;

        dockListener listener = getMechDockListener(ship);
        if (listener.hasTarget()) {
            if (!listener.isDocked()) {
                flyToTarget(listener.computeLoc(), 200f, amount);
            } else {
                useDefaultAI = true;
            }
        } else {
            useDefaultAI = true;
        }
    }

    private void flyToTarget(Vector2f targetLoc, float closeRange, float amount) {
        if (targetLoc == null) {
            return;
        }

        float angularSpeed = ship.getAngularVelocity();
        float angularDistance = MathUtils.getShortestRotation(ship.getFacing(),
                VectorUtils.getAngle(ship.getLocation(), targetLoc));
        float absAngle = Math.abs(angularDistance);

        float maxSpeed = ship.getMaxSpeed();
        Vector2f calculationVelocity = new Vector2f(ship.getVelocity());
        if (calculationVelocity.length() <= maxSpeed * 0.5f) {
            if (calculationVelocity.length() <= maxSpeed * 0.25f) {
                calculationVelocity.set(maxSpeed * 0.5f, 0f);
                VectorUtils.rotate(calculationVelocity, ship.getFacing(), calculationVelocity);
            } else {
                calculationVelocity.scale((maxSpeed * 0.5f) / calculationVelocity.length());
            }
        }

        if (absAngle > angularSpeed * angularSpeed / ship.getTurnAcceleration()) {
            ship.giveCommand(angularDistance < 0 ? ShipCommand.TURN_RIGHT : ShipCommand.TURN_LEFT, targetLoc, 1);
        } else {
            ship.giveCommand(angularSpeed > 0 ? ShipCommand.TURN_RIGHT : ShipCommand.TURN_LEFT, targetLoc, 1);
            float movementAngle = VectorUtils.getAngle(zero, calculationVelocity);
            float movementCorrection = MathUtils.getShortestRotation(ship.getFacing(), movementAngle);
            if (Math.abs(movementCorrection) > 5f) {
                ship.giveCommand(movementCorrection < 0 ? ShipCommand.STRAFE_LEFT : ShipCommand.STRAFE_RIGHT, targetLoc, 1);
            }
        }

        if (absAngle < 5f) {
            float movementAngle = VectorUtils.getAngle(zero, calculationVelocity);
            float movementCorrection = MathUtils.getShortestRotation(ship.getFacing(), movementAngle);
            if (Math.abs(movementCorrection) > 20f) {
                ship.giveCommand(movementCorrection < 0 ? ShipCommand.STRAFE_LEFT : ShipCommand.STRAFE_RIGHT, null, 0);
            }
        }
        if (absAngle < Math.abs(ship.getAngularVelocity()) * 0.1f) {
            ship.setAngularVelocity(angularDistance / 0.1f);
        }

        Vector2f velocity = new Vector2f(ship.getVelocity());
        float speed = velocity.length();
        float dist = MathUtils.getDistance(ship.getLocation(), targetLoc);
        dockListener listener = getMechDockListener(ship);
        if (listener.isDocking()) {
            Vector2f newVelocity = calculateSmoothPath(
                    ship.getLocation(),
                    targetLoc,
                    ship.getVelocity(),
                    listener.getTargetShip().getVelocity(),
                    ship.getMaxSpeedWithoutBoost(),
                    dist,
                    amount);
            ship.getVelocity().set(newVelocity);
            if (dist < speed * amount * 5f) {
                listener.dock();
                approachFactor = 0f;
                targetVelocity = new Vector2f();
            }
        } else {
            float deceleration = ship.getDeceleration();
            if (listener.getTargetShip() != null) {
                velocity.setX(velocity.getX() - listener.getTargetShip().getVelocity().getX());
                velocity.setY(velocity.getY() - listener.getTargetShip().getVelocity().getY());
                speed = Math.max(velocity.length() * 0.8f,
                        projectionMagnitude(velocity, listener.getTargetShip().getVelocity()));
            }
            speed = Math.max(0.1f, speed);
            boolean stop = dist * 0.5f / speed < speed / deceleration || dist < 10f;
            if (absAngle < Math.min(60f * dist / 300f, 90f)) {
                if (Math.abs(ship.getFacing() - VectorUtils.getFacing(ship.getVelocity())) > 5f
                        || (ship.getVelocity().length() < maxSpeed && !stop)) {
                    ship.giveCommand(ShipCommand.ACCELERATE, targetLoc, 1);
                }
            } else if (absAngle > Math.min(60f * closeRange / MathUtils.getDistance(ship, targetLoc) + 90f, 170f)) {
                ship.giveCommand(ShipCommand.ACCELERATE_BACKWARDS, targetLoc, 1);
            } else {
                ship.giveCommand(ShipCommand.DECELERATE, targetLoc, 1);
                if (!listener.isDocking()) {
                    listener.setDocking(true);
                }
            }
        }

    }

    public Vector2f calculateSmoothPath(Vector2f shipLoc, Vector2f targetPos, Vector2f currentVel, Vector2f targetVel,
                                        float maxSpeed, float distance, float amount) {
        if (targetVelocity.length() < 0.1f) {
            targetVelocity = new Vector2f(currentVel);
        }
        if (lastTargetPosition == null) {
            lastTargetPosition = new Vector2f(targetPos);
        }

        Vector2f idealDir = new Vector2f(targetPos.x - shipLoc.x, targetPos.y - shipLoc.y);
        if (idealDir.length() < 0.1f) {
            return new Vector2f(0f, 0f);
        }
        idealDir.normalise();

        float currentSpeed = currentVel.length();
        Vector2f currentDir = new Vector2f(currentVel);
        if (currentSpeed > 0.1f) {
            currentDir.normalise();
        } else {
            currentDir = new Vector2f(idealDir);
        }

        float targetApproachFactor;
        if (distance > 600f) {
            targetApproachFactor = 0.1f;
        } else if (distance > 300f) {
            targetApproachFactor = 0.15f + (600f - distance) / 300f * 0.15f;
        } else if (distance > 100f) {
            targetApproachFactor = 0.3f + (300f - distance) / 200f * 0.3f;
        } else {
            targetApproachFactor = 0.6f + (100f - distance) / 100f * 0.4f;
        }
        approachFactor += (targetApproachFactor - approachFactor) * Math.min(amount * 5f, 0.2f);

        float targetSpeed;
        if (distance > 500f) {
            targetSpeed = maxSpeed;
        } else if (distance > 200f) {
            targetSpeed = maxSpeed * (0.85f + distance / 500f * 0.15f);
        } else if (distance > 100f) {
            targetSpeed = maxSpeed * 0.85f * (distance / 200f);
        } else if (distance > 50f) {
            targetSpeed = maxSpeed * 0.7f * (distance / 100f);
        } else if (distance > 20f) {
            targetSpeed = maxSpeed * 0.5f * (distance / 50f);
        } else {
            targetSpeed = maxSpeed * 0.3f * (distance / 20f);
        }
        targetSpeed = Math.max(targetSpeed, 50f);

        Vector2f idealVelocity = new Vector2f(
                idealDir.x * targetSpeed + targetVel.x,
                idealDir.y * targetSpeed + targetVel.y);

        targetVelocity.x += (idealVelocity.x - targetVelocity.x) * approachFactor * amount * 80f;
        targetVelocity.y += (idealVelocity.y - targetVelocity.y) * approachFactor * amount * 80f;

        float resultSpeed = targetVelocity.length();
        if (resultSpeed > maxSpeed * 1.5f) {
            targetVelocity.normalise();
            targetVelocity.scale(maxSpeed * 1.5f);
        }

        lastTargetPosition = new Vector2f(targetPos);
        return targetVelocity;
    }

    private float projectionMagnitude(Vector2f a, Vector2f b) {
        float dotProduct = a.x * b.x + a.y * b.y;
        float magnitudeB = b.lengthSquared();
        if (magnitudeB == 0f) {
            return 0f;
        }
        return dotProduct / magnitudeB;
    }

    private static DockManager getDockManager(ShipAPI ship) {
        if (ship.hasListenerOfClass(DockManager.class)) {
            return ship.getListeners(DockManager.class).get(0);
        }
        DockManager manager = new DockManager(ship);
        ship.addListener(manager);
        return manager;
    }

    private static class DockManager implements AdvanceableListener {
        private final ShipAPI ship;
        private final Map<WeaponSlotAPI, Boolean> slots = new HashMap<WeaponSlotAPI, Boolean>();
        private boolean occupyable = true;
        private boolean pressed = false;
        private final Object key = new Object();

        private DockManager(ShipAPI ship) {
            this.ship = ship;
            for (WeaponSlotAPI slot : ship.getHullSpec().getAllWeaponSlotsCopy()) {
                if (slot.getSlotSize() != WeaponAPI.WeaponSize.LARGE) {
                    continue;
                }
                if (!slot.isTurret()) {
                    continue;
                }
                if (!SLOT_TYPE.contains(slot.getWeaponType())) {
                    continue;
                }
                if (ship.getVariant().getWeaponId(slot.getId()) != null) {
                    continue;
                }
                slots.put(slot, false);
            }
        }

        public WeaponSlotAPI getIdleSlot() {
            return getIdleSlot(null);
        }

        public WeaponSlotAPI getIdleSlot(Vector2f preferredPoint) {
            if (preferredPoint == null) {
                for (WeaponSlotAPI slot : slots.keySet()) {
                    if (!slots.get(slot)) {
                        return slot;
                    }
                }
                return null;
            }

            WeaponSlotAPI bestSlot = null;
            float bestDistanceSq = Float.MAX_VALUE;
            for (WeaponSlotAPI slot : slots.keySet()) {
                if (slots.get(slot)) {
                    continue;
                }
                Vector2f slotPoint = slot.computePosition(ship);
                float distanceSq = Misc.getDistanceSq(preferredPoint, slotPoint);
                if (bestSlot == null || distanceSq < bestDistanceSq) {
                    bestSlot = slot;
                    bestDistanceSq = distanceSq;
                }
            }
            return bestSlot;
        }

        public boolean isOccupyable() {
            return occupyable;
        }

        public void occupy(WeaponSlotAPI slot) {
            if (!slots.containsKey(slot)) {
                throw new RuntimeException("Error: The slot does not exist on this ship. "
                        + slot.getId() + " " + ship.getHullSpec().getHullId());
            }
            slots.put(slot, true);
        }

        public void release(WeaponSlotAPI slot) {
            if (!slots.containsKey(slot)) {
                throw new RuntimeException("Error: The slot does not exist on this ship. "
                        + slot.getId() + " " + ship.getHullSpec().getHullId());
            }
            slots.put(slot, false);
        }

        public boolean isSlotIdle(WeaponSlotAPI slot) {
            return slot != null
                    && slots.containsKey(slot)
                    && !Boolean.TRUE.equals(slots.get(slot));
        }

        private boolean hasOccupiedSlots() {
            for (Boolean occupied : slots.values()) {
                if (Boolean.TRUE.equals(occupied)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void advance(float amount) {
            boolean player = Global.getCombatEngine().getPlayerShip() == ship;
            boolean aiControlled = ship.getShipAI() != null;
            if (!player || aiControlled) {
                // 宿主舰已经挂载机体时，若当前幅能低于阈值则禁止 AI 主动排幅，
                // 避免因为短时间低幅能 vent 导致机体被意外弹出炮塔模式。
                if (hasOccupiedSlots()
                        && ship.getFluxLevel() < HOST_VENT_BLOCK_FLUX_THRESHOLD
                        && !ship.getFluxTracker().isOverloadedOrVenting()) {
                    ship.blockCommandForOneFrame(ShipCommand.VENT_FLUX);
                }
                if (!player) {
                    return;
                }
            }

            if (!pressed && Keyboard.isKeyDown(releaseKey)) {
                pressed = true;
                occupyable = !occupyable;
            } else if (!Keyboard.isKeyDown(releaseKey)) {
                pressed = false;
            }

            for (WeaponSlotAPI slot : slots.keySet()) {
                if (slots.get(slot)) {
                    Map<String, String> replacements = Moci_TextLoader.mapOf(
                            "release_key", Keyboard.getKeyName(releaseKey)
                    );
                    Global.getCombatEngine().maintainStatusForPlayerShip(
                            key, "",
                            Moci_TextLoader.getText(TEXT_ID, "status.dock_status_title"),
                            Moci_TextLoader.getTextWithReplacements(TEXT_ID, "status.dock_release_all", replacements),
                            false);
                    break;
                }
            }
            if (!occupyable) {
                Map<String, String> replacements = Moci_TextLoader.mapOf(
                        "release_key", Keyboard.getKeyName(releaseKey)
                );
                Global.getCombatEngine().maintainStatusForPlayerShip(
                        key, "",
                        Moci_TextLoader.getText(TEXT_ID, "status.dock_status_title"),
                        Moci_TextLoader.getTextWithReplacements(TEXT_ID, "status.dock_enable_dock", replacements),
                        false);
            }
        }
    }

    private static class DockSlotIndicator {
        private SpriteEntity circleEntity = null;
        private SpriteEntity arrowEntity = null;
        private SpriteAPI circleSprite = null;
        private SpriteAPI arrowSprite = null;
        private float circleWidth = 0f;
        private float circleHeight = 0f;
        private float arrowWidth = 0f;
        private float arrowHeight = 0f;
        private float circleAngle = 0f;
        private float arrowAngle = 0f;
        private final Vector2f anchoredLocation = new Vector2f();
        private float anchoredFacing = 0f;
        private boolean hasAnchoredLocation = false;
        private boolean dismissing = false;
        private float dismissElapsed = 0f;
        private boolean missingSpriteLogged = false;

        public void advance(float amount, ShipAPI targetShip, WeaponSlotAPI slot) {
            if (dismissing) {
                advanceDismiss(amount);
                return;
            }
            if (targetShip == null || slot == null || !targetShip.isAlive()) {
                cleanup();
                return;
            }

            Vector2f slotLocation = slot.computePosition(targetShip);
            if (slotLocation == null) {
                cleanup();
                return;
            }

            ensureEntities();
            if (!isRenderable()) {
                return;
            }

            anchoredLocation.set(slotLocation);
            anchoredFacing = slot.computeMidArcAngle(targetShip);
            hasAnchoredLocation = true;

            circleAngle -= amount * SLOT_INDICATOR_CIRCLE_SPIN_RATE;
            arrowAngle -= amount * SLOT_INDICATOR_ARROW_SPIN_RATE;

            applyCircleState(1f, SLOT_INDICATOR_READY_COLOR, SLOT_INDICATOR_READY_ALPHA, anchoredLocation, anchoredFacing);
            applyArrowState(1f, SLOT_INDICATOR_READY_COLOR, SLOT_INDICATOR_READY_ALPHA, anchoredLocation, anchoredFacing);
        }

        public boolean triggerDismiss(ShipAPI targetShip, WeaponSlotAPI slot) {
            if (slot != null && targetShip != null && targetShip.isAlive()) {
                Vector2f slotLocation = slot.computePosition(targetShip);
                if (slotLocation != null) {
                    anchoredLocation.set(slotLocation);
                    anchoredFacing = slot.computeMidArcAngle(targetShip);
                    hasAnchoredLocation = true;
                }
            }
            if (!hasAnchoredLocation) {
                cleanup();
                return false;
            }

            ensureEntities();
            if (!isRenderable()) {
                return false;
            }

            dismissing = true;
            dismissElapsed = 0f;
            return true;
        }

        public void cleanup() {
            dismissing = false;
            dismissElapsed = 0f;
            hasAnchoredLocation = false;
            deleteEntity(circleEntity);
            deleteEntity(arrowEntity);
            circleEntity = null;
            arrowEntity = null;
        }

        private void advanceDismiss(float amount) {
            if (!hasAnchoredLocation) {
                cleanup();
                return;
            }

            ensureEntities();
            if (!isRenderable()) {
                dismissing = false;
                return;
            }

            dismissElapsed += amount;
            float progress = Math.min(1f, dismissElapsed / SLOT_INDICATOR_DISMISS_DURATION);

            circleAngle -= amount * SLOT_INDICATOR_CIRCLE_SPIN_RATE;
            arrowAngle -= amount * SLOT_INDICATOR_ARROW_SPIN_RATE;

            float alpha = SLOT_INDICATOR_READY_ALPHA * (1f - progress);

            applyCircleState(
                    interpolate(1f, SLOT_INDICATOR_CIRCLE_DISMISS_SCALE, progress),
                    SLOT_INDICATOR_DISMISS_COLOR,
                    alpha,
                    anchoredLocation,
                    anchoredFacing
            );
            applyArrowState(
                    interpolate(1f, SLOT_INDICATOR_ARROW_DISMISS_SCALE, progress),
                    SLOT_INDICATOR_DISMISS_COLOR,
                    alpha,
                    anchoredLocation,
                    anchoredFacing
            );

            if (progress >= 1f) {
                cleanup();
            }
        }

        private void ensureEntities() {
            if (!loadSprites()) {
                return;
            }
            if (circleEntity == null || circleEntity.hasDelete()) {
                circleEntity = createEntity(circleSprite);
            }
            if (arrowEntity == null || arrowEntity.hasDelete()) {
                arrowEntity = createEntity(arrowSprite);
            }
        }

        private boolean loadSprites() {
            if (circleSprite == null) {
                circleSprite = tryLoadSprite(SLOT_INDICATOR_CIRCLE_SPRITE_PATH);
                if (circleSprite != null) {
                    circleWidth = circleSprite.getWidth();
                    circleHeight = circleSprite.getHeight();
                }
            }
            if (arrowSprite == null) {
                arrowSprite = tryLoadSprite(SLOT_INDICATOR_ARROW_SPRITE_PATH);
                if (arrowSprite != null) {
                    arrowWidth = arrowSprite.getWidth();
                    arrowHeight = arrowSprite.getHeight();
                }
            }
            if (circleSprite == null || arrowSprite == null) {
                if (!missingSpriteLogged) {
                    missingSpriteLogged = true;
                    Global.getLogger(Moci_MechTurretAI.class).warn(
                            "Failed to load mech turret slot indicator sprites: "
                                    + SLOT_INDICATOR_CIRCLE_SPRITE_PATH + ", "
                                    + SLOT_INDICATOR_ARROW_SPRITE_PATH
                    );
                }
                return false;
            }
            return true;
        }

        private SpriteAPI tryLoadSprite(String path) {
            try {
                return Global.getSettings().getSprite(path);
            } catch (RuntimeException ex) {
                return null;
            }
        }

        private SpriteEntity createEntity(SpriteAPI sprite) {
            if (sprite == null) {
                return null;
            }

            SpriteEntity entity = new SpriteEntity(sprite);
            entity.setEmissiveSprite(sprite);
            entity.setAdditiveBlend();
            entity.setLayer(CombatEngineLayers.ABOVE_SHIPS_LAYER);
            entity.setGlobalTimer(0f, 9999f, 0f);
            entity.getMaterialData().setColorToEmissive(1f);
            entity.getMaterialData().setAlphaToEmissive(1f);
            entity.getMaterialData().setIgnoreIllumination(true);
            CombatRenderingManager.addEntity(entity);
            return entity;
        }

        private boolean isRenderable() {
            return circleEntity != null && !circleEntity.hasDelete()
                    && arrowEntity != null && !arrowEntity.hasDelete();
        }

        private void applyCircleState(float scale, Color color, float alpha, Vector2f location, float baseFacing) {
            updateEntity(circleEntity, circleWidth, circleHeight, scale, color, alpha, location, baseFacing + circleAngle);
        }

        private void applyArrowState(float scale, Color color, float alpha, Vector2f location, float baseFacing) {
            updateEntity(arrowEntity, arrowWidth, arrowHeight, scale, color, alpha, location, baseFacing + arrowAngle);
        }

        private void updateEntity(SpriteEntity entity, float width, float height, float scale,
                                  Color color, float alpha, Vector2f location, float facing) {
            if (entity == null || entity.hasDelete() || location == null) {
                return;
            }

            float clampedAlpha = Math.max(0f, alpha);
            entity.setBaseSizePerTiles(
                    width * SLOT_INDICATOR_BASE_SCALE * scale,
                    height * SLOT_INDICATOR_BASE_SCALE * scale
            );
            entity.setLocation(location.x, location.y);
            entity.getMaterialData().setColor(color);
            entity.getMaterialData().setColorAlpha(clampedAlpha);
            entity.getMaterialData().setEmissiveColor(color);
            entity.getMaterialData().setEmissiveColorAlpha(clampedAlpha);

            Matrix2f transform = new Matrix2f();
            TransformUtil.createSimpleRotateMatrix(facing, transform);
            entity.getModelMatrix().m00 = transform.m00;
            entity.getModelMatrix().m01 = transform.m01;
            entity.getModelMatrix().m10 = transform.m10;
            entity.getModelMatrix().m11 = transform.m11;
            entity.getModelMatrix().m30 = location.x;
            entity.getModelMatrix().m31 = location.y;
        }

        private void deleteEntity(SpriteEntity entity) {
            if (entity != null && !entity.hasDelete()) {
                entity.delete();
            }
        }

        private float interpolate(float start, float end, float progress) {
            return start + (end - start) * progress;
        }
    }

    public static class dockListener implements AdvanceableListener {
        private final ShipAPI ship;
        private boolean dock = false;
        private boolean docking = false;
        private ShipAPI targetShip = null;
        private WeaponSlotAPI slot = null;
        private DockManager dockManager = null;
        private final Object key = new Object();
        private final IntervalUtil interval =
                new IntervalUtil(TARGET_SEARCH_INTERVAL_MIN, TARGET_SEARCH_INTERVAL_MAX);
        private final IntervalUtil escortAssignmentInterval =
                new IntervalUtil(TARGET_SEARCH_INTERVAL_MIN, TARGET_SEARCH_INTERVAL_MAX);
        /**
         * 炮塔模式驻留期间的补弹计时器。
         *
         * 这里使用固定间隔：
         * - 到达设定秒数后，触发一次“检查机体所有武器并直接补满弹药”
         * - 不会逐秒回弹，也不会按缺口比例补一点，而是到点后直接补满
         */
        private final IntervalUtil ammoReloadInterval =
                new IntervalUtil(AMMO_RELOAD_INTERVAL_SECONDS, AMMO_RELOAD_INTERVAL_SECONDS);
        private final int type;
        private boolean pressed = false;
        private float mediumAIPursuitCooldownRemaining = 0f;
        private float mediumAINoEnemyTimer = 0f;
        private final DockSlotIndicator slotIndicator = new DockSlotIndicator();
        private boolean hideIndicatorForCommittedTurretProcess = false;
        private ShipAPI previewTargetShip = null;
        private WeaponSlotAPI previewSlot = null;
        private ShipAPI cachedEscortAssignedHost = null;

        private dockListener(ShipAPI ship) {
            this.ship = ship;
            if (ship.getVariant() != null && ship.getVariant().hasHullMod("Moci_Temp2")) {
                type = 1;
            } else {
                type = 0;
            }
        }

        @Override
        public void advance(float amount) {
            if (!shouldAssignTurretAI(ship)) {
                slotIndicator.cleanup();
                forceReleaseTarget();
                return;
            }

            if (mediumAIPursuitCooldownRemaining > 0f) {
                mediumAIPursuitCooldownRemaining = Math.max(0f, mediumAIPursuitCooldownRemaining - amount);
            }

            if (ship.getShipAI() instanceof Moci_LandingAI || isLandingAIActive(ship)) {
                slotIndicator.cleanup();
                forceReleaseTarget();
                return;
            }

            boolean player = Global.getCombatEngine().getPlayerShip() == ship;
            ShipAPI selectedTarget = ship.getShipTarget();
            boolean playerSelectedFriendly = player
                    && selectedTarget != null
                    && selectedTarget.getOwner() == ship.getOwner();
            boolean selectedTargetPrefersTurret = playerSelectedFriendly
                    && Moci_LandingSequence.shouldUseTurretModeForSelectedTarget(ship, selectedTarget);
            boolean autopilotOn = isPlayerAutopilotOn();
            ShipAPI escortAssignedHost = player ? null : refreshEscortAssignedHost(amount);
            boolean escortAssignmentActive = escortAssignedHost != null;

            if (dock) {
                clearPreviewSelection();
            } else if (player && !autopilotOn) {
                refreshPreviewSelection(selectedTarget, playerSelectedFriendly, selectedTargetPrefersTurret);
            } else if (!playerSelectedFriendly) {
                clearPreviewSelection();
            }

            boolean shouldRefit = Moci_LandingSequence.shouldTriggerRefit(ship);
            if (escortAssignmentActive && targetShip != null && targetShip != escortAssignedHost) {
                slotIndicator.cleanup();
                forceReleaseTarget();
                return;
            }
            if (targetShip != null && !dock && playerSelectedFriendly) {
                if (targetShip != selectedTarget || !selectedTargetPrefersTurret || !autopilotOn) {
                    slotIndicator.cleanup();
                    forceReleaseTarget();
                    return;
                }
            }
            if (targetShip != null && !dock && player && !playerSelectedFriendly && !autopilotOn) {
                slotIndicator.cleanup();
                forceReleaseTarget();
                return;
            }
            if (targetShip != null && !dock && shouldRefit) {
                if (!(playerSelectedFriendly && selectedTarget == targetShip && selectedTargetPrefersTurret)) {
                    slotIndicator.cleanup();
                    forceReleaseTarget();
                    return;
                }
            }
            if (dock && Moci_LandingSequence.areAllAmmoWeaponsEmpty(ship)) {
                slotIndicator.cleanup();
                forceReleaseTarget();
                return;
            }

            if (!player && !escortAssignmentActive && shouldReleaseForLowAIRefitOpportunity()) {
                forceReleaseTarget();
                return;
            }

            if (!player && !escortAssignmentActive && shouldReleaseForMediumAIPursuit(amount, shouldRefit)) {
                releaseForAIPursuit();
                return;
            }

            if (player) {
                if (!pressed && Keyboard.isKeyDown(releaseKey)) {
                    pressed = true;
                    if (targetShip != null) {
                        forceReleaseTarget();
                    }
                } else if (!Keyboard.isKeyDown(releaseKey)) {
                    pressed = false;
                }
                if (dock) {
                    Map<String, String> replacements = Moci_TextLoader.mapOf(
                            "release_key", Keyboard.getKeyName(releaseKey),
                            "range_bonus", String.valueOf((int) getRangeBonusForHostHullSize(targetShip.getHullSize()))
                    );
                    Global.getCombatEngine().maintainStatusForPlayerShip(
                            key,
                            Global.getSettings().getSpriteName("ui", "icon_tactical_bdeck"),
                            Moci_TextLoader.getTextWithReplacements(TEXT_ID, "status.dock_detach_title", replacements),
                            Moci_TextLoader.getTextWithReplacements(TEXT_ID, "status.dock_range_bonus", replacements),
                            false);
                }
            }

            if (targetShip != null) {
                if (!targetShip.isAlive()
                        || !Global.getCombatEngine().isEntityInPlay(targetShip)
                        || targetShip.isPhased()
                        || !dockManager.occupyable
                        || targetShip.getFluxTracker().isOverloadedOrVenting()) {
                    forceReleaseTarget();
                }
            }

            if (targetShip != null) {
                if (dock) {
                    ship.blockCommandForOneFrame(ShipCommand.ACCELERATE);
                    ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);
                    ship.blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT);
                    ship.blockCommandForOneFrame(ShipCommand.STRAFE_LEFT);

                    for (ShipEngineControllerAPI.ShipEngineAPI eng : ship.getEngineController().getShipEngines()) {
                        ship.getEngineController().setFlameLevel(eng.getEngineSlot(), 0f);
                    }
                    ship.getLocation().set(computeLoc());

                    float fluxDisp = ship.getHullSpec().getFluxDissipation() * FLUX_TRANSFER_MULT * amount;
                    float current = Math.min(fluxDisp, ship.getFluxTracker().getCurrFlux());
                    if (current > 0f) {
                        ship.getFluxTracker().decreaseFlux(current);
                        targetShip.getFluxTracker().increaseFlux(current, false);
                    }

                    // 只有当“宿主炮塔槽”的类型属于允许补弹的几类时，机体才会定期补弹。
                    // 注意这里看的不是机体武器类型，而是宿主给它提供的停靠槽位类型。
                    if (ENABLE_TURRET_AMMO_RELOAD
                            && slot != null
                            && RELOAD_SLOT_TYPE.contains(slot.getWeaponType())) {
                        ammoReloadInterval.advance(amount);
                        if (ammoReloadInterval.intervalElapsed()) {
                            // 预留：未来若重新启用炮塔驻留补弹，可以恢复这一段逻辑。
                            for (WeaponAPI weapon : ship.getAllWeapons()) {
                                if (weapon.getAmmo() < weapon.getMaxAmmo()) {
                                    weapon.setAmmo(weapon.getMaxAmmo());
                                }
                            }
                        }
                    }

                    targetShip.setTimeDeployed(amount * 0.25f + targetShip.getTimeDeployedForCRReduction());
                }
            } else {
                dock = false;
                if (!shouldRefit && shouldFindNewTarget()) {
                    findNewTarget(amount);
                }
            }

            updatePlayerTurretCommitAnimationState(player);
            updateSlotIndicator(amount, player, selectedTarget, playerSelectedFriendly, selectedTargetPrefersTurret);
        }

        public boolean hasTarget() {
            return targetShip != null;
        }

        public boolean isDocked() {
            return dock;
        }

        public boolean isDocking() {
            return docking;
        }

        public void setDocking(boolean docking) {
            this.docking = docking;
        }

        public ShipAPI getTargetShip() {
            return targetShip;
        }

        public void forceReleaseTarget() {
            if (targetShip != null && dockManager != null && slot != null) {
                dockManager.release(slot);
            }
            slot = null;
            targetShip = null;
            dockManager = null;
            unapplyBuff();
            docking = false;
            dock = false;
            mediumAINoEnemyTimer = 0f;
            hideIndicatorForCommittedTurretProcess = false;
            clearPreviewSelection();
        }

        public void notifyPlayerTurretAutopilotEngaged() {
            if (targetShip == null || slot == null) {
                return;
            }
            hideIndicatorForCommittedTurretProcess = slotIndicator.triggerDismiss(targetShip, slot);
        }

        private void updatePlayerTurretCommitAnimationState(boolean player) {
            if (!player || !isPlayerAutopilotOn()) {
                hideIndicatorForCommittedTurretProcess = false;
                return;
            }
            if (dock) {
                return;
            }
            if (targetShip != null && slot != null && !hideIndicatorForCommittedTurretProcess) {
                notifyPlayerTurretAutopilotEngaged();
            }
        }

        private void updateSlotIndicator(float amount, boolean player, ShipAPI selectedTarget,
                                         boolean playerSelectedFriendly, boolean selectedTargetPrefersTurret) {
            if (!player) {
                slotIndicator.cleanup();
                return;
            }

            if (dock) {
                slotIndicator.cleanup();
                return;
            }

            if (hideIndicatorForCommittedTurretProcess) {
                slotIndicator.advance(amount, null, null);
                return;
            }

            if (!dock && !playerSelectedFriendly && !isPlayerAutopilotOn()) {
                slotIndicator.advance(amount, null, null);
                return;
            }

            if (targetShip != null && slot != null) {
                slotIndicator.advance(amount, targetShip, slot);
                return;
            }

            if (playerSelectedFriendly && selectedTargetPrefersTurret && selectedTarget == previewTargetShip) {
                slotIndicator.advance(amount, previewTargetShip, previewSlot);
                return;
            }

            slotIndicator.advance(amount, null, null);
        }

        private boolean isPlayerAutopilotOn() {
            return Global.getCombatEngine() != null
                    && Global.getCombatEngine().getCombatUI() != null
                    && Global.getCombatEngine().getCombatUI().isAutopilotOn();
        }

        private void findNewTarget(float amount) {
            interval.advance(amount);
            if (!interval.intervalElapsed()) {
                return;
            }

            ShipAPI selectedTarget = ship.getShipTarget();
            boolean playerSelectedFriendly = Global.getCombatEngine().getPlayerShip() == ship
                    && selectedTarget != null
                    && selectedTarget.getOwner() == ship.getOwner();
            if (playerSelectedFriendly) {
                if (!isPlayerAutopilotOn()) {
                    return;
                }
                if (Moci_LandingSequence.shouldUseTurretModeForSelectedTarget(ship, selectedTarget)) {
                    check(selectedTarget, selectedTarget == previewTargetShip ? previewSlot : null);
                }
                return;
            }

            ShipAPI escortAssignedHost = cachedEscortAssignedHost;
            if (escortAssignedHost != null) {
                check(escortAssignedHost);
                return;
            }

            if (isManualTurretAIMode(ship)) {
                return;
            }

            if (Moci_LandingSequence.findAutomaticRefitTarget(ship) != null) {
                return;
            }

            if (isLowTurretAIMode(ship) && !shouldUseLowAIFallbackTurretMode(ship)) {
                return;
            }

            if (isMediumTurretAIMode(ship)) {
                if (mediumAIPursuitCooldownRemaining > 0f) {
                    return;
                }
                if (findPreferredEnemy(ship, MEDIUM_AI_ENGAGEMENT_RANGE) == null) {
                    return;
                }
                ShipAPI nearbyHost = findNearbyDockableHost(ship, MEDIUM_AI_HOST_SEARCH_RANGE);
                if (nearbyHost != null) {
                    check(nearbyHost);
                }
                return;
            }

            int owner = ship.getOwner();
            if (owner != 0 && owner != 1) {
                return;
            }

            ShipAPI nearbyHost = findNearestDockableHost(ship);
            if (nearbyHost != null) {
                check(nearbyHost);
                return;
            }
        }

        private boolean check(ShipAPI ally) {
            return check(ally, null);
        }

        private boolean check(ShipAPI ally, WeaponSlotAPI preferredSlot) {
            if (!canUseAsTurretHost(ship, ally)) {
                return false;
            }

            DockManager manager = getDockManager(ally);
            WeaponSlotAPI idleSlot = manager.isSlotIdle(preferredSlot)
                    ? preferredSlot
                    : manager.getIdleSlot(getPreferredDockPoint(ship));
            if (idleSlot == null) {
                return false;
            }

            dockManager = manager;
            manager.occupy(idleSlot);
            slot = idleSlot;
            targetShip = ally;
            clearPreviewSelection();
            return true;
        }

        private void refreshPreviewSelection(ShipAPI selectedTarget, boolean playerSelectedFriendly,
                                             boolean selectedTargetPrefersTurret) {
            if (!playerSelectedFriendly || !selectedTargetPrefersTurret || !canUseAsTurretHost(ship, selectedTarget)) {
                clearPreviewSelection();
                return;
            }

            DockManager selectedTargetManager = getDockManager(selectedTarget);
            previewTargetShip = selectedTarget;
            previewSlot = selectedTargetManager.getIdleSlot(getPreferredDockPoint(ship));
        }

        private void clearPreviewSelection() {
            previewTargetShip = null;
            previewSlot = null;
        }

        private ShipAPI refreshEscortAssignedHost(float amount) {
            escortAssignmentInterval.advance(amount);
            if (escortAssignmentInterval.intervalElapsed()) {
                cachedEscortAssignedHost = getEscortAssignedHost(ship);
            }
            return cachedEscortAssignedHost;
        }

        public void dock() {
            // 标记“已经完成停靠”。
            // 之后 DockListener 会开始持续锁定位置、传递幅能、补弹等驻留逻辑。
            dock = true;

            // 给机体打上停靠标签，供其他系统或调试时识别当前是否处于炮塔模式。
            ship.addTag(DOCK_TAG);

            MutableShipStatsAPI stats = ship.getMutableStats();

            // 根据宿主舰体型，从上面的 RANGE_BONUS 配置表中取出射程加成。
            // 例如：
            // - 宿主是 DESTROYER -> 取 20f -> 实弹/能量射程 +20%
            // - 宿主是 CRUISER   -> 取 40f -> 实弹/能量射程 +40%
            // - 宿主是 CAPITAL   -> 取 60f -> 实弹/能量射程 +60%
            Float bonus = RANGE_BONUS.get(targetShip.getHullSize());
            if (bonus == null) {
                // 理论上不会进入这里；只是为了防止未来出现未配置的 HullSize 时直接 NPE。
                bonus = 0f;
            }

            // 这里用的是 modifyPercent，所以 bonus 的含义就是“百分比增减”。
            // 只加成机体自身的：
            // - 实弹武器基础射程
            // - 能量武器基础射程
            // 不影响导弹武器，也不直接改武器伤害/射速。
            stats.getBallisticWeaponRangeBonus().modifyPercent(BUFF_ID, bonus);
            stats.getEnergyWeaponRangeBonus().modifyPercent(BUFF_ID, bonus);
        }

        private void unapplyBuff() {
            // 脱离停靠时移除标签和射程加成。
            // 注意这里按 BUFF_ID 精确 unmodify，确保不会误删别的系统提供的射程修正。
            ship.removeTag(DOCK_TAG);
            MutableShipStatsAPI stats = ship.getMutableStats();
            stats.getBallisticWeaponRangeBonus().unmodify(BUFF_ID);
            stats.getEnergyWeaponRangeBonus().unmodify(BUFF_ID);
        }

        public Vector2f computeLoc() {
            if (slot == null || targetShip == null) {
                return null;
            }
            return slot.computePosition(targetShip);
        }

        private boolean shouldFindNewTarget() {
            if (type == 1) {
                return ship.getHullLevel() < 0.5f;
            }
            return true;
        }

        private boolean shouldReleaseForMediumAIPursuit(float amount, boolean shouldRefit) {
            if (!dock || shouldRefit || !isMediumTurretAIMode(ship)) {
                mediumAINoEnemyTimer = 0f;
                return false;
            }

            ShipAPI nearbyEnemy = findPreferredEnemy(ship, MEDIUM_AI_NO_ENEMY_RELEASE_RANGE);
            if (nearbyEnemy == null) {
                mediumAINoEnemyTimer += amount;
                return mediumAINoEnemyTimer >= MEDIUM_AI_NO_ENEMY_RELEASE_DELAY;
            }

            mediumAINoEnemyTimer = 0f;
            return isWeakPursuitTarget(nearbyEnemy);
        }

        private void releaseForAIPursuit() {
            mediumAINoEnemyTimer = 0f;
            mediumAIPursuitCooldownRemaining = MEDIUM_AI_REDOCK_DELAY_AFTER_RELEASE;
            forceReleaseTarget();
        }

        private boolean shouldReleaseForLowAIRefitOpportunity() {
            if (!dock || !isLowTurretAIMode(ship) || !shouldUseLowAIFallbackTurretMode(ship)) {
                return false;
            }
            return Moci_LandingSequence.searchForLand(ship) != null;
        }
    }
}
