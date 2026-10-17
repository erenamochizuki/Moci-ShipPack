package data.hullmods; // 声明当前文件所在的包名为data.hullmods

// 导入必要的Java类库
import java.awt.Color; // 导入颜色类，用于UI显示
import java.lang.invoke.MethodHandle; // 导入方法句柄，用于反射调用
import java.lang.invoke.MethodHandles; // 导入方法句柄查找器，用于获取方法句柄
import java.lang.invoke.MethodType; // 导入方法类型，用于描述方法签名
import java.util.HashMap; // 导入哈希映射，用于存储系统配置
import java.util.HashSet; // 导入哈希集合，用于存储已知模组
import java.util.Map; // 导入映射接口，用于声明系统配置映射
import java.util.Set; // 导入集合接口，用于声明已知模组集合
import com.fs.starfarer.api.Global; // 导入全局类，用于访问游戏全局状态
import com.fs.starfarer.api.combat.BaseHullMod; // 导入基础船体模组类，作为本类的父类
import com.fs.starfarer.api.combat.MutableShipStatsAPI; // 导入可变船只统计API，用于修改船只属性
import com.fs.starfarer.api.combat.ShipAPI; // 导入船只API，用于操作船只对象
import com.fs.starfarer.api.combat.ShipHullSpecAPI; // 导入船体规格API，用于修改船体规格
import com.fs.starfarer.api.combat.ShipVariantAPI; // 导入船只变体API，用于操作船只变体
import com.fs.starfarer.api.ui.Alignment; // 导入对齐枚举，用于UI元素对齐
import com.fs.starfarer.api.ui.TooltipMakerAPI; // 导入提示制作器API，用于创建提示UI
import com.fs.starfarer.api.util.Misc; // 导入杂项工具类，提供各种实用方法
import data.scripts.util.Moci_TextLoader;

/**
 * 飞龙系统切换器
 * 这个类负责管理飞龙级战舰的战术系统切换功能
 * 继承自BaseHullMod，实现了船体模组的基本功能
 */
public class Moci_Pa_HiryuSystemSwitcher extends BaseHullMod { // 定义飞龙系统切换器类，继承自BaseHullMod
  private static final String TEXT_ID = "Moci_conditional_system_change";
  private static final String GLOBAL_DATA_KEY = "moci_hiryu_system_data"; // 定义全局数据键，用于在游戏全局数据中存储飞龙系统数据
  private static final Map<String, SystemConfig> SYSTEM_CONFIGS = new HashMap<>(); // 定义系统配置映射，用于存储不同系统的配置信息

  private static String text(String key) {
    return Moci_TextLoader.getText(TEXT_ID, key);
  }

  static { // 静态初始化块，在类加载时执行一次
    SYSTEM_CONFIGS.put( // 向系统配置映射中添加高能聚焦系统的配置
        "Moci_hiryu_system_highenergy", // 键：高能聚焦系统的模组ID
        new SystemConfig("highenergyfocus", "Moci_hiryu_system_recall",
            text("description.config_highenergy_name"),
            text("description.config_highenergy_desc"))); // 值：创建新的系统配置对象
    SYSTEM_CONFIGS.put( // 向系统配置映射中添加召回指令系统的配置
        "Moci_hiryu_system_recall", // 键：召回指令系统的模组ID
        new SystemConfig("recalldevice", "Moci_hiryu_system_plasma",
            text("description.config_recall_name"),
            text("description.config_recall_desc"))); // 值：创建新的系统配置对象
    SYSTEM_CONFIGS.put( // 向系统配置映射中添加等离子推进器系统的配置
        "Moci_hiryu_system_plasma", // 键：等离子推进器系统的模组ID
        new SystemConfig("plasmajets", "Moci_hiryu_system_highenergy",
            text("description.config_plasma_name"),
            text("description.config_plasma_desc"))); // 值：创建新的系统配置对象
  } // 静态初始化块结束

