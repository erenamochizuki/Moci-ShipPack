package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.CombatListenerManagerAPI;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import com.fs.starfarer.api.loading.BeamWeaponSpecAPI;
import com.fs.starfarer.api.loading.ProjectileSpawnType;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.combat.entities.MovingRay;
import java.util.*;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import org.lwjgl.opengl.*;
import org.lwjgl.util.vector.Vector2f;

/** Owned Zeta weapon/defense overlay. Original body, transform, blade and fire callbacks stay native. */
public final class ZetaOverdriveStats implements AutoCloseable,DamageTakenModifier {
    public static final String SYSTEM_ID="Moci_fw_zeta_biosensor_overload",KEY="xi.arsenal.zeta.overdrive",
            MODIFIER=KEY+".stats",BLADE_MODIFIER=KEY+".bladeDamage",RAY_RESISTANCE=KEY+".rayResistance",
            PHYSICAL_COMPENSATION=KEY+".physicalBioSensor";
    public static final String HULL_ID="Moci_MSZ_006",SABRE_ID="MSZ_006_arm_left_gun_Sabre",RAM_ID="Moci_fw_zeta_shield_ram";
    public static final float DAMAGE=1.35f,RANGE=1.5f,AIM_TURN=2f,SPREAD=.45f,VISUAL_WIDTH=1.7f,BEAM_TAKEN=.5f;
    private static final int MAX_WEAPONS=256,MAX_BEAMS=256,MAX_RAYS=64,MAX_PROJECTILES=4096;
    private static final float EPS=.0001f;
    private static final String ORIGINAL_BIOSENSOR="Moci_BioSensor";
    private final ShipAPI ship;
    private final MutableShipStatsAPI stats;
    private final Map<WeaponAPI,WeaponChange> weapons=new IdentityHashMap<>();
    private final Map<BeamAPI,Field> beams=new IdentityHashMap<>();
    private final Set<WeaponAPI> blades=Collections.newSetFromMap(new IdentityHashMap<>());
    private CombatEngineAPI boundEngine;
    private CombatListenerManagerAPI damageListeners;
    private boolean closed,waveRider;
    private float effect;
    private int coatedPartsLastRender;
    private long fieldConflicts,unclonableSpecs,budgetSkips,discreteRayDamageCallbacks;

