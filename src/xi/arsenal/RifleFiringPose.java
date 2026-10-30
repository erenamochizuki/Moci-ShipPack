package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.loading.specs.nullsuper;
import com.fs.starfarer.loading.specs.W;
import java.util.*;
import org.lwjgl.util.vector.Vector2f;

/** Per-instance release-only bone overlay. Holding leaves normal arm and gun motion alone.
 * Shoulder pivots travel around BODY; gun, arm and native aperture share the same
 * translation. No hull/slot specification or actual ship facing is changed. */
public final class RifleFiringPose {
    public static final String KEY = "xi.arsenal.rifle.firingPose";
    public static final float LEFT_ARM_DEGREES = FullSalvoPose.LEFT_RELATIVE_DEGREES;
    public static final float BODY_DEGREES = 28f;
    /** Compatibility token for old callers; hand extension is now joint rotation, never translation. */
    @Deprecated public static final float RIGHT_FORWARD = 22f;
    public static final float OPEN_SECONDS = .12f, CLOSE_SECONDS = .18f;
    public static final float CHEST_SECONDS = .30f, TURN_SECONDS = .30f, TURN_SETTLE_SECONDS = .22f, RAISE_SECONDS = .22f, AIM_SETTLE_SECONDS = .18f;
    public static final float PRESENT_SECONDS = TURN_SECONDS + TURN_SETTLE_SECONDS + RAISE_SECONDS + AIM_SETTLE_SECONDS;
    @Deprecated public static final float CHEST_FORWARD = 8f, CHEST_RIGHT = 12f;
    public static final float CHEST_RIFLE_DEGREES = 25f;
    public static final float RECOIL_DISTANCE = 6f, RECOIL_RETURN_SECONDS = .14f;
    public enum Stage { HOLDING, PRESENTING, FIRING, CLOSING }
    public record Presentation(float turnCross, float crossHold, float whipAim, float aimHold) { public float total() { return turnCross + crossHold + whipAim + aimHold; } }
    /** Only a real overlimit shot stretches the accepted normal release beats. */
    public static Presentation presentation(float power) { if (!Float.isFinite(power) || power < 0) throw new IllegalArgumentException("Invalid presentation power"); float p = Math.min(1, power), move = p > 0 ? 1.25f + .55f * p : 1, pause = p > 0 ? 1.75f + 1.25f * p : 1; return new Presentation(TURN_SECONDS * move, TURN_SETTLE_SECONDS * pause, RAISE_SECONDS * move, AIM_SETTLE_SECONDS * pause); }
    private static final Set<String> SHOULDERS = Set.of("SHOULDER_L", "SHOULDER_R", "SHOULDER_L_UNDER", "SHOULDER_R_UNDER");
    private static final List<String> BLADE_KEYS = List.of("MOCI_BLADE_SWING_ACTIVE_LEFT", "MOCI_BLADE_SWING_ACTIVE_RIGHT");
    private static final List<String> TRANSFORM_KEYS = List.of("MOCI_MSZ_006_TRANSFORMED", "MOCI_MSZ_006_PARTS_HIDDEN", "MOCI_MSZ_006_TRANSFORM_PREPARE");
    private RifleFiringPose() { }
    private static final String RIG_KEY = KEY + ".releaseRig";
    /** Single-instance rig attachment. Existing Xi/Penelope callers need no attachment and keep their current adapter. */
    public static AutoCloseable attach(ShipAPI ship,RifleReleaseRig rig) {
        Objects.requireNonNull(ship,"rifle rig ship");Objects.requireNonNull(rig,"rifle release rig");
        if(rig.ship()!=ship)throw new IllegalArgumentException("Rifle rig belongs to another ship");
        WeaponAPI source=rig.source();if(source==null||source.getShip()!=ship)throw new IllegalArgumentException("Rifle rig requires its own real visible weapon");
        int standing=rig.standingLegFrame();if(standing<0||standing>1024)throw new IllegalArgumentException("Invalid declared standing leg frame");
        presentation(rig.presentationPower());
        if(rig.useQuinticMotionCurve()&&(!Float.isFinite(rig.recoverySeconds())||rig.recoverySeconds()<=0))throw new IllegalArgumentException("Invalid curved rifle recovery duration");
        if(ship.getCustomData().containsKey(RIG_KEY)||state(ship)!=null||legState(ship)!=null)throw new IllegalStateException("Rifle motion already has a live owner");
        RigAttachment binding=new RigAttachment(ship,rig);ship.setCustomData(RIG_KEY,binding);return binding;
    }
    private static final class RigAttachment implements AutoCloseable {
        final Thread thread=Thread.currentThread();final ShipAPI ship;final RifleReleaseRig rig;boolean closed;
        RigAttachment(ShipAPI ship,RifleReleaseRig rig){this.ship=ship;this.rig=rig;}
        void requireOwner(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Rifle rig belongs to its native battle thread");}
        @Override public void close(){requireOwner();if(closed)return;closed=true;if(ship.getCustomData().get(RIG_KEY)==this){clear(ship);ship.removeCustomData(RIG_KEY);}}
    }
    private static RifleReleaseRig rig(ShipAPI ship){
        Object value=ship.getCustomData().get(RIG_KEY);if(!(value instanceof RigAttachment binding)||binding.closed)return null;
        binding.requireOwner();return binding.rig;
    }
    private static WeaponAPI sourceFor(ShipAPI ship){
        RifleReleaseRig external=rig(ship);if(external!=null){WeaponAPI source=external.source();if(external.ship()!=ship||source==null||source.getShip()!=ship)throw new IllegalStateException("Attached rifle source changed ownership");return source;}
        BeamRifleModes modes=BeamRifleModes.instance(ship);return modes==null?null:modes.source();
    }
    private static float presentationPowerFor(ShipAPI ship){
        RifleReleaseRig external=rig(ship);if(external!=null)return external.presentationPower();
        BeamRifleModes modes=BeamRifleModes.instance(ship);return modes==null?0:modes.shotOverlimitPower();
    }


