package data.shipsystems.scripts;

import java.util.IdentityHashMap;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;

/** 将深潜可用武器接到原版自动瞄准流程，不接管玩家操作或强制发射。 */
public final class Moci_DeepDiveWeaponControl implements AdvanceableListener {
    private final ShipAPI ship;
    private final Map<WeaponGroupAPI, Boolean> previousAutofire = new IdentityHashMap<>();

    private Moci_DeepDiveWeaponControl(ShipAPI ship) {
        this.ship = ship;
    }

    public static boolean isTdd(ShipAPI ship) {
        return ship != null && ship.getHullSpec() != null
                && "Moci_TDD_1".equals(ship.getHullSpec().getBaseHullId());
    }

    public static void attach(ShipAPI ship) {
        if (isTdd(ship) && !ship.hasListenerOfClass(Moci_DeepDiveWeaponControl.class)) {
            ship.addListener(new Moci_DeepDiveWeaponControl(ship));
        }
    }

    /** 与 AI、潜射登记共用同一资格判定，混编武器组也不会让 source 偷跑。 */
    public static void suppressSurfaceWeapons(ShipAPI ship) {
        if (!isTdd(ship)) {
            return;
        }
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (!weapon.isDecorative() && !Moci_DeepDiveMissileManager.isAllowedWeapon(weapon)) {
                weapon.setForceNoFireOneFrame(true);
            }
        }
    }

    @Override
    public void advance(float amount) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || engine.isPaused()) {
            return;
        }
        // 舰船创建也会用于装配预览；等实体实际参战后再注册战斗插件。
        if (engine.isEntityInPlay(ship) && !engine.isCombatOver()) {
            Moci_DeepDiveEnginePlugin.getInstance(engine);
        }
        boolean diving = ship.isAlive() && !ship.isHulk() && engine.isEntityInPlay(ship)
                && !engine.isCombatOver() && Moci_DeepDive.isDeepDiveActive(ship);
        if (diving) {
            suppressSurfaceWeapons(ship);
        }
        if (!diving || ship.getShipAI() == null) {
            restoreGroups();
            return;
        }

        // 只打开包含可潜射导弹的武器组。原版/第三方 AutofireAIPlugin 仍决定
        // 目标、瞄准、友军遮挡、弹药节约和 shouldFire；不会调用 setForceFireOneFrame。
        for (WeaponGroupAPI group : ship.getWeaponGroupsCopy()) {
            boolean allowed = false;
            for (WeaponAPI weapon : group.getWeaponsCopy()) {
                if (Moci_DeepDiveMissileManager.isAllowedWeapon(weapon)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                continue;
            }
            if (!previousAutofire.containsKey(group)) {
                previousAutofire.put(group, group.isAutofiring());
            }
            if (!group.isAutofiring()) {
                group.toggleOn();
            }
        }
    }

    private void restoreGroups() {
        for (Map.Entry<WeaponGroupAPI, Boolean> entry : previousAutofire.entrySet()) {
            if (entry.getValue()) {
                if (!entry.getKey().isAutofiring()) {
                    entry.getKey().toggleOn();
                }
            } else if (entry.getKey().isAutofiring()) {
                entry.getKey().toggleOff();
            }
        }
        previousAutofire.clear();
    }
}
