package data.hullmods;

import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * CombatSquad动态部署点计算缓存管理器
 * 
 * 提供智能缓存机制来优化部署点计算性能，包括：
 * 1. 计算结果缓存和失效管理
 * 2. 缓存命中率统计
 * 3. 内存使用优化
 * 4. 自动清理机制
 * 
 * @author Moci
 * @version 1.0
 */
public class Moci_DeploymentCalculationCache {

    // 缓存存储
    private static final Map<String, CacheEntry> cache = new HashMap<>();

    // 缓存统计
    private static long cacheHits = 0;
    private static long cacheMisses = 0;
    private static long cacheEvictions = 0;

    // 缓存配置
    private static final int MAX_CACHE_SIZE = 100; // 最大缓存条目数
    private static final long CACHE_TTL_MS = 30000; // 缓存生存时间（30秒）
    private static final long CLEANUP_INTERVAL_MS = 60000; // 清理间隔（60秒）

    // 清理时间戳
    private static long lastCleanupTime = 0;

    /**
     * 缓存条目数据类
     */
    private static class CacheEntry {
        final float multiplier;
        final Moci_DynamicDeploymentCalculator.CalculationDetails details;
        final long timestamp;
        final String cacheKey;

        CacheEntry(float multiplier, Moci_DynamicDeploymentCalculator.CalculationDetails details, String cacheKey) {
            this.multiplier = multiplier;
            this.details = details;
            this.timestamp = System.currentTimeMillis();
            this.cacheKey = cacheKey;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    /**
     * 获取缓存的部署点倍数
     * 
     * @param ship 舰船对象
     * @return 缓存的倍数，如果未命中则返回null
     */
    public static Float getCachedMultiplier(ShipAPI ship) {
        if (ship == null) {
            return null;
        }

        String cacheKey = generateCacheKey(ship);
        if (cacheKey == null) {
            return null;
        }

        // 执行定期清理
        performPeriodicCleanup();

        CacheEntry entry = cache.get(cacheKey);
        if (entry != null) {
            if (!entry.isExpired()) {
                cacheHits++;

                if (Moci_DynamicDeploymentConfig.isPerformanceMonitoringEnabled()) {
                    Global.getLogger(Moci_DeploymentCalculationCache.class)
                            .info("缓存命中: " + cacheKey);
                }

                return entry.multiplier;
            } else {
                // 缓存过期，移除
                cache.remove(cacheKey);
                cacheEvictions++;
            }
        }

        cacheMisses++;
        return null;
    }

    /**
     * 获取缓存的计算详情
     * 
     * @param ship 舰船对象
     * @return 缓存的详情，如果未命中则返回null
     */
    public static Moci_DynamicDeploymentCalculator.CalculationDetails getCachedDetails(ShipAPI ship) {
        if (ship == null) {
            return null;
        }

        String cacheKey = generateCacheKey(ship);
        if (cacheKey == null) {
            return null;
        }

        CacheEntry entry = cache.get(cacheKey);
        if (entry != null && !entry.isExpired()) {
            return entry.details;
        }

        return null;
    }

    /**
     * 缓存计算结果
     * 
     * @param ship       舰船对象
     * @param multiplier 计算的倍数
     * @param details    计算详情
     */
    public static void cacheResult(ShipAPI ship, float multiplier,
            Moci_DynamicDeploymentCalculator.CalculationDetails details) {
        if (ship == null) {
            return;
        }

        String cacheKey = generateCacheKey(ship);
        if (cacheKey == null) {
            return;
        }

        // 检查缓存大小限制
        if (cache.size() >= MAX_CACHE_SIZE) {
            evictOldestEntry();
        }

        CacheEntry entry = new CacheEntry(multiplier, details, cacheKey);
        cache.put(cacheKey, entry);

        if (Moci_DynamicDeploymentConfig.isPerformanceMonitoringEnabled()) {
            Global.getLogger(Moci_DeploymentCalculationCache.class)
                    .info("缓存结果: " + cacheKey + " -> " + multiplier);
        }
    }

    /**
     * 使指定舰船的缓存失效
     * 
     * @param ship 舰船对象
     */
    public static void invalidateCache(ShipAPI ship) {
        if (ship == null) {
            return;
        }

        String cacheKey = generateCacheKey(ship);
        if (cacheKey != null && cache.containsKey(cacheKey)) {
            cache.remove(cacheKey);
            cacheEvictions++;

            if (Moci_DynamicDeploymentConfig.isDetailedLoggingEnabled()) {
                Global.getLogger(Moci_DeploymentCalculationCache.class)
                        .info("缓存失效: " + cacheKey);
            }
        }
    }

    /**
     * 使指定指挥官的所有缓存失效（当僚机配置改变时）
     * 
     * @param commanderId 指挥官ID
     */
    public static void invalidateCacheByCommander(String commanderId) {
        if (commanderId == null || commanderId.isEmpty()) {
            return;
        }

        // 查找并移除所有包含该指挥官ID的缓存条目
        java.util.Iterator<Map.Entry<String, CacheEntry>> iterator = cache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, CacheEntry> entry = iterator.next();
            if (entry.getKey().contains(commanderId)) {
                cacheEvictions++;

                if (Moci_DynamicDeploymentConfig.isDetailedLoggingEnabled()) {
                    Global.getLogger(Moci_DeploymentCalculationCache.class)
                            .info("指挥官缓存失效: " + entry.getKey());
                }

                iterator.remove();
            }
        }
    }

