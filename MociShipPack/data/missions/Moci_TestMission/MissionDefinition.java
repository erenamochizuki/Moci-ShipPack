package data.missions.Moci_TestMission;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

public class MissionDefinition implements MissionDefinitionPlugin {

    @Override
    public void defineMission(MissionDefinitionAPI api) {
        // 初始化玩家舰队
        api.initFleet(FleetSide.PLAYER, "MOCI", FleetGoal.ATTACK, false);
        // 初始化敌方舰队
        api.initFleet(FleetSide.ENEMY, "ZUKI", FleetGoal.ATTACK, true);

        // 设置舰队标签
        api.setFleetTagline(FleetSide.PLAYER, "玩家舰队");
        api.setFleetTagline(FleetSide.ENEMY, "敌方舰队");

        // 添加任务简报项
        api.addBriefingItem("测试战役");

        api.addToFleet(FleetSide.PLAYER, "Moci_Limarch_Standard", FleetMemberType.SHIP, "MOCI_A1", true); // 旗舰
        api.addToFleet(FleetSide.PLAYER, "Moci_Limarch_xiv_Elite", FleetMemberType.SHIP, "MOCI_A2", false);
        api.addToFleet(FleetSide.PLAYER, "Moci_Limarch_EP_special", FleetMemberType.SHIP, "MOCI_A3", false);
        api.addToFleet(FleetSide.PLAYER, "Moci_Limarch_LG_Base", FleetMemberType.SHIP, "MOCI_A4", false);
        api.addToFleet(FleetSide.PLAYER, "Moci_Praesidium_Base", FleetMemberType.SHIP, "MOCI_B1", false);
        api.addToFleet(FleetSide.PLAYER, "Moci_Aether_Base", FleetMemberType.SHIP, "MOCI_B2", false);
        api.addToFleet(FleetSide.PLAYER, "Moci_Gladiator_Base", FleetMemberType.SHIP, "MOCI_B3", false);
        api.addToFleet(FleetSide.PLAYER, "Moci_Pyrejudge_p_Base", FleetMemberType.SHIP, "MOCI_B4", false);
        api.addToFleet(FleetSide.PLAYER, "Moci_Monoceros_Base", FleetMemberType.SHIP, "MOCI_B5", false);
        api.addToFleet(FleetSide.PLAYER, "Moci_Ironshark_pirate_Custom", FleetMemberType.SHIP, "MOCI_C1", false);
        api.addToFleet(FleetSide.PLAYER, "Moci_Arbitrager_special", FleetMemberType.SHIP, "MOCI_D1", false);
        
        api.addToFleet(FleetSide.PLAYER, "Moci_colossus9_Base", FleetMemberType.SHIP, "MOCI_DD1", false);
        
        api.addToFleet(FleetSide.PLAYER, "Moci_MSN_04_2_Base", FleetMemberType.SHIP, "MOCI_DD2", false);
        api.addToFleet(FleetSide.PLAYER, "Moci_RX_93_v2_base", FleetMemberType.SHIP, "MOCI_DD3", false);
        api.addToFleet(FleetSide.PLAYER, "Moci_RX_93_v2_HWS_base", FleetMemberType.SHIP, "MOCI_DD4", false);

        api.addToFleet(FleetSide.PLAYER, "Moci_AltEisen_base", FleetMemberType.SHIP, "MOCI_DD5", false);
        api.addToFleet(FleetSide.PLAYER, "Moci_ReinWeissRitter_Base", FleetMemberType.SHIP, "MOCI_DD6", false);

        api.addToFleet(FleetSide.PLAYER, "Moci_MS_06_Standard", FleetMemberType.SHIP, "MOCI_DD7", false);
        api.addToFleet(FleetSide.PLAYER, "Moci_RMS_106_Standard", FleetMemberType.SHIP, "MOCI_DD8", false);
        api.addToFleet(FleetSide.PLAYER, "Moci_MS_14_Standard", FleetMemberType.SHIP, "MOCI_DD9", false);
        api.addToFleet(FleetSide.PLAYER, "Moci_MSN_03b_Standard", FleetMemberType.SHIP, "MOCI_DE1", false);
        api.addToFleet(FleetSide.PLAYER, "Moci_MSN_03_Standard", FleetMemberType.SHIP, "MOCI_DE2", false);
        api.addToFleet(FleetSide.PLAYER, "Moci_MSN_03_Standard", FleetMemberType.SHIP, "MOCI_DE3", false);
        api.addToFleet(FleetSide.PLAYER, "Moci_MS_06_leader_Standard", FleetMemberType.SHIP, "MOCI_DE4", false);
        api.addToFleet(FleetSide.PLAYER, "Moci_MS_06_Standard", FleetMemberType.SHIP, "MOCI_DE5", false);
        api.addToFleet(FleetSide.PLAYER, "Moci_MS_06_Standard", FleetMemberType.SHIP, "MOCI_DE6", false);
        api.addToFleet(FleetSide.PLAYER, "Moci_RX_78_2_Standard", FleetMemberType.SHIP, "MOCI_DE7", false);
        api.addToFleet(FleetSide.PLAYER, "Moci_RX_78_1_Standard", FleetMemberType.SHIP, "MOCI_DE8", false);
        api.addToFleet(FleetSide.PLAYER, "Moci_RX_78_3_Standard", FleetMemberType.SHIP, "MOCI_DE9", false);



        // 创建霸权派系实例
        FactionAPI hegemony = Global.getSettings().createBaseFaction(Factions.HEGEMONY);
        FleetMemberAPI member;
        // 添加敌方旗舰
        member = api.addToFleet(FleetSide.ENEMY, "mora_Strike", FleetMemberType.SHIP, false);
        member.setShipName(hegemony.pickRandomShipName()); // 使用随机舰船名

        // 初始化战斗地图 (16000x16000单位)
        float width = 16000f;
        float height = 16000f;
        api.initMap(-width / 2f, width / 2f, -height / 2f, height / 2f);

        // 随机生成6个星云
        for (int i = 0; i < 6; i++) {
            float x = (float) Math.random() * width - width / 2;
            float y = (float) Math.random() * height - height / 2;
            float radius = 100f + (float) Math.random() * 400f;
            api.addNebula(x, y, radius);
        }

        // 添加战略目标点
        api.addObjective(width * 0.35f, -height * 0.1f, "nav_buoy"); // 导航浮标
        api.addObjective(-width * 0.35f, -height * 0.1f, "nav_buoy");
        api.addObjective(0f, -height * 0.3f, "sensor_array"); // 传感器阵列
        api.addObjective(width * 0.2f, height * 0.35f, "comm_relay"); // 通讯中继站
        api.addObjective(-width * 0.2f, height * 0.35f, "comm_relay");

        // 添加固定位置的大型星云
        api.addNebula(0f, -height * 0.3f, 1000f);
        api.addNebula(width * 0.15f, -height * 0.05f, 2000f);
        api.addNebula(-width * 0.15f, -height * 0.05f, 2000f);

        // 添加环形小行星带
        api.addRingAsteroids(0f, 0f, 40f, width, 30f, 40f, 400);

        // 在中心添加冰巨星行星
        api.addPlanet(0, 0, 350f, "ice_giant", 0f, true);
    }
}