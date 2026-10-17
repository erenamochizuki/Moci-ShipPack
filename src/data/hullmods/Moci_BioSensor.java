package data.hullmods;

import java.awt.Color;

/**
 * 生物传感器：半强度塞克谬，无粒子，默认紫色辉光。
 */
public class Moci_BioSensor extends Moci_PsycommuSystem {
    private static final String TEXT_ID = "Moci_BioSensor";
    private static final String HULLMOD_ID = "Moci_BioSensor";
    private static final String COLLISION_MODIFIER_ID = "Moci_BioSensor";
    private static final String VISUAL_MOD_ID = "Moci_BioSensor_Halo";
    private static final Color BIO_HALO_COLOR = new Color(244, 99, 226, 255);

    @Override
    protected String getTextId() {
        return TEXT_ID;
    }

    @Override
    protected String getHullModId() {
        return HULLMOD_ID;
    }

    @Override
    protected String getCollisionModifierId() {
        return COLLISION_MODIFIER_ID;
    }

    @Override
    protected String getVisualModId() {
        return VISUAL_MOD_ID;
    }

    @Override
    protected float getEffectMult() {
        return 0.5f;
    }

    @Override
    protected boolean shouldSpawnParticles() {
        return false;
    }

    @Override
    protected Color getBaseHaloColor() {
        return BIO_HALO_COLOR;
    }

    @Override
    protected Color getCapitalEngineBaseColor() {
        return BIO_HALO_COLOR;
    }

    @Override
    protected Color getHeadingTextColor() {
        return new Color(211, 150, 255, 255);
    }

    @Override
    protected Color getHeadingBackgroundColor() {
        return new Color(156, 70, 255, 50);
    }
}