    public static void advance(ShipAPI ship, boolean firing, float dt) {
        advance(ship, firing ? Stage.FIRING : Stage.CLOSING, dt);
    }
    public static void advance(ShipAPI ship, Stage wanted, float dt) {
        if (!Float.isFinite(dt) || dt < 0) throw new IllegalArgumentException("Invalid rifle pose local step");
        Pose pose = state(ship);
        if (!blockedReason(ship).isEmpty() || !ship.isAlive() || ship.isHulk()) { clear(ship); return; }
        if (wanted == Stage.HOLDING) { if (pose != null) { pose.restore(); ship.removeCustomData(KEY); } holdLegs(ship); return; }
        if (pose == null) { if (wanted == Stage.CLOSING) { releaseLegs(ship); return; } pose = new Pose(ship); ship.setCustomData(KEY, pose); }
        holdLegs(ship);
        pose.transition(wanted);
        pose.active = wanted != Stage.CLOSING;
        if (wanted == Stage.HOLDING) pose.chestBlend = Math.min(1, pose.chestBlend + dt / CHEST_SECONDS);
        else if (wanted == Stage.PRESENTING) { pose.presentElapsed = Math.min(pose.presentation.total(), pose.presentElapsed + dt); pose.blend = pose.presentElapsed / pose.presentation.total(); }
        else if (wanted == Stage.FIRING) pose.blend = Math.min(1, pose.blend + dt / OPEN_SECONDS);
        else if (DskRifleCurveV2.isDsk(ship)) { pose.closingElapsed = Math.min(DskRifleCurveV2.RECOVERY, pose.closingElapsed + dt); float remaining = 1 - DskRifleCurveV2.recoveryCurve(pose.closingElapsed / DskRifleCurveV2.RECOVERY); pose.blend = pose.closingBlend * remaining; pose.chestBlend = pose.closingChest * remaining; }
        else if(pose.quinticMotion){pose.closingElapsed=Math.min(pose.recoverySeconds,pose.closingElapsed+dt);float remaining=1-pose.closingElapsed/pose.recoverySeconds;pose.blend=pose.closingBlend*remaining;pose.chestBlend=pose.closingChest*remaining;}
        else { pose.blend = Math.max(0, pose.blend - dt / CLOSE_SECONDS); pose.chestBlend = Math.max(0, pose.chestBlend - dt / CLOSE_SECONDS); }
        if (pose.recoilAge >= 0) pose.recoilAge += dt;
        for (Part part : pose.parts.values()) pose.apply(part);
        pose.leftAttachments.applyAll();
        pose.applyShieldLinks();
        if (wanted == Stage.CLOSING && pose.blend <= .00001f && pose.chestBlend <= .00001f) clear(ship);
    }
    public static boolean blocksShieldGuard(ShipAPI ship) { Pose pose = state(ship); return pose != null && pose.stage != Stage.HOLDING; }
    public static boolean readyForBeam(ShipAPI ship) { Pose pose = state(ship); return pose != null && pose.stage == Stage.PRESENTING && pose.blend >= .99999f; }
    public static float gunAimAngle(WeaponAPI rifle, float nativeAim) { Pose pose = state(rifle.getShip()); return pose == null ? nativeAim : pose.gunAimAngle(nativeAim); }
    public static void onNativeFirstLight(ShipAPI ship) { Pose pose = state(ship); if (pose == null || pose.blend < .99999f) throw new IllegalStateException("Native rifle illuminated before presenting pose completed"); pose.transition(Stage.FIRING); pose.recoilAge = 0; }
    public static float forward(ShipAPI ship) {
        Pose pose = state(ship);
        return 0;
    }
    /** World movement of the authored shoulder/arm anchor caused only by the torso's pivot. */
    public static Vector2f armTranslation(ShipAPI ship, boolean left) {
        Pose pose=state(ship);if(pose==null||!blockedReason(ship).isEmpty())return new Vector2f();
        Part arm=pose.bySlot.get(left?"ARM_L":"ARM_R");return arm==null?new Vector2f():pose.orbit(arm);
    }
    /** World translation of both rendered right arm and visible gun, including the moved shoulder. */
    public static Vector2f gunTranslation(WeaponAPI rifle) {
        Pose pose = state(rifle.getShip());
        return pose == null || !blockedReason(rifle.getShip()).isEmpty() ? new Vector2f() : pose.rightTranslation(rifle);
    }
    public static void apply(WeaponAPI weapon) {
        ShipAPI ship=weapon.getShip();if(!blockedReason(ship).isEmpty()||!ship.isAlive()||ship.isHulk()){clear(ship);return;}
        applyLegs(ship);
        Pose pose = state(ship);
        if (pose != null) { Part part = pose.parts.get(weapon); if (part != null) {pose.apply(part);if("ARM_L".equals(part.slot))pose.leftAttachments.applyAll();}else LeftArmAttachments.apply(weapon); }
    }
    public static void applyAll(ShipAPI ship) {
        if(!blockedReason(ship).isEmpty()||!ship.isAlive()||ship.isHulk()){clear(ship);return;}
        applyLegs(ship);
        Pose pose = state(ship);
        if (pose != null) { for (Part part : pose.parts.values()) pose.apply(part);pose.leftAttachments.applyAll(); pose.applyShieldLinks(); }
    }
    public static String blockedReason(ShipAPI ship) {
        if (FullSalvoPose.isBlockingRifle(ship)) return "导弹齐射姿势正在占用双臂";
        if (Boolean.TRUE.equals(ship.getCustomData().get("xi.arsenal.pose.blockRifle"))) return "高优先动作正在占用步枪姿态";
        for (String key : BLADE_KEYS) if (Boolean.TRUE.equals(ship.getCustomData().get(key))) return "原生光剑动作正在占用双臂";
        for (String key : TRANSFORM_KEYS) if (Boolean.TRUE.equals(ship.getCustomData().get(key))) return "原生变形动作正在占用机体";
        Object transform = ship.getCustomData().get("MOCI_MSZ_006_TRANSFORM_STATE");
        if (transform != null && !"NORMAL".equals(String.valueOf(transform))) return "原生变形尚未结束";
        CombatEngineAPI engine = Global.getCombatEngine();
        Object facts = engine == null ? null : engine.getCustomData().get(UnicornNtdOverloadSkill.KEY);
        // That skill is local-pilot-only. Read its already published bounded facts;
        // don't re-evaluate the entire target/availability catalog from every bone.
        if (engine != null && engine.getPlayerShip() == ship && facts instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("charging")))
            return "电子战技能姿势正在占用双臂";
        return "";
    }
    public static Map<String, Object> status(ShipAPI ship) {
        Pose pose = state(ship);
        if (pose == null) return Map.of("active", false, "blend", 0f, "blocksGuard", false, "standingLegFrame", standingFrame(ship), "standingLegLease", legState(ship) != null);
        Map<String, Object> r = new LinkedHashMap<>(); float b = pose.bodyBlend();
        r.put("standingLegFrame", standingFrame(ship)); r.put("standingLegLease", legState(ship) != null);
        r.put("active", pose.active); r.put("blend", pose.blend); r.put("leftArmDegrees", (BODY_DEGREES + LEFT_ARM_DEGREES) * b);
        r.put("stage", pose.stage.name()); r.put("chestBlend", pose.chestBlend); r.put("blocksGuard", blocksShieldGuard(ship));
        r.put("presentElapsed", pose.presentElapsed); r.put("presentSeconds", pose.presentation.total()); r.put("turnSeconds", pose.presentation.turnCross()); r.put("crossHoldSeconds", pose.presentation.crossHold()); r.put("whipSeconds", pose.presentation.whipAim()); r.put("aimHoldSeconds", pose.presentation.aimHold()); r.put("presentationOverlimitPower", pose.presentingPower); r.put("recoilDistance", pose.recoilDistance());
        r.put("bodyBlend", b); r.put("raiseBlend", pose.raiseBlend()); r.put("beat", pose.beat());
        if(pose.quinticMotion){r.put("motionCurve","QUINTIC");r.put("recoverySeconds",pose.recoverySeconds);r.put("recoveryElapsed",pose.closingElapsed);}
        r.put("leftArmRelativeToBodyDegrees", LEFT_ARM_DEGREES * b);
        r.put("bodyDegrees", BODY_DEGREES * b); r.put("rightForward", 0f); r.put("limbTranslationPolicy", "BODY_PIVOT_ORBIT_ONLY");
        Vector2f shoulder = pose.shoulderTranslation(false);
        r.put("rightShoulderWorldShift", List.of(shoulder.x, shoulder.y));
        r.put("headTargetAngle", pose.headAngle());
        return Collections.unmodifiableMap(r);
    }
    public static void clear(ShipAPI ship) {
        Pose pose = state(ship); if (pose != null) { pose.restore(); ship.removeCustomData(KEY); } releaseLegs(ship);
    }
    private static final String LEG_KEY = KEY + ".standingLeg";
    private static final class StandingLeg {
        final WeaponAPI weapon; final int frame; int nativeFrame; boolean written;
        StandingLeg(WeaponAPI weapon, int frame) { this.weapon = weapon; this.frame = frame; nativeFrame = weapon.getAnimation().getFrame(); if (weapon.getAnimation().getNumFrames() <= frame) throw new IllegalStateException("Missing declared native standing leg frame"); }
        void apply() { int actual = weapon.getAnimation().getFrame(); if (!written || actual != frame) nativeFrame = actual; weapon.getAnimation().setFrame(frame); written = true; }
        void restore() { if (written && weapon.getAnimation().getFrame() == frame) weapon.getAnimation().setFrame(nativeFrame); }
    }
    private static int standingFrame(ShipAPI ship) { RifleReleaseRig external=rig(ship);if(external!=null)return external.standingLegFrame();WeaponAPI source=sourceFor(ship);return source!=null&&BeamRiflePalette.PENELOPE_WEAPON.equals(source.getId())?4:3; }
    private static StandingLeg legState(ShipAPI ship) { Object value = ship.getCustomData().get(LEG_KEY); return value instanceof StandingLeg leg ? leg : null; }
    private static void holdLegs(ShipAPI ship) { RifleReleaseRig current=rig(ship);if(current!=null&&current.preserveNativeLegs())return;StandingLeg leg = legState(ship); if (leg == null) { WeaponAPI weapon = ship.getAllWeapons().stream().filter(w -> w.getSlot() != null && "LEGS".equals(w.getSlot().getId())).findFirst().orElseThrow(() -> new IllegalStateException("Native rifle standing leg bone missing")); if (weapon.getAnimation() == null) throw new IllegalStateException("Native standing leg animation missing"); leg = new StandingLeg(weapon, standingFrame(ship)); ship.setCustomData(LEG_KEY, leg); } leg.apply(); }
    private static void applyLegs(ShipAPI ship) { StandingLeg leg = legState(ship); if (leg != null) leg.apply(); }
    private static void releaseLegs(ShipAPI ship) { StandingLeg leg = legState(ship); if (leg != null) { leg.restore(); ship.removeCustomData(LEG_KEY); } }
    private static Pose state(ShipAPI ship) {
        Object p = ship == null ? null : ship.getCustomData().get(KEY); return p instanceof Pose pose ? pose : null;
    }
    private static float smooth(float x) { return x * x * (3 - 2 * x); }
    static float quintic(float x) { float t=Math.max(0,Math.min(1,x));return t*t*t*(10+t*(-15+6*t)); }
    private static float shortest(float x) { float v = ((x % 360) + 360) % 360; return v > 180 ? v - 360 : v; }
    static float leftArmRelative(float startingRelative, float progress) { return startingRelative + shortest(BODY_DEGREES + LEFT_ARM_DEGREES - startingRelative) * progress; }
    static float recoilAt(float localAge) { return localAge < 0 ? 0 : RECOIL_DISTANCE * (1 - smooth(Math.min(1, localAge / RECOIL_RETURN_SECONDS))); }
    private static Vector2f rotate(Vector2f v, float angle) {
        double a = Math.toRadians(angle); return new Vector2f((float)(v.x * Math.cos(a) - v.y * Math.sin(a)), (float)(v.x * Math.sin(a) + v.y * Math.cos(a)));
    }
    static Vector2f bodyPivotOrbit(Vector2f pivot, Vector2f body, float degrees, float facing) {
        Vector2f relative = Vector2f.sub(pivot, body, null);
        return rotate(Vector2f.sub(rotate(relative, degrees), relative, null), facing);
    }
    /** Current cosmetic sprite point. This is neither a hull-engine mount nor a weapon muzzle declaration. */
    public static Vector2f decorativeWorldPoint(WeaponAPI weapon,Vector2f localPoint){
        Vector2f point=Vector2f.add(weapon.getLocation(),rotate(localPoint,weapon.getCurrAngle()),null);
        Vector2f offset=weapon.isDecorative()&&weapon.isBeam()?weapon.getRenderOffsetForDecorativeBeamWeaponsOnly():null;
        if(offset!=null)Vector2f.add(point,rotate(offset,weapon.getShip().getFacing()),point);return point;
    }
    /** BACKPACK's own decorative engine-render firepoints follow the same cosmetic anchor.
     * These offsets are not rifle apertures and never change the root ship's engine slots. */
    static final class BackpackAnchors {
        private final WeaponAPI weapon;private final List<Anchor> anchors=new ArrayList<>();
        BackpackAnchors(WeaponAPI weapon){this.weapon=weapon;ZetaRifleMount.detach(weapon);for(var list:List.of(weapon.getSpec().getTurretFireOffsets(),weapon.getSpec().getHardpointFireOffsets(),weapon.getSpec().getHiddenFireOffsets()))for(Vector2f point:list)anchors.add(new Anchor(point));}
        void apply(Vector2f worldShift){Vector2f local=rotate(worldShift,-weapon.getCurrAngle());for(Anchor anchor:anchors){anchor.written=Vector2f.add(anchor.base,local,null);anchor.point.set(anchor.written);}weapon.updateBeamFromPoints();}
        void restore(){for(Anchor anchor:anchors)if(anchor.written!=null&&Vector2f.sub(anchor.point,anchor.written,null).lengthSquared()<.0001f)anchor.point.set(anchor.base);weapon.updateBeamFromPoints();}
        private static final class Anchor{final Vector2f point,base;Vector2f written;Anchor(Vector2f point){this.point=point;base=new Vector2f(point);}}
    }
    /** A SpriteAPI uses +Y forward and +X right; weapon local axes are +X forward and +Y left. */
    private static Vector2f toWeaponLocal(Vector2f world, float angle) { return rotate(world, -angle); }
    /** Native slot.clone is shallow for its node and location; detach before any location write. */
    static void detachLocation(WeaponSlotAPI slot) {
        if (!(slot instanceof nullsuper nativeSlot)) throw new IllegalStateException("Unsupported native module slot implementation: " + slot.getClass().getName());
        nativeSlot.setNode(new W(nativeSlot.getNode().o00000(), new Vector2f(slot.getLocation())));
    }

