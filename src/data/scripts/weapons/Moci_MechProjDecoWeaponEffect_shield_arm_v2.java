package data.scripts.weapons;

import java.awt.Color;
import java.util.List;

import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

import data.scripts.util.Moci_ColorData;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileRenderDataAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

import data.scripts.util.Moci_Util;
import data.scripts.weapons.Moci_MechDecoWeaponMutexControl;

/**
 * 机甲护盾手臂装饰武器控制脚本（v2）。
 *
 * <p>这个脚本挂在“护盾手臂”的装饰武器槽位上，每帧根据当前战况决定手臂、真实武器、
 * 隐藏跟随武器、护盾模块以及遮罩/外壳贴图的朝向和显示状态。</p>
 *
 * <p>槽位命名约定是本脚本能够找到关联对象的关键：</p>
 * <ul>
 *     <li>当前装饰武器槽位：例如 ARM_L、ARM_R 或其他带后缀的手臂槽位。</li>
 *     <li>护盾模块槽位：SHIELD_ + 当前槽位最后一个下划线后的后缀，例如 SHIELD_L。</li>
 *     <li>真实武器槽位：当前槽位 + _SOURCE，用于实际射击和提供默认瞄准角。</li>
 *     <li>隐藏跟随武器槽位：当前槽位 + _FOLLOW，用于保持武器组/开火点等机制正常，但视觉上隐藏。</li>
 *     <li>遮罩武器槽位：护盾模块槽位 + _COVER，用动画帧表现护盾存在/破坏后的外观。</li>
 * </ul>
 *
 * <p>控制优先级从高到低：</p>
 * <ol>
 *     <li>战斗暂停或引擎不存在：不处理。</li>
 *     <li>光剑挥动互斥：只同步装饰手臂到 SOURCE 角度，避免和近战动画抢控制权。</li>
 *     <li>玩家手操 SOURCE/FOLLOW 所在武器组：完全尊重玩家瞄准角。</li>
 *     <li>自动防御：发现附近敌舰、敌方弹丸或导弹时，把手臂平滑转到固定防御偏角。</li>
 *     <li>普通待机：没有威胁时让装饰手臂跟随 SOURCE 的自由瞄准角。</li>
 * </ol>
 */
public class Moci_MechProjDecoWeaponEffect_shield_arm_v2 implements EveryFrameWeaponEffectPlugin {
    // 预留日志对象，方便后续调试初始化、槽位匹配或威胁判定问题。
    private static final org.apache.log4j.Logger log = Global.getLogger(Moci_MechProjDecoWeaponEffect_shield_arm_v2.class);
    
    // 挂载该装饰武器的母舰。初始化后缓存，避免每帧重复从 weapon 上取。
    ShipAPI ship;

    // 每个武器效果实例只需要扫描一次槽位和模块，init 用来标记是否已经完成绑定。
    boolean init = false;

    // 与当前手臂对应的护盾子模块。护盾存活时可被脚本转向；死亡后停止自动举盾。
    private ShipAPI shield = null;

    // 自动顶盾时的角速度，单位约为“度/秒”。amount 是每帧经过的秒数。
    private final float rotationSpeed = 35f; // 转向速度

    // 预留默认偏角字段。当前 v2 逻辑没有直接使用，保留它可以兼容早期版本的参数结构。
    private final float defaultOffset = 0f; // 默认角度

    // 真实武器：实际承担开火、瞄准和武器组控制的槽位，命名为“当前槽位_SOURCE”。
    protected WeaponAPI source; // 新增：关联的_SOURCE武器

    // 隐藏跟随武器：参与武器组/控制逻辑，但贴图和导弹渲染会被隐藏，命名为“当前槽位_FOLLOW”。
    WeaponAPI sourceFollow; // 新增：FOLLOW武器引用

    // 护盾外观遮罩/盖板武器：通过动画帧显示“护盾存在”或“护盾破坏”的外观。
    WeaponAPI coverWeapon; // 新增：COVER武器引用（用于控制帧动画）

