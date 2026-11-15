package data.scripts;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import data.hullmods.Moci_GNShield_Script;
import data.hullmods.Moci_GNFieldDefense_Script;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.apache.log4j.Logger;
import org.moci.filmshield.api.FilmShield;
import org.moci.filmshield.core.FilmShieldController;

/** Read-only combat diagnostics, including hulls whose GN hullmod never ran. */
public final class Moci_GNShieldDiagnostics extends BaseEveryFrameCombatPlugin {
    private static final Logger LOG = Global.getLogger(Moci_GNShieldDiagnostics.class);
    private static final String PREFIX = "[Moci_GNShieldDiag] ";
    private static final String HULLMOD = "Moci_GNShield";
    private static final String FIELD = "Moci_GNFieldDefenseSystem";
    private static final String CREATION_KEY = "moci.gnshield.diagnostics.creation.v1";
    private static final int MISSING_CALLBACK = 1, MISSING_CONTROLLER = 2, MISSING_LISTENER = 4,
            SHIELD_REPLACED = 8, SHIELD_VISIBLE = 16, ARC_CHANGED = 32, CONTROLLER_CHANGED = 64,
            VISUAL_CHANGED = 128, MISSING_VISUAL = 256, MISSING_RENDERER = 512, MISSING_UI = 1024;
    private final Map<ShipAPI, State> states = new IdentityHashMap<>();
    private CombatEngineAPI engine;
    private float elapsed;

    private static final class Creation {
        final ShieldAPI shield;
        final FilmShieldController controller;
        final Object fieldListener;
        final org.moci.filmshield.api.ShieldVisual visual;
        final String outcome;
        Creation(ShipAPI ship) {
            shield = ship.getShield();
            controller = FilmShield.find(ship);
            fieldListener = fieldListener(ship);
            visual = FilmShield.visual(ship);
            String selected = FilmShield.getSelectedHullmodId(ship);
            outcome = HULLMOD.equals(selected) && controller instanceof Moci_GNShield_Script ? "attached"
                    : FIELD.equals(selected) && fieldListener != null && visual != null ? "attached"
                    : FilmShield.isDisabled(ship) ? "disabled"
                    : !HULLMOD.equals(selected) && !FIELD.equals(selected) ? "not_selected"
                    : FIELD.equals(selected) ? fieldListener == null ? "field_listener_missing" : "visual_missing"
                    : controller != null ? "foreign_controller"
                    : shield == null ? "no_native_shield" : "unclaimed";
        }
    }

    private static final class State {
        final Creation creation;
        final ShieldAPI firstShield;
        final FilmShieldController firstController;
        final Object firstFieldListener;
        final org.moci.filmshield.api.ShieldVisual firstVisual;
        int reported;
        boolean registrationReported;
        boolean servicesReported;
        final float deployedAt;
        State(ShipAPI ship) {
            deployedAt = Global.getCombatEngine().getTotalElapsedTime(false);
            Object saved = ship.getCustomData().get(CREATION_KEY);
            creation = saved instanceof Creation ? (Creation) saved : null;
            firstShield = creation == null ? ship.getShield() : creation.shield;
            firstController = creation == null ? FilmShield.find(ship) : creation.controller;
            firstFieldListener = creation == null ? fieldListener(ship) : creation.fieldListener;
            firstVisual = creation == null ? FilmShield.visual(ship) : creation.visual;
        }
    }

    /** Save the post-hullmod baseline without logging refit/preview ship creation. */
    public static void recordCreation(ShipAPI ship) {
        if (!ship.getCustomData().containsKey(CREATION_KEY))
            ship.setCustomData(CREATION_KEY, new Creation(ship));
    }

    @Override public void init(CombatEngineAPI engine) {
        this.engine = engine;
        states.clear();
        elapsed = 0.5f; // Inspect the first live combat frame, then twice per second.
        LOG.info(PREFIX + "combat monitor ready; schema=2, moci=" + version("moci_shippack")
                + ", filmshield=" + version(FilmShield.MOD_ID));
    }

