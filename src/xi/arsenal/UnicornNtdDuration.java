package xi.arsenal;

import data.subsystems.Moci_NTDSubsystem;
import java.util.Objects;

/** Historical timed-NTD lease. Retained for old integrations; the current toggle never constructs it. */
@Deprecated
public final class UnicornNtdDuration implements AutoCloseable {
    public static final float ACTIVE_SECONDS=60f;
    private final Moci_NTDSubsystem subsystem;
    private final float previousBase,previousEffective;
    private boolean closed;
    public UnicornNtdDuration(Moci_NTDSubsystem subsystem){
        this.subsystem=Objects.requireNonNull(subsystem);
        previousBase=subsystem.getBaseActiveDuration();previousEffective=subsystem.getActiveDuration();
        // Moci's single-argument setter updates only its base field. MagicLib has
        // already cached that value at installation, so update the public runtime
        // duration too. Preserve elapsed time if the original has already started.
        subsystem.setActiveDuration(ACTIVE_SECONDS);
        subsystem.setActiveDuration(ACTIVE_SECONDS,true);
    }
    @Override public void close(){
        if(closed)return;closed=true;
        if(subsystem.getBaseActiveDuration()==ACTIVE_SECONDS)subsystem.setActiveDuration(previousBase);
        if(subsystem.getActiveDuration()==ACTIVE_SECONDS)subsystem.setActiveDuration(previousEffective,true);
    }
}