  /**
   * 系统配置类
   * 存储每种战术系统的配置信息，包括系统ID、下一个切换的模组ID、显示名称和描述
   */
  public static class SystemConfig { // 定义系统配置类，作为静态内部类
    public final String systemId; // 系统ID，用于识别战术系统
    public final String nextHullmodId; // 下一个模组ID，用于确定切换到哪个系统
    public final String displayName; // 显示名称，用于UI显示
    public final String description; // 描述，用于提供系统的详细信息

    /**
     * 系统配置构造函数
     * @param systemId 系统ID
     * @param nextHullmodId 下一个模组ID
     * @param displayName 显示名称
     * @param description 描述
     */
    public SystemConfig( // 构造函数，用于创建系统配置对象
        String systemId, // 参数：系统ID
        String nextHullmodId, // 参数：下一个模组ID
        String displayName, // 参数：显示名称
        String description) { // 参数：描述
      this.systemId = systemId; // 初始化系统ID字段
      this.nextHullmodId = nextHullmodId; // 初始化下一个模组ID字段
      this.displayName = displayName; // 初始化显示名称字段
      this.description = description; // 初始化描述字段
    } // 构造函数结束
  } // SystemConfig类结束

  /**
   * 船只系统数据类
   * 存储每艘飞龙级战舰的系统状态数据
   */
  public static class ShipSystemData { // 定义船只系统数据类，作为静态内部类
    public String permanentId; // 永久ID，用于唯一标识一艘船
    public Set<String> lastKnownMods; // 上次已知的模组集合，用于跟踪模组变化
    public String currentSystemId; // 当前系统ID，记录当前使用的战术系统
    public boolean isInitialized; // 初始化标志，表示是否已初始化

    /**
     * 船只系统数据构造函数
     * @param permanentId 永久ID
     */
    public ShipSystemData(String permanentId) { // 构造函数，用于创建船只系统数据对象
      this.permanentId = permanentId; // 初始化永久ID字段
      this.lastKnownMods = new HashSet<>(); // 初始化上次已知的模组集合为空集合
      this.currentSystemId = null; // 初始化当前系统ID为null
      this.isInitialized = false; // 初始化初始化标志为false
    } // 构造函数结束
  } // ShipSystemData类结束

  /**
   * 获取全局系统数据
   * 从游戏全局持久数据中获取飞龙系统数据映射，如果不存在则创建
   * @return 飞龙系统数据映射
   */
  @SuppressWarnings("unchecked") // 抑制未检查的类型转换警告
  private static Map<String, ShipSystemData> getGlobalSystemData() { // 定义获取全局系统数据的静态方法
    Map<String, Object> globalData = Global.getSector().getPersistentData(); // 获取游戏全局持久数据
    Map<String, ShipSystemData> systemData = // 从全局数据中获取飞龙系统数据
        (Map<String, ShipSystemData>) globalData.get(GLOBAL_DATA_KEY); // 使用全局数据键获取并强制类型转换
    if (systemData == null) { // 如果系统数据不存在
      systemData = new HashMap<>(); // 创建新的系统数据映射
      globalData.put(GLOBAL_DATA_KEY, systemData); // 将新创建的系统数据映射存入全局数据
    } // if语句结束
    return systemData; // 返回系统数据映射
  } // getGlobalSystemData方法结束

