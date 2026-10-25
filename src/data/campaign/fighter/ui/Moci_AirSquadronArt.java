package data.campaign.fighter.ui;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.loading.ProjectileWeaponSpecAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

/** Per-dialog art cache. Every resource/spec lookup happens while constructing a preview. */
final class Moci_AirSquadronArt {
    private final Map<String, SpriteAPI> sprites = new HashMap<>();
    private final Map<String, WeaponSpecAPI> weapons = new HashMap<>();
    private final Map<String, float[]> hullCenters = new HashMap<>();
    private final Map<String, Boolean> barrelsBelow = new HashMap<>();

    static final class Model {
        final List<Layer> layers;
        final float minX, minY, maxX, maxY;
        Model(List<Layer> layers) {
            this.layers = List.copyOf(layers);
            float x0 = Float.POSITIVE_INFINITY, y0 = x0, x1 = Float.NEGATIVE_INFINITY, y1 = x1;
            for (Layer layer : layers) {
                double angle = Math.toRadians(layer.angle);
                float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
                for (int i = 0; i < 4; i++) {
                    float x = (i % 2 == 0 ? 0 : layer.width) - layer.pivotX;
                    float y = (i < 2 ? 0 : layer.height) - layer.pivotY;
                    float px = layer.x + x * cos - y * sin, py = layer.y + x * sin + y * cos;
                    x0 = Math.min(x0, px); y0 = Math.min(y0, py);
                    x1 = Math.max(x1, px); y1 = Math.max(y1, py);
                }
            }
            minX = Float.isFinite(x0) ? x0 : -1; minY = Float.isFinite(y0) ? y0 : -1;
            maxX = Float.isFinite(x1) ? x1 : 1; maxY = Float.isFinite(y1) ? y1 : 1;
        }
        Moci_AirSquadronLayout.Transform fit(float x, float y, float w, float h) {
            return Moci_AirSquadronLayout.fit(minX, minY, maxX, maxY, x, y, w, h);
        }
        void render(Moci_AirSquadronLayout.Transform transform, float alpha) {
            for (Layer layer : layers) layer.render(transform, alpha);
        }
    }

    private record Layer(SpriteAPI sprite, float width, float height, float pivotX, float pivotY,
                         float x, float y, float angle) {
        void render(Moci_AirSquadronLayout.Transform t, float alpha) {
            float oldW = sprite.getWidth(), oldH = sprite.getHeight(), oldX = sprite.getCenterX(), oldY = sprite.getCenterY();
            float oldAngle = sprite.getAngle(), oldAlpha = sprite.getAlphaMult();
            Color oldColor = sprite.getColor();
            int src = sprite.getBlendSrc(), dst = sprite.getBlendDest();
            try {
                sprite.setSize(width * t.scale(), height * t.scale());
                sprite.setCenter(pivotX * t.scale(), pivotY * t.scale());
                sprite.setAngle(angle); sprite.setColor(Color.WHITE); sprite.setAlphaMult(alpha);
                sprite.setNormalBlend();
                sprite.renderAtCenter(t.mapX(x), t.mapY(y));
            } finally {
                sprite.setSize(oldW, oldH); sprite.setCenter(oldX, oldY); sprite.setAngle(oldAngle);
                sprite.setAlphaMult(oldAlpha); sprite.setColor(oldColor); sprite.setBlendFunc(src, dst);
            }
        }
    }

    /** 单个贴图（例如船插图标）构成的模型，供「战术改装」图标行按比例居中绘制。 */
    Model spriteModel(String path) {
        SpriteAPI sprite = sprite(path);
        if (sprite == null) return new Model(List.of());
        float w = sprite.getWidth(), h = sprite.getHeight();
        return new Model(List.of(new Layer(sprite, w, h, w / 2f, h / 2f, 0, 0, 0)));
    }

    Model build(ShipVariantAPI variant) {
        List<Layer> layers = new ArrayList<>();
        appendVariant(layers, variant, 0, 0, 0, 0);
        return new Model(layers);
    }

    Model weaponIcon(String id) {
        if (id == null || id.isEmpty()) return new Model(List.of());
        WeaponSpecAPI weapon = weapon(id);
        List<Layer> layers = new ArrayList<>();
        appendWeapon(layers, weapon, true, 0, 0, 0);
        return new Model(layers);
    }

