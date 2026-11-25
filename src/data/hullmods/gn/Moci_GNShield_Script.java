package data.hullmods.gn;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import org.lwjgl.util.vector.Vector2f;
import org.moci.filmshield.api.FilmShield;
import org.moci.filmshield.core.FilmShieldController;
import data.scripts.gn.Moci_FilmShieldIntegration;
import data.scripts.ships.ai.Moci_GNShieldAI;
/** Legacy hullmod/listener name retained for existing content and saves. */
public class Moci_GNShield_Script extends FilmShieldController {
    public static final String KEY="Moci_GNShield_Absorb";
    private static final org.apache.log4j.Logger LOG=com.fs.starfarer.api.Global.getLogger(Moci_GNShield_Script.class);
    public Moci_GNShield_Script(ShipAPI ship) { super(ship,Moci_FilmShieldIntegration.profile()); }
    public static Moci_GNShield_Script getInstance(ShipAPI ship) {
        if(!FilmShield.isSelected(ship,"Moci_GNShield")) return null;
        FilmShieldController current=FilmShield.find(ship);
        if(current instanceof Moci_GNShield_Script) return (Moci_GNShield_Script)current;
        // 前置只允许一艘舰挂一个贴膜盾控制器。槽位被第三方提供者占了这件事不该打断整艘船的
        // hullmod 初始化，所以只记一条可查的日志并放弃本舰的贴膜盾，其余 hullmod 照常生效。
        if(current!=null) {
            LOG.warn("[Moci_GNShield] ship " + ship.getHullSpec().getHullId() + "#" + ship.getId()
                    + " is already claimed by another film shield provider (" + current.getProfile().id
                    + "); the GN shield controller is skipped for this ship");
            return null;
        }
        Moci_GNShield_Script controller=new Moci_GNShield_Script(ship);
        ship.addListener(controller);
        return controller;
    }
    public static boolean hasShield(ShipAPI ship) { return FilmShield.find(ship) instanceof Moci_GNShield_Script; }
    public static String getKey() { return KEY; }
    /** 保留本体 GN 盾的关盾装甲快照，并按显式实验开关安装全向盾感知 AI。 */
    @Override public void advance(float amount) {
        super.advance(amount);
        ShipAPI ship=getShip();
        CombatEngineAPI engine=Global.getCombatEngine();
        if(FilmShield.find(ship)==this && !ship.isStationModule() && engine!=null && !engine.isPaused()) {
            ShieldAPI shield=ship.getShield();
            // 本体非模块舰关盾时也刷新基准，避免下一次开盾恢复关盾期间的装甲损失。
            if(shield!=null&&!shield.isOn()) saveArmor();
        }
        if(!Moci_FilmShieldIntegration.omniPerceptionAIEnabled()) return;
        if(FilmShield.find(getShip())==this) Moci_GNShieldAI.ensureInstalled(getShip());
    }
    @Override protected void attachModules() {
        if(getShip().isShipWithModules()) for(ShipAPI module:getShip().getChildModulesCopy())
            if(module.isAlive()&&!module.isHulk()&&FilmShield.find(module)==null&&FilmShield.visual(module)==null
                    &&FilmShield.getLocalSelectedHullmodId(module)==null) getInstance(module);
    }
    @Override public boolean shouldRender(ShipAPI ship,int fighters) { return Moci_FilmShieldIntegration.shouldRender(ship,fighters); }
    @Override protected Hit createHit(Vector2f point,float time,float size) { return new Moci_GNShieldHitData(getShip(),point,time,size); }
    /** Legacy nested data name; the list retains its original mutable hit instances. */
    public static class Moci_GNShieldHitData extends Hit {
        public Moci_GNShieldHitData(ShipAPI ship,Vector2f point,float time,float size) { super(ship,point,time,size); }
    }
    @Override @SuppressWarnings({"rawtypes","unchecked"}) public java.util.List getHitDatas() { return super.getHitDatas(); }
}
