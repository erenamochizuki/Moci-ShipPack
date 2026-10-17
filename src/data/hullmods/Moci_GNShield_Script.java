package data.hullmods;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import org.dark.shaders.distortion.DistortionShader;
import org.dark.shaders.distortion.RippleDistortion;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;

import data.scripts.util.Moci_ColorData;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EmpArcEntityAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import com.fs.starfarer.api.loading.ProjectileSpawnType;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.TimeoutTracker;
import data.scripts.util.Moci_TextLoader;

import data.scripts.Moci_GNShieldUI;

public class Moci_GNShield_Script implements DamageTakenModifier, AdvanceableListener {
    private static final String TEXT_ID = "Moci_GNShieldStatus";
    private ShipAPI ship;
    public static final String KEY = "Moci_GNShield_Absorb";

    private final Color DMG_TEXT = new Color(15, 15, 245, 225);

    public enum ParamType {
        ARC, SHIP, PROJ, BEAM;
    }

    public static Moci_GNShield_Script getInstance(ShipAPI ship) {
        if (Moci_GNShield_Script.hasShield(ship)) {
            return ship.getListeners(Moci_GNShield_Script.class).get(0);
        } else {
            Moci_GNShield_Script l = new Moci_GNShield_Script(ship);
            ship.addListener(l);
            return l;
        }
    }

    public static boolean hasShield(ShipAPI ship) {
        return ship.hasListenerOfClass(Moci_GNShield_Script.class);
    }

    public Moci_GNShield_Script(ShipAPI ship) {
        this.ship = ship;
        init(ship);
    }

    private TimeoutTracker<BeamAPI> effected = new TimeoutTracker<>();
    private List<Moci_GNShieldHitData> hitDatas = new ArrayList<>();

    public void init(ShipAPI ship) {
        saveArmor();
        if (ship.getShield() != null) {
            ship.getShield().setArc(0f);
            ship.getShield().setRadius(Math.max(10f, ship.getCollisionRadius() - ship.getHullSize().ordinal() * 10f));
            ship.getShield().setSkipRendering(true);
            ship.getShield().setInnerColor(Moci_ColorData.NONE);
            ship.getShield().setRingColor(Moci_ColorData.NONE);
        }
        
        // 注册UI插件（只会注册一次）
        Moci_GNShieldUI.register();
    }

    private TimeoutTracker<BeamAPI> beamController = new TimeoutTracker<>();

