package data.campaign.fighter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;

/** Cross-save catalog, loaded before save deserialization. Never prune historical definitions. */
public final class Moci_FighterLoadoutStore {
    static final String FILE_NAME = "moci_fighter_loadouts_v1.json";
    static final String BACKUP_FILE = FILE_NAME + ".backup";
    static final String PENDING_FILE = FILE_NAME + ".pending";
    private static final Map<String, Moci_FighterLoadoutSpec> definitions = new LinkedHashMap<>();
    private static String failure;
    private static boolean loaded;
    private Moci_FighterLoadoutStore() {}

    static synchronized void initialize() {
        definitions.clear(); loaded = true; failure = null;
        SettingsAPI settings = Global.getSettings();
        if (settings == null) { failure = "游戏配置尚未初始化"; return; }
        try {
            if (settings.fileExistsInCommon(FILE_NAME)) {
                definitions.putAll(parse(settings.readTextFileFromCommon(FILE_NAME)));
            } else if (settings.fileExistsInCommon(BACKUP_FILE) || settings.fileExistsInCommon(PENDING_FILE)) {
                throw new IllegalStateException("装配注册库缺失");
            }
        } catch (Exception ex) {
            failure = "装配注册库异常，已进入只读保护：" + ex.getMessage();
            for (String recovery : List.of(BACKUP_FILE, PENDING_FILE)) {
                try {
                    if (!settings.fileExistsInCommon(recovery)) continue;
                    definitions.putAll(parse(settings.readTextFileFromCommon(recovery)));
                    break;
                } catch (Exception backupFailure) {
                    Global.getLogger(Moci_FighterLoadoutStore.class).warn("Fighter catalog recovery unavailable: " + recovery, backupFailure);
                }
            }
        }
    }
    private static void ensureLoaded() { if (!loaded) initialize(); }
    static synchronized String getFailure() { ensureLoaded(); return failure; }
    static synchronized Moci_FighterLoadoutSpec get(String wingId) { ensureLoaded(); return definitions.get(wingId); }
    static synchronized List<Moci_FighterLoadoutSpec> all() { ensureLoaded(); return new ArrayList<>(definitions.values()); }
    static synchronized boolean verifyDurableCatalog() {
        ensureLoaded();
        if (failure != null) return false;
        try {
            verify(parse(Global.getSettings().readTextFileFromCommon(FILE_NAME)), definitions);
            verify(parse(Global.getSettings().readTextFileFromCommon(BACKUP_FILE)), definitions);
            return true;
        } catch (Exception ex) {
            failure = "保存的装配注册库无法读回，已阻止提交：" + ex.getMessage();
            return false;
        }
    }

    /** True only after the full catalog has been durably written and read back. */
    static synchronized boolean persist(Moci_FighterLoadoutSpec spec) {
        ensureLoaded();
        if (failure != null || spec == null) return false;
        Moci_FighterLoadoutSpec old = definitions.get(spec.getWingId());
        if (old != null) {
            if (old.sameDefinition(spec)) {
                return verifyDurableCatalog();
            }
            failure = "装配型号 ID 冲突，已进入只读保护";
            return false;
        }
        SettingsAPI settings = Global.getSettings();
        String previous = null;
        boolean mainWriteAttempted = false;
        try {
            boolean existed = settings.fileExistsInCommon(FILE_NAME);
            if (existed) {
                previous = settings.readTextFileFromCommon(FILE_NAME);
                verify(parse(previous), definitions);
            } else if (!definitions.isEmpty()) {
                throw new IllegalStateException("Existing catalog disappeared");
            }
            Map<String, Moci_FighterLoadoutSpec> next = new LinkedHashMap<>(definitions);
            next.put(spec.getWingId(), spec);
            String text = serialize(next);
            settings.writeTextFileToCommon(PENDING_FILE, text);
            verify(parse(settings.readTextFileFromCommon(PENDING_FILE)), next);
            // The empty initial catalog is a valid recovery point as well.
            String backup = previous == null ? serialize(definitions) : previous;
            settings.writeTextFileToCommon(BACKUP_FILE, backup);
            verify(parse(settings.readTextFileFromCommon(BACKUP_FILE)), definitions);
            mainWriteAttempted = true;
            settings.writeTextFileToCommon(FILE_NAME, text);
            verify(parse(settings.readTextFileFromCommon(FILE_NAME)), next);
            // Both durable copies contain every committed definition before any asset changes.
            // Pending remains a third recovery source if a failed write damages either copy.
            settings.writeTextFileToCommon(BACKUP_FILE, text);
            verify(parse(settings.readTextFileFromCommon(BACKUP_FILE)), next);
            definitions.clear(); definitions.putAll(next);
            return true;
        } catch (Exception ex) {
            failure = "装配定义保存失败，未提交换装；旧库或备份已保留：" + ex.getMessage();
            if (mainWriteAttempted && previous != null) {
                try {
                    settings.writeTextFileToCommon(FILE_NAME, previous);
                    verify(parse(settings.readTextFileFromCommon(FILE_NAME)), definitions);
                } catch (Exception restoreFailure) {
                    Global.getLogger(Moci_FighterLoadoutStore.class).error("Fighter catalog restore failed; retain backup", restoreFailure);
                }
            }
            Global.getLogger(Moci_FighterLoadoutStore.class).warn(failure, ex);
            return false;
        }
    }
    private static String serialize(Map<String, Moci_FighterLoadoutSpec> values) throws org.json.JSONException {
        JSONObject root = new JSONObject(); root.put("version", 1);
        JSONArray entries = new JSONArray();
        for (Moci_FighterLoadoutSpec spec : values.values()) entries.put(spec.toJSON());
        root.put("definitions", entries);
        return root.toString(2);
    }
    private static Map<String, Moci_FighterLoadoutSpec> parse(String text) throws org.json.JSONException {
        JSONObject root = new JSONObject(text);
        if (root.getInt("version") != 1) throw new IllegalArgumentException("Unsupported catalog version");
        Map<String, Moci_FighterLoadoutSpec> result = new LinkedHashMap<>();
        JSONArray entries = root.getJSONArray("definitions");
        for (int i = 0; i < entries.length(); i++) {
            Moci_FighterLoadoutSpec spec = Moci_FighterLoadoutSpec.fromJSON(entries.getJSONObject(i));
            Moci_FighterLoadoutSpec old = result.put(spec.getWingId(), spec);
            if (old != null) throw new IllegalArgumentException("Duplicate catalog ID");
        }
        return result;
    }
    private static void verify(Map<String, Moci_FighterLoadoutSpec> actual,
            Map<String, Moci_FighterLoadoutSpec> expected) {
        if (!actual.keySet().equals(expected.keySet())) throw new IllegalArgumentException("Catalog read-back differs");
        for (String id : expected.keySet()) {
            if (!expected.get(id).sameDefinition(actual.get(id))) throw new IllegalArgumentException("Definition read-back differs");
        }
    }
}