  /**
   * 获取船只永久ID（从变体）
   * 尝试从船只变体获取永久ID，优先使用舰队成员ID，如果不存在则使用变体ID和哈希值
   * @param variant 船只变体
   * @return 船只的永久ID
   */
  private String getShipPermanentId(ShipVariantAPI variant) { // 定义获取船只永久ID的方法
    if (variant == null) return null;
    
    // 获取真正的船体ID（去掉_default_D后缀）
    String hullId = variant.getHullSpec().getHullId();
    String trueHullId = hullId.endsWith("_default_D") ? hullId.substring(0, hullId.length() - "_default_D".length()) : hullId;
    
    // 使用真正的船体ID创建哈希值
    int variantHash = variant.toString().hashCode();
    
    // 尝试获取舰队成员ID
    MutableShipStatsAPI stats = variant.getStatsForOpCosts();
    if (stats != null && stats.getFleetMember() != null) {
      String fleetMemberId = stats.getFleetMember().getId();
      if (fleetMemberId != null && !fleetMemberId.isEmpty()) {
        return "hiryu_" + fleetMemberId; // 返回以"hiryu_"为前缀的舰队成员ID
      }
    }
    
    // 否则使用哈希值创建永久ID
    String stableId = "hiryu_" + trueHullId + "_" + Integer.toHexString(variantHash); // 组合基础ID和哈希码的十六进制表示形成稳定ID
    return stableId; // 返回稳定ID
  }

  /**
   * 获取船只永久ID（从船只对象）
   * 这个方法尝试从船只对象获取永久ID，用于唯一标识一艘船
   * 永久ID用于在全局数据中查找和存储船只的系统状态
   * 首先尝试使用舰队成员ID，如果不存在则使用船体ID和哈希值
   * 
   * @param ship 船只对象，包含了船只的各种信息
   * @return 船只的永久ID，一个唯一标识船只的字符串
   */
  private String getShipPermanentIdFromShip(ShipAPI ship) {
    if (ship == null) return null;
    
    // 获取真正的船体ID（去掉_default_D后缀）
    String hullId = ship.getHullSpec().getHullId();
    String trueHullId = hullId.endsWith("_default_D") ? hullId.substring(0, hullId.length() - "_default_D".length()) : hullId;
    
    // 尝试获取舰队成员ID
    if (ship.getFleetMember() != null) {
      String fleetMemberId = ship.getFleetMember().getId();
      if (fleetMemberId != null && !fleetMemberId.isEmpty()) {
        return "hiryu_" + fleetMemberId; // 返回以"hiryu_"为前缀的舰队成员ID，这是一个字符串
      }
    }
    
    // 否则使用哈希值创建永久ID
    int shipHash = ship.toString().hashCode();
    String stableId = "hiryu_" + trueHullId + "_" + Integer.toHexString(shipHash); // 组合基础ID和哈希码的十六进制表示形成稳定ID，这是一个字符串
    return stableId; // 返回稳定ID
  }

