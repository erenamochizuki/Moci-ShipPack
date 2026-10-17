package data.scripts.weapons;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicRender;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;

/**
 * 增强光束武器 - 每帧效果插件
 * 
 * 功能：
 * 1. 散热烟雾 - 武器开火时生成散热烟雾
 * 2. 起点粒子 - 光束起点的粒子特效
 * 3. 镜头光晕 - 光束起点的镜头光晕效果
 */
public class Moci_EnhancedBeam_EveryFrame implements EveryFrameWeaponEffectPlugin {

    // ========== 散热烟雾参数 ==========
    private static final boolean ENABLE_HEAT_SMOKE = true;
    private static final float SMOKE_INTERVAL = 0.05f; // 烟雾生成间隔（秒）
    private static final float SMOKE_SPEED = 25f; // 烟雾粒子速度
    private static final float SMOKE_SIZE = 15f; // 烟雾粒子基础大小
    private static final float SMOKE_SIZE_MULT = 2.5f; // 烟雾粒子大小倍数
    private static final float SMOKE_DURATION = 3f; // 烟雾持续时间
    private static final Color SMOKE_COLOR = new Color(120, 120, 120, 100); // 烟雾颜色（灰色，半透明）
    private float smokeTimer = 0f;

    // ========== 起点粒子参数 ==========
    private static final boolean ENABLE_MUZZLE_PARTICLES = true;
    private static final float MUZZLE_PARTICLE_INTERVAL = 0.05f; // 起点粒子间隔（秒）
    private static final float MUZZLE_PARTICLE_SIZE_MIN = 15f;
    private static final float MUZZLE_PARTICLE_SIZE_MAX = 25f;
    private static final float MUZZLE_PARTICLE_DURATION_MIN = 0.2f;
    private static final float MUZZLE_PARTICLE_DURATION_MAX = 0.25f;
    private static final float MUZZLE_PARTICLE_BRIGHTNESS = 0.8f;
    private float muzzleParticleTimer = 0f;

    // ========== 镜头光晕参数 ==========
    private static final boolean ENABLE_LENS_FLARE = true;
    private static final String LENS_FLARE_SPRITE_ID = "Moci_bullet_aura"; // 镜头光晕贴图ID
    private static final Color FLARE_COLOR_PRIMARY = new Color(215, 157, 255, 80); // 主光晕颜色
    private static final Color FLARE_COLOR_SECONDARY = new Color(255, 200, 200, 100); // 次光晕颜色
    private static final Vector2f FLARE_SIZE_PRIMARY = new Vector2f(200f, 10f); // 主光晕尺寸
    private static final Vector2f FLARE_SIZE_SECONDARY = new Vector2f(150f, 2f); // 次光晕尺寸

    // ========== 炮管轮换状态 ==========
    private boolean hasFiredThisCharge = false;
    private int currentBarrel = 0;