    public String modifyDamageTaken(Object param, CombatEntityAPI target,
                                    DamageAPI damage, Vector2f point, boolean shieldHit) {
        damage.getModifier().unmodify(KEY);
        if(ship.isStationModule()){
            ShipAPI parent = ship.getParentStation();
            if (parent==null|| (parent.getShield() == null || (parent.getShield() != null && !parent.getShield().isOn()))) {
                return null;
            }
            ParamType type = getType(param, damage);

            ShipAPI source = null;
            float factor = parent.getHullSpec().getShieldSpec().getFluxPerDamageAbsorbed() *
                    parent.getMutableStats().getShieldAbsorptionMult().getModifiedValue() *
                    parent.getMutableStats().getShieldDamageTakenMult().getModifiedValue();
            boolean isBeam = false;
            switch (type) {
                case BEAM:
                    source = ((BeamAPI) param).getSource();
                    isBeam = true;
                    break;
                case PROJ:
                    DamagingProjectileAPI projectile = (DamagingProjectileAPI) param;
                    source = projectile.getSource();
                    if (shouldSuppressFluxForProjectile(projectile)) factor = 0;
                    break;
                case SHIP:
                    break;
                default:
                    break;
            }
            if (damage.isDps()) isBeam = true;
            float damageTaken = (factor==0)?0:computeDamageToShield(damage, source, isBeam) * factor;
            if(damage.isForceHardFlux()) isBeam = false;
            if (damageTaken >= 0) {
                {
                    if (type == ParamType.BEAM) {
                        BeamAPI beam = (BeamAPI) param;
                        if (!beamController.contains(beam) && beam.getBrightness() > 0) {
                            beamController.add(beam, 1f);
                        }
                    }
                    if (type == ParamType.PROJ) {
                        createHitRipple(point, parent.getVelocity(), damageTaken,
                                damage.getType(),
                                VectorUtils.getFacing(VectorUtils.getDirectionalVector(parent.getLocation(),
                                        point)),
                                parent.getCollisionRadius());
                    }
                }
                parent.getFluxTracker().increaseFlux(damageTaken,!isBeam);

                Global.getCombatEngine().addFloatingDamageText(point, damageTaken, TEXT_COLOR, ship, (damage.getStats() != null ? damage.getStats().getEntity() : null));
                damage.getModifier().modifyMult(KEY, 0.0001f);//绝对不能是0
                if (type == ParamType.BEAM) {
                    if (effected.contains((BeamAPI) param)) {
                        effected.set((BeamAPI) param, 1.1f);
                    } else {
                        effected.add((BeamAPI) param, 1.1f);
                    }
                }
                return KEY;
            }
        }else {
            if (ship.getShield() == null || (ship.getShield() != null && !ship.getShield().isOn())) {
                return null;
            }
            ParamType type = getType(param, damage);

            ShipAPI source = null;
            float factor = ship.getHullSpec().getShieldSpec().getFluxPerDamageAbsorbed() *
                    ship.getMutableStats().getShieldAbsorptionMult().getModifiedValue() *
                    ship.getMutableStats().getShieldDamageTakenMult().getModifiedValue();
            boolean isBeam = false;
            switch (type) {
                case BEAM:
                    source = ((BeamAPI) param).getSource();
                    isBeam = true;
                    break;
                case PROJ:
                    source = ((DamagingProjectileAPI) param).getSource();
                    break;
                case SHIP:
                    break;
                default:
                    break;
            }
            if (damage.isDps()) isBeam = true;
            float damageTaken = computeDamageToShield(damage, source, isBeam) * factor;
            if(damage.isForceHardFlux()) isBeam = false;
            if (damageTaken > 0) {
                {
                    if (type == ParamType.BEAM) {
                        BeamAPI beam = (BeamAPI) param;
                        if (!beamController.contains(beam) && beam.getBrightness() > 0) {
                            beamController.add(beam, 1f);
                        }
                    }
                    if (type == ParamType.PROJ) {
                        createHitRipple(point, ship.getVelocity(), damageTaken,
                                damage.getType(),
                                VectorUtils.getFacing(VectorUtils.getDirectionalVector(ship.getLocation(),
                                        point)),
                                ship.getCollisionRadius());
                        // 添加击中效果数据
                        hitDatas.add(new Moci_GNShieldHitData(ship, point, 1.0f, 
                                MathUtils.clamp(damageTaken/4f, 10f, 40f)));
                    }
                    if (type == ParamType.BEAM) {
                        // 添加光束击中效果数据
                        hitDatas.add(new Moci_GNShieldHitData(ship, point, 0.8f, 
                                MathUtils.clamp(damageTaken/2f, 10f, 40f)));
                    }
                }
                ship.getFluxTracker().increaseFlux(damageTaken, !isBeam);
                Global.getCombatEngine().addFloatingDamageText(point, damageTaken, TEXT_COLOR, ship, (damage.getStats() != null ? damage.getStats().getEntity() : null));
                damage.getModifier().modifyMult(KEY, 0.0001f);//绝对不能是0
                if (type == ParamType.BEAM) {
                    if (effected.contains((BeamAPI) param)) {
                        effected.set((BeamAPI) param, 1.1f);
                    } else {
                        effected.add((BeamAPI) param, 1.1f);
                    }
                }
                return KEY;
            }
        }


        return null;
    }

    public static final Color TEXT_COLOR = new Color(255, 155, 255, 255);
    public static final Color JITTER_COLOR = new Color(155, 155, 255, 75);
    public static final Color JITTER_UNDER_COLOR = new Color(155, 155, 255, 155);

    public void advance(float amount) {
        // 死亡时移除监听器
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            ship.removeListener(this);
            return;
        }
        
        if (!effected.getItems().isEmpty()) {
            for (BeamAPI b : effected.getItems()) {
                if (effected.getRemaining(b) < 1f) {
                    b.getDamage().getModifier().unmodify(KEY);
                }
            }
        }
        
        // 更新击中效果数据
        List<Moci_GNShieldHitData> toRemoveHits = new ArrayList<>();
        for(Moci_GNShieldHitData data : hitDatas){
            data.advance(amount);
            if(data.shouldExpire()){
                toRemoveHits.add(data);
            }
        }
        hitDatas.removeAll(toRemoveHits);
        
