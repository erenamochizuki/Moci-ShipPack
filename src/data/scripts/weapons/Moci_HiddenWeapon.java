package data.scripts.weapons;

import java.awt.Color;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileRenderDataAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

public class Moci_HiddenWeapon implements EveryFrameWeaponEffectPlugin {
    boolean init = false;
    WeaponAPI source;
    WeaponAPI sourceFollow;
    boolean wasFiring = false;
    protected boolean isAnimating = false;
    boolean hasWeaponInstalled = false;
    private float animationTimer = 0f;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null) {
            return;
        }

        // 初始化
        if (!init) {
            init = true;
            ShipAPI ship = weapon.getShip();
            String slotId = weapon.getSlot().getId();
            // 通过武器槽添加后缀寻找关联武器
            String sourceId = slotId + "_SOURCE";
            String followId = slotId + "_FOLLOW";

            for (WeaponAPI w : ship.getAllWeapons()) {
                if (sourceId.equals(w.getSlot().getId())) {
                    source = w;
                }
                if (followId.equals(w.getSlot().getId())) {
                    sourceFollow = w;
                }
            }

            // 关联武器槽安装了武器的情况下
            if (source != null) {
                // 如果关联武器不是Hidden槽位，才进行开火点同步
                if (!source.getSlot().isHidden()) {
                    // 计算装饰武器和真武器之间的坐标差
                    Vector2f diff = new Vector2f();
                    Vector2f.sub(weapon.getSlot().getLocation(), source.getSlot().getLocation(), diff);

                    syncFirePoint(source, diff, weapon);
                }
                // 隐藏SOURCE武器
                hideSourceWeapon();
                hasWeaponInstalled = true;
            }

            // 如果找到了SOURCE和FOLLOW武器，初始化FOLLOW武器
            if (source != null && sourceFollow != null) {
                initializeFollowWeapon(weapon);
            }

            // 初始化动画状态
            initializeAnimation(weapon);
        }

        if (weapon.getAnimation() != null &&
                (engine.isPaused() || weapon.getShip() == null || !weapon.getShip().isAlive() || weapon.getShip().isHulk())) {
            setIdleFrame(weapon, hasWeaponInstalled);
            weapon.getAnimation().pause();
            wasFiring = false;
            isAnimating = false;
            animationTimer = 0f;
            return;
        }

        // 动画和跟随控制
        if (source != null) {
            weapon.setCurrAngle(source.getCurrAngle());

            // 持续隐藏SOURCE武器
            hideSourceWeapon();

            // 同步FOLLOW武器角度并持续隐藏
            if (sourceFollow != null) {
                sourceFollow.setCurrAngle(source.getCurrAngle());
                hideFollowWeapon();
            }

            // 新的动画控制逻辑
            handleAnimation(weapon);
        } else {
            // 没有安装武器时的动画控制
            handleNoWeaponAnimation(weapon);
        }
    }

    /**
     * 初始化动画状态
     */
    private void initializeAnimation(WeaponAPI weapon) {
        if (weapon.getAnimation() == null)
            return;

        if (hasWeaponInstalled) {
            // 有武器时优先设置为第1帧，但需要按运行时帧数钳制
            setIdleFrame(weapon, true);
            weapon.getAnimation().pause();
        } else {
            // 没有武器时设置为第0帧并暂停
            setIdleFrame(weapon, false);
            weapon.getAnimation().pause();
        }
    }

    /**
     * 处理有武器时的动画 - 手动控制帧数避开第0帧
     */
    private void handleAnimation(WeaponAPI weapon) {
        if (weapon.getAnimation() == null)
            return;

        int totalFrames = weapon.getAnimation().getNumFrames();

        // 如果只有0,1帧，简单处理
        if (totalFrames <= 2) {
            setIdleFrame(weapon, true);
            weapon.getAnimation().pause();
            return;
        }

        // 有多帧时的开火动画控制
        boolean isFiring = source.isFiring() &&
                (source.getCooldownRemaining() == 0 ||
                        (source.getSpec().getBurstSize() > 1 && source.isInBurst()));

        if (isFiring) {
            // 开火时手动控制帧数在1到最后一帧之间循环
            if (!wasFiring) {
                setIdleFrame(weapon, true);
                animationTimer = 0f;
                wasFiring = true;
                isAnimating = true;
            }

            // 手动推进动画帧
            if (isAnimating) {
                // 获取动画帧率，如果没有则使用默认值
                float frameRate = weapon.getAnimation().getFrameRate();
                if (frameRate <= 0) {
                    frameRate = 15f; // 默认15fps
                }

                animationTimer += Global.getCombatEngine().getElapsedInLastFrame();
                float frameTime = 1f / frameRate;

                if (animationTimer >= frameTime) {
                    int currentFrame = weapon.getAnimation().getFrame();
                    int nextFrame = currentFrame + 1;

                    // 如果到达最后一帧，循环回第1帧（跳过第0帧）
                    if (nextFrame >= totalFrames) {
                        nextFrame = 1;
                    }

                    setFrameSafe(weapon, nextFrame);
                    weapon.getAnimation().pause(); // 暂停自动播放，完全手动控制
                    animationTimer = 0f;
                }
            }
        } else if (wasFiring) {
            // 停火后继续播放直到完成当前循环
            if (isAnimating) {
                // 获取动画帧率
                float frameRate = weapon.getAnimation().getFrameRate();
                if (frameRate <= 0) {
                    frameRate = 15f;
                }

                animationTimer += Global.getCombatEngine().getElapsedInLastFrame();
                float frameTime = 1f / frameRate;

                if (animationTimer >= frameTime) {
                    int currentFrame = weapon.getAnimation().getFrame();
                    int nextFrame = currentFrame + 1;

                    // 如果到达最后一帧，停止动画并回到第1帧
                    if (nextFrame >= totalFrames) {
                        setIdleFrame(weapon, true);
                        weapon.getAnimation().pause();
                        wasFiring = false;
                        isAnimating = false;
                        animationTimer = 0f;
                    } else {
                        setFrameSafe(weapon, nextFrame);
                        weapon.getAnimation().pause();
                        animationTimer = 0f;
                    }
                }
            }
        } else {
            // 待机状态保持在第1帧
            setIdleFrame(weapon, true);
            weapon.getAnimation().pause();
            isAnimating = false;
            animationTimer = 0f;
        }
    }

    /**
     * 处理没有武器时的动画
     */
    private void handleNoWeaponAnimation(WeaponAPI weapon) {
        if (weapon.getAnimation() == null)
            return;

        // 没有武器时始终保持第0帧并暂停
        setIdleFrame(weapon, false);
        weapon.getAnimation().pause();
    }

    private void setIdleFrame(WeaponAPI weapon, boolean installed) {
        int maxFrame = getMaxFrame(weapon);
        int idleFrame = installed ? Math.min(1, maxFrame) : 0;
        setFrameSafe(weapon, idleFrame);
    }

    private void setFrameSafe(WeaponAPI weapon, int frame) {
        if (weapon == null || weapon.getAnimation() == null) {
            return;
        }
        weapon.getAnimation().setFrame(clampFrame(frame, getMaxFrame(weapon)));
    }

    private int getMaxFrame(WeaponAPI weapon) {
        if (weapon == null || weapon.getAnimation() == null) {
            return 0;
        }
        return Math.max(0, weapon.getAnimation().getNumFrames() - 1);
    }

    private int clampFrame(int frame, int maxFrame) {
        if (frame < 0) {
            return 0;
        }
        return Math.min(frame, maxFrame);
    }

    /**
     * 初始化FOLLOW武器
     */
    private void initializeFollowWeapon(WeaponAPI weapon) {
        if (source == null || sourceFollow == null)
            return;

        // 如果FOLLOW武器不是Hidden槽位，才进行开火点同步
        if (!sourceFollow.getSlot().isHidden()) {
            // FOLLOW武器同步到装饰武器的位置
            Vector2f diffFollow = new Vector2f();
            Vector2f.sub(weapon.getSlot().getLocation(), sourceFollow.getSlot().getLocation(), diffFollow);

            syncFirePoint(sourceFollow, diffFollow, weapon);
        }

        // 初始隐藏FOLLOW武器
        hideFollowWeapon();
    }

    /**
     * 隐藏SOURCE武器
     */
    protected void hideSourceWeapon() {
        if (source == null)
            return;

        // Hidden槽位的武器本来就没有贴图，只需要隐藏导弹渲染
        if (source.getSlot().isHidden()) {
            // 隐藏导弹渲染数据
            if (source.getMissileRenderData() != null) {
                for (MissileRenderDataAPI render : source.getMissileRenderData()) {
                    render.getSprite().setWidth(0);
                    render.getSprite().setHeight(0);
                }
            }
            return;
        }

        // 非Hidden槽位才需要隐藏贴图
        // 透明颜色
        Color transparent = new Color(0f, 0f, 0f, 0f);

        // 设置所有贴图组件为透明
        if (source.getSprite() != null) {
            source.getSprite().setColor(transparent);
        }
        if (source.getBarrelSpriteAPI() != null) {
            source.getBarrelSpriteAPI().setColor(transparent);
        }
        if (source.getUnderSpriteAPI() != null) {
            source.getUnderSpriteAPI().setColor(transparent);
        }
        if (source.getGlowSpriteAPI() != null) {
            source.getGlowSpriteAPI().setColor(transparent);
        }

        // 隐藏导弹渲染数据
        if (source.getMissileRenderData() != null) {
            for (MissileRenderDataAPI render : source.getMissileRenderData()) {
                render.getSprite().setWidth(0);
                render.getSprite().setHeight(0);
            }
        }
    }

    /**
     * 隐藏FOLLOW武器
     */
    private void hideFollowWeapon() {
        if (sourceFollow == null)
            return;

        // Hidden槽位的武器本来就没有贴图，只需要隐藏导弹渲染
        if (sourceFollow.getSlot().isHidden()) {
            // 隐藏导弹渲染数据
            if (sourceFollow.getMissileRenderData() != null) {
                for (MissileRenderDataAPI render : sourceFollow.getMissileRenderData()) {
                    render.getSprite().setWidth(0);
                    render.getSprite().setHeight(0);
                }
            }
            return;
        }

        // 非Hidden槽位才需要隐藏贴图
        // 透明颜色
        Color transparent = new Color(0f, 0f, 0f, 0f);

        // 设置所有贴图组件为透明
        if (sourceFollow.getSprite() != null) {
            sourceFollow.getSprite().setColor(transparent);
        }
        if (sourceFollow.getBarrelSpriteAPI() != null) {
            sourceFollow.getBarrelSpriteAPI().setColor(transparent);
        }
        if (sourceFollow.getUnderSpriteAPI() != null) {
            sourceFollow.getUnderSpriteAPI().setColor(transparent);
        }
        if (sourceFollow.getGlowSpriteAPI() != null) {
            sourceFollow.getGlowSpriteAPI().setColor(transparent);
        }

        // 隐藏导弹渲染数据
        if (sourceFollow.getMissileRenderData() != null) {
            for (MissileRenderDataAPI render : sourceFollow.getMissileRenderData()) {
                render.getSprite().setWidth(0);
                render.getSprite().setHeight(0);
            }
        }
    }

    /**
     * 同步开火点 - 使用新的方法
     */
    public static void syncFirePoint(WeaponAPI sync, Vector2f diff, WeaponAPI weapon) {
        int i = Math.max(sync.getSpec().getTurretFireOffsets().size(), weapon.getSpec().getTurretFireOffsets().size());
        int i2 = Math.max(sync.getSpec().getHardpointFireOffsets().size(),
                weapon.getSpec().getHardpointFireOffsets().size());

        // 渲染导弹和炮管的武器使用原本武器的开火点数量避免报错，否则以装饰武器的开火点数量为准
        while (sync.getMissileRenderData() != null || sync.getBarrelSpriteAPI() != null) {
            i = sync.getSpec().getTurretFireOffsets().size();
            i2 = sync.getSpec().getHardpointFireOffsets().size();
            break;
        }

        // 确保武器的spec经过clone，不会直接修改所有同类武器
        sync.ensureClonedSpec();

        // 清空发射点
        sync.getSpec().getTurretFireOffsets().clear();
        sync.getSpec().getTurretAngleOffsets().clear();
        sync.getSpec().getHardpointFireOffsets().clear();
        sync.getSpec().getHardpointAngleOffsets().clear();

        // 装饰武器的开火点数量
        int decoPointNum = weapon.getSpec().getTurretFireOffsets().size();

        // 遍历，同时输入坐标和角度确保数量匹配
        for (int a = 0; a < i; a++) {
            int n = a;
            while (n >= decoPointNum) {
                n -= decoPointNum;
            }
            Vector2f p = weapon.getSpec().getTurretFireOffsets().get(n);
            Vector2f point = Vector2f.add(p, diff, null);
            float ang = weapon.getSpec().getTurretAngleOffsets().get(n);
            sync.getSpec().getTurretFireOffsets().add(point);
            sync.getSpec().getTurretAngleOffsets().add(ang);
        }

        decoPointNum = weapon.getSpec().getHardpointFireOffsets().size();
        for (int a = 0; a < i2; a++) {
            int n = a;
            while (n >= decoPointNum) {
                n -= decoPointNum;
            }
            Vector2f p = weapon.getSpec().getHardpointFireOffsets().get(n);
            Vector2f point = Vector2f.add(p, diff, null);
            float ang = weapon.getSpec().getHardpointAngleOffsets().get(n);
            sync.getSpec().getHardpointFireOffsets().add(point);
            sync.getSpec().getHardpointAngleOffsets().add(ang);
        }

        while (sync.getMissileRenderData() != null) {
            sync.getMissileRenderData().clear();
            for (MissileRenderDataAPI render : sync.getMissileRenderData()) {
                render.getSprite().setWidth(0);
                render.getSprite().setHeight(0);
                render.getMissileCenterLocation().set(0, 0);
            }
            break;
        }
    }
}
