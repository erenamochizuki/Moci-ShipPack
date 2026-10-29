package xi.arsenal;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;

/** Pure bounded probability/CPA helper. Inject randomness; each live attack gets at most one roll. */
public final class NewtypeThreatRolls {
    public static final int MAX_ROLLED_ATTACKS=1024;
    public static final float MISSILE_CHANCE=.20f,PROJECTILE_CHANCE=.12f,NEAR_RADIUS=900f,CPA_SECONDS=1.5f;
    public enum Decision { NOT_ELIGIBLE, ALREADY_ROLLED, CAPACITY, MISS, TRIGGER }
    private final Set<Object> rolled=Collections.newSetFromMap(new IdentityHashMap<>());
    private final DoubleSupplier random;
    private long rolls,triggers,capacitySkips;
    public NewtypeThreatRolls(){this(Math::random);}
    public NewtypeThreatRolls(DoubleSupplier random){this.random=Objects.requireNonNull(random);}
    public Decision consider(Object identity,int owner,int localOwner,boolean live,boolean damaging,boolean threatening,boolean missile){
        if(identity==null||!live||!damaging||!threatening||owner<0||owner>1||localOwner<0||localOwner>1||owner==localOwner)return Decision.NOT_ELIGIBLE;
        if(rolled.contains(identity))return Decision.ALREADY_ROLLED;
        if(rolled.size()>=MAX_ROLLED_ATTACKS){capacitySkips++;return Decision.CAPACITY;}
        rolled.add(identity);double chance=random.getAsDouble();
        if(!Double.isFinite(chance)||chance<0||chance>=1)throw new IllegalStateException("Threat random value must be in [0,1)");
        rolls++;boolean trigger=chance<(missile?MISSILE_CHANCE:PROJECTILE_CHANCE);if(trigger)triggers++;
        return trigger?Decision.TRIGGER:Decision.MISS;
    }
    public void prune(Predicate<Object> alive){rolled.removeIf(key->!alive.test(key));}
    public void clear(){rolled.clear();}
    public int tracked(){return rolled.size();}
    public long rolls(){return rolls;}
    public long triggers(){return triggers;}
    public long capacitySkips(){return capacitySkips;}
    public static boolean collisionLikely(float dx,float dy,float relativeVx,float relativeVy,float combinedRadius){
        if(!Float.isFinite(dx)||!Float.isFinite(dy)||!Float.isFinite(relativeVx)||!Float.isFinite(relativeVy)||!Float.isFinite(combinedRadius)||combinedRadius<0)return false;
        double distance=dx*(double)dx+dy*(double)dy;
        if(distance>NEAR_RADIUS*NEAR_RADIUS)return false;
        double closing=dx*(double)relativeVx+dy*(double)relativeVy,speed=relativeVx*(double)relativeVx+relativeVy*(double)relativeVy;
        if(closing>=0||speed<1e-6)return false;
        double time=-closing/speed;if(time>CPA_SECONDS)return false;
        double x=dx+relativeVx*time,y=dy+relativeVy*time;
        return x*x+y*y<=combinedRadius*(double)combinedRadius;
    }
}