  /**
   * 在船只创建前应用效果
   * 这个方法会在游戏创建船只之前被调用，用于修改船只的属性和配置
   * 在这个方法中，我们会处理飞龙级战舰的系统切换逻辑
   * 当玩家手动移除某个系统切换插件时，我们会自动添加下一个系统切换插件
   * 这样可以实现在三种战术系统之间循环切换的功能
   * 
   * @param hullSize 船体大小，表示船只的尺寸类别
   * @param stats 可变船只统计API，用于修改船只属性
   * @param id 模组ID，表示当前正在应用的模组
   */
  @Override // 这是一个注解，告诉Java编译器这个方法是重写父类BaseHullMod的方法。重写意味着我们在子类中提供了父类方法的新实现。
  public void applyEffectsBeforeShipCreation(
      ShipAPI.HullSize hullSize, // 这是第一个参数，类型是ShipAPI.HullSize，名称是hullSize，它表示船只的尺寸类别，例如小型、中型、大型等
      MutableShipStatsAPI stats, // 这是第二个参数，类型是MutableShipStatsAPI，名称是stats，它是一个可以修改的船只统计对象，用于改变船只的各种属性
      String id) { // 这是第三个参数，类型是String，名称是id，它表示当前正在应用的模组的ID
    ShipVariantAPI variant = stats.getVariant(); // 这行代码获取船只的变体，并将其存储在variant变量中。变体包含了船只的配置信息，如装备的武器、插件等。
    if (variant == null) return;
    
    String hullId = variant.getHullSpec().getHullId();
    // 检查是否是飞龙级战舰或损坏的飞龙级战舰
    if (!"Moci_Hiryu".equals(hullId) && !"Moci_Hiryu_default_D".equals(hullId)) return;
    
    // 获取船只的永久ID和系统数据
    String permanentId = getShipPermanentId(variant);
    Map<String, ShipSystemData> globalData = getGlobalSystemData();
    ShipSystemData shipData = globalData.get(permanentId);
    
    // 如果没有找到船只数据，创建新的数据
    if (shipData == null) {
      shipData = new ShipSystemData(permanentId);
      globalData.put(permanentId, shipData);
    }
    
    // 检查当前安装的系统切换插件
    String currentSystemMod = null;
    Set<String> currentMods = new HashSet<>();
    
    // 遍历所有系统配置，检查哪些插件已安装
    for (String modId : SYSTEM_CONFIGS.keySet()) {
      if (variant.hasHullMod(modId)) {
        currentSystemMod = modId;
        currentMods.add(modId);
      }
    }
    
    // 检查是否有插件被移除
    Set<String> removedMods = new HashSet<>();
    
    // 如果船只数据已初始化，检查哪些插件被移除了
    if (shipData.isInitialized) {
      for (String modId : shipData.lastKnownMods) {
        if (!currentMods.contains(modId)) {
          removedMods.add(modId);
        }
      }
    } else {
      // 如果船只数据未初始化，标记为已初始化，并检查是否需要添加默认系统
      shipData.isInitialized = true;
      if (currentSystemMod == null) {
        // 如果没有安装任何系统切换插件，添加默认的高能聚焦系统
        variant.addMod("Moci_hiryu_system_highenergy");
        currentSystemMod = "Moci_hiryu_system_highenergy";
        currentMods.add(currentSystemMod);
      }
    }
    
    // 处理被移除的插件
    if (!removedMods.isEmpty()) {
      for (String removedModId : removedMods) {
        SystemConfig config = SYSTEM_CONFIGS.get(removedModId);
        if (config != null && config.nextHullmodId != null) {
          // 如果找到了被移除插件的配置，并且有下一个插件ID，则添加下一个插件
          variant.addMod(config.nextHullmodId);
          currentMods.add(config.nextHullmodId);
          SystemConfig nextConfig = SYSTEM_CONFIGS.get(config.nextHullmodId);
          if (nextConfig != null) {
            // 如果找到了下一个插件的配置，设置当前系统ID并尝试设置战术系统
            shipData.currentSystemId = nextConfig.systemId;
            setShipSystemSafely(variant, nextConfig.systemId);
          }
          break;
        }
      }
    }
    
    // 确保当前系统ID与当前安装的插件匹配
    if (currentSystemMod != null) {
      SystemConfig config = SYSTEM_CONFIGS.get(currentSystemMod);
      if (config != null) {
        // 如果找到了当前插件的配置，设置当前系统ID并检查战术系统是否需要更新
        shipData.currentSystemId = config.systemId;
        String currentSystemId = variant.getHullSpec().getShipSystemId();
        if (!config.systemId.equals(currentSystemId)) {
          // 如果当前战术系统与应该使用的系统不同，更新战术系统
          setShipSystemSafely(variant, config.systemId);
        }
      }
    }
    
    // 更新上次已知的模组集合
    shipData.lastKnownMods = new HashSet<>(currentMods);
  } // 这个大括号表示applyEffectsBeforeShipCreation方法的结束。在Java中，方法的内容被大括号{}包围。

