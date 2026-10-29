package xi.arsenal;

import org.lwjgl.util.vector.Vector2f;

/** A rigid group per arm: native weapon pivots are never redefined. */
public final class RifleBraceNativeKinematics {
    /** The two complete arm/shoulder groups first clear the head together. */
    public static final float SHARED_FORWARD=13f;
    private RifleBraceNativeKinematics() {}
    public record Solution(boolean reachable,float bodyYaw,float leftAngle,float rightForward,float leftForward,
            Vector2f rightOrigin,Vector2f leftOrigin,float supportError) {}

    public static Solution solve(Vector2f body,Vector2f rightOrigin,Vector2f leftOrigin,
            Vector2f leftPalm,Vector2f gunSupport,float facing,float aim,float restLeft) {
        float radius=leftPalm.length();
        if(!Float.isFinite(radius)||radius<1)return empty(rightOrigin,leftOrigin);
        float rel=BimanualRifleKinematics.shortest(facing,aim);
        float preferred=Math.max(-85,Math.min(-20,rel-55));
        Vector2f support=BimanualRifleKinematics.rotate(gunSupport,aim);
        Solution best=null;double scoreBest=Double.POSITIVE_INFINITY;
        for(int step=0;step<=180;step++){
            float yaw=step==0?preferred:preferred+((step&1)==1?1:-1)*((step+1)/2);
            if(yaw< -100||yaw>35)continue;
            float rightTurn=BimanualRifleKinematics.shortest(facing+yaw,aim);
            if(rightTurn<0||rightTurn>60)continue;
            // "Forward" is the posed torso's axis, not the unturned hull axis.
            Vector2f forward=BimanualRifleKinematics.rotate(new Vector2f(1,0),facing+yaw);
            Vector2f r=BimanualRifleKinematics.orbit(rightOrigin,body,yaw);
            Vector2f l=BimanualRifleKinematics.orbit(leftOrigin,body,yaw);
            // Give the support hand a little extra clearance from Xi's large torso.
            for(int leftForward=4;leftForward<=8;leftForward+=2){
                Vector2f left=new Vector2f(l.x+forward.x*leftForward,l.y+forward.y*leftForward);
                Vector2f delta=Vector2f.sub(Vector2f.add(r,support,null),left,null);
                double projection=delta.x*forward.x+delta.y*forward.y;
                double disc=projection*projection-(delta.lengthSquared()-radius*radius);
                if(disc<0)continue;
                for(int branch:new int[]{-1,1}){
                    float rightForward=(float)(-projection+branch*Math.sqrt(disc));
                    // The common forward move already gives clearance; keep the
                    // right-only adjustment small so its shoulder stays attached.
                    if(rightForward<0||rightForward>7)continue;
                    Vector2f right=new Vector2f(r.x+forward.x*rightForward,r.y+forward.y*rightForward);
                    Vector2f hand=Vector2f.add(right,support,null),toHand=Vector2f.sub(hand,left,null);
                    float leftAngle=BimanualRifleKinematics.angle(toHand)-BimanualRifleKinematics.angle(leftPalm);
                    float leftTurn=BimanualRifleKinematics.shortest(restLeft+yaw,leftAngle);
                    if(leftTurn< -55||leftTurn>0)continue;
                    Vector2f inward=BimanualRifleKinematics.rotate(toHand,-(facing+yaw));
                    if(inward.y>=0)continue;
                    // Prefer clearance in front of the torso, but only while the
                    // shoulder/arm/gun group stays within its bounded movement.
                    Vector2f fromBody=BimanualRifleKinematics.rotate(Vector2f.sub(hand,body,null),-aim);
                    double score=3*(yaw-preferred)*(yaw-preferred)+.25*rightForward*rightForward+2*leftForward*leftForward
                            +.04*leftTurn*leftTurn+Math.max(0,10-fromBody.x)*Math.max(0,10-fromBody.x);
                    if(score>=scoreBest)continue;
                    Vector2f shared=new Vector2f(forward.x*SHARED_FORWARD,forward.y*SHARED_FORWARD);
                    Vector2f.add(right,shared,right);Vector2f.add(left,shared,left);
                    best=new Solution(true,yaw,leftAngle,rightForward+SHARED_FORWARD,leftForward+SHARED_FORWARD,right,left,
                            Math.abs(toHand.length()-radius));scoreBest=score;
                }
            }
        }
        return best==null?empty(rightOrigin,leftOrigin):best;
    }
    private static Solution empty(Vector2f r,Vector2f l){return new Solution(false,0,0,0,0,new Vector2f(r),new Vector2f(l),Float.POSITIVE_INFINITY);}
}
