package data.scripts.tail;

import org.lwjgl.util.vector.Vector2f;

/**
 * 尾巴配置类 - 定义单个舰船的尾巴参数
 */
public class Moci_TailConfig {
    // 舰船标识
    public final String shipId;
    public final String tailWeaponSlotId;
    
    // 体节配置
    public final int numSegments;
    public final String[] spritePaths;
    
    // 挂载点位置（相对于舰船中心）
    public final Vector2f mountLocation;
    
    // 链条物理参数
    public final float overlapFactor;           // 最小重叠系数（拉伸状态）
    public final float maxOverlapFactor;        // 最大重叠系数（压缩状态）
    public final float tailTipPivotOffset;
    public final float tailTipOverlapBonus;
    
    // 压缩动力学参数
    public final float compressionSpeed;        // 压缩响应速度
    public final float decompressionSpeed;      // 释放恢复速度
    public final float compressionSensitivity;  // 压缩力敏感度
    
    // 骨架约束
    public final float boneRootMaxAngle;
    public final float boneSecondBaseAngle;
    public final float boneAngleIncrement;
    public final float boneMidSectionBonus;
    
    // 运动参数
    public final float rootLerpSpeed;
    public final float followLerpSpeed;
    
    // 自主摆动参数
    public final float swingFrequency;
    public final float swingAmplitude;
    
    // 仿生行为参数（使用默认值，可选覆盖）
    public final float balanceResponse;
    public final float balanceMaxAngle;
    public final float inertiaResponse;
    public final float inertiaMaxAngle;
    public final float strafeResponse;
    public final float strafeMaxAngle;
    
    /**
     * 完整构造器（带压缩参数）
     */
    public Moci_TailConfig(
            String shipId,
            String tailWeaponSlotId,
            int numSegments,
            String[] spritePaths,
            Vector2f mountLocation,
            float overlapFactor,
            float maxOverlapFactor,
            float tailTipPivotOffset,
            float tailTipOverlapBonus,
            float compressionSpeed,
            float decompressionSpeed,
            float compressionSensitivity,
            float boneRootMaxAngle,
            float boneSecondBaseAngle,
            float boneAngleIncrement,
            float boneMidSectionBonus,
            float rootLerpSpeed,
            float followLerpSpeed,
            float swingFrequency,
            float swingAmplitude,
            float balanceResponse,
            float balanceMaxAngle,
            float inertiaResponse,
            float inertiaMaxAngle,
            float strafeResponse,
            float strafeMaxAngle) {
        this.shipId = shipId;
        this.tailWeaponSlotId = tailWeaponSlotId;
        this.numSegments = numSegments;
        this.spritePaths = spritePaths;
        this.mountLocation = mountLocation;
        this.overlapFactor = overlapFactor;
        this.maxOverlapFactor = maxOverlapFactor;
        this.tailTipPivotOffset = tailTipPivotOffset;
        this.tailTipOverlapBonus = tailTipOverlapBonus;
        this.compressionSpeed = compressionSpeed;
        this.decompressionSpeed = decompressionSpeed;
        this.compressionSensitivity = compressionSensitivity;
        this.boneRootMaxAngle = boneRootMaxAngle;
        this.boneSecondBaseAngle = boneSecondBaseAngle;
        this.boneAngleIncrement = boneAngleIncrement;
        this.boneMidSectionBonus = boneMidSectionBonus;
        this.rootLerpSpeed = rootLerpSpeed;
        this.followLerpSpeed = followLerpSpeed;
        this.swingFrequency = swingFrequency;
        this.swingAmplitude = swingAmplitude;
        this.balanceResponse = balanceResponse;
        this.balanceMaxAngle = balanceMaxAngle;
        this.inertiaResponse = inertiaResponse;
        this.inertiaMaxAngle = inertiaMaxAngle;
        this.strafeResponse = strafeResponse;
        this.strafeMaxAngle = strafeMaxAngle;
    }
    
    /**
     * 简化构造器 - 使用默认仿生行为参数和压缩参数
     */
    public Moci_TailConfig(
            String shipId,
            String tailWeaponSlotId,
            int numSegments,
            String[] spritePaths,
            Vector2f mountLocation,
            float overlapFactor,
            float tailTipPivotOffset,
            float tailTipOverlapBonus,
            float boneRootMaxAngle,
            float boneSecondBaseAngle,
            float boneAngleIncrement,
            float boneMidSectionBonus,
            float rootLerpSpeed,
            float followLerpSpeed,
            float swingFrequency,
            float swingAmplitude) {
        this(shipId, tailWeaponSlotId, numSegments, spritePaths, mountLocation,
             overlapFactor, 
             overlapFactor - 0.5f,  // 默认最大重叠 = 最小重叠 + 50%
             tailTipPivotOffset, tailTipOverlapBonus,
             1.0f,   // 默认压缩速度：快速响应
             0.4f,   // 默认释放速度：缓慢恢复
             0.75f,   // 默认压缩敏感度
             boneRootMaxAngle, boneSecondBaseAngle, boneAngleIncrement, boneMidSectionBonus,
             rootLerpSpeed, followLerpSpeed, swingFrequency, swingAmplitude,
             0.8f, 35f, 0.015f, 25f, 0.8f, 30f);  // 默认仿生参数
    }
    
    /**
     * 获取指定体节的最大弯曲角度
     */
    public float getBoneMaxAngle(int segmentIndex) {
        if (segmentIndex == 0) {
            return boneRootMaxAngle;
        }
        float baseAngle = boneSecondBaseAngle + (segmentIndex - 1) * boneAngleIncrement;
        // 3-5节（index 2-4）额外加成
        if (segmentIndex >= 2 && segmentIndex <= 4) {
            baseAngle += boneMidSectionBonus;
        }
        return baseAngle;
    }
    
    /**
     * 计算旋转中心偏移
     */
    public float calculatePivotOffset(int segmentIndex, float segmentLength) {
        if (segmentIndex == numSegments - 1) {
            return segmentLength * tailTipPivotOffset;
        } else {
            return segmentLength / 2f;
        }
    }
    
    /**
     * 计算重叠系数（基础值，不含压缩）
     */
    public float calculateOverlapFactor(int segmentIndex) {
        if (segmentIndex == numSegments - 2) {
            return overlapFactor + tailTipOverlapBonus;
        }
        return overlapFactor;
    }
    
    /**
     * 计算动态重叠系数（含压缩状态）
     * @param segmentIndex 体节索引
     * @param compression 压缩状态 (0=最小重叠, 1=最大重叠)
     */
    public float calculateDynamicOverlapFactor(int segmentIndex, float compression) {
        float baseOverlap = calculateOverlapFactor(segmentIndex);
        float maxOverlap = (segmentIndex == numSegments - 2) ? 
            (maxOverlapFactor + tailTipOverlapBonus) : maxOverlapFactor;
        // 在最小和最大重叠之间插值
        return baseOverlap + (maxOverlap - baseOverlap) * compression;
    }
}