  /**
   * 克隆船体规格
   * 这个方法使用Java的反射机制来克隆船体规格对象
   * 由于游戏API没有直接提供克隆船体规格的方法，我们需要使用高级技术来实现这个功能
   * 这里使用了MethodHandles和MethodHandle来安全地调用对象的clone方法
   * 
   * @param originalSpec 原始的船体规格对象，需要被克隆
   * @return 克隆后的船体规格对象，如果克隆失败则返回null
   */
  private static ShipHullSpecAPI cloneHullSpec(ShipHullSpecAPI originalSpec) { // 这是方法声明的开始，private表示这个方法只能在当前类内部访问，static表示这是一个静态方法（不需要实例化对象就可以调用），ShipHullSpecAPI是返回类型，cloneHullSpec是方法名，ShipHullSpecAPI originalSpec是参数
    try { // 这是try语句的开始，用于捕获可能发生的异常。在Java中，可能抛出异常的代码应该放在try块中，这样可以在发生异常时进行处理，避免程序崩溃。
      MethodHandles.Lookup lookup = MethodHandles.lookup(); // 这行代码创建了一个MethodHandles.Lookup对象，它是用来查找方法句柄的工具。MethodHandles是Java反射API的一部分，用于动态调用方法。
      Class<?> hullSpecClass = originalSpec.getClass(); // 这行代码获取原始船体规格对象的类，并将其存储在hullSpecClass变量中。getClass()是Object类的方法，返回对象的运行时类。Class<?>中的?表示这是一个未知类型的Class对象。
      MethodHandle cloneMethod = // 这行代码声明了一个MethodHandle变量，用于存储clone方法的句柄。MethodHandle是Java反射API的一部分，表示一个可以被调用的方法。
          lookup.findVirtual(hullSpecClass, "clone", MethodType.methodType(Object.class)); // 这行代码使用lookup对象查找hullSpecClass类中名为"clone"的虚方法（实例方法），该方法的返回类型是Object。findVirtual方法用于查找实例方法，第一个参数是方法所在的类，第二个参数是方法名，第三个参数是方法类型。
      return (ShipHullSpecAPI) cloneMethod.invoke(originalSpec); // 这行代码调用找到的clone方法，传入originalSpec作为this参数（即调用originalSpec.clone()），然后将结果强制转换为ShipHullSpecAPI类型并返回。invoke方法用于调用方法句柄表示的方法。
    } catch (Throwable t) { // 这是catch语句，用于捕获try块中可能抛出的任何Throwable对象（包括Error和Exception）。t是Throwable对象，包含了异常的详细信息。
      return null; // 这行代码返回null，表示克隆失败。return语句会立即结束方法的执行，并将指定的值返回给调用者。
    } // 这个大括号表示catch语句的结束。
  } // 这个大括号表示cloneHullSpec方法的结束。在Java中，方法的内容被大括号{}包围。

  /**
   * 安全地设置船只的战术系统
   * 这个方法尝试以安全的方式为船只设置新的战术系统
   * 为了避免直接修改原始船体规格可能导致的问题，方法会先克隆船体规格，然后修改克隆版本
   * 这样可以避免影响其他使用相同船体规格的船只
   * 
   * @param variant 船只变体，包含了船只的配置信息
   * @param systemId 要设置的战术系统ID
   * @return 一个布尔值，true表示设置成功，false表示设置失败
   */
  private boolean setShipSystemSafely(ShipVariantAPI variant, String systemId) { // 这是方法声明的开始，private表示这个方法只能在当前类内部访问，boolean表示这个方法返回一个布尔值，setShipSystemSafely是方法名，ShipVariantAPI variant和String systemId是参数
    try { // 这是try语句的开始，用于捕获可能发生的异常。在Java中，可能抛出异常的代码应该放在try块中，这样可以在发生异常时进行处理，避免程序崩溃。
      ShipHullSpecAPI originalSpec = variant.getHullSpec(); // 这行代码获取变体的原始船体规格，并将其存储在originalSpec变量中。船体规格包含了船体的基本属性，如大小、装甲、战术系统等。
      ShipHullSpecAPI clonedSpec = cloneHullSpec(originalSpec); // 这行代码调用cloneHullSpec方法克隆原始船体规格，并将克隆结果存储在clonedSpec变量中。克隆是为了避免修改原始规格可能导致的问题。
      if (clonedSpec == null) { // 这行代码检查克隆是否失败，即clonedSpec是否为null。如果克隆失败，则执行大括号内的代码。
        return false; // 这行代码返回false，表示设置战术系统失败。return语句会立即结束方法的执行，并将指定的值返回给调用者。
      } // 这个大括号表示if语句的结束。
      clonedSpec.setShipSystemId(systemId); // 这行代码设置克隆规格的战术系统ID为传入的systemId参数。setShipSystemId方法用于设置船体规格的战术系统ID。
      variant.setHullSpecAPI(clonedSpec); // 这行代码将变体的船体规格设置为克隆规格。setHullSpecAPI方法用于设置变体的船体规格。
      return true; // 这行代码返回true，表示设置战术系统成功。return语句会立即结束方法的执行，并将指定的值返回给调用者。
    } catch (Exception e) { // 这是catch语句，用于捕获try块中可能抛出的Exception类型的异常。e是异常对象，包含了异常的详细信息。
      return false; // 这行代码返回false，表示设置战术系统失败。return语句会立即结束方法的执行，并将指定的值返回给调用者。
    } // 这个大括号表示catch语句的结束。
  } // 这个大括号表示setShipSystemSafely方法的结束。在Java中，方法的内容被大括号{}包围。

