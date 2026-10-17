package data.scripts.tail;

import java.util.HashMap;
import java.util.Map;

import org.lwjgl.util.vector.Vector2f;

/**
 * 尾巴配置注册表 - 集中管理所有舰船的尾巴配置
 */
public class Moci_TailConfigRegistry {
    private static final Map<String, Moci_TailConfig> configs = new HashMap<>();
    
    static {
        registerConfigs();
    }
    
    /**
     * 注册所有尾巴配置
     */
    private static void registerConfigs() {
        // ShroudedDragon 配置
        registerShroudedDragon();
        registerHoarShroudDragon();
        
        // Black Selena 配置
        registerBlackSelena();
    }

    
    /**
     * ShroudedDragon 尾巴配置
     */
    private static void registerShroudedDragon() {
        String[] spritePaths = new String[8];
        for (int i = 0; i < 8; i++) {
            spritePaths[i] = "graphics/ships/VIII/ShroudedDragon/tail/Moci_ShroudedDragon_tail_0" + i + ".png";
        }
        
        Moci_TailConfig config = new Moci_TailConfig(
            "Moci_ShroudedDragon",      // 舰船ID
            "TAIL",                      // 尾巴武器槽ID
            8,                           // 体节数量
            spritePaths,                 // 体节图片路径数组
            new Vector2f(-36, 0),        // 挂载点位置（相对于舰船中心，X负值=后方，Y负值=下方）
            0.82f,                       // 体节重叠系数（0.82 = 重叠18%）
            0.32f,                       // 最大重叠系数（当前运行中的压缩状态）
            0.14f,                       // 尾端旋转中心偏移（0.14 = 在图片顶部向下14%处）
            0.03f,                       // 尾端额外重叠补偿
            1.0f,                        // 压缩响应速度
            0.4f,                        // 释放恢复速度
            1.0f,                        // 压缩敏感度（1 = 保持当前满压缩手感）
            45f,                         // 根部最大弯曲角度（度）
            12f,                         // 第二节基础弯曲角度（度）
            4f,                          // 每节递增角度（度）
            10f,                         // 中段额外弯曲加成（3-5节，度）
            8f,                          // 根部追踪速度（越大响应越快）
            12f,                         // 后续体节跟随速度（越大响应越快）
            0.3f,                        // 自主摆动频率（Hz）
            45f,                         // 自主摆动幅度（度）
            0.8f,                        // 转向平衡响应系数
            35f,                         // 转向平衡最大角度
            0.015f,                      // 惯性响应系数
            25f,                         // 惯性最大角度
            0.8f,                        // 横移响应系数
            30f                          // 横移最大角度
        );
        
        configs.put(config.shipId, config);
    }

    private static void registerHoarShroudDragon() {
        String[] spritePaths = new String[8];
        for (int i = 0; i < 8; i++) {
            spritePaths[i] = "graphics/ships/VIII/HoarShroudDragon/tail/Moci_HoarShroudDragon_tail_0" + i + ".png";
        }

        Moci_TailConfig config = new Moci_TailConfig(
            "Moci_HoarShroudDragon",     // 舰船ID
            "TAIL",                      // 尾巴武器槽ID
            8,                           // 体节数量
            spritePaths,                 // 体节图片路径数组
            new Vector2f(-50f, 0f),      // 挂载点位置（与当前插件运行效果保持一致）
            0.82f,                       // 体节重叠系数（0.82 = 重叠18%）
            0.32f,                       // 最大重叠系数（当前运行中的压缩状态）
            0.14f,                       // 尾端旋转中心偏移（0.14 = 在图片顶部向下14%处）
            0.03f,                       // 尾端额外重叠补偿
            1.0f,                        // 压缩响应速度
            0.4f,                        // 释放恢复速度
            1.0f,                        // 压缩敏感度（1 = 保持当前满压缩手感）
            35f,                         // 根部最大弯曲角度（度）
            11f,                         // 第二节基础弯曲角度（度）
            3f,                          // 每节递增角度（度）
            10f,                         // 中段额外弯曲加成（3-5节，度）
            6f,                          // 根部追踪速度（越大响应越快）
            10f,                         // 后续体节跟随速度（越大响应越快）
            0.15f,                        // 自主摆动频率（Hz）
            35f,                         // 自主摆动幅度（度）
            0.8f,                        // 转向平衡响应系数
            30f,                         // 转向平衡最大角度
            0.015f,                      // 惯性响应系数
            20f,                         // 惯性最大角度
            0.8f,                        // 横移响应系数
            20f                          // 横移最大角度
        );

        configs.put(config.shipId, config);
    }
    