        effected.advance(1);
        beamController.advance(amount);
        if (Global.getCombatEngine().isPaused())
            return;
        if (ship.isStationModule()) {
            ShipAPI core = ship.getParentStation();

            /*
            if (core != null && core.getShield() != null && core.getShield().isOn()) {
                retainArmor();
                Global.getCombatEngine().maintainStatusForPlayerShip(ship, "graphics/icons/hullsys/fortress_shield.png",
                        Moci_TextLoader.getText(TEXT_ID, "status.module_title"),
                        Moci_TextLoader.getText(TEXT_ID, "status.active"), false);
            }

             */
        } else {
            if (ship.getShield() != null) {
                if (ship.getShield().isOn()) {
                    ship.getShield().forceFacing(Misc.normalizeAngle(ship.getFacing() + 180f));
                    retainArmor();
                    ship.getMutableStats().getEmpDamageTakenMult().modifyMult(getKey(), 0f);
                    if (ship == Global.getCombatEngine().getPlayerShip()) {
                        Global.getCombatEngine().maintainStatusForPlayerShip("Moci_GNShield", "graphics/icons/hullsys/fortress_shield.png",
                                Moci_TextLoader.getText(TEXT_ID, "status.main_title"),
                                Moci_TextLoader.getText(TEXT_ID, "status.active"), false);
                    }
                    //float jitterLevel = 1f;
                    //ship.setJitterUnder(this, JITTER_UNDER_COLOR, jitterLevel, 2, 0f, 3f + jitterLevel * 5f);
                    //ship.setJitter(this, JITTER_COLOR, jitterLevel, 2, 0f, 0f);
                } else {
                    if (ship == Global.getCombatEngine().getPlayerShip()) {
                        Global.getCombatEngine().maintainStatusForPlayerShip("Moci_GNShield", "graphics/icons/hullsys/fortress_shield.png",
                                Moci_TextLoader.getText(TEXT_ID, "status.main_title"),
                                Moci_TextLoader.getText(TEXT_ID, "status.inactive"), true);
                    }
                    ship.getMutableStats().getEmpDamageTakenMult().unmodify(getKey());
                }
            }
            saveArmor();
        }
    }

    protected ParamType getType(Object param, DamageAPI damage) {
        if (param instanceof DamagingProjectileAPI) {
            return ParamType.PROJ;
        }
        if (param instanceof BeamAPI) {
            return ParamType.BEAM;
        }
        if (param instanceof EmpArcEntityAPI) {
            return ParamType.ARC;
        }
        return ParamType.SHIP;
    }

    private float computeDamageToShield(DamageAPI damage, ShipAPI source, boolean isBeam) {
        float base = damage.getDamage();
        if (isBeam) {
            if (damage.getDamage() > 0) {
                base = damage.getDpsDuration() * damage.getDamage();
            }
        }
        base *= damage.getType().getShieldMult();
        if (base == 0) {
            return 0;
        }
        float mult = 1;
        if (source != null) {
            switch (ship.getHullSize()) {
                case DEFAULT:
                    break;
                case FIGHTER:
                    mult *= source.getMutableStats().getDamageToFighters().getModifiedValue();
                    break;
                case FRIGATE:
                    mult *= source.getMutableStats().getDamageToFrigates().getModifiedValue();
                    break;
                case DESTROYER:
                    mult *= source.getMutableStats().getDamageToDestroyers().getModifiedValue();
                    break;
                case CRUISER:
                    mult *= source.getMutableStats().getDamageToCruisers().getModifiedValue();
                    break;
                case CAPITAL_SHIP:
                    mult *= source.getMutableStats().getDamageToCapital().getModifiedValue();
                    break;
            }
        }
        switch (damage.getType()) {
            case KINETIC:
                mult *= ship.getMutableStats().getKineticDamageTakenMult().getModifiedValue();
                break;
            case HIGH_EXPLOSIVE:
                mult *= ship.getMutableStats().getHighExplosiveDamageTakenMult().getModifiedValue();
                break;
            case FRAGMENTATION:
                mult *= ship.getMutableStats().getFragmentationDamageTakenMult().getModifiedValue();
                mult *= 0.25f;
                break;
            case ENERGY:
                mult *= ship.getMutableStats().getEnergyDamageTakenMult().getModifiedValue();
                break;
            case OTHER:
                break;
        }
        if (isBeam) {
            mult *= ship.getMutableStats().getBeamDamageTakenMult().getModifiedValue();
        } else {
            mult *= ship.getMutableStats().getProjectileDamageTakenMult().getModifiedValue();
        }
        return base * mult;
    }

    /**
     * Missiles and damaging explosions are valid shield hits even though they do not expose a
     * ProjectileSpecAPI. Only suppress truly spec-less custom projectiles.
     */
    private boolean shouldSuppressFluxForProjectile(DamagingProjectileAPI projectile) {
        if (projectile == null) {
            return false;
        }
        if (projectile instanceof MissileAPI || projectile.getSpawnType() == ProjectileSpawnType.MISSILE) {
            return false;
        }
        if (projectile.getExplosionSpecIfExplosion() != null) {
            return false;
        }
        return projectile.getProjectileSpec() == null;
    }

    protected boolean checkSurvival() {
        return !ship.isAlive();
    }

    private float[][] armorGridCopy = null;

    public void retainArmor() {
        if (armorGridCopy == null) {
            saveArmor();
        } else {
            ArmorGridAPI a = ship.getArmorGrid();

            if (a == null)
                return;
            float[][] cag = a.getGrid();
            if (cag.length == 0)
                return;
            for (int i = 0; i < cag.length; i++) {
                for (int j = 0; j < cag[i].length; j++) {
                    a.setArmorValue(i, j, Math.max(armorGridCopy[i][j], cag[i][j]));
                }
            }
        }
    }

    public void saveArmor() {
        ArmorGridAPI a = ship.getArmorGrid();
        if (a == null)
            return;
        float[][] cag = a.getGrid();
        if (cag.length == 0)
            return;
        armorGridCopy = new float[cag.length][cag[0].length];
        for (int i = 0; i < cag.length; i++) {
            for (int j = 0; j < cag[i].length; j++) {
                armorGridCopy[i][j] = cag[i][j];
            }
        }
    }

    public void immuneEMPEffects(float level) {
        float EMP_REDUCTION = 0f;
        if (level < 0.25f) {
            EMP_REDUCTION = (0.25f - level) * 4f;
        }
        ship.getMutableStats().getEmpDamageTakenMult().modifyMult(KEY, EMP_REDUCTION);
    }


    private void createHitRipple(Vector2f location, Vector2f velocity, float damage, DamageType type, float direction,
                                 float shieldRadius) {
        float dmg = damage;
        if (type == DamageType.FRAGMENTATION) {
            dmg *= 0.25f;
        }
        if (type == DamageType.HIGH_EXPLOSIVE) {
            dmg *= 0.5f;
        }
        if (type == DamageType.KINETIC) {
            dmg *= 2f;
        }

        if (dmg < 75f) {
            return;
        }

        float fadeTime = (float) Math.pow(dmg, 0.25) * 0.1f;
        float size = (float) Math.pow(dmg, 0.3333333) * 8f;

        float ratio = Math.min(size / shieldRadius, 1f);
        float arc = 90f - ratio * 14.54136f; // Don't question the magic number

        float start1 = direction - arc;
        if (start1 < 0f) {
            start1 += 360f;
        }
        float end1 = direction + arc;
        if (end1 >= 360f) {
            end1 -= 360f;
        }

        float start2 = direction + arc;
        if (start2 < 0f) {
            start2 += 360f;
        }
        float end2 = direction - arc;
        if (end2 >= 360f) {
            end2 -= 360f;
        }

        RippleDistortion ripple = new RippleDistortion(location, velocity);
        ripple.setSize(size);
        ripple.setIntensity(size * 0.2f);
        ripple.setFrameRate(60f / fadeTime);
        ripple.fadeInSize(fadeTime * 1.2f);
        ripple.fadeOutIntensity(fadeTime);
        ripple.setSize(size * 0.2f);
        ripple.setArc(start1, end1);
        DistortionShader.addDistortion(ripple);

        ripple = new RippleDistortion(location, velocity);
        ripple.setSize(size);
        ripple.setIntensity(size * 0.05f);
        ripple.setFrameRate(60f / fadeTime);
        ripple.fadeInSize(fadeTime * 1.2f);
        ripple.fadeOutIntensity(fadeTime);
        ripple.setSize(size * 0.2f);
        ripple.setArc(start2, end2);
        DistortionShader.addDistortion(ripple);
    }

    public static String getKey() {
        return KEY;
    }

    public ShipAPI getShip() {
        return ship;
    }

    public List<Moci_GNShieldHitData> getHitDatas() {
        return hitDatas;
    }

    // GN护盾击中数据类
    public static class Moci_GNShieldHitData {
        private Vector2f rel;
        private float time;
        private final float size;
        private final float orgT;

        public Moci_GNShieldHitData(ShipAPI ship, Vector2f location, float time, float size){
            this.rel = Vector2f.sub(location, ship.getLocation(), null);
            VectorUtils.rotate(rel, -ship.getFacing());
            this.orgT = time;
            this.time = time;
            this.size = size;
        }

        public void advance(float amount){
            time -= amount;
        }

        public boolean shouldExpire(){
            return time <= 0;
        }

        public float getSize() {
            return size;
        }

        public Vector2f getRel() {
            return new Vector2f(rel);
        }

        public float getLevel(){
            return (orgT == 0) ? 0 : time / orgT;
        }
    }

}
