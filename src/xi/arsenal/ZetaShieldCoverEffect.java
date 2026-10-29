package xi.arsenal;

import data.scripts.weapons.Moci_MechTransformController;

import com.fs.starfarer.api.combat.*;
import data.scripts.weapons.Moci_MSZ006ShieldCoverEffect;
import java.util.*;
import java.util.function.Function;
import org.lwjgl.util.vector.Vector2f;

/** Z-only single physical shield presentation.
 * Original behavior remains in Moci_MSZ006ShieldCoverEffect, advanced exactly once.
 * New: suppress its duplicate decorative cover and reconcile the real module with
 * the SAME pose shoulder anchor. No collision, health, damage or counter weapon is removed. */
public final class ZetaShieldCoverEffect implements EveryFrameWeaponEffectPluginWithAdvanceAfter {
    public static final String KEY="xi.arsenal.zeta.singleShield",WEAPON="Moci_MSZ_006_SHIELD_COVER";
    private static final String OWNER=KEY+".owner";
    private final EveryFrameWeaponEffectPlugin original;
    private final Function<ShipAPI,Vector2f> armTranslation;
    private WeaponAPI bound;private CombatEngineAPI boundEngine;private long originalCalls,lateCalls;private String reason="等待原生盾盖";
    public ZetaShieldCoverEffect(){this(new Moci_MSZ006ShieldCoverEffect(),ZetaShieldCoverEffect::leftTranslation);}
    /** Test-only dependency boundary; production always delegates to the exact original callback above. */
    ZetaShieldCoverEffect(EveryFrameWeaponEffectPlugin original,Function<ShipAPI,Vector2f> armTranslation){this.original=Objects.requireNonNull(original);this.armTranslation=Objects.requireNonNull(armTranslation);}
    private static Vector2f leftTranslation(ShipAPI ship){return ZetaTetherPose.blocksGuard(ship)?ZetaTetherPose.armTranslation(ship,true):RifleFiringPose.armTranslation(ship,true);}
    @Override public void advance(float amount,CombatEngineAPI engine,WeaponAPI weapon){
        if(bound==null)bound=weapon;else if(bound!=weapon)throw new IllegalStateException("Zeta shield cover callback shared between installed weapons");
        boundEngine=engine;
        if(weapon!=null&&weapon.getShip()!=null&&WEAPON.equals(weapon.getId())&&weapon.getSlot()!=null&&"SHIELD_L_COVER".equals(weapon.getSlot().getId())&&ZetaMobility.supports(weapon.getShip())){
            ShipAPI ship=weapon.getShip();Object owner=ship.getCustomData().get(OWNER);if(owner!=null&&owner!=this)throw new IllegalStateException("Another owner occupies the installed Zeta shield cover");ship.setCustomData(OWNER,this);
        }
        original.advance(amount,engine,weapon);originalCalls++;
    }
    @Override public void advanceAfter(float amount,CombatEngineAPI engine,WeaponAPI cover){
        reconcileInstalled(engine,cover);
    }
    /** Pure late boundary for the sole Actor after its pose clock. No original callback or time is advanced. */
    public static void reconcile(ShipAPI ship){if(ship==null)return;Object owner=ship.getCustomData().get(OWNER);if(owner instanceof ZetaShieldCoverEffect cover&&cover.bound!=null&&cover.bound.getShip()==ship)cover.reconcileInstalled(cover.boundEngine,cover.bound);}
    private void reconcileInstalled(CombatEngineAPI engine,WeaponAPI cover){
        if(engine==null||engine.isPaused()||cover==null||cover.getShip()==null)return;
        ShipAPI ship=cover.getShip();if(!WEAPON.equals(cover.getId())||cover.getSlot()==null||!"SHIELD_L_COVER".equals(cover.getSlot().getId())||!ZetaMobility.supports(ship)||!ship.isAlive()||ship.isHulk())return;
        lateCalls++;ShipAPI module=null;WeaponAPI arm=null;
        for(ShipAPI child:ship.getChildModulesCopy())if(child!=null&&child.getParentStation()==ship&&child.getStationSlot()!=null&&"SHIELD_L".equals(child.getStationSlot().getId())&&child.isAlive()&&!child.isHulk()&&child.getHitpoints()>0&&child.getHullSpec()!=null&&"Moci_MSZ_006_SHIELD_L".equals(child.getHullSpec().getHullId())){module=child;break;}
        for(WeaponAPI candidate:ship.getAllWeapons())if(candidate.getSlot()!=null&&"ARM_L".equals(candidate.getSlot().getId())){arm=candidate;break;}
        if(module==null){reason="没有本机实体左盾，保留原回调结果";publish(ship,null,new Vector2f());return;}
        if(cover.getAnimation()==null||cover.getAnimation().getNumFrames()<2)throw new IllegalStateException("Original Zeta shield cover blank frame1 is required");
        cover.getAnimation().pause();cover.getAnimation().setFrame(1);
        Vector2f shift=new Vector2f();
        if(!nonMs(ship)&&arm!=null){
            shift=Objects.requireNonNull(armTranslation.apply(ship),"Pose shoulder translation");
            if(!Float.isFinite(shift.x)||!Float.isFinite(shift.y))throw new IllegalStateException("Nonfinite Zeta physical shield shoulder anchor");
            // The original callback already positions this module at ARM_L. Reuse the
            // pose's actual world translation once, moving the real collision body too.
            module.getLocation().set(arm.getLocation().x+shift.x,arm.getLocation().y+shift.y);module.setFacing(arm.getCurrAngle());
            reason="仅实体左盾显示；实际肩锚点与碰撞位置一致";
        }else reason="WR/变形保留原生后方10收纳；装饰盖保持空帧";
        publish(ship,module,shift);
    }
    private static boolean nonMs(ShipAPI ship){Map<String,Object> data=ship.getCustomData();if(Boolean.TRUE.equals(data.get(Moci_MechTransformController.CUSTOM_PARTS_HIDDEN))||Boolean.TRUE.equals(data.get(Moci_MechTransformController.CUSTOM_TRANSFORMED)))return true;Object state=data.get(Moci_MechTransformController.CUSTOM_TRANSFORM_STATE);return state!=null&&!"NORMAL".equals(String.valueOf(state));}
    private void publish(ShipAPI ship,ShipAPI module,Vector2f shift){Map<String,Object> row=new LinkedHashMap<>();row.put("originalCallback",original.getClass().getName());row.put("originalAdvances",originalCalls);row.put("lateReconciliations",lateCalls);row.put("reason",reason);row.put("physicalShield",module==null?"":Objects.toString(module.getId(),""));row.put("armTranslation",List.of(shift.x,shift.y));row.put("coverBlankFrame",1);if(module!=null){row.put("physicalPosition",List.of(module.getLocation().x,module.getLocation().y));row.put("physicalFacing",module.getFacing());}ship.setCustomData(KEY,Collections.unmodifiableMap(row));}
}
