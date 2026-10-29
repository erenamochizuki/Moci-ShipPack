package xi.arsenal;

import java.util.Objects;
import org.lwjgl.util.vector.Vector2f;

/** Two fixed-length arms and one rigid rifle. No limb scale or extension exists.
 * The right wrist must lie on both its shoulder circle and the left shoulder
 * circle shifted by the rifle's measured grip-to-support vector.
 */
public final class BimanualRifleKinematics {
    private BimanualRifleKinematics() { }
    public record Solution(boolean reachable,float bodyYaw,float rightAngle,float leftAngle,
            Vector2f rightShoulder,Vector2f leftShoulder,Vector2f rightHand,
            Vector2f leftHand,Vector2f gunOrigin,float rightError,float leftError) { }

    /** All anchors are rendered world units; arm-local vectors use forward/left axes. */
    public static Solution solve(Vector2f body,Vector2f restRightShoulder,Vector2f restLeftShoulder,
            Vector2f rightPalm,Vector2f leftPalm,Vector2f gunGrip,Vector2f gunSupport,
            float facing,float aim,float preferredYaw,float previousRightAngle) {
        for(Vector2f p:new Vector2f[]{body,restRightShoulder,restLeftShoulder,rightPalm,leftPalm,gunGrip,gunSupport}) {
            Objects.requireNonNull(p);if(!Float.isFinite(p.x)||!Float.isFinite(p.y))throw new IllegalArgumentException("Finite brace anchors required");
        }
        if(!Float.isFinite(facing)||!Float.isFinite(aim)||!Float.isFinite(preferredYaw))throw new IllegalArgumentException("Finite brace angles required");
        float rr=rightPalm.length(),lr=leftPalm.length();
        if(rr<1||lr<1)throw new IllegalArgumentException("Anatomical shoulder-to-palm radii are required");
        Vector2f separation=rotate(Vector2f.sub(gunSupport,gunGrip,null),aim);
        boolean forwardPalms=rightPalm.x>0 && leftPalm.x>0;
        float preferred=forwardPalms?Math.max(-85,Math.min(-20,shortest(facing,aim)-60)):Math.max(-110,Math.min(70,preferredYaw));
        Solution best=null;double bestScore=Double.POSITIVE_INFINITY;
        // Bounded fitting permits differently sized mecha to use the same solver.
        // Prefer the requested torso angle; only deviate when geometry demands it.
        for(int step=0;step<=180;step++) {
            float yaw=step==0?preferred:preferred+((step&1)==1?1:-1)*((step+1)/2);
            if(yaw< -110||yaw>70)continue;
            if(!forwardPalms && 25*(yaw-preferred)*(yaw-preferred)>bestScore)continue;
            Vector2f originalR=orbit(restRightShoulder,body,yaw),originalL=orbit(restLeftShoulder,body,yaw);
            for(int forwardR=0;forwardR<=(forwardPalms?16:0);forwardR+=4)
            for(int forwardL=0;forwardL<=(forwardPalms?4:0);forwardL+=2){
            Vector2f r=Vector2f.add(originalR,rotate(new Vector2f(forwardR,0),facing),null);
            Vector2f l=Vector2f.add(originalL,rotate(new Vector2f(forwardL,0),facing),null);
            Vector2f c=Vector2f.sub(l,separation,null),delta=Vector2f.sub(c,r,null);
            float d=delta.length();if(d<.0001f||d>rr+lr+.0001f||d<Math.abs(rr-lr)-.0001f)continue;
            float along=(rr*rr-lr*lr+d*d)/(2*d),height=(float)Math.sqrt(Math.max(0,rr*rr-along*along));
            float ux=delta.x/d,uy=delta.y/d;
            for(int branch:new int[]{-1,1}) {
                Vector2f handR=new Vector2f(r.x+ux*along-branch*uy*height,r.y+uy*along+branch*ux*height);
                Vector2f handL=Vector2f.add(handR,separation,null);
                float ra=angle(Vector2f.sub(handR,r,null))-angle(rightPalm);
                float la=angle(Vector2f.sub(handL,l,null))-angle(leftPalm);
                if(forwardPalms){
                    // Cross-body movement must stay within a conservative shoulder arc.
                    // Do not accept the other circle intersection merely because
                    // its hands touch: that branch folds the forearms through the torso.
                    float rightTurn=shortest(facing+yaw,ra),leftTurn=shortest(facing+yaw,la);
                    if(rightTurn<0||rightTurn>55||leftTurn< -55||leftTurn>0)continue;
                    // Distal palms must extend inward across the torso; this is
                    // a geometric check, not proof of the sprite's root orientation.
                    Vector2f rightDirection=rotate(Vector2f.sub(handR,r,null),-(facing+yaw));
                    Vector2f leftDirection=rotate(Vector2f.sub(handL,l,null),-(facing+yaw));
                    if(rightDirection.y<=0||leftDirection.y>=0)continue;
                }
                Vector2f bodyRelative=rotate(Vector2f.sub(handR,body,null),-aim);
                if(forwardPalms&&bodyRelative.x< -4)continue;
                Vector2f wanted=Vector2f.add(body,rotate(new Vector2f(20,-16),aim),null);
                Vector2f error=Vector2f.sub(handR,wanted,null);
                double score=error.lengthSquared()+(forwardPalms?4:25)*(yaw-preferred)*(yaw-preferred)+.4*forwardR*forwardR+2*forwardL*forwardL;
                if(Float.isFinite(previousRightAngle)){float turn=shortest(previousRightAngle,ra);score+=.02*turn*turn;}
                if(score>=bestScore)continue;
                Vector2f origin=Vector2f.sub(handR,rotate(gunGrip,aim),null);
                float er=Math.abs(Vector2f.sub(handR,r,null).length()-rr),el=Math.abs(Vector2f.sub(handL,l,null).length()-lr);
                best=new Solution(true,yaw,ra,la,r,l,handR,handL,origin,er,el);bestScore=score;
            }
            }
        }
        if(best!=null)return best;
        return new Solution(false,preferred,facing,facing,new Vector2f(restRightShoulder),new Vector2f(restLeftShoulder),
                new Vector2f(),new Vector2f(),new Vector2f(),Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY);
    }
    public static Vector2f rotate(Vector2f point,float degrees){double a=Math.toRadians(degrees);return new Vector2f((float)(point.x*Math.cos(a)-point.y*Math.sin(a)),(float)(point.x*Math.sin(a)+point.y*Math.cos(a)));}
    public static Vector2f orbit(Vector2f point,Vector2f pivot,float degrees){return Vector2f.add(pivot,rotate(Vector2f.sub(point,pivot,null),degrees),null);}
    public static float angle(Vector2f p){return (float)Math.toDegrees(Math.atan2(p.y,p.x));}
    public static float shortest(float from,float to){return ((to-from+540)%360+360)%360-180;}
}
