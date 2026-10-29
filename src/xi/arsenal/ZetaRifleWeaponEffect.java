package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.combat.entities.MovingRay;
import java.util.*;
import org.lwjgl.util.vector.Vector2f;

/** Single native trigger callback: pose-aware real muzzle alignment and read-only optical layers. */
public final class ZetaRifleWeaponEffect implements EveryFrameWeaponEffectPluginWithAdvanceAfter,WeaponEffectPluginWithInit,AutoCloseable {
    public static final String OPTICS_KEY=ZetaRifleModes.KEY+".optics";
    private WeaponAPI bound,source;private ZetaRifleMount mount;private CombatEngineAPI engine;
    private boolean sniper,rendererInstalled,closed;private Map<String,Object> opticalFacts;
    @Override public void init(WeaponAPI weapon){
        if(bound!=null){if(bound!=weapon)throw new IllegalStateException("Zeta rifle callback reused");return;}
        bound=weapon;sniper=ZetaRifleModes.SNIPER.equals(weapon.getId());
        if(!sniper&&!ZetaRifleModes.BURST.equals(weapon.getId()))throw new IllegalArgumentException("Wrong Zeta rifle callback");
        if(sniper)ZetaRifleMount.detach(weapon);else{source=weapon;mount=new ZetaRifleMount(weapon);}
    }
    @Override public void advance(float amount,CombatEngineAPI engine,WeaponAPI weapon){
        if(bound==null)init(weapon);if(bound!=weapon)throw new IllegalStateException("Zeta rifle instance mismatch");
        if(closed||engine==null||engine.isPaused())return;this.engine=engine;
        if(sniper&&source==null){String sourceSlot=ZetaRifleMount.isWr(weapon)?ZetaRifleModes.WR_SOURCE_SLOT:ZetaRifleModes.SOURCE_SLOT;source=ZetaRifleMount.slot(weapon.getShip(),sourceSlot);if(source==null)throw new IllegalStateException("Zeta deployed native rifle SOURCE missing");}
        ZetaEnhancement.ensure(engine);
        ZetaRifleModes.beforeWeapon(weapon);
        // 无引擎外资源：close() 只有 closed=true 并从舰船移除 OPTICS_KEY；渲染插件由 Visual.isExpired() 收尾。
        if(!rendererInstalled&&sniper&&ZetaRifleModes.SNIPER_SLOT.equals(weapon.getSlot().getId())){engine.addLayeredRenderingPlugin(new Visual());rendererInstalled=true;}
    }
    @Override public void advanceAfter(float amount,CombatEngineAPI engine,WeaponAPI weapon){if(closed||engine==null||engine.isPaused())return;ZetaRifleModes.afterWeapon(weapon);}
    /** Idempotent end-of-bone pass. Never advances another callback or changes group membership. */
    public static void reconcile(WeaponAPI source){if(source!=null&&source.getEffectPlugin() instanceof ZetaRifleWeaponEffect effect&&effect.mount!=null)effect.mount.reconcile();}
    private final class Visual extends BaseCombatLayeredRenderingPlugin {
        @Override public EnumSet<CombatEngineLayers> getActiveLayers(){return EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);}
        @Override public float getRenderRadius(){return bound==null?6000:Math.max(6000,Math.min(20000,bound.getRange()+512));}
        @Override public boolean isExpired(){return closed||bound==null||!bound.getShip().isAlive()||bound.getShip().wasRemoved()||Global.getCombatEngine()!=engine;}
        @Override public void advance(float amount){if(entity!=null&&bound!=null)entity.getLocation().set(bound.getShip().getLocation());}
        @Override public void render(CombatEngineLayers layer,ViewportAPI viewport){
            if(isExpired()||bound.getShip().isPhased())return;ShipAPI ship=bound.getShip();ZetaRifleModes own=ZetaRifleModes.instance(ship);if(own==null)return;
            Map<String,Object> state=own.status();String phase=String.valueOf(state.get("phase"));float held=((Number)state.get("heldLocal")).floatValue(),time=engine.getTotalElapsedTime(false),fired="FIRING".equals(phase)?((Number)state.get("firedLocal")).floatValue():-1;
            boolean gathering=Set.of("HOLDING","WAITING_FORM","PRESENTING").contains(phase);Vector2f muzzle=ZetaRifleMount.visualMuzzle(ship);BeamRiflePalette colors=BeamRiflePalette.MAGENTA;
            List<PsychoOpticalRenderer.Segment> segments=new ArrayList<>();List<PsychoOpticalRenderer.Flare> flares=new ArrayList<>();List<BeamAPI> beams=new ArrayList<>();
            for(WeaponAPI weapon:ship.getAllWeapons())if(ZetaRifleModes.SNIPER.equals(weapon.getId()))for(BeamAPI ray:weapon.getBeams()){
                if(beams.size()>=RifleChargeOptics.MAX_RAYS)break;beams.add(ray);if(ray.getBrightness()<.001f)continue;
                Vector2f from=ray.getFrom(),to=ray.getTo();float ripple=1.10f+.025f*(float)Math.sin(time*17);
                segments.add(new PsychoOpticalRenderer.Segment(from.x,from.y,to.x,to.y,ray.getWidth()*ripple,ray.getBrightness()*.95f,time*8,colors.primary,colors.inner,true));
                flares.add(new PsychoOpticalRenderer.Flare(from.x,from.y,weapon.getCurrAngle(),24+ray.getWidth()*.4f,ray.getBrightness()*.78f,colors.primary,colors.inner,false));
                if(ray.getDamageTarget()!=null)flares.add(new PsychoOpticalRenderer.Flare(to.x,to.y,time*56,36+ray.getWidth()*.5f,ray.getBrightness()*.85f,colors.primary,colors.inner,true));
            }
            int scanned=0,drawn=0;for(DamagingProjectileAPI projectile:engine.getProjectiles()){
                if(scanned++>=4096||drawn>=64)break;if(projectile.getSource()!=ship||projectile.getWeapon()==null||!ZetaRifleModes.BURST.equals(projectile.getWeapon().getId())||!(projectile instanceof MovingRay ray)||ray.isExpired())continue;
                Vector2f from=ray.getFrom(),to=ray.getTo();float width=projectile.getProjectileSpec()==null?30:projectile.getProjectileSpec().getWidth();
                segments.add(new PsychoOpticalRenderer.Segment(from.x,from.y,to.x,to.y,width*1.1f,Math.max(0,Math.min(1,ray.getBrightness()))*.8f,time*8,colors.primary,colors.inner,false));drawn++;
            }
            int ribbons=PsychoOpticalRenderer.render(segments,viewport),lights=PsychoOpticalRenderer.renderFlares(flares,viewport);
            WeaponAPI driver=ZetaRifleModes.activeDriver(ship);float angle=driver==null?ship.getFacing():driver.getCurrAngle();
            int accents=RifleChargeOptics.render(muzzle,angle,time,Math.min(ZetaRifleModes.FULL_HOLD,held),gathering,fired,0,muzzle,null,-1,beams,colors,viewport);
            opticalFacts=Map.of("phase",phase,"form",state.get("form"),"heldLocal",held,"muzzle",List.of(muzzle.x,muzzle.y),"drawnRibbons",ribbons,"drawnFlares",lights,"drawnAccents",accents,"realNativeRays",beams.size(),"scope","Real beams/projectiles and visual-only transitional charge aperture; no damage clock or synthetic shot");ship.setCustomData(OPTICS_KEY,opticalFacts);
        }
    }
    @Override public void close(){if(closed)return;closed=true;if(bound!=null&&bound.getShip().getCustomData().get(OPTICS_KEY)==opticalFacts)bound.getShip().removeCustomData(OPTICS_KEY);}
}
