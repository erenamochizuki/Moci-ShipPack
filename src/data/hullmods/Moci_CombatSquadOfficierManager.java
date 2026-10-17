package data.hullmods;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Ranks;
import com.fs.starfarer.api.impl.campaign.ids.Skills;

public class Moci_CombatSquadOfficierManager {

        public static String MAP_KEY = "$Moci_CombatSquadOfficierMap";

        public static class SquadMember {
                public String variantId;
                public String displayName;
                public int count;
                public int officerLevel;
                public String[] skills;
                public String personality;

                public SquadMember(String variantId, String displayName, int count, int officerLevel, String[] skills,
                                String personality) {
                        this.variantId = variantId;
                        this.displayName = displayName;
                        this.count = count;
                        this.officerLevel = officerLevel;
                        this.skills = skills;
                        this.personality = personality;
                }
        }

        // 编队配置映射表
        public static final Map<String, List<SquadMember>> SQUAD_CONFIGS = new HashMap<>();

        static {
                // 扎古队长机
                List<SquadMember> ms06LeaderSquad = new ArrayList<>();
                ms06LeaderSquad.add(new SquadMember("Moci_MS_06_Gunner", "扎古_炮击型", 1, 3,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION },
                                "aggressive"));
                ms06LeaderSquad.add(new SquadMember("Moci_MS_06_Rocket", "扎古_火箭型", 1, 3,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION },
                                "aggressive"));
                SQUAD_CONFIGS.put("Moci_MS_06_leader", ms06LeaderSquad);

                // 扎古夏亚机
                List<SquadMember> ms06CharSquad = new ArrayList<>();
                ms06CharSquad.add(new SquadMember("Moci_MS_06_GunnerM", "扎古_炮击型改", 1, 3,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION },
                                "aggressive"));
                ms06CharSquad.add(new SquadMember("Moci_MS_06_leader_RocketS", "扎古队长_火箭型改", 1, 4,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.DAMAGE_CONTROL },
                                "steady"));
                SQUAD_CONFIGS.put("Moci_MS_06_Char", ms06CharSquad);

                // 扎古黑色三连星机
                List<SquadMember> ms06BTSSquad = new ArrayList<>();
                ms06BTSSquad.add(new SquadMember("Moci_MS_06_Rocket", "扎古_火箭型", 1, 3,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION },
                                "aggressive"));
                ms06BTSSquad.add(new SquadMember("Moci_MS_06_leader_Rocket", "扎古队长_火箭型", 1, 4,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.DAMAGE_CONTROL },
                                "steady"));
                SQUAD_CONFIGS.put("Moci_MS_06_BTS", ms06BTSSquad);

                // 扎古强尼机
                List<SquadMember> ms06JohnnySquad = new ArrayList<>();
                ms06JohnnySquad.add(new SquadMember("Moci_MS_06_Rocket", "扎古_火箭型", 1, 3,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION },
                                "aggressive"));
                ms06JohnnySquad.add(new SquadMember("Moci_MS_06_leader_Rocket", "扎古队长_火箭型", 1, 4,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.DAMAGE_CONTROL },
                                "steady"));
                SQUAD_CONFIGS.put("Moci_MS_06_Johnny", ms06JohnnySquad);

                // 扎古松永真机
                List<SquadMember> ms06ShinSquad = new ArrayList<>();
                ms06ShinSquad.add(new SquadMember("Moci_MS_06_GunnerM", "扎古_炮击型改", 1, 3,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION },
                                "aggressive"));
                ms06ShinSquad.add(new SquadMember("Moci_MS_06_GunnerM", "扎古_炮击型改", 1, 4,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.DAMAGE_CONTROL },
                                "steady"));
                SQUAD_CONFIGS.put("Moci_MS_06_Shin", ms06ShinSquad);

                // 勇士夏亚机
                List<SquadMember> ms14CharSquad = new ArrayList<>();
                ms14CharSquad.add(new SquadMember("Moci_MS_14_Cannon", "勇士_炮击型", 1, 4,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.ENERGY_WEAPON_MASTERY },
                                "aggressive"));
                ms14CharSquad.add(new SquadMember("Moci_MS_14_Beam", "勇士_光束型", 1, 4,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.ENERGY_WEAPON_MASTERY },
                                "aggressive"));
                SQUAD_CONFIGS.put("Moci_MS_14_Char", ms14CharSquad);

                // 勇士黑色三连星机
                List<SquadMember> ms14BTSSquad = new ArrayList<>();
                ms14BTSSquad.add(new SquadMember("Moci_MS_14_Cannon", "勇士_炮击型", 1, 4,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.ENERGY_WEAPON_MASTERY },
                                "aggressive"));
                ms14BTSSquad.add(new SquadMember("Moci_MS_14_Beam", "勇士_光束型", 1, 4,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.ENERGY_WEAPON_MASTERY },
                                "aggressive"));
                SQUAD_CONFIGS.put("Moci_MS_14_BTS", ms14BTSSquad);

                // 勇士强尼机
                List<SquadMember> ms14JohnnySquad = new ArrayList<>();
                ms14JohnnySquad.add(new SquadMember("Moci_MS_14_Cannon", "勇士_炮击型", 1, 4,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.ENERGY_WEAPON_MASTERY },
                                "aggressive"));
                ms14JohnnySquad.add(new SquadMember("Moci_MS_14_Cannon", "勇士_炮击型", 1, 4,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.ENERGY_WEAPON_MASTERY },
                                "aggressive"));
                SQUAD_CONFIGS.put("Moci_MS_14_Johnny", ms14JohnnySquad);

                // 勇士松永真机
                List<SquadMember> ms14ShinSquad = new ArrayList<>();
                ms14ShinSquad.add(new SquadMember("Moci_MS_14_Beam", "勇士_光束型", 1, 4,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.ENERGY_WEAPON_MASTERY },
                                "aggressive"));
                ms14ShinSquad.add(new SquadMember("Moci_MS_14_Beam", "勇士_光束型", 1, 4,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.ENERGY_WEAPON_MASTERY },
                                "aggressive"));
                SQUAD_CONFIGS.put("Moci_MS_14_Shin", ms14ShinSquad);

                // RGM-89队长机
                List<SquadMember> rgm89LeaderSquad = new ArrayList<>();
                rgm89LeaderSquad.add(new SquadMember("Moci_RGM_89_Gunner", "RGM-89_炮击型", 1, 5,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.DAMAGE_CONTROL, Skills.BALLISTIC_MASTERY },
                                "aggressive"));
                rgm89LeaderSquad.add(new SquadMember("Moci_RGM_89_Rocket", "RGM-89_火箭型", 1, 5,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.DAMAGE_CONTROL, Skills.MISSILE_SPECIALIZATION },
                                "aggressive"));
                SQUAD_CONFIGS.put("Moci_RGM_89_leader", rgm89LeaderSquad);

                // AMS-119队长机
                List<SquadMember> ams119LeaderSquad = new ArrayList<>();
                ams119LeaderSquad.add(new SquadMember("Moci_AMS_119_Gunner", "AMS-119_炮击型", 1, 5,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.DAMAGE_CONTROL, Skills.BALLISTIC_MASTERY },
                                "aggressive"));
                ams119LeaderSquad.add(new SquadMember("Moci_AMS_119_GunnerH", "AMS-119_重炮击型", 1, 5,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.DAMAGE_CONTROL, Skills.BALLISTIC_MASTERY },
                                "aggressive"));
                SQUAD_CONFIGS.put("Moci_AMS_119_leader", ams119LeaderSquad);

                // AMS-119H队长机
                List<SquadMember> ams119hLeaderSquad = new ArrayList<>();
                ams119hLeaderSquad.add(new SquadMember("Moci_AMS_119H_Gunner", "AMS-119H_炮击型", 1, 5,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.DAMAGE_CONTROL, Skills.BALLISTIC_MASTERY },
                                "aggressive"));
                ams119hLeaderSquad.add(new SquadMember("Moci_AMS_119_GunnerH", "AMS-119_重炮击型", 1, 5,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.DAMAGE_CONTROL, Skills.BALLISTIC_MASTERY },
                                "aggressive"));
                SQUAD_CONFIGS.put("Moci_AMS_119H_leader", ams119hLeaderSquad);

                // RX-78-2高达
                List<SquadMember> rx782Squad = new ArrayList<>();
                rx782Squad.add(new SquadMember("Moci_RGM_86_Cannon", "RGM-86_炮击型", 1, 5,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.DAMAGE_CONTROL, Skills.ENERGY_WEAPON_MASTERY },
                                "aggressive"));
                rx782Squad.add(new SquadMember("Moci_RGM_86_Cannon", "RGM-86_炮击型", 1, 5,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.DAMAGE_CONTROL, Skills.ENERGY_WEAPON_MASTERY },
                                "aggressive"));
                SQUAD_CONFIGS.put("Moci_RX_78_2", rx782Squad);

                // RX-78-1高达
                List<SquadMember> rx781Squad = new ArrayList<>();
                rx781Squad.add(new SquadMember("Moci_RGM_86_Cannon", "RGM-86_炮击型", 1, 5,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.DAMAGE_CONTROL, Skills.ENERGY_WEAPON_MASTERY },
                                "aggressive"));
                rx781Squad.add(new SquadMember("Moci_RGM_86_Cannon", "RGM-86_炮击型", 1, 5,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.DAMAGE_CONTROL, Skills.ENERGY_WEAPON_MASTERY },
                                "aggressive"));
                SQUAD_CONFIGS.put("Moci_RX_78_1", rx781Squad);

                // RX-78-3高达
                List<SquadMember> rx783Squad = new ArrayList<>();
                rx783Squad.add(new SquadMember("Moci_RGM_86_Cannon", "RGM-86_炮击型", 1, 5,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.DAMAGE_CONTROL, Skills.ENERGY_WEAPON_MASTERY },
                                "aggressive"));
                rx783Squad.add(new SquadMember("Moci_RGM_86_Cannon", "RGM-86_炮击型", 1, 5,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.DAMAGE_CONTROL, Skills.ENERGY_WEAPON_MASTERY },
                                "aggressive"));
                SQUAD_CONFIGS.put("Moci_RX_78_3", rx783Squad);

                // RGM-79SP吉姆
                List<SquadMember> rgm79spSquad = new ArrayList<>();
                rgm79spSquad.add(new SquadMember("Moci_RMS_179_Beam", "RMS-179_光束型", 1, 4,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.ENERGY_WEAPON_MASTERY },
                                "aggressive"));
                rgm79spSquad.add(new SquadMember("Moci_RMS_179_Beam", "RMS-179_光束型", 1, 4,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.ENERGY_WEAPON_MASTERY },
                                "aggressive"));
                SQUAD_CONFIGS.put("Moci_RGM_79SP", rgm79spSquad);

                // RGM-79P吉姆
                List<SquadMember> rgm79pSquad = new ArrayList<>();
                rgm79pSquad.add(new SquadMember("Moci_RGM_79_Cannon", "RGM-79_炮击型", 1, 4,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.ENERGY_WEAPON_MASTERY },
                                "aggressive"));
                rgm79pSquad.add(new SquadMember("Moci_RGM_79_Cannon", "RGM-79_炮击型", 1, 4,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.ENERGY_WEAPON_MASTERY },
                                "aggressive"));
                SQUAD_CONFIGS.put("Moci_RGM_79P", rgm79pSquad);

                // MSN-03b乍得多加
                List<SquadMember> msn03bSquad = new ArrayList<>();
                msn03bSquad.add(new SquadMember("Moci_MSN_03_Beam", "MSN_03_光束型", 1, 5,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.DAMAGE_CONTROL, Skills.ENERGY_WEAPON_MASTERY },
                                "aggressive"));
                msn03bSquad.add(new SquadMember("Moci_MSN_03_Beam", "MSN_03_光束型", 1, 5,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.DAMAGE_CONTROL, Skills.ENERGY_WEAPON_MASTERY },
                                "aggressive"));
                SQUAD_CONFIGS.put("Moci_MSN_03b", msn03bSquad);

                // FA-78-3全装甲高达
                List<SquadMember> fa783Squad = new ArrayList<>();
                fa783Squad.add(new SquadMember("Moci_RGM_86_Cannon", "RGM-86_炮击型", 1, 5,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.DAMAGE_CONTROL, Skills.ENERGY_WEAPON_MASTERY },
                                "aggressive"));
                fa783Squad.add(new SquadMember("Moci_RGM_86_Cannon", "RGM-86_炮击型", 1, 5,
                                new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION,
                                                Skills.DAMAGE_CONTROL, Skills.ENERGY_WEAPON_MASTERY },
                                "aggressive"));
                SQUAD_CONFIGS.put("Moci_FA_78_3", fa783Squad);
        }

        public static Map<String, List<PersonAPI>> getSquadMap(CampaignFleetAPI fleet) {
                if (fleet.getMemoryWithoutUpdate().contains(MAP_KEY)) {
                        return (Map<String, List<PersonAPI>>) fleet.getMemoryWithoutUpdate().get(MAP_KEY);
                }
                return new HashMap<>();
        }

        public static List<PersonAPI> getSquadByMemberId(FleetMemberAPI member, ShipHullSpecAPI spec) {
                if (member != null && member.getFleetData() != null && member.getFleetData().getFleet() != null) {
                        Map<String, List<PersonAPI>> squad_map = getSquadMap(member.getFleetData().getFleet());
                        if (squad_map != null) {
                                if (squad_map.containsKey(member.getId())) {
                                        return squad_map.get(member.getId());
                                } else {
                                        List<PersonAPI> newSquad = createNewSquad(spec);
                                        squad_map.put(member.getId(), newSquad);
                                        return newSquad;
                                }
                        }
                }
                return createNewSquad(spec);
        }

        public static List<PersonAPI> createNewSquad(ShipHullSpecAPI spec) {
                List<PersonAPI> personList = new ArrayList<>();

                // 添加备用逻辑，与getSquadConfig保持一致
                String hullId = spec.getDParentHullId();
                if (hullId == null) {
                        hullId = spec.getHullId();
                }

                // 使用新的getSquadConfigFromFile方法,优先从CSV加载
                // 该方法保证永远不会返回null,会使用保底配置
                List<SquadMember> squadMemberList = getSquadConfigFromFile(hullId);
                
                // 创建小队成员
                for (SquadMember officerConfig : squadMemberList) {
                        personList.add(createSquadOfficer(officerConfig));
                }
                
                return personList;
        }

        public static List<PersonAPI> createNewSquad(@NotNull FleetMemberAPI member) {
                return createNewSquad(member.getHullSpec());
        }

        /**
         * 从CSV配置文件获取小队配置
         * 优先使用CSV配置,如果不存在则使用默认配置
         * 如果都不存在,返回保底配置(扎古小队)
         * 
         * @param leaderHullId 队长机Hull ID
         * @return 小队成员列表,永远不会返回null
         */
        public static List<SquadMember> getSquadConfigFromFile(String leaderHullId) {
                // 1. 尝试从CSV配置获取
                List<Moci_CombatSquadConfigLoader.SquadMemberConfig> csvConfig = 
                        Moci_CombatSquadConfigLoader.getSquadConfig(leaderHullId);
                
                if (csvConfig != null && !csvConfig.isEmpty()) {
                        // 转换为SquadMember格式
                        return convertToSquadMembers(csvConfig);
                }
                
                // 2. 使用默认配置
                List<SquadMember> defaultConfig = SQUAD_CONFIGS.get(leaderHullId);
                if (defaultConfig != null) {
                        return defaultConfig;
                }
                
                // 3. 保底配置 - 使用扎古队长机的配置作为通用保底
                return getDefaultFallbackSquad();
        }
        
        /**
         * 转换配置格式
         * 将SquadMemberConfig列表转换为SquadMember列表
         * 
         * @param configs SquadMemberConfig列表
         * @return SquadMember列表
         */
        private static List<SquadMember> convertToSquadMembers(
                        List<Moci_CombatSquadConfigLoader.SquadMemberConfig> configs) {
                List<SquadMember> result = new ArrayList<>();
                
                for (Moci_CombatSquadConfigLoader.SquadMemberConfig config : configs) {
                        SquadMember member = new SquadMember(
                                config.variantId,
                                config.displayName,
                                config.count,
                                config.officerLevel,
                                config.skills,
                                config.personality
                        );
                        result.add(member);
                }
                
                return result;
        }
        
        /**
         * 获取保底小队配置
         * 当找不到任何配置时使用的默认配置(扎古队长机配置)
         * 
         * @return 保底小队配置
         */
        private static List<SquadMember> getDefaultFallbackSquad() {
                List<SquadMember> fallbackSquad = new ArrayList<>();
                
                // 使用扎古队长机的配置作为保底
                fallbackSquad.add(new SquadMember(
                        "Moci_MS_06_Gunner", 
                        "扎古_炮击型", 
                        1, 
                        3,
                        new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION },
                        "aggressive"
                ));
                
                fallbackSquad.add(new SquadMember(
                        "Moci_MS_06_Rocket", 
                        "扎古_火箭型", 
                        1, 
                        3,
                        new String[] { Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE, Skills.IMPACT_MITIGATION },
                        "aggressive"
                ));
                
                return fallbackSquad;
        }

        private static PersonAPI createSquadOfficer(SquadMember memberConfig) {
                PersonAPI officer = Global.getFactory().createPerson();
                officer.setFaction("player");
                officer.setPortraitSprite("graphics/portraits/Moci_mob.png");
                officer.getStats().setLevel(memberConfig.officerLevel);
                // 设置技能
                for (String skill : memberConfig.skills) {
                        officer.getStats().setSkillLevel(skill, 1);
                }
                // 设置性格和军衔
                officer.setPersonality(memberConfig.personality);
                officer.setRankId(Ranks.SPACE_LIEUTENANT);
                officer.setPostId(null);
                return officer;
        }
}
