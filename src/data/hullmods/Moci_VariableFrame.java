package data.hullmods;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import data.scripts.util.Moci_TextLoader;
import data.scripts.weapons.Moci_MechTransformController;
import data.scripts.weapons.render.Moci_EngineRender;

public class Moci_VariableFrame extends BaseHullMod {
    public static final String HULLMOD_ID = "Moci_VariableFrame";
    private static final String TEXT_ID = HULLMOD_ID;

    private static final String BACKPACK_SLOT = "BACKPACK";
    private static final String RENDERERS_CREATED_KEY = "MOCI_VARIABLE_FRAME_ENGINE_RENDERERS_CREATED";
    private static final String WR_RENDERERS_KEY = "MOCI_VARIABLE_FRAME_WR_ENGINE_RENDERERS";

    private static final Color DEFAULT_ENGINE_COLOR = new Color(43, 149, 255, 210);
    private static final int MSZ_006_WR_BACKPACK_FRAME = 11;
    private static final float WR_LENGTH_MULT = 2.4f;
    private static final float WR_WIDTH_MULT = 5f;
    private static final float WR_GLOW_SIZE_MULT = 1.4f;

    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        if (ship == null || ship.getEngineController() == null || !ship.isAlive() || ship.isHulk()) {
            return;
        }

        if (ship.getCustomData().get(RENDERERS_CREATED_KEY) == null) {
            initializeEngineRenderers(ship);
            ship.setCustomData(RENDERERS_CREATED_KEY, Boolean.TRUE);
        }

