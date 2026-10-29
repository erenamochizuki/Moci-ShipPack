package xi.arsenal;

import java.awt.Color;

/** Colours belong only to the two own right-hand rifles; torso mega cannons never use this palette. */
public final class BeamRiflePalette {
    public static final String XI_WEAPON = "Moci_fw_xi_beam_rifle";
    public static final String PENELOPE_WEAPON = "Moci_fw_penelope_beam_rifle";
    public static final String XI_PROJECTILE = "Moci_fw_xi_beam_rifle_shot";
    public static final String PENELOPE_PROJECTILE = "Moci_fw_penelope_beam_rifle_shot";
    public static final BeamRiflePalette GREEN = new BeamRiflePalette(XI_PROJECTILE,
            new Color(72, 255, 116), new Color(190, 255, 218), new Color(95, 255, 190));
    public static final BeamRiflePalette MAGENTA = new BeamRiflePalette(PENELOPE_PROJECTILE,
            new Color(255, 48, 174), new Color(255, 190, 232), new Color(221, 105, 255));

    public final String projectileId;
    public final Color primary, inner, spark;

    private BeamRiflePalette(String projectileId, Color primary, Color inner, Color spark) {
        this.projectileId = projectileId;
        this.primary = primary; this.inner = inner; this.spark = spark;
    }
    public static boolean isOwnRifle(String weaponId) {
        return XI_WEAPON.equals(weaponId) || PENELOPE_WEAPON.equals(weaponId);
    }
    public static BeamRiflePalette forWeapon(String weaponId) {
        if (XI_WEAPON.equals(weaponId)) return GREEN;
        if (PENELOPE_WEAPON.equals(weaponId)) return MAGENTA;
        throw new IllegalArgumentException("Unknown own rifle palette: " + weaponId);
    }
    public static BeamRiflePalette forProjectile(String projectileId) {
        if (XI_PROJECTILE.equals(projectileId)) return GREEN;
        if (PENELOPE_PROJECTILE.equals(projectileId)) return MAGENTA;
        throw new IllegalArgumentException("Unknown own rifle projectile palette: " + projectileId);
    }
    public static Color alpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }
}