    // ========== 光束映射 ==========
    private Map<Integer, BeamAPI> beamMap = new HashMap<Integer, BeamAPI>();
    private boolean beamMapInitialized = false;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 基础检查
        if (engine == null || engine.isPaused()) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            return;
        }

        // 屏幕外剔除优化
        if (!engine.getViewport().isNearViewport(weapon.getLocation(), 400f)) {
            return;
        }

        // 获取武器状态
        float chargeLevel = weapon.getChargeLevel();
        boolean isFiring = weapon.isFiring();

        // 初始化光束映射
        if (chargeLevel >= 1f && !beamMapInitialized) {
            initializeBeamMap(engine, weapon);
        }

        // 重置光束映射（充能不足时）
        if (chargeLevel < 1f) {
            beamMap.clear();
            beamMapInitialized = false;
        }

        // 处理开火效果（只在完全充能且开火时）
        if (isFiring && chargeLevel >= 0.9f) {
            // 获取所有开火点并生成效果
            int firePointCount = getBarrelCount(weapon);
            
            for (int i = 0; i < firePointCount; i++) {
                Vector2f firePointLocation = calculateFirePointLocation(weapon, i);
                
                // 1. 散热烟雾效果
                if (ENABLE_HEAT_SMOKE) {
                    handleMuzzleSmokeEffects(amount, engine, weapon, firePointLocation);
                }
                
                // 2. 起点粒子效果
                if (ENABLE_MUZZLE_PARTICLES) {
                    handleMuzzleParticleEffects(amount, engine, weapon, firePointLocation);
                }
                
                // 3. 镜头光晕效果
                if (ENABLE_LENS_FLARE) {
                    handleLensFlareEffects(engine, weapon, firePointLocation);
                }
            }
        } else {
            // 重置计时器
            smokeTimer = 0f;
            muzzleParticleTimer = 0f;
        }

        // 处理炮管轮换
        handleBarrelRotation(weapon, chargeLevel, isFiring);
    }

    /**
     * 初始化光束映射
     */
    private void initializeBeamMap(CombatEngineAPI engine, WeaponAPI weapon) {
        int counterForBeams = 0;
        for (BeamAPI beam : engine.getBeams()) {
            if (beam.getWeapon() == weapon) {
                if (!beamMap.containsValue(beam)) {
                    beamMap.put(counterForBeams, beam);
                    counterForBeams++;
                }
            }
        }

        if (!beamMap.isEmpty()) {
            beamMapInitialized = true;
        }
    }

    /**
     * 处理散热烟雾效果
     */
    private void handleMuzzleSmokeEffects(float amount, CombatEngineAPI engine, WeaponAPI weapon,
            Vector2f muzzleLocation) {
        smokeTimer += amount;

        if (smokeTimer >= SMOKE_INTERVAL) {
            float weaponAngle = weapon.getCurrAngle();

            // 在开火点周围生成烟雾粒子
            for (int i = 0; i < 2; i++) {
                // 在开火点附近随机偏移
                Vector2f smokeLoc = new Vector2f(muzzleLocation);
                smokeLoc.x += MathUtils.getRandomNumberInRange(-5f, 5f);
                smokeLoc.y += MathUtils.getRandomNumberInRange(-5f, 5f);

                // 计算烟雾速度（向武器后方扩散）
                float smokeAngle = weaponAngle + 180f + MathUtils.getRandomNumberInRange(-30f, 30f);
                Vector2f smokeVel = VectorUtils.getDirectionalVector(smokeLoc,
                        MathUtils.getPointOnCircumference(smokeLoc, 10f, smokeAngle));
                smokeVel.scale(SMOKE_SPEED);

                // 叠加舰船速度
                Vector2f.add(weapon.getShip().getVelocity(), smokeVel, smokeVel);

                // 生成烟雾粒子
                engine.addNebulaSmokeParticle(
                        smokeLoc,
                        smokeVel,
                        SMOKE_SIZE,
                        SMOKE_SIZE_MULT,
                        0.1f,
                        0.1f,
                        SMOKE_DURATION,
                        SMOKE_COLOR);
            }

            smokeTimer = 0f;
        }
    }

    /**
     * 处理起点粒子效果
     */
    private void handleMuzzleParticleEffects(float amount, CombatEngineAPI engine, WeaponAPI weapon,
            Vector2f muzzleLocation) {
        muzzleParticleTimer += amount;

        if (muzzleParticleTimer >= MUZZLE_PARTICLE_INTERVAL) {
            // 获取光束信息（用于颜色）
            BeamAPI currentBeam = beamMap.get(currentBarrel);

            if (currentBeam != null) {
                // 使用光束的颜色和宽度
                float beamWidth = currentBeam.getWidth();
                Color coreColor = currentBeam.getCoreColor();
                Color fringeColor = currentBeam.getFringeColor();

                // 生成核心粒子
                float size = beamWidth * MathUtils.getRandomNumberInRange(2f, 2.2f);
                float duration = MathUtils.getRandomNumberInRange(
                    MUZZLE_PARTICLE_DURATION_MIN, 
                    MUZZLE_PARTICLE_DURATION_MAX
                );

                engine.addSmoothParticle(
                    muzzleLocation, 
                    weapon.getShip().getVelocity(), 
                    beamWidth, 
                    MUZZLE_PARTICLE_BRIGHTNESS,
                    duration, 
                    coreColor
                );
                
                engine.addSmoothParticle(
                    muzzleLocation, 
                    weapon.getShip().getVelocity(), 
                    size, 
                    MUZZLE_PARTICLE_BRIGHTNESS,
                    duration,
                    fringeColor.brighter()
                );
            } else {
                // 非光束武器的默认粒子效果
                float size = MathUtils.getRandomNumberInRange(
                    MUZZLE_PARTICLE_SIZE_MIN, 
                    MUZZLE_PARTICLE_SIZE_MAX
                );
                float duration = MathUtils.getRandomNumberInRange(
                    MUZZLE_PARTICLE_DURATION_MIN, 
                    MUZZLE_PARTICLE_DURATION_MAX
                );
                Color defaultColor = new Color(255, 200, 100, 200);

                engine.addSmoothParticle(
                    muzzleLocation, 
                    weapon.getShip().getVelocity(), 
                    size, 
                    MUZZLE_PARTICLE_BRIGHTNESS,
                    duration, 
                    defaultColor
                );
            }

            muzzleParticleTimer = 0f;
        }
    }

    /**
     * 处理镜头光晕效果
     */
    private void handleLensFlareEffects(CombatEngineAPI engine, WeaponAPI weapon, Vector2f muzzleLocation) {
        try {
            // 获取镜头光晕贴图
            SpriteAPI flare1 = Global.getSettings().getSprite("fx", LENS_FLARE_SPRITE_ID);
            SpriteAPI flare2 = Global.getSettings().getSprite("fx", LENS_FLARE_SPRITE_ID);

            // 主光晕
            Vector2f flarePos1 = MathUtils.getRandomPointInCircle(muzzleLocation,
                    MathUtils.getRandomNumberInRange(0f, 2f));
            MagicRender.singleframe(
                    flare1,
                    flarePos1,
                    FLARE_SIZE_PRIMARY,
                    0f,
                    FLARE_COLOR_PRIMARY,
                    true);

            // 次光晕
            Vector2f flarePos2 = MathUtils.getRandomPointInCircle(muzzleLocation,
                    MathUtils.getRandomNumberInRange(0f, 2f));
            MagicRender.singleframe(
                    flare2,
                    flarePos2,
                    FLARE_SIZE_SECONDARY,
                    0f,
                    FLARE_COLOR_SECONDARY,
                    true);
        } catch (Exception e) {
            // 如果贴图不存在，静默忽略镜头光晕效果
        }
    }

    /**
     * 处理炮管轮换逻辑
     */
    private void handleBarrelRotation(WeaponAPI weapon, float chargeLevel, boolean isFiring) {
        // 如果达到满充能且未记录开火，标记为已开火
        if (chargeLevel >= 1f && !hasFiredThisCharge) {
            hasFiredThisCharge = true;
        }

        // 如果已开火且现在停止充能或停止开火，进行炮管轮换
        if (hasFiredThisCharge && (chargeLevel <= 0f || !isFiring)) {
            hasFiredThisCharge = false;
            currentBarrel++;

            // 获取炮管数量并循环
            int barrelCount = getBarrelCount(weapon);
            if (currentBarrel >= barrelCount) {
                currentBarrel = 0;
            }
        }
    }

    /**
     * 获取武器的炮管数量
     */
    private int getBarrelCount(WeaponAPI weapon) {
        if (weapon.getSlot().isHardpoint()) {
            return weapon.getSpec().getHardpointAngleOffsets().size();
        } else if (weapon.getSlot().isHidden()) {
            return weapon.getSpec().getHiddenAngleOffsets().size();
        } else {
            return weapon.getSpec().getTurretAngleOffsets().size();
        }
    }

    /**
     * 计算指定索引的开火点世界坐标位置
     * 
     * @param weapon 武器实例
     * @param firePointIndex 开火点索引
     * @return 开火点的世界坐标位置
     */
    private Vector2f calculateFirePointLocation(WeaponAPI weapon, int firePointIndex) {
        Vector2f firePointLocation = new Vector2f(0f, 0f);

        // 获取指定索引的开火偏移
        if (weapon.getSlot().isHardpoint()) {
            firePointLocation.x += weapon.getSpec().getHardpointFireOffsets().get(firePointIndex).x;
            firePointLocation.y += weapon.getSpec().getHardpointFireOffsets().get(firePointIndex).y;
        } else if (weapon.getSlot().isTurret()) {
            firePointLocation.x += weapon.getSpec().getTurretFireOffsets().get(firePointIndex).x;
            firePointLocation.y += weapon.getSpec().getTurretFireOffsets().get(firePointIndex).y;
        } else {
            firePointLocation.x += weapon.getSpec().getHiddenFireOffsets().get(firePointIndex).x;
            firePointLocation.y += weapon.getSpec().getHiddenFireOffsets().get(firePointIndex).y;
        }

        // 转换到世界坐标系
        firePointLocation = VectorUtils.rotate(firePointLocation, weapon.getCurrAngle(), new Vector2f(0f, 0f));
        firePointLocation.x += weapon.getLocation().x;
        firePointLocation.y += weapon.getLocation().y;

        return firePointLocation;
    }
}