  /**
   * 在船只创建后应用效果
   * 这个方法会在游戏创建船只后被调用，用于应用需要在船只对象存在后才能应用的效果
   * 在这个方法中，我们会检查并确保飞龙级战舰有正确的战术系统
   * 如果发现飞龙级战舰没有系统切换插件，会自动添加默认的高能聚焦系统
   * 
   * @param ship 船只对象，表示刚创建的船只
   * @param id 模组ID，表示当前正在应用的模组
   */
  @Override // 这是一个注解，告诉Java编译器这个方法是重写父类BaseHullMod的方法。重写意味着我们在子类中提供了父类方法的新实现。
  public void applyEffectsAfterShipCreation(ShipAPI ship, String id) { // 这是方法声明的开始，public表示这个方法可以被任何类访问，void表示这个方法不返回任何值，applyEffectsAfterShipCreation是方法名，ShipAPI ship和String id是参数
    // 检查船只是否为飞龙级战舰
    if (ship == null) return;
    
    String hullId = ship.getHullSpec().getHullId();
    // 检查是否是飞龙级战舰或损坏的飞龙级战舰
    if (!"Moci_Hiryu".equals(hullId) && !"Moci_Hiryu_default_D".equals(hullId)) return;
    
    // 获取船只的永久ID和系统数据
    String permanentId = getShipPermanentIdFromShip(ship);
    Map<String, ShipSystemData> globalData = getGlobalSystemData();
    ShipSystemData shipData = globalData.get(permanentId);
    
    // 检查当前舰船是否有任何系统切换插件
      String currentSystemMod = null;
      for (String modId : SYSTEM_CONFIGS.keySet()) {
        if (ship.getVariant().hasHullMod(modId)) {
          currentSystemMod = modId;
          break;
        }
      }
    
    // 如果没有系统切换插件，无论isInitialized如何，都添加默认的高能聚焦系统
    if (currentSystemMod == null) {
      ship.getVariant().addMod("Moci_hiryu_system_highenergy");
      
      // 如果shipData为null，创建新的数据
      if (shipData == null) {
        shipData = new ShipSystemData(permanentId);
        globalData.put(permanentId, shipData);
      }
      
      shipData.currentSystemId = "highenergyfocus";
      shipData.isInitialized = true;
    }
    // 如果有系统切换插件但没有shipData，创建新的数据
    else if (shipData == null) {
      shipData = new ShipSystemData(permanentId);
      globalData.put(permanentId, shipData);
      
        SystemConfig config = SYSTEM_CONFIGS.get(currentSystemMod);
        if (config != null) {
          shipData.currentSystemId = config.systemId;
      }
      shipData.isInitialized = true;
    }
    
    // 设置战术系统
    if (shipData.currentSystemId != null) {
      String currentSystemId = ship.getSystem() != null ? ship.getSystem().getId() : "null";
      if (!shipData.currentSystemId.equals(currentSystemId)) {
        setShipSystemSafely(ship.getVariant(), shipData.currentSystemId);
      } else {
        ship.setCustomData("moci_hiryu_system_id", shipData.currentSystemId);
      }
    }
  } // 这个大括号表示applyEffectsAfterShipCreation方法的结束。在Java中，方法的内容被大括号{}包围。

