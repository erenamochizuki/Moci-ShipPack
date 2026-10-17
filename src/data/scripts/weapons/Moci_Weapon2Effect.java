package data.scripts.weapons;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.lazywizard.lazylib.CollisionUtils;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.loading.DamagingExplosionSpec;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.util.Misc;

import static data.scripts.weapons.Moci_MechProjDecoWeaponEffect.syncFirePoint;

/**
 * 机甲打桩武器效果实现
 * 该类实现了一个特殊的机甲打桩武器效果，包括:
 * 1. 冲刺和打桩动画控制
 * 2. 碰撞检测和伤害应用
 * 3. 视觉和声音效果
 * 4. 机甲各部件的协调动画
 */
public class Moci_Weapon2Effect implements EveryFrameWeaponEffectPlugin {
    private boolean init = false;
  private static final float EXPLODE_DELAY = 2;
  private WeaponAPI source;
  private static final String OTHER_WEAPON_ID = "Moci_Weapon1";
  // 缓存差值向量，避免重复创建
  private final Vector2f diffVector = new Vector2f();

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }
        if (!init) {
            init = true;
            weapon.getShip().addListener(new Moci_EffectScript(weapon));
            ShipAPI ship = weapon.getShip();
            String slotId = weapon.getSlot().getId();
            String sourceId = slotId + "_SOURCE";
            for (WeaponAPI w : ship.getAllWeapons()) {
                if (sourceId.equals(w.getSlot().getId())) {
                    source = w;
                    break;
                }
            }
            if (source != null) {
        // 使用缓存的向量进行计算
        Vector2f.sub(weapon.getSlot().getLocation(), source.getSlot().getLocation(), diffVector);
                Vector2f firePoint = weapon.getSpec().getTurretFireOffsets().get(0);
        syncFirePoint(source, diffVector, firePoint);
        
        // 创建一次透明颜色对象
        Color transparentColor = new Color(0, 0, 0, 0);
        // 隐藏源武器的所有精灵
                if (source.getSprite() != null) {
          source.getSprite().setColor(transparentColor);
                }
                if (source.getBarrelSpriteAPI() != null) {
          source.getBarrelSpriteAPI().setColor(transparentColor);
                }
                if (source.getUnderSpriteAPI() != null) {
          source.getUnderSpriteAPI().setColor(transparentColor);
                }
                if (source.getGlowSpriteAPI() != null) {
          source.getGlowSpriteAPI().setColor(transparentColor);
                }
            }
        }
        if (source != null) {
            weapon.setCurrAngle(source.getCurrAngle());
        }
    }

  /**
   * 机甲打桩武器效果的主要实现类
   * 该类负责管理武器的状态机、动画控制和效果执行
   * 实现了AdvanceableListener接口以便每帧更新
   */
    public static class Moci_EffectScript implements AdvanceableListener {
        final ShipAPI ship;
        final WeaponAPI weapon;
    CollisionClass collision; // 存储原始碰撞类型，应初始化为null以避免潜在问题
    int chargeUP = 6; // 充能动画帧数
    int fire = 10; // 发射动画帧数
        float timer = 0;
        boolean fired = false;
        ShipAPI target = null;
    private static final float ENGINE_GLOW_MULT = 2f; // 引擎发光倍数
        private Moci_MechSystemFollowerAnimationWeapon wingPlugin = null;
    private punchAnimController controller = null;
    // 缓存所有武器以避免重复获取
    private List<WeaponAPI> cachedWeapons = null;
    // 缓存其他武器ID的武器，避免每帧遍历
    private WeaponAPI otherWeapon = null;

    // 自动瞄准相关常量
    private static final float AUTO_AIM_SEARCH_CONE_ANGLE = 10f; // 索敌锥角 (度)
    private static final float AUTO_AIM_SEARCH_RANGE = 500f;     // 索敌范围
    private static final float AUTO_AIM_WEIGHT_BALANCE_DISTANCE = 350f; // 权重平衡距离
    private static final float AUTO_AIM_ROTATION_DEGREES_PER_SECOND = 5f; // 每秒转向度数 (60fps下每帧1度)
    private ShipAPI autoAimTarget = null; // 当前自动瞄准的目标


    /**
     * 构造函数，初始化武器效果脚本
     * @param weapon 关联的武器对象
     */
        private Moci_EffectScript(WeaponAPI weapon) {
            this.ship = weapon.getShip();
            this.weapon = weapon;
            this.collision = ship.getCollisionClass();
      
      // 缓存所有武器
      this.cachedWeapons = ship.getAllWeapons();
      
      // 初始化时查找所需的武器
      for (WeaponAPI w : cachedWeapons) {
        String slotId = w.getSlot().getId();
        if (slotId.equals("WING_R")) {
                    this.wingPlugin = (Moci_MechSystemFollowerAnimationWeapon) w.getEffectPlugin();
                }
        
        // 缓存OTHER_WEAPON_ID对应的武器
        if (w.getId().equals(OTHER_WEAPON_ID)) {
          this.otherWeapon = w;
        }
            }

      if (controller == null) {
             Set<Object[]> weapons = new HashSet<>();
             WeaponAPI body = null;
        for (WeaponAPI w : cachedWeapons) {
                 if (w.getSlot().getId().equals("BODY")) {
                     body = w;
                 }
                 if (punchAnimController.LINKED_WEAPON_SLOTS.contains(w.getSlot().getId())) {
                     if (w.getSprite() == null) {
                         continue;
                     }
                 float cx = w.getSprite().getCenterX();
                 float cy = w.getSprite().getCenterY();
            Object[] obj = new Object[] {w, cx, cy};
                 weapons.add(obj);
          }
                 }
                 controller = new punchAnimController(weapons, body, ship);
                 ship.addListener(controller);
             }
        }

    /**
     * 武器状态枚举，用于控制武器的状态机
     * 现在改为public以便外部访问
     */
        public enum State {
      IDLE(0),      // 空闲状态
      CHARGE_UP(2), // 充能状态，持续5秒
      PUNCH(0),     // 打击状态
      REBOUND(1),   // 反弹状态，持续1秒
      DE_CHARGE(0); // 放电状态
            private final float duration;

            State(float duration) {
                this.duration = duration;
            }
        }

        // 改为public以便外部访问
        public State state = State.IDLE;

        /**
         * 获取当前武器状态
         * @return 当前状态
         */
        public State getCurrentState() {
            return state;
        }
        
        /**
         * 检查是否处于充能状态
         * @return 如果处于充能状态则返回true
         */
        public boolean isCharging() {
            return state == State.CHARGE_UP;
        }

        /**
     * 触发武器开火
     * 只有在IDLE状态下才能触发
         */
        public void setFire() {
            if (state == State.IDLE) {
                state = State.CHARGE_UP;
                timer = 0;
                fired = false;
                target = null;
                autoAimTarget = null; // 重置自动瞄准目标
        if (wingPlugin != null) {
                    wingPlugin.setExtraActive(true);
                }
        if (controller != null) {
          controller.setTarget(20, 0.3f, 10.0f, State.CHARGE_UP);
        }
            }
        }

    /**
     * 每帧更新方法，实现武器效果的状态机逻辑
     * @param amount 时间增量
     */
        @Override
        public void advance(float amount) {
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine == null || engine.isPaused()) {
                return;
            }
            if (weapon.getAnimation() != null) {
                weapon.getAnimation().pause();
            }
      // 只在充能状态时禁用其他武器，而不是所有非空闲状态
      if ((state == State.CHARGE_UP || state == State.PUNCH) && otherWeapon != null) {
        otherWeapon.setForceNoFireOneFrame(true);
      }
      
      // 充能状态处理
            if (state == State.CHARGE_UP) {
        // 修改碰撞类以便穿过敌舰
                if (collision == null) {
                    collision = ship.getCollisionClass();
                    ship.setCollisionClass(CollisionClass.FIGHTER);
                }
        // 阻止减速和后退
                ship.blockCommandForOneFrame(ShipCommand.DECELERATE);
                ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);
                // 禁止手动转向
                ship.blockCommandForOneFrame(ShipCommand.TURN_LEFT);
                ship.blockCommandForOneFrame(ShipCommand.TURN_RIGHT);

                ship.blockCommandForOneFrame(ShipCommand.STRAFE_LEFT);
                ship.blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT);
                timer += amount;
        // 增加速度和加速度以实现冲刺效果
                    ship.getMutableStats().getMaxSpeed().modifyFlat("Moci_Weapon2", 350);
                    ship.getMutableStats().getAcceleration().modifyFlat("Moci_Weapon2", 10000);
                    ship.giveCommand(ShipCommand.ACCELERATE, null, 0);
        ship.getEngineController()
            .extendFlame(this, ENGINE_GLOW_MULT, ENGINE_GLOW_MULT, ENGINE_GLOW_MULT);
        // 播放充能动画
        if (weapon.getAnimation().getFrame() < chargeUP) {
          weapon.getAnimation().play();
        }

        // ---- 自动瞄准逻辑 ----
        float bestWeight = -Float.MAX_VALUE;
        ShipAPI currentBestTarget = null;
        List<ShipAPI> potentialTargets = AIUtils.getNearbyEnemies(ship, AUTO_AIM_SEARCH_RANGE);
        for (ShipAPI pTarget : potentialTargets) {
            if (pTarget.isFighter() || !pTarget.isAlive() || pTarget.isPhased() || pTarget == ship) {
                continue;
            }
            float angleToPTarget = VectorUtils.getAngle(ship.getLocation(), pTarget.getLocation());
            float angleDiff = Math.abs(Misc.getAngleDiff(ship.getFacing(), angleToPTarget));
            if (angleDiff <= AUTO_AIM_SEARCH_CONE_ANGLE / 2f) { // 在锥形范围内
                float weight = calculateTargetWeight(pTarget, ship);
                if (weight > bestWeight) {
                    bestWeight = weight;
                    currentBestTarget = pTarget;
                }
            }
        }
        autoAimTarget = currentBestTarget;

        // ---- 自动转向逻辑 ----
        if (autoAimTarget != null) {
            float angleToAimTarget = VectorUtils.getAngle(ship.getLocation(), autoAimTarget.getLocation());
            float shipFacing = ship.getFacing();
            float signedAngleDiff = Misc.getAngleDiff(shipFacing, angleToAimTarget); // 带符号的角度差
            float maxRotationThisFrame = AUTO_AIM_ROTATION_DEGREES_PER_SECOND * amount;

            if (Math.abs(signedAngleDiff) > 0.1f) { // 避免抖动
                if (Math.abs(signedAngleDiff) <= maxRotationThisFrame) {
                    ship.setFacing(angleToAimTarget); // 如果足够近，直接对准
                } else {
                    if (signedAngleDiff > 0) { // 目标在右边
                        ship.setFacing(shipFacing + maxRotationThisFrame);
                    } else { // 目标在左边
                        ship.setFacing(shipFacing - maxRotationThisFrame);
                    }
                }
            }
        }
        
        // ---- 碰撞与打桩逻辑 ----
        ShipAPI hitTargetForPunch = null;
        // 优先检查自动瞄准的目标
        if (autoAimTarget != null && !autoAimTarget.isFighter() && autoAimTarget.isAlive() && !autoAimTarget.isPhased()) {
            if (CollisionUtils.isPointWithinBounds(weapon.getFirePoint(0), autoAimTarget)) {
                hitTargetForPunch = autoAimTarget;
            }
        }
        // 如果自动瞄准目标未命中或不适用，检查其他附近非战机目标
        if (hitTargetForPunch == null) {
            // 使用一个合理的范围来获取附近的敌人进行碰撞检测
            List<ShipAPI> nearbyEnemiesForCollision = AIUtils.getNearbyEnemies(ship, ship.getCollisionRadius() + 100f);
            ShipAPI closestHitNonFighter = null;
            float minDistanceSqToClosestHit = Float.MAX_VALUE;

            for (ShipAPI enemy : nearbyEnemiesForCollision) {
                if (enemy.isFighter() || !enemy.isAlive() || enemy.isPhased() || enemy == ship) {
                    continue;
                }
                // 如果这个敌人就是自动瞄准的目标，但上面没选上，这里也跳过 (避免重复逻辑或选已排除的)
                if (enemy == autoAimTarget) continue;

                if (CollisionUtils.isPointWithinBounds(weapon.getFirePoint(0), enemy)) {
                    float dist = MathUtils.getDistance(weapon.getFirePoint(0), enemy.getLocation());
                    float distSq = dist * dist;
                    if (distSq < minDistanceSqToClosestHit) {
                        minDistanceSqToClosestHit = distSq;
                        closestHitNonFighter = enemy;
                    }
                }
            }
            if (closestHitNonFighter != null) {
                hitTargetForPunch = closestHitNonFighter;
            }
        }

        if (hitTargetForPunch != null) { // 找到有效打击目标
          this.target = hitTargetForPunch;
          weapon.getAnimation().setFrame(fire);
          ship.getMutableStats().getMaxSpeed().unmodify("Moci_Weapon2");
          ship.getMutableStats().getAcceleration().unmodify("Moci_Weapon2");
          normalizeShipSpeed();
          if (collision != null) {
              ship.setCollisionClass(collision);
              collision = null;
          }
          state = State.PUNCH;
          if (controller != null) {
            controller.setTarget(-30, 0.15f, 0.1f, State.PUNCH);
          }
          executePunchEffect(engine, this.target);
          autoAimTarget = null; // 重置自动瞄准目标
        } else if (timer > State.CHARGE_UP.duration) { // 充能时间结束，未命中
                    ship.getMutableStats().getMaxSpeed().unmodify("Moci_Weapon2");
                    ship.getMutableStats().getAcceleration().unmodify("Moci_Weapon2");
          normalizeShipSpeed();
                    if (collision != null) {
                        ship.setCollisionClass(collision);
                        collision = null;
                    }
                    state = State.DE_CHARGE;
                    timer = 0;
          if (wingPlugin != null) {
                        wingPlugin.setExtraActive(false);
                    }
          if (controller != null) {
            controller.setTarget(0, 0.2f, 0f, State.DE_CHARGE);
          }
          autoAimTarget = null; // 重置自动瞄准目标
        }
      } 
      // 打击状态处理
      else if (state == State.PUNCH) {
        weapon.getAnimation().play();
                int current = weapon.getAnimation().getFrame();
        if (current == fire && !fired) {
          fired = true;
        }
        // 动画结束后转为反弹状态
        if (current == weapon.getAnimation().getNumFrames() - 1 || current < chargeUP) {
          weapon.getAnimation().setFrame(0);
          state = State.REBOUND;
          timer = 0;
          if (controller != null) {
            controller.setTarget(20, 0.2f, 0.1f, State.REBOUND);
          }
        }
      } 
      // 反弹状态处理
      else if (state == State.REBOUND) {
        timer += amount;
        // 反弹时间结束后转为放电状态
        if (timer > State.REBOUND.duration) {
          state = State.DE_CHARGE;
          timer = 0;
          if (controller != null) {
            controller.setTarget(0, 0.2f, 0f, State.DE_CHARGE);
          }
        }
      } 
      // 放电状态处理
      else if (state == State.DE_CHARGE) {
        int current = weapon.getAnimation().getFrame();
        // 动画回到初始帧时转为空闲状态
                if (current == 0) {
                    state = State.IDLE;
          if (wingPlugin != null) {
            wingPlugin.setExtraActive(false);
          }
                } else {
          // 逐帧回退动画
                    timer += amount * weapon.getAnimation().getFrameRate();
                    while (timer >= 1) {
                        timer -= 1;
                        current -= 1;
                        if (current == 0) {
                            break;
                        }
                    }
                    weapon.getAnimation().setFrame(current);
                }
        // 恢复正常速度和碰撞类
        ship.getMutableStats().getMaxSpeed().unmodify("Moci_Weapon2");
                ship.getMutableStats().getAcceleration().unmodify("Moci_Weapon2");
        normalizeShipSpeed();
        if (collision != null) {
          ship.setCollisionClass(collision);
          collision = null;
        }
      }
    }

    /**
     * 计算潜在目标的权重，用于自动瞄准
     * @param potentialTarget 潜在目标舰船
     * @param selfShip 自身舰船
     * @return 目标权重，越高越好
     */
    private float calculateTargetWeight(ShipAPI potentialTarget, ShipAPI selfShip) {
        if (potentialTarget == null || selfShip == null) return -1f;

        float distance = MathUtils.getDistance(selfShip, potentialTarget);
        float angleToTarget = VectorUtils.getAngle(selfShip.getLocation(), potentialTarget.getLocation());
        float shipFacing = selfShip.getFacing();
        // 角度差，0表示正前方，AUTO_AIM_SEARCH_CONE_ANGLE/2 表示在锥形边缘
        float angleDifference = Math.abs(Misc.getAngleDiff(shipFacing, angleToTarget));

        // 分数越高越好
        // 角度分数：越接近0度越好，最大为 AUTO_AIM_SEARCH_CONE_ANGLE / 2f
        float angleScore = Math.max(0, (AUTO_AIM_SEARCH_CONE_ANGLE / 2f) - angleDifference);
        // 距离分数：越近越好，最大为 AUTO_AIM_SEARCH_RANGE
        float distanceScore = Math.max(0, AUTO_AIM_SEARCH_RANGE - distance);

        float angleFactor;
        float distanceFactor;

        if (distance <= AUTO_AIM_WEIGHT_BALANCE_DISTANCE) {
            angleFactor = 2.0f; // 近距离时，角度更重要
            distanceFactor = 1.0f;
        } else {
            angleFactor = 1.0f; // 远距离时，距离更重要
            distanceFactor = 2.0f;
        }
        // 基础权重，防止0权重
        float baseWeight = 1.0f;
        return baseWeight + (angleScore * angleFactor) + (distanceScore * distanceFactor);
    }


    /**
     * 规范化舰船速度，确保不超过最大速度
     */
    private void normalizeShipSpeed() {
                float defaultMaxSpeed = ship.getMaxSpeed();
                if (ship.getVelocity().length() > defaultMaxSpeed) {
                    Vector2f velocity = ship.getVelocity();
                    velocity.normalise();
                    velocity.scale(defaultMaxSpeed);
                    ship.getVelocity().set(velocity);
                }
    }

    /**
     * 执行打桩效果，包括伤害应用和视觉效果
     */
    private void executePunchEffect(CombatEngineAPI engine, ShipAPI target) {
      if (target == null || target.isFighter() || !target.isAlive() || target.isPhased()) { // 增加对战机和无效状态的过滤
          return;
      }
      float angle = 180 + weapon.getCurrAngle();
      Vector2f point = weapon.getFirePoint(0);
      
      // 获取触发武器的伤害值
      float damage = 0;
      for (WeaponAPI w : ship.getAllWeapons()) {
          if (w.getId().equals("Moci_Weapon2")) {
              damage = w.getDamage().getDamage();
              break;
          }
      }
      
      // 应用伤害（打桩机）
      engine.applyDamage(target, point, damage, DamageType.KINETIC, damage, true, false, ship);
      
      // 调整舰船速度，避免与目标碰撞
      adjustVelocityToAvoidCollision(ship, target, point);
      
      // 添加打桩视觉效果
      Global.getCombatEngine()
          .addLayeredRenderingPlugin(
              new anchorEffect(target, point, weapon.getCurrAngle(), ship, weapon));
      
      // 播放音效
      Global.getSoundPlayer().playSound("Moci_Weapon2Sound", 1f, 1f, point, new Vector2f());
      
      // 缓存向量对象，减少创建
      Vector2f vel = new Vector2f();
      Vector2f particlePos = new Vector2f();
      Color smokeColor = new Color(0.6f, 0.6f, 0.6f, 0.5f);
      Color hitColor1 = new Color(255, 125, 50);
      Color hitColor2 = new Color(255, 20, 10);
      Color hitColor3 = new Color(255, 200, 50);
      Color hitColor4 = new Color(255, 234, 212);
      
      // 添加烟雾粒子效果
      for (int i = 0; i < 20; i++) {
        // 重用向量对象
        vel.set(0, 0);
        MathUtils.getRandomPointInCone(vel, 100, angle - 20, angle + 20);
        vel.scale((float) Math.random());
        Vector2f.add(vel, ship.getVelocity(), vel);
        
        float grey = MathUtils.getRandomNumberInRange(0.5f, 0.75f);
        smokeColor = new Color(grey, grey, grey, MathUtils.getRandomNumberInRange(0.25f, 0.75f));
        
        particlePos.set(point);
        MathUtils.getRandomPointInCircle(particlePos, 5);
        
        engine.addSmokeParticle(
            particlePos,
            vel,
            MathUtils.getRandomNumberInRange(5, 20),
            MathUtils.getRandomNumberInRange(0.25f, 0.75f),
            MathUtils.getRandomNumberInRange(0.25f, 1f),
            smokeColor);
      }
      
      // 添加撞击粒子效果
      for (int i = 0; i < 10; i++) {
        vel.set(0, 0);
        MathUtils.getRandomPointInCone(vel, 250, angle - 20, angle + 20);
        Vector2f.add(vel, ship.getVelocity(), vel);
        
        engine.addSmoothParticle(
            point,
            vel,
            MathUtils.getRandomNumberInRange(2, 4),
            1,
            MathUtils.getRandomNumberInRange(0.05f, 0.25f),
            hitColor1);
      }
      
      // 添加爆炸核心粒子效果
      vel.set(0, 0);
      MathUtils.getRandomPointInCone(vel, 100, angle - 20, angle + 20);
      vel.scale((float) Math.random());
      Vector2f.add(vel, ship.getVelocity(), vel);
      
      engine.addSmoothParticle(point, vel, 100, 0.5f, 0.5f, hitColor2);
      engine.addSmoothParticle(point, vel, 80, 0.75f, 0.15f, hitColor3);
      engine.addSmoothParticle(point, vel, 50, 1, 0.05f, hitColor4);
    }
    
    /**
     * 调整舰船速度，避免与目标碰撞
     */
    private void adjustVelocityToAvoidCollision(ShipAPI ship, ShipAPI target, Vector2f impactPoint) {
        // 获取两艘舰船的速度
        Vector2f shipVel = ship.getVelocity();
        Vector2f targetVel = target.getVelocity();
        
        // 计算相对速度
        Vector2f relativeVel = new Vector2f(shipVel.x - targetVel.x, shipVel.y - targetVel.y);
        
        // 获取从舰船指向目标的方向向量
        Vector2f dirToTarget = Vector2f.sub(target.getLocation(), ship.getLocation(), null);
        float distToTarget = dirToTarget.length();
        
        // 将方向向量标准化
        if (distToTarget > 0) {
            dirToTarget.scale(1f / distToTarget);
            
            // 计算相对速度在船到目标方向上的投影（点积）
            float velAlongDirToTarget = relativeVel.x * dirToTarget.x + relativeVel.y * dirToTarget.y;
            
            // 如果相对速度沿着从船到目标的方向（即船正在靠近目标）
            if (velAlongDirToTarget > 0) {
                // 计算需要抵消的速度分量
                float cancelX = velAlongDirToTarget * dirToTarget.x;
                float cancelY = velAlongDirToTarget * dirToTarget.y;
                
                // 更新船速，抵消相对速度分量
                ship.getVelocity().x = targetVel.x + (relativeVel.x - cancelX);
                ship.getVelocity().y = targetVel.y + (relativeVel.y - cancelY);
                
                // 添加一个小的远离目标的速度分量，防止立即再次碰撞
                ship.getVelocity().x -= dirToTarget.x * 20f;
                ship.getVelocity().y -= dirToTarget.y * 20f;
            }
        }
    }
  }

  /**
   * 打桩动画控制器类
   * 负责协调机甲各部件的动画，实现打桩动作的视觉效果
   */
  private static class punchAnimController implements AdvanceableListener {
    final Set<Object[]> weapons;
    final WeaponAPI body;
    final ShipAPI ship;
    private float originalBodyAngle = 0f;
    private float originalBodyArc = 0f;
    private boolean slotModified = false;
    
    // 需要协调动画的武器槽位
    private static final Set<String> LINKED_WEAPON_SLOTS =
        new HashSet<>(
            Arrays.asList(
                "HEAD", "BODY", "WING_R", "SHOULDER_R", "ARM_L", "SHOULDER_L", "WING_L", "WING_B"));
    // 需要旋转的部件
    private static final Set<String> ROTATING_PARTS =
        new HashSet<>(Arrays.asList("BODY", "WING_B"));
    // 左侧部件
    private static final Set<String> LEFT_PARTS =
        new HashSet<>(Arrays.asList("ARM_L", "SHOULDER_L", "WING_L"));
    // 右侧部件
    private static final Set<String> RIGHT_PARTS =
        new HashSet<>(Arrays.asList("SHOULDER_R", "WING_R"));
    private static final String HEAD_SLOT = "HEAD";
    
    // 用于隐藏舰船原始精灵
    private float originalShipSpriteWidth = 0f;
    private float originalShipSpriteHeight = 0f;
    private boolean hasStoredOriginalSize = false;
    
    // 缓存不同类型的武器部件，避免每帧重新判断
    private final List<Object[]> rotatingParts = new ArrayList<>();
    private final List<Object[]> headParts = new ArrayList<>();
    private final List<Object[]> rightParts = new ArrayList<>();
    private final List<Object[]> leftParts = new ArrayList<>();
    private final List<Object[]> otherParts = new ArrayList<>();
    
    // 缓存机体中心点
    private Vector2f bodyCenter = new Vector2f();

    /**
     * 构造函数
     * @param weapons 需要控制的武器集合
     * @param body 机体主体武器
     * @param ship 舰船对象
     */
    private punchAnimController(Set<Object[]> weapons, WeaponAPI body, ShipAPI ship) {
      this.weapons = weapons;
      this.body = body;
      this.ship = ship;
      if (body != null && body.getSlot() != null) {
        WeaponSlotAPI slot = body.getSlot();
        originalBodyAngle = slot.getAngle();
        originalBodyArc = slot.getArc();
        bodyCenter.set(body.getSlot().getLocation());
      }
      
      // 预先分类所有武器部件
      for (Object[] obj : weapons) {
        WeaponAPI weapon = (WeaponAPI) obj[0];
        String slotId = weapon.getSlot().getId();
        
        if (ROTATING_PARTS.contains(slotId)) {
          rotatingParts.add(obj);
        } else if (slotId.equals(HEAD_SLOT)) {
          headParts.add(obj);
        } else if (RIGHT_PARTS.contains(slotId)) {
          rightParts.add(obj);
        } else if (LEFT_PARTS.contains(slotId)) {
          leftParts.add(obj);
        } else {
          otherParts.add(obj);
        }
      }
    }

    // 动画控制参数
    float punchTargetAngle = 0;
    float currentAngle = 0;
    float timer = 0;
    float actionTime = 0;
    float waitWhenArrive = 0;
    private STATE state = STATE.IDLE;
    private Moci_EffectScript.State currentAnimPhase = Moci_EffectScript.State.IDLE;

    /**
     * 动画控制器状态枚举
     */
    enum STATE {
      IDLE,  // 空闲
      PUNCH, // 打击动作
      WAIT,  // 等待
      BACK   // 回退动作
    }

    /**
     * 设置动画目标
     * @param angle 目标角度
     * @param actionTime 动作时间
     * @param waitWhenArrive 到达后等待时间
     * @param animPhase 当前动画阶段
     */
    public void setTarget(
        float angle, float actionTime, float waitWhenArrive, Moci_EffectScript.State animPhase) {
      // 如果已经在执行相同阶段的动画，则不重复设置
      if (state != STATE.IDLE && currentAnimPhase == animPhase) {
        return;
      }
      this.currentAnimPhase = animPhase;
      punchTargetAngle = angle;
      this.actionTime = actionTime;
      this.waitWhenArrive = waitWhenArrive;
      this.state = STATE.PUNCH;
      timer = 0;
      
      // 隐藏舰船原始精灵，使用武器精灵代替
      if (!hasStoredOriginalSize && ship.getSpriteAPI() != null) {
        originalShipSpriteWidth = ship.getSpriteAPI().getWidth();
        originalShipSpriteHeight = ship.getSpriteAPI().getHeight();
        hasStoredOriginalSize = true;
        ship.getSpriteAPI().setSize(0f, 0f);
      }
      
      // 修改机体武器槽位以允许360度旋转
      if (body != null && body.getSlot() != null && !slotModified) {
        WeaponSlotAPI slot = body.getSlot();
        slot.setArc(360f);
        slotModified = true;
      }
    }

    /**
     * 每帧更新方法，实现动画控制逻辑
     * @param amount 时间增量
     */
    @Override
    public void advance(float amount) {
      // PUNCH状态：执行打击动作
      if (state == STATE.PUNCH) {
        timer += amount;
        float progress = Math.min(1, timer / actionTime);
        currentAngle = Misc.interpolate(0, punchTargetAngle, progress);
        if (progress == 1) {
          state = STATE.WAIT;
          timer = 0;
        }
      } 
      // WAIT状态：等待指定时间
      else if (state == STATE.WAIT) {
        timer += amount;
        // 充能状态下不进入BACK状态，保持姿势
        if (currentAnimPhase == Moci_EffectScript.State.CHARGE_UP) {
          // 空实现，保持当前姿势
        } else if (timer >= waitWhenArrive) {
          state = STATE.BACK;
          timer = 0;
        }
      } 
      // BACK状态：执行回退动作
      else if (state == STATE.BACK) {
        // 根据不同动画阶段调整回退速度
        float effectiveActionTime = actionTime;
        if (currentAnimPhase == Moci_EffectScript.State.DE_CHARGE) {
          effectiveActionTime = actionTime * 2.5f;
        } else if (currentAnimPhase == Moci_EffectScript.State.REBOUND) {
          effectiveActionTime = actionTime * 1.8f;
        }
        timer += amount;
        float progress = Math.min(1, timer / effectiveActionTime);
        currentAngle = Misc.interpolate(punchTargetAngle, 0, progress);
        if (progress == 1) {
          state = STATE.IDLE;
          timer = 0;
          // 恢复舰船原始精灵
          if (currentAnimPhase == Moci_EffectScript.State.DE_CHARGE
              && hasStoredOriginalSize
              && ship.getSpriteAPI() != null) {
            ship.getSpriteAPI().setSize(originalShipSpriteWidth, originalShipSpriteHeight);
          }
          // 恢复机体武器槽位原始设置
          if (currentAnimPhase == Moci_EffectScript.State.DE_CHARGE
              && body != null
              && body.getSlot() != null
              && slotModified) {
            WeaponSlotAPI slot = body.getSlot();
            slot.setArc(originalBodyArc);
            slot.setAngle(originalBodyAngle);
            slotModified = false;
          }
        }
      }
      
      // 更新机体中心点
      if (body != null) {
        bodyCenter.set(body.getSlot().getLocation());
      }
      
      // 处理旋转部件
      for (Object[] obj : rotatingParts) {
        WeaponAPI weapon = (WeaponAPI) obj[0];
        String slotId = weapon.getSlot().getId();
        float rotationAngle = currentAngle;
        
        if (slotId.equals("BODY")) {
          if (currentAnimPhase == Moci_EffectScript.State.CHARGE_UP) {
            rotationAngle = -currentAngle * 0.7f;
          } else if (currentAnimPhase == Moci_EffectScript.State.PUNCH) {
            rotationAngle = -currentAngle * 1.2f;
          } else if (currentAnimPhase == Moci_EffectScript.State.REBOUND) {
            rotationAngle = currentAngle * 0.8f;
          }
        } else if (slotId.equals("WING_B")) {
          if (currentAnimPhase == Moci_EffectScript.State.CHARGE_UP) {
            rotationAngle = currentAngle * 1.2f;
          } else if (currentAnimPhase == Moci_EffectScript.State.PUNCH) {
            rotationAngle = -currentAngle * 0.9f;
          } else if (currentAnimPhase == Moci_EffectScript.State.REBOUND) {
            rotationAngle = -currentAngle * 0.7f;
          }
        }
        
        weapon.setCurrAngle(rotationAngle + ship.getFacing());
      }
      
      // 处理头部部件
      processPartGroup(headParts, getHeadAngle());
      
      // 处理右侧部件
      processPartGroup(rightParts, getRightPartsAngle());
      
      // 处理左侧部件
      processPartGroup(leftParts, getLeftPartsAngle());
      
      // 处理其他部件
      processPartGroup(otherParts, currentAngle);
    }
    
    /**
     * 根据当前动画阶段获取头部角度
     */
    private float getHeadAngle() {
      if (currentAnimPhase == Moci_EffectScript.State.CHARGE_UP) {
        return -currentAngle * 1.0f;
      } else if (currentAnimPhase == Moci_EffectScript.State.PUNCH) {
        return -currentAngle * 0.8f;
      } else if (currentAnimPhase == Moci_EffectScript.State.REBOUND) {
        return currentAngle * 0.5f;
      }
      return currentAngle;
    }
    
    /**
     * 根据当前动画阶段获取右侧部件角度
     */
    private float getRightPartsAngle() {
      if (currentAnimPhase == Moci_EffectScript.State.CHARGE_UP) {
        return currentAngle;
      } else if (currentAnimPhase == Moci_EffectScript.State.PUNCH) {
        return -currentAngle;
      } else if (currentAnimPhase == Moci_EffectScript.State.REBOUND) {
        return currentAngle;
      }
      return currentAngle;
    }
    
    /**
     * 根据当前动画阶段获取左侧部件角度
     */
    private float getLeftPartsAngle() {
      if (currentAnimPhase == Moci_EffectScript.State.CHARGE_UP) {
        return currentAngle;
      } else if (currentAnimPhase == Moci_EffectScript.State.PUNCH) {
        return -currentAngle;
      } else if (currentAnimPhase == Moci_EffectScript.State.REBOUND) {
        return 0;
      }
      return currentAngle;
    }
    
    /**
     * 处理一组部件的位置和旋转
     * @param parts 部件组
     * @param angleToUse 要应用的角度
     */
    private void processPartGroup(List<Object[]> parts, float angleToUse) {
      for (Object[] obj : parts) {
        WeaponAPI weapon = (WeaponAPI) obj[0];
        Vector2f offset = new Vector2f(weapon.getSlot().getLocation());
        Vector2f rotate = Vector2f.sub(offset, bodyCenter, null);
        
        // 应用旋转和位移
        VectorUtils.rotate(rotate, angleToUse);
        Vector2f.add(rotate, bodyCenter, rotate);
        Vector2f.sub(rotate, offset, rotate);
        float cx = (float) obj[1] + rotate.y;
        float cy = (float) obj[2] + rotate.x;
        weapon.getSprite().setCenterX(cx);
        weapon.getSprite().setCenterY(cy);
      }
    }
  }

  /**
   * 打桩锚点效果类
   * 实现打桩后在目标上留下的视觉效果和延迟爆炸
   */
    private static class anchorEffect extends BaseCombatLayeredRenderingPlugin {
        final ShipAPI ship;
        final Vector2f offset;
        final SpriteAPI sprite;
        final float facing;
        Vector2f location;
        final ShipAPI source;
        final WeaponAPI weapon;
    private int explosionCount = 0;
    private float nextExplosionTime = 0f;
    private float lastBeepTime = 0f;
    private static final float BEEP_INTERVAL = 1f;
    
    // 缓存颜色对象，避免重复创建
    private Color flashColor = new Color(1f, 1f, 1f, 1f);
    // 缓存爆炸颜色
    private final Color explosionColor1 = new Color(255, 79, 15, 255);
    private final Color explosionColor2 = new Color(255, 227, 185, 100);
    // 缓存零向量，用于音效位置偏移
    private final Vector2f zeroVector = new Vector2f(0f, 0f);

    /**
     * 构造函数
     * @param ship 目标舰船
     * @param location 锚点位置
     * @param facing 朝向角度
     * @param source 来源舰船
     * @param weapon 来源武器
     */
    private anchorEffect(
        ShipAPI ship, Vector2f location, float facing, ShipAPI source, WeaponAPI weapon) {
            this.ship = ship;
            this.source = source;
            this.weapon = weapon;
      // 将世界坐标转换为相对于目标舰船的偏移量
            this.offset = LocationToOffSet(ship, location);
      // 加载打桩精灵
            this.sprite = Global.getSettings().getSprite("graphics/missiles/Moci_pile.png");
            this.facing = normalizeAngle(facing - ship.getFacing());
            this.location = new Vector2f(location);
            this.layer = CombatEngineLayers.ABOVE_SHIPS_LAYER;
      this.nextExplosionTime = EXPLODE_DELAY;
        }

        @Override
        public float getRenderRadius() {
      return 30;
        }

        float timer = 0;

    /**
     * 渲染方法，绘制打桩锚点
     */
        @Override
        public void render(CombatEngineLayers layer, ViewportAPI viewport) {
            if (layer == this.layer) {
                sprite.setAngle(facing + ship.getFacing() - 90);
        // 在爆炸前闪烁效果
                if (timer < EXPLODE_DELAY) {
          float flashIntensity = (float) Math.sin(timer * 10f) * 0.5f + 0.5f;
          flashColor = new Color(1f, 1f - flashIntensity, 1f - flashIntensity, 1f);
          sprite.setColor(flashColor);
                } else {
                    sprite.setColor(Color.WHITE);
                }
                sprite.renderAtCenter(location.x, location.y);
            }
        }

    /**
     * 每帧更新方法，处理锚点效果逻辑
     */
        @Override
        public void advance(float amount) {
            if (isExpired) {
                return;
            }
      // 更新锚点位置，跟随目标舰船移动
            this.location.set(OffSetToLocation(ship, offset));
            this.entity.getLocation().set(location);
            timer += amount;

      // 爆炸前播放警告音效
            if (timer < EXPLODE_DELAY) {
                if (timer - lastBeepTime >= BEEP_INTERVAL) {
          Global.getSoundPlayer().playSound("Moci_Beep", 1f, 0.7f, location, zeroVector);
                    lastBeepTime = timer;
                }
            }

      // 延迟爆炸效果
            if (timer >= nextExplosionTime && explosionCount < 6) {
                float f = normalizeAngle(facing + ship.getFacing());
                // 获取触发武器的伤害值
                float damage = 0;
                for (WeaponAPI w : source.getAllWeapons()) {
                    if (w.getId().equals("Moci_Weapon2")) {
                        damage = w.getDamage().getDamage();
                        break;
                    }
                }
                
                // 创建爆炸效果
                DamagingExplosionSpec spec =
                    new DamagingExplosionSpec(
                        0.25f,
                        75,
                        50,
                        damage * 0.2f,
                        damage * 0.1f,
                        CollisionClass.MISSILE_FF,
                        CollisionClass.MISSILE_FF,
                        3,
                        5,
                        1f,
                        55,
                        explosionColor1,
                        explosionColor2);
                Global.getCombatEngine().spawnDamagingExplosion(spec, source, location);

                spec.setUseDetailedExplosion(false);
        // 施加旋转力
        float force = 45f;
                forceRotate(1, f, force, null, location, ship, true, 10);
                explosionCount++;
        nextExplosionTime += 0.25f;
        // 完成所有爆炸后标记为过期
                if (explosionCount >= 6) {
                    isExpired = true;
                }
            }
        }

        private boolean isExpired = false;

        @Override
        public boolean isExpired() {
            return isExpired;
        }
    }

  /**
   * 将相对偏移量转换为世界坐标
   * @param entity 参考实体
   * @param offset 相对偏移量
   * @return 世界坐标
   */
    public static Vector2f OffSetToLocation(CombatEntityAPI entity, Vector2f offset) {
    if (entity == null || offset == null) return new Vector2f(); // 添加空值检查
        Vector2f loc = new Vector2f(offset);
        VectorUtils.rotate(loc, entity.getFacing(), loc);
        Vector2f.add(loc, entity.getLocation(), loc);
        return loc;
    }

  /**
   * 将世界坐标转换为相对偏移量
   * @param entity 参考实体
   * @param loc 世界坐标
   * @return 相对偏移量
   */
    public static Vector2f LocationToOffSet(CombatEntityAPI entity, Vector2f loc) {
    if (entity == null || loc == null) return new Vector2f(); // 添加空值检查
        Vector2f offset = new Vector2f(loc);
        Vector2f.sub(offset, entity.getLocation(), offset);
        VectorUtils.rotate(offset, -entity.getFacing(), offset);
        return offset;
    }

  /**
   * 规范化角度到-180到180度范围
   * @param ang 输入角度
   * @return 规范化后的角度
   */
    public static float normalizeAngle(float ang) {
        while ((ang > 180f || ang < -180f)) {
            if (ang > 180f) {
                ang -= 360f;
            }
            if (ang < -180f) {
                ang += 360f;
            }
        }
        return ang;
    }

    /**
   * 对舰船施加旋转力
   * @param amount 时间增量
   * @param facing 力的方向
   * @param force 力的大小
   * @param Dirvel 方向速度向量，可为null
   * @param loc 力的作用点
   * @param targetShip 目标舰船
   * @param effectSpeed 是否影响线速度
   * @param rotateSpeed 旋转速度系数
   */
  public static void forceRotate(
      float amount,
      float facing,
      float force,
      Vector2f Dirvel,
      Vector2f loc,
      ShipAPI targetShip,
      boolean effectSpeed,
      float rotateSpeed) {
    if (targetShip == null || loc == null) return; // 添加空值检查
    
        ShipAPI ship_ = targetShip;
        float r = ship_.getCollisionRadius();
    // 如果是模块，考虑与母舰的距离
        if (targetShip.getParentStation() != null) {
            ship_ = targetShip.getParentStation();
            r += Misc.getDistance(targetShip.getLocation(), ship_.getLocation());
        }

    // 根据质量调整力的大小
        float mass = ship_.getMass();
        float massFactor = 1f;
        if (mass > 1000f) {
            massFactor = Math.max(0f, 1f - (mass - 1000f) / 3000f);
            force *= massFactor;
        }

    // 计算力的作用效果
        float f = VectorUtils.getAngle(ship_.getLocation(), loc);
        float d = Misc.getDistance(ship_.getLocation(), loc);
        if (d > r) {
            d = r;
        }
    float dr = d / r;
    float speed_ang = normalizeAngle(facing - f);
    float Rotate_percent = Math.abs(speed_ang / 90);
        if (Rotate_percent > 1) {
            Rotate_percent = 2 - Rotate_percent;
        }
    Rotate_percent *= dr;
        float forcePercent = 1 - Rotate_percent;
        if (speed_ang < 0) {
            Rotate_percent = -Rotate_percent;
        }
    
    // 应用旋转力
        Rotate_percent *= force / ship_.getMass();
        float rotate = Rotate_percent * rotateSpeed;
        if (Math.abs(ship_.getAngularVelocity()) < Math.abs(rotate)) {
            ship_.setAngularVelocity(rotate * amount + ship_.getAngularVelocity());
        }
    
    // 应用线性力
        if (effectSpeed) {
            if (Dirvel == null) {
                Dirvel = new Vector2f(1, 0);
                VectorUtils.rotate(Dirvel, facing);
            }
            Dirvel.scale(forcePercent * force / ship_.getMass());
            forceSpeed(Dirvel, ship_);
        }
    }

    /**
   * 对舰船施加线性力
   * @param vel 速度向量
   * @param launcher 目标舰船
     */
    public static void forceSpeed(Vector2f vel, ShipAPI launcher) {
    if (launcher == null || vel == null) return; // 添加空值检查
        Vector2f shipV = launcher.getVelocity();
        Vector2f.add(shipV, vel, shipV);
    }

  /**
   * 获取舰船上的EffectScript实例
   * @param ship 目标舰船
   * @return EffectScript实例，如果不存在则返回null
   */
    public static Moci_EffectScript getEffectScriptInstance(ShipAPI ship) {
        if (ship == null) return null;
        if (ship.hasListenerOfClass(Moci_EffectScript.class)) {
            return ship.getListeners(Moci_EffectScript.class).get(0);
        }
        return null;
    }
}
