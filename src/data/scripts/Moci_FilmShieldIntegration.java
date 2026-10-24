package data.scripts;
import java.awt.Color;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;
import org.moci.filmshield.api.ShieldProfile;
/** Moci-specific values/settings stay in the content mod; mechanics and presentation live in the prerequisite. */
public final class Moci_FilmShieldIntegration {
    private static final ShieldProfile PROFILE=ShieldProfile.builder("moci_gnshield_v1")
        .fluxPerDamage(1f).fragmentationMultiplier(0.25f).followParent(true).showStatus(true)
        .modifierId("Moci_GNShield_Absorb").suppressSpecLessModuleProjectiles(true)
        .labels(data.scripts.util.Moci_TextLoader.getText("Moci_GNShieldStatus","status.main_title"),
                data.scripts.util.Moci_TextLoader.getText("Moci_GNShieldStatus","status.active"),
                data.scripts.util.Moci_TextLoader.getText("Moci_GNShieldStatus","status.inactive"),
                data.scripts.util.Moci_TextLoader.getText("Moci_GNShieldUI","status.active","GN: ON"),
                data.scripts.util.Moci_TextLoader.getText("Moci_GNShieldUI","status.inactive","GN: OFF"))
        .colors(new Color(55,145,255),new Color(255,20,45)).build();
    private Moci_FilmShieldIntegration() {}
    public static ShieldProfile profile() { return PROFILE; }
    public static boolean shouldRender(ShipAPI ship,int fighters) {
        if(!ship.isFighter()) return true;
        boolean skip=bool("Moci_gnShieldSkipFighter",true),limit=bool("Moci_gnShieldEnableLimit",false);
        return !ship.isFighter()||(!skip&&(!limit||fighters<=integer("Moci_gnShieldFighterLimit",150)));
    }
    private static boolean bool(String key,boolean def) {
        try { if(Global.getSettings().getModManager().isModEnabled("lunalib")) return lunalib.lunaSettings.LunaSettings.getBoolean("moci_shippack",key); }
        catch(Exception | LinkageError ignored) { }
        return def;
    }
    private static int integer(String key,int def) {
        try { if(Global.getSettings().getModManager().isModEnabled("lunalib")) return lunalib.lunaSettings.LunaSettings.getInt("moci_shippack",key); }
        catch(Throwable ignored) { }
        return def;
    }
}