    /**
     * Black Selena 尾巴配置
     */
    private static void registerBlackSelena() {
        String baseDir = "graphics/ships/Mobile_Suit/Moci_Black_Sarena/tail/";
        String[] spritePaths = new String[8];
        
        // 起始端
        spritePaths[0] = baseDir + "Black_Sarena_tail_start.png";
        
        // 6节中端
        for (int i = 1; i <= 6; i++) {
            spritePaths[i] = baseDir + "Black_Sarena_tail_mid.png";
        }
        
        // 末端
        spritePaths[7] = baseDir + "Black_Sarena_tail_end.png";
        
        Moci_TailConfig config = new Moci_TailConfig(
            "Moci_Black_Sarena",         // 舰船ID
            "TAIL",                      // 尾巴武器槽ID
            8,                           // 体节数量
            spritePaths,                 // 体节图片路径数组
            new Vector2f(-26, 0),        // 挂载点位置（相对于舰船中心，根据实际调整）
            0.82f,                       // 体节重叠系数（0.82 = 重叠18%）
            0.32f,                       // 最大重叠系数（当前运行中的压缩状态）
            0.14f,                       // 尾端旋转中心偏移（0.14 = 在图片顶部向下14%处）
            0.03f,                       // 尾端额外重叠补偿
            1.0f,                        // 压缩响应速度
            0.4f,                        // 释放恢复速度
            1.0f,                        // 压缩敏感度（1 = 保持当前满压缩手感）
            65f,                         // 根部最大弯曲角度（度）
            12f,                         // 第二节基础弯曲角度（度）
            5f,                          // 每节递增角度（度）
            10f,                         // 中段额外弯曲加成（3-5节，度）
            8f,                          // 根部追踪速度（越大响应越快）
            12f,                         // 后续体节跟随速度（越大响应越快）
            0.3f,                        // 自主摆动频率（Hz）
            45f,                         // 自主摆动幅度（度）
            0.8f,                        // 转向平衡响应系数
            35f,                         // 转向平衡最大角度
            0.015f,                      // 惯性响应系数
            25f,                         // 惯性最大角度
            0.8f,                        // 横移响应系数
            30f                          // 横移最大角度
        );
        
        configs.put(config.shipId, config);
    }
    
    /**
     * 辅助方法：快速注册使用 起始-中端-末端 结构的尾巴
     * @param shipId 舰船ID
     * @param baseDir 图片目录
     * @param startName 起始端图片名（不含.png）
     * @param midName 中端图片名（不含.png）
     * @param endName 末端图片名（不含.png）
     * @param midCount 中端节数（通常是6）
     * @param mountX 挂载点X坐标（负值=后方）
     * @param mountY 挂载点Y坐标（负值=下方）
     */
    public static void registerSimpleTail(
            String shipId,
            String baseDir,
            String startName,
            String midName,
            String endName,
            int midCount,
            float mountX,
            float mountY) {
        
        int totalSegments = 1 + midCount + 1;  // 起始 + 中端 + 末端
        String[] spritePaths = new String[totalSegments];
        
        // 起始端
        spritePaths[0] = baseDir + startName + ".png";
        
        // 中端
        for (int i = 1; i <= midCount; i++) {
            spritePaths[i] = baseDir + midName + ".png";
        }
        
        // 末端
        spritePaths[totalSegments - 1] = baseDir + endName + ".png";
        
        // 使用默认参数创建配置
        Moci_TailConfig config = new Moci_TailConfig(
            shipId,                      // 舰船ID
            "TAIL",                      // 尾巴武器槽ID
            totalSegments,               // 体节数量
            spritePaths,                 // 体节图片路径数组
            new Vector2f(mountX, mountY), // 挂载点位置
            0.82f,                       // 体节重叠系数
            0.14f,                       // 尾端旋转中心偏移
            0.03f,                       // 尾端额外重叠补偿
            25f,                         // 根部最大弯曲角度
            12f,                         // 第二节基础弯曲角度
            4f,                          // 每节递增角度
            10f,                         // 中段额外弯曲加成
            8f,                          // 根部追踪速度
            12f,                         // 后续体节跟随速度
            0.3f,                        // 自主摆动频率
            35f                          // 自主摆动幅度
        );
        
        configs.put(config.shipId, config);
    }
    
    /**
     * 获取指定舰船的尾巴配置
     */
    public static Moci_TailConfig getConfig(String shipId) {
        return configs.get(shipId);
    }
    
    /**
     * 检查舰船是否有尾巴配置
     */
    public static boolean hasConfig(String shipId) {
        return configs.containsKey(shipId);
    }
    
    /**
     * 获取所有已注册的舰船ID
     */
    public static String[] getRegisteredShipIds() {
        return configs.keySet().toArray(new String[0]);
    }
}