    @Override public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null || engine.isPaused()) return;
        elapsed += amount;
        if (elapsed < 0.5f) return;
        elapsed = 0f;
        states.keySet().removeIf(ship -> !ship.isAlive() || ship.isHulk() || !engine.isEntityInPlay(ship));
        for (ShipAPI ship : engine.getShips()) {
            if (!ship.isAlive() || ship.isHulk() || !engine.isEntityInPlay(ship) || !relevant(ship)) continue;
            inspect(ship);
        }
    }

    private static boolean relevant(ShipAPI ship) {
        return ship.getHullSpec() != null && ("Moci_PendingBill".equals(ship.getHullSpec().getBaseHullId())
                || "Moci_Yamato".equals(ship.getHullSpec().getBaseHullId())
                || ship.getHullSpec().getBuiltInMods().contains(HULLMOD)
                || ship.getHullSpec().getBuiltInMods().contains(FIELD)
                || ship.getVariant() != null && (ship.getVariant().hasHullMod(HULLMOD) || ship.getVariant().hasHullMod(FIELD)));
    }

    private void inspect(ShipAPI ship) {
        State state = states.get(ship);
        if (state == null) {
            state = new State(ship);
            states.put(ship, state);
            LOG.info(PREFIX + "deployed " + snapshot(ship, state));
        }
        FilmShieldController controller = FilmShield.find(ship);
        ShieldAPI shield = ship.getShield();
        boolean field = FIELD.equals(FilmShield.getSelectedHullmodId(ship));
        Object listener = field ? fieldListener(ship) : controller;
        boolean registered = (field ? listener != null : controller instanceof Moci_GNShield_Script)
                && FilmShield.visual(ship) != null
                && (field || ship.getListeners(FilmShieldController.class).contains(controller));
        if (!state.registrationReported) {
            state.registrationReported = true;
            String result = registered ? "success" : FilmShield.isDisabled(ship) ? "disabled"
                    : !HULLMOD.equals(FilmShield.getSelectedHullmodId(ship)) && !field ? "not_selected" : "failed";
            String message = PREFIX + "registration result=" + result + " " + snapshot(ship, state);
            if ("failed".equals(result)) LOG.warn(message); else LOG.info(message);
        }
        boolean rendererPresent = engine.getCustomData().get(org.moci.filmshield.render.FilmShieldRenderer.RENDER_KEY)
                instanceof org.moci.filmshield.render.FilmShieldRenderer;
        boolean uiPresent = engine.getCustomData().containsKey(field ? "Moci_GNFieldDefenseUI" : "moci_filmshield.ui.v1");
        if (registered && rendererPresent && uiPresent && !state.servicesReported) {
            state.servicesReported = true;
            LOG.info(PREFIX + "services_ready " + snapshot(ship, state));
        } else if (registered && engine.getTotalElapsedTime(false) - state.deployedAt >= 2f) {
            if (!rendererPresent) warn(ship, state, MISSING_RENDERER, "renderer_registration_missing");
            if (!uiPresent) warn(ship, state, MISSING_UI, "ui_registration_missing");
        }
        if (state.creation == null) warn(ship, state, MISSING_CALLBACK, "creation_callback_not_completed");
        if (field && listener == null) warn(ship, state, MISSING_LISTENER, "field_listener_missing");
        if (field && listener != state.firstFieldListener) warn(ship, state, CONTROLLER_CHANGED, "field_listener_changed");
        if (field && FilmShield.visual(ship) == null) warn(ship, state, MISSING_VISUAL, "field_visual_missing");
        if (field && FilmShield.visual(ship) != state.firstVisual) warn(ship, state, VISUAL_CHANGED, "visual_owner_changed");
        if (!field && controller == null) warn(ship, state, MISSING_CONTROLLER, "controller_missing");
        if (!field && controller != state.firstController) warn(ship, state, CONTROLLER_CHANGED, "controller_changed");
        if (controller != null && !ship.getListeners(FilmShieldController.class).contains(controller))
            warn(ship, state, MISSING_LISTENER, "controller_listener_missing");
        if (shield != state.firstShield) warn(ship, state, SHIELD_REPLACED, "native_shield_replaced");
        if ((field || controller instanceof Moci_GNShield_Script) && shield != null) {
            if (!field && FilmShield.visual(ship) != controller) warn(ship, state, VISUAL_CHANGED, "visual_owner_changed");
            if (!shield.isSkipRendering()) warn(ship, state, SHIELD_VISIBLE, "native_shield_rendering_enabled");
            if (controller != null && controller.getProfile().overrideNativeShieldArc
                    && Math.abs(shield.getArc() - controller.getProfile().nativeShieldArc) > 0.001f)
                warn(ship, state, ARC_CHANGED, "native_shield_arc_changed");
        }
    }

    private void warn(ShipAPI ship, State state, int flag, String event) {
        if ((state.reported & flag) != 0) return;
        state.reported |= flag;
        LOG.warn(PREFIX + event + " " + snapshot(ship, state));
    }

    private static String snapshot(ShipAPI ship, State state) {
        ShipVariantAPI variant = ship.getVariant();
        FilmShieldController controller = FilmShield.find(ship);
        ShieldAPI shield = ship.getShield();
        String provider = FIELD.equals(FilmShield.getSelectedHullmodId(ship)) ? FIELD : HULLMOD;
        com.fs.starfarer.api.loading.HullModSpecAPI gnSpec = Global.getSettings().getHullModSpec(provider);
        Object gnEffect = gnSpec == null ? null : gnSpec.getEffect();
        Object renderer = Global.getCombatEngine().getCustomData().get(org.moci.filmshield.render.FilmShieldRenderer.RENDER_KEY);
        Object fieldListener = fieldListener(ship);
        Moci_GNFieldDefense_Script fieldScript = fieldListener instanceof Moci_GNFieldDefense_Script
                ? (Moci_GNFieldDefense_Script) fieldListener : null;
        return "t=" + Global.getCombatEngine().getTotalElapsedTime(false)
                + ", engine=0x" + Integer.toHexString(System.identityHashCode(Global.getCombatEngine()))
                + ", hull=" + ship.getHullSpec().getHullId() + ", ship=" + ship.getId()
                + ", member=" + ship.getFleetMemberId() + ", owner=" + ship.getOwner()
                + ", variant=" + (variant == null ? null : variant.getHullVariantId())
                + ", core=" + (ship.getCaptain() == null ? null : ship.getCaptain().getAICoreId())
                + ", ai=" + type(ship.getShipAI())
                + ", creation=" + (state.creation == null ? "not_observed" : state.creation.outcome)
                + ", disabled=" + FilmShield.isDisabled(ship) + ", selected=" + FilmShield.getSelectedHullmodId(ship)
                + ", gnEffect=" + type(gnEffect)
                + ", gnProvider=" + (gnEffect instanceof org.moci.filmshield.api.FilmShieldProvider)
                + ", controller=" + identity(controller) + ", visual=" + identity(FilmShield.visual(ship))
                + ", fieldListener=" + identity(fieldListener)
                + ", fieldCurrent=" + (fieldScript == null ? null : fieldScript.getShield().getCurrent())
                + ", fieldCapacity=" + (fieldScript == null ? null : fieldScript.getShield().getShieldCap())
                + ", fieldBroken=" + (fieldScript == null ? null : fieldScript.getShield().isShieldBroken())
                + ", rendererRegistered=" + Global.getCombatEngine().getCustomData().containsKey(org.moci.filmshield.render.FilmShieldRenderer.RENDER_KEY)
                + ", shaderInitialized=" + (renderer instanceof org.moci.filmshield.render.FilmShieldRenderer
                        && ((org.moci.filmshield.render.FilmShieldRenderer) renderer).isShaderInitialized())
                + ", uiRegistered=" + Global.getCombatEngine().getCustomData().containsKey(FIELD.equals(provider) ? "Moci_GNFieldDefenseUI" : "moci_filmshield.ui.v1")
                + ", visualActive=" + (FilmShield.visual(ship) != null && FilmShield.visual(ship).isActive())
                + ", listener=" + (controller != null && ship.getListeners(FilmShieldController.class).contains(controller))
                + ", active=" + (controller != null && controller.isActive())
                + ", shield=" + identity(shield) + ", creationShield=" + identity(state.firstShield)
                + ", arc=" + (shield == null ? null : shield.getArc())
                + ", skipRendering=" + (shield == null ? null : shield.isSkipRendering())
                + ", shieldOn=" + (shield != null && shield.isOn())
                + ", overloadedOrVenting=" + ship.getFluxTracker().isOverloadedOrVenting()
                + ", builtIns=" + ship.getHullSpec().getBuiltInMods()
                + ", hullmods=" + (variant == null ? null : variant.getHullMods())
                + ", suppressed=" + (variant == null ? null : variant.getSuppressedMods());
    }

    private static String identity(Object value) {
        return value == null ? "null" : type(value) + "@" + Integer.toHexString(System.identityHashCode(value));
    }
    private static Object fieldListener(ShipAPI ship) {
        List<Moci_GNFieldDefense_Script> listeners = ship.getListeners(Moci_GNFieldDefense_Script.class);
        return listeners.isEmpty() ? null : listeners.get(0);
    }
    private static String type(Object value) { return value == null ? "null" : value.getClass().getName(); }
    private static String version(String id) {
        com.fs.starfarer.api.ModSpecAPI spec = Global.getSettings().getModManager().getModSpec(id);
        return spec == null ? "unknown" : spec.getVersion();
    }
}
