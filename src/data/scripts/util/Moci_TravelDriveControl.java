package data.scripts.util;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;

/** 同时清除舰船的持续巡航请求，并关闭当前运行的巡航驱动。 */
public final class Moci_TravelDriveControl {
    private Moci_TravelDriveControl() { }

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
