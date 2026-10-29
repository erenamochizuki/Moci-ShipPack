package xi.arsenal;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.Global;
import org.lazywizard.lazylib.ui.LazyFont;
import org.lwjgl.opengl.GL11;
import java.awt.Color;
import java.util.Locale;
/** Draw in the real UI render callback; no advance-time drawing or MagicUI global cache. */
final class HiNuHandHud {
    private LazyFont.DrawableString text;
    void render(ShipAPI ship,HiNuPsychoframeCharge charge,HiNuHandGroups groups,float remaining,float heldCharge,HiNuHandCannon.Phase phase,boolean spent,String reason){
        if(text==null)try{text=LazyFont.loadFont("graphics/fonts/insignia15LTaa.fnt").createText();}catch(Exception e){throw new IllegalStateException("Hi-Nu HUD font unavailable",e);}
        float value=((Number)charge.status().get("progress")).floatValue();
        boolean charging=phase==HiNuHandCannon.Phase.CHARGING||phase==HiNuHandCannon.Phase.RELEASING;
        float fill=charging?heldCharge/HiNuHandCannon.CHARGE_SECONDS:remaining>0?spent?0:1:value/100f;
        int matrix=GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glPushMatrix();GL11.glLoadIdentity();
        GL11.glOrtho(0,Global.getSettings().getScreenWidth(),0,Global.getSettings().getScreenHeight(),-1,1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glPushMatrix();GL11.glLoadIdentity();
        try{
            GL11.glDisable(GL11.GL_TEXTURE_2D);GL11.glDisable(GL11.GL_ALPHA_TEST);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glEnable(GL11.GL_BLEND);GL11.glBlendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA);
            rect(24,238,330,88,new Color(7,12,22,205));rect(36,275,306,12,new Color(48,46,65,240));rect(37,276,Math.max(1,304*Math.max(0,Math.min(1,fill))),10,new Color(175,133,255,255));
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            line(charging?"决战重炮蓄力中":remaining>0?"决战重炮 · 仅一发":"精神感应框架激活中",36,314,new Color(210,189,255));
            line(charging?String.format(Locale.ROOT,"%.1f / 3 秒",heldCharge):remaining>0?spent?"已释放":"1 / 1":String.format(Locale.ROOT,"%.1f / 100",value),230,314,Color.WHITE);
            int index=((Number)groups.status().get("cannonGroupIndex")).intValue();
            line(index>=0?"武器组 "+(index+1)+" · 呼唤重炮武装":reason,36,262,new Color(195,218,226));
        }finally{
            GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glPopMatrix();GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glPopMatrix();GL11.glMatrixMode(matrix);GL11.glPopAttrib();
        }
    }
    private void line(String value,float x,float y,Color color){text.setText(value);text.setMaxWidth(308);text.setMaxHeight(22);text.setBaseColor(color);text.draw(x,y);}
    private static void rect(float x,float y,float w,float h,Color color){GL11.glColor4ub((byte)color.getRed(),(byte)color.getGreen(),(byte)color.getBlue(),(byte)color.getAlpha());GL11.glBegin(GL11.GL_QUADS);GL11.glVertex2f(x,y);GL11.glVertex2f(x+w,y);GL11.glVertex2f(x+w,y+h);GL11.glVertex2f(x,y+h);GL11.glEnd();}
}
