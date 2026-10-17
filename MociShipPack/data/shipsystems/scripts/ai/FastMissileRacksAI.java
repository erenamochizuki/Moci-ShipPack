// 引入必要的包和类  
package data.shipsystems.scripts.ai;  
  
import java.util.List;  
  
import org.lwjgl.util.vector.Vector2f;  
  
import com.fs.starfarer.api.combat.CombatEngineAPI;  
import com.fs.starfarer.api.combat.ShipAPI;  
import com.fs.starfarer.api.combat.ShipSystemAIScript;  
import com.fs.starfarer.api.combat.ShipSystemAPI;  
import com.fs.starfarer.api.combat.ShipwideAIFlags;  
import com.fs.starfarer.api.combat.WeaponAPI;  
import com.fs.starfarer.api.combat.WeaponAPI.WeaponSize;  
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;  
import com.fs.starfarer.api.util.IntervalUtil;  
  
/**  
 * 示例船载系统AI脚本，用于控制快速导弹发射架。  
 *   
 * 此代码仅为示例，并未在实际游戏中使用。  
 * 要启用它，请在fastmissileracks.system中取消相关行的注释。  
 *   
 * @author Alex Mosolov  
 * @copyright 2012 Fractal Softworks, LLC  
 */  
public class FastMissileRacksAI implements ShipSystemAIScript {  
  
    // 成员变量，用于存储船只、战斗引擎、AI标志和系统API的引用  
    private ShipAPI ship;  
    private CombatEngineAPI engine;  
    private ShipwideAIFlags flags;  
    private ShipSystemAPI system;  
      
    // 时间间隔跟踪器，用于定期执行检查  
    private IntervalUtil tracker = new IntervalUtil(0.5f, 1f);  
      
    /**  
     * 初始化方法，接收船只、系统、AI标志和战斗引擎的引用，并存储它们。  
     */  
    public void init(ShipAPI ship, ShipSystemAPI system, ShipwideAIFlags flags, CombatEngineAPI engine) {  
        this.ship = ship;  
        this.flags = flags;  
        this.engine = engine;  
        this.system = system;  
    }  
      
    // 用于记录最佳冷却时间占比和自上次发射以来的时间  
    private float bestFractionEver = 0f;  
    private float sinceLast = 0f;  
      
    /**  
     * 游戏循环中的推进方法，用于根据条件决定是否使用导弹发射架。  
     */  
    @SuppressWarnings("unchecked")  
    public void advance(float amount, Vector2f missileDangerDir, Vector2f collisionDangerDir, ShipAPI target) {  
        tracker.advance(amount); // 更新时间间隔跟踪器  
          
        sinceLast += amount; // 自上次发射以来累加时间  
          
        // 如果时间间隔已过去，则执行以下检查  
        if (tracker.intervalElapsed()) {  
            // 如果系统正在冷却、弹药耗尽、已激活或无目标，则不执行任何操作  
            if (system.getCooldownRemaining() > 0) return;  
            if (system.isOutOfAmmo()) return;  
            if (system.isActive()) return;  
            if (target == null) return;  
              
            // 初始化变量以计算导弹武器的总冷却时间和剩余冷却时间  
            float maxCooldown = 0f;  
            float totalCooldownSaved = 0f;  
              
            // 初始化变量以计算武器的总权重和导弹武器的总权重  
            float totalWeight = 0f;  
            float totalReloadWeight = 0f;  
              
            // 获取船只上的所有武器  
            List weapons = ship.getAllWeapons();  
            for (int i = 0; i < weapons.size(); i++) {  
                WeaponAPI w = (WeaponAPI) weapons.get(i); // 获取当前武器  
                  
                // 根据武器大小设置权重  
                float weight = 0;  
                WeaponSize size = w.getSize();  
                if (size == WeaponSize.SMALL) weight = 1;  
                else if (size == WeaponSize.MEDIUM) weight = 2;  
                else if (size == WeaponSize.LARGE) weight = 4;  
                  
                totalWeight += weight; // 累加总权重  
                  
                // 仅考虑导弹武器且冷却时间大于2秒的武器  
                if (w.getType() != WeaponType.MISSILE) continue;  
                if (w.getCooldown() < 2f) continue;  
                  
                // 累加导弹武器的最大冷却时间和剩余冷却时间  
                maxCooldown += w.getCooldown();  
                totalCooldownSaved += w.getCooldownRemaining();  
                  
                // 累加导弹武器的总权重  
                totalReloadWeight += weight;  
            }  
              
            // 如果最大冷却时间、总剩余冷却时间或总权重为零，则不执行任何操作  
            if (maxCooldown <= 0 || totalCooldownSaved <= 0 || totalWeight <= 0f) return;  
              
            // 计算重新装填的重要性  
            float reloadSignificance = totalReloadWeight / totalWeight;  
              
            // 获取船只的当前能量水平  
            float fluxLevel = ship.getFluxTracker().getFluxLevel();  
              
            // 检查目标是否处于易受攻击状态（过载或排气）  
            boolean targetIsVulnerable = target != null && target.getFluxTracker().isOverloadedOrVenting() &&   
                                         (target.getFluxTracker().getOverloadTimeRemaining() > 5f ||   
                                          target.getFluxTracker().getTimeToVent() > 5f);  
              
            // 如果目标易受攻击，则增加重新装填的重要性  
            if (targetIsVulnerable) reloadSignificance *= 2f;  
              
            // 计算剩余的能量水平  
            float remainingFluxLevel = 1f - fluxLevel;  
              
            // 计算使用导弹发射架所需的能量占最大能量的比例  
            float fluxFractionPerUse = system.getFluxPerUse() / ship.getFluxTracker().getMaxFlux();  
              
            // 如果所需能量超过剩余能量，则不执行任何操作  
            if (fluxFractionPerUse > remainingFluxLevel) return;  
              
            // 计算使用后的能量水平  
            float fluxLevelAfterUse = fluxLevel + fluxFractionPerUse;  
              
            // 如果使用后的能量水平过高，则不执行任何操作  
            if (fluxLevelAfterUse > reloadSignificance || (fluxLevelAfterUse > 0.9f && fluxFractionPerUse > 0.025f)) return;  
              
            // 如果目标不易受攻击且自上次发射以来的时间不足10秒，则不执行任何操作  
            if (!targetIsVulnerable && sinceLast < 10f) return;  
              
            // 计算剩余冷却时间占最大冷却时间的比例  
            float fraction = totalCooldownSaved / maxCooldown;  
              
            // 如果当前比例是最佳比例，则更新最佳比例  
            if (fraction > bestFractionEver) {  
                bestFractionEver = fraction;  
            }  
              
            // 如果比例达到或超过0.5，则使用导弹发射架，并重置自上次发射以来的时间  
            if (fraction >= 0.5) {  
                ship.useSystem();  
                sinceLast = 0f;  
                return;  
            }  
        }  
    }  
}