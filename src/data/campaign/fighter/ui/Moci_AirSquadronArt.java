package data.campaign.fighter.ui;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.lwjgl.util.vector.Vector2f;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.loading.ProjectileWeaponSpecAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;

/** Per-dialog art cache. Every resource/spec lookup happens while constructing a preview. */
public final class Moci_AirSquadronArt {
    private final Map<String, SpriteAPI> sprites = new HashMap<>();
    private final Map<String, WeaponSpecAPI> weapons = new HashMap<>();
    private final Map<String, float[]> hullCenters = new HashMap<>();
    private final Map<String, Boolean> barrelsBelow = new HashMap<>();
    private final Map<String, float[]> visibleBounds = new HashMap<>();

    public static final class Model {
        final List<Layer> layers;
        final float minX, minY, maxX, maxY;
        Model(List<Layer> layers) {
            this.layers = List.copyOf(layers);
            float x0 = Float.POSITIVE_INFINITY, y0 = x0, x1 = Float.NEGATIVE_INFINITY, y1 = x1;
            for (Layer layer : layers) {
                if (layer.bounds[0] >= layer.bounds[2] || layer.bounds[1] >= layer.bounds[3]) continue;
                double angle = Math.toRadians(layer.angle);
                float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
                for (int i = 0; i < 4; i++) {
                    float x = layer.bounds[i % 2 == 0 ? 0 : 2] - layer.pivotX;
                    float y = layer.bounds[i < 2 ? 1 : 3] - layer.pivotY;
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
            render(transform, alpha, Color.WHITE);
        }
        void render(Moci_AirSquadronLayout.Transform transform, float alpha, Color tint) {
            for (Layer layer : layers) layer.render(transform, alpha, tint);
        }
        /** Fit the visible model into a centered fraction of the frame, preserving its aspect ratio. */
        public void addToPanel(CustomPanelAPI parent, float width, float height, float contentFraction) {
            float w = width * contentFraction, h = height * contentFraction;
            CustomPanelAPI canvas = parent.createCustomPanel(w, h,
                    new Moci_AirSquadronCanvas(this, false, false, 0, 0, null, false));
            parent.addComponent(canvas).inTL((width - w) / 2f, (height - h) / 2f);
        }
    }

    private record Layer(SpriteAPI sprite, String path, float width, float height, float pivotX, float pivotY,
                         float x, float y, float angle, float[] bounds) {
        void render(Moci_AirSquadronLayout.Transform t, float alpha, Color tint) {
            float oldW = sprite.getWidth(), oldH = sprite.getHeight(), oldX = sprite.getCenterX(), oldY = sprite.getCenterY();
            float oldAngle = sprite.getAngle(), oldAlpha = sprite.getAlphaMult();
            Color oldColor = sprite.getColor();
            int src = sprite.getBlendSrc(), dst = sprite.getBlendDest();
            try {
                sprite.setSize(width * t.scale(), height * t.scale());
                sprite.setCenter(pivotX * t.scale(), pivotY * t.scale());
                sprite.setAngle(angle); sprite.setColor(tint); sprite.setAlphaMult(alpha);
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
        return new Model(List.of(new Layer(sprite, path, w, h, w / 2f, h / 2f, 0, 0, 0, new float[]{0, 0, w, h})));
    }

    public Model build(ShipVariantAPI variant) {
        List<Layer> layers = new ArrayList<>();
        appendVariant(layers, variant, 0, 0, 0, Collections.newSetFromMap(new IdentityHashMap<>()), true);
        return new Model(layers);
    }

    /** Ship overview: decorations and built-in weapons; editable fighter previews use build instead. */
    public Model buildShipPreview(ShipVariantAPI variant) {
        List<Layer> layers = new ArrayList<>();
        appendVariant(layers, variant, 0, 0, 0, Collections.newSetFromMap(new IdentityHashMap<>()), false);
        return new Model(layers);
    }

    Model weaponIcon(String id) {
        if (id == null || id.isEmpty()) return new Model(List.of());
        WeaponSpecAPI weapon = weapon(id);
        List<Layer> layers = new ArrayList<>();
        appendWeapon(layers, weapon, true, 0, 0, 0);
        return new Model(layers);
    }

    private void appendVariant(List<Layer> layers, ShipVariantAPI variant, float ox, float oy, float angle, Set<ShipVariantAPI> ancestors,
                               boolean includeFittedWeapons) {
        if (variant == null || !ancestors.add(variant)) return;
        ShipHullSpecAPI hull = variant.getHullSpec();
        SpriteAPI hullSprite = sprite(hull.getSpriteName());
        if (hullSprite != null) {
            float[] center = hullCenters.computeIfAbsent(hull.getHullId(), id -> readHullCenter(hull, hullSprite));
            layers.add(new Layer(hullSprite, hull.getSpriteName(), hullSprite.getWidth(), hullSprite.getHeight(), center[0], center[1], ox, oy, angle,
                    bounds(hull.getSpriteName(), hullSprite)));
        }
        Map<String, String> fitted = new LinkedHashMap<>(hull.getBuiltInWeapons());
        Map<String, String> modules = variant.getStationModules();
        List<WeaponSlotAPI> slots = new ArrayList<>(hull.getAllWeaponSlotsCopy());
        // getFittedWeaponSlots excludes decorations. Query every hull slot to include variant decorations too.
        for (WeaponSlotAPI slot : slots) {
            String weaponId = variant.getWeaponId(slot.getId());
            if (weaponId != null && !weaponId.isEmpty()) fitted.put(slot.getId(), weaponId);
        }
        slots.sort(Comparator.comparingDouble((WeaponSlotAPI slot) -> {
            String id = fitted.get(slot.getId());
            WeaponSpecAPI spec = id == null ? null : weapon(id);
            return (spec != null && spec.isRenderAboveAllWeapons() ? 10000 : 0) + slot.getRenderOrderMod();
        }));
        for (WeaponSlotAPI slot : slots) {
            // Hidden weapon mounts are not drawn, but a module mounted there is still a hull.
            if (slot.isHidden() && (modules == null || !modules.containsKey(slot.getId()))) continue;
            float x = -slot.getLocation().y, y = slot.getLocation().x;
            double a = Math.toRadians(angle);
            float px = ox + x * (float) Math.cos(a) - y * (float) Math.sin(a);
            float py = oy + x * (float) Math.sin(a) + y * (float) Math.cos(a);
            String id = fitted.get(slot.getId());
            boolean visibleWeapon = includeFittedWeapons || slot.isDecorative() || slot.isBuiltIn()
                    || hull.getBuiltInWeapons().containsKey(slot.getId());
            if (!slot.isHidden() && visibleWeapon && id != null && !id.isEmpty()) {
                appendWeapon(layers, weapon(id), !slot.isHardpoint(), px, py, angle + slot.getAngle());
            }
            ShipVariantAPI module = modules == null || !modules.containsKey(slot.getId()) ? null : variant.getModuleVariant(slot.getId());
            if (module != null) {
                float moduleAngle = angle + slot.getAngle();
                double ma = Math.toRadians(moduleAngle);
                Vector2f anchor = module.getHullSpec().getModuleAnchor();
                float anchorX = anchor == null ? 0 : -anchor.y;
                float anchorY = anchor == null ? 0 : anchor.x;
                px -= anchorX * (float) Math.cos(ma) - anchorY * (float) Math.sin(ma);
                py -= anchorX * (float) Math.sin(ma) + anchorY * (float) Math.cos(ma);
                appendVariant(layers, module, px, py, moduleAngle, ancestors, includeFittedWeapons);
            }
        }
        ancestors.remove(variant);
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
        if (sprite != null) layers.add(new Layer(sprite, path, sprite.getWidth(), sprite.getHeight(), sprite.getWidth() / 2f,
                sprite.getHeight() * (turret ? 0.5f : 0.25f), x, y, angle, bounds(path, sprite)));
    }

    /** Cache alpha bounds once per dialog; transparent padding must not shrink the visible ship. */
    private float[] bounds(String path, SpriteAPI sprite) {
        return visibleBounds.computeIfAbsent(path, key -> {
            float w = sprite.getWidth(), h = sprite.getHeight();
            try (InputStream stream = Global.getSettings().openStream(key)) {
                BufferedImage image = ImageIO.read(stream);
                if (image == null) return new float[]{0, 0, w, h};
                int iw = image.getWidth(), ih = image.getHeight();
                int minX = iw, minY = ih, maxX = 0, maxY = 0;
                for (int y = 0; y < ih; y++) for (int x = 0; x < iw; x++) {
                    if ((image.getRGB(x, y) >>> 24) == 0) continue;
                    minX = Math.min(minX, x); minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x + 1); maxY = Math.max(maxY, y + 1);
                }
                if (minX >= maxX || minY >= maxY) return new float[]{0, 0, 0, 0};
                return new float[]{minX * w / iw, (ih - maxY) * h / ih, maxX * w / iw, (ih - minY) * h / ih};
            } catch (IOException ex) {
                return new float[]{0, 0, w, h};
            }
        });
    }

    private SpriteAPI sprite(String path) {
        if (path == null || path.isEmpty()) return null;
        // Campaign previews may reference weapons whose textures have not been used in combat yet.
        if (!sprites.containsKey(path)) {
            try {
                Global.getSettings().loadTexture(path);
                SpriteAPI sprite = Global.getSettings().getSprite(path);
                sprites.put(path, sprite.getWidth() > 0 && sprite.getHeight() > 0 ? sprite : null);
            } catch (IOException ex) {
                Global.getLogger(Moci_AirSquadronArt.class).warn("无法加载预览贴图：" + path, ex);
                sprites.put(path, null);
            }
        }
        return sprites.get(path);
    }

    private WeaponSpecAPI weapon(String id) {
        if (id == null || id.isEmpty()) return null;
        // 这里的 ID 只来自船体内置武器表、引擎已解析的装配（addWeapon 会拒绝未知 ID）和候选武器列表，
        // 都是已注册的武器；getWeaponSpec 只在 ID 未注册时才抛异常，因此不需要兜底。缓存同样记住 null。
        if (!weapons.containsKey(id)) weapons.put(id, Global.getSettings().getWeaponSpec(id));
        return weapons.get(id);
    }

    private static float[] readHullCenter(ShipHullSpecAPI hull, SpriteAPI sprite) {
        JSONObject json = loadJson(hull.getShipFilePath());
        JSONArray center = json == null ? null : json.optJSONArray("center");
        if (center == null || center.length() < 2) return new float[]{sprite.getWidth() / 2f, sprite.getHeight() / 2f};
        return new float[]{(float) center.optDouble(0, sprite.getWidth() / 2f), (float) center.optDouble(1, sprite.getHeight() / 2f)};
    }

    private static boolean barrelBelow(String id) {
        if (id == null || id.isEmpty()) return false;
        // 第三方武器的 .wpn 不一定在 data/weapons/ 根目录下；读不到时按引擎默认（炮管在上）处理。
        JSONObject json = loadJson("data/weapons/" + id + ".wpn");
        JSONArray hints = json == null ? null : json.optJSONArray("renderHints");
        if (hints != null) for (int i = 0; i < hints.length(); i++) {
            if ("RENDER_BARREL_BELOW".equals(hints.optString(i))) return true;
        }
        return false;
    }

    /**
     * 读取一个数据文件的 JSON；路径不合规或文件读不到时返回 null。
     * 贴图中心和 RENDER_BARREL_BELOW 都不在 API 规格对象里，只能读原始文件。
     */
    private static JSONObject loadJson(String path) {
        if (!loadablePath(path)) return null;
        try {
            return Global.getSettings().loadJSON(path);
        } catch (IOException | JSONException | RuntimeException ex) {
            // 保留：SettingsAPI 没有判断 data 文件是否存在的方法（fileExistsInCommon 只管 common 目录），
            // 引擎找不到文件时抛的就是裸 RuntimeException；读失败是 IOException，内容损坏是 JSONException。
            return null;
        }
    }

    /** 与引擎 loadJSON 的路径检查一致：拒绝空路径、「..」、以「/」开头、反斜杠和盘符冒号。 */
    private static boolean loadablePath(String path) {
        if (path == null || path.isBlank()) return false;
        String trimmed = path.trim();
        return !trimmed.contains("..") && !trimmed.startsWith("/") && !trimmed.contains("\\") && !trimmed.contains(":");
    }
}