    /**
     * 清空所有缓存
     */
    public static void clearAllCache() {
        int clearedCount = cache.size();
        cache.clear();
        cacheEvictions += clearedCount;

        Global.getLogger(Moci_DeploymentCalculationCache.class)
                .info("清空所有缓存，共清理 " + clearedCount + " 个条目");
    }

    /**
     * 生成缓存键值
     */
    private static String generateCacheKey(ShipAPI ship) {
        if (ship == null || ship.getHullSpec() == null) {
            return null;
        }

        StringBuilder keyBuilder = new StringBuilder();

        // 基础舰船信息
        keyBuilder.append(ship.getHullSpec().getHullId());

        // 指挥官信息（如果有）
        if (ship.getCaptain() != null && !ship.getCaptain().isDefault()) {
            keyBuilder.append("_cmd_").append(ship.getCaptain().getId());

            // 编队规模（影响计算结果）
            try {
                int squadSize = Moci_PilotManager.getSquadSize(ship.getCaptain().getId());
                keyBuilder.append("_size_").append(squadSize);
            } catch (Exception e) {
                // 获取编队规模失败，使用默认标识
                keyBuilder.append("_size_unknown");
            }
        } else {
            keyBuilder.append("_no_cmd");
        }

        // 母舰部署点（影响计算结果）
        int mothershipDP = Moci_DeploymentInfoProvider.getShipBaseDeploymentPoints(ship);
        keyBuilder.append("_mdp_").append(mothershipDP);

        return keyBuilder.toString();
    }

    /**
     * 驱逐最旧的缓存条目
     */
    private static void evictOldestEntry() {
        if (cache.isEmpty()) {
            return;
        }

        String oldestKey = null;
        long oldestTime = Long.MAX_VALUE;

        for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
            if (entry.getValue().timestamp < oldestTime) {
                oldestTime = entry.getValue().timestamp;
                oldestKey = entry.getKey();
            }
        }

        if (oldestKey != null) {
            cache.remove(oldestKey);
            cacheEvictions++;

            if (Moci_DynamicDeploymentConfig.isPerformanceMonitoringEnabled()) {
                Global.getLogger(Moci_DeploymentCalculationCache.class)
                        .info("驱逐最旧缓存条目: " + oldestKey);
            }
        }
    }

    /**
     * 执行定期清理
     */
    private static void performPeriodicCleanup() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCleanupTime < CLEANUP_INTERVAL_MS) {
            return;
        }

        lastCleanupTime = currentTime;

        // 清理过期条目
        int cleanedCount = 0;
        java.util.Iterator<Map.Entry<String, CacheEntry>> cleanupIterator = cache.entrySet().iterator();
        while (cleanupIterator.hasNext()) {
            Map.Entry<String, CacheEntry> entry = cleanupIterator.next();
            if (entry.getValue().isExpired()) {
                cleanedCount++;
                cleanupIterator.remove();
            }
        }

        cacheEvictions += cleanedCount;

        if (cleanedCount > 0 && Moci_DynamicDeploymentConfig.isPerformanceMonitoringEnabled()) {
            Global.getLogger(Moci_DeploymentCalculationCache.class)
                    .info("定期清理完成，清理了 " + cleanedCount + " 个过期条目");
        }
    }

    /**
     * 获取缓存统计信息
     */
    public static CacheStatistics getStatistics() {
        return new CacheStatistics(
                cache.size(),
                cacheHits,
                cacheMisses,
                cacheEvictions,
                calculateHitRate());
    }

    /**
     * 计算缓存命中率
     */
    private static float calculateHitRate() {
        long totalRequests = cacheHits + cacheMisses;
        if (totalRequests == 0) {
            return 0f;
        }
        return (float) cacheHits / totalRequests * 100f;
    }

    /**
     * 重置缓存统计
     */
    public static void resetStatistics() {
        cacheHits = 0;
        cacheMisses = 0;
        cacheEvictions = 0;

        Global.getLogger(Moci_DeploymentCalculationCache.class)
                .info("缓存统计已重置");
    }

    /**
     * 缓存统计数据类
     */
    public static class CacheStatistics {
        public final int currentSize;
        public final long hits;
        public final long misses;
        public final long evictions;
        public final float hitRate;

        CacheStatistics(int currentSize, long hits, long misses, long evictions, float hitRate) {
            this.currentSize = currentSize;
            this.hits = hits;
            this.misses = misses;
            this.evictions = evictions;
            this.hitRate = hitRate;
        }

        @Override
        public String toString() {
            return String.format("缓存统计 - 当前大小: %d, 命中: %d, 未命中: %d, 驱逐: %d, 命中率: %.1f%%",
                    currentSize, hits, misses, evictions, hitRate);
        }
    }
}