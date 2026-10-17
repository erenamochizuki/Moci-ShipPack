package data.campaign.intel;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.impl.campaign.ids.Skills;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.IntelUIAPI;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.characters.FullName.Gender;

import data.hullmods.Moci_PilotManager;
import data.hullmods.Moci_PilotTraining;
import data.hullmods.Moci_CombatSquadOfficierManager;
import data.hullmods.Moci_CombatSquadConfigLoader;
import data.hullmods.Moci_CombatSquadTextRegistry;
import data.hullmods.Moci_CombatSquadPilotConfig;
import data.hullmods.Moci_CustomWingmanManager;
import data.hullmods.Moci_ShipboardAIAuxEffectRegistry;
import data.hullmods.Moci_ShipboardAIConfigLoader;
import data.hullmods.Moci_ShipboardAIManager;
import data.hullmods.Moci_ShipboardAITextRegistry;

/**
 * CombatSquad机师编队管理界面
 * 
 * 这个类实现了一个完整的机师编队管理系统，提供可视化的界面来管理玩家舰船的机师编队。
 * 主要功能包括：
 *
 * ## 核心功能
 * - 编队列表查看：显示所有安装了CombatSquad船插的舰船及其编队信息
 * - 机师详细管理：查看和编辑机师的基本信息（姓名、呼号、等级、经验、技能）
 * - 技能系统：支持手动升级机师等级和学习/精英化技能
 * - 头像定制：为机师选择个性化头像
 * - 编队重命名：自定义编队名称
 *
 * ## 界面布局
 * 采用七列布局设计：
 * - 第0列(60px)：舰船图标 - 显示僚机驾驶的舰船类型
 * - 第1列(80px)：机师头像 + 换头像按钮
 * - 第2列(150px)：基本信息（姓名、呼号、等级、经验条）
 * - 第3列(110px)：技能显示（前4个技能+精英技能统计）
 * - 第4列(80px)：标签文字（"修改姓名:"、"修改呼号:"）
 * - 第5列(200px)：输入框（姓名输入、呼号输入）
 * - 第6列(100px)：按钮区域（修改按钮、升级按钮、精英化按钮）
 *
 * ## 技能选择系统
 * - 普通技能选择：3列网格布局，显示12个可选技能
 * - 精英技能选择：同样采用3列网格布局，提供一致的用户体验
 * - 升级模式：学习新技能+精英化现有技能
 * - 精英化模式：仅显示可精英化的技能
 *
 * ## 技能显示配置
 * 如需调整技能选择界面的布局：
 * - 普通技能网格：修改 skillPanel 创建时的 cols 变量（默认3列）
 * - 精英技能网格：修改 eliteCols 变量（默认3列）
 * - 按钮尺寸：调整 skillButtonWidth 和 skillButtonHeight
 * - 间距：调整 skillGap
 *
 * ## 头像选择配置
 * 头像选择面板使用10列网格布局：
 * - 列数：cols 变量（默认10列）
 * - 头像尺寸：portraitSize（默认56px）
 * - 间距：portraitGap（默认6px）
 *
 * ## 数据同步
 * 界面中的机师数据与战斗中完全同步，确保"所见即所得"的体验。
 * 所有修改（姓名、头像、呼号、技能）都会立即反映到实际战斗中使用的机师。
 *
 * @author Moci
 * @version 2.3
 * @since 1.0
 */
public class Moci_CombatSquadManagerIntel extends BaseIntelPlugin {

    public static final boolean DISPLAY_ONLY = false;
    public static final Tab BUTTON_FLEET = Tab.FLEET;
    public static final String BUTTON_REFRESH = "btn_refresh";

    protected transient Tab currentTab;
    protected PersonAPI member;
    protected int pilot;

    protected IntervalUtil updateInterval = new IntervalUtil(0.25f, 0.25f);

    // UI常量
    public static int TAB_BUTTON_HEIGHT = 20;
    public static int TAB_BUTTON_WIDTH = 180;
    public static int ENTRY_HEIGHT = 80;
    public static int ENTRY_WIDTH = 300;
    public static int IMAGE_WIDTH = 80;
    public static int MANAGE_BUTTON_WIDTH = 120;
    public static int IMAGE_DESC_GAP = 12;
    public static int AI_ENTRY_WIDTH = 250;
    public static int AI_PORTRAIT_SIZE = 80;
    public static int SHIP_COMMANDER_GAP = 16;
    public static int INFO_TO_AI_GAP = 4;
    public static int COMMANDER_INFO_MAX_WIDTH = 300;

    private static final boolean AI_DEBUG_MODE = false;
    private static final String AI_DETAIL_LOG_PREFIX = "[MOCI_AI_DETAIL]";
    private static final Color AI_SKILL_NORMAL_COLOR = new Color(255, 215, 0);
    private static final Color AI_SKILL_ELITE_COLOR = Misc.getPositiveHighlightColor();
    private static final Color AI_BENEFIT_COLOR = Misc.getPositiveHighlightColor();
    private static final Color AI_NEGATIVE_COLOR = Misc.getNegativeHighlightColor();
    private static final String SHIPBOARD_AI_PERSON_ID_PREFIX = "moci_shipboard_ai_";

    protected Integer selectingPortraitIdx = null;
    protected Integer selectingSkillIdx = null;
    protected String selectingCommanderId = null;
    protected boolean eliteOnlyMode = false; // 是否为纯精英化模式

    // 自定义舰船选择相关状态
    protected Integer selectingShipIdx = null; // 正在为哪个机师选择舰船
    protected String selectingShipCommanderId = null; // 选择舰船的指挥官ID
    protected String selectedSquadShipId = null; // 当前查看小队详情的舰船ID
    protected String selectedAiMemberId = null; // 当前查看舰载AI详情的舰船ID

    /**
     * 构造函数
     */
    public Moci_CombatSquadManagerIntel(PersonAPI member) {
        this.member = member;
        FleetMemberAPI ship = findShipByOfficer(member);
        this.selectedSquadShipId = ship != null ? ship.getId() : null;
        this.currentTab = Tab.SQUAD;
        logUnformedSquadDiagnostics(ship, "open_by_officer");
    }

    /**
     * 获取指定等级所需的累计经验值
     *
     * 经验值阶梯：
     * - 0级: 0经验
     * - 1级: 100经验
     * - 2级: 450经验（100+350）
     * - 3级: 1200经验（450+750）
     * - 4级: 2700经验（1200+1500）
     * - 5级: 5700经验（2700+3000）
     * - 6级: 10700经验（5700+5000）
     * - 7级: 18700经验（10700+8000）
     *
     * @param level 目标等级（0-7）
     * @return 该等级所需的累计经验值
     */
    private float getExperienceForLevel(int level) {
        switch (level) {
            case 0:
                return 0f;
            case 1:
                return 100f;
            case 2:
                return 450f; // 100 + 350
            case 3:
                return 1200f; // 450 + 750
            case 4:
                return 2700f; // 1200 + 1500
            case 5:
                return 5700f; // 2700 + 3000
            case 6:
                return 10700f; // 5700 + 5000
            case 7:
                return 18700f; // 10700 + 8000
            default:
                return 18700f; // 最高7级
        }
    }

    /**
     * 构造函数
     *
     * 初始化机师编队管理界面，自动注册到情报系统并设为重要情报。
     */
    public Moci_CombatSquadManagerIntel() {
        Global.getSector().getIntelManager().addIntel(this);
        Global.getSector().addScript(this);
        this.setImportant(true);
        this.currentTab = Tab.FLEET;
    }

    /**
     * 获取玩家舰队中的所有舰船
     *
     * @return 玩家舰队的所有舰船列表
     */
    public List<FleetMemberAPI> getAllShips() {
        return Global.getSector().getPlayerFleet().getFleetData().getMembersListCopy();
    }

    @Override
    public String getSmallDescriptionTitle() {
        return getName();
    }

    protected String getName() {
        return csText("intel_name", "CombatSquad编队管理");
    }

    @Override
    public boolean hasSmallDescription() {
        return false;
    }

    @Override
    public boolean hasLargeDescription() {
        return true;
    }

    @Override
    public boolean isPlayerVisible() {
        return true;
    }

    /**
     * 为面板创建人物头像元素
     *
     * @param panel  父面板
     * @param person 人物对象
     * @param width  头像宽度
     * @param height 头像高度
     * @return 包含头像的TooltipMakerAPI元素
     */
    public TooltipMakerAPI createPersonImageForPanel(CustomPanelAPI panel,
            PersonAPI person, float width, float height) {
        TooltipMakerAPI image = panel.createUIElement(width, height, false);
        image.addImage(person.getPortraitSprite(), height, 0);
        return image;
    }

    /**
     * 创建舰船图像 -
     */
    public TooltipMakerAPI createFleetMemberImageForPanel(CustomPanelAPI panel,
            FleetMemberAPI member, float width, float height) {
        TooltipMakerAPI image = panel.createUIElement(width, height, false);
        // ：使用单舰列表显示
        List<FleetMemberAPI> singleShip = new ArrayList<FleetMemberAPI>();
        singleShip.add(member);
        image.addShipList(1, 1, height, Misc.getButtonTextColor(), singleShip, 0f);
        return image;
    }

    /**
     * 生成图像 -
     */
    protected TooltipMakerAPI generateImage(CustomPanelAPI panel, FleetMemberAPI member, PersonAPI person) {
        if (member == null && person == null) {
            return null;
        }
        if (member != null) {
            return createFleetMemberImageForPanel(panel, member, IMAGE_WIDTH, ENTRY_HEIGHT);
        } else {
            return createPersonImageForPanel(panel, person, IMAGE_WIDTH, ENTRY_HEIGHT);
        }
    }

    /**
     * 创建编队列表视图 -
     */
    protected void createFleetView(CustomPanelAPI panel, TooltipMakerAPI info, float width) {
        float pad = 3;
        float opad = 10;
        Color h = Misc.getHighlightColor();
        CampaignFleetAPI player = Global.getSector().getPlayerFleet();
        if (player == null)
            return;

        List<FleetMemberAPI> trackedShips = getTrackedShips(player);
        if (trackedShips.isEmpty()) {
            info.addPara(csText("fleet_no_manageable", "当前没有可管理舰船。"), 8f, Misc.getGrayColor());
            info.addPara(csFormat("fleet_hint", "安装了 {mod1} 或 {mod2} 的舰船会出现在这里。",
                    "mod1", "CombatSquad船插", "mod2", "舰载AI系统"), pad, h, "CombatSquad船插", "舰载AI系统");
            member = null;
            selectedSquadShipId = null;
            return;
        }

        float heightPerItem = ENTRY_HEIGHT + opad;
        float itemPanelHeight = Math.max(heightPerItem * trackedShips.size() + opad, ENTRY_HEIGHT + opad * 2f);
        CustomPanelAPI itemPanel = panel.createCustomPanel(width - 8f, itemPanelHeight, null);
        float yPos = opad;

        for (FleetMemberAPI ship : trackedShips) {
            PersonAPI captain = ship.getCaptain();
            PersonAPI captainForPortrait = captain != null ? captain : player.getFleetData().getCommander();
            if (captainForPortrait == null) {
                captainForPortrait = Global.getSector().getPlayerPerson();
            }
            boolean hasEstablishedSquad = hasCombatSquadSystem(ship)
                    && captain != null
                    && !captain.isDefault()
                    && hasEstablishedSquad(captain);

            float shipX = 4f;
            float officerX = shipX + IMAGE_WIDTH + SHIP_COMMANDER_GAP;

            // 舰船图像（纯显示，避免按钮覆盖导致颜色变化）
            TooltipMakerAPI image = generateImage(itemPanel, ship, null);
            itemPanel.addUIElement(image).inTL(shipX, yPos);

            // 军官头像 - 在舰船图像右侧（点击进入编队管理）
            TooltipMakerAPI officerClickHolder = itemPanel.createUIElement(IMAGE_WIDTH, ENTRY_HEIGHT, false);
            List<Object> officerParams = new ArrayList<Object>();
            officerParams.add("open_squad");
            officerParams.add(ship.getId());
            officerClickHolder.addAreaCheckbox("",
                    officerParams,
                    Misc.getBasePlayerColor(),
                    Misc.getDarkPlayerColor(),
                    Misc.getBrightPlayerColor(),
                    IMAGE_WIDTH,
                    ENTRY_HEIGHT,
                    0f);
            itemPanel.addUIElement(officerClickHolder).inTL(officerX, yPos);

            TooltipMakerAPI officerImage = createPersonImageForPanel(itemPanel, captainForPortrait, IMAGE_WIDTH, ENTRY_HEIGHT);
            itemPanel.addUIElement(officerImage).inTL(officerX, yPos);

            float infoX = officerX + IMAGE_WIDTH + IMAGE_DESC_GAP;
            float infoWidth = Math.max(220f, width - infoX - AI_ENTRY_WIDTH - 20f);
            infoWidth = Math.min(COMMANDER_INFO_MAX_WIDTH, infoWidth);
            float aiX = infoX + infoWidth + INFO_TO_AI_GAP;
            float aiTextWidth = Math.max(120f, AI_ENTRY_WIDTH - AI_PORTRAIT_SIZE - 8f);

            TooltipMakerAPI entry = itemPanel.createUIElement(infoWidth, ENTRY_HEIGHT, true);
            String commanderName = (captain != null && !captain.isDefault()) ? captain.getName().getFullName()
                    : csText("fleet_commander_unassigned", "未分配军官");
            String commanderRank = (captain != null && !captain.isDefault()) ? captain.getRank() : csText("fleet_none", "无");
            String safeCommanderName = escapeTooltipFormat(commanderName);
            String safeCommanderRank = escapeTooltipFormat(commanderRank);
            entry.addPara(csFormat("fleet_label_commander", "指挥官: {value}", "value", safeCommanderName), opad, h, safeCommanderName);
            entry.addPara(csFormat("fleet_label_rank", "军衔: {value}", "value", safeCommanderRank), pad, h, safeCommanderRank);

            if (hasEstablishedSquad) {
                String squadName = Moci_PilotManager.getSquadName(captain.getId(), true);
                String safeSquadName = escapeTooltipFormat(squadName);
                entry.addPara(csFormat("fleet_label_squad", "编队: {value}", "value", safeSquadName), pad, h, safeSquadName);
            } else {
                entry.addPara(csText("fleet_squad_unformed", "编队: 未成立"), pad, Misc.getGrayColor());
            }

            String hullName = ship.getHullSpec().getHullNameWithDashClass();
            String safeHullName = escapeTooltipFormat(hullName);
            entry.addPara(csFormat("fleet_label_ship", "舰船: {value}", "value", safeHullName),
                    pad, Misc.getGrayColor(), safeHullName);
            itemPanel.addUIElement(entry).inTL(infoX, yPos);

            TooltipMakerAPI aiArea = itemPanel.createUIElement(aiTextWidth, ENTRY_HEIGHT, true);
            Moci_ShipboardAIManager.ShipboardAIData aiData = Moci_ShipboardAIManager.getOrCreateData(ship);
            Moci_ShipboardAIConfigLoader.AITypeDefinition aiType = aiData != null
                    ? Moci_ShipboardAIConfigLoader.getAiType(aiData.currentAiTypeId)
                    : null;

            if (aiData != null && aiType != null) {
                float aiPortraitY = yPos + Math.max(0f, (ENTRY_HEIGHT - AI_PORTRAIT_SIZE) / 2f);
                TooltipMakerAPI aiButtonHolder = itemPanel.createUIElement(AI_PORTRAIT_SIZE, AI_PORTRAIT_SIZE, false);
                List<Object> aiParams = new ArrayList<Object>();
                aiParams.add("open_ai_detail");
                aiParams.add(ship.getId());
                aiButtonHolder.addAreaCheckbox("",
                        aiParams,
                        Misc.getBasePlayerColor(),
                        Misc.getDarkPlayerColor(),
                        Misc.getBrightPlayerColor(),
                        AI_PORTRAIT_SIZE,
                        AI_PORTRAIT_SIZE,
                        0f);
                itemPanel.addUIElement(aiButtonHolder).inTL(aiX, aiPortraitY);

                TooltipMakerAPI aiPortrait = itemPanel.createUIElement(AI_PORTRAIT_SIZE, AI_PORTRAIT_SIZE, false);
                aiPortrait.addImage(aiType.portrait, AI_PORTRAIT_SIZE, 0f);
                itemPanel.addUIElement(aiPortrait).inTL(aiX, aiPortraitY);

                String displayName = aiData.customName != null && !aiData.customName.trim().isEmpty()
                        ? aiData.customName
                        : aiType.name;
                String safeDisplayName = escapeTooltipFormat(displayName);
                int level = Moci_ShipboardAIManager.getCurrentLevel(aiData);
                int levelCap = aiType.levelCap;
                int currExp = (int) Moci_ShipboardAIManager.getCurrentExp(aiData);
                int nextExp = (int) Moci_ShipboardAIManager.getNextLevelExp(aiData);
                String safePersonality = escapeTooltipFormat(Moci_ShipboardAIManager.getPersonalityLabel(aiType.personality));
                aiArea.addPara(csFormat("fleet_ai_label", "AI: {value}", "value", safeDisplayName),
                        8f, Misc.getTextColor(), Misc.getHighlightColor(), safeDisplayName);
                aiArea.addPara(csText("fleet_ai_level", "等级: %s/%s"), 4f,
                        new Color[]{AI_BENEFIT_COLOR, Misc.getHighlightColor()},
                        String.valueOf(level), String.valueOf(levelCap));
                aiArea.addPara(csText("fleet_ai_exp", "经验值: %s/%s"), 4f,
                        new Color[]{AI_BENEFIT_COLOR, Misc.getHighlightColor()},
                        String.valueOf(currExp), String.valueOf(nextExp));
                aiArea.addPara(csFormat("fleet_ai_personality", "性格: {value}", "value", safePersonality), 4f,
                        Misc.getTextColor(), Misc.getHighlightColor(), safePersonality);
            }
            itemPanel.addUIElement(aiArea).inTL(aiX + AI_PORTRAIT_SIZE + 8f, yPos + 2f);

            yPos += ENTRY_HEIGHT + opad;
        }

        info.addCustom(itemPanel, 0);
        member = null;
    }