    /**
     * Starsector 每帧调用一次的武器效果入口。
     *
     * @param amount 本帧经过的战斗时间，单位为秒，用于把角速度转换成这一帧的角度变化量
     * @param engine 当前战斗引擎，暂停或为空时不应修改战斗对象
     * @param weapon 挂载本脚本的装饰手臂武器
     */
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 战斗引擎不存在或游戏暂停时直接跳过，避免暂停期间动画/角度继续推进。
        if (engine == null || engine.isPaused()) {
            return;
        }

        // 初始化只做一次：解析槽位命名、绑定护盾模块、SOURCE/FOLLOW/COVER 等关联武器。
        if (!init) {
            init = true;
            ship = weapon.getShip();

            // 当前装饰武器槽位决定所有关联对象的命名。lastIndexOf("_") 后的部分通常是 L/R 等方位后缀。
            String slotId = weapon.getSlot().getId();
            String shieldId = "SHIELD_" + slotId.substring(slotId.lastIndexOf("_") + 1);
            String coverSlotId = shieldId + "_COVER"; // 新增：COVER槽位ID

            // 在母舰的子模块中查找对应护盾。护盾在游戏里是一个子 ShipAPI，而不是普通武器。
            for (ShipAPI module : ship.getChildModulesCopy()) {
                if (module.getStationSlot() != null && module.getStationSlot().getId().equals(shieldId)) {
                    shield = module;
                    break;
                }
            }

            // 在母舰武器列表中查找和该手臂绑定的真实武器、隐藏跟随武器、护盾遮罩武器。
            String sourceId = slotId + "_SOURCE";
            String followId = slotId + "_FOLLOW";
            for (WeaponAPI w : ship.getAllWeapons()) {
                if (sourceId.equals(w.getSlot().getId())) {
                    source = w;
                }
                if (followId.equals(w.getSlot().getId())) {
                    sourceFollow = w;
                }
                // 新增：查找COVER武器
                if (coverSlotId.equals(w.getSlot().getId())) {
                    coverWeapon = w;
                }
            }

            // FOLLOW 依赖 SOURCE 和装饰武器的开火点信息；两者都存在时才需要同步和隐藏。
            if (source != null && sourceFollow != null) {
                initializeFollowWeapon(weapon);
            }
        }

        // COVER 只负责视觉外壳：角度跟随装饰手臂，帧数表示护盾是否还活着。
        if (coverWeapon != null) {
            // 遮罩贴图必须和手臂角度一致，否则护盾盖板会和手臂错位。
            coverWeapon.setCurrAngle(weapon.getCurrAngle());
            
            // v2 使用两帧：0 = 护盾存活状态；1 = 护盾不存在/被打爆后的状态。
            if (coverWeapon.getAnimation() != null) {
                if (shield != null && shield.isAlive()) {
                    coverWeapon.getAnimation().setFrame(0); // 护盾存活：第0帧
                } else {
                    coverWeapon.getAnimation().setFrame(1); // 护盾死亡：第1帧
                }
            }
        }

        // ========== 互斥控制：检查光剑是否正在挥动 ==========
        // 近战/光剑动画也会控制手臂角度。若全局互斥标记为真，本脚本让出控制权，只做必要同步。
        if (Moci_MechDecoWeaponMutexControl.isBladeSwingActive(weapon)) {
            if (source != null) {
                // 装饰手臂跟随 SOURCE，避免自动防御逻辑把挥剑动画拉回去。
                Moci_MechDecoWeaponMutexControl.syncAngleOnly(weapon, source);
                if (sourceFollow != null) {
                    // FOLLOW 继续贴着 SOURCE，但仍保持不可见。
                    sourceFollow.setCurrAngle(source.getCurrAngle());
                    hideFollowWeapon();
                }
            }
            return; // 重要：直接返回，不执行后续控制逻辑
        }

