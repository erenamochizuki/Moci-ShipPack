package xi.arsenal;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * 可复用的单机步枪释放动作接入口。它只提供当前实体、真实可见枪与骨架参数，
 * 不拥有蓄力时间、开火、幅能、弹药、伤害或Mod原回调。
 * 通过 RifleFiringPose.attach 取得唯一实例租约；只能在同一战斗线程使用和关闭。
 */
public interface RifleReleaseRig {
    ShipAPI ship();
    /** 当前真正可见的枪；必须属于 ship()，不能给隐藏照射替身或另一台机体的武器。 */
    WeaponAPI source();
    /** 释放节拍的可选强度。普通动作使用0，既有机体策略保持自己的真实输入。 */
    default float presentationPower(){return 0f;}
    /** Opt-in only: zero velocity/acceleration at each authored motion beat. */
    default boolean useQuinticMotionCurve(){return false;}
    /** Only the opt-in curve uses this duration; existing release rigs keep their recovery. */
    default float recoverySeconds(){return RifleFiringPose.CLOSE_SECONDS;}
    /** An opted-in action never takes the native leg animation or leg transform. */
    default boolean preserveNativeLegs(){return false;}
    /** 原Mod该机体真实站腿帧：Z=4、柯西=3、佩涅罗佩=4、海牛=4。不得用blank帧代替。 */
    int standingLegFrame();
}
