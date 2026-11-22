package data.scripts.util;

import com.fs.starfarer.api.combat.*;
import java.util.ArrayList;

/** 巡航驱动的启停与跃迁演出期间的操控锁。 */
public final class Moci_TravelDriveControl {
    private Moci_TravelDriveControl() { }

    private static final String LOCK_KEY = "Moci_TravelDriveControl_owners";
    private static final class Owners {
        int count, hiddenCount;
        float originalAlpha;
        boolean setByUs;
    }

    /** 每个使用者只持有自己的锁；最后一个离开时也不撤销接管前已有的锁。 */
    private static final class ControlHold {
        private final ShipAPI ship;
        private final boolean hideHull;
        private Owners owners;

        private ControlHold(ShipAPI ship, boolean hideHull) {
            this.ship = ship;
            this.hideHull = hideHull;
            if (ship.getCustomData().get(LOCK_KEY) instanceof Owners shared) owners = shared;
            else ship.setCustomData(LOCK_KEY, owners = new Owners());
            owners.count++;
            if (hideHull && owners.hiddenCount++ == 0) owners.originalAlpha = ship.getExtraAlphaMult2();
            maintain();
        }
        void maintain() {
            if (hideHull) ship.setExtraAlphaMult2(0f);
            if (!ship.controlsLocked()) {
                ship.setControlsLocked(true);
                owners.setByUs = true;
            }
        }
        void release() {
            if (owners == null) return;
            if (hideHull && --owners.hiddenCount == 0) ship.setExtraAlphaMult2(owners.originalAlpha);
            if (--owners.count == 0) {
                if (owners.setByUs) ship.setControlsLocked(false);
                ship.removeCustomData(LOCK_KEY);
            }
            owners = null;
        }
    }

    /** 只接管母舰和开始时仍挂接的直属模块；不操作已离舰的飞机，也不关掉旅行引擎本身。 */
    public static final class AnimationLock {
        private final CombatEngineAPI engine;
        private final ShipAPI root;
        private final ArrayList<ControlHold> parts = new ArrayList<>();

        public AnimationLock(CombatEngineAPI engine, ShipAPI root) {
            this(engine, root, false);
        }
        /** 旧版跳跃还会隐藏整舰；中断或模块脱离时一并还原原透明度。 */
        public static AnimationLock hiddenArrival(CombatEngineAPI engine, ShipAPI root) {
            return new AnimationLock(engine, root, true);
        }
        private AnimationLock(CombatEngineAPI engine, ShipAPI root, boolean hideHull) {
            this.engine = engine;
            this.root = root;
            take(root, hideHull);
            for (ShipAPI child : root.getChildModulesCopy()) if (attached(child)) take(child, hideHull);
        }
        private boolean attached(ShipAPI s) {
            return s != null && s.getParentStation() == root && s.isAlive() && engine.isEntityInPlay(s);
        }
        private void take(ShipAPI s, boolean hideHull) {
            parts.add(new ControlHold(s, hideHull));
            suppressWeapons(s, true);
        }
        private static void suppressWeapons(ShipAPI s, boolean firstFrame) {
            s.setHoldFireOneFrame(true);
            s.blockCommandForOneFrame(ShipCommand.FIRE);
            s.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
            for (WeaponAPI w : s.getAllWeapons()) {
                if (firstFrame && w.isFiring()) w.stopFiring();
                w.setForceNoFireOneFrame(true);
            }
        }
        public void advance() {
            for (int i = parts.size() - 1; i >= 0; i--) {
                ControlHold part = parts.get(i);
                if (part.ship != root && !attached(part.ship)) parts.remove(i).release();
                else {
                    part.maintain();
                    suppressWeapons(part.ship, false);
                }
            }
        }
        public void release() {
            for (ControlHold part : parts) part.release();
            parts.clear();
        }
    }

    public static void stopTravelDrive(ShipAPI ship) {
        if (ship == null) return;
        // 仅调用 deactivate() 不会清除引擎中的 remainingDriveDur，必须同时取消持续巡航请求。
        ship.turnOffTravelDrive();
        ShipSystemAPI drive = ship.getTravelDrive();
        if (drive != null && drive.isActive()) {
            drive.deactivate();
        }
        // 引擎会使用实际生效的修改器 ID，调用所属系统脚本的 unapply 清理属性。
        // 此处不重置冷却，也不删除其他引擎或系统施加的属性修改。
    }
}
