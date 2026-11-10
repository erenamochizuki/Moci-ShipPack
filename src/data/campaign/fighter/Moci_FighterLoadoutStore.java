package data.campaign.fighter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;

/** Cross-save catalog, loaded before save deserialization. Retain all supported definitions. */
public final class Moci_FighterLoadoutStore {
    static final String FILE_NAME = "moci_fighter_loadouts_v1.json";
    static final String BACKUP_FILE = FILE_NAME + ".backup";
    static final String PENDING_FILE = FILE_NAME + ".pending";
    /**
     * SettingsAPI.writeTextFileToCommon 的单文件上限（0.98a StarfarerSettings$1 写死 1048576 个字符，
     * 超出直接抛 RuntimeException）。写盘前先按这个上限检查，给出明确原因。
     */
    static final int MAX_COMMON_FILE_CHARS = 1048576;
    private static final Map<String, Moci_FighterLoadoutSpec> definitions = new LinkedHashMap<>();
    /** 目录级失败：注册库无法读取或无法写入，整库进入只读保护。 */
    private static String failure;
    /** 事务级失败：上一次落库或回滚失败；只用于提示，下一次成功写盘或读档即清空。 */
    private static String lastCommitFailure;
    private static boolean loaded;
    private Moci_FighterLoadoutStore() {}