        // 玩家手操优先于自动顶盾
        WeaponAPI manualControlWeapon = getManualControlWeapon();
        if (manualControlWeapon != null) {
            // 如果玩家当前武器组里选中了 SOURCE/FOLLOW，就把玩家瞄准角作为最高优先级。
            float manualAngle = manualControlWeapon.getCurrAngle();
            weapon.setCurrAngle(manualAngle);

            // 玩家手操 FOLLOW 时，SOURCE 也要同步到该角度；反之亦然。
            if (source != null && source != manualControlWeapon) {
                source.setCurrAngle(manualAngle);
            }
            if (sourceFollow != null && sourceFollow != manualControlWeapon) {
                sourceFollow.setCurrAngle(manualAngle);
                hideFollowWeapon();
            }

            // 真实护盾模块也面向玩家瞄准方向，这样视觉手臂和可被命中的盾牌模块一致。
            if (shield != null && shield.isAlive()) {
                shield.setFacing(manualAngle);
            }
            return;
        }

        // 非手操状态下，FOLLOW武器始终跟随SOURCE并保持隐藏。
        // 这样它能保留武器组/开火点用途，但不会在画面上多显示一把武器。
        if (sourceFollow != null && source != null) {
            sourceFollow.setCurrAngle(source.getCurrAngle());
            hideFollowWeapon();
        }

        // 检查是否有需要自动举盾的威胁。返回值只表示“有威胁”，当前防御角不直接追踪威胁方向。
        CombatEntityAPI target = findNearestThreat();
        
        // 如果有威胁，执行护盾防御逻辑。
        if (target != null) {
            // 固定防御偏角：相对母舰朝向 -50 度。该值决定手臂举盾姿势，而不是朝向目标本身。
            float targetOffset = -50f;
            
            // 先把当前手臂角度换算成相对母舰的偏角，并规范化到 [-180, 180]，便于走最短插值。
            float currentOffset = weapon.getCurrAngle() - ship.getFacing();
            while (currentOffset > 180) currentOffset -= 360;
            while (currentOffset < -180) currentOffset += 360;

            // 按 rotationSpeed 平滑靠近固定防御偏角，避免瞬间转向造成视觉突变。
            if (currentOffset != targetOffset) {
                if (currentOffset < targetOffset) {
                    currentOffset = Math.min(currentOffset + rotationSpeed * amount, targetOffset);
                } else {
                    currentOffset = Math.max(currentOffset - rotationSpeed * amount, targetOffset);
                }
            }

            // 把相对偏角还原成世界角度，并同步给装饰手臂和 SOURCE。
            float finalAngle = ship.getFacing() + currentOffset;
            weapon.setCurrAngle(finalAngle);

            // 自动举盾期间 SOURCE 跟随手臂，保证真实武器/开火点/护盾模块不会和手臂姿态分离。
            if (source != null) {
                source.setCurrAngle(finalAngle);
            }
        }
        // 无威胁时，护盾臂跟随 SOURCE 武器（让 SOURCE 保持游戏原生/AI/玩家武器组的自由控制）。
        else if (source != null) {
            // 计算 SOURCE 相对母舰的角度，避免跨越 0/360 度时出现大幅跳角。
            float shipFacing = weapon.getShip().getFacing();
            float sourceAngle = source.getCurrAngle() - shipFacing;

            // 规范化角度到 [-180, 180] 范围。
            while (sourceAngle > 180) sourceAngle -= 360;
            while (sourceAngle < -180) sourceAngle += 360;

            // 装饰手臂最终使用“母舰朝向 + SOURCE相对角”，视觉上等价于跟随 SOURCE。
            float targetAngle = shipFacing + sourceAngle;
            weapon.setCurrAngle(targetAngle);
        }

