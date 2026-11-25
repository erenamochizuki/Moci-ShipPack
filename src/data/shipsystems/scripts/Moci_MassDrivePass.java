package data.shipsystems.scripts;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.combat.CombatViewport;
import com.fs.starfarer.combat.entities.Ship;
import data.scripts.util.Moci_Util;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.Random;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.util.vector.Vector2f;

final class Moci_MassDrivePass extends BaseCombatLayeredRenderingPlugin implements Moci_MassDrive.Pass {
    private final String eventKey;
    private final CombatEngineAPI engine;
    final ShipAPI ship;
    final boolean leaving;
    final float seed;
    final float duration;
    final Vector2f position = new Vector2f();
    final Vector2f gate = new Vector2f();
    private final Vector2f velocity = new Vector2f();
    private final Moci_MassDriveImage image = new Moci_MassDriveImage();
    float facing;
    float age;
    float trailAge;
    private float afterRemoval;
    private boolean started;
    private boolean expired;
    private boolean masked;
    private boolean removed;
    private boolean visible;
    private boolean stopping;
    private boolean arrivalPeakFrame;
    private boolean peakWasDrawn;
    private boolean systemEnded;
    private float stopAge;
    final ArrayList<Ship> modules = new ArrayList<>();
    float hullLength, hullWidth, hullNose, stampSize;

    Moci_MassDrivePass(CombatEngineAPI engine, ShipAPI ship, int sequence) {
        this(engine, ship, sequence, Moci_MassDrive.EVENT);
    }

    Moci_MassDrivePass(CombatEngineAPI engine, ShipAPI ship, int sequence, String eventKey) {
        super(ship.getLayer());
        this.eventKey=eventKey;
        this.engine = engine;
        this.ship = ship;
        leaving = ship.isRetreating();
        Random random = new Random(31L * ship.getId().hashCode() + sequence * 100003L);
        seed = random.nextFloat() * 173f;
        duration = 0.84f + 0.34f * random.nextFloat();
        age = leaving ? -0.82f : -0.36f;
        position.set(ship.getLocation());
    }

    @Override public boolean isLeaving() { return leaving; }

    @Override public void stop() {
        systemEnded = true;
        if (ship.isHulk() || leaving && !started) cancel();
        else if (leaving) stopping = true;
    }

    @Override public void cancel() {
        restore();
        expired = true;
    }

    private void restore() {
        if (masked) {
            ship.setDoNotRender(false);
            for (Ship module : modules) module.setDoNotRender(false);
            masked = false;
        }
    }

    private void readPose() {
        position.set(ship.getLocation());
        velocity.set(ship.getVelocity());
        facing = ship.getFacing();
        if (!started) {
            gate.set(position.x + velocity.x * 0.36f * duration, position.y + velocity.y * 0.36f * duration);
            started = true;
        }
    }

    private void playWarpSound(Vector2f source) {
        ShipAPI player = engine.getPlayerShip();
        int side = player == null || player.getOwner() == 100 ? 0 : player.getOwner();
        FogOfWarAPI fog = engine.getFogOfWar(side);
        if (ship.getOwner() == side || fog == null || fog.isVisible(source)) {
            Global.getSoundPlayer().playSound("moci_sys_warp_02", 1f, 2f, source, velocity);
        }
    }

    float renderAge() {
        // A frame step can straddle zero. Sample the exact apex once without resetting simulation time.
        return arrivalPeakFrame ? 0f : age;
    }

    boolean hidesNativeHull() { return !expired && !leaving && age < 0f; }

    void peakPresented() { if (arrivalPeakFrame) peakWasDrawn = true; }

    @Override
    public void advance(float amount) {
        if (expired) return;
        if (ship.isHulk() || ship.getHitpoints() <= 0f) {
            cancel();
            return;
        }
        boolean present = engine.isEntityInPlay(ship);
        if (present && leaving && !ship.isRetreating()) { cancel(); return; }
        if (engine.isPaused() || amount <= 0f) return;
        if (peakWasDrawn) arrivalPeakFrame = false;
        if (!present) {
            if (!started || !leaving) {
                cancel();
                return;
            }
            if (!removed) {
                removed = true;
                age = 0f;
                playWarpSound(position);
            }
            afterRemoval += amount;
            position.translate(velocity.x * amount, velocity.y * amount);
            if (afterRemoval >= 0.58f * duration) expired = true;
        } else {
            readPose();
            if (leaving) {
                if (ship.getTravelDrive() != null && ship.getTravelDrive().isChargedown()) stopping = true;
                // Chargedown/unapply can precede removal by one simulation tick.
                // No collapse while the entity is still present, even during this grace period.
                if (stopping && (stopAge += amount) >= 0.12f) { cancel(); return; }
            }
        }
        trailAge += amount / duration;
        float previousAge = age;
        if (!leaving || removed) age += amount / duration;
        if (leaving && !removed) age = -0.015f;
        if (!leaving && previousAge < 0f && age >= 0f) {
            arrivalPeakFrame = true;
            playWarpSound(gate);
        }
        if (!leaving && age >= 0.81f) expired = true;
    }

