package data.hullmods;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;

/**
 * 轮替插件所挂载的代码 通过插件id来决定生效的功能
 * 填错id的话没有效果
 * 
 * 新增功能：
 * 1. 自动清理被轮替武器，防止仓库垃圾积累
 * 2. 固定船插显示顺序，防止界面跳跃导致误操作
 */
public class Moci_WeaponSwitchHullMod2 extends BaseHullMod {

    public static final Map<String, hullModComfig> HULL_MOD_CONFIGS = new HashMap<>();

    static {
        /*
         * // 示例轮换插件组（保留用于参考）
         * hullModComfig config = new hullModComfig("轮换插件1");
         * HULL_MOD_CONFIGS.put("轮换插件1",config);
         * //冲突插件 不是用来给循环内部做插件互斥的，而是外部对当前插件的兼容性，比如，10个插件循环中，第二插件不兼容安超，当安装安超的时候，跳过第二插件
         * //当循环的第一个插件就因为冲突而无法安装的时候，整个循环将无法应用于当前飞船
         * //config.conflictHullMods.add("冲突的插件");
         * //插件允许安装的船体id 该合集为空的时候能够安装在任意舰船，不为空只能安装在指定舰船上
         * // config.hulls.add("");
         * //替换武器 一个循环中的所有插件都应该管理同样数量的武器槽
         * config.replaceSlots.put("WS 001",""); //清空武器槽
         * config.replaceSlots.put("WS 002","lightmg"); //替换武器
         * //当前插件被移除的话，会自动安装的下一个插件
         * config.nextHullMod="轮换插件2";
         * //整组循环插件的第一个插件，当什么插件都没装的时候，sourceHullMod会被自动安装，除非安装条件判定(船体id，冲突插件)不通过
         * config.sourceHullMod="轮换插件1";
         * 
         * config = new hullModComfig("轮换插件2");
         * HULL_MOD_CONFIGS.put("轮换插件2",config);
         * //config.conflictHullMods.add("冲突的插件");
         * //config.hulls.add("");
         * config.replaceSlots.put("WS 001","lightmg");
         * config.replaceSlots.put("WS 002","lightac");
         * config.nextHullMod="轮换插件3";
         * config.sourceHullMod="轮换插件1";
         * 
         * config = new hullModComfig("轮换插件3");
         * HULL_MOD_CONFIGS.put("轮换插件3",config);
         * //config.conflictHullMods.add("冲突的插件");
         * //config.hulls.add("");
         * config.replaceSlots.put("WS 001","lightac");
         * config.replaceSlots.put("WS 002","");
         * config.nextHullMod="轮换插件1";
         * config.sourceHullMod="轮换插件1";
         */

        // MSN-04-2 夜莺武器切换系统配置 - 手臂武器轮替
        // 米加粒子枪模式（手臂武器源插件 - 默认安装）
        hullModComfig config = new hullModComfig("Moci_MSN_04_2_right_mode_rilfe");
        HULL_MOD_CONFIGS.put("Moci_MSN_04_2_right_mode_rilfe", config);
        // 限制只能安装在MSN-04-2舰船上
        config.hulls.add("Moci_MSN_04_2");
        // 武器槽配置：米加粒子枪模式（仅管理手臂武器）
        config.replaceSlots.put("ARM_R_SOURCE", "MSN_04_2_arm_right_rifle"); // 右臂装备米加粒子枪
        // 轮替设置
        config.nextHullMod = "Moci_MSN_04_2_right_mode_axe";
        config.sourceHullMod = "Moci_MSN_04_2_right_mode_rilfe";
        // 显示控制：MSN-04-2手臂武器组，显示顺序3000-3099
        config.displaySortOrder = 3000;
        config.displayCategoryIndex = 2; // 舰船系统类
        // 武器清理：管理手臂武器相关的武器ID
        config.managedWeaponPrefixes.add("MSN_04_2_arm_right");

        // 光束战斧模式（手臂武器）
        config = new hullModComfig("Moci_MSN_04_2_right_mode_axe");
        HULL_MOD_CONFIGS.put("Moci_MSN_04_2_right_mode_axe", config);
        // 限制只能安装在MSN-04-2舰船上
        config.hulls.add("Moci_MSN_04_2");
        // 武器槽配置：光束战斧模式（仅管理手臂武器）
        config.replaceSlots.put("ARM_R_SOURCE", "MSN_04_2_arm_right_Axe"); // 右臂装备光束战斧
        // 轮替设置
        config.nextHullMod = "Moci_MSN_04_2_right_mode_rilfe";
        config.sourceHullMod = "Moci_MSN_04_2_right_mode_rilfe";
        // 显示控制：MSN-04-2手臂武器组
        config.displaySortOrder = 3001;
        config.displayCategoryIndex = 2; // 舰船系统类
        // 武器清理：管理手臂武器相关的武器ID
        config.managedWeaponPrefixes.add("MSN_04_2_arm_right");

        // MSN-04-2 夜莺武器切换系统配置 - 身体武器轮替
        // 扩散式米加粒子炮（身体武器源插件 - 默认安装）
        config = new hullModComfig("Moci_MSN_04_2_right_mode_megacannon");
        HULL_MOD_CONFIGS.put("Moci_MSN_04_2_right_mode_megacannon", config);
        // 限制只能安装在MSN-04-2舰船上
        config.hulls.add("Moci_MSN_04_2");
        // 武器槽配置：扩散式米加粒子炮（仅管理身体武器）
        config.replaceSlots.put("BODY_WEAPON", "MSN_04_2_body_beam"); // 身体装备扩散式米加粒子炮
        // 轮替设置
        config.nextHullMod = "Moci_MSN_04_2_right_mode_warpbeam";
        config.sourceHullMod = "Moci_MSN_04_2_right_mode_megacannon";
        // 显示控制：MSN-04-2身体武器组，显示顺序3100-3199
        config.displaySortOrder = 3100;
        config.displayCategoryIndex = 2; // 舰船系统类
        // 武器清理：管理身体武器相关的武器ID
        config.managedWeaponPrefixes.add("MSN_04_2_body");

        // 接近战用牵引光束（身体武器）
        config = new hullModComfig("Moci_MSN_04_2_right_mode_warpbeam");
        HULL_MOD_CONFIGS.put("Moci_MSN_04_2_right_mode_warpbeam", config);
        // 限制只能安装在MSN-04-2舰船上
        config.hulls.add("Moci_MSN_04_2");
        // 武器槽配置：接近战用牵引光束（仅管理身体武器）
        config.replaceSlots.put("BODY_WEAPON", "MSN_04_2_body_beam_improved"); // 身体装备接近战用牵引光束
        // 轮替设置
        config.nextHullMod = "Moci_MSN_04_2_right_mode_megacannon";
        config.sourceHullMod = "Moci_MSN_04_2_right_mode_megacannon";
        // 显示控制：MSN-04-2身体武器组
        config.displaySortOrder = 3101;
        config.displayCategoryIndex = 2; // 舰船系统类
        // 武器清理：管理身体武器相关的武器ID
        config.managedWeaponPrefixes.add("MSN_04_2_body");

        // Moci_Gespenst 量产型亡灵武器切换系统配置
        // 左臂冲锋枪模式（左臂队列源插件 - 默认安装）
        config = new hullModComfig("Moci_Gespenst_left_mode_gun");
        HULL_MOD_CONFIGS.put("Moci_Gespenst_left_mode_gun", config);
        // 限制只能安装在Moci_Gespenst舰船上
        config.hulls.add("Moci_Gespenst");
        // 武器槽配置：左臂冲锋枪模式
        config.replaceSlots.put("ARM_L", "Moci_Gespenst_arm_left_gun"); // 左臂装备冲锋枪
        config.replaceSlots.put("ARM_L_SOURCE", "Moci_Gespenst_left_gun"); // 左臂源武器槽冲锋枪
        // 轮替设置
        config.nextHullMod = "Moci_Gespenst_left_mode_fist";
        config.sourceHullMod = "Moci_Gespenst_left_mode_gun";
        // 显示控制：Gespenst左臂武器组，显示顺序3200-3299
        config.displaySortOrder = 3200;
        config.displayCategoryIndex = 2; // 舰船系统类
        // 武器清理：管理Gespenst左臂武器相关的武器ID
        config.managedWeaponPrefixes.add("Moci_Gespenst_arm_left");
        config.managedWeaponPrefixes.add("Moci_Gespenst_left");

        // 左臂拳套模式
        config = new hullModComfig("Moci_Gespenst_left_mode_fist");
        HULL_MOD_CONFIGS.put("Moci_Gespenst_left_mode_fist", config);
        // 限制只能安装在Moci_Gespenst舰船上
        config.hulls.add("Moci_Gespenst");
        // 武器槽配置：左臂拳套模式
        config.replaceSlots.put("ARM_L", "Moci_Gespenst_arm_left_fist"); // 左臂装备拳套
        config.replaceSlots.put("ARM_L_SOURCE", "Moci_Gespenst_left_fist"); // 左臂源武器槽拳套
        // 轮替设置
        config.nextHullMod = "Moci_Gespenst_left_mode_gun";
        config.sourceHullMod = "Moci_Gespenst_left_mode_gun";
        // 显示控制：Gespenst左臂武器组
        config.displaySortOrder = 3201;
        config.displayCategoryIndex = 2; // 舰船系统类
        // 武器清理：管理Gespenst左臂武器相关的武器ID
        config.managedWeaponPrefixes.add("Moci_Gespenst_arm_left");
        config.managedWeaponPrefixes.add("Moci_Gespenst_left");

        // 右臂冲锋枪模式（右臂队列源插件 - 默认安装）
        config = new hullModComfig("Moci_Gespenst_right_mode_gun");
        HULL_MOD_CONFIGS.put("Moci_Gespenst_right_mode_gun", config);
        // 限制只能安装在Moci_Gespenst舰船上
        config.hulls.add("Moci_Gespenst");
        // 武器槽配置：右臂冲锋枪模式
        config.replaceSlots.put("ARM_R", "Moci_Gespenst_arm_right_gun"); // 右臂装备冲锋枪
        config.replaceSlots.put("ARM_R_SOURCE", "Moci_Gespenst_right_gun"); // 右臂源武器槽冲锋枪
        // 轮替设置
        config.nextHullMod = "Moci_Gespenst_right_mode_fist";
        config.sourceHullMod = "Moci_Gespenst_right_mode_gun";
        // 显示控制：Gespenst右臂武器组，显示顺序3300-3399
        config.displaySortOrder = 3300;
        config.displayCategoryIndex = 2; // 舰船系统类
        // 武器清理：管理Gespenst右臂武器相关的武器ID
        config.managedWeaponPrefixes.add("Moci_Gespenst_arm_right");
        config.managedWeaponPrefixes.add("Moci_Gespenst_right");

        // 右臂拳套模式
        config = new hullModComfig("Moci_Gespenst_right_mode_fist");
        HULL_MOD_CONFIGS.put("Moci_Gespenst_right_mode_fist", config);
        // 限制只能安装在Moci_Gespenst舰船上
        config.hulls.add("Moci_Gespenst");
        // 武器槽配置：右臂拳套模式
        config.replaceSlots.put("ARM_R", "Moci_Gespenst_arm_right_fist"); // 右臂装备拳套
        config.replaceSlots.put("ARM_R_SOURCE", "Moci_Gespenst_right_fist"); // 右臂源武器槽拳套
        // 轮替设置
        config.nextHullMod = "Moci_Gespenst_right_mode_gun";
        config.sourceHullMod = "Moci_Gespenst_right_mode_gun";
        // 显示控制：Gespenst右臂武器组
        config.displaySortOrder = 3301;
        config.displayCategoryIndex = 2; // 舰船系统类
        // 武器清理：管理Gespenst右臂武器相关的武器ID
        config.managedWeaponPrefixes.add("Moci_Gespenst_arm_right");
        config.managedWeaponPrefixes.add("Moci_Gespenst_right");

        // RX-93 v2系列武器切换系统配置
        // 光束步枪模式（RX-93 v2默认武器）
        config = new hullModComfig("Moci_RX_93_v2_WeaponSwap_rifle");
        HULL_MOD_CONFIGS.put("Moci_RX_93_v2_WeaponSwap_rifle", config);
        // 限制只能安装在RX-93 v2系列舰船上
        config.hulls.add("Moci_RX_93_v2");
        // 武器槽配置：光束步枪模式
        config.replaceSlots.put("ARM_R_SOURCE", "RX_93_v2_arm_right_gun_rifle");
        // 轮替设置
        config.nextHullMod = "Moci_RX_93_v2_WeaponSwap_bazooka";
        config.sourceHullMod = "Moci_RX_93_v2_WeaponSwap_rifle";
        // 显示控制：RX-93 v2武器组，显示顺序3400-3499
        config.displaySortOrder = 3400;
        config.displayCategoryIndex = 2; // 舰船系统类
        // 武器清理：管理RX-93 v2武器相关的武器ID
        config.managedWeaponPrefixes.add("RX_93_v2_arm_right");

        // 高能火箭炮模式
        config = new hullModComfig("Moci_RX_93_v2_WeaponSwap_bazooka");
        HULL_MOD_CONFIGS.put("Moci_RX_93_v2_WeaponSwap_bazooka", config);
        // 限制只能安装在RX-93 v2系列舰船上
        config.hulls.add("Moci_RX_93_v2");
        // 武器槽配置：高能火箭炮模式
        config.replaceSlots.put("ARM_R_SOURCE", "RX_93_v2_arm_right_gun_bazooka");
        // 轮替设置
        config.nextHullMod = "Moci_RX_93_v2_WeaponSwap_Sabre";
        config.sourceHullMod = "Moci_RX_93_v2_WeaponSwap_rifle";
        // 显示控制：RX-93 v2武器组
        config.displaySortOrder = 3401;
        config.displayCategoryIndex = 2; // 舰船系统类
        // 武器清理：管理RX-93 v2武器相关的武器ID
        config.managedWeaponPrefixes.add("RX_93_v2_arm_right");

        // 光束军刀模式
        config = new hullModComfig("Moci_RX_93_v2_WeaponSwap_Sabre");
        HULL_MOD_CONFIGS.put("Moci_RX_93_v2_WeaponSwap_Sabre", config);
        // 限制只能安装在RX-93 v2系列舰船上
        config.hulls.add("Moci_RX_93_v2");
        // 武器槽配置：光束军刀模式
        config.replaceSlots.put("ARM_R_SOURCE", "RX_93_v2_arm_right_gun_Sabre");
        // 轮替设置
        config.nextHullMod = "Moci_RX_93_v2_WeaponSwap_rifle";
        config.sourceHullMod = "Moci_RX_93_v2_WeaponSwap_rifle";
        // 显示控制：RX-93 v2武器组
        config.displaySortOrder = 3402;
        config.displayCategoryIndex = 2; // 舰船系统类
        // 武器清理：管理RX-93 v2武器相关的武器ID
        config.managedWeaponPrefixes.add("RX_93_v2_arm_right");

        // RX-78-2系列武器切换系统配置
        // 右手武器轮替组
        // 光束步枪模式（右手默认武器）
        config = new hullModComfig("Moci_RX_78_2_WeaponSwap_right_rifle");
        HULL_MOD_CONFIGS.put("Moci_RX_78_2_WeaponSwap_right_rifle", config);
        // 限制只能安装在RX-78-2系列舰船上
        config.hulls.add("Moci_RX_78_2");
        // 武器槽配置：右手光束步枪模式
        config.replaceSlots.put("ARM_R_SOURCE", "RX_78_2_arm_right_gun_rifle");
        // 轮替设置
        config.nextHullMod = "Moci_RX_78_2_WeaponSwap_right_bazooka";
        config.sourceHullMod = "Moci_RX_78_2_WeaponSwap_right_rifle";
        // 显示控制：RX-78-2右手武器组，显示顺序3500-3549
        config.displaySortOrder = 3500;
        config.displayCategoryIndex = 2; // 舰船系统类
        // 武器清理：管理RX-78-2右手武器相关的武器ID
        config.managedWeaponPrefixes.add("RX_78_2_arm_right");

        // 右手高能火箭炮模式
        config = new hullModComfig("Moci_RX_78_2_WeaponSwap_right_bazooka");
        HULL_MOD_CONFIGS.put("Moci_RX_78_2_WeaponSwap_right_bazooka", config);
        // 限制只能安装在RX-78-2系列舰船上
        config.hulls.add("Moci_RX_78_2");
        // 武器槽配置：右手高能火箭炮模式
        config.replaceSlots.put("ARM_R_SOURCE", "RX_78_2_arm_right_gun_bazooka");
        // 轮替设置
        config.nextHullMod = "Moci_RX_78_2_WeaponSwap_right_Sabre";
        config.sourceHullMod = "Moci_RX_78_2_WeaponSwap_right_rifle";
        // 显示控制：RX-78-2右手武器组
        config.displaySortOrder = 3501;
        config.displayCategoryIndex = 2; // 舰船系统类
        // 武器清理：管理RX-78-2右手武器相关的武器ID
        config.managedWeaponPrefixes.add("RX_78_2_arm_right");

        // 右手光束军刀模式
        config = new hullModComfig("Moci_RX_78_2_WeaponSwap_right_Sabre");
        HULL_MOD_CONFIGS.put("Moci_RX_78_2_WeaponSwap_right_Sabre", config);
        // 限制只能安装在RX-78-2系列舰船上
        config.hulls.add("Moci_RX_78_2");
        // 武器槽配置：右手光束军刀模式
        config.replaceSlots.put("ARM_R_SOURCE", "RX_78_2_arm_right_gun_Sabre");
        // 轮替设置
        config.nextHullMod = "Moci_RX_78_2_WeaponSwap_right_rifle";
        config.sourceHullMod = "Moci_RX_78_2_WeaponSwap_right_rifle";
        // 显示控制：RX-78-2右手武器组
        config.displaySortOrder = 3502;
        config.displayCategoryIndex = 2; // 舰船系统类
        // 武器清理：管理RX-78-2右手武器相关的武器ID
        config.managedWeaponPrefixes.add("RX_78_2_arm_right");

        // 左手武器轮替组
        // 高能火箭炮模式（左手默认武器）
        config = new hullModComfig("Moci_RX_78_2_WeaponSwap_left_bazooka");
        HULL_MOD_CONFIGS.put("Moci_RX_78_2_WeaponSwap_left_bazooka", config);
        // 限制只能安装在RX-78-2系列舰船上
        config.hulls.add("Moci_RX_78_2");
        // 武器槽配置：左手高能火箭炮模式
        config.replaceSlots.put("ARM_L_SOURCE", "RX_78_2_arm_left_gun_bazooka");
        // 轮替设置
        config.nextHullMod = "Moci_RX_78_2_WeaponSwap_left_Sabre";
        config.sourceHullMod = "Moci_RX_78_2_WeaponSwap_left_bazooka";
        // 显示控制：RX-78-2左手武器组，显示顺序3550-3599（位于右手武器下方）
        config.displaySortOrder = 3550;
        config.displayCategoryIndex = 2; // 舰船系统类
        // 武器清理：管理RX-78-2左手武器相关的武器ID
        config.managedWeaponPrefixes.add("RX_78_2_arm_left");

        // 左手光束军刀模式
        config = new hullModComfig("Moci_RX_78_2_WeaponSwap_left_Sabre");
        HULL_MOD_CONFIGS.put("Moci_RX_78_2_WeaponSwap_left_Sabre", config);
        // 限制只能安装在RX-78-2系列舰船上
        config.hulls.add("Moci_RX_78_2");
        // 武器槽配置：左手光束军刀模式
        config.replaceSlots.put("ARM_L_SOURCE", "RX_78_2_arm_left_gun_Sabre");
        // 轮替设置
        config.nextHullMod = "Moci_RX_78_2_WeaponSwap_left_bazooka";
        config.sourceHullMod = "Moci_RX_78_2_WeaponSwap_left_bazooka";
        // 显示控制：RX-78-2左手武器组
        config.displaySortOrder = 3551;
        config.displayCategoryIndex = 2; // 舰船系统类
        // 武器清理：管理RX-78-2左手武器相关的武器ID
        config.managedWeaponPrefixes.add("RX_78_2_arm_left");

        // RGM-89系列武器切换系统配置
        // 光束步枪模式（RGM-89默认武器）
        config = new hullModComfig("Moci_RGM_89_leader_WeaponSwap_right_rifle");
        HULL_MOD_CONFIGS.put("Moci_RGM_89_leader_WeaponSwap_right_rifle", config);
        // 限制只能安装在RGM-89系列舰船上
        config.hulls.add("Moci_RGM_89");
        // 武器槽配置：光束步枪模式
        config.replaceSlots.put("ARM_R_SOURCE", "RGM_89_arm_right_gun_rifle");
        // 轮替设置
        config.nextHullMod = "Moci_RGM_89_leader_WeaponSwap_right_bazooka";
        config.sourceHullMod = "Moci_RGM_89_leader_WeaponSwap_right_rifle";
        // 显示控制：RGM-89武器组，显示顺序3600-3699
        config.displaySortOrder = 3600;
        config.displayCategoryIndex = 2; // 舰船系统类
        // 武器清理：管理RGM-89武器相关的武器ID
        config.managedWeaponPrefixes.add("RGM_89_arm_right");

        // 高能火箭炮模式
        config = new hullModComfig("Moci_RGM_89_leader_WeaponSwap_right_bazooka");
        HULL_MOD_CONFIGS.put("Moci_RGM_89_leader_WeaponSwap_right_bazooka", config);
        // 限制只能安装在RGM-89系列舰船上
        config.hulls.add("Moci_RGM_89");
        // 武器槽配置：高能火箭炮模式
        config.replaceSlots.put("ARM_R_SOURCE", "RGM_89_arm_right_gun_bazooka");
        // 轮替设置
        config.nextHullMod = "Moci_RGM_89_leader_WeaponSwap_right_Sabre";
        config.sourceHullMod = "Moci_RGM_89_leader_WeaponSwap_right_rifle";
        // 显示控制：RGM-89武器组
        config.displaySortOrder = 3601;
        config.displayCategoryIndex = 2; // 舰船系统类
        // 武器清理：管理RGM-89武器相关的武器ID
        config.managedWeaponPrefixes.add("RGM_89_arm_right");

        // 光束军刀模式
        config = new hullModComfig("Moci_RGM_89_leader_WeaponSwap_right_Sabre");
        HULL_MOD_CONFIGS.put("Moci_RGM_89_leader_WeaponSwap_right_Sabre", config);
        // 限制只能安装在RGM-89系列舰船上
        config.hulls.add("Moci_RGM_89");
        // 武器槽配置：光束军刀模式
        config.replaceSlots.put("ARM_R_SOURCE", "RGM_89_arm_right_gun_Sabre");
        // 轮替设置
        config.nextHullMod = "Moci_RGM_89_leader_WeaponSwap_right_rifle";
        config.sourceHullMod = "Moci_RGM_89_leader_WeaponSwap_right_rifle";
        // 显示控制：RGM-89武器组
        config.displaySortOrder = 3602;
        config.displayCategoryIndex = 2; // 舰船系统类
        // 武器清理：管理RGM-89武器相关的武器ID
        config.managedWeaponPrefixes.add("RGM_89_arm_right");

        // RX-93系列武器切换系统配置
        // 光束步枪模式（RX-93默认武器）
        config = new hullModComfig("Moci_RX_93_WeaponSwap_rifle");
        HULL_MOD_CONFIGS.put("Moci_RX_93_WeaponSwap_rifle", config);
        // 限制只能安装在RX-93系列舰船上
        config.hulls.add("Moci_RX_93");
        // 武器槽配置：光束步枪模式
        config.replaceSlots.put("ARM_R_SOURCE", "RX_93_arm_right_gun_rifle");
        // 轮替设置
        config.nextHullMod = "Moci_RX_93_WeaponSwap_bazooka";
        config.sourceHullMod = "Moci_RX_93_WeaponSwap_rifle";
        // 显示控制：RX-93武器组，显示顺序3800-3899
        config.displaySortOrder = 3800;
        config.displayCategoryIndex = 2; // 舰船系统类
        // 武器清理：管理RX-93武器相关的武器ID
        config.managedWeaponPrefixes.add("RX_93_arm_right");

        // 高能火箭炮模式
        config = new hullModComfig("Moci_RX_93_WeaponSwap_bazooka");
        HULL_MOD_CONFIGS.put("Moci_RX_93_WeaponSwap_bazooka", config);
        // 限制只能安装在RX-93系列舰船上
        config.hulls.add("Moci_RX_93");
        // 武器槽配置：高能火箭炮模式
        config.replaceSlots.put("ARM_R_SOURCE", "RX_93_arm_right_gun_bazooka");
        // 轮替设置
        config.nextHullMod = "Moci_RX_93_WeaponSwap_Sabre";
        config.sourceHullMod = "Moci_RX_93_WeaponSwap_rifle";
        // 显示控制：RX-93武器组
        config.displaySortOrder = 3801;
        config.displayCategoryIndex = 2; // 舰船系统类
        // 武器清理：管理RX-93武器相关的武器ID
        config.managedWeaponPrefixes.add("RX_93_arm_right");

        // 光束军刀模式
        config = new hullModComfig("Moci_RX_93_WeaponSwap_Sabre");
        HULL_MOD_CONFIGS.put("Moci_RX_93_WeaponSwap_Sabre", config);
        // 限制只能安装在RX-93系列舰船上
        config.hulls.add("Moci_RX_93");
        // 武器槽配置：光束军刀模式
        config.replaceSlots.put("ARM_R_SOURCE", "RX_93_v2_arm_right_gun_Sabre");
        // 轮替设置
        config.nextHullMod = "Moci_RX_93_WeaponSwap_rifle";
        config.sourceHullMod = "Moci_RX_93_WeaponSwap_rifle";
        // 显示控制：RX-93武器组
        config.displaySortOrder = 3802;
        config.displayCategoryIndex = 2; // 舰船系统类
        // 武器清理：管理RX-93武器相关的武器ID
        config.managedWeaponPrefixes.add("RX_93_arm_right");

        // Moci_Aestivalis系列武器切换系统配置
        // 右臂武器轮替组
        // 右臂步枪模式（右臂默认武器）
        config = new hullModComfig("Moci_Aestivalis_WeaponSwap_right_rifle");
        HULL_MOD_CONFIGS.put("Moci_Aestivalis_WeaponSwap_right_rifle", config);
        // 限制只能安装在Aestivalis系列舰船上
        config.hulls.add("Moci_Aestivalis_AKITO");
        config.hulls.add("Moci_Aestivalis_H");
        config.hulls.add("Moci_Aestivalis_I");
        // 武器槽配置：右臂步枪模式
        config.replaceSlots.put("ARM_R_SOURCE", "Moci_Aestivalis_AKITO_right_gun_rifle");
        // 轮替设置
        config.nextHullMod = "Moci_Aestivalis_WeaponSwap_right_railcannon";
        config.sourceHullMod = "Moci_Aestivalis_WeaponSwap_right_rifle";
        // 显示控制：Aestivalis右臂武器组，显示顺序3700-3749
        config.displaySortOrder = 3700;
        config.displayCategoryIndex = 2; // 舰船系统类
        // 武器清理：管理Aestivalis右臂武器相关的武器ID
        config.managedWeaponPrefixes.add("Moci_Aestivalis_AKITO_right");
        config.managedWeaponPrefixes.add("Moci_Aestivalis_AKITO_arm_right");

        // 右臂轨道炮模式
        config = new hullModComfig("Moci_Aestivalis_WeaponSwap_right_railcannon");
        HULL_MOD_CONFIGS.put("Moci_Aestivalis_WeaponSwap_right_railcannon", config);
        // 限制只能安装在Aestivalis系列舰船上
        config.hulls.add("Moci_Aestivalis_AKITO");
        config.hulls.add("Moci_Aestivalis_H");
        config.hulls.add("Moci_Aestivalis_I");
        // 武器槽配置：右臂轨道炮模式
        config.replaceSlots.put("ARM_R_SOURCE", "Moci_Aestivalis_AKITO_arm_right_railcannon");
        // 轮替设置
        config.nextHullMod = "Moci_Aestivalis_WeaponSwap_right_rifle";
        config.sourceHullMod = "Moci_Aestivalis_WeaponSwap_right_rifle";
        // 显示控制：Aestivalis右臂武器组
        config.displaySortOrder = 3701;
        config.displayCategoryIndex = 2; // 舰船系统类
        // 武器清理：管理Aestivalis右臂武器相关的武器ID
        config.managedWeaponPrefixes.add("Moci_Aestivalis_AKITO_right");
        config.managedWeaponPrefixes.add("Moci_Aestivalis_AKITO_arm_right");

        // 左臂武器轮替组
        // 左臂步枪模式（左臂默认武器）
        config = new hullModComfig("Moci_Aestivalis_WeaponSwap_left_rifle");
        HULL_MOD_CONFIGS.put("Moci_Aestivalis_WeaponSwap_left_rifle", config);
        // 限制只能安装在Aestivalis系列舰船上
        config.hulls.add("Moci_Aestivalis_AKITO");
        config.hulls.add("Moci_Aestivalis_H");
        config.hulls.add("Moci_Aestivalis_I");
        // 武器槽配置：左臂步枪模式
        config.replaceSlots.put("ARM_L_SOURCE", "Moci_Aestivalis_AKITO_left_gun_rifle");
        // 轮替设置
        config.nextHullMod = "Moci_Aestivalis_WeaponSwap_left_railcannon";
        config.sourceHullMod = "Moci_Aestivalis_WeaponSwap_left_rifle";
        // 显示控制：Aestivalis左臂武器组，显示顺序3750-3799（位于右臂武器下方）
        config.displaySortOrder = 3750;
        config.displayCategoryIndex = 2; // 舰船系统类
        // 武器清理：管理Aestivalis左臂武器相关的武器ID
        config.managedWeaponPrefixes.add("Moci_Aestivalis_AKITO_left");
        config.managedWeaponPrefixes.add("Moci_Aestivalis_AKITO_arm_left");
        config.managedWeaponPrefixes.add("Moci_Aestivalis_Gekiganflare");

        // 左臂轨道炮模式
        config = new hullModComfig("Moci_Aestivalis_WeaponSwap_left_railcannon");
        HULL_MOD_CONFIGS.put("Moci_Aestivalis_WeaponSwap_left_railcannon", config);
        // 限制只能安装在Aestivalis系列舰船上
        config.hulls.add("Moci_Aestivalis_AKITO");
        config.hulls.add("Moci_Aestivalis_H");
        config.hulls.add("Moci_Aestivalis_I");
        // 武器槽配置：左臂轨道炮模式
        config.replaceSlots.put("ARM_L_SOURCE", "Moci_Aestivalis_AKITO_arm_left_railcannon");
        // 轮替设置
        config.nextHullMod = "Moci_Aestivalis_WeaponSwap_left_Gekiganflare";
        config.sourceHullMod = "Moci_Aestivalis_WeaponSwap_left_rifle";
        // 显示控制：Aestivalis左臂武器组
        config.displaySortOrder = 3751;
        config.displayCategoryIndex = 2; // 舰船系统类
        // 武器清理：管理Aestivalis左臂武器相关的武器ID
        config.managedWeaponPrefixes.add("Moci_Aestivalis_AKITO_left");
        config.managedWeaponPrefixes.add("Moci_Aestivalis_AKITO_arm_left");
        config.managedWeaponPrefixes.add("Moci_Aestivalis_Gekiganflare");

        // 左臂激钢爆炎模式
        config = new hullModComfig("Moci_Aestivalis_WeaponSwap_left_Gekiganflare");
        HULL_MOD_CONFIGS.put("Moci_Aestivalis_WeaponSwap_left_Gekiganflare", config);
        // 限制只能安装在Aestivalis系列舰船上
        config.hulls.add("Moci_Aestivalis_AKITO");
        config.hulls.add("Moci_Aestivalis_H");
        config.hulls.add("Moci_Aestivalis_I");
        // 武器槽配置：左臂激钢爆炎模式
        config.replaceSlots.put("ARM_L_SOURCE", "Moci_Aestivalis_Gekiganflare");
        // 轮替设置
        config.nextHullMod = "Moci_Aestivalis_WeaponSwap_left_rifle";
        config.sourceHullMod = "Moci_Aestivalis_WeaponSwap_left_rifle";
        // 显示控制：Aestivalis左臂武器组
        config.displaySortOrder = 3752;
        config.displayCategoryIndex = 2; // 舰船系统类
        // 武器清理：管理Aestivalis左臂武器相关的武器ID
        config.managedWeaponPrefixes.add("Moci_Aestivalis_AKITO_left");
        config.managedWeaponPrefixes.add("Moci_Aestivalis_AKITO_arm_left");
        config.managedWeaponPrefixes.add("Moci_Aestivalis_Gekiganflare");

    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        if (HULL_MOD_CONFIGS.containsKey(spec.getId())) {
            HULL_MOD_CONFIGS.get(spec.getId()).applyEffectsAfterShipCreation(ship, id);
        }
    }