        boolean waveRider = isWaveRiderMode(ship);
        setWaveRiderRenderersActive(ship, waveRider);
    }

    private void initializeEngineRenderers(ShipAPI ship) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return;
        }

        ShipEngineControllerAPI.ShipEngineAPI templateEngine = getTemplateEngine(ship);
        if (templateEngine == null) {
            return;
        }

        WeaponAPI backpack = getWeaponInSlot(ship, BACKPACK_SLOT);
        if (backpack == null) {
            return;
        }

        SpriteAPI glowSprite = Global.getSettings().getSprite("fx", "engineglow32b");
        SpriteAPI flameSprite = Global.getSettings().getSprite("fx", "MBSglowOutline");
        SpriteAPI circleSprite = Global.getSettings().getSprite("fx", "MBSEngineglowCricleNothing");
        Color engineColor = getEngineColor(templateEngine);

        List<Moci_EngineRender> wrRenderers = new ArrayList<Moci_EngineRender>();
        int firePointCount = getBackpackFirePointCount(backpack);
        for (int i = 0; i < firePointCount; i++) {
            Moci_EngineRender renderer = new Moci_EngineRender(
                    ship,
                    templateEngine,
                    backpack,
                    glowSprite,
                    flameSprite,
                    circleSprite,
                    CombatEngineLayers.ABOVE_SHIPS_LAYER,
                    CombatEngineLayers.ABOVE_SHIPS_LAYER,
                    true);
            renderer.setFirePointIndex(i);
            renderer.setColorOverride(engineColor);
            renderer.setLengthMult(WR_LENGTH_MULT);
            renderer.setWidthMult(WR_WIDTH_MULT);
            renderer.setGlowSizeMult(WR_GLOW_SIZE_MULT);
            Float firePointFacing = getConfiguredFirePointFacing(backpack, i);
            if (firePointFacing != null) {
                renderer.setFixedFacing(firePointFacing);
            }
            renderer.setActive(false);
            engine.addLayeredRenderingPlugin(renderer);
            wrRenderers.add(renderer);
        }

        ship.setCustomData(WR_RENDERERS_KEY, wrRenderers);
    }

    private boolean isWaveRiderMode(ShipAPI ship) {
        Object state = ship.getCustomData().get(Moci_MechTransformController.CUSTOM_TRANSFORM_STATE);
        if (!(state instanceof String) || !"TRANSFORMED".equals(state)) {
            return false;
        }

        WeaponAPI backpack = getWeaponInSlot(ship, BACKPACK_SLOT);
        if (backpack == null || backpack.getAnimation() == null) {
            return false;
        }
        return backpack.getAnimation().getFrame() == getWaveRiderBackpackFrame(ship, backpack);
    }

    private int getWaveRiderBackpackFrame(ShipAPI ship, WeaponAPI backpack) {
        if (ship != null && ship.getHullSpec() != null
                && "Moci_MSZ_010".equals(ship.getHullSpec().getHullId())) {
            return Math.max(0, backpack.getAnimation().getNumFrames() - 1);
        }
        return Math.min(MSZ_006_WR_BACKPACK_FRAME, Math.max(0, backpack.getAnimation().getNumFrames() - 1));
    }

    @SuppressWarnings("unchecked")
    private void setWaveRiderRenderersActive(ShipAPI ship, boolean active) {
        Object renderers = ship.getCustomData().get(WR_RENDERERS_KEY);
        if (!(renderers instanceof List)) {
            return;
        }
        for (Moci_EngineRender renderer : (List<Moci_EngineRender>) renderers) {
            if (renderer != null) {
                renderer.setActive(active);
            }
        }
    }

    private ShipEngineControllerAPI.ShipEngineAPI getTemplateEngine(ShipAPI ship) {
        for (ShipEngineControllerAPI.ShipEngineAPI engine : ship.getEngineController().getShipEngines()) {
            if (engine != null) {
                return engine;
            }
        }
        return null;
    }

    private WeaponAPI getWeaponInSlot(ShipAPI ship, String slotId) {
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon != null && weapon.getSlot() != null && slotId.equals(weapon.getSlot().getId())) {
                return weapon;
            }
        }
        return null;
    }

    private int getBackpackFirePointCount(WeaponAPI backpack) {
        List<Vector2f> firePoints = backpack.getSpec().getTurretFireOffsets();
        if (firePoints == null || firePoints.isEmpty()) {
            return 1;
        }
        return firePoints.size();
    }

    private Float getConfiguredFirePointFacing(WeaponAPI backpack, int index) {
        List<Float> angles = backpack.getSpec().getTurretAngleOffsets();
        if (angles == null || index >= angles.size()) {
            return null;
        }

        Float angle = angles.get(index);
        if (angle == null || Math.abs(angle) < 0.001f) {
            return null;
        }
        return angle;
    }

    private Color getEngineColor(ShipEngineControllerAPI.ShipEngineAPI engine) {
        if (engine != null && engine.getEngineColor() != null) {
            return new Color(
                    engine.getEngineColor().getRed(),
                    engine.getEngineColor().getGreen(),
                    engine.getEngineColor().getBlue(),
                    DEFAULT_ENGINE_COLOR.getAlpha());
        }
        return DEFAULT_ENGINE_COLOR;
    }

    @Override
    public boolean shouldAddDescriptionToTooltip(ShipAPI.HullSize hullSize, ShipAPI ship, boolean isForModSpec) {
        return false;
    }

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, ShipAPI.HullSize hullSize, ShipAPI ship,
            float width, boolean isForModSpec) {
        Color h = Misc.getHighlightColor();
        Color good = Misc.getPositiveHighlightColor();
        float pad = 3f;

        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.transform_intro"),
                pad, h,
                Moci_TextLoader.getHighlights(TEXT_ID, "description.transform_intro_highlights").toArray(new String[0]));
        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.form_effects"),
                pad, new Color[] { good, good },
                Moci_TextLoader.getHighlights(TEXT_ID, "description.form_effects_highlights").toArray(new String[0]));
        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.animation_rules"),
                pad, h,
                Moci_TextLoader.getHighlights(TEXT_ID, "description.animation_rules_highlights").toArray(new String[0]));
    }
}