    float lightFade() {
        return 1f - Math.min(1f, Math.max(0f, (afterRemoval/duration - 0.34f)/0.24f));
    }

    @Override
    public EnumSet<CombatEngineLayers> getActiveLayers() {
        if (!leaving) return EnumSet.of(CombatEngineLayers.UNDER_SHIPS_LAYER, CombatEngineLayers.ABOVE_SHIPS_LAYER);
        return EnumSet.of(CombatEngineLayers.UNDER_SHIPS_LAYER, layer, CombatEngineLayers.CONTRAILS_LAYER);
    }

    @Override
    public float getRenderRadius() {
        return Float.MAX_VALUE;
    }

    @Override
    public boolean isExpired() {
        return expired;
    }

    @Override
    public void cleanup() {
        restore();
        if (systemEnded && ship.getCustomData().get(eventKey) == this) ship.removeCustomData(eventKey);
        image.close();
    }

    @Override
    public void render(CombatEngineLayers current, ViewportAPI view) {
        if (leaving && current == CombatEngineLayers.CONTRAILS_LAYER) {
            restore();
            return;
        }
        if (expired || !(view instanceof CombatViewport viewport) || !(ship instanceof Ship nativeShip)) return;
        if (!started) {
            if (current != CombatEngineLayers.UNDER_SHIPS_LAYER || !engine.isEntityInPlay(ship) || ship.isHulk()) return;
            readPose(); // The first render may precede the first simulation tick, including while paused.
        }
        try {
            if (current == CombatEngineLayers.UNDER_SHIPS_LAYER) {
                restore();
                if (!removed) {
                    ShipAPI player = engine.getPlayerShip();
                    int side = player == null || player.getOwner() == 100 ? 0 : player.getOwner();
                    FogOfWarAPI fog = engine.getFogOfWar(side);
                    visible = !ship.isDoNotRender() && (ship.getOwner() == side || fog == null || fog.isVisible(ship));
                }
                if (!visible || !view.isNearViewport(position, reach())) return;
                if (!GLContext.getCapabilities().OpenGL30) { cancel(); return; }
                if (!removed && (leaving || hidesNativeHull())) {
                    modules.clear();
                    if (nativeShip.getChildModules() != null) {
                        for (Ship child : nativeShip.getChildModules()) {
                            if (child != null && child.getParentStation() == nativeShip && !child.isDoNotRender()
                                    && engine.isEntityInPlay(child)) modules.add(child);
                        }
                    }
                    if (leaving) image.prepare(this, nativeShip, viewport);
                    ship.setDoNotRender(true);
                    masked = true;
                    for (Ship module : modules) module.setDoNotRender(true);
                }
            } else if (!leaving && current == CombatEngineLayers.ABOVE_SHIPS_LAYER) {
                // All native hull/weapon layers have finished. At/after the apex they were never masked.
                restore();
                if (visible && view.isNearViewport(position, reach()) && image.draw(this, viewport)) peakPresented();
            } else if ((masked || removed && visible) && view.isNearViewport(position, reach())) {
                image.draw(this, viewport);
            }
        } catch (RuntimeException ex) {
            cancel();
            Global.getLogger(Moci_MassDrivePass.class).error("Shader rendering failed: " + ship.getId(), ex);
        }
    }

    float reach() {
        // 第一次渲染且尚未隐藏舰体时测量；退场实体被移除后仍沿用最后的整舰尺寸。
        if (stampSize==0) {
            var b=Moci_Util.measureTravelHull(ship,engine);
            var sp=ship.getSpriteAPI();
            hullLength=sp==null?0:sp.getHeight(); hullWidth=sp==null?0:sp.getWidth();
            hullNose=sp==null?0:hullLength-sp.getCenterY();
            stampSize=2.4f*Math.max(hullLength,hullWidth);
            if (b.hasModules() || !(stampSize>0)) {
                hullLength=Math.max(hullLength,b.nose()+b.tail());
                hullWidth=Math.max(hullWidth,2f*b.side()); // 门以母舰为中心，不能剪掉偏向一侧的模块
                hullNose=b.nose();
                stampSize=Math.max(stampSize,2f*(Math.max(Math.max(b.nose(),b.tail()),b.side())+2f));
            }
            hullLength=Math.max(1f,hullLength); hullWidth=Math.max(1f,hullWidth);
        }
        float size = Math.max(hullLength,hullWidth);
        return 4f * size + (leaving ? 0f : Math.abs(position.x - gate.x) + Math.abs(position.y - gate.y));
    }
}