    @Override
    public int getDisplaySortOrder() {
        if (HULL_MOD_CONFIGS.containsKey(spec.getId())) {
            return HULL_MOD_CONFIGS.get(spec.getId()).displaySortOrder;
        }
        return super.getDisplaySortOrder();
    }

    @Override
    public int getDisplayCategoryIndex() {
        if (HULL_MOD_CONFIGS.containsKey(spec.getId())) {
            return HULL_MOD_CONFIGS.get(spec.getId()).displayCategoryIndex;
        }
        return super.getDisplayCategoryIndex();
    }

    public static class hullModComfig {
        private final Set<String> conflictHullMods = new HashSet<>();
        private final Set<String> hulls = new HashSet<>();
        private String nextHullMod = null;
        private String sourceHullMod = null;
        private final Map<String, String> replaceSlots = new HashMap<>();
        private final String modId;

        // 新增：显示控制属性
        private int displaySortOrder = 2000; // 默认显示顺序
        private int displayCategoryIndex = 2; // 默认舰船系统分类

        // 新增：武器清理管理
        private final Set<String> managedWeaponPrefixes = new HashSet<>(); // 管理的武器ID前缀

        private hullModComfig(String modId) {
            this.modId = modId;
        }

        public String getNextHullmod() {
            return nextHullMod;
        }