    /**
     * 收集需要显示在管理列表中的舰船（拥有任意系统即可）
     */
    private List<FleetMemberAPI> getTrackedShips(CampaignFleetAPI playerFleet) {
        List<FleetMemberAPI> tracked = new ArrayList<FleetMemberAPI>();
        if (playerFleet == null || playerFleet.getFleetData() == null) {
            return tracked;
        }
        for (FleetMemberAPI ship : playerFleet.getFleetData().getMembersListCopy()) {
            if (ship == null || ship.getVariant() == null) {
                continue;
            }
            boolean hasSquadSystem = hasCombatSquadSystem(ship);
            boolean hasAISystem = Moci_ShipboardAIConfigLoader.hasShipboardAISystem(ship);
            if (hasSquadSystem || hasAISystem) {
                tracked.add(ship);
            }
        }
        return tracked;
    }

    private boolean hasCombatSquadSystem(FleetMemberAPI ship) {
        return ship != null
                && ship.getVariant() != null
                && ship.getVariant().getHullMods() != null
                && ship.getVariant().getHullMods().contains("Moci_CombatSquad");
    }

    private boolean hasEstablishedSquad(PersonAPI squadLeader) {
        if (squadLeader == null || squadLeader.isDefault() || squadLeader.getId() == null) {
            return false;
        }
        String squadName = Moci_PilotManager.getSquadName(squadLeader.getId(), false);
        int squadSize = Moci_PilotManager.getSquadSize(squadLeader.getId());
        return (squadName != null && !squadName.trim().isEmpty()) || squadSize > 0;
    }

    private void logUnformedSquadDiagnostics(FleetMemberAPI ship, String entryPoint) {
        PersonAPI captain = ship != null ? ship.getCaptain() : null;
        boolean hasCombatSquad = hasCombatSquadSystem(ship);
        String captainId = captain != null ? captain.getId() : null;
        String squadName = captainId != null ? Moci_PilotManager.getSquadName(captainId, true) : null;
        int squadSize = captainId != null ? Moci_PilotManager.getSquadSize(captainId) : 0;
        boolean pilotSystemEnabled = Moci_CombatSquadPilotConfig.isPilotSystemEnabled();
        boolean dynamicPilotsEnabled = Moci_CombatSquadPilotConfig.useDynamicPilots();
        boolean formed = hasCombatSquad
                && captain != null
                && !captain.isDefault()
                && ((squadName != null && !squadName.trim().isEmpty()) || squadSize > 0);
        if (formed) {
            return;
        }

        List<String> reasons = new ArrayList<String>();
        if (ship == null) {
            reasons.add("舰船未找到");
        } else if (!hasCombatSquad) {
            reasons.add("未安装Moci_CombatSquad船插");
        }
        if (captain == null) {
            reasons.add("舰船没有舰长");
        } else {
            if (captain.isDefault()) {
                reasons.add("舰长是默认军官");
            }
            if (captainId == null) {
                reasons.add("舰长ID为空");
            } else {
                if (!pilotSystemEnabled) {
                    reasons.add("机师系统已关闭，不会创建持久化编队");
                } else if (!dynamicPilotsEnabled) {
                    reasons.add("动态机师模式已关闭，使用默认小队，不创建军官专属编队");
                }
                if (squadName == null || squadName.trim().isEmpty()) {
                    reasons.add("持久化编队名称为空");
                }
                if (squadSize <= 0) {
                    reasons.add("持久化编队人数为0");
                }
            }
        }

        String hullId = ship != null && ship.getHullSpec() != null ? ship.getHullSpec().getHullId() : null;
        String variantId = ship != null && ship.getVariant() != null ? ship.getVariant().getHullVariantId() : null;
        Global.getLogger(this.getClass()).info("[MOCI_COMBAT_SQUAD_INTEL] 未成立小队诊断"
                + " | entryPoint=" + entryPoint
                + " | shipId=" + safeLogValue(ship != null ? ship.getId() : null)
                + " | shipName=" + safeLogValue(ship != null ? ship.getShipName() : null)
                + " | hullId=" + safeLogValue(hullId)
                + " | variantId=" + safeLogValue(variantId)
                + " | hasCombatSquad=" + hasCombatSquad
                + " | captainId=" + safeLogValue(captainId)
                + " | captainName=" + safeLogValue(captain != null ? captain.getNameString() : null)
                + " | captainIsDefault=" + (captain != null && captain.isDefault())
                + " | pilotSystemEnabled=" + pilotSystemEnabled
                + " | dynamicPilotsEnabled=" + dynamicPilotsEnabled
                + " | squadName=" + safeLogValue(squadName)
                + " | squadSize=" + squadSize
                + " | reasons=" + reasons);
    }

    private boolean isShipboardAICommander(PersonAPI commander) {
        if (commander == null) {
            return false;
        }
        String personId = commander.getId();
        return personId != null && personId.startsWith(SHIPBOARD_AI_PERSON_ID_PREFIX);
    }

    private List<String> getPortraitOptionsForCommander(PersonAPI squadLeader) {
        List<String> portraits = new ArrayList<>();

        if (isShipboardAICommander(squadLeader)) {
            for (String portrait : Moci_CombatSquadConfigLoader.getAllAIPortraits()) {
                if (portrait != null && !portrait.trim().isEmpty() && !portraits.contains(portrait)) {
                    portraits.add(portrait);
                }
            }
            return portraits;
        }

        for (Gender gender : Gender.values()) {
            if (Misc.getCommissionFaction() != null) {
                for (String portrait : Misc.getCommissionFaction().getPortraits(gender).getItems()) {
                    if (!portraits.contains(portrait)) {
                        portraits.add(portrait);
                    }
                }
            }
            for (String portrait : Global.getSector().getPlayerFaction().getPortraits(gender).getItems()) {
                if (!portraits.contains(portrait)) {
                    portraits.add(portrait);
                }
            }
        }
        return portraits;
    }

    private FleetMemberAPI findShipByOfficer(PersonAPI officer) {
        if (officer == null || Global.getSector() == null || Global.getSector().getPlayerFleet() == null) {
            return null;
        }
        for (FleetMemberAPI ship : Global.getSector().getPlayerFleet().getFleetData().getMembersListCopy()) {
            if (ship != null && ship.getCaptain() == officer) {
                return ship;
            }
        }
        return null;
    }

    private FleetMemberAPI findFleetMemberById(String memberId) {
        if (memberId == null || Global.getSector() == null || Global.getSector().getPlayerFleet() == null) {
            return null;
        }
        for (FleetMemberAPI candidate : Global.getSector().getPlayerFleet().getFleetData().getMembersListCopy()) {
            if (candidate != null && memberId.equals(candidate.getId())) {
                return candidate;
            }
        }
        return null;
    }

    private void logAIDetailWarn(String stage, String memberId,
            Moci_ShipboardAIManager.ShipboardAIData data,
            Moci_ShipboardAIConfigLoader.AITypeDefinition type,
            FleetMemberAPI ship,
            String extra) {
        Global.getLogger(this.getClass()).warn(buildAIDetailContext(stage, memberId, data, type, ship, extra));
    }

    private void logAIDetailInfo(String stage, String memberId,
            Moci_ShipboardAIManager.ShipboardAIData data,
            Moci_ShipboardAIConfigLoader.AITypeDefinition type,
            FleetMemberAPI ship,
            String extra) {
        Global.getLogger(this.getClass()).info(buildAIDetailContext(stage, memberId, data, type, ship, extra));
    }

    private void logAIDetailError(String stage, String memberId,
            Moci_ShipboardAIManager.ShipboardAIData data,
            Moci_ShipboardAIConfigLoader.AITypeDefinition type,
            FleetMemberAPI ship,
            Throwable ex) {
        Global.getLogger(this.getClass()).error(buildAIDetailContext(stage, memberId, data, type, ship, null), ex);
    }

    private String buildAIDetailContext(String stage, String memberId,
            Moci_ShipboardAIManager.ShipboardAIData data,
            Moci_ShipboardAIConfigLoader.AITypeDefinition type,
            FleetMemberAPI ship,
            String extra) {
        StringBuilder sb = new StringBuilder(AI_DETAIL_LOG_PREFIX)
                .append(' ')
                .append(stage)
                .append(" | currentTab=").append(currentTab)
                .append(" | requestedMemberId=").append(safeLogValue(memberId))
                .append(" | selectedAiMemberId=").append(safeLogValue(selectedAiMemberId))
                .append(" | selectedSquadShipId=").append(safeLogValue(selectedSquadShipId))
                .append(" | selectingCommanderId=").append(safeLogValue(selectingCommanderId))
                .append(" | selectingShipCommanderId=").append(safeLogValue(selectingShipCommanderId));

        if (ship != null) {
            sb.append(" | shipId=").append(safeLogValue(ship.getId()))
                    .append(" | shipName=").append(safeLogValue(ship.getShipName()))
                    .append(" | hasShipboardAISystem=").append(Moci_ShipboardAIConfigLoader.hasShipboardAISystem(ship));
            if (ship.getHullSpec() != null) {
                sb.append(" | hullId=").append(safeLogValue(ship.getHullSpec().getHullId()));
            }
            if (ship.getCaptain() != null) {
                sb.append(" | captainId=").append(safeLogValue(ship.getCaptain().getId()));
            }
        } else {
            sb.append(" | ship=<null>");
        }

        if (data != null) {
            sb.append(" | data.memberId=").append(safeLogValue(data.memberId))
                    .append(" | data.baseAiTypeId=").append(safeLogValue(data.baseAiTypeId))
                    .append(" | data.currentAiTypeId=").append(safeLogValue(data.currentAiTypeId))
                    .append(" | canonicalCurrentAiTypeId=")
                    .append(safeLogValue(Moci_ShipboardAIConfigLoader.getCanonicalAiTypeId(data.currentAiTypeId)))
                    .append(" | customName=").append(safeLogValue(data.customName))
                    .append(" | experience=").append((int) data.experience)
                    .append(" | learnedSkills=").append(data.learnedSkills != null ? data.learnedSkills.size() : -1)
                    .append(" | eliteSkills=").append(data.eliteSkills != null ? data.eliteSkills.size() : -1)
                    .append(" | learnedAux=").append(data.learnedAuxSkills != null ? data.learnedAuxSkills.size() : -1)
                    .append(" | aiPersonId=")
                    .append(safeLogValue(data.aiPerson != null ? data.aiPerson.getId() : null));
        } else {
            sb.append(" | data=<null>");
        }

        if (type != null) {
            sb.append(" | type.aiId=").append(safeLogValue(type.aiId))
                    .append(" | type.name=").append(safeLogValue(type.name))
                    .append(" | type.portrait=").append(safeLogValue(type.portrait))
                    .append(" | type.levelCap=").append(type.levelCap)
                    .append(" | type.canRename=").append(type.canRename)
                    .append(" | type.personality=").append(safeLogValue(type.personality));
        } else {
            sb.append(" | type=<null>");
        }

        if (extra != null && !extra.trim().isEmpty()) {
            sb.append(" | ").append(extra.trim());
        }

        return sb.toString();
    }

    private String safeLogValue(String value) {
        return value == null ? "<null>" : value;
    }

    private void addAIDetailRenderError(TooltipMakerAPI info, float pad) {
        info.addPara(aiUiText("intel_render_error", "舰载AI详情页渲染失败，请查看日志。"),
                pad, Misc.getNegativeHighlightColor());
        info.addButton(aiUiText("intel_button_back", "返回"), "back_to_fleet", 120, TAB_BUTTON_HEIGHT, 10f);
    }

