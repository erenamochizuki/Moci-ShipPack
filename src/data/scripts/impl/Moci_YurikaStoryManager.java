package data.scripts.impl;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;

public final class Moci_YurikaStoryManager {
    public enum StoryMissionType {
        REMNANT,
        NADE_A,
        NADE_B,
        TDD
    }

    private static final String KEY_AFFINITY = "$moci_yurika_affinity";
    private static final String KEY_REMNANT_JOBS = "$moci_yurika_remnant_job_count";
    private static final String KEY_STORY_A = "$moci_yurika_story_a_completed";
    private static final String KEY_STORY_B = "$moci_yurika_story_b_completed";
    private static final String KEY_STORY_TDD = "$moci_yurika_story_tdd_completed";
    private static final String KEY_PENDING_REPORT = "$moci_yurika_pending_report_text";
    private static final String KEY_PENDING_FAILURE = "$moci_yurika_pending_report_is_failure";

    private static final float MAX_AFFINITY = 100f;
    private static final int MAX_REMNANT_JOBS = 1000000;

    private static final float AFFINITY_THRESHOLD_A = 20f;
    private static final float AFFINITY_THRESHOLD_B = 40f;
    private static final float AFFINITY_THRESHOLD_TDD = 60f;

    private Moci_YurikaStoryManager() {
    }

    public static void ensureLoaded() {
        MemoryAPI mem = globalMem();
        if (!mem.contains(KEY_AFFINITY)) {
            mem.set(KEY_AFFINITY, 0f);
        }
        if (!mem.contains(KEY_REMNANT_JOBS)) {
            mem.set(KEY_REMNANT_JOBS, 0);
        }
        ensureDefault(mem, KEY_STORY_A);
        ensureDefault(mem, KEY_STORY_B);
        ensureDefault(mem, KEY_STORY_TDD);
        if (!mem.contains(KEY_PENDING_FAILURE)) {
            mem.set(KEY_PENDING_FAILURE, false);
        }
        sanitizePendingReport(mem);
    }

    public static void addAffinity(float amount) {
        changeAffinity(amount, true);
    }

    private static void changeAffinity(float amount, boolean playSound) {
        if (amount == 0f || Float.isNaN(amount)) {
            return;
        }
        MemoryAPI mem = globalMem();
        float current = mem.getFloat(KEY_AFFINITY);
        if (Float.isNaN(current) || Float.isInfinite(current)) {
            current = 0f;
        }
        float next = current + amount;
        if (Float.isNaN(next)) {
            next = current;
        } else if (Float.isInfinite(next)) {
            next = amount > 0f ? MAX_AFFINITY : 0f;
        }
        float clamped = Math.max(0f, Math.min(MAX_AFFINITY, next));
        mem.set(KEY_AFFINITY, clamped);
        float actualChange = clamped - current;
        if (playSound && actualChange != 0f) {
            Moci_CampaignFeedback.playAffinityChanged(actualChange);
        }
    }

    public static float getAffinity() {
        return globalMem().getFloat(KEY_AFFINITY);
    }

    public static void incrementRemnantJobCount() {
        MemoryAPI mem = globalMem();
        int count = mem.getInt(KEY_REMNANT_JOBS);
        mem.set(KEY_REMNANT_JOBS, count >= MAX_REMNANT_JOBS ? MAX_REMNANT_JOBS : count + 1);
    }

    public static float completeRemnantJob(float affinityGain) {
        // The mission completion feedback supplies the single positive sound for this reward.
        changeAffinity(affinityGain, false);
        incrementRemnantJobCount();
        return getAffinity();
    }

    public static int getRemnantJobCount() {
        return globalMem().getInt(KEY_REMNANT_JOBS);
    }

    public static boolean isStoryCompleted(StoryMissionType type) {
        MemoryAPI mem = globalMem();
        switch (type) {
            case NADE_A:
                return mem.getBoolean(KEY_STORY_A);
            case NADE_B:
                return mem.getBoolean(KEY_STORY_B);
            case TDD:
                return mem.getBoolean(KEY_STORY_TDD);
            default:
                return false;
        }
    }

    public static void markStoryCompleted(StoryMissionType type, String pendingReport, boolean failure) {
        MemoryAPI mem = globalMem();
        switch (type) {
            case NADE_A:
                mem.set(KEY_STORY_A, true);
                break;
            case NADE_B:
                mem.set(KEY_STORY_B, true);
                break;
            case TDD:
                mem.set(KEY_STORY_TDD, true);
                break;
            default:
                break;
        }
        setPendingReport(pendingReport, failure);
    }

    public static void setPendingReport(String text, boolean failure) {
        MemoryAPI mem = globalMem();
        if (text == null || text.trim().isEmpty()) {
            mem.unset(KEY_PENDING_REPORT);
        } else {
            mem.set(KEY_PENDING_REPORT, text);
        }
        mem.set(KEY_PENDING_FAILURE, failure);
        if (failure) {
            Moci_CampaignFeedback.playMissionFailed();
        }
    }

    public static boolean isPendingReportFailure() {
        return globalMem().getBoolean(KEY_PENDING_FAILURE);
    }

    public static String getPendingReportText() {
        MemoryAPI mem = globalMem();
        sanitizePendingReport(mem);
        Object value = mem.get(KEY_PENDING_REPORT);
        if (!(value instanceof String)) {
            return null;
        }

        String text = ((String) value).trim();
        if (text.isEmpty() || "false".equalsIgnoreCase(text)) {
            mem.unset(KEY_PENDING_REPORT);
            return null;
        }
        return text;
    }

    public static void clearPendingReport() {
        setPendingReport(null, false);
    }

    public static boolean shouldUseProspectorAsPrimaryContact() {
        ensureLoaded();
        Moci_NadesicoStoryRouteManager.ensureRouteState();
        return !Moci_NadesicoStoryRouteManager.hasAStart()
                && !isStoryCompleted(StoryMissionType.NADE_A);
    }

    public static StoryMissionType getNextAvailableStoryMissionType() {
        MemoryAPI mem = globalMem();
        if (!mem.getBoolean(KEY_STORY_A) && getAffinity() >= AFFINITY_THRESHOLD_A) {
            return StoryMissionType.NADE_A;
        }
        if (!mem.getBoolean(KEY_STORY_B) && getAffinity() >= AFFINITY_THRESHOLD_B) {
            return StoryMissionType.NADE_B;
        }
        if (!mem.getBoolean(KEY_STORY_TDD) && getAffinity() >= AFFINITY_THRESHOLD_TDD) {
            return StoryMissionType.TDD;
        }
        return StoryMissionType.REMNANT;
    }

    private static void ensureDefault(MemoryAPI mem, String key) {
        if (!mem.contains(key)) {
            mem.set(key, false);
        }
    }

    private static void sanitizePendingReport(MemoryAPI mem) {
        if (mem == null || !mem.contains(KEY_PENDING_REPORT)) {
            return;
        }

        Object value = mem.get(KEY_PENDING_REPORT);
        if (!(value instanceof String)) {
            mem.unset(KEY_PENDING_REPORT);
            return;
        }

        String text = ((String) value).trim();
        if (text.isEmpty() || "false".equalsIgnoreCase(text)) {
            mem.unset(KEY_PENDING_REPORT);
        }
    }

    private static MemoryAPI globalMem() {
        return Global.getSector().getMemoryWithoutUpdate();
    }
}