        public String getSourceHullMod() {
            return sourceHullMod;
        }

        public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
            applySlotReplace(ship);
            // 新增：应用武器清理逻辑
            cleanupManagedWeapons(ship);
        }

        public void applySlotReplace(ShipAPI ship) {
            ShipVariantAPI variant = ship.getVariant();
            for (String slotID : replaceSlots.keySet()) {
                if (variant.getSlot(slotID) == null) {
                    continue;
                }
                variant.clearSlot(slotID);
                String weaponId = replaceSlots.get(slotID);
                if (weaponId != null && !weaponId.isEmpty() && Global.getSettings().getWeaponSpec(weaponId) != null) {
                    variant.addWeapon(slotID, weaponId);
                }
            }
        }

        /**
         * TpcWeaponSwap的仓库管理
         */
        private void cleanupManagedWeapons(ShipAPI ship) {
            if (ship.getOriginalOwner() < 0) { // 仅处理玩家舰船
                // 确保玩家舰队货物存在
                if (Global.getSector() != null
                        && Global.getSector().getPlayerFleet() != null
                        && Global.getSector().getPlayerFleet().getCargo() != null) {

                    // 遍历所有货物
                    for (CargoStackAPI stack : Global.getSector().getPlayerFleet().getCargo().getStacksCopy()) {
                        if (stack.isWeaponStack()) {
                            String weaponId = stack.getWeaponSpecIfWeapon().getWeaponId();
                            // 检查是否是当前配置管理的武器类型
                            for (String prefix : managedWeaponPrefixes) {
                                if (weaponId.startsWith(prefix)) {
                                    // 移除被轮替的武器（防止重复）
                                    Global.getSector().getPlayerFleet().getCargo().removeStack(stack);
                                    Global.getLogger(this.getClass()).info(
                                            "武器切换系统：清理玩家仓库中的轮替武器 " + weaponId +
                                                    " (配置: " + modId + ")");
                                    break; // 找到匹配的前缀后跳出内层循环
                                }
                            }
                        }
                    }
                }
            }
        }

        public boolean isApplicableToShip(ShipVariantAPI variant) {
            String hullID = variant.getHullSpec().getBaseHullId();
            if (!hulls.isEmpty() && !hulls.contains(hullID)) {
                return false;
            }
            for (String mod : variant.getHullMods()) {
                if (conflictHullMods.contains(mod)) {
                    return false;
                }
            }
            return true;
        }
    }

}