    /**
     * 创建编队详情视图
     */
    protected void createSquadView(CustomPanelAPI panel, TooltipMakerAPI info, float width, PersonAPI squadLeader) {
        float pad = 3;
        float opad = 10;
        Color h = Misc.getHighlightColor();
        if (squadLeader == null || squadLeader.getId() == null)
            return;
        String commanderId = squadLeader.getId();

        // 编队名称修改区域
        List<Object> squadParams = new ArrayList<Object>();
        squadParams.add(squadLeader);
        TextFieldAPI squadName = info.addTextField(200f, pad);
        String currentSquadName = Moci_PilotManager.getSquadName(commanderId, true);
        if (currentSquadName != null && !currentSquadName.isEmpty()) {
            squadName.setText(currentSquadName);
        }
        squadParams.add(squadName);
        ButtonAPI renameBtn = info.addButton(csText("squad_button_rename", "重命名编队"), squadParams, 160, 20, opad);

        int numItems = Moci_PilotManager.getSquadSize(commanderId);

        if (numItems <= 0) {
            info.addPara(csText("squad_empty", "此编队暂无机师成员。"), pad, Misc.getGrayColor());
            return;
        }

        // 七列布局设计
        float shipWidth = 60; // 第0列：舰船图标
        float portraitWidth = 80; // 第1列：头像
        float infoWidth = 150; // 第2列：基本信息（姓名、呼号、等级）
        float skillWidth = 110; // 第3列：技能显示
        float labelWidth = 80; // 第4列：标签文字（修改姓名:、修改呼号:）
        float inputWidth = 200; // 第5列：输入框
        float buttonWidth = 100; // 第6列：按钮
        float colGap = 12; // 列间距

        // 每个机师的垂直间距
        float heightPerItem = ENTRY_HEIGHT + 80;
        float itemPanelHeight = heightPerItem * numItems;

        // 计算总宽度
        float totalWidth = shipWidth + portraitWidth + infoWidth + skillWidth + labelWidth + inputWidth + buttonWidth
                + (colGap * 7) + 20;
        CustomPanelAPI itemPanel = panel.createCustomPanel(totalWidth, itemPanelHeight, null);
        float yPos = opad;

        for (int i = 0; i < numItems; i++) {
            PersonAPI pilot = Moci_PilotManager.getPilot(commanderId, i, true);
            String callsign = Moci_PilotManager.getCallsign(commanderId, i, true);

            if (pilot == null)
                continue;
            float currentX = 4;

            // 第0列：舰船图标（简化版）
            createSimpleShipColumn(itemPanel, squadLeader, i, currentX, yPos, shipWidth, ENTRY_HEIGHT);
            currentX += shipWidth + colGap;

            // 第1列：头像
            TooltipMakerAPI portraitColumn = itemPanel.createUIElement(portraitWidth, ENTRY_HEIGHT, false);
            portraitColumn.addImage(pilot.getPortraitSprite(), ENTRY_HEIGHT, 0);
            itemPanel.addUIElement(portraitColumn).inTL(currentX, yPos);

            TooltipMakerAPI portraitBtnHolder = itemPanel.createUIElement(portraitWidth, 20, false);
            ButtonAPI portraitBtn = portraitBtnHolder.addButton(csText("pilot_button_portrait", "换头像"), i, (int) portraitWidth - 4, 16, 0);
            itemPanel.addUIElement(portraitBtnHolder).inTL(currentX, yPos + ENTRY_HEIGHT + 4);
            currentX += portraitWidth + colGap;

            // 第2列：基本信息（姓名、呼号、等级）+ 经验条
            TooltipMakerAPI infoColumn = itemPanel.createUIElement(infoWidth, ENTRY_HEIGHT + 40, true);
            infoColumn.setParaFontDefault();
            infoColumn.addPara(csFormat("pilot_label_name", "姓名: {value}", "value", pilot.getName().getFullName()),
                    pad, h, pilot.getName().getFullName());

            if (callsign == null || callsign.isEmpty()) {
                callsign = csFormat("pilot_default_callsign", "机师-{index}", "index", String.valueOf(i + 1));
            }
            infoColumn.addPara(csFormat("pilot_label_callsign", "呼号: {value}", "value", callsign), pad, h, callsign);

            int level = pilot.getStats().getLevel();
            float experience = Moci_PilotManager.getExperience(commanderId, i, true);
            String levelText = String.valueOf(level);
            infoColumn.addPara(csFormat("pilot_label_level", "等级: {value}", "value", levelText), pad, h, levelText);

            // 经验条显示
            float nextLevelExp = getExperienceForLevel(level + 1);
            float currentLevelExp = getExperienceForLevel(level);
            float progressRatio = Math.min(1.0f,
                    Math.max(0.0f, (experience - currentLevelExp) / (nextLevelExp - currentLevelExp)));

            // 检查是否可以升级
            boolean canLevelUp = experience >= nextLevelExp && level < 7;
            Color expColor = canLevelUp ? Misc.getHighlightColor() : Misc.getGrayColor();

            infoColumn.addPara(csFormat("pilot_label_exp", "经验: {current} / {next}",
                    "current", String.format("%.0f", experience), "next", String.format("%.0f", nextLevelExp)),
                    2f, expColor);

            itemPanel.addUIElement(infoColumn).inTL(currentX, yPos);
            currentX += infoWidth + colGap;

            // 第3列：技能显示
            TooltipMakerAPI skillColumn = itemPanel.createUIElement(skillWidth, ENTRY_HEIGHT + 40, true);
            skillColumn.setParaFontDefault();
            skillColumn.addPara(csText("pilot_section_skills", "技能:"), 5f, h);

            try {
                com.fs.starfarer.api.characters.MutableCharacterStatsAPI stats = pilot.getStats();
                java.util.List<com.fs.starfarer.api.characters.MutableCharacterStatsAPI.SkillLevelAPI> skillList = new java.util.ArrayList<>(
                        stats.getSkillsCopy());

                int eliteSkills = Misc.getNumEliteSkills(pilot);
                int maxElite = Moci_PilotTraining.calculatePilotMaxEliteSkills(pilot);
                skillColumn.addPara(csFormat("pilot_label_elite_skills", "精英技能: {current} / {max}",
                        "current", String.valueOf(eliteSkills), "max", String.valueOf(maxElite)), 2f, h, "" + eliteSkills);

                int displayedSkills = 0;
                for (com.fs.starfarer.api.characters.MutableCharacterStatsAPI.SkillLevelAPI skillLevel : skillList) {
                    if (displayedSkills >= 7)
                        break;
                    if (skillLevel.getLevel() <= 0)
                        continue;
                    try {
                        String skillId = skillLevel.getSkill().getId();
                        String skillName = skillLevel.getSkill().getName();
                        float skillLvl = skillLevel.getLevel();
                        boolean isElite = skillLvl >= 2;

                        String skillText = skillName;
                        Color skillColor = isElite ? Misc.getPositiveHighlightColor() : Misc.getTextColor();

                        if (isElite) {
                            skillColumn.addPara(skillText, 2f, skillColor, skillText);
                        } else {
                            skillColumn.addPara(skillText, 2f, skillColor);
                        }
                        displayedSkills++;
                    } catch (Exception skillEx) {
                        // 忽略无效技能
                    }
                }

                if (displayedSkills == 0) {
                    skillColumn.addPara(csText("pilot_no_skills", "无技能"), 2f, Misc.getGrayColor());
                }
            } catch (Exception ex) {
                skillColumn.addPara(csText("pilot_skill_error", "技能数据错误"), 2f, Misc.getGrayColor());
            }

            itemPanel.addUIElement(skillColumn).inTL(currentX, yPos);
            currentX += skillWidth + colGap;

            // 第4列：标签文字
            TooltipMakerAPI labelColumn = itemPanel.createUIElement(labelWidth, ENTRY_HEIGHT, true);
            labelColumn.setParaFontDefault();
            labelColumn.addPara(csText("pilot_edit_name", "修改姓名:"), 20, h);
            labelColumn.addPara(csText("pilot_edit_callsign", "修改呼号:"), 15, h);

            itemPanel.addUIElement(labelColumn).inTL(currentX, yPos);
            currentX += labelWidth + colGap;

            // 第5列：输入框区域
            TooltipMakerAPI inputColumn = itemPanel.createUIElement(inputWidth, ENTRY_HEIGHT, true);
            inputColumn.setParaFontDefault();

            // 姓名输入 - 姓和名在同一行，但放在输入框列中
            TooltipMakerAPI nameInputHolder = itemPanel.createUIElement(inputWidth, 20, false);
            TextFieldAPI lastNameField = nameInputHolder.addTextField(90f, 0); // 增加姓输入框宽度到90像素
            lastNameField.setText(pilot.getName().getLast());
            itemPanel.addUIElement(nameInputHolder).inTL(currentX, yPos + 18);

            TooltipMakerAPI firstNameHolder = itemPanel.createUIElement(90f, 20, false); // 增加名输入框容器宽度
            TextFieldAPI firstNameField = firstNameHolder.addTextField(90f, 0); // 增加名输入框宽度到90像素
            firstNameField.setText(pilot.getName().getFirst());
            itemPanel.addUIElement(firstNameHolder).inTL(currentX + 100, yPos + 18); // 调整位置，增加间距

            // 呼号输入框
            TooltipMakerAPI callsignInputHolder = itemPanel.createUIElement(inputWidth, 20, false);
            TextFieldAPI callsignField = callsignInputHolder.addTextField(inputWidth - 10, 0); // 减少边距，增加可用宽度
            if (callsign != null)
                callsignField.setText(callsign);
            itemPanel.addUIElement(callsignInputHolder).inTL(currentX, yPos + 50);

            currentX += inputWidth + colGap;

            // 第6列：按钮区域 - 与输入框对齐
            // 舰船指派按钮 - 第一行
            TooltipMakerAPI shipAssignHolder = itemPanel.createUIElement(buttonWidth, 20, false);
            List<Object> assignParams = new ArrayList<>();
            assignParams.add(commanderId);
            assignParams.add(i);
            assignParams.add("assign_ship");
            ButtonAPI assignBtn = shipAssignHolder.addButton(csText("pilot_button_assign_ship", "指派舰船"), assignParams, (int) buttonWidth - 4, 16, 0);
            itemPanel.addUIElement(shipAssignHolder).inTL(currentX, yPos); // 第一行位置

            // 修改姓名按钮
            TooltipMakerAPI nameButtonHolder = itemPanel.createUIElement(buttonWidth, 20, false);
            List<Object> nameParams = new ArrayList<>();
            nameParams.add(commanderId);
            nameParams.add(i);
            nameParams.add(lastNameField);
            nameParams.add(firstNameField);
            ButtonAPI nameBtn = nameButtonHolder.addButton(csText("pilot_button_rename_name", "修改姓名"), nameParams, (int) buttonWidth - 4, 16, 0);
            itemPanel.addUIElement(nameButtonHolder).inTL(currentX, yPos + 18); // 与姓名输入框对齐

            // 修改呼号按钮
            TooltipMakerAPI callsignButtonHolder = itemPanel.createUIElement(buttonWidth, 20, false);
            List<Object> callsignParams = new ArrayList<Object>();
            callsignParams.add(squadLeader);
            callsignParams.add(i);
            callsignParams.add(callsignField);
            ButtonAPI callsignBtn = callsignButtonHolder.addButton(csText("pilot_button_rename_callsign", "修改呼号"), callsignParams, (int) buttonWidth - 4, 16,
                    0);
            itemPanel.addUIElement(callsignButtonHolder).inTL(currentX, yPos + 50); // 与呼号输入框对齐

            // 升级按钮 - 放在与换头像按钮的同一行（第三行）
            // 重新检查升级条件
            boolean canUpgrade = experience >= nextLevelExp && level < 7;
            if (canUpgrade) {
                TooltipMakerAPI upgradeBtnHolder = itemPanel.createUIElement(buttonWidth, 20, false);
                List<Object> upgradeParams = new ArrayList<>();
                upgradeParams.add(commanderId);
                upgradeParams.add(i);
                upgradeParams.add("skill_selection"); // 改为技能选择模式
                ButtonAPI upgradeBtn = upgradeBtnHolder.addButton(csText("pilot_button_upgrade", "升级"), upgradeParams, (int) buttonWidth - 4, 16, 0);
                itemPanel.addUIElement(upgradeBtnHolder).inTL(currentX, yPos + 80); // 第三行位置
            }

            // 精英化按钮 - 在升级按钮下方（第四行）
            // 检查是否有精英化点数可用
            int maxElite = Moci_PilotTraining.calculatePilotMaxEliteSkills(pilot);
            int currentEliteSkills = com.fs.starfarer.api.util.Misc.getNumEliteSkills(pilot);
            boolean canElite = currentEliteSkills < maxElite;

            if (canElite) {
                TooltipMakerAPI eliteBtnHolder = itemPanel.createUIElement(buttonWidth, 20, false);
                List<Object> eliteParams = new ArrayList<>();
                eliteParams.add(commanderId);
                eliteParams.add(i);
                eliteParams.add("elite_selection"); // 精英化选择模式
                ButtonAPI eliteBtn = eliteBtnHolder.addButton(csText("pilot_button_elite", "精英化"), eliteParams, (int) buttonWidth - 4, 16, 0);
                itemPanel.addUIElement(eliteBtnHolder).inTL(currentX, yPos + 104); // 第四行位置
            }

            yPos += heightPerItem; // 使用新的增大间距
        }

        info.addCustom(itemPanel, 0);

        // 头像选择面板
        FactionAPI fc = getFactionForUIColors();
        Color base = fc.getBaseUIColor();
        Color bg = fc.getDarkUIColor();
        Color bright = fc.getBrightUIColor();

        if (selectingPortraitIdx != null) {
            info.addPara(csText("portrait_select_prompt", "请选择新头像："), 10f, Misc.getHighlightColor());

            // Shipboard AI 指挥官使用独立头像池，普通指挥官保持原画像池
            List<String> portraits = getPortraitOptionsForCommander(squadLeader);

            int cols = 11;
            int rows = (int) Math.ceil(portraits.size() / (float) cols);
            float portraitSize = 70f;
            float portraitGap = 6f;

            float gridWidth = cols * (portraitSize + portraitGap);
            float gridHeight = rows * (portraitSize + portraitGap);
            CustomPanelAPI portraitPanel = panel.createCustomPanel(gridWidth, gridHeight, null);

            float yGridPos = 0;
            int portraitIndex = 0;

            for (int r = 0; r < rows; r++) {
                float xGridPos = 0;
                for (int c = 0; c < cols; c++) {
                    if (portraitIndex >= portraits.size())
                        break;

                    String portrait = portraits.get(portraitIndex);

                    // 可点击的头像按钮
                    TooltipMakerAPI portraitContainer = portraitPanel.createUIElement(portraitSize, portraitSize,
                            false);
                    List<Object> params = new ArrayList<>();
                    params.add(squadLeader);
                    params.add(selectingPortraitIdx);
                    params.add(portrait);

                    ButtonAPI portraitBtn = portraitContainer.addAreaCheckbox("", params,
                            Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(), Misc.getBrightPlayerColor(),
                            (int) portraitSize, (int) portraitSize, 0);

                    portraitPanel.addUIElement(portraitContainer).inTL(xGridPos, yGridPos);

                    // 在按钮上叠加头像图片
                    TooltipMakerAPI portraitImage = portraitPanel.createUIElement(portraitSize, portraitSize, false);
                    portraitImage.addImage(portrait, portraitSize, 0);
                    portraitPanel.addUIElement(portraitImage).inTL(xGridPos, yGridPos);

                    xGridPos += portraitSize + portraitGap;
                    portraitIndex++;
                }
                yGridPos += portraitSize + portraitGap;
            }

            info.addCustom(portraitPanel, 10);
            info.addButton(csText("common_cancel", "取消"), "portrait_cancel", 80, TAB_BUTTON_HEIGHT, 10);
        }

        // 技能选择面板
        if (selectingSkillIdx != null) {
            PersonAPI upgradePilot = Moci_PilotManager.getPilot(selectingCommanderId, selectingSkillIdx, true);
            if (upgradePilot != null) {
                if (eliteOnlyMode) {
                    // 纯精英化模式：只显示可精英化的技能
                    info.addPara(csFormat("upgrade_prompt_elite", "机师 {name} 精英化！请选择要精英化的技能：",
                            "name", upgradePilot.getName().getFullName()), 10f, Misc.getHighlightColor());
                } else {
                    // 升级模式：显示学习新技能和精英化现有技能
                    info.addPara(csFormat("upgrade_prompt_learn", "机师 {name} 升级！请选择要学习的技能：",
                            "name", upgradePilot.getName().getFullName()), 10f, Misc.getHighlightColor());
                }

                // 获取可用技能列表（只在非精英化模式显示）
                List<String> availableSkills = getAvailableSkills(upgradePilot);

                if (!eliteOnlyMode && !availableSkills.isEmpty()) {
                    // 计算网格布局
                    int cols = 8; // 8列显示技能图标
                    int rows = (int) Math.ceil(availableSkills.size() / (float) cols);
                    float skillIconSize = 64f; // 技能图标大小
                    float skillNameHeight = 20f; // 技能名称高度
                    float skillCellWidth = skillIconSize + 10f; // 每个技能单元宽度
                    float skillCellHeight = skillIconSize + skillNameHeight + 5f; // 每个技能单元高度
                    float skillGap = 10f;

                    float gridWidth = cols * (skillCellWidth + skillGap);
                    float gridHeight = rows * (skillCellHeight + skillGap);
                    CustomPanelAPI skillPanel = panel.createCustomPanel(gridWidth, gridHeight, null);

                    float yGridPos = 0;
                    int skillIndex = 0;

                    for (int r = 0; r < rows; r++) {
                        float xGridPos = 0;
                        for (int c = 0; c < cols; c++) {
                            if (skillIndex >= availableSkills.size())
                                break;

                            String skillId = availableSkills.get(skillIndex);
                            String skillName = Global.getSettings().getSkillSpec(skillId).getName();
                            String skillIcon = Global.getSettings().getSkillSpec(skillId).getSpriteName();

                            // 技能图标按钮
                            TooltipMakerAPI skillContainer = skillPanel.createUIElement(skillCellWidth, skillCellHeight,
                                    false);

                            // 创建技能图标按钮
                            List<Object> params = new ArrayList<>();
                            params.add(selectingCommanderId);
                            params.add(selectingSkillIdx);
                            params.add(skillId);
                            params.add("learn_skill");

                            ButtonAPI skillBtn = skillContainer.addAreaCheckbox("", params,
                                    Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(), Misc.getBrightPlayerColor(),
                                    (int) skillIconSize, (int) skillIconSize, 0);

                            skillPanel.addUIElement(skillContainer).inTL(xGridPos, yGridPos);

                            // 在按钮上叠加技能图标
                            TooltipMakerAPI skillIconHolder = skillPanel.createUIElement(skillIconSize, skillIconSize,
                                    false);
                            skillIconHolder.addImage(skillIcon, skillIconSize, 0);
                            skillPanel.addUIElement(skillIconHolder).inTL(xGridPos, yGridPos);

                            // 技能名称（居中显示）
                            TooltipMakerAPI skillNameHolder = skillPanel.createUIElement(skillCellWidth,
                                    skillNameHeight, false);
                            skillNameHolder.addSectionHeading(skillName, Alignment.MID, 0);
                            skillPanel.addUIElement(skillNameHolder).inTL(xGridPos, yGridPos + skillIconSize + 2);

                            xGridPos += skillCellWidth + skillGap;
                            skillIndex++;
                        }
                        yGridPos += skillCellHeight + skillGap;
                    }

                    info.addCustom(skillPanel, 10);
                }

                // 检查是否可以精英化现有技能
                List<String> eliteableSkills = getEliteableSkills(upgradePilot);
                if (!eliteableSkills.isEmpty()) {
                    if (eliteOnlyMode) {
                        // 纯精英化模式：使用技能图标显示可精英化技能
                        int eliteCols = 8; // 8列显示精英技能图标
                        int eliteRows = (int) Math.ceil(eliteableSkills.size() / (float) eliteCols);
                        float eliteIconSize = 64f; // 精英技能图标大小
                        float eliteNameHeight = 30f; // 精英技能名称高度（包含"精英化"前缀）
                        float eliteCellWidth = eliteIconSize + 10f; // 每个精英技能单元宽度
                        float eliteCellHeight = eliteIconSize + eliteNameHeight + 5f; // 每个精英技能单元高度
                        float eliteGap = 10f;

                        float eliteGridWidth = eliteCols * (eliteCellWidth + eliteGap);
                        float eliteGridHeight = eliteRows * (eliteCellHeight + eliteGap);
                        CustomPanelAPI elitePanel = panel.createCustomPanel(eliteGridWidth, eliteGridHeight, null);

                        float yElitePos = 0;
                        int eliteIndex = 0;

                        for (int r = 0; r < eliteRows; r++) {
                            float xElitePos = 0;
                            for (int c = 0; c < eliteCols; c++) {
                                if (eliteIndex >= eliteableSkills.size())
                                    break;

                                String skillId = eliteableSkills.get(eliteIndex);
                                String skillName = Global.getSettings().getSkillSpec(skillId).getName();
                                String skillIcon = Global.getSettings().getSkillSpec(skillId).getSpriteName();

                                // 精英技能图标按钮
                                TooltipMakerAPI eliteContainer = elitePanel.createUIElement(eliteCellWidth,
                                        eliteCellHeight, false);

                                // 创建精英技能图标按钮
                                List<Object> eliteParams = new ArrayList<>();
                                eliteParams.add(selectingCommanderId);
                                eliteParams.add(selectingSkillIdx);
                                eliteParams.add(skillId);
                                eliteParams.add("elite_only");

                                ButtonAPI eliteBtn = eliteContainer.addAreaCheckbox("", eliteParams,
                                        Misc.getPositiveHighlightColor(), Misc.getDarkPlayerColor(),
                                        Misc.getBrightPlayerColor(),
                                        (int) eliteIconSize, (int) eliteIconSize, 0);

                                elitePanel.addUIElement(eliteContainer).inTL(xElitePos, yElitePos);

                                // 在按钮上叠加技能图标（带金色边框表示精英化）
                                TooltipMakerAPI eliteIconHolder = elitePanel.createUIElement(eliteIconSize,
                                        eliteIconSize, false);
                                eliteIconHolder.addImage(skillIcon, eliteIconSize, 0);
                                elitePanel.addUIElement(eliteIconHolder).inTL(xElitePos, yElitePos);

                                // 精英技能名称
                                TooltipMakerAPI eliteNameHolder = elitePanel.createUIElement(eliteCellWidth,
                                        eliteNameHeight, false);
                                eliteNameHolder.addSectionHeading(skillName, Alignment.MID, 0);
                                eliteNameHolder.addPara("  " + csText("upgrade_elite_tag", "精英化"), 0,
                                        Misc.getPositiveHighlightColor(), csText("upgrade_elite_tag", "精英化"));
                                elitePanel.addUIElement(eliteNameHolder).inTL(xElitePos, yElitePos + eliteIconSize + 2);

                                xElitePos += eliteCellWidth + eliteGap;
                                eliteIndex++;
                            }
                            yElitePos += eliteCellHeight + eliteGap;
                        }

                        info.addCustom(elitePanel, 10);
                    } else {
                        // 升级模式：作为额外选项显示，使用技能图标
                        info.addPara(csText("upgrade_elite_alt", "或选择精英化现有技能："), 5f, Misc.getHighlightColor());

                        int eliteCols = 8; // 8列显示精英技能图标
                        int eliteRows = (int) Math.ceil(eliteableSkills.size() / (float) eliteCols);
                        float eliteIconSize = 48f; // 升级模式下使用较小的图标
                        float eliteNameHeight = 25f; // 精英技能名称高度
                        float eliteCellWidth = eliteIconSize + 8f; // 每个精英技能单元宽度
                        float eliteCellHeight = eliteIconSize + eliteNameHeight + 3f; // 每个精英技能单元高度
                        float eliteGap = 8f;

                        float eliteGridWidth = eliteCols * (eliteCellWidth + eliteGap);
                        float eliteGridHeight = eliteRows * (eliteCellHeight + eliteGap);
                        CustomPanelAPI elitePanel = panel.createCustomPanel(eliteGridWidth, eliteGridHeight, null);

                        float yElitePos = 0;
                        int eliteIndex = 0;

                        for (int r = 0; r < eliteRows; r++) {
                            float xElitePos = 0;
                            for (int c = 0; c < eliteCols; c++) {
                                if (eliteIndex >= eliteableSkills.size())
                                    break;

                                String skillId = eliteableSkills.get(eliteIndex);
                                String skillName = Global.getSettings().getSkillSpec(skillId).getName();
                                String skillIcon = Global.getSettings().getSkillSpec(skillId).getSpriteName();

                                // 精英技能图标按钮
                                TooltipMakerAPI eliteContainer = elitePanel.createUIElement(eliteCellWidth,
                                        eliteCellHeight, false);

                                // 创建精英技能图标按钮
                                List<Object> eliteParams = new ArrayList<>();
                                eliteParams.add(selectingCommanderId);
                                eliteParams.add(selectingSkillIdx);
                                eliteParams.add(skillId);
                                eliteParams.add("elite_only");

                                ButtonAPI eliteBtn = eliteContainer.addAreaCheckbox("", eliteParams,
                                        Misc.getPositiveHighlightColor(), Misc.getDarkPlayerColor(),
                                        Misc.getBrightPlayerColor(),
                                        (int) eliteIconSize, (int) eliteIconSize, 0);

                                elitePanel.addUIElement(eliteContainer).inTL(xElitePos, yElitePos);

                                // 在按钮上叠加技能图标
                                TooltipMakerAPI eliteIconHolder = elitePanel.createUIElement(eliteIconSize,
                                        eliteIconSize, false);
                                eliteIconHolder.addImage(skillIcon, eliteIconSize, 0);
                                elitePanel.addUIElement(eliteIconHolder).inTL(xElitePos, yElitePos);

                                // 精英技能名称
                                TooltipMakerAPI eliteNameHolder = elitePanel.createUIElement(eliteCellWidth,
                                        eliteNameHeight, false);
                                eliteNameHolder.addSectionHeading(skillName, Alignment.MID, 0);
                                eliteNameHolder.addPara("★" + csText("upgrade_elite_tag", "精英化"), 0,
                                        Misc.getPositiveHighlightColor(), "★" + csText("upgrade_elite_tag", "精英化"));
                                elitePanel.addUIElement(eliteNameHolder).inTL(xElitePos, yElitePos + eliteIconSize + 2);

                                xElitePos += eliteCellWidth + eliteGap;
                                eliteIndex++;
                            }
                            yElitePos += eliteCellHeight + eliteGap;
                        }

                        info.addCustom(elitePanel, 5);
                    }
                } else if (eliteOnlyMode) {
                    // 精英化模式但没有可精英化技能
                    info.addPara(csText("upgrade_no_elite", "该机师暂无可精英化的技能"), 5f, Misc.getGrayColor());
                }

                String cancelText = eliteOnlyMode
                        ? csText("upgrade_cancel_elite", "取消精英化")
                        : csText("upgrade_cancel_upgrade", "取消升级");
                info.addButton(cancelText, "skill_cancel", 80, TAB_BUTTON_HEIGHT, 10);
            }
        }

        // 返回按钮
        info.addButton(csText("common_back", "返回"), "back_to_fleet", base, bg, 120, TAB_BUTTON_HEIGHT, 10);

    }

    /**
     * 显示当前选中舰船的小队详情（无小队时显示提示信息）
     */
    private void createSelectedShipSquadView(CustomPanelAPI panel, TooltipMakerAPI info, float width, float opad, Color h) {
        FleetMemberAPI ship = findFleetMemberById(selectedSquadShipId);
        if (ship == null) {
            info.addPara(csText("detail_missing_ship", "目标舰船不存在，可能已离开舰队。"), 8f, Misc.getNegativeHighlightColor());
            info.addButton(csText("common_back", "返回"), "back_to_fleet", 120, TAB_BUTTON_HEIGHT, 10f);
            return;
        }

        String shipName = ship.getShipName();
        String safeShipName = escapeTooltipFormat(shipName == null ? csText("detail_unnamed_ship", "未命名舰船") : shipName);
        info.addPara(csFormat("detail_ship_label", "舰船: {value}", "value", safeShipName), opad, h, safeShipName);

        PersonAPI squadLeader = ship.getCaptain();
        boolean hasShipSquad = hasCombatSquadSystem(ship)
                && squadLeader != null
                && !squadLeader.isDefault()
                && hasEstablishedSquad(squadLeader);
        if (!hasShipSquad) {
            info.addPara(csText("detail_no_squad", "该舰船当前未成立小队。"), 8f, Misc.getGrayColor());
            info.addPara(csFormat("detail_no_squad_hint", "请先安装 {mod} 并分配非默认军官，随后在战役中完成小队初始化。",
                    "mod", "CombatSquad 船插"), 4f, h, "CombatSquad 船插");
            info.addButton(csText("common_back", "返回"), "back_to_fleet", 120, TAB_BUTTON_HEIGHT, 10f);
            return;
        }

        member = squadLeader;
        String squadName = Moci_PilotManager.getSquadName(member.getId(), true);
        if (squadName != null && !squadName.isEmpty()) {
            String safeSquadName = escapeTooltipFormat(squadName);
            info.addPara(csFormat("detail_title_named", "编队详情: {value}", "value", safeSquadName), 6f, h, safeSquadName);
        } else {
            String safeCommanderName = escapeTooltipFormat(member.getNameString());
            info.addPara(csFormat("detail_title_by_commander", "编队详情: {value}的编队",
                    "value", safeCommanderName), 6f, h, safeCommanderName);
        }
        createSquadView(panel, info, width, member);
    }

    /**
     * 创建舰载AI详情视图
     */
    private void createAIDetailView(CustomPanelAPI panel, TooltipMakerAPI info, float width, float height, String memberId) {
        FleetMemberAPI ship = null;
        Moci_ShipboardAIManager.ShipboardAIData data = null;
        Moci_ShipboardAIConfigLoader.AITypeDefinition type = null;

        try {
            logAIDetailInfo("render_start", memberId, null, null, null,
                    "panelWidth=" + width + " | panelHeight=" + height);
            ship = findFleetMemberById(memberId);
            if (ship == null) {
                logAIDetailWarn("render_missing_ship", memberId, null, null, null, null);
                info.addPara(aiUiText("intel_missing_ship", "目标舰船不存在，可能已离开舰队。"),
                        8f, Misc.getNegativeHighlightColor());
                info.addButton(aiUiText("intel_button_back", "返回"), "back_to_fleet", 120, TAB_BUTTON_HEIGHT, 10f);
                return;
            }

            data = Moci_ShipboardAIManager.getOrCreateData(ship);
            if (data == null) {
                logAIDetailWarn("render_missing_data", memberId, null, null, ship,
                        "hasShipboardAISystem=" + Moci_ShipboardAIConfigLoader.hasShipboardAISystem(ship));
                info.addPara(aiUiText("intel_missing_no_ai", "该舰船未安装舰载AI系统。"),
                        8f, Misc.getNegativeHighlightColor());
                info.addButton(aiUiText("intel_button_back", "返回"), "back_to_fleet", 120, TAB_BUTTON_HEIGHT, 10f);
                return;
            }

            type = Moci_ShipboardAIConfigLoader.getAiType(data.currentAiTypeId);
            if (type == null) {
                logAIDetailWarn("render_missing_type", memberId, data, null, ship, null);
                info.addPara(aiUiText("intel_missing_type", "舰载AI类型配置缺失。"),
                        8f, Misc.getNegativeHighlightColor());
                info.addButton(aiUiText("intel_button_back", "返回"), "back_to_fleet", 120, TAB_BUTTON_HEIGHT, 10f);
                return;
            }

            String displayName = data.customName != null && !data.customName.trim().isEmpty() ? data.customName : type.name;
            int level = Moci_ShipboardAIManager.getCurrentLevel(data);
            float exp = Moci_ShipboardAIManager.getCurrentExp(data);
            float nextExp = Moci_ShipboardAIManager.getNextLevelExp(data);
            String safeShipName = escapeTooltipFormat(ship.getShipName());
            String safeDisplayName = escapeTooltipFormat(displayName);
            String safePersonality = escapeTooltipFormat(Moci_ShipboardAIManager.getPersonalityLabel(type.personality));

            float sectionPad = 10f;
            float sectionHeaderPad = 6f;
            float sectionContentPad = 6f;

            int normalPick = Moci_ShipboardAIManager.getAvailableSkillPickCount(data, type);
            int elitePick = Moci_ShipboardAIManager.getAvailableElitePickCount(data, type);
            int auxPick = Moci_ShipboardAIManager.getAvailableAuxPickCount(data, type);

            List<String> normalOptions = Moci_ShipboardAIManager.getAvailableSkillOptions(ship);
            List<String> eliteOptions = Moci_ShipboardAIManager.getAvailableEliteSkillOptions(ship);
            List<String> auxOptions = Moci_ShipboardAIManager.getAvailableAuxOptions(ship);
            List<String> evolutionOptions = Moci_ShipboardAIManager.getAvailableEvolutionOptions(ship);
            logAIDetailInfo("render_context_ready", memberId, data, type, ship,
                    "normalPick=" + normalPick
                            + " | elitePick=" + elitePick
                            + " | auxPick=" + auxPick
                            + " | normalOptions=" + normalOptions.size()
                            + " | eliteOptions=" + eliteOptions.size()
                            + " | auxOptions=" + auxOptions.size()
                            + " | evolutionOptions=" + evolutionOptions.size());

            float columnsWidth = Math.max(320f, width - 24f);
            float columnGap = 12f;
            float preferredRightColumnWidth = 236f;
            float preferredLeftColumnWidth = 420f;
            float minWidthForFixedColumns = 252f + preferredRightColumnWidth + columnGap;
            float rightColumnWidth;
            float leftColumnWidth;
            if (columnsWidth >= minWidthForFixedColumns) {
                // Keep the action column close to the info column and leave room for future content on the right.
                rightColumnWidth = preferredRightColumnWidth;
                leftColumnWidth = Math.min(preferredLeftColumnWidth, columnsWidth - rightColumnWidth - columnGap);
            } else {
                rightColumnWidth = Math.min(340f, columnsWidth * 0.38f);
                leftColumnWidth = columnsWidth - rightColumnWidth - columnGap;
            }
            CustomPanelAPI columnsPanel = panel.createCustomPanel(columnsWidth, 1f, null);
            TooltipMakerAPI leftColumn = columnsPanel.createUIElement(leftColumnWidth, 0f, false);
            TooltipMakerAPI rightColumn = columnsPanel.createUIElement(rightColumnWidth, 0f, false);

            leftColumn.addPara(aiFormat("intel_ai_detail_title", "舰载AI详情: {value}", "value", safeShipName),
                    8f, Misc.getHighlightColor(), safeShipName);
            TooltipMakerAPI head = leftColumn.beginImageWithText(type.portrait, 80f);
            head.addPara(aiFormat("intel_detail_title_name", "名称: {value}", "value", safeDisplayName),
                    0f, Misc.getTextColor(), Misc.getHighlightColor(), safeDisplayName);
            head.addPara(aiUiText("intel_detail_title_level", "等级: %s/%s"), 2f,
                    new Color[]{AI_BENEFIT_COLOR, Misc.getHighlightColor()},
                    String.valueOf(level), String.valueOf(type.levelCap));
            head.addPara(aiUiText("intel_detail_title_exp", "经验: %s/%s"), 2f,
                    new Color[]{AI_BENEFIT_COLOR, Misc.getHighlightColor()},
                    String.valueOf((int) exp), String.valueOf((int) nextExp));
            head.addPara(aiFormat("intel_detail_title_personality", "性格: {value}", "value", safePersonality),
                    2f, Misc.getTextColor(), Misc.getHighlightColor(), safePersonality);
            leftColumn.addImageWithText(8f);

            if (type.canRename) {
                TextFieldAPI textField = leftColumn.addTextField(240f, 4f);
                textField.setText(displayName);
                List<Object> renameParams = new ArrayList<Object>();
                renameParams.add("ai_rename");
                renameParams.add(ship.getId());
                renameParams.add(textField);
                leftColumn.addButton(aiUiText("intel_button_rename", "修改名称"), renameParams, 120, 22, 4f);
            }

            leftColumn.addPara("----------------------------------------", sectionPad, Misc.getGrayColor());
            leftColumn.addPara(aiUiText("intel_section_skills", "技能"), sectionHeaderPad, Misc.getHighlightColor(),
                    aiUiText("intel_section_skills", "技能"));
            if (data.learnedSkills.isEmpty()) {
                leftColumn.addPara(aiUiText("intel_none", "无"), sectionContentPad, Misc.getGrayColor());
            } else {
                for (String skillId : data.learnedSkills) {
                    String skillName = getShipboardAISkillName(skillId);
                    boolean elite = data.eliteSkills.contains(skillId);
                    Color color = elite ? AI_SKILL_ELITE_COLOR : AI_SKILL_NORMAL_COLOR;
                    String safeSkillName = escapeTooltipFormat(skillName);
                    leftColumn.addPara(safeSkillName + (elite ? aiUiText("tooltip_elite_suffix", " [精英]") : ""),
                            sectionContentPad, color, safeSkillName);
                }
            }

            leftColumn.addPara("----------------------------------------", sectionPad, Misc.getGrayColor());
            leftColumn.addPara(aiUiText("intel_section_aux", "辅助效果"), sectionHeaderPad, Misc.getHighlightColor(),
                    aiUiText("intel_section_aux", "辅助效果"));
            if (data.learnedAuxSkills.isEmpty()) {
                leftColumn.addPara(aiUiText("intel_none", "无"), sectionContentPad, Misc.getGrayColor());
            } else {
                for (String auxId : data.learnedAuxSkills) {
                    String auxName = getShipboardAIAuxEffectDisplayName(auxId);
                    float auxLevel = (data.eliteSkills != null && data.eliteSkills.contains(auxId)) ? 2f : 1f;
                    addAIAuxEffectLine(leftColumn, auxId, auxName, auxLevel, sectionContentPad);
                }
            }

            rightColumn.addPara(aiFormat("intel_available_points", "可用点数: 技能 {normal} / 精英 {elite} / 辅助 {aux}",
                    "normal", String.valueOf(normalPick),
                    "elite", String.valueOf(elitePick),
                    "aux", String.valueOf(auxPick)),
                    8f, Misc.getTextColor(), Misc.getHighlightColor(),
                    String.valueOf(normalPick), String.valueOf(elitePick), String.valueOf(auxPick));

            boolean hasActionSection = false;
            if (AI_DEBUG_MODE) {
                rightColumn.addPara(aiUiText("intel_debug_button_hint", "学习/精英化/辅助按钮仅在有剩余次数且有可选项时显示。"),
                        4f, Misc.getGrayColor());
            }

            if (AI_DEBUG_MODE || (normalPick > 0 && !normalOptions.isEmpty())) {
                hasActionSection = true;
                rightColumn.addPara("----------------------------------------", sectionPad, Misc.getGrayColor());
                rightColumn.addPara(aiFormat("intel_available_skill_heading", "可学习技能（剩余 {count} 次）",
                        "count", String.valueOf(normalPick)), sectionHeaderPad, Misc.getHighlightColor(), "" + normalPick);
                if (normalPick > 0 && !normalOptions.isEmpty()) {
                    addAIDetailSkillButtons(rightColumn, "ai_learn", ship.getId(), normalOptions);
                } else if (AI_DEBUG_MODE) {
                    rightColumn.addPara(aiUiText("intel_available_skill_empty", "当前暂无可学习技能。"),
                            sectionContentPad, Misc.getGrayColor());
                }
            }

            if (AI_DEBUG_MODE || (elitePick > 0 && !eliteOptions.isEmpty())) {
                hasActionSection = true;
                rightColumn.addPara("----------------------------------------", sectionPad, Misc.getGrayColor());
                rightColumn.addPara(aiFormat("intel_available_elite_heading", "可精英化技能（剩余 {count} 次）",
                        "count", String.valueOf(elitePick)), sectionHeaderPad, Misc.getHighlightColor(), "" + elitePick);
                if (elitePick > 0 && !eliteOptions.isEmpty()) {
                    addAIDetailSkillButtons(rightColumn, "ai_elite", ship.getId(), eliteOptions);
                } else if (AI_DEBUG_MODE) {
                    rightColumn.addPara(aiUiText("intel_available_elite_empty", "当前暂无可精英化技能。"),
                            sectionContentPad, Misc.getGrayColor());
                }
            }

            if (AI_DEBUG_MODE || (auxPick > 0 && !auxOptions.isEmpty())) {
                hasActionSection = true;
                rightColumn.addPara("----------------------------------------", sectionPad, Misc.getGrayColor());
                rightColumn.addPara(aiFormat("intel_available_aux_heading", "可学习辅助效果（剩余 {count} 次）",
                        "count", String.valueOf(auxPick)), sectionHeaderPad, Misc.getHighlightColor(), "" + auxPick);
                if (auxPick > 0 && !auxOptions.isEmpty()) {
                    addAIDetailSkillButtons(rightColumn, "ai_aux", ship.getId(), auxOptions);
                } else if (AI_DEBUG_MODE) {
                    rightColumn.addPara(aiUiText("intel_available_aux_empty", "当前暂无可学习辅助效果。"),
                            sectionContentPad, Misc.getGrayColor());
                }
            }

            if (!evolutionOptions.isEmpty()) {
                hasActionSection = true;
                rightColumn.addPara("----------------------------------------", sectionPad, Misc.getGrayColor());
                rightColumn.addPara(aiUiText("intel_evolution_options", "可选进化路线"), 4f, Misc.getHighlightColor(),
                        aiUiText("intel_evolution_options", "可选进化路线"));
                for (String targetId : evolutionOptions) {
                    Moci_ShipboardAIConfigLoader.AITypeDefinition evolveType =
                            Moci_ShipboardAIConfigLoader.getAiType(targetId);
                    if (evolveType == null) {
                        continue;
                    }
                    List<Object> evolveParams = new ArrayList<Object>();
                    evolveParams.add("ai_evolve");
                    evolveParams.add(ship.getId());
                    evolveParams.add(targetId);
                    rightColumn.addButton(aiFormat("intel_evolution_button", "进化为 {name}",
                            "name", escapeTooltipFormat(evolveType.name)), evolveParams, 220, 24, 4f);
                }
            } else if (type.hasEvolutionRoute) {
                hasActionSection = true;
                rightColumn.addPara("----------------------------------------", sectionPad, Misc.getGrayColor());
                rightColumn.addPara(aiFormat("intel_evolution_requirement", "进化条件：达到等级 {level}",
                        "level", String.valueOf(type.evolutionLevel)), 4f, Misc.getGrayColor());
            }

            if (!hasActionSection) {
                rightColumn.addPara(aiUiText("intel_no_actions", "当前暂无可执行操作。"), sectionPad, Misc.getGrayColor());
            }

            rightColumn.addButton(aiUiText("intel_button_back", "返回"), "back_to_fleet", 120, TAB_BUTTON_HEIGHT, sectionPad);
            float columnsHeight = Math.max(420f,
                    Math.max(leftColumn.getHeightSoFar(), rightColumn.getHeightSoFar()) + 8f);
            columnsPanel.getPosition().setSize(columnsWidth, columnsHeight);
            leftColumn.setHeightSoFar(columnsHeight);
            rightColumn.setHeightSoFar(columnsHeight);
            columnsPanel.addUIElement(leftColumn).inTL(0f, 0f);
            columnsPanel.addUIElement(rightColumn).inTL(leftColumnWidth + columnGap, 0f);
            info.addCustom(columnsPanel, sectionPad);
            logAIDetailInfo("render_complete", memberId, data, type, ship,
                    "columnsWidth=" + columnsWidth
                            + " | columnsHeight=" + columnsHeight
                            + " | leftColumnWidth=" + leftColumnWidth
                            + " | rightColumnWidth=" + rightColumnWidth
                            + " | hasActionSection=" + hasActionSection);
        } catch (Exception ex) {
            logAIDetailError("render_exception", memberId, data, type, ship, ex);
            addAIDetailRenderError(info, 8f);
        }
    }

