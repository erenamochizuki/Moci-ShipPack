package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import data.scripts.weapons.Moci_Magnum_EveryFrame;
import java.util.*;
import org.lwjgl.util.vector.Vector2f;

/** One original Magnum visual callback,with an independent hold glow and real source-muzzle mapping. */
public final class UnicornMagnumChargeEffect extends Moci_Magnum_EveryFrame implements EveryFrameWeaponEffectPluginWithAdvanceAfter,WeaponEffectPluginWithInit,AutoCloseable {
    public static final String WEAPON_ID="Moci_fw_unicorn_magnum_charge";
    private WeaponAPI bound,source;private CombatEngineAPI engine;private boolean installed,closed;private float visualClock;
    @Override public void init(WeaponAPI weapon){
        if(bound!=null&&bound!=weapon)throw new IllegalStateException("Magnum charge callback reused");
        if(!WEAPON_ID.equals(weapon.getId()))throw new IllegalArgumentException("Exact owned Magnum charge required");bound=weapon;
        weapon.setForceNoFireOneFrame(true);
        weapon.ensureClonedSpec();detach(weapon.getSpec().getTurretFireOffsets());detach(weapon.getSpec().getHardpointFireOffsets());detach(weapon.getSpec().getHiddenFireOffsets());
    }
    @Override public void advance(float amount,CombatEngineAPI engine,WeaponAPI weapon){
        if(bound==null)init(weapon);if(closed||engine==null||engine.isPaused())return;
        if(source==null)source=weapon.getShip().getAllWeapons().stream().filter(w->w.getSlot()!=null&&"ARM_R_SOURCE".equals(w.getSlot().getId())).findFirst().orElseThrow(()->new IllegalStateException("Magnum charge missing original right source"));
        UnicornMagnumModes.beforeWeapon(weapon);synchronizeMuzzle();
        super.advance(amount,engine,weapon); // exactly one native original visual advance
        visualClock+=amount;
        if(!installed){this.engine=engine;engine.addLayeredRenderingPlugin(new HoldVisual());OwnBattleResources.register(engine,this);installed=true;}
    }
    @Override public void advanceAfter(float amount,CombatEngineAPI engine,WeaponAPI weapon){
        if(closed||engine==null||engine.isPaused())return;UnicornMagnumModes.afterWeapon(weapon);synchronizeMuzzle();weapon.updateBeamFromPoints();
    }
    private void synchronizeMuzzle(){if(source==null)return;Vector2f world=source.getFirePoint(0),pivot=bound.getLocation();double a=Math.toRadians(bound.getCurrAngle());float x=world.x-pivot.x,y=world.y-pivot.y;Vector2f local=new Vector2f(x*(float)Math.cos(a)+y*(float)Math.sin(a),-x*(float)Math.sin(a)+y*(float)Math.cos(a));write(bound.getSpec().getTurretFireOffsets(),local);write(bound.getSpec().getHardpointFireOffsets(),local);write(bound.getSpec().getHiddenFireOffsets(),local);}
    private static void detach(List<Vector2f> offsets){for(int i=0;i<offsets.size();i++)offsets.set(i,new Vector2f(offsets.get(i)));}
    private static void write(List<Vector2f> offsets,Vector2f value){for(Vector2f offset:offsets)offset.set(value);}
    private final class HoldVisual extends BaseCombatLayeredRenderingPlugin {
        final UnicornMagnumPulseEffect.Masks masks=new UnicornMagnumPulseEffect.Masks();
        @Override public EnumSet<CombatEngineLayers> getActiveLayers(){return EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);}
        @Override public float getRenderRadius(){return 350;}
        @Override public boolean isExpired(){return closed||!bound.getShip().isAlive()||bound.getShip().wasRemoved()||Global.getCombatEngine()!=engine;}
        @Override public void advance(float amount){if(entity!=null)entity.getLocation().set(bound.getLocation());}
        @Override public void render(CombatEngineLayers layer,ViewportAPI viewport){float ratio=UnicornMagnumPulseEffect.clamp(UnicornMagnumModes.heldRatio(bound));if(isExpired()||ratio<=0||source==null||bound.getShip().isPhased())return;Vector2f at=source.getFirePoint(0);if(!viewport.isNearViewport(at,180))return;try(UnicornMagnumPulseEffect.Masks.State guard=new UnicornMagnumPulseEffect.Masks.State()){masks.muzzle(at,visualClock*80,UnicornMagnumPulseEffect.clamp(viewport.getAlphaMult())*(.3f+.7f*ratio),.55f+.65f*ratio);}}
    }
    @Override public void close(){closed=true;}
}