  /**
   * 添加描述后的部分
   * 这个方法会在插件的描述后面添加额外的信息，向玩家展示插件的功能
   * 它会被游戏自动调用，用于生成插件的提示信息
   * 这里我们会添加关于飞龙战术系统切换功能的详细说明
   * 
   * @param tooltip 提示制作器，用于创建和修改提示UI
   * @param hullSize 船体大小，表示船只的尺寸类别
   * @param ship 船只对象，表示当前查看插件的船只
   * @param width 宽度，表示提示UI的宽度
   * @param isForModSpec 是否为模组规格，表示是否在模组信息中显示
   */
  @Override // 这是一个注解，告诉Java编译器这个方法是重写父类的方法。重写意味着我们在子类中提供了父类方法的新实现。
  public void addPostDescriptionSection( // 这是方法声明的开始，public表示这个方法可以被任何类访问，void表示这个方法不返回任何值，addPostDescriptionSection是方法名
      TooltipMakerAPI tooltip, // 这是第一个参数，类型是TooltipMakerAPI，名称是tooltip，它是一个用于创建提示UI的工具
      ShipAPI.HullSize hullSize, // 这是第二个参数，类型是ShipAPI.HullSize，名称是hullSize，它表示船只的尺寸类别，例如小型、中型、大型等
      ShipAPI ship, // 这是第三个参数，类型是ShipAPI，名称是ship，它表示当前查看插件的船只
      float width, // 这是第四个参数，类型是float（浮点数），名称是width，它表示提示UI的宽度，单位是像素
      boolean isForModSpec) { // 这是第五个参数，类型是boolean（布尔值），名称是isForModSpec，它表示是否在模组信息中显示
    float pad = 10f; // 这行代码声明了一个名为pad的浮点数变量，并将其初始化为10。pad表示内边距，用于控制UI元素之间的间距。
    float opad = 5f; // 这行代码声明了一个名为opad的浮点数变量，并将其初始化为5。opad表示外边距，通常用于控制段落之间的间距。
    Color h = Misc.getHighlightColor(); // 这行代码声明了一个名为h的Color变量，并使用Misc工具类的getHighlightColor方法获取高亮颜色。Color是表示颜色的类。
    Color g = Misc.getPositiveHighlightColor(); // 这行代码声明了一个名为g的Color变量，并使用Misc工具类的getPositiveHighlightColor方法获取积极高亮颜色（通常是绿色）。
    tooltip.addSectionHeading(text("description.title"), Alignment.MID, opad); // 这行代码向提示中添加了一个居中对齐的标题"飞龙战术系统切换器"，使用了之前定义的opad作为边距。
    tooltip.addPara(text("description.intro"), pad); // 这行代码向提示中添加了一个段落，内容是"允许飞龙在三种战术系统间切换："，使用了之前定义的pad作为边距。
    tooltip.addPara(text("description.line_highenergy"), pad, h,
        Moci_TextLoader.getHighlights(TEXT_ID, "description.line_highenergy_highlights").toArray(new String[0])); // 这行代码向提示中添加了一个段落，描述高能聚焦系统。%s是一个占位符，会被后面的参数"高能聚焦"替换，并使用h颜色高亮显示。
    tooltip.addPara(text("description.line_recall"), pad, h,
        Moci_TextLoader.getHighlights(TEXT_ID, "description.line_recall_highlights").toArray(new String[0])); // 这行代码向提示中添加了一个段落，描述召回指令系统。%s是一个占位符，会被后面的参数"召回指令"替换，并使用h颜色高亮显示。
    tooltip.addPara(text("description.line_plasma"), pad, h,
        Moci_TextLoader.getHighlights(TEXT_ID, "description.line_plasma_highlights").toArray(new String[0])); // 这行代码向提示中添加了一个段落，描述等离子推进器系统。%s是一个占位符，会被后面的参数"等离子推进器"替换，并使用h颜色高亮显示。
  } // 这个大括号表示addPostDescriptionSection方法的结束。在Java中，方法的内容被大括号{}包围。