    private void addAIDetailSkillButtons(TooltipMakerAPI info, String action, String memberId, List<String> skillIds) {
        int count = 0;
        for (String skillId : skillIds) {
            if (count >= 18) {
                break;
            }
            List<Object> params = new ArrayList<Object>();
            params.add(action);
            params.add(memberId);
            params.add(skillId);
            String displayName = "ai_aux".equals(action)
                    ? getShipboardAIAuxEffectDisplayName(skillId)
                    : getShipboardAISkillName(skillId);
            info.addButton(escapeTooltipFormat(displayName), params, 220, 20, 2f);
            if ("ai_aux".equals(action)) {
                String safeDesc = escapeTooltipFormat(Moci_ShipboardAIAuxEffectRegistry.getEffectDescription(skillId, 1f));
                info.addPara(safeDesc,
                        2f, Misc.getGrayColor());
            }
            count++;
        }
    }

    private void addAIAuxEffectLine(TooltipMakerAPI info, String auxId, String auxName, float level, float pad) {
        String visibleAuxName = auxName == null ? "" : auxName;
        String desc = Moci_ShipboardAIAuxEffectRegistry.getEffectDescription(auxId, level);
        info.addPara(aiUiText("intel_aux_effect_line", "%s %s：%s"), pad,
                new Color[]{AI_BENEFIT_COLOR, Misc.getHighlightColor(), Misc.getTextColor()},
                "#", visibleAuxName, desc);
    }

    private String getShipboardAISkillName(String skillId) {
        try {
            return Global.getSettings().getSkillSpec(skillId).getName();
        } catch (Exception ex) {
            return skillId;
        }
    }

    private String getShipboardAIAuxEffectDisplayName(String skillId) {
        return Moci_ShipboardAIAuxEffectRegistry.getDisplayName(skillId);
    }

    private boolean handleShipboardAIAction(List<?> params, IntelUIAPI ui) {
        if (params == null || params.isEmpty() || !(params.get(0) instanceof String)) {
            return false;
        }
        String action = (String) params.get(0);

        if ("ai_learn".equals(action) || "ai_elite".equals(action) || "ai_aux".equals(action)) {
            if (params.size() < 3 || !(params.get(1) instanceof String) || !(params.get(2) instanceof String)) {
                return true;
            }
            FleetMemberAPI ship = findFleetMemberById((String) params.get(1));
            String skillId = (String) params.get(2);
            boolean success = false;
            if ("ai_learn".equals(action)) {
                success = Moci_ShipboardAIManager.learnSkill(ship, skillId);
            } else if ("ai_elite".equals(action)) {
                success = Moci_ShipboardAIManager.eliteSkill(ship, skillId);
            } else if ("ai_aux".equals(action)) {
                success = Moci_ShipboardAIManager.learnAuxEffect(ship, skillId);
            }

            if (success) {
                String displayName = "ai_aux".equals(action)
                        ? getShipboardAIAuxEffectDisplayName(skillId)
                        : getShipboardAISkillName(skillId);
                Global.getSector().getCampaignUI().addMessage(
                        aiFormat("intel_msg_action_success", "舰载AI操作成功: {value}", "value", displayName),
                        Misc.getPositiveHighlightColor());
            } else {
                Global.getSector().getCampaignUI().addMessage(
                        aiUiText("intel_msg_action_fail", "舰载AI操作失败"), Misc.getNegativeHighlightColor());
            }
            ui.updateUIForItem(this);
            return true;
        }

        if ("ai_rename".equals(action)) {
            if (params.size() < 3 || !(params.get(1) instanceof String) || !(params.get(2) instanceof TextFieldAPI)) {
                return true;
            }
            FleetMemberAPI ship = findFleetMemberById((String) params.get(1));
            TextFieldAPI textField = (TextFieldAPI) params.get(2);
            boolean success = Moci_ShipboardAIManager.renameAI(ship, textField.getText());
            if (success) {
                Global.getSector().getCampaignUI().addMessage(
                        aiUiText("intel_msg_rename_success", "舰载AI名称已更新"), Misc.getPositiveHighlightColor());
            } else {
                Global.getSector().getCampaignUI().addMessage(
                        aiUiText("intel_msg_rename_fail", "舰载AI名称更新失败"), Misc.getNegativeHighlightColor());
            }
            ui.updateUIForItem(this);
            return true;
        }

        if ("ai_evolve".equals(action)) {
            if (params.size() < 2 || !(params.get(1) instanceof String)) {
                return true;
            }
            FleetMemberAPI ship = findFleetMemberById((String) params.get(1));
            String targetAiId = params.size() >= 3 && params.get(2) instanceof String ? (String) params.get(2) : null;
            boolean success = targetAiId == null
                    ? Moci_ShipboardAIManager.evolveAI(ship)
                    : Moci_ShipboardAIManager.evolveAI(ship, targetAiId);
            if (success) {
                Global.getSector().getCampaignUI().addMessage(
                        aiUiText("intel_msg_evolve_success", "舰载AI进化完成"), Misc.getHighlightColor());
            } else {
                Global.getSector().getCampaignUI().addMessage(
                        aiUiText("intel_msg_evolve_fail", "舰载AI进化失败"), Misc.getNegativeHighlightColor());
            }
            ui.updateUIForItem(this);
            return true;
        }

        return false;
    }

    private String escapeTooltipFormat(String text) {
        return text == null ? "" : text.replace("%", "%%");
    }

    private String aiUiText(String key, String fallback) {
        return Moci_ShipboardAITextRegistry.getUiText(key, fallback);
    }

    private String aiFormat(String key, String fallback, String... replacements) {
        return Moci_ShipboardAITextRegistry.formatTemplate(aiUiText(key, fallback),
                Moci_ShipboardAITextRegistry.mapOf(replacements));
    }

    private String csText(String key, String fallback) {
        return Moci_CombatSquadTextRegistry.getText(key, fallback);
    }

    private String csFormat(String key, String fallback, String... replacements) {
        return Moci_CombatSquadTextRegistry.format(key, fallback, replacements);
    }

    /**
     * 获取机师可学习的技能列表
     */
    private List<String> getAvailableSkills(PersonAPI pilot) {
        List<String> availableSkills = new ArrayList<>();
        List<String> allSkills = new ArrayList<>();

        // 参考骤雨wingcom的技能列表
        allSkills.add(com.fs.starfarer.api.impl.campaign.ids.Skills.COMBAT_ENDURANCE);
        allSkills.add(com.fs.starfarer.api.impl.campaign.ids.Skills.HELMSMANSHIP);
        allSkills.add(com.fs.starfarer.api.impl.campaign.ids.Skills.ENERGY_WEAPON_MASTERY);
        allSkills.add(com.fs.starfarer.api.impl.campaign.ids.Skills.BALLISTIC_MASTERY);
        allSkills.add(com.fs.starfarer.api.impl.campaign.ids.Skills.FIELD_MODULATION);
        allSkills.add(com.fs.starfarer.api.impl.campaign.ids.Skills.TARGET_ANALYSIS);
        allSkills.add(com.fs.starfarer.api.impl.campaign.ids.Skills.IMPACT_MITIGATION);
        allSkills.add(com.fs.starfarer.api.impl.campaign.ids.Skills.DAMAGE_CONTROL);
        allSkills.add(com.fs.starfarer.api.impl.campaign.ids.Skills.POLARIZED_ARMOR);
        allSkills.add(com.fs.starfarer.api.impl.campaign.ids.Skills.POINT_DEFENSE);
        allSkills.add(com.fs.starfarer.api.impl.campaign.ids.Skills.MISSILE_SPECIALIZATION);
        allSkills.add(com.fs.starfarer.api.impl.campaign.ids.Skills.SYSTEMS_EXPERTISE);

        // 只返回机师还没有学习的技能
        for (String skillId : allSkills) {
            if (!pilot.getStats().hasSkill(skillId)) {
                availableSkills.add(skillId);
            }
        }

        return availableSkills;
    }

    /**
     * 获取可以精英化的技能列表
     */
    private List<String> getEliteableSkills(PersonAPI pilot) {
        List<String> eliteableSkills = new ArrayList<>();

        // 计算精英技能上限
        int maxElite = Moci_PilotTraining.calculatePilotMaxEliteSkills(pilot);
        int currentEliteSkills = com.fs.starfarer.api.util.Misc.getNumEliteSkills(pilot);

        // 如果已达到精英技能上限，不能再精英化
        if (currentEliteSkills >= maxElite) {
            return eliteableSkills;
        }

        // 检查所有已学习但未精英化的技能
        for (Object skillObj : pilot.getStats().getSkillsCopy()) {
            try {
                com.fs.starfarer.api.characters.MutableCharacterStatsAPI.SkillLevelAPI skill = (com.fs.starfarer.api.characters.MutableCharacterStatsAPI.SkillLevelAPI) skillObj;

                // 只处理已学会（level >= 1）但未精英化（level < 2）的技能
                if (skill.getLevel() >= 1 && skill.getLevel() < 2) {
                    String skillId = skill.getSkill().getId();
                    // 确保技能ID不为空且是有效的技能（而不是技能类别）
                    if (skillId != null && !skillId.isEmpty() &&
                            Global.getSettings().getSkillSpec(skillId) != null) {
                        eliteableSkills.add(skillId);
                    }
                }
            } catch (Exception e) {
                // 忽略无效技能项目
                continue;
            }
        }

        return eliteableSkills;
    }

    /**
     * 获取僚机驾驶的舰船Hull ID（支持自定义舰船）
     * 优先使用自定义配置，无自定义时使用默认配置
     */
    private String getWingmanShipHull(PersonAPI squadLeader, int wingmanIndex) {
        try {
            String commanderId = squadLeader.getId();

            // 1. 优先检查自定义舰船配置
            Moci_CustomWingmanManager.CustomWingmanShip customShip = Moci_CustomWingmanManager
                    .getCustomWingmanShip(commanderId, wingmanIndex);

            if (customShip != null) {
                return customShip.hullId;
            }

            // 2. 使用默认配置
            FleetMemberAPI commanderShip = findShipByOfficer(squadLeader);
            if (commanderShip != null) {
                String commanderHull = commanderShip.getHullId();

                // 使用getDParentHullId()获取基础船体ID，这样可以正确匹配损坏版本的船只
                String baseHullId = commanderShip.getHullSpec().getDParentHullId();
                if (baseHullId == null) {
                    baseHullId = commanderHull; // 备用方案：如果不是D-hull则使用getHullId()
                }

                // 从CombatSquadOfficierManager获取编队配置
                List<Moci_CombatSquadOfficierManager.SquadMember> squadConfig = Moci_CombatSquadOfficierManager.SQUAD_CONFIGS
                        .get(baseHullId);

                if (squadConfig != null && !squadConfig.isEmpty()) {
                    // 根据wingmanIndex获取对应的僚机配置
                    int memberIndex = wingmanIndex % squadConfig.size(); // 循环使用配置
                    Moci_CombatSquadOfficierManager.SquadMember member = squadConfig.get(memberIndex);

                    String wingmanVariantId = member.variantId;

                    // 从变体ID推导Hull ID
                    String wingmanHullId = extractHullIdFromVariant(wingmanVariantId);

                    return wingmanHullId;
                } else {
                    return getDefaultWingmanHull(commanderHull);
                }
            } else {
                return "Moci_MS_06"; // 默认扎古
            }
        } catch (Exception e) {
            return "Moci_MS_06"; // 默认扎古
        }
    }

    /**
     * 从变体ID推导Hull ID - 使用标准的D-hull处理方式
     */
    private String extractHullIdFromVariant(String variantId) {
        if (variantId == null || variantId.isEmpty()) {
            return "Moci_MS_06"; // 默认
        }

        try {
            // 尝试创建临时舰船来获取正确的Hull ID
            FleetMemberAPI tempShip = Global.getFactory().createFleetMember(
                com.fs.starfarer.api.fleet.FleetMemberType.SHIP, variantId);
            
            // 使用标准的D-hull处理方式 - 与Moci_Pa_HiryuSystemPlasma一致
            String baseHullId = tempShip.getHullSpec().getDParentHullId();
            if (baseHullId == null) {
                baseHullId = tempShip.getHullSpec().getHullId();
            }

            return baseHullId;

        } catch (Exception e) {
            // 如果创建舰船失败，使用传统的字符串解析方法作为后备
            // 传统解析方法：变体ID通常格式为 HullId_VariantSuffix
            int underscoreIndex = variantId.lastIndexOf('_');
            if (underscoreIndex > 0) {
                return variantId.substring(0, underscoreIndex);
            } else {
                return variantId;
            }
        }
    }

    /**
     * 基于指挥官舰船类型的默认僚机Hull推断（后备方案）
     */
    private String getDefaultWingmanHull(String commanderHull) {

        if (commanderHull.contains("MS_06") || commanderHull.toLowerCase().contains("zaku")) {
            return "Moci_MS_06"; // 扎古系列
        } else if (commanderHull.contains("MS_14")) {
            return "Moci_MS_14"; // 勇士系列
        } else if (commanderHull.contains("RGM_89")) {
            return "Moci_RGM_89"; // RGM-89系列
        } else if (commanderHull.contains("AMS_119")) {
            return "Moci_AMS_119"; // AMS-119系列
        } else if (commanderHull.contains("RX_78")) {
            return "Moci_RGM_86"; // 高达系列配吉姆
        } else if (commanderHull.contains("RGM_79")) {
            return "Moci_RGM_79"; // 吉姆系列
        } else if (commanderHull.contains("MSN_03")) {
            return "Moci_MSN_03"; // 乍得多加系列
        } else if (commanderHull.contains("moci_") || commanderHull.contains("Moci_")) {
            return "Moci_MS_06"; // 其他Moci系列默认扎古
        } else {
            return "Moci_MS_06"; // 最终默认
        }
    }

    /**
     * 为指定Hull创建舰船列表用于显示
     * 
     * @deprecated 建议使用 {@link #createShipListForDisplay(Moci_CustomWingmanManager.CustomWingmanShip, String)} 
     *             来直接使用已存储的变体信息
     */
    @Deprecated
    private List<FleetMemberAPI> getShipListForHull(String hullId) {

        List<FleetMemberAPI> shipList = new ArrayList<>();
        try {
            // 智能变体尝试顺序 - 优先尝试_Base（用于skin舰船）和_Standard
            String[] variants = {
                    hullId,
                    hullId + "_Base",        // 优先：skin舰船的基础变体
                    hullId + "_Standard",
                    hullId + "_standard",
                    hullId + "_Basic",
                    hullId + "_Normal",
                    hullId + "_Default",
                    hullId + "_Fighter",
                    hullId + "_Support",
                    hullId + "_Strike",
                    hullId + "_Gunner",
                    hullId + "_Rocket",
                    hullId + "_Cannon"
            };

            // 首先尝试从已有的SQUAD_CONFIGS中查找实际使用的变体
            String preferredVariant = findVariantFromSquadConfigs(hullId);
            if (preferredVariant != null) {
                try {
                    FleetMemberAPI preferredShip = Global.getFactory().createFleetMember(
                            com.fs.starfarer.api.fleet.FleetMemberType.SHIP, preferredVariant);
                    shipList.add(preferredShip);
                    return shipList;
                } catch (Exception e) {
                }
            }

            // 按顺序尝试各种变体
            for (String variant : variants) {
                try {
                    FleetMemberAPI tempShip = Global.getFactory().createFleetMember(
                            com.fs.starfarer.api.fleet.FleetMemberType.SHIP, variant);

                    // 验证创建的舰船是否正确
                    String actualHull = tempShip.getHullId();
                    String actualName = tempShip.getHullSpec().getHullName();

                    // 严格的Hull ID匹配验证
                    if (isValidHullMatch(hullId, actualHull)) {
                        shipList.add(tempShip);
                        return shipList;
                    } else {
                        continue;
                    }
                } catch (Exception e) {
                    continue;
                }
            }

            // 如果所有Moci变体都失败，尝试使用同类型的替代Moci舰船
            String[] fallbackMochiShips = getMochiShipFallbacks(hullId);
            if (fallbackMochiShips.length > 0) {
                for (String fallback : fallbackMochiShips) {
                    if (tryCreateShipVariant(fallback, shipList)) {
                        return shipList;
                    }
                }
            }

        } catch (Exception e) {
            // 忽略错误，返回空列表
        }
        return shipList;
    }

