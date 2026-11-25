package data.shipsystems.scripts.travel;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.graphics.SpriteAPI;
import org.boxutil.base.BaseControlData;
import org.boxutil.base.api.RenderDataAPI;
import org.boxutil.config.BoxConfigs;
import org.boxutil.define.BoxEnum;
import org.boxutil.manager.CombatRenderingManager;
import org.boxutil.units.standard.entity.DistortionEntity;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicRender;
import java.awt.Color;
import static data.shipsystems.scripts.travel.Moci_NebulaGateMotion.*;

final class Moci_TransitFold {
    static final float OUTER_RADIUS = 1.18f;
    private static final float INWARD_POWER = -.36f, RING_OPACITY = .22f;
    private static final Vector2f STILL = new Vector2f();
    private DistortionEntity refraction;
    private SpriteAPI ring;
    private boolean finished;

    private static final class FoldLifetime extends BaseControlData {
        private static final FoldLifetime INSTANCE = new FoldLifetime();
        @Override public boolean controlRemoveBasedTimer(RenderDataAPI entity) { return false; }
        @Override public boolean controlIsOnceRender(RenderDataAPI entity) {
            return entity.isGlobalTimerOver() || entity.isGlobalTimerOnce();
        }
    }

    void advance(float battleTime,boolean visible,Vector2f center,float radius,Color tint) {
        if (finished) return;
        if (!visible || battleTime>=FOLD_END) { clear(); return; }
        float extent=radius*(OUTER_RADIUS-.48f*ease(battleTime/FOLD_END));
        float strength=fold(battleTime);
        boolean firstFrame=refraction==null && ring==null;
        if (firstFrame) {
            var settings=Global.getSettings();
            var mods=settings.getModManager();
            if (mods.isModEnabled("BoxUtil") && BoxConfigs.isShaderEnable() && BoxConfigs.isDistortionEnable()
                    && (BoxConfigs.isMultiPassBeauty() || BoxConfigs.isMultiPassColor())) {
                refraction=new DistortionEntity();
                refraction.setControlData(FoldLifetime.INSTANCE);
                refraction.setLocation(center);
                refraction.setGlobalTimer(0f,FOLD_END,0f);
                refraction.setInnerIn(.21f,.21f);
                refraction.setInnerFull(.21f,.21f);
                refraction.setInnerOut(.21f,.21f);
                refraction.setInnerHardness(.18f);
                refraction.setRingHardness(.40f);
                refraction.setPowerIn(0f);
                refraction.setPowerOut(0f);
                refraction.setPowerFull(INWARD_POWER*strength);
                refraction.setSizeFull(extent,extent);
                if (CombatRenderingManager.addEntity(refraction)==BoxEnum.STATE_SUCCESS) return;
                refraction.delete(); refraction=null;
            }
            if (mods.isModEnabled("MagicLib")) ring=settings.getSprite("misc","wormhole_ring");
            if (ring==null) { finished=true; return; }
        }
        if (refraction!=null) {
            refraction.setPowerFull(INWARD_POWER*strength);
            refraction.setSizeFull(extent,extent);
        } else {
            float size=2f*extent;
            Color shade=new Color(tint.getRed(),tint.getGreen(),tint.getBlue(),Math.round(tint.getAlpha()*RING_OPACITY*strength));
            if (firstFrame) {
                MagicRender.battlespace(ring,center,STILL,new Vector2f(size,size),null,0f,0f,shade,true,
                        0f,0f,0f,0f,0f,0f,FOLD_END,0f,CombatEngineLayers.UNDER_SHIPS_LAYER);
            } else { ring.setSize(size,size); ring.setColor(shade); }
        }
    }

    void clear() {
        if (refraction!=null && !refraction.hasDelete()) {
            refraction.setPowerFull(0f);
            refraction.setGlobalTimerOnce();
        }
        if (ring!=null) ring.setSize(0f,0f);
        refraction=null; ring=null; finished=true;
    }
}
