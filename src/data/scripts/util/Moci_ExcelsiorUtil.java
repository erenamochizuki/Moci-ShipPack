package data.scripts.util;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Personalities;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

public final class Moci_ExcelsiorUtil {

    private Moci_ExcelsiorUtil() {
    }

    public static List<DamagingProjectileAPI> getProjectilesWithinRange(Vector2f location, float range) {
        List<DamagingProjectileAPI> projectiles = new ArrayList<>();
        for (DamagingProjectileAPI projectile : Global.getCombatEngine().getProjectiles()) {
            if (projectile == null || projectile instanceof MissileAPI) {
                continue;
            }
            if (MathUtils.isWithinRange(projectile.getLocation(), location, range)) {
                projectiles.add(projectile);
            }
        }
        return projectiles;
    }

    public static List<MissileAPI> getMissilesWithinRange(Vector2f location, float range) {
        List<MissileAPI> missiles = new ArrayList<>();
        for (MissileAPI missile : Global.getCombatEngine().getMissiles()) {
            if (MathUtils.isWithinRange(missile.getLocation(), location, range)) {
                missiles.add(missile);
            }
        }
        return missiles;
    }

    public static List<CombatEntityAPI> getAsteroidsWithinRange(Vector2f location, float range) {
        List<CombatEntityAPI> asteroids = new ArrayList<>();
        for (CombatEntityAPI asteroid : Global.getCombatEngine().getAsteroids()) {
            if (MathUtils.isWithinRange(asteroid, location, range)) {
                asteroids.add(asteroid);
            }
        }
        return asteroids;
    }

    public static int clamp255(int value) {
        return Math.max(0, Math.min(255, value));
    }

    public static float effectiveRadius(ShipAPI ship) {
        if (ship.getSpriteAPI() == null || ship.isPiece()) {
            return ship.getCollisionRadius();
        }
        float fudgeFactor = 1.5f;
        return ((ship.getSpriteAPI().getWidth() / 2f) + (ship.getSpriteAPI().getHeight() / 2f)) * 0.5f * fudgeFactor;
    }

    public static Color interpolateColor255(Color from, Color to, float progress) {
        float clamped = Math.max(0f, Math.min(1f, progress));
        float anti = 1f - clamped;
        float[] oldComp = from.getComponents(null);
        float[] newComp = to.getComponents(null);
        return new Color(
                clamp255((int) ((oldComp[0] * 255f * anti) + (newComp[0] * 255f * clamped))),
                clamp255((int) ((oldComp[1] * 255f * anti) + (newComp[1] * 255f * clamped))),
                clamp255((int) ((oldComp[2] * 255f * anti) + (newComp[2] * 255f * clamped))),
                clamp255((int) ((oldComp[3] * 255f * anti) + (newComp[3] * 255f * clamped)))
        );
    }

    public static Color fadeColor255(Color color, float mult) {
        float[] comp = color.getComponents(null);
        return new Color(
                clamp255((int) (comp[0] * 255f)),
                clamp255((int) (comp[1] * 255f)),
                clamp255((int) (comp[2] * 255f)),
                clamp255((int) (comp[3] * 255f * mult))
        );
    }

    public static float lerp(float from, float to, float alpha) {
        return (1f - alpha) * from + alpha * to;
    }

    public static String getLessAggressivePersonality(FleetMemberAPI member, ShipAPI ship) {
        String personality = getCurrentPersonality(member, ship);
        if (personality == null) {
            return Personalities.CAUTIOUS;
        }
        switch (personality) {
            case Personalities.TIMID:
            case Personalities.CAUTIOUS:
                return Personalities.TIMID;
            case Personalities.AGGRESSIVE:
                return Personalities.STEADY;
            case Personalities.RECKLESS:
                return Personalities.AGGRESSIVE;
            default:
                return Personalities.CAUTIOUS;
        }
    }

    public static String getMoreAggressivePersonality(FleetMemberAPI member, ShipAPI ship) {
        String personality = getCurrentPersonality(member, ship);
        if (personality == null) {
            return Personalities.AGGRESSIVE;
        }
        switch (personality) {
            case Personalities.TIMID:
                return Personalities.CAUTIOUS;
            case Personalities.CAUTIOUS:
                return Personalities.STEADY;
            case Personalities.STEADY:
                return Personalities.AGGRESSIVE;
            case Personalities.AGGRESSIVE:
            case Personalities.RECKLESS:
                return Personalities.RECKLESS;
            default:
                return Personalities.AGGRESSIVE;
        }
    }

    private static String getCurrentPersonality(FleetMemberAPI member, ShipAPI ship) {
        if (ship == null) {
            return null;
        }

        boolean playerOfficer = false;
        if (member != null
                && member.getFleetData() != null
                && member.getFleetData().getFleet() != null
                && member.getFleetData().getFleet().isPlayerFleet()
                && member.getCaptain() != null
                && (!member.getCaptain().isDefault() || member.getCaptain().isPlayer())) {
            playerOfficer = true;
        }
        if (playerOfficer) {
            return null;
        }

        String personality = null;
        if (member != null && member.getCaptain() != null) {
            personality = member.getCaptain().getPersonalityAPI().getId();
        } else if (ship.getCaptain() != null) {
            personality = ship.getCaptain().getPersonalityAPI().getId();
        }

        if (ship.getShipAI() != null && ship.getShipAI().getConfig() != null && ship.getShipAI().getConfig().personalityOverride != null) {
            personality = ship.getShipAI().getConfig().personalityOverride;
        }

        return personality;
    }
}