    /**
     * 创建用于显示的舰船列表（优化版本）
     * 
     * 优先使用已存储的精确变体信息，避免猜测变体ID。
     * 
     * @param customShip 自定义舰船配置（如果有）
     * @param fallbackHullId 后备Hull ID（当customShip为null时使用）
     * @return 包含一个舰船的列表，用于UI显示
     */
    private List<FleetMemberAPI> createShipListForDisplay(
            Moci_CustomWingmanManager.CustomWingmanShip customShip, 
            String fallbackHullId) {
        
        List<FleetMemberAPI> shipList = new ArrayList<>();
        
        try {
            if (customShip != null) {
                // 优先使用已存储的变体配置对象
                if (customShip.variantConfig != null) {
                    FleetMemberAPI ship = Global.getFactory().createFleetMember(
                        com.fs.starfarer.api.fleet.FleetMemberType.SHIP, 
                        customShip.variantConfig
                    );
                    shipList.add(ship);
                    return shipList;
                }
                
                // 其次使用已存储的变体ID
                if (customShip.variantId != null && !customShip.variantId.isEmpty()) {
                    try {
                        FleetMemberAPI ship = Global.getFactory().createFleetMember(
                            com.fs.starfarer.api.fleet.FleetMemberType.SHIP, 
                            customShip.variantId
                        );
                        shipList.add(ship);
                        return shipList;
                    } catch (Exception e) {
                        // 继续尝试使用 Hull ID 作为后备显示方案
                    }
                }
                
                // 最后才使用Hull ID（需要猜测变体）
                if (customShip.hullId != null && !customShip.hullId.isEmpty()) {
                    return getShipListForHull(customShip.hullId);
                }
            }
            
            // 如果没有自定义舰船，使用后备Hull ID
            if (fallbackHullId != null && !fallbackHullId.isEmpty()) {
                return getShipListForHull(fallbackHullId);
            }
            
        } catch (Exception e) {
            Global.getLogger(this.getClass()).error(
                "创建显示舰船失败: " + e.getMessage(), e
            );
        }
        
        return shipList;
    }

    /**
     * 从编队配置中查找使用此Hull ID的变体
     */
    private String findVariantFromSquadConfigs(String hullId) {
        try {
            for (List<Moci_CombatSquadOfficierManager.SquadMember> squad : Moci_CombatSquadOfficierManager.SQUAD_CONFIGS
                    .values()) {
                for (Moci_CombatSquadOfficierManager.SquadMember member : squad) {
                    String variantId = member.variantId;
                    if (variantId != null && extractHullIdFromVariant(variantId).equals(hullId)) {
                        return variantId;
                    }
                }
            }
        } catch (Exception e) {
            // 忽略错误
        }
        return null;
    }

    /**
     * 验证Hull ID是否匹配
     */
    private boolean isValidHullMatch(String expectedHull, String actualHull) {
        if (expectedHull == null || actualHull == null)
            return false;

        // 完全匹配
        if (expectedHull.equals(actualHull))
            return true;

        // 忽略大小写匹配
        if (expectedHull.equalsIgnoreCase(actualHull))
            return true;

        // 检查是否是D-hull版本
        if (actualHull.equals(expectedHull + "_d") || expectedHull.equals(actualHull + "_d"))
            return true;

        if (expectedHull.startsWith("Moci_") && actualHull.startsWith("Moci_")) {
            // 必须是相同的型号前缀
            String expectedPrefix = expectedHull.split("_")[1]; // Moci_MS_06 -> MS
            String actualPrefix = actualHull.split("_")[1];
            return expectedPrefix.equals(actualPrefix);
        }

        return false;
    }

    /**
     * 获取Moci系列舰船的替代方案
     */
    private String[] getMochiShipFallbacks(String hullId) {
        if (hullId.contains("MS_06")) {
            return new String[] { "Moci_MS_06", "Moci_MS_06_Standard", "Moci_MS_06_Normal" };
        } else if (hullId.contains("RGM_79")) {
            return new String[] { "Moci_RGM_79", "Moci_RGM_79_Standard", "Moci_RGM_86" };
        } else if (hullId.contains("RX_78")) {
            return new String[] { "Moci_RX_78", "Moci_RGM_79", "Moci_RGM_86" };
        } else {
            return new String[] { "Moci_MS_06", "Moci_RGM_79" }; // 通用替代
        }
    }

