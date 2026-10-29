package xi.arsenal;

import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import org.lwjgl.util.vector.Vector2f;

/** Original-canvas hand and support marks. Arm pivots remain the native slot centres. */
public record RifleBraceProfile(String id,float width,float height,
        float gunGripX,float gunGripY,float supportX,float supportY,
        float rightPalmX,float rightPalmY,float leftPalmX,float leftPalmY) {
    public static final RifleBraceProfile XI=new RifleBraceProfile("xi-rifle",102,360,
            49,134,28,94,49,134,55,140);
    public static final RifleBraceProfile PENELOPE=new RifleBraceProfile("penelope-rifle",56,260,
            26,128,16,68,26,128,29,128);
    public static RifleBraceProfile forWeapon(WeaponAPI gun){
        if(gun==null)return null;
        if(BeamRiflePalette.XI_WEAPON.equals(gun.getId()))return XI;
        if(BeamRiflePalette.PENELOPE_WEAPON.equals(gun.getId()))return PENELOPE;
        return null;
    }
    /** Forward/left point relative to actual sprite rotation centre. */
    public Vector2f local(SpriteAPI sprite,float x,float y,float centerX,float centerY){
        float cx=centerX<0?sprite.getWidth()/2:centerX,cy=centerY<0?sprite.getHeight()/2:centerY;
        return new Vector2f((height-y)*sprite.getHeight()/height-cy,cx-x*sprite.getWidth()/width);
    }
}