        // 保底逻辑：只要护盾模块还活着，就让它最终面向 SOURCE。
        // 这确保实际可碰撞/可受伤的护盾模块和武器瞄准角保持一致。
        if (shield != null && shield.isAlive() && source != null) {
            shield.setFacing(source.getCurrAngle());
        }
    }

    /**
     * 初始化FOLLOW武器
     * FOLLOW武器同步到装饰武器的位置
     *
     * <p>FOLLOW 武器通常不用于显示，而是辅助保持武器组、开火点或某些武器机制的兼容。
     * 初始化时会把 FOLLOW 的开火点改到装饰武器对应的位置，然后立即隐藏它的贴图。</p>
     *
     * @param weapon 挂载本脚本的装饰手臂武器
     */
    private void initializeFollowWeapon(WeaponAPI weapon) {
        if (source == null || sourceFollow == null) return;

        // 计算“装饰武器槽位 - FOLLOW槽位”的局部坐标差。
        // 后续把这个差值加到装饰武器开火点上，就能得到 FOLLOW 应该使用的开火点。
        Vector2f diffFollow = new Vector2f();
        Vector2f.sub(weapon.getSlot().getLocation(), sourceFollow.getSlot().getLocation(), diffFollow);
        
        // 获取装饰武器的第一个炮塔开火点，作为 FOLLOW 对齐的基准点。
        Vector2f decorativeFirePoint = weapon.getSpec().getTurretFireOffsets().get(0);
        syncFirePoint(sourceFollow, diffFollow, decorativeFirePoint);
        
        // 初始隐藏 FOLLOW，之后每帧也会重复隐藏，防止其他效果把贴图颜色改回来。
        hideFollowWeapon();
    }

    /**
     * 隐藏FOLLOW武器
     *
     * <p>隐藏方式分两层：</p>
     * <ul>
     *     <li>把普通贴图、炮管贴图、底层贴图和发光贴图都设成完全透明。</li>
     *     <li>如果武器使用导弹渲染数据，把对应 sprite 宽高设为 0，避免导弹模型/投影残留。</li>
     * </ul>
     */
    private void hideFollowWeapon() {
        if (sourceFollow == null) return;
        
        // 透明颜色，参考骤雨 mod 的实现；Moci_ColorData.NONE 应为 alpha = 0 的颜色。
        Color transparent = Moci_ColorData.NONE;
        
        // 设置所有常见武器贴图组件为透明，包括发光贴图。
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

        // 某些武器的可见部分来自 MissileRenderDataAPI，仅改 weapon sprite 不足以隐藏它们。
        if (sourceFollow.getMissileRenderData() != null) {
            for (MissileRenderDataAPI render : sourceFollow.getMissileRenderData()) {
                render.getSprite().setWidth(0);
                render.getSprite().setHeight(0);
            }
        }
    }

    /**
     * 批量修改开火点
     *
     * <p>Starsector 的武器规格中可能同时有 turret/hardpoint/hidden 三套开火点列表。
     * 这个方法先 clone 一份武器规格，避免修改全局 weapon spec，然后把三套列表里的每个开火点
     * 都替换成同一个“目标开火点 + 槽位差值”。这样不管武器当前使用哪种挂载类型，
     * 实际发射位置都会和装饰武器对齐。</p>
     *
     * @param sync     要被同步开火点的武器，通常是隐藏 FOLLOW 武器
     * @param diff     装饰武器槽位相对 sync 槽位的坐标差值；方法内部会把 override 加到该向量上
     * @param override 装饰武器提供的目标开火点；为空时按 (0,0) 处理
     */
    public static void syncFirePoint(WeaponAPI sync, Vector2f diff, Vector2f override) {
        if (override == null) {
            override = new Vector2f();
        }

        // 得到 sync 武器局部坐标系下的最终开火点。
        // 注意这里会直接修改传入的 diff 对象，因此调用方不应在之后继续依赖原始 diff。
        Vector2f.add(override, diff, diff);

        // clone 后再改 spec，避免把同型号武器的全局开火点一起改掉。
        sync.ensureClonedSpec();

        // 记录原列表长度，后面清空并用相同数量的新坐标填回去。
        int t = sync.getSpec().getTurretFireOffsets().size();
        int h = sync.getSpec().getHardpointFireOffsets().size();
        int hd = sync.getSpec().getHiddenFireOffsets().size();

        // 清空旧开火点，避免旧位置和新位置混用。
        sync.getSpec().getTurretFireOffsets().clear();
        sync.getSpec().getHardpointFireOffsets().clear();
        sync.getSpec().getHiddenFireOffsets().clear();

        // 每次 add 都创建新 Vector2f，避免多个列表元素共享同一个可变向量实例。
        for (int i = 0; i < t; i++) {
            sync.getSpec().getTurretFireOffsets().add(new Vector2f(diff));
        }
        for (int i = 0; i < h; i++) {
            sync.getSpec().getHardpointFireOffsets().add(new Vector2f(diff));
        }
        for (int i = 0; i < hd; i++) {
            sync.getSpec().getHiddenFireOffsets().add(new Vector2f(diff));
        }

        // 如果 sync 是导弹类/特殊渲染武器，同步开火点后顺手把渲染宽高归零，保持 FOLLOW 不可见。
        if (sync.getMissileRenderData() != null) {
            for (MissileRenderDataAPI render : sync.getMissileRenderData()) {
                render.getSprite().setWidth(0);
                render.getSprite().setHeight(0);
            }
        }
    }

    /**
     * 查找最近的威胁目标
     *
     * <p>威胁判定只决定是否进入“自动顶盾姿势”，并不把手臂转向威胁所在方向。
     * 当前规则会优先考虑最近的敌舰，然后再把 600 范围内的敌方弹丸/导弹纳入比较。</p>
     *
     * @return 最近的敌方威胁；没有需要自动防御的对象时返回 null
     */
    private CombatEntityAPI findNearestThreat() {
        // 不在战斗场景中或暂停时不做威胁扫描。
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || engine.isPaused()) {
            return null;
        }

        // 母舰原生护盾已经开启，或 SOURCE 正在开火时，不抢控制权自动举盾。
        // 这能避免防御姿态干扰玩家/AI 的主动攻击动作。
        if ((ship.getShield() != null && ship.getShield().isOn()) || (source != null && source.isFiring())) {
            return null;
        }

        // 子模块护盾不存在或已被摧毁时，没有必要进入举盾姿态。
        if (shield == null || !shield.isAlive()) {
            return null;
        }

        CombatEntityAPI nearestThreat = null;
        float nearestDistance = Float.MAX_VALUE;

        // 检查 1000 范围内的敌方舰船，记录最近者。
        for (ShipAPI enemy : Global.getCombatEngine().getShips()) {
            if (enemy.isAlive() && enemy.getOwner() != ship.getOwner()) {
                float distance = MathUtils.getDistance(ship, enemy);
                if (distance < 1000f && distance < nearestDistance) {
                    nearestThreat = enemy;
                    nearestDistance = distance;
                }
            }
        }

        // 使用工具类获取 600 范围内的敌方弹丸和导弹，再和上面找到的敌舰距离一起比较。
        List<DamagingProjectileAPI> threats = Moci_Util.getEnemyProjectilesAndMissilesWithinRange(
            ship.getLocation(), 600f, ship.getOwner());
        
        for (DamagingProjectileAPI threat : threats) {
            float distance = MathUtils.getDistance(ship, threat);
            if (distance < nearestDistance) {
                nearestThreat = threat;
                nearestDistance = distance;
            }
        }

        return nearestThreat;
    }

    /**
     * 判断玩家当前是否正在通过武器组手动控制 SOURCE 或 FOLLOW。
     *
     * <p>判定分两步：</p>
     * <ul>
     *     <li>如果当前武器组的 active weapon 就是 SOURCE/FOLLOW，直接视为手操。</li>
     *     <li>如果 SOURCE/FOLLOW 在当前选中武器组里，即使不是 active weapon，也让手臂尊重该组角度。</li>
     * </ul>
     *
     * @return 被视为手动控制来源的武器；没有手操时返回 null
     */
    private WeaponAPI getManualControlWeapon() {
        // ship 还没初始化，或没有选中武器组时，无法判断手操。
        if (ship == null || ship.getSelectedGroupAPI() == null) {
            return null;
        }

        // 优先使用当前武器组的 active weapon，这是最明确的玩家控制对象。
        WeaponAPI activeWeapon = ship.getSelectedGroupAPI().getActiveWeapon();
        if (activeWeapon == sourceFollow || activeWeapon == source) {
            return activeWeapon;
        }

        // 其次检查选中武器组是否包含 FOLLOW。FOLLOW 通常隐藏，但仍可能作为控制代理存在。
        if (sourceFollow != null && ship.getSelectedGroupAPI().getWeaponsCopy().contains(sourceFollow)) {
            return sourceFollow;
        }

        // 最后检查 SOURCE。只要 SOURCE 所在组被选中，就认为玩家有意控制该手臂方向。
        if (source != null && ship.getSelectedGroupAPI().getWeaponsCopy().contains(source)) {
            return source;
        }

        return null;
    }
}
