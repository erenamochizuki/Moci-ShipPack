package xi.arsenal;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import data.subsystems.Moci_HotkeySubsystem;
import data.subsystems.Moci_NTDSubsystem;
import java.util.List;
import org.magiclib.subsystems.MagicSubsystem;
import org.magiclib.subsystems.MagicSubsystemsManager;

/** Exact replacement registration point; original Moci remains the animation/effect superclass. */
public final class UnicornSharedNtdHullmod extends BaseHullMod {

    /** NTD 叠加层的宿主：只要装了共享 NTD 的舰船在场就惰性安装，随战斗结束关闭。 */
    @Override public void advanceInCombat(ShipAPI ship, float amount) {
        if (ship == null || !ship.isAlive() || ship.isHulk()) return;
        com.fs.starfarer.api.combat.CombatEngineAPI engine = com.fs.starfarer.api.Global.getCombatEngine();
        if (engine == null || engine.isPaused() || engine.isCombatOver()) return;
        try { UnicornNtdEnhancement.ensure(engine); }
        catch (RuntimeException | LinkageError ignored) { }
    }
    @Override public void applyEffectsAfterShipCreation(ShipAPI ship,String id){
        // NTD 电子战的按键：MagicLib 子系统热键，LunaLib「NTD 电子战键（独角兽）」可改。只在战斗里挂，整备界面不用。
        if(Global.getCurrentState()==GameState.COMBAT&&UnicornNtdStats.supports(ship))
            Moci_HotkeySubsystem.attach(ship,UnicornNtdOverloadSkill.Hotkey.class,UnicornNtdOverloadSkill.Hotkey::new);
        List<MagicSubsystem> existing=MagicSubsystemsManager.getSubsystemsForShipCopy(ship);
        if(existing!=null&&existing.stream().anyMatch(s->s instanceof Moci_NTDSubsystem))return;
        Moci_NTDSubsystem subsystem=UnicornNtdStats.supports(ship)?new UnicornToggleNtdSubsystem(ship):new Moci_NTDSubsystem(ship);
        subsystem.setStartWithNoCharges(false);
        MagicSubsystemsManager.addSubsystemToShip(ship,subsystem);
    }
}