    public ZetaOverdriveStats(ShipAPI ship){this.ship=Objects.requireNonNull(ship);this.stats=Objects.requireNonNull(ship.getMutableStats());}
    public static boolean supports(ShipAPI ship){
        if(ship==null||ship.isDrone()||ship.isFighter()||ship.isStationModule()||ship.getParentStation()!=null)return false;
        Set<ShipHullSpecAPI> seen=Collections.newSetFromMap(new IdentityHashMap<>());ArrayDeque<ShipHullSpecAPI> todo=new ArrayDeque<>();
        if(ship.getHullSpec()!=null)todo.add(ship.getHullSpec());
        for(int checked=0;checked<16&&!todo.isEmpty();checked++){ShipHullSpecAPI hull=todo.removeFirst();if(!seen.add(hull))continue;
            if(HULL_ID.equals(hull.getHullId())||HULL_ID.equals(hull.getBaseHullId())||HULL_ID.equals(hull.getDParentHullId()))return true;
            if(hull.getBaseHull()!=null)todo.addLast(hull.getBaseHull());if(hull.getDParentHull()!=null)todo.addLast(hull.getDParentHull());}
        return false;
    }
    /** Called once by the owning enhancement per frame with the actual native F effect and original WR state. */
    public void apply(float effectLevel,boolean waveRider){
        if(closed)return;this.waveRider=waveRider;
        effect=supports(ship)&&ship.isAlive()&&!ship.isHulk()&&!ship.wasRemoved()?clamp(effectLevel):0;
        if(effect<=0){reset();return;}
        bindDamageRouter();float damage=blend(DAMAGE,effect);
        // Zeta's native ENERGY weapons are optical (MovingRay rifle and real Beam sabre/sniper).
        // Preserve the proven native energy damage route and original sabre cap compensation.
        // Physical ballistic/missile weapons never receive this owned damage multiplier.
        stats.getEnergyWeaponDamageMult().modifyMult(MODIFIER,damage);
        stats.getBallisticWeaponDamageMult().unmodify(MODIFIER);stats.getMissileWeaponDamageMult().unmodify(MODIFIER);
        // A ship-wide turn/accuracy bonus also enhances wrist grenades. Optical aim is instead
        // expressed through each qualified cloned spec's turn rate and dispersion below.
        stats.getWeaponTurnRateBonus().unmodify(MODIFIER);stats.getBeamWeaponTurnRateBonus().unmodify(MODIFIER);
        stats.getAutofireAimAccuracy().unmodify(MODIFIER);
        compensatePhysicalBioSensor();
        stats.getBeamDamageTakenMult().modifyMult(MODIFIER,blend(BEAM_TAKEN,effect));
        stats.getBeamShieldDamageTakenMult().modifyMult(MODIFIER,blend(BEAM_TAKEN,effect));
        updateWeapons();
    }
    private void bindDamageRouter(){
        CombatEngineAPI current=Global.getCombatEngine();if(current==null)return;
        if(boundEngine!=null&&boundEngine!=current)throw new IllegalStateException("Zeta overdrive helper cannot cross native battles");
        if(damageListeners==null){boundEngine=current;damageListeners=current.getListenerManager();damageListeners.addListener(this);}
    }
    /** Cancel only the original F's physical RoF/cost entries, retaining their keys and foreign mods. */
    private void compensatePhysicalBioSensor(){
        inverseOriginal(stats.getBallisticRoFMult());inverseOriginal(stats.getBallisticWeaponFluxCostMod());
    }
    private static void inverseOriginal(MutableStat stat){
        MutableStat.StatMod original=stat.getMultStatMod(ORIGINAL_BIOSENSOR);
        if(original!=null&&Float.isFinite(original.getValue())&&original.getValue()>EPS)stat.modifyMult(PHYSICAL_COMPENSATION,1f/original.getValue());
        else stat.unmodify(PHYSICAL_COMPENSATION);
    }
    private static void inverseOriginal(StatBonus stat){
        MutableStat.StatMod original=stat.getMultBonus(ORIGINAL_BIOSENSOR);
        if(original!=null&&Float.isFinite(original.getValue())&&original.getValue()>EPS)stat.modifyMult(PHYSICAL_COMPENSATION,1f/original.getValue());
        else stat.unmodify(PHYSICAL_COMPENSATION);
    }
    /** True optical native weapon classification; a BALLISTIC gun with beam-like art is not enough. */
    public static boolean isOpticalWeapon(WeaponAPI weapon){
        if(weapon==null||RAM_ID.equals(weapon.getId())||"Moci_fw_zeta_wrist_grenade".equals(weapon.getId()))return false;
        WeaponSpecAPI spec=weapon.getSpec();if(spec==null)return false;
        boolean originalBlade=SABRE_ID.equals(weapon.getId());
        if(weapon.getType()!=WeaponAPI.WeaponType.ENERGY&&!(originalBlade&&spec.getType()==WeaponAPI.WeaponType.ENERGY))return false;
        if(weapon.isDecorative()&&!originalBlade)return false;
        if(weapon.isBeam()&&spec instanceof BeamWeaponSpecAPI)return true;
        Object projectile=spec.getProjectileSpec();
        if(!(projectile instanceof com.fs.starfarer.api.loading.ProjectileSpecAPI ray))return false;
        return ray.getSpawnType()==ProjectileSpawnType.BEAM||ray.getSpawnType()==ProjectileSpawnType.BALLISTIC_AS_BEAM;
    }
    private void updateWeapons(){
        Set<WeaponAPI> seen=Collections.newSetFromMap(new IdentityHashMap<>());Set<BeamAPI> seenBeams=Collections.newSetFromMap(new IdentityHashMap<>());
        int count=0;for(WeaponAPI weapon:ship.getAllWeapons()){
            if(count++>=MAX_WEAPONS){budgetSkips++;break;}
            if(!isOpticalWeapon(weapon))continue;
            seen.add(weapon);WeaponChange change=weapons.get(weapon);
            if(change!=null&&change.spec!=weapon.getSpec()){change.restore();weapons.remove(weapon);change=null;}
            if(change==null){weapon.ensureClonedSpec();WeaponSpecAPI local=weapon.getSpec();
                if(local==null||local==weapon.getOriginalSpec()){unclonableSpecs++;continue;}
                change=new WeaponChange(weapon,local);weapons.put(weapon,change);}
            change.apply(effect);
            if(SABRE_ID.equals(weapon.getId())){
                blades.add(weapon);float own=1;MutableStat.StatMod mod=stats.getEnergyWeaponDamageMult().getMultStatMod(MODIFIER);
                if(mod!=null&&mod.getValue()>EPS)own=mod.getValue();
                float foreignEnergy=stats.getEnergyWeaponDamageMult().computeMultMod()/own;
                float beamPercent=stats.getBeamWeaponDamageMult().getPercentMod()*.01f;
                float correction=bladeDamageCorrection(weapon.getSpec().getBurstDuration(),foreignEnergy,beamPercent,blend(DAMAGE,effect));
                weapon.getDamage().getModifier().modifyMult(BLADE_MODIFIER,correction);
            }
            for(BeamAPI beam:weapon.getBeams()){
                if(seenBeams.size()>=MAX_BEAMS){budgetSkips++;break;}
                if(beam==null||beam.getWeapon()!=weapon||beam.getSource()!=ship)continue;seenBeams.add(beam);
                Field width=beams.get(beam);if(width==null){
                    float observed=beam.getWidth(),base=observed;
                    // A beam born after the cloned spec was widened already has our desired width.
                    // Use the original width once, rather than multiplying the new beam a second time.
                    if(change.width!=null&&(ZetaRifleModes.SNIPER.equals(weapon.getId())||same(observed,change.width.written)))base=change.width.original;
                    width=new Field(beam::getWidth,v->beam.setWidth((float)v),base,observed);beams.put(beam,width);}
                width.apply(blend(visualMultiplier(weapon),effect)*(ZetaRifleModes.SNIPER.equals(weapon.getId())?ZetaRifleModes.firingWidthMultiplier(weapon):1));
            }
        }
        for(Iterator<Map.Entry<WeaponAPI,WeaponChange>> it=weapons.entrySet().iterator();it.hasNext();){var entry=it.next();if(!seen.contains(entry.getKey())){entry.getValue().restore();it.remove();}}
        for(Iterator<Map.Entry<BeamAPI,Field>> it=beams.entrySet().iterator();it.hasNext();){var entry=it.next();if(!seenBeams.contains(entry.getKey())){entry.getValue().restore();it.remove();}}
        for(Iterator<WeaponAPI> it=blades.iterator();it.hasNext();){WeaponAPI blade=it.next();if(!seen.contains(blade)){blade.getDamage().getModifier().unmodify(BLADE_MODIFIER);it.remove();}}
    }
    /** Exact compensation for the original Moci blade's max(base,base*burst*(energy+beamPercent)) cap.
     * This changes only the existing weapon DamageAPI modifier; no extra damage or effect callback. */
    public static float bladeDamageCorrection(float burst,float foreignEnergyMult,float beamPercent,float desiredDamageMult){
        if(!Float.isFinite(burst)||!Float.isFinite(foreignEnergyMult)||!Float.isFinite(beamPercent)||!Float.isFinite(desiredDamageMult)||desiredDamageMult<=0)return 1;
        float before=Math.max(1,burst*(foreignEnergyMult+beamPercent));
        float after=Math.max(1,burst*(foreignEnergyMult*desiredDamageMult+beamPercent));
        return desiredDamageMult*before/after;
    }
    private final class WeaponChange {
        final WeaponAPI weapon;final WeaponSpecAPI spec;final Field range,min,max,buildup,width,turn;
        WeaponChange(WeaponAPI weapon,WeaponSpecAPI spec){this.weapon=weapon;this.spec=spec;
            range=field(spec::getMaxRange,v->spec.setMaxRange((float)v));min=field(spec::getMinSpread,v->spec.setMinSpread((float)v));
            max=field(spec::getMaxSpread,v->spec.setMaxSpread((float)v));buildup=field(spec::getSpreadBuildup,v->spec.setSpreadBuildup((float)v));
            turn=field(spec::getTurnRate,v->spec.setTurnRate((float)v));
            width=spec instanceof BeamWeaponSpecAPI beam?field(beam::getWidth,v->beam.setWidth((float)v)):null;}
        void apply(float level){range.apply(blend(SABRE_ID.equals(weapon.getId())?2:RANGE,level));min.apply(blend(SPREAD,level));max.apply(blend(SPREAD,level));buildup.apply(blend(SPREAD,level));turn.apply(blend(AIM_TURN,level));if(width!=null)width.apply(blend(visualMultiplier(weapon),level)*(ZetaRifleModes.SNIPER.equals(weapon.getId())?ZetaRifleModes.firingWidthMultiplier(weapon):1));}
        void restore(){range.restore();min.restore();max.restore();buildup.restore();turn.restore();if(width!=null)width.restore();}
    }
    private Field field(DoubleSupplier getter,DoubleConsumer setter){float initial=(float)getter.getAsDouble();return new Field(getter,setter,initial,initial);}
    private static float visualMultiplier(WeaponAPI w){return SABRE_ID.equals(w.getId())?2:ZetaRifleModes.SNIPER.equals(w.getId())?1.5f:VISUAL_WIDTH;}
    private final class Field {
        final DoubleSupplier getter;final DoubleConsumer setter;final float original;float written;boolean yielded;
        Field(DoubleSupplier getter,DoubleConsumer setter,float original,float observed){this.getter=getter;this.setter=setter;this.original=original;written=observed;yielded=!Float.isFinite(original)||original<0;}
        void apply(float mult){if(yielded)return;if(!same((float)getter.getAsDouble(),written)){yielded=true;fieldConflicts++;return;}written=original*mult;setter.accept(written);}
        void restore(){if(!yielded&&same((float)getter.getAsDouble(),written))setter.accept(original);}
    }
    @Override public String modifyDamageTaken(Object source,CombatEntityAPI target,DamageAPI damage,Vector2f point,boolean shieldHit){
        if(closed||effect<=0||target!=ship||damage==null||damage.isDps()||!(source instanceof DamagingProjectileAPI projectile)||projectile.getProjectileSpec()==null)return null;
        ProjectileSpawnType type=projectile.getProjectileSpec().getSpawnType();
        if(type!=ProjectileSpawnType.BEAM&&type!=ProjectileSpawnType.BALLISTIC_AS_BEAM)return null;
        damage.getModifier().modifyMult(RAY_RESISTANCE,blend(BEAM_TAKEN,effect));discreteRayDamageCallbacks++;return RAY_RESISTANCE;
    }
    private void reset(){
        effect=0;coatedPartsLastRender=0;for(MutableStat stat:new MutableStat[]{stats.getEnergyWeaponDamageMult(),stats.getBallisticWeaponDamageMult(),stats.getMissileWeaponDamageMult(),
                stats.getAutofireAimAccuracy(),stats.getBeamDamageTakenMult(),stats.getBeamShieldDamageTakenMult()})stat.unmodify(MODIFIER);
        stats.getWeaponTurnRateBonus().unmodify(MODIFIER);stats.getBeamWeaponTurnRateBonus().unmodify(MODIFIER);
        stats.getBallisticRoFMult().unmodify(PHYSICAL_COMPENSATION);stats.getBallisticWeaponFluxCostMod().unmodify(PHYSICAL_COMPENSATION);
        for(Field width:beams.values())width.restore();beams.clear();for(WeaponChange change:weapons.values())change.restore();weapons.clear();
        for(WeaponAPI blade:blades)blade.getDamage().getModifier().unmodify(BLADE_MODIFIER);blades.clear();
    }
    /** Own GL overlay only. Call from the existing main's renderInWorldCoords,after normal native drawing.
     * Real burst beams are already physically widened by apply; MovingRay specs remain shared/untouched. */
    public void renderWorld(ViewportAPI viewport){
        if(closed||effect<=0||viewport==null)return;CombatEngineAPI engine=Global.getCombatEngine();if(engine==null)return;
        coatedPartsLastRender=viewport.isNearViewport(ship.getLocation(),500)?ZetaOverdriveCoating.render(ship,effect,viewport.getAlphaMult(),engine.getTotalElapsedTime(false)):0;
        int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),texture=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try{GL20.glUseProgram(0);GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glDisable(GL11.GL_TEXTURE_2D);GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDepthMask(false);GL11.glEnable(GL11.GL_BLEND);GL11.glBlendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE);GL11.glShadeModel(GL11.GL_SMOOTH);
            float alpha=effect*clamp(viewport.getAlphaMult()),time=engine.getTotalElapsedTime(false);GL11.glLineWidth(1.4f);
            // WR retains the actual purple coating and real engine flare; no enclosing shield-like ellipse.
            for(int i=0;!waveRider&&i<3;i++){float phase=(time*.7f+i/3f)%1f,radius=ship.getCollisionRadius()*(.8f+phase*.9f);
                GL11.glColor4f(.8f,.28f,1,alpha*(1-phase)*.3f);GL11.glBegin(GL11.GL_LINE_LOOP);
                float angle=(float)Math.toRadians(ship.getFacing());for(int n=0;n<64;n++){double a=n*Math.PI*2/64;float x=(float)Math.cos(a)*radius*(waveRider?1.4f:1),y=(float)Math.sin(a)*radius*(waveRider?.7f:1);
                    GL11.glVertex2f(ship.getLocation().x+x*(float)Math.cos(angle)-y*(float)Math.sin(angle),ship.getLocation().y+x*(float)Math.sin(angle)+y*(float)Math.cos(angle));}GL11.glEnd();}
            int scanned=0,drawn=0;for(DamagingProjectileAPI projectile:engine.getProjectiles()){
                if(scanned++>=MAX_PROJECTILES||drawn>=MAX_RAYS)break;
                if(projectile.getSource()!=ship||!isOpticalWeapon(projectile.getWeapon())||!(projectile instanceof MovingRay ray)||ray.isExpired())continue;
                Vector2f from=ray.getFrom(),to=ray.getTo();float dx=to.x-from.x,dy=to.y-from.y,len=(float)Math.hypot(dx,dy);if(!Float.isFinite(len)||len<=0)continue;
                float width=projectile.getProjectileSpec()==null?15:projectile.getProjectileSpec().getWidth();
                ribbon(from,to,-dy/len,dx/len,width*blend(VISUAL_WIDTH,effect),alpha*clamp(ray.getBrightness())*.18f);drawn++;
            }
        }finally{GL20.glUseProgram(program);GL13.glActiveTexture(texture);GL11.glPopAttrib();}
    }
    private static void ribbon(Vector2f a,Vector2f b,float nx,float ny,float width,float alpha){
        float half=width*.5f;GL11.glBegin(GL11.GL_QUAD_STRIP);
        // Transparent outer edges retain the native bright core without a hard rectangular band.
        GL11.glColor4f(.85f,.28f,1,0);GL11.glVertex2f(a.x-nx*half,a.y-ny*half);GL11.glVertex2f(b.x-nx*half,b.y-ny*half);
        GL11.glColor4f(.85f,.28f,1,alpha);GL11.glVertex2f(a.x,a.y);GL11.glVertex2f(b.x,b.y);
        GL11.glColor4f(.85f,.28f,1,0);GL11.glVertex2f(a.x+nx*half,a.y+ny*half);GL11.glVertex2f(b.x+nx*half,b.y+ny*half);GL11.glEnd();
    }
    public Map<String,Object> status(){Map<String,Object> row=new LinkedHashMap<>();row.put("eligible",supports(ship));row.put("closed",closed);row.put("effectLevel",effect);row.put("waveRider",waveRider);
        row.put("damageMult",blend(DAMAGE,effect));row.put("rangeMult",blend(RANGE,effect));row.put("turnMult",blend(AIM_TURN,effect));row.put("spreadMult",blend(SPREAD,effect));row.put("visualWidthMult",blend(VISUAL_WIDTH,effect));
        row.put("beamDamageTakenMult",blend(BEAM_TAKEN,effect));row.put("clonedWeapons",weapons.size());row.put("nativeBeamInstances",beams.size());row.put("bladeDamageCompensations",blades.size());
        row.put("coatingPartsLastRender",coatedPartsLastRender);row.put("coatingMode","read-only visible texture-alpha full-surface purple coat plus soft bloom");
        row.put("fieldConflicts",fieldConflicts);row.put("unclonableSpecs",unclonableSpecs);row.put("budgetSkips",budgetSkips);row.put("discreteRayDamageCallbacks",discreteRayDamageCallbacks);row.put("earlyGlobalDamageRouter",damageListeners!=null);
        row.put("damageScope","Native ENERGY damage only for the Zeta optical loadout; no owned BALLISTIC or MISSILE damage multiplier");
        row.put("aimScope","Qualified ENERGY real Beam/BEAM/BALLISTIC_AS_BEAM cloned turn and spread only; no ship-wide autofire accuracy/turn bonus");
        MutableStat.StatMod physicalRof=stats.getBallisticRoFMult().getMultStatMod(PHYSICAL_COMPENSATION),physicalFlux=stats.getBallisticWeaponFluxCostMod().getMultBonus(PHYSICAL_COMPENSATION);
        row.put("physicalRoFCompensation",physicalRof==null?1:physicalRof.getValue());row.put("physicalFluxCompensation",physicalFlux==null?1:physicalFlux.getValue());
        row.put("widthMode","Instance-cloned BeamWeaponSpec + actual BeamAPI; native MovingRay-only read-only visual overlay");row.put("ramWeaponExcluded",RAM_ID);return Collections.unmodifiableMap(row);}
    @Override public void close(){if(closed)return;reset();closed=true;if(damageListeners!=null){damageListeners.removeListener(this);damageListeners=null;}boundEngine=null;}
    private static float blend(float target,float level){return 1+(target-1)*level;}
    private static float clamp(float value){return Float.isFinite(value)?Math.max(0,Math.min(1,value)):0;}
    private static boolean same(float a,float b){return Float.isFinite(a)&&Float.isFinite(b)&&Math.abs(a-b)<=EPS;}
}