    static synchronized void initialize() {
        definitions.clear(); loaded = true; failure = null; lastCommitFailure = null;
        SettingsAPI settings = Global.getSettings();
        if (settings == null) { failure = "游戏配置尚未初始化"; return; }
        // 三份副本都参与恢复：main 优先，其余只补缺失条目；单份损坏不拖累其余副本。
        List<String> damaged = new ArrayList<>();
        Map<String, Moci_FighterLoadoutSpec> main = parseCopy(settings, FILE_NAME, damaged);
        mergeCopy(main);
        mergeCopy(parseCopy(settings, BACKUP_FILE, damaged));
        mergeCopy(parseCopy(settings, PENDING_FILE, damaged));
        boolean mainMissing = main == null && !definitions.isEmpty();
        boolean mainIncomplete = main != null && !main.keySet().equals(definitions.keySet());
        if (damaged.isEmpty() && !mainMissing && !mainIncomplete) return;
        String reason = !damaged.isEmpty() ? "副本损坏：" + String.join(", ", damaged)
                : mainMissing ? "主文件缺失" : "副本内容不一致";
        if (definitions.isEmpty()) {
            // 只存在损坏副本时不要用空库覆盖它，保持只读让人工/后续版本抢救。
            failure = "装配注册库异常，已进入只读保护：" + reason;
            Global.getLogger(Moci_FighterLoadoutStore.class).warn(failure);
            return;
        }
        if (!selfHeal(reason)) {
            failure = "装配注册库异常且无法自愈，已进入只读保护：" + reason;
            return;
        }
        Global.getLogger(Moci_FighterLoadoutStore.class).warn(
                "装配注册库已自愈（" + reason + "）：" + definitions.size() + " 条定义已写回 " + FILE_NAME);
    }
    /** 读取一份副本：不存在返回 null（不算损坏）；存在但读不出或校验失败记入 damaged。 */
    private static Map<String, Moci_FighterLoadoutSpec> parseCopy(SettingsAPI settings, String name, List<String> damaged) {
        if (!settings.fileExistsInCommon(name)) return null;
        try {
            return parse(settings.readTextFileFromCommon(name));
        } catch (IOException | JSONException | IllegalArgumentException ex) {
            // 文件 IO 与 JSON 解析：副本内容只有读出来才知道能不能用；IllegalArgumentException 是 parse/fromJSON 的格式校验。
            damaged.add(name);
            Global.getLogger(Moci_FighterLoadoutStore.class).warn("Fighter catalog copy unusable: " + name, ex);
            return null;
        }
    }
    private static void mergeCopy(Map<String, Moci_FighterLoadoutSpec> copy) {
        if (copy == null) return;
        for (Map.Entry<String, Moci_FighterLoadoutSpec> entry : copy.entrySet()) {
            definitions.putIfAbsent(entry.getKey(), entry.getValue());
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
            Map<String, Moci_FighterLoadoutSpec> main = readCopy(FILE_NAME);
            Map<String, Moci_FighterLoadoutSpec> backup = readCopy(BACKUP_FILE);
            if (definitions.isEmpty() && main == null && backup == null) return true;
            verify(main == null ? Map.of() : main, definitions);
            verify(backup == null ? Map.of() : backup, definitions);
            return true;
        } catch (IOException | JSONException | IllegalArgumentException ex) {
            // 文件 IO 与 JSON 解析/读回校验。读回不一致是可自愈的：内存里的库才是权威，把两份耐久副本整体重写一遍。
            Global.getLogger(Moci_FighterLoadoutStore.class).warn(
                    "Fighter catalog read-back differs; rewriting durable copies from memory", ex);
            if (selfHeal("落库副本与内存不一致")) return true;
            failure = "保存的装配注册库无法读回，已阻止提交：" + ex.getMessage();
            return false;
        }
    }
    private static Map<String, Moci_FighterLoadoutSpec> readCopy(String name) throws JSONException, IOException {
        SettingsAPI settings = Global.getSettings();
        if (settings == null || !settings.fileExistsInCommon(name)) return null;
        return parse(settings.readTextFileFromCommon(name));
    }
    /**
     * 用内存里的完整定义重写三份副本（pending → main → backup），读回校验通过才算成功。
     * 成功即说明目录可写，一并解除只读保护；失败保留原有文件不动。
     */
    private static boolean selfHeal(String reason) {
        SettingsAPI settings = Global.getSettings();
        if (settings == null) return false;
        try {
            String text = serialize(definitions);
            String oversize = oversizeReason(text);
            if (oversize != null) {
                Global.getLogger(Moci_FighterLoadoutStore.class).warn("Fighter catalog self-heal skipped: " + reason + "; " + oversize);
                return false;
            }
            writeAndVerify(settings, PENDING_FILE, text, definitions);
            writeAndVerify(settings, FILE_NAME, text, definitions);
            writeAndVerify(settings, BACKUP_FILE, text, definitions);
            failure = null; lastCommitFailure = null;
            return true;
        } catch (IOException | JSONException | RuntimeException ex) {
            // 文件 IO：写盘失败只能在写的时候知道。RuntimeException 包括读回校验失败，以及引擎在本次运行累计
            // 写入超过上限（totalWriteLimit）时抛的异常：这个计数在引擎内部，无法事先查询。
            Global.getLogger(Moci_FighterLoadoutStore.class).warn("Fighter catalog self-heal failed: " + reason, ex);
            return false;
        }
    }
    private static void writeAndVerify(SettingsAPI settings, String name, String text,
            Map<String, Moci_FighterLoadoutSpec> expected) throws IOException, JSONException {
        settings.writeTextFileToCommon(name, text);
        verify(parse(settings.readTextFileFromCommon(name)), expected);
    }
    /** 单文件超过引擎上限时返回原因，否则 null。 */
    private static String oversizeReason(String text) {
        return text.length() <= MAX_COMMON_FILE_CHARS ? null
                : "注册库文本 " + text.length() + " 字符，超过单文件上限 " + MAX_COMMON_FILE_CHARS;
    }
    /**
     * 存档内嵌副本用：只导出给定 ID 中内存里有的定义，格式与注册库文件相同。
     * 内存里没有的 ID 直接跳过，由调用方决定怎么处理。
     */
    static synchronized String exportJson(Set<String> wingIds) {
        return exportJson(wingIds, null, null);
    }
    /**
     * 存档内嵌副本用：只导出给定 ID 的定义。内存里没有的从 {@code fallbackJson}（存档里原来的内嵌副本）取，
     * 仍取不到的 ID 写进 {@code unresolved}。不改内存、不写注册库文件：从旧副本补回的定义在下次读档时由
     * {@link #importMissing} 正式回灌。
     */
    static synchronized String exportJson(Set<String> wingIds, String fallbackJson, Set<String> unresolved) {
        ensureLoaded();
        Map<String, Moci_FighterLoadoutSpec> fallback = null;
        Map<String, Moci_FighterLoadoutSpec> selected = new LinkedHashMap<>();
        for (String id : new TreeSet<>(wingIds)) {
            Moci_FighterLoadoutSpec spec = definitions.get(id);
            if (spec == null && fallbackJson != null) {
                if (fallback == null) fallback = parseEmbedded(fallbackJson);
                spec = fallback.get(id);
            }
            if (spec != null) selected.put(id, spec);
            else if (unresolved != null) unresolved.add(id);
        }
        try {
            return serialize(selected);
        } catch (JSONException ex) {
            // org.json 的 put/toString 声明了受检的 JSONException，但定义里只有字符串，实际不会抛；这里只是满足编译器。
            throw new IllegalStateException("Cannot export fighter catalog", ex);
        }
    }
    /** 解析存档内嵌副本；空串或读不出返回空表，并记一条 warn。 */
    private static Map<String, Moci_FighterLoadoutSpec> parseEmbedded(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return parse(json);
        } catch (JSONException | IllegalArgumentException ex) {
            // JSON 解析：存档里的字符串只有解析了才知道是否完好；IllegalArgumentException 是 parse/fromJSON 的格式校验。
            Global.getLogger(Moci_FighterLoadoutStore.class).warn("Embedded fighter catalog is unreadable", ex);
            return Map.of();
        }
    }
    /**
     * 存档内嵌副本回灌：只合并缺失且能通过校验的定义，不抛异常，返回新增条数。
     * 读档时调用，用来恢复「注册库文件被删/损坏」造成的定义丢失。
     * 注册库处于只读保护时只合并进内存（本局可用），不写盘、不解除只读：
     * 损坏的文件里可能还有内嵌副本没有的定义，不能用一个存档的子集覆盖它们。
     */
    static synchronized int importMissing(String json) {
        ensureLoaded();
        int added = 0;
        for (Map.Entry<String, Moci_FighterLoadoutSpec> entry : parseEmbedded(json).entrySet()) {
            if (definitions.containsKey(entry.getKey())) continue;
            definitions.put(entry.getKey(), entry.getValue());
            added++;
        }
        if (added == 0) return 0;
        if (failure != null) {
            Global.getLogger(Moci_FighterLoadoutStore.class).warn("Embedded fighter catalog loaded into memory only ("
                    + added + " definition(s)); catalog stays read-only: " + failure);
            return added;
        }
        if (!selfHeal("存档内嵌副本回灌")) lastCommitFailure = "存档内嵌的装配定义已恢复但仍无法写回注册库";
        return added;
    }
    /** 已加载的定义条数，用于常驻日志与提示。 */
    static synchronized int size() {
        ensureLoaded();
        return definitions.size();
    }
    /** 面向玩家的失败原因：事务级优先，其次目录级只读原因。 */
    static synchronized String describeFailure() {
        ensureLoaded();
        if (lastCommitFailure != null) return lastCommitFailure;
        return failure == null ? "装配定义保存失败，未提交换装" : failure;
    }

    /**
     * True only after the new definition has been durably written and read back.
     *
     * <p>主文件里的每条定义都必须在内存里且内容相同：这保证写盘不会丢掉磁盘上有、内存里没有的定义。
     * 内存比磁盘多是允许的（例如读档回灌或自愈时写盘失败），这时写入的是并集，顺带补齐磁盘。
     * 主文件缺失按空集合处理。
     */
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
        Map<String, Moci_FighterLoadoutSpec> disk = Map.of();
        boolean mainWriteAttempted = false;
        try {
            if (settings.fileExistsInCommon(FILE_NAME)) {
                previous = settings.readTextFileFromCommon(FILE_NAME);
                disk = parse(previous);
            }
            String unloaded = firstDiskOnlyDefinition(disk);
            if (unloaded != null) {
                return commitFailed("注册库文件里有本次运行未载入的定义 " + unloaded
                        + "，为避免覆盖已停止写入；重启游戏后会自动合并", null);
            }
            Map<String, Moci_FighterLoadoutSpec> next = new LinkedHashMap<>(definitions);
            next.put(spec.getWingId(), spec);
            String text = serialize(next);
            String oversize = oversizeReason(text);
            if (oversize != null) return commitFailed(oversize, null);
            writeAndVerify(settings, PENDING_FILE, text, next);
            // 写主文件前，backup 先保存内存里的完整旧库：它包含主文件的全部定义，是可靠的恢复点。
            writeAndVerify(settings, BACKUP_FILE, serialize(definitions), definitions);
            mainWriteAttempted = true;
            writeAndVerify(settings, FILE_NAME, text, next);
            // Both durable copies contain every committed definition before any asset changes.
            // Pending remains a third recovery source if a failed write damages either copy.
            writeAndVerify(settings, BACKUP_FILE, text, next);
            definitions.clear(); definitions.putAll(next);
            lastCommitFailure = null;
            return true;
        } catch (IOException | JSONException | RuntimeException ex) {
            // 事务回滚：文件 IO/JSON 解析只能在读写时发现，RuntimeException 包括读回校验失败和引擎内部的累计写入上限。
            // 失败后把主文件还原成写入前的内容，再返回失败；内存保持旧库。
            if (mainWriteAttempted && previous != null) restoreMain(settings, previous, disk);
            return commitFailed(ex.getMessage(), ex);
        }
    }
    /** 主文件里内存没有（或内容不同）的第一条定义 ID；全部包含时返回 null。 */
    private static String firstDiskOnlyDefinition(Map<String, Moci_FighterLoadoutSpec> disk) {
        for (Map.Entry<String, Moci_FighterLoadoutSpec> entry : disk.entrySet()) {
            if (!entry.getValue().sameDefinition(definitions.get(entry.getKey()))) return entry.getKey();
        }
        return null;
    }
    private static boolean commitFailed(String reason, Exception cause) {
        lastCommitFailure = "装配定义保存失败，未提交换装；旧库或备份已保留：" + reason;
        if (cause == null) Global.getLogger(Moci_FighterLoadoutStore.class).warn(lastCommitFailure);
        else Global.getLogger(Moci_FighterLoadoutStore.class).warn(lastCommitFailure, cause);
        return false;
    }
    private static void restoreMain(SettingsAPI settings, String previous, Map<String, Moci_FighterLoadoutSpec> disk) {
        try {
            settings.writeTextFileToCommon(FILE_NAME, previous);
            verify(parse(settings.readTextFileFromCommon(FILE_NAME)), disk);
        } catch (IOException | JSONException | RuntimeException restoreFailure) {
            // 事务回滚本身的写盘也可能失败；backup 与 pending 仍保留完整内容，下次启动由 initialize 合并。
            Global.getLogger(Moci_FighterLoadoutStore.class).error("Fighter catalog restore failed; retain backup", restoreFailure);
        }
    }
    private static String serialize(Map<String, Moci_FighterLoadoutSpec> values) throws JSONException {
        JSONObject root = new JSONObject(); root.put("version", 1);
        JSONArray entries = new JSONArray();
        for (Moci_FighterLoadoutSpec spec : values.values()) entries.put(spec.toJSON());
        root.put("definitions", entries);
        return root.toString(2);
    }
    private static Map<String, Moci_FighterLoadoutSpec> parse(String text) throws JSONException {
        JSONObject root = new JSONObject(text);
        if (root.getInt("version") != 1) throw new IllegalArgumentException("Unsupported catalog version");
        Map<String, Moci_FighterLoadoutSpec> result = new LinkedHashMap<>();
        JSONArray entries = root.getJSONArray("definitions");
        for (int i = 0; i < entries.length(); i++) {
            // 只接受现行格式：缺 `_wing` 后缀或指纹不符的记录由 fromJSON 拒绝，整份副本按损坏处理。
            Moci_FighterLoadoutSpec spec = Moci_FighterLoadoutSpec.fromJSON(entries.getJSONObject(i));
            if (result.put(spec.getWingId(), spec) != null) throw new IllegalArgumentException("Duplicate catalog ID");
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