    private void appendVariant(List<Layer> layers, ShipVariantAPI variant, float ox, float oy, float angle, int depth) {
        if (depth > 4 || variant == null) return;
        ShipHullSpecAPI hull = variant.getHullSpec();
        SpriteAPI hullSprite = sprite(hull.getSpriteName());
        if (hullSprite != null) {
            float[] center = hullCenters.computeIfAbsent(hull.getHullId(), id -> readHullCenter(hull, hullSprite));
            layers.add(new Layer(hullSprite, hullSprite.getWidth(), hullSprite.getHeight(), center[0], center[1], ox, oy, angle));
        }
        Map<String, String> fitted = new LinkedHashMap<>(hull.getBuiltInWeapons());
        for (String id : variant.getFittedWeaponSlots()) fitted.put(id, variant.getWeaponId(id));
        List<WeaponSlotAPI> slots = new ArrayList<>(hull.getAllWeaponSlotsCopy());
        slots.sort(Comparator.comparingDouble((WeaponSlotAPI slot) -> {
            String id = fitted.get(slot.getId());
            WeaponSpecAPI spec = id == null ? null : weapon(id);
            return (spec != null && spec.isRenderAboveAllWeapons() ? 10000 : 0) + slot.getRenderOrderMod();
        }));
        for (WeaponSlotAPI slot : slots) {
            if (slot.isHidden()) continue;
            float x = -slot.getLocation().y, y = slot.getLocation().x;
            double a = Math.toRadians(angle);
            float px = ox + x * (float) Math.cos(a) - y * (float) Math.sin(a);
            float py = oy + x * (float) Math.sin(a) + y * (float) Math.cos(a);
            String id = fitted.get(slot.getId());
            if (id != null && !id.isEmpty()) appendWeapon(layers, weapon(id), !slot.isHardpoint(), px, py, angle + slot.getAngle());
            ShipVariantAPI module = variant.getModuleVariant(slot.getId());
            if (module != null) {
                float moduleAngle = angle + slot.getAngle();
                double ma = Math.toRadians(moduleAngle);
                float anchorX = -module.getHullSpec().getModuleAnchor().y;
                float anchorY = module.getHullSpec().getModuleAnchor().x;
                px -= anchorX * (float) Math.cos(ma) - anchorY * (float) Math.sin(ma);
                py -= anchorX * (float) Math.sin(ma) + anchorY * (float) Math.cos(ma);
                appendVariant(layers, module, px, py, moduleAngle, depth + 1);
            }
        }
    }

    private void appendWeapon(List<Layer> layers, WeaponSpecAPI weapon, boolean turret, float x, float y, float angle) {
        if (weapon == null) return;
        addLayer(layers, turret ? weapon.getTurretUnderSpriteName() : weapon.getHardpointUnderSpriteName(), turret, x, y, angle);
        String barrel = null;
        if (weapon instanceof ProjectileWeaponSpecAPI projectile) {
            barrel = turret ? projectile.getTurretGunSpriteName() : projectile.getHardpointGunSpriteName();
        }
        boolean below = barrel != null && barrelsBelow.computeIfAbsent(weapon.getWeaponId(), Moci_AirSquadronArt::barrelBelow);
        if (below) addLayer(layers, barrel, turret, x, y, angle);
        addLayer(layers, turret ? weapon.getTurretSpriteName() : weapon.getHardpointSpriteName(), turret, x, y, angle);
        if (!below) addLayer(layers, barrel, turret, x, y, angle);
    }

    private void addLayer(List<Layer> layers, String path, boolean turret, float x, float y, float angle) {
        SpriteAPI sprite = sprite(path);
        if (sprite != null) layers.add(new Layer(sprite, sprite.getWidth(), sprite.getHeight(), sprite.getWidth() / 2f,
                sprite.getHeight() * (turret ? 0.5f : 0.25f), x, y, angle));
    }

    private SpriteAPI sprite(String path) {
        if (path == null || path.isEmpty()) return null;
        if (!sprites.containsKey(path)) {
            try { sprites.put(path, Global.getSettings().getSprite(path)); }
            catch (RuntimeException ex) { sprites.put(path, null); }
        }
        return sprites.get(path);
    }

    private WeaponSpecAPI weapon(String id) {
        if (!weapons.containsKey(id)) {
            try { weapons.put(id, Global.getSettings().getWeaponSpec(id)); }
            catch (RuntimeException ex) { weapons.put(id, null); }
        }
        return weapons.get(id);
    }

    private static float[] readHullCenter(ShipHullSpecAPI hull, SpriteAPI sprite) {
        try {
            JSONObject json = Global.getSettings().loadJSON(hull.getShipFilePath());
            JSONArray center = json.getJSONArray("center");
            return new float[]{(float) center.getDouble(0), (float) center.getDouble(1)};
        } catch (Exception ex) { return new float[]{sprite.getWidth() / 2f, sprite.getHeight() / 2f}; }
    }

    private static boolean barrelBelow(String id) {
        try {
            JSONArray hints = Global.getSettings().loadJSON("data/weapons/" + id + ".wpn").optJSONArray("renderHints");
            if (hints != null) for (int i = 0; i < hints.length(); i++) {
                if ("RENDER_BARREL_BELOW".equals(hints.optString(i))) return true;
            }
        } catch (Exception ex) { /* Some third-party weapon paths differ; the engine default is above. */ }
        return false;
    }
}