    /**
     * 尝试创建指定变体的舰船
     */
    private boolean tryCreateShipVariant(String variant, List<FleetMemberAPI> shipList) {
        try {
            FleetMemberAPI ship = Global.getFactory().createFleetMember(
                    com.fs.starfarer.api.fleet.FleetMemberType.SHIP, variant);
            shipList.add(ship);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 创建舰船指派列（第0列扩展功能）
     *
     * @param itemPanel   父面板
     * @param squadLeader 编队指挥官
     * @param pilotIndex  机师索引
     * @param currentX    X坐标
     * @param yPos        Y坐标
     * @param shipWidth   列宽度
     * @param entryHeight 条目高度
     */
    private void createShipAssignmentColumn(CustomPanelAPI itemPanel, PersonAPI squadLeader, int pilotIndex,
            float currentX, float yPos, float shipWidth, float entryHeight) {
        String commanderId = squadLeader.getId();

        // 获取当前机师使用的舰船信息
        Moci_CustomWingmanManager.CustomWingmanShip customShip = Moci_CustomWingmanManager
                .getCustomWingmanShip(commanderId, pilotIndex);

        String displayHullId;
        String displayName;
        boolean isCustom = false;

        if (customShip != null) {
            displayHullId = customShip.hullId;
            displayName = customShip.displayName;
            isCustom = true;
        } else {
            displayHullId = getWingmanShipHull(squadLeader, pilotIndex);
            displayName = csText("default_config_name", "默认配置");
            isCustom = false;
        }

        // 舰船图标显示 - 使用优化的方法直接使用已存储的变体信息
        TooltipMakerAPI shipColumn = itemPanel.createUIElement(shipWidth, entryHeight, false);
        List<FleetMemberAPI> shipList = createShipListForDisplay(customShip, displayHullId);
        shipColumn.addShipList(1, 1, shipWidth, Misc.getButtonTextColor(), shipList, 0f);
        itemPanel.addUIElement(shipColumn).inTL(currentX, yPos);

        // 舰船名称显示（带自定义标识）
        TooltipMakerAPI nameHolder = itemPanel.createUIElement(shipWidth + 40, 20, false);
        Color nameColor = isCustom ? Misc.getHighlightColor() : Misc.getTextColor();
        String customTag = csText("ship_custom_tag", "(自定义)");
        String nameText = isCustom ? displayName + " " + customTag : displayName;
        nameHolder.addPara(nameText, 0, nameColor, isCustom ? customTag : "");
        itemPanel.addUIElement(nameHolder).inTL(currentX, yPos + entryHeight + 2);

        // 指派舰船按钮
        TooltipMakerAPI assignBtnHolder = itemPanel.createUIElement(60, 16, false);
        List<Object> assignParams = new ArrayList<>();
        assignParams.add(commanderId);
        assignParams.add(pilotIndex);
        assignParams.add("assign_ship");
        ButtonAPI assignBtn = assignBtnHolder.addButton(csText("ship_button_assign", "指派"), assignParams, 58, 14, 0);
        itemPanel.addUIElement(assignBtnHolder).inTL(currentX, yPos + entryHeight + 22);
    }

    /**
     * 生成标签页按钮
     */
    public TooltipMakerAPI generateTabButton(CustomPanelAPI panel, String nameId, Tab tab,
            Color base, Color bg, Color bright, TooltipMakerAPI rightOf) {
        TooltipMakerAPI holder = panel.createUIElement(TAB_BUTTON_WIDTH, TAB_BUTTON_HEIGHT, false);
        ButtonAPI button = holder.addAreaCheckbox(nameId, tab, base, bg, bright,
                TAB_BUTTON_WIDTH, TAB_BUTTON_HEIGHT, 0);
        button.setChecked(tab == this.currentTab);
        if (rightOf != null) {
            panel.addUIElement(holder).rightOfTop(rightOf, 4);
        } else {
            panel.addUIElement(holder).inTL(0, 3);
        }
        return holder;
    }

    /**
     * 添加标签页按钮
     */
    protected TooltipMakerAPI addTabButtons(TooltipMakerAPI tm, CustomPanelAPI panel, float width) {
        FactionAPI fc = getFactionForUIColors();
        Color base = fc.getBaseUIColor();
        Color bg = fc.getDarkUIColor();
        Color bright = fc.getBrightUIColor();
        TooltipMakerAPI btnHolder1 = generateTabButton(panel, csText("tab_fleet", "编队列表"), BUTTON_FLEET, base, bg, bright, null);
        TooltipMakerAPI btnHolder2 = generateTabButton(panel, csText("tab_help", "使用说明"), Tab.HELP, base, bg, bright, btnHolder1);
        return btnHolder1;
    }

    @Override
    public void createLargeDescription(CustomPanelAPI panel, float width, float height) {
        float opad = 10;
        float pad = 3;
        float contentY = TAB_BUTTON_HEIGHT + 6f;
        float contentHeight = Math.max(100f, height - contentY);
        Color h = Misc.getHighlightColor();
        if (currentTab == null)
            currentTab = Tab.FLEET;
        TooltipMakerAPI info = panel.createUIElement(width, contentHeight, true);
        FactionAPI faction = getFactionForUIColors();
        addTabButtons(info, panel, width);
        try {
            info.addSectionHeading(csText("intel_heading", "CombatSquad编队管理系统"), faction.getBaseUIColor(),
                    faction.getDarkUIColor(), Alignment.MID, opad);

            // 检查是否在舰船选择模式
            if (selectingShipIdx != null && selectingShipCommanderId != null) {
                createShipSelectionPanel(panel, info, width);
            } else {
                switch (currentTab) {
                    case FLEET:
                        createFleetView(panel, info, width);
                        break;
                    case SQUAD:
                        createSelectedShipSquadView(panel, info, width, opad, h);
                        break;
                    case AI_DETAIL:
                        if (selectedAiMemberId == null || selectedAiMemberId.trim().isEmpty()) {
                            logAIDetailWarn("render_without_selected_member_id", selectedAiMemberId, null, null, null, null);
                            addAIDetailRenderError(info, 8f);
                        } else {
                            createAIDetailView(panel, info, width, contentHeight, selectedAiMemberId);
                        }
                        break;
                    case HELP:
                        createHelpView(panel, info, width);
                        break;
                }
            }
        } catch (Exception ex) {
            logAIDetailError("large_description_exception", selectedAiMemberId, null, null, null, ex);
            addAIDetailRenderError(info, 8f);
        }
        panel.addUIElement(info).inTL(0f, contentY);
    }

    @Override
    public void buttonPressConfirmed(Object buttonId, IntelUIAPI ui) {
        // 返回按钮
        if ("back_to_fleet".equals(buttonId)) {
            currentTab = Tab.FLEET;
            selectingPortraitIdx = null;
            selectingSkillIdx = null;
            selectingCommanderId = null;
            selectingShipIdx = null;
            selectingShipCommanderId = null;
            eliteOnlyMode = false;
            selectedSquadShipId = null;
            selectedAiMemberId = null;
            member = null;
            ui.updateUIForItem(this);
            return;
        }
        // 标签页切换
        if (buttonId instanceof Tab) {
            currentTab = (Tab) buttonId;
            selectingPortraitIdx = null;
            selectingSkillIdx = null;
            selectingCommanderId = null;
            selectedSquadShipId = null;
            selectedAiMemberId = null;
            ui.updateUIForItem(this);
            return;
        }
        // 军官管理
        if (buttonId instanceof PersonAPI) {
            PersonAPI officer = (PersonAPI) buttonId;
            member = officer;
            FleetMemberAPI ship = findShipByOfficer(officer);
            selectedSquadShipId = ship != null ? ship.getId() : null;
            logUnformedSquadDiagnostics(ship, "open_by_officer_button");
            selectedAiMemberId = null;
            currentTab = Tab.SQUAD;
            selectingPortraitIdx = null;
            ui.updateUIForItem(this);
            return;
        }
        // 换头像（进入选择面板）
        if (buttonId instanceof Integer && currentTab == Tab.SQUAD) {
            int idx = (int) buttonId;
            selectingPortraitIdx = idx;
            ui.updateUIForItem(this);
            return;
        }
        // 列表入口 & 舰载AI动作
        if (buttonId instanceof List) {
            List<?> params = (List<?>) buttonId;
            if (!params.isEmpty() && params.get(0) instanceof String) {
                String action = (String) params.get(0);
                if ("open_squad".equals(action) && params.size() >= 2 && params.get(1) instanceof String) {
                    selectedSquadShipId = (String) params.get(1);
                    FleetMemberAPI ship = findFleetMemberById(selectedSquadShipId);
                    member = ship != null ? ship.getCaptain() : null;
                    logUnformedSquadDiagnostics(ship, "open_from_fleet_list");
                    selectedAiMemberId = null;
                    currentTab = Tab.SQUAD;
                    selectingPortraitIdx = null;
                    selectingSkillIdx = null;
                    selectingCommanderId = null;
                    selectingShipIdx = null;
                    selectingShipCommanderId = null;
                    eliteOnlyMode = false;
                    ui.updateUIForItem(this);
                    return;
                }
                if ("open_ai_detail".equals(action) && params.size() >= 2 && params.get(1) instanceof String) {
                    String targetMemberId = (String) params.get(1);
                    FleetMemberAPI ship = null;
                    Moci_ShipboardAIManager.ShipboardAIData data = null;
                    Moci_ShipboardAIConfigLoader.AITypeDefinition type = null;
                    boolean canOpen = false;
                    try {
                        ship = findFleetMemberById(targetMemberId);
                        data = ship != null ? Moci_ShipboardAIManager.getOrCreateData(ship) : null;
                        type = data != null ? Moci_ShipboardAIConfigLoader.getAiType(data.currentAiTypeId) : null;
                        if (ship == null) {
                            logAIDetailWarn("open_click_missing_ship", targetMemberId, null, null, null,
                                    "buttonAction=open_ai_detail");
                        } else if (data == null) {
                            logAIDetailWarn("open_click_missing_data", targetMemberId, null, null, ship,
                                    "buttonAction=open_ai_detail");
                        } else if (type == null) {
                            logAIDetailWarn("open_click_missing_type", targetMemberId, data, null, ship,
                                    "buttonAction=open_ai_detail");
                        } else {
                            canOpen = true;
                            logAIDetailInfo("open_click_resolved", targetMemberId, data, type, ship,
                                    "buttonAction=open_ai_detail");
                        }
                    } catch (Exception ex) {
                        logAIDetailError("open_click_exception", targetMemberId, data, type, ship, ex);
                    }
                    if (!canOpen) {
                        selectedAiMemberId = null;
                        currentTab = Tab.FLEET;
                        Global.getSector().getCampaignUI().addMessage(
                                aiUiText("intel_msg_open_detail_fail", "Cannot open shipboard AI detail."), AI_NEGATIVE_COLOR);
                        ui.updateUIForItem(this);
                        return;
                    }
                    selectedAiMemberId = targetMemberId;
                    selectedSquadShipId = null;
                    currentTab = Tab.AI_DETAIL;
                    selectingPortraitIdx = null;
                    selectingSkillIdx = null;
                    selectingCommanderId = null;
                    selectingShipIdx = null;
                    selectingShipCommanderId = null;
                    eliteOnlyMode = false;
                    ui.updateUIForItem(this);
                    return;
                }
            }

            if (handleShipboardAIAction(params, ui)) {
                return;
            }
        }
        // 头像选择、呼号修改、编队重命名、机师姓名修改、技能选择处理
        if (buttonId instanceof List) {
            List<?> params = (List<?>) buttonId;
            // 技能选择：参数为[指挥官ID, 机师索引, "skill_selection"]
            if (params.size() == 3 && params.get(0) instanceof String && params.get(1) instanceof Integer
                    && "skill_selection".equals(params.get(2))) {
                String commanderId = (String) params.get(0);
                int pilotIndex = (Integer) params.get(1);

                // 进入技能选择模式
                selectingSkillIdx = pilotIndex;
                selectingCommanderId = commanderId;
                selectingPortraitIdx = null; // 确保不显示头像选择
                eliteOnlyMode = false; // 重置为正常升级模式
                ui.updateUIForItem(this);
                return;
            }
            // 精英化选择：参数为[指挥官ID, 机师索引, "elite_selection"]
            if (params.size() == 3 && params.get(0) instanceof String && params.get(1) instanceof Integer
                    && "elite_selection".equals(params.get(2))) {
                String commanderId = (String) params.get(0);
                int pilotIndex = (Integer) params.get(1);

                // 进入精英化选择模式（只显示可精英化技能）
                selectingSkillIdx = pilotIndex;
                selectingCommanderId = commanderId;
                selectingPortraitIdx = null; // 确保不显示头像选择
                eliteOnlyMode = true; // 设置为纯精英化模式
                ui.updateUIForItem(this);
                return;
            }
            // 学习技能：参数为[指挥官ID, 机师索引, 技能ID, "learn_skill"]
            if (params.size() == 4 && params.get(0) instanceof String && params.get(1) instanceof Integer &&
                    params.get(2) instanceof String && "learn_skill".equals(params.get(3))) {
                String commanderId = (String) params.get(0);
                int pilotIndex = (Integer) params.get(1);
                String skillId = (String) params.get(2);

                // 执行升级并学习技能
                PersonAPI pilot = Moci_PilotManager.getPilot(commanderId, pilotIndex, true);
                if (pilot != null) {
                    boolean success = upgradeAndLearnSkill(commanderId, pilotIndex, skillId);
                    if (success) {
                        String skillName = Global.getSettings().getSkillSpec(skillId).getName();
                        String callsign = Moci_PilotManager.getCallsign(commanderId, pilotIndex, true);
                        Global.getSector().getCampaignUI().addMessage(
                                csFormat("msg_learn_skill_success",
                                        "机师 {pilot} (呼号: {callsign}) 升级并学习了技能: {skill}",
                                        "pilot", pilot.getName().getFullName(),
                                        "callsign", callsign,
                                        "skill", skillName),
                                com.fs.starfarer.api.util.Misc.getHighlightColor());
                    }
                }

                // 退出技能选择模式
                selectingSkillIdx = null;
                selectingCommanderId = null;
                eliteOnlyMode = false; // 重置模式标记
                ui.updateUIForItem(this);
                return;
            }
            // 精英化技能：参数为[指挥官ID, 机师索引, 技能ID, "elite_skill"]
            if (params.size() == 4 && params.get(0) instanceof String && params.get(1) instanceof Integer &&
                    params.get(2) instanceof String && "elite_skill".equals(params.get(3))) {
                String commanderId = (String) params.get(0);
                int pilotIndex = (Integer) params.get(1);
                String skillId = (String) params.get(2);

                // 执行升级并精英化技能
                PersonAPI pilot = Moci_PilotManager.getPilot(commanderId, pilotIndex, true);
                if (pilot != null) {
                    boolean success = upgradeAndEliteSkill(commanderId, pilotIndex, skillId);
                    if (success) {
                        String skillName = Global.getSettings().getSkillSpec(skillId).getName();
                        String callsign = Moci_PilotManager.getCallsign(commanderId, pilotIndex, true);
                        Global.getSector().getCampaignUI().addMessage(
                                csFormat("msg_elite_skill_success",
                                        "机师 {pilot} (呼号: {callsign}) 升级并精英化了技能: {skill}",
                                        "pilot", pilot.getName().getFullName(),
                                        "callsign", callsign,
                                        "skill", skillName),
                                com.fs.starfarer.api.util.Misc.getHighlightColor());
                    }
                }

                // 退出技能选择模式
                selectingSkillIdx = null;
                selectingCommanderId = null;
                eliteOnlyMode = false; // 重置模式标记
                ui.updateUIForItem(this);
                return;
            }
            // 单独精英化技能：参数为[指挥官ID, 机师索引, 技能ID, "elite_only"]
            if (params.size() == 4 && params.get(0) instanceof String && params.get(1) instanceof Integer &&
                    params.get(2) instanceof String && "elite_only".equals(params.get(3))) {
                String commanderId = (String) params.get(0);
                int pilotIndex = (Integer) params.get(1);
                String skillId = (String) params.get(2);

                // 执行单独精英化（不升级等级）
                PersonAPI pilot = Moci_PilotManager.getPilot(commanderId, pilotIndex, true);
                if (pilot != null) {
                    boolean success = Moci_PilotTraining.elitePilotSkill(commanderId, pilotIndex, skillId, true);
                    if (success) {
                        String skillName = Global.getSettings().getSkillSpec(skillId).getName();
                        String callsign = Moci_PilotManager.getCallsign(commanderId, pilotIndex, true);
                        Global.getSector().getCampaignUI().addMessage(
                                csFormat("msg_elite_only_success",
                                        "机师 {pilot} (呼号: {callsign}) 精英化了技能: {skill}",
                                        "pilot", pilot.getName().getFullName(),
                                        "callsign", callsign,
                                        "skill", skillName),
                                com.fs.starfarer.api.util.Misc.getHighlightColor());
                    }
                }

                // 退出技能选择模式
                selectingSkillIdx = null;
                selectingCommanderId = null;
                eliteOnlyMode = false; // 重置模式标记
                ui.updateUIForItem(this);
                return;
            }
            // 头像选择：参数为[指挥官, 机师索引, 头像路径]
            if (params.size() == 3 && params.get(0) instanceof PersonAPI && params.get(1) instanceof Integer
                    && params.get(2) instanceof String) {
                PersonAPI squadLeader = (PersonAPI) params.get(0);
                int idx = (Integer) params.get(1);
                String portrait = (String) params.get(2);
                // 直接获取机师对象并设置头像
                PersonAPI pilot = Moci_PilotManager.getPilot(squadLeader.getId(), idx, true);
                if (pilot != null) {
                    pilot.setPortraitSprite(portrait);
                }
                selectingPortraitIdx = null;
                ui.updateUIForItem(this);
                return;
            }
            // 呼号修改
            if (params.size() == 3 && params.get(0) instanceof PersonAPI && params.get(1) instanceof Integer
                    && params.get(2) instanceof TextFieldAPI) {
                PersonAPI squadLeader = (PersonAPI) params.get(0);
                int idx = (Integer) params.get(1);
                TextFieldAPI text = (TextFieldAPI) params.get(2);
                String newCallsign = text.getText();
                if (newCallsign != null && !newCallsign.trim().isEmpty()) {
                    Global.getSector().getPersistentData()
                            .put("moci_combatSquad_callsign_" + idx + "_" + squadLeader.getId(), newCallsign.trim());
                }
                ui.updateUIForItem(this);
                return;
            }
            // 编队重命名
            if (params.size() == 2 && params.get(0) instanceof PersonAPI && params.get(1) instanceof TextFieldAPI) {
                PersonAPI squadLeader = (PersonAPI) params.get(0);
                TextFieldAPI newName = (TextFieldAPI) params.get(1);
                String newSquadName = newName.getText();
                if (newSquadName != null && !newSquadName.trim().isEmpty()) {
                    Moci_PilotManager.setSquadName(squadLeader.getId(), newSquadName.trim());
                }
                ui.updateUIForItem(this);
                return;
            }
            // 机师姓名修改：参数为[指挥官ID, 机师索引, 姓Field, 名Field]
            if (params.size() == 4 && params.get(0) instanceof String && params.get(1) instanceof Integer
                    && params.get(2) instanceof TextFieldAPI && params.get(3) instanceof TextFieldAPI) {
                String commanderId = (String) params.get(0);
                int idx = (Integer) params.get(1);
                TextFieldAPI lastNameField = (TextFieldAPI) params.get(2);
                TextFieldAPI firstNameField = (TextFieldAPI) params.get(3);
                String last = lastNameField.getText();
                String first = firstNameField.getText();
                PersonAPI pilot = Moci_PilotManager.getPilot(commanderId, idx, true);
                if (pilot != null && last != null && first != null && !last.trim().isEmpty()
                        && !first.trim().isEmpty()) {
                    pilot.setName(
                            new com.fs.starfarer.api.characters.FullName(first, last, pilot.getName().getGender()));
                }
                ui.updateUIForItem(this);
                return;
            }
        }
        // 头像选择取消
        if ("portrait_cancel".equals(buttonId)) {
            selectingPortraitIdx = null;
            ui.updateUIForItem(this);
            return;
        }

        // 技能选择取消
        if ("skill_cancel".equals(buttonId)) {
            selectingSkillIdx = null;
            selectingCommanderId = null;
            eliteOnlyMode = false; // 重置模式标记
            ui.updateUIForItem(this);
            return;
        }

        // 舰船选择取消
        if ("ship_cancel".equals(buttonId)) {
            selectingShipIdx = null;
            selectingShipCommanderId = null;
            ui.updateUIForItem(this);
            return;
        }

        // 处理列表参数按钮
        if (buttonId instanceof List) {
            List<?> params = (List<?>) buttonId;

            // 舰船还原按钮：参数为[指挥官ID, 机师索引, "restore_ship"]
            if (params.size() == 3 && params.get(0) instanceof String && params.get(1) instanceof Integer
                    && "restore_ship".equals(params.get(2))) {
                String commanderId = (String) params.get(0);
                int pilotIndex = (Integer) params.get(1);

                // 执行还原操作
                try {
                    Moci_CustomWingmanManager.resetToDefault(commanderId, pilotIndex);
                    
                    // 显示成功消息
                    PersonAPI pilot = Moci_PilotManager.getPilot(commanderId, pilotIndex, true);
                    String pilotName = pilot != null ? pilot.getName().getFullName() : csText("pilot_generic", "机师");

                    Global.getSector().getCampaignUI().addMessage(
                            csFormat("msg_restore_success", "{pilot} 的舰船已还原为默认配置，自定义舰船已归还舰队",
                                    "pilot", pilotName),
                            Misc.getPositiveHighlightColor());
                } catch (Exception e) {
                    // 显示失败消息
                    Global.getLogger(this.getClass()).error("舰船还原失败: " + e.getMessage(), e);
                    Global.getSector().getCampaignUI().addMessage(
                            csText("msg_restore_fail", "舰船还原失败"),
                            Misc.getNegativeHighlightColor());
                }

                ui.updateUIForItem(this);
                return;
            }

            // 舰船指派按钮：参数为[指挥官ID, 机师索引, "assign_ship"]
            if (params.size() == 3 && params.get(0) instanceof String && params.get(1) instanceof Integer
                    && "assign_ship".equals(params.get(2))) {
                String commanderId = (String) params.get(0);
                int pilotIndex = (Integer) params.get(1);

                // 进入舰船选择模式
                selectingShipIdx = pilotIndex;
                selectingShipCommanderId = commanderId;
                selectingPortraitIdx = null; // 确保不显示头像选择
                selectingSkillIdx = null; // 确保不显示技能选择
                ui.updateUIForItem(this);
                return;
            }

            // 选择舰船：参数为[指挥官ID, 机师索引, FleetMemberAPI, "select_ship"]
            if (params.size() == 4 && params.get(0) instanceof String && params.get(1) instanceof Integer &&
                    params.get(2) instanceof FleetMemberAPI && "select_ship".equals(params.get(3))) {
                String commanderId = (String) params.get(0);
                int pilotIndex = (Integer) params.get(1);
                FleetMemberAPI ship = (FleetMemberAPI) params.get(2);

                // 指派舰船
                boolean success = Moci_CustomWingmanManager.assignCustomShip(commanderId, pilotIndex, ship);

                if (success) {
                    // 显示成功消息
                    String shipName = ship.getHullSpec().getHullNameWithDashClass();
                    PersonAPI pilot = Moci_PilotManager.getPilot(commanderId, pilotIndex, true);
                    String pilotName = pilot != null ? pilot.getName().getFullName() : csText("pilot_generic", "机师");

                    Global.getSector().getCampaignUI().addMessage(
                            csFormat("msg_assign_success", "已将{ship}指派给{pilot}",
                                    "ship", shipName, "pilot", pilotName),
                            Misc.getPositiveHighlightColor());
                } else {
                    // 显示失败消息
                    Global.getLogger(this.getClass()).warn("舰船指派失败 - 指挥官ID: " + commanderId
                            + ", 机师索引: " + pilotIndex
                            + ", 舰船: " + ship.getHullSpec().getHullNameWithDashClass());
                    Global.getSector().getCampaignUI().addMessage(
                            csText("msg_assign_fail", "舰船指派失败，可能不符合条件"),
                            Misc.getNegativeHighlightColor());
                }

                // 退出舰船选择模式
                selectingShipIdx = null;
                selectingShipCommanderId = null;
                ui.updateUIForItem(this);
                return;
            }
        }

        Global.getLogger(this.getClass()).warn("未处理的按钮事件: " + buttonId);
    }



    @Override
    public boolean doesButtonHaveConfirmDialog(Object buttonId) {
        if (buttonId instanceof String) {
            return "reset_config".equals(buttonId);
        }
        return false;
    }

    @Override
    public String getConfirmText(Object buttonId) {
        if ("reset_config".equals(buttonId)) {
            return csText("confirm_reset", "确认重置所有配置？此操作将清除所有机师数据！");
        }
        return super.getConfirmText(buttonId);
    }

    @Override
    public String getIcon() {
        return "graphics/hullmods/Moci_CombatSquad.png";
    }

    @Override
    public Set<String> getIntelTags(SectorMapAPI map) {
        Set<String> tags = super.getIntelTags(map);
        tags.add(csText("tag_squad_management", "编队管理"));
        tags.add(csText("tag_wing_management", "中队管理"));
        return tags;
    }

    @Override
    public boolean isHidden() {
        return false;
    }

    /**
     * 标签页枚举
     */
    public static enum Tab {
        FLEET, SQUAD, AI_DETAIL, HELP
    }

    /**
     * 升级机师并学习指定技能
     */
    private boolean upgradeAndLearnSkill(String commanderId, int pilotIndex, String skillId) {
        PersonAPI pilot = Moci_PilotManager.getPilot(commanderId, pilotIndex, true);
        if (pilot == null) {
            return false;
        }

        int currentLevel = pilot.getStats().getLevel();
        if (currentLevel >= 7) {
            return false;
        }

        float currentExp = Moci_PilotManager.getExperience(commanderId, pilotIndex, true);
        float requiredExp = getExperienceForLevel(currentLevel + 1);
        if (currentExp < requiredExp) {
            return false;
        }

        int newLevel = currentLevel + 1;
        pilot.getStats().setLevel(newLevel);

        if (!pilot.getStats().hasSkill(skillId)) {
            pilot.getStats().setSkillLevel(skillId, 1);
        }

        float newExp = currentExp - requiredExp;
        try {
            Map<String, Object> storage = Global.getSector().getPersistentData();
            String key = "moci_combatSquad_exp_" + pilotIndex + "_" + commanderId;
            storage.put(key, newExp);
        } catch (Exception e) {
            // 保存失败
        }

        return true;
    }

    /**
     * 升级机师并精英化指定技能
     */
    private boolean upgradeAndEliteSkill(String commanderId, int pilotIndex, String skillId) {
        PersonAPI pilot = Moci_PilotManager.getPilot(commanderId, pilotIndex, true);
        if (pilot == null) {
            return false;
        }

        int currentLevel = pilot.getStats().getLevel();
        if (currentLevel >= 7) {
            return false;
        }

        float currentExp = Moci_PilotManager.getExperience(commanderId, pilotIndex, true);
        float requiredExp = getExperienceForLevel(currentLevel + 1);
        if (currentExp < requiredExp) {
            return false;
        }

        int maxElite = Moci_PilotTraining.calculatePilotMaxEliteSkills(pilot);
        int currentEliteSkills = com.fs.starfarer.api.util.Misc.getNumEliteSkills(pilot);

        if (currentEliteSkills >= maxElite) {
            return false;
        }

        int newLevel = currentLevel + 1;
        pilot.getStats().setLevel(newLevel);

        if (pilot.getStats().hasSkill(skillId) && pilot.getStats().getSkillLevel(skillId) < 2) {
            pilot.getStats().setSkillLevel(skillId, 2);
        }

        float newExp = currentExp - requiredExp;
        try {
            Map<String, Object> storage = Global.getSector().getPersistentData();
            String key = "moci_combatSquad_exp_" + pilotIndex + "_" + commanderId;
            storage.put(key, newExp);
        } catch (Exception e) {
            // 保存失败
        }

        return true;
    }

    /**
     * 创建舰船选择面板
     * 显示可指派给指定机师的舰船列表
     */
    private void createShipSelectionPanel(CustomPanelAPI panel, TooltipMakerAPI info, float width) {
        if (selectingShipIdx == null || selectingShipCommanderId == null)
            return;

        float pad = 3;
        float opad = 10;
        Color h = Misc.getHighlightColor();

        // 获取机师信息
        PersonAPI pilot = Moci_PilotManager.getPilot(selectingShipCommanderId, selectingShipIdx, true);
        String callsign = Moci_PilotManager.getCallsign(selectingShipCommanderId, selectingShipIdx, true);

        if (pilot == null) {
            Global.getLogger(this.getClass()).warn("无法找到机师信息 - 指挥官ID: " + selectingShipCommanderId
                    + ", 机师索引: " + selectingShipIdx);
            info.addPara(csText("msg_missing_pilot", "错误：无法找到机师信息"), opad, Misc.getNegativeHighlightColor());
            info.addButton(csText("common_cancel", "取消"), "ship_cancel", 120, 20, opad);
            return;
        }

        // 标题
        String pilotName = pilot.getName().getFullName();
        if (callsign != null && !callsign.isEmpty()) {
            pilotName += csFormat("ship_select_callsign_suffix", " (呼号: {value})", "value", callsign);
        }
        info.addPara(csFormat("ship_select_prompt", "为机师 {name} 选择舰船：", "name", pilotName), opad, h, pilotName);

        // 获取可指派的舰船列表
        List<FleetMemberAPI> assignableShips = Moci_CustomWingmanManager.getAssignableShips(selectingShipCommanderId);

        if (assignableShips.isEmpty()) {
            info.addPara(csText("ship_select_empty", "没有可指派的舰船。舰船必须是护卫舰且部署点低于队长机。"),
                    opad, Misc.getGrayColor());
            info.addButton(csText("common_cancel", "取消"), "ship_cancel", 120, 20, opad);
            return;
        }

        // 使用网格布局显示舰船 - 每行4个，图像更大
        int shipsPerRow = 4;
        float shipItemWidth = (width - 40) / shipsPerRow; // 减去边距
        float shipItemHeight = 150; // 增加高度：图像 + 文字 + 按钮的高度

        CustomPanelAPI shipGridPanel = panel.createCustomPanel(width - 20,
                ((assignableShips.size() + shipsPerRow - 1) / shipsPerRow) * (shipItemHeight + 10), null);

        int currentRow = 0;
        int currentCol = 0;

        for (FleetMemberAPI ship : assignableShips) {
            float xPos = currentCol * shipItemWidth;
            float yPos = currentRow * (shipItemHeight + 10);

            // 创建单个舰船项目面板
            createShipSelectionItem(shipGridPanel, ship, xPos, yPos, shipItemWidth, shipItemHeight);

            currentCol++;
            if (currentCol >= shipsPerRow) {
                currentCol = 0;
                currentRow++;
            }
        }

        info.addCustom(shipGridPanel, opad);

        info.addButton(csText("ship_select_cancel", "取消选择"), "ship_cancel", 120, 20, opad);
    }

    /**
     * 创建单个舰船选择项目
     *
     * @param panel  父面板
     * @param ship   舰船
     * @param xPos   X位置
     * @param yPos   Y位置
     * @param width  宽度
     * @param height 高度
     */
    private void createShipSelectionItem(CustomPanelAPI panel, FleetMemberAPI ship,
            float xPos, float yPos, float width, float height) {
        // 舰船图像 - 更大的图像
        TooltipMakerAPI shipImage = panel.createUIElement(width, 80, false);
        List<FleetMemberAPI> singleShip = new ArrayList<>();
        singleShip.add(ship);
        shipImage.addShipList(1, 1, 80, Misc.getButtonTextColor(), singleShip, 0f); // 增大图像尺寸
        panel.addUIElement(shipImage).inTL(xPos + (width - 80) / 2, yPos + 5); // 居中显示

        // 舰船信息文字
        TooltipMakerAPI shipInfo = panel.createUIElement(width, 50, false);

        // 第一行：舰船的命名（玩家自定义的名字）
        String shipCustomName = ship.getShipName(); // 舰船的命名
        if (shipCustomName == null || shipCustomName.isEmpty()) {
            shipCustomName = csText("ship_name_unnamed", "未命名");
        }
        shipInfo.addPara(shipCustomName, 0, Misc.getTextColor());

        // 第二行：舰船名（Hull名称）+ DP（DP高亮显示）
        String shipHullName = ship.getHullSpec().getHullName(); // 舰船名
        int dp = (int) ship.getDeploymentPointsCost();
        String dpLabel = csText("ship_dp_label", "DP:");
        String infoText = shipHullName + " " + dpLabel + dp;
        // Color dpColor = Misc.getHighlightColor(); // 使用金色高亮
        shipInfo.addPara(infoText, 2f, Misc.getHighlightColor(), dpLabel + dp); // 高亮DP部分

        panel.addUIElement(shipInfo).inTL(xPos, yPos + 90); // 调整位置

        // 选择按钮 - 更往下，避免重叠
        TooltipMakerAPI buttonHolder = panel.createUIElement(width, 20, false);
        List<Object> params = new ArrayList<>();
        params.add(selectingShipCommanderId);
        params.add(selectingShipIdx);
        params.add(ship);
        params.add("select_ship");

        ButtonAPI selectBtn = buttonHolder.addButton(csText("ship_select_button", "选择"), params, (int) (width - 10), 18, 0);
        panel.addUIElement(buttonHolder).inTL(xPos + 5, yPos + height - 20); // 更往下
    }

    /**
     * 获取UI颜色的派系
     */
    /**
     * 创建简化的舰船显示列
     * 只显示舰船图标和状态，不包含按钮
     *
     * @param itemPanel   父面板
     * @param squadLeader 编队指挥官
     * @param pilotIndex  机师索引
     * @param currentX    X坐标
     * @param yPos        Y坐标
     * @param shipWidth   列宽度
     * @param entryHeight 条目高度
     */
    private void createSimpleShipColumn(CustomPanelAPI itemPanel, PersonAPI squadLeader, int pilotIndex,
            float currentX, float yPos, float shipWidth, float entryHeight) {
        String commanderId = squadLeader.getId();

        // 获取当前机师使用的舰船信息
        Moci_CustomWingmanManager.CustomWingmanShip customShip = Moci_CustomWingmanManager
                .getCustomWingmanShip(commanderId, pilotIndex);

        String displayHullId;
        boolean isCustom = false;

        if (customShip != null) {
            displayHullId = customShip.hullId;
            isCustom = true;
        } else {
            displayHullId = getWingmanShipHull(squadLeader, pilotIndex);
            isCustom = false;
        }
        
        // 处理D-hull兼容问题 - 使用优化的方法直接创建舰船
        // 优先使用已存储的变体信息，避免猜测
        List<FleetMemberAPI> shipList = createShipListForDisplay(customShip, displayHullId);
        
        String imageHullId = displayHullId;
        if (!shipList.isEmpty()) {
            FleetMemberAPI displayShip = shipList.get(0);
            String baseHullId = displayShip.getHullSpec().getDParentHullId();
            if (baseHullId == null) {
                baseHullId = displayShip.getHullSpec().getHullId();
            }
            imageHullId = baseHullId;
        }

        // 舰船图标显示 - 稍微向下移动，与上端有距离
        TooltipMakerAPI shipColumn = itemPanel.createUIElement(shipWidth, entryHeight, false);
        if (!shipList.isEmpty()) {
            shipColumn.addShipList(1, 1, shipWidth, Misc.getButtonTextColor(), shipList, 0f);
        } else {
            // 如果无法创建舰船图像，显示占位符
            shipColumn.addPara(csText("ship_placeholder_1", "舰船"), 0, Misc.getGrayColor());
            shipColumn.addPara(csText("ship_placeholder_2", "图像"), 0, Misc.getGrayColor());
            shipColumn.addPara(csText("ship_placeholder_3", "缺失"), 0, Misc.getGrayColor());
            Global.getLogger(this.getClass()).warn(
                "无法创建舰船显示 - 原Hull ID: " + displayHullId + ", 图像Hull ID: " + imageHullId
            );
        }
        itemPanel.addUIElement(shipColumn).inTL(currentX, yPos + 8); // 向下移动8像素

        // 舰船名称显示 - 更靠近舰船图像，高亮显示
        TooltipMakerAPI nameHolder = itemPanel.createUIElement(shipWidth + 40, 16, false);

        // 获取舰船名称用于显示
        String shipDisplayName;
        if (customShip != null) {
            // 自定义舰船显示自定义名称
            shipDisplayName = customShip.displayName;
        } else {
            // 默认舰船显示Hull名称
            if (!shipList.isEmpty()) {
                shipDisplayName = shipList.get(0).getHullSpec().getHullName();
            } else {
                shipDisplayName = csText("ship_name_unknown", "未知舰船");
            }
        }

        // 检查DP是否超出
        boolean dpExceeded = false;
        if (customShip != null) {
            // 获取当前队长机的DP
            FleetMemberAPI commanderShip = findShipByOfficer(squadLeader);
            if (commanderShip != null) {
                int commanderDP = (int) commanderShip.getHullSpec().getSuppliesToRecover();
                int wingmanDP = customShip.deploymentPoints;
                dpExceeded = wingmanDP > commanderDP;
            }
        }

        // 根据DP状态和自定义状态确定颜色
        Color nameColor;
        if (dpExceeded) {
            nameColor = Misc.getNegativeHighlightColor(); // DP超出时显示红色
        } else if (isCustom) {
            nameColor = Misc.getHighlightColor(); // 自定义舰船显示高亮色
        } else {
            nameColor = Misc.getTextColor(); // 默认舰船显示普通颜色
        }

        // 显示舰船名称（如果DP超出，名称会变红）
        nameHolder.addPara(shipDisplayName, 0, nameColor, shipDisplayName);

        if (dpExceeded) {
            // 添加DP超出警告
            nameHolder.addPara(csText("ship_dp_exceeded", "DP超出！"), 2f,
                    Misc.getNegativeHighlightColor(), csText("ship_dp_exceeded", "DP超出！"));
        }

        itemPanel.addUIElement(nameHolder).inTL(currentX, yPos + entryHeight + 10); // 更靠近图像

        // 如果是自定义舰船，添加"还原"按钮
        if (isCustom) {
            TooltipMakerAPI restoreBtnHolder = itemPanel.createUIElement(60, 16, false);
            List<Object> restoreParams = new ArrayList<>();
            restoreParams.add(commanderId);
            restoreParams.add(pilotIndex);
            restoreParams.add("restore_ship");
            ButtonAPI restoreBtn = restoreBtnHolder.addButton(csText("ship_button_restore", "还原"), restoreParams, 58, 14, 0);
            itemPanel.addUIElement(restoreBtnHolder).inTL(currentX, yPos + entryHeight + 28); // 在名称下方
        }
    }

    /**
     * 创建使用说明页面
     */
    protected void createHelpView(CustomPanelAPI panel, TooltipMakerAPI info, float width) {
        float pad = 3;
        float opad = 10;
        Color h = Misc.getHighlightColor();
        Color bad = Misc.getNegativeHighlightColor();
        Color t = Misc.getTextColor();
        Color w = Misc.getNegativeHighlightColor();
        Color g = Misc.getGrayColor();

        info.addSectionHeading(csText("help_heading", "CombatSquad 编队系统使用说明"), Alignment.MID, opad);

        info.addPara(csText("help_section_concept", "基本概念"), opad, h, csText("help_section_concept", "基本概念"));
        info.addPara(csFormat("help_concept_1",
                "- {system} 是一个机师编队管理系统，允许玩家为安装了 {mod} 船插的舰船配置僚机编队",
                "system", "CombatSquad", "mod", "CombatSquad"), pad, h, "CombatSquad", "CombatSquad");
        info.addPara(csFormat("help_concept_2",
                "- 编队由一名 {leader} 和最多 {count} 名 {wingman} 组成",
                "leader", "指挥官（队长机）", "count", "2", "wingman", "僚机"), pad, h, "指挥官（队长机）", "2", "僚机");
        info.addPara(csFormat("help_concept_3",
                "- {wingman} 会在战斗中自动跟随 {leader} 行动，提供 {support1} 和 {support2}",
                "wingman", "僚机", "leader", "队长机", "support1", "火力支援", "support2", "护航"),
                pad, h, "僚机", "队长机", "火力支援", "护航");

        // 分隔线
        info.addSpacer(5f);
        info.addPara(csText("help_separator", "——————————————————————————————————————————"), 0, g);
        info.addSpacer(5f);

        // 编队组建规则
        info.addPara(csText("help_section_rules", "编队组建规则"), opad, h, csText("help_section_rules", "编队组建规则"));
        info.addPara(csFormat("help_rules_1", "- 队长机必须安装 {mod} 船插", "mod", "CombatSquad"), pad, h, "CombatSquad");
        info.addPara(csFormat("help_rules_2", "- 队长机必须有 {captain} 担任舰长", "captain", "非默认军官"), pad, h, "非默认军官");
        info.addPara(csFormat("help_rules_3", "- 编队会自动跟随 {leader} 移动和战斗", "leader", "队长机"), pad, h, "队长机");
        info.addPara(csFormat("help_rules_4", "- {wingman}在战斗中不消耗 {dp} 和 {supplies} ",
                "wingman", "僚机", "dp", "部署点", "supplies", "补给"), pad, h, "僚机", "部署点", "补给");
        
        // 分隔线
        info.addSpacer(5f);
        info.addPara(csText("help_separator", "——————————————————————————————————————————"), 0, g);
        info.addSpacer(5f);

        // 僚机选择规则
        info.addPara(csText("help_section_select", "僚机选择规则"), opad, h, csText("help_section_select", "僚机选择规则"));
        info.addPara(csFormat("help_select_1", "- {wingman} 必须是 {size} 级别", "wingman", "僚机", "size", "护卫舰"), pad, h, "僚机", "护卫舰");
        info.addPara(csFormat("help_select_2", "- {wingman} 的 {stat} 不能超过 {leader} ",
                "wingman", "僚机", "stat", "原始部署点", "leader", "队长机"), pad, h, "僚机", "原始部署点", "队长机");
        info.addPara(csFormat("help_select_3", "- {wingman} 不能有 {captain} ", "wingman", "僚机", "captain", "非默认军官"), pad, h, "僚机", "非默认军官");
        info.addPara(csFormat("help_select_4", "- {wingman} 不能是其他编队的 {leader} ", "wingman", "僚机", "leader", "队长机"), pad, h, "僚机", "队长机");
        info.addPara(csFormat("help_select_5", "- {wingman} 不能处于封存状态", "wingman", "僚机"), pad, h, "僚机");
        info.addPara(csFormat("help_select_6", "- {wingman} 必须是支持的制造商类型", "wingman", "僚机"), pad, h, "僚机");
        
        // 分隔线
        info.addSpacer(5f);
        info.addPara(csText("help_separator", "——————————————————————————————————————————"), 0, g);
        info.addSpacer(5f);

        // 自定义舰船功能
        info.addPara(csText("help_section_custom_ship", "自定义舰船功能"), opad, h, csText("help_section_custom_ship", "自定义舰船功能"));
        info.addPara(csFormat("help_custom_ship_1", "- 可以为每个机师 {assign} 特定的舰船", "assign", "指派"), pad, h, "指派");
        info.addPara(csFormat("help_custom_ship_2", "- 指派的舰船会保持完整的 {fit} （武器、船插、改装等）", "fit", "装配配置"), pad, h, "装配配置");
        info.addPara(csFormat("help_custom_ship_3", "- 指派后的舰船会从舰队中 {remove} ，当指派新的舰船时，旧的舰船会 {return} ",
                "remove", "移除", "return", "自动归还"), pad, h, "移除", "自动归还");
        info.addPara(csFormat("help_custom_ship_4", "- 如果僚机DP超出队长机会导致战斗中 {fail} ", "fail", "无法部署"), pad, bad, "无法部署");
        
        // 分隔线
        info.addSpacer(5f);
        info.addPara(csText("help_separator", "——————————————————————————————————————————"), 0, g);
        info.addSpacer(5f);

        // 机师管理
        info.addPara(csText("help_section_pilot", "机师管理功能"), opad, h, csText("help_section_pilot", "机师管理功能"));
        info.addPara(csFormat("help_pilot_1", "- 可以修改机师的 {name} 和 {callsign} ", "name", "姓名", "callsign", "呼号"), pad, h, "姓名", "呼号");
        info.addPara(csFormat("help_pilot_2", "- 可以更换机师 {portrait} ", "portrait", "头像"), pad, h, "头像");
        info.addPara(csFormat("help_pilot_3", "- 支持机师 {levelup} 和 {learn} ", "levelup", "升级", "learn", "技能学习"), pad, h, "升级", "技能学习");
        info.addPara(csFormat("help_pilot_4", "- 支持技能 {elite} ", "elite", "精英化"), pad, h, "精英化");
        info.addPara(csFormat("help_pilot_5", "- 机师经验会在战斗中 {auto_exp} ", "auto_exp", "自动积累"), pad, h, "自动积累");
        
        // 分隔线
        info.addSpacer(5f);
        info.addPara(csText("help_separator", "——————————————————————————————————————————"), 0, g);
        info.addSpacer(5f);

        // 操作说明
        info.addPara(csText("help_section_ops", "操作说明"), opad, h, csText("help_section_ops", "操作说明"));
        info.addPara(csFormat("help_ops_1", "- 在编队列表中点击 {portrait} 进入编队详情", "portrait", "'指挥官头像'"), pad, h, "'指挥官头像'");
        info.addPara(csFormat("help_ops_2", "- 点击 {ai_portrait} 进入舰载AI详情", "ai_portrait", "'AI头像'"), pad, h, "'AI头像'");
        info.addPara(csFormat("help_ops_3", "- 点击 {assign_ship} 为机师选择自定义舰船", "assign_ship", "'指派舰船'"), pad, h, "'指派舰船'");
        info.addPara(csFormat("help_ops_4", "- 点击 {change_portrait} 为机师选择新头像", "change_portrait", "'换头像'"), pad, h, "'换头像'");
        info.addPara(csFormat("help_ops_5", "- 使用输入框和按钮修改机师 {name} 和 {callsign} ", "name", "姓名", "callsign", "呼号"), pad, h, "姓名", "呼号");
        info.addPara(csFormat("help_ops_6", "- 当机师经验足够时，点击 {upgrade} 学习新技能", "upgrade", "'升级'"), pad, h, "'升级'");
        info.addPara(csFormat("help_ops_7", "- 当有精英化点数时，点击 {elite} 提升技能等级", "elite", "'精英化'"), pad, h, "'精英化'");
        
        // 分隔线
        info.addSpacer(5f);
        info.addPara(csText("help_separator", "——————————————————————————————————————————"), 0, g);
        info.addSpacer(5f);

        // 注意事项
        info.addPara(csText("help_section_notes", "注意事项"), opad, h, csText("help_section_notes", "注意事项"));
        info.addPara(csFormat("help_notes_1", "- 编队数据会 {autosave} ，无需手动操作", "autosave", "自动保存"), pad, h, "自动保存");
        info.addPara(csFormat("help_notes_2", "- 战斗中僚机会 {autodeploy} ，无需玩家干预", "autodeploy", "自动部署"), pad, h, "自动部署");
        info.addPara(csFormat("help_notes_3", "- 指派的自定义舰船在战斗结束后会 {autoreturn} 到舰队", "autoreturn", "自动归还"), pad, h, "自动归还");
        info.addPara(csFormat("help_notes_4", "- 在战斗中队长机被击毁会导致所属的小队开始 {retreat} ", "retreat", "撤退"), pad, h, "撤退");

        // 返回按钮
        info.addButton(csText("common_back", "返回"), "back_to_fleet", h, Misc.getDarkPlayerColor(), 120, TAB_BUTTON_HEIGHT, opad);
    }

    public FactionAPI getFactionForUIColors() {
        return Global.getSector().getPlayerFaction();
    }
}
