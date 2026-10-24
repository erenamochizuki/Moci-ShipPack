package data.hullmods;
import com.fs.starfarer.api.combat.ShipAPI;
import org.lwjgl.util.vector.Vector2f;
import org.moci.filmshield.api.FilmShield;
import org.moci.filmshield.core.FilmShieldController;
import data.scripts.Moci_FilmShieldIntegration;
/** Legacy hullmod/listener name retained for existing content and saves. */
public class Moci_GNShield_Script extends FilmShieldController {
    public static final String KEY="Moci_GNShield_Absorb";
    public Moci_GNShield_Script(ShipAPI ship) { super(ship,Moci_FilmShieldIntegration.profile()); }
    public static Moci_GNShield_Script getInstance(ShipAPI ship) {
        FilmShieldController current=FilmShield.find(ship);
        if(current instanceof Moci_GNShield_Script) return (Moci_GNShield_Script)current;
        if(current!=null) throw new IllegalStateException("Ship already uses a third-party film shield");
        Moci_GNShield_Script controller=new Moci_GNShield_Script(ship);
        ship.addListener(controller);
        return controller;
    }
    public static boolean hasShield(ShipAPI ship) { return FilmShield.find(ship) instanceof Moci_GNShield_Script; }
    public static String getKey() { return KEY; }
    @Override protected void attachModules() {
        if(getShip().isShipWithModules()) for(ShipAPI module:getShip().getChildModulesCopy())
            if(module.isAlive()&&!module.isHulk()&&FilmShield.find(module)==null&&FilmShield.visual(module)==null) getInstance(module);
    }
    @Override public boolean shouldRender(ShipAPI ship,int fighters) { return Moci_FilmShieldIntegration.shouldRender(ship,fighters); }
    @Override protected Hit createHit(Vector2f point,float time,float size) { return new Moci_GNShieldHitData(getShip(),point,time,size); }
    /** Legacy nested data name; the list retains its original mutable hit instances. */
    public static class Moci_GNShieldHitData extends Hit {
        public Moci_GNShieldHitData(ShipAPI ship,Vector2f point,float time,float size) { super(ship,point,time,size); }
    }
    @Override @SuppressWarnings({"rawtypes","unchecked"}) public java.util.List getHitDatas() { return super.getHitDatas(); }
}