    private static final class Layer {
        final SpriteAPI sprite; final float x, y; float writtenX, writtenY; boolean written;
        Layer(SpriteAPI sprite) { this.sprite = sprite; x = sprite.getCenterX(); y = sprite.getCenterY(); }
        void translate(Vector2f world, float angle) {
            Vector2f local = toWeaponLocal(world, angle);
            sprite.setCenter(x + local.y, y - local.x);
            writtenX = sprite.getCenterX(); writtenY = sprite.getCenterY(); written = true;
        }
        void restore() {
            // 归还必须无条件：原先「中心仍等于我写入的值才归还」的守卫，在姿态关闭与
            // BeamRifleMount.synchronize() 同帧并行写入时会失败，于是这一帧谁都不写，
            // 步枪就停在握持位置闪一下。最终位置由 mount 的 synchronize()/align() 兜底，
            // 它们在本帧更晚执行、且按「当前是否还有姿态位移」重算，所以这里先写回是真值。
            if (written) sprite.setCenter(x, y);
        }
    }
    private static final class Part {
        final WeaponAPI weapon; final String slot; float relativeAngle; final Vector2f restPivot;
        final List<Layer> layers = new ArrayList<>(); float writtenAngle; boolean wroteAngle;
        final boolean frameSafeOffset; final Vector2f originalRenderOffset, baseRenderOffset;
        final BackpackAnchors backpackAnchors;
        Vector2f writtenRenderOffset; float writtenOffsetX, writtenOffsetY;
        Part(WeaponAPI weapon, ShipAPI ship) {
            this.weapon = weapon; slot = weapon.getSlot().getId(); relativeAngle = shortest(weapon.getCurrAngle() - ship.getFacing());
            restPivot = rotate(Vector2f.sub(weapon.getLocation(), ship.getLocation(), null), -ship.getFacing());
            frameSafeOffset = slot.equals("LEGS") || SHOULDERS.contains(slot) || slot.equals("BACKPACK")&&weapon.isDecorative()&&weapon.isBeam();
            if (frameSafeOffset && (!weapon.isDecorative() || !weapon.isBeam())) throw new IllegalStateException("Animated leg/shoulder pose requires its original decorative beam: " + slot);
            originalRenderOffset = frameSafeOffset ? weapon.getRenderOffsetForDecorativeBeamWeaponsOnly() : null;
            baseRenderOffset = originalRenderOffset == null ? new Vector2f() : new Vector2f(originalRenderOffset);
            backpackAnchors=slot.equals("BACKPACK")&&frameSafeOffset?new BackpackAnchors(weapon):null;
            Set<SpriteAPI> unique = Collections.newSetFromMap(new IdentityHashMap<>());
            if (!frameSafeOffset) for (SpriteAPI sprite : new SpriteAPI[]{weapon.getSprite(), weapon.getBarrelSpriteAPI(), weapon.getUnderSpriteAPI(), weapon.getGlowSpriteAPI()})
                if (sprite != null && unique.add(sprite)) layers.add(new Layer(sprite));
        }
        void angle(float angle) { weapon.setCurrAngle(angle); writtenAngle = weapon.getCurrAngle(); wroteAngle = true; }
        void translate(Vector2f world) {
            if (frameSafeOffset) {
                // Native oooo_1.render rotates this offset by ship.facing once.
                // It is independent of the current animation-frame Sprite, so
                // no leg/shoulder frame centre is modified or left behind.
                writtenRenderOffset = Vector2f.add(baseRenderOffset, rotate(world, -weapon.getShip().getFacing()), null);
                writtenOffsetX = writtenRenderOffset.x; writtenOffsetY = writtenRenderOffset.y;
                weapon.setRenderOffsetForDecorativeBeamWeaponsOnly(writtenRenderOffset);
            } else for (Layer layer : layers) layer.translate(world, weapon.getCurrAngle());
            if(backpackAnchors!=null)backpackAnchors.apply(world);
        }
        void restore(ShipAPI ship) {
            // 同上：归还一律无条件写回。姿态关闭时本帧后续的 align()/synchronize()
            // 会按最新角度与位移重新写入，所以这里不存在「覆盖他人成果」的风险，
            // 只存在「守卫失败导致空窗」的风险。
            if (wroteAngle) weapon.setCurrAngle(ship.getFacing() + relativeAngle);
            if (frameSafeOffset && writtenRenderOffset != null)
                weapon.setRenderOffsetForDecorativeBeamWeaponsOnly(originalRenderOffset);
            for (Layer layer : layers) layer.restore();
            if(backpackAnchors!=null)backpackAnchors.restore();
        }
    }
    private static final class Pose {
        final ShipAPI ship; final Map<WeaponAPI, Part> parts = new IdentityHashMap<>(); final Map<String, Part> bySlot = new HashMap<>();
        final List<ShieldLink> shields = new ArrayList<>();
        final LeftArmAttachments leftAttachments;
        final boolean quinticMotion;final float recoverySeconds;
        float closingElapsed,closingBody,closingRaise,closingBlend,closingChest;
        boolean baseWasHidden, bodyClaimed;
        float blend, chestBlend, presentingChest, presentElapsed, presentingRifleRelative, initialRifleRelative, recoilAge = -1; boolean active;
        Stage stage = Stage.HOLDING; float presentingPower; Presentation presentation = presentation(0);
        Pose(ShipAPI ship) {
            this.ship = ship;
            RifleReleaseRig external=rig(ship);quinticMotion=external!=null&&external.useQuinticMotionCurve();recoverySeconds=quinticMotion?external.recoverySeconds():CLOSE_SECONDS;
            for (WeaponAPI w : ship.getAllWeapons()) if (w.getSlot() != null) {
                String slot = w.getSlot().getId();
                if (slot.equals("BODY") || slot.equals("BODY_ARMOR") || slot.equals("LEGS") && (external==null || !external.preserveNativeLegs()) || SHOULDERS.contains(slot) || slot.equals("ARM_L") || slot.equals("ARM_R") || slot.equals("HEAD") || slot.equals("BACKPACK")) {
                    Part part = new Part(w, ship); parts.put(w, part); bySlot.put(slot, part);
                }
            }
            if (!bySlot.containsKey("BODY") || !bySlot.containsKey("ARM_R") || !bySlot.containsKey("HEAD")) throw new IllegalStateException("Rifle release requires real body, right-arm and head bones");
            WeaponAPI source=sourceFor(ship); initialRifleRelative = source == null ? 0 : shortest(source.getCurrAngle() - ship.getFacing());
            leftAttachments=new LeftArmAttachments(ship,this::leftAngle,()->{Part arm=bySlot.get("ARM_L");return arm==null?new Vector2f():orbit(arm);},()->state(ship)==this);
        }
        void claimBody() {
            if (bodyClaimed) return;
            baseWasHidden = ship.isDoNotRenderSprite();
            for (ShipAPI module : ship.getChildModulesCopy()) if (module.getStationSlot() != null && "SHIELD_L".equals(module.getStationSlot().getId()) && module.isAlive()) shields.add(new ShieldLink(module));
            // BODY supplies the moving torso. Ship.render forcibly resets the
            // hull sprite size, so use the public bitmap-only render gate rather
            // than copying Alteisen's ineffective setSize(0,0) request.
            if (ship.getCustomData().containsKey(KEY + ".bitmapLease")) throw new IllegalStateException("Base bitmap already has a rifle pose owner");
            ship.setCustomData(KEY + ".bitmapLease", this); ship.setDoNotRenderSprite(true);
            bodyClaimed = true;
        }
        void transition(Stage wanted) {
            if (stage == wanted) return;
            if (wanted == Stage.PRESENTING) {
                presentingChest = smooth(chestBlend); presentElapsed = 0;
                WeaponAPI source=sourceFor(ship); presentingPower=presentationPowerFor(ship); presentation = DskRifleCurveV2.isDsk(ship) ? DskRifleCurveV2.toPresentation(presentingPower) : presentation(presentingPower); presentingRifleRelative = source == null ? initialRifleRelative : shortest(source.getCurrAngle() - ship.getFacing());
                Part left = bySlot.get("ARM_L"); if (left != null) left.relativeAngle = shortest(left.weapon.getCurrAngle() - ship.getFacing());
                claimBody();
            } else if (wanted == Stage.FIRING) { claimBody(); chestBlend = 0; }
            else if(wanted==Stage.CLOSING&&(quinticMotion||DskRifleCurveV2.isDsk(ship))){closingBody=bodyBlend();closingRaise=raiseBlend();closingBlend=blend;closingChest=chestBlend;closingElapsed=0;}
            stage = wanted;
        }
        float gunAimAngle(float nativeAim) {
            if (stage == Stage.HOLDING) return nativeAim;
            if (stage == Stage.PRESENTING || stage == Stage.CLOSING && (quinticMotion || DskRifleCurveV2.isDsk(ship))) { float turned = bodyBlend(); float from = shortest(ship.getFacing() + presentingRifleRelative + shortest(CHEST_RIFLE_DEGREES - presentingRifleRelative) * turned + BODY_DEGREES * turned); return shortest(from + shortest(nativeAim - from) * raiseBlend()); }
            return nativeAim;
        }
        float motionCurve(float x){return quinticMotion?quintic(x):smooth(x);}
        /** 归位进度 0..1：DS柯西走三段卸力曲线，其余机体保持 quintic。 */
        float closingCurve() {
            if (DskRifleCurveV2.isDsk(ship)) return DskRifleCurveV2.recoveryCurve(closingElapsed / DskRifleCurveV2.RECOVERY);
            return quintic(closingElapsed / Math.max(Float.MIN_NORMAL, recoverySeconds));
        }
        float bodyBlend() {
            if (stage == Stage.CLOSING && (quinticMotion || DskRifleCurveV2.isDsk(ship))) return closingBody * (1 - closingCurve());
            if (stage == Stage.PRESENTING) {
                float t = Math.min(1, presentElapsed / presentation.turnCross());
                if (DskRifleCurveV2.isDsk(ship)) return DskRifleCurveV2.turnCurve(t);
                return motionCurve(t);
            }
            return motionCurve(blend);
        }
        /** DS柯西第 1 层：头部比身体早到位（72% 处就转完），其余机体与 bodyBlend 一致。 */
        float headBlend() {
            if (stage == Stage.PRESENTING && DskRifleCurveV2.isDsk(ship)) {
                return DskRifleCurveV2.headCurve(Math.min(1, presentElapsed / presentation.turnCross()));
            }
            return bodyBlend();
        }
        /** DS柯西第 2 层：停顿段末尾的身体压缩（负角 = 往回收一点），其余机体恒为 0。 */
        float anticipateBody() {
            if (stage != Stage.PRESENTING || !DskRifleCurveV2.isDsk(ship)) return 0f;
            float hold = presentElapsed - presentation.turnCross();
            if (hold <= 0 || hold >= presentation.crossHold()) return 0f;
            float h = hold / presentation.crossHold();
            if (h < .55f) return 0f;
            return -1.5f * smooth((h - .55f) / .45f);
        }
        /** DS柯西第 7 层：反向预备偏航。甩枪前先把腰往反方向拧，甩枪段前 30% 弹回。 */
        float anticipateYaw() {
            if (stage != Stage.PRESENTING || !DskRifleCurveV2.isDsk(ship)) return 0f;
            float t1 = presentation.turnCross(), t2 = t1 + presentation.crossHold(), t3 = t2 + presentation.whipAim();
            if (presentElapsed <= t1) return 0f;
            if (presentElapsed < t2) {
                float h = (presentElapsed - t1) / presentation.crossHold();
                if (h < .55f) return 0f;
                return DskRifleCurveV2.ANTICIPATE_YAW * DskRifleCurveV2.anticipateYaw((h - .55f) / .45f);
            }
            if (presentElapsed < t3) {
                float w = (presentElapsed - t2) / presentation.whipAim();
                if (w >= .30f) return 0f;
                return DskRifleCurveV2.ANTICIPATE_YAW * DskRifleCurveV2.anticipateYaw(1f - w / .30f);
            }
            return 0f;
        }
        /** 上身实际偏航（度）：躯干基础转角 + 反向预备。 */
        float bodyYaw() {
            float base = BODY_DEGREES * bodyBlend();
            if (stage != Stage.PRESENTING || !DskRifleCurveV2.isDsk(ship)) return base;
            return base + anticipateYaw() + anticipateBody();
        }
        /** 腿部偏航（度）：只跟 {@link DskRifleCurveV2#LEG_FOLLOW} 这么多，也不吃反向预备。 */
        float legYaw() {
            float base = BODY_DEGREES * bodyBlend();
            if (!DskRifleCurveV2.isDsk(ship)) return base;
            return base * DskRifleCurveV2.LEG_FOLLOW;
        }
        /** 第 1 层：按延迟秒数算出的部件跟随进度（肩甲 / 背包用）。 */
        float followBlend(float delaySeconds) {
            if (stage != Stage.PRESENTING || !DskRifleCurveV2.isDsk(ship)) return bodyBlend();
            float t = DskRifleCurveV2.delayedProgress(presentElapsed, presentation.turnCross(), delaySeconds);
            return DskRifleCurveV2.turnCurve(t);
        }
        float raiseBlend() {
            if (stage == Stage.CLOSING && (quinticMotion || DskRifleCurveV2.isDsk(ship))) return closingRaise * (1 - closingCurve());
            if (stage != Stage.PRESENTING) return motionCurve(blend);
            float t1 = presentation.turnCross();
            float t2 = t1 + presentation.crossHold();
            float t3 = t2 + presentation.whipAim();
            if (DskRifleCurveV2.isDsk(ship)) {
                if (presentElapsed < t1) return 0f;
                if (presentElapsed < t2) {
                    float h = (presentElapsed - t1) / presentation.crossHold();
                    return h < .55f ? 0f : DskRifleCurveV2.anticipate((h - .55f) / .45f);
                }
                if (presentElapsed < t3) return DskRifleCurveV2.whipCurve((presentElapsed - t2) / presentation.whipAim());
                return DskRifleCurveV2.settleCurve((presentElapsed - t3) / presentation.aimHold());
            }
            return motionCurve(Math.max(0, Math.min(1, (presentElapsed - t2) / presentation.whipAim())));
        }
        String beat() { if (stage == Stage.HOLDING) return "HOLD_NORMAL"; if (stage == Stage.PRESENTING) return presentElapsed < presentation.turnCross() ? "TURN_CROSS" : presentElapsed < presentation.turnCross() + presentation.crossHold() ? "CROSS_HOLD" : presentElapsed < presentation.turnCross() + presentation.crossHold() + presentation.whipAim() ? "WHIP_AIM" : "AIM_HOLD"; return stage == Stage.CLOSING ? "RECOVER" : recoilDistance() > 0 ? "FIRST_LIGHT_RECOIL" : "ILLUMINATING"; }
        float recoilDistance() { return recoilAt(recoilAge); }
        Vector2f orbit(Part part) { return orbit(part, bodyYaw()); }
        Vector2f orbit(Part part, float degrees) {
            Vector2f pivot=part.restPivot;
            if(part.slot.equals("BACKPACK")&&part.frameSafeOffset)pivot=Vector2f.add(pivot,part.baseRenderOffset,null);
            return bodyPivotOrbit(pivot, bySlot.get("BODY").restPivot, degrees, ship.getFacing());
        }
        Vector2f shoulderTranslation(boolean left) {
            Part shoulder = bySlot.get(left ? "SHOULDER_L" : "SHOULDER_R");
            if (shoulder == null) shoulder = bySlot.get(left ? "ARM_L" : "ARM_R");
            Vector2f shift = shoulder == null ? new Vector2f() : orbit(shoulder);
            return shift;
        }
        Vector2f rightTranslation(WeaponAPI source) {
            // The authored arm and gun share a shoulder anchor. Crossing/raising the rifle
            // changes its joint angle; it must never slide or stretch away from that anchor.
            Part arm=bySlot.get("ARM_R");return arm==null?new Vector2f():orbit(arm);
        }
        float headAngle() {
            Part head = bySlot.get("HEAD"); if (head == null) return ship.getFacing();
            Vector2f pivot = Vector2f.add(head.weapon.getLocation(), orbit(head), null), at;
            ShipAPI target = ship.getShipTarget();
            if (target != null && target.isAlive() && !target.isHulk() && !target.wasRemoved()) at = target.getLocation(); else at = ship.getMouseTarget();
            if (at == null || Vector2f.sub(at, pivot, null).lengthSquared() < .001) return ship.getFacing();
            return (float)Math.toDegrees(Math.atan2(at.y - pivot.y, at.x - pivot.x));
        }
        float leftAngle(){
            Part left=bySlot.get("ARM_L"); if(left==null) return ship.getFacing();
            float relative = leftArmRelative(left.relativeAngle, bodyBlend());
            // DS柯西第 4 层：左手反向配重（侧身张开 / 甩枪后甩），其余机体不动这一项
            if (stage == Stage.PRESENTING && DskRifleCurveV2.isDsk(ship)) {
                float total = presentation.total();
                relative += DskRifleCurveV2.leftCounter(total > 0 ? presentElapsed / total : 1f);
            }
            return ship.getFacing() + relative;
        }
        void apply(Part part) {
            float b = bodyBlend(); Vector2f shift = new Vector2f(); String slot = part.slot;
            boolean dsk = DskRifleCurveV2.isDsk(ship);
            if (stage == Stage.HOLDING && !slot.equals("ARM_R")) return;
            if (slot.equals("ARM_L")) {
                part.angle(leftAngle()); shift = orbit(part);
            } else if (slot.equals("ARM_R")) {
                WeaponAPI source=sourceFor(ship);
                part.angle(source == null || (stage == Stage.CLOSING && b <= 0) ? ship.getFacing() + part.relativeAngle : source.getCurrAngle());
                if (source != null) shift = rightTranslation(source);
            } else if (slot.equals("HEAD")) {
                float hb = headBlend();
                float from = ship.getFacing() + part.relativeAngle; part.angle(from + shortest(headAngle() - from) * hb); shift = orbit(part);
            } else if (dsk) {
                // ── DS柯西：腿站住 / 上身拧 / 重部件拖尾 / 后坐后仰 ──────────────
                boolean leg = slot.equals("LEGS");
                float degrees;
                if (leg) degrees = legYaw();
                else if (SHOULDERS.contains(slot)) degrees = BODY_DEGREES * followBlend(DskRifleCurveV2.SHOULDER_DELAY) + anticipateYaw();
                else if (slot.equals("BACKPACK")) degrees = BODY_DEGREES * followBlend(DskRifleCurveV2.BACKPACK_DELAY) + anticipateYaw();
                else degrees = bodyYaw();
                if (slot.equals("BODY") || slot.equals("BODY_ARMOR")) degrees += DskRifleCurveV2.recoilPitch(recoilAge);
                part.angle(ship.getFacing() + part.relativeAngle + degrees);
                if (leg || SHOULDERS.contains(slot) || slot.equals("BACKPACK")) shift = orbit(part, degrees);
            } else {
                part.angle(ship.getFacing() + part.relativeAngle + BODY_DEGREES * b);
                // The original leg controller keeps advancing internally; the visual
                // standing-frame lease is applied at this same late-pose boundary.
                // Move the actual rendered
                // pivot around BODY with the same turn, rather than rotating in
                // place at a different hip position (Xi+18 / Penelope-50).
                if (SHOULDERS.contains(slot) || slot.equals("LEGS") || slot.equals("BACKPACK")) shift = orbit(part);
            }
            part.translate(shift);
        }
        void applyShieldLinks() { if (!bodyClaimed) return; Part left=bySlot.get("ARM_L");Vector2f local = rotate(left==null?new Vector2f():orbit(left), -ship.getFacing()); for (ShieldLink shield : shields) shield.apply(ship, local); AdaptiveShieldGuard.ensureVisualLayer(ship); }
        /** 是否已经有别的动作（齐射/光剑/变形/盾构外形）要接管同一批骨骼与机体贴图。 */
        private boolean handingOff() {
            if (!ship.isAlive() || ship.isHulk()) return false;
            if (!blockedReason(ship).isEmpty()) return true;
            return Boolean.TRUE.equals(ship.getCustomData().get(data.scripts.weapons.Moci_MechTransformController.CUSTOM_PARTS_HIDDEN))
                    || Boolean.TRUE.equals(ship.getCustomData().get(data.scripts.weapons.Moci_MechTransformController.CUSTOM_TRANSFORMED));
        }
        void restore() {
            // 交棒那一帧不要先还原贴图：否则步枪/骨骼会闪回原生挂点一帧，接管方下一帧才盖住。
            // 接管方自己会在本帧写这些骨骼与机体可见性，我们只放掉租约并入账。
            boolean handoff = handingOff();
            Throwable failure = null;
            if (!handoff) for (AutoCloseable item : restorations()) try { item.close(); } catch (Throwable error) { if (error instanceof VirtualMachineError fatal) throw fatal; if (failure == null) failure = error; else failure.addSuppressed(error); }
            if (ship.getCustomData().get(KEY + ".bitmapLease") == this) { if (!handoff && ship.isDoNotRenderSprite()) ship.setDoNotRenderSprite(baseWasHidden); ship.removeCustomData(KEY + ".bitmapLease"); }
            blend = 0; active = false;
            if (failure != null) throw new IllegalStateException("Rifle pose cleanup failed", failure);
        }
        private List<AutoCloseable> restorations(){List<AutoCloseable> result=new ArrayList<>();result.add(leftAttachments);for(Part part:parts.values())result.add(()->part.restore(ship));for(ShieldLink shield:shields)result.add(shield::restore);return result;}
    }
    /** Location-only lease; Moci remains the sole owner of shield angle, AI, damage and collision. */
    private static final class ShieldLink {
        final ShipAPI module; final WeaponSlotAPI slot; final Vector2f original; Vector2f written;
        ShieldLink(ShipAPI module) {
            this.module = module; module.ensureClonedStationSlotSpec(); slot = module.getStationSlot(); detachLocation(slot); original = new Vector2f(slot.getLocation());
        }
        void apply(ShipAPI parent, Vector2f shift) {
            if (!module.isAlive() || module.wasRemoved() || module.getParentStation() != parent || module.getStationSlot() != slot) { restore(); return; }
            written = Vector2f.add(original, shift, null); slot.getLocation().set(written);
        }
        void restore() { if (written != null && Vector2f.sub(slot.getLocation(), written, null).lengthSquared() < .0001) slot.getLocation().set(original); written = null; }
    }
}