  /**
   * 判断插件是否适用于指定船只
   * 这个方法用于检查当前插件是否可以安装在指定的船只上
   * 在游戏中，当玩家尝试将插件安装到船只上时，游戏会调用这个方法进行检查
   * 如果方法返回true，则允许安装；如果返回false，则禁止安装
   * 在这个实现中，我们只允许将插件安装在飞龙级战舰上
   * 
   * @param ship 要检查的船只对象，包含了船只的各种信息
   * @return 一个布尔值，true表示可以安装，false表示不可安装
   */
  @Override // 这是一个注解，告诉Java编译器这个方法是重写父类BaseHullMod的方法。重写意味着我们在子类中提供了父类方法的新实现。
  public boolean isApplicableToShip(ShipAPI ship) { // 这是方法声明的开始，public表示这个方法可以被任何类访问，boolean表示这个方法返回一个布尔值（真或假），isApplicableToShip是方法名，ShipAPI ship是参数，表示要检查的船只
    if (ship == null) return false;
    String hullId = ship.getHullSpec().getHullId();
    return "Moci_Hiryu".equals(hullId) || "Moci_Hiryu_default_D".equals(hullId);
  } // 这个大括号表示isApplicableToShip方法的结束。在Java中，方法的内容被大括号{}包围。

  /**
   * 获取插件不适用的原因
   * 当插件不能安装在指定船只上时，这个方法会返回一个解释原因的文本
   * 这个文本会显示在游戏界面上，告诉玩家为什么不能安装此插件
   * 如果插件可以安装，则返回null（表示没有不适用的原因）
   * 
   * @param ship 要检查的船只对象，包含了船只的各种信息
   * @return 一个字符串，解释插件不能安装的原因；如果可以安装则返回null
   */
  @Override // 这是一个注解，告诉Java编译器这个方法是重写父类BaseHullMod的方法。重写意味着我们在子类中提供了父类方法的新实现。
  public String getUnapplicableReason(ShipAPI ship) { // 这是方法声明的开始，public表示这个方法可以被任何类访问，String表示这个方法返回一个字符串，getUnapplicableReason是方法名，ShipAPI ship是参数，表示要检查的船只
    if (ship == null) return null; // 这行代码检查ship是否为null（空）。如果是null，则返回null。在Java中，null是一个特殊值，表示"没有对象"。这里返回null意味着"没有不适用的原因"或"不适用的原因未知"。
    if (!"Moci_Hiryu".equals(ship.getHullSpec().getHullId()) && !"Moci_Hiryu_default_D".equals(ship.getHullSpec().getHullId())) { // 这行代码检查船只的船体ID是否不等于"Moci_Hiryu"。!是逻辑非运算符，表示对后面的条件取反。如果船体ID不是"Moci_Hiryu"，则执行大括号内的代码。
      return text("description.unapplicable_reason"); // 如果船只不是飞龙级战舰，则返回这个字符串，解释不能安装的原因。
    } // 这个大括号表示if语句的结束。
    return null; // 如果前面的条件都不满足（即船只是飞龙级战舰），则返回null，表示没有不适用的原因，可以安装。
  } // 这个大括号表示getUnapplicableReason方法的结束。在Java中，方法的内容被大括号{}包围。

}
