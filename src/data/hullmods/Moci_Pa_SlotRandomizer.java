/*
 * Pa_SlotRandomizer - 舰船槽位随机化插件
 * 
 * === 使用方式 ===
 * 1. 将此HullMod应用到变体舰船上
 * 2. 通过变体ID控制随机化行为：
 *    - randomtypeall: 随机所有槽位为全部非黑名单类型（BALLISTIC, ENERGY, MISSILE, SYNERGY, HYBRID, COMPOSITE, UNIVERSAL）
 *    - randomtypeship: 随机所有槽位为舰船已有的非黑名单类型
 *    - randomsizeall: 随机所有槽位为全部尺寸（SMALL, MEDIUM, LARGE）
 *    - randomsizeship: 随机所有槽位为舰船已有的尺寸
 * 
 * 变体ID示例：
 *    - "1_randomtypeall_randomsizeall" - 同时随机化类型和尺寸
 *    - "2_randomtypeship" - 只随机化为舰船已有类型
 *    - "3_randomsizeall" - 只随机化为全部尺寸
 * 
 * === D级船只处理 ===
 * 此插件能够正确处理D级船只（损坏的舰船）和修复后的正常舰船之间的持久化数据。
 * 当舰船从D级状态修复为正常状态时，其随机化的槽位配置将被保留。
 * 
 * === 如需修改的关键部分 ===
 * 1. 可用武器类型修改：
 *    修改 AVAILABLE_WEAPON_TYPES 列表（第26-34行）
 * 
 * 2. 可用武器尺寸修改：
 *    修改 AVAILABLE_WEAPON_SIZES 列表（第35-36行）
 * 
 * 3. 黑名单槽位类型修改：
 *    修改 DEFAULT_EXCLUDED_SLOT_TYPES 集合（第37-45行）
 * 
 * 4. 变体ID识别关键词修改：
 *    修改 performSlotRandomization 方法中的字符串匹配（第390-405行）
 * 
 * 5. 数据存储键修改：
 *    修改 GLOBAL_DATA_KEY 和 UUID_MAPPING_KEY 常量（第21-22行）
 */

package data.hullmods;

import java.awt.Color;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponSize;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

public class Moci_Pa_SlotRandomizer extends BaseHullMod {
  private static final String TEXT_ID = "Moci_pyrejudge_slot_randomizer";
  private static final String HULL_MOD_ID = "Moci_pyrejudge_slot_randomizer";
  private static final String GLOBAL_DATA_KEY = "Pa__slot_data";
  private static final String UUID_MAPPING_KEY = "Pa__uuid_mapping";
  private static final String PERMANENT_ID_TAG = "Pa__permanent_id_";
  
  private static final List<WeaponType> AVAILABLE_WEAPON_TYPES =
      Arrays.asList(
          WeaponType.BALLISTIC,
          WeaponType.ENERGY,
          WeaponType.MISSILE,
          WeaponType.SYNERGY,
          WeaponType.HYBRID,
          WeaponType.COMPOSITE,
          WeaponType.UNIVERSAL);
  private static final List<WeaponSize> AVAILABLE_WEAPON_SIZES =
      Arrays.asList(WeaponSize.SMALL, WeaponSize.MEDIUM, WeaponSize.LARGE);
  private static final Set<String> DEFAULT_EXCLUDED_SLOT_TYPES =
      new HashSet<>(
          Arrays.asList(
              WeaponType.SYSTEM.toString(),
              WeaponType.BUILT_IN.toString(),
              WeaponType.DECORATIVE.toString(),
              WeaponType.LAUNCH_BAY.toString(),
              WeaponType.STATION_MODULE.toString()));

  public static class SlotModification {
    public final WeaponType originalType;
    public final WeaponSize originalSize;
    public final WeaponType newType;
    public final WeaponSize newSize;

    public SlotModification(
        WeaponType origType, WeaponSize origSize, WeaponType newType, WeaponSize newSize) {
      this.originalType = origType;
      this.originalSize = origSize;
      this.newType = newType;
      this.newSize = newSize;
    }

    public String serialize() {
      return originalType + ":" + originalSize + ">" + newType + ":" + newSize;
    }

    public static SlotModification deserialize(String data) {
      try {
        String[] parts = data.split(">");
        String[] origParts = parts[0].split(":");
        String[] newParts = parts[1].split(":");
        return new SlotModification(
            WeaponType.valueOf(origParts[0]),
            WeaponSize.valueOf(origParts[1]),
            WeaponType.valueOf(newParts[0]),
            WeaponSize.valueOf(newParts[1]));
      } catch (Exception e) {
        return null;
      }
    }
  }

  public static class ShipSlotData {
    public String permanentId;
    public Map<String, SlotModification> modifications;
    public boolean isRandomized;
    public String lastAppliedHash;
    public long timestamp;
    public String memberId;
    public String hullId;
    public String variantId;

    public ShipSlotData(String permanentId) {
      this.permanentId = permanentId;
      this.modifications = new HashMap<>();
      this.isRandomized = false;
      this.lastAppliedHash = null;
      this.timestamp = System.currentTimeMillis();
      this.memberId = null;
      this.hullId = null;
      this.variantId = null;
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ShipSlotData> getGlobalSlotData() {
    Map<String, Object> globalData = Global.getSector().getPersistentData();
    Map<String, ShipSlotData> slotData =
        (Map<String, ShipSlotData>) globalData.get(GLOBAL_DATA_KEY);
    if (slotData == null) {
      slotData = new HashMap<>();
      globalData.put(GLOBAL_DATA_KEY, slotData);
    }
    return slotData;
  }
  
  @SuppressWarnings("unchecked")
  private static Map<String, Object> getUuidMappingData() {
    Map<String, Object> globalData = Global.getSector().getPersistentData();
    Map<String, Object> uuidMapping =
        (Map<String, Object>) globalData.get(UUID_MAPPING_KEY);
    if (uuidMapping == null) {
      uuidMapping = new HashMap<>();
      globalData.put(UUID_MAPPING_KEY, uuidMapping);
    }
    return uuidMapping;
  }

  private String generateStablePermanentId(ShipVariantAPI variant) {
    if (variant == null) {
      return "unknown_" + System.nanoTime();
    }
    
    String existingUuidFromTag = null;
    for (Object tagObj : variant.getTags()) {
      if (tagObj instanceof String) {
        String tag = (String) tagObj;
        if (tag.startsWith(PERMANENT_ID_TAG)) {
          existingUuidFromTag = tag.substring(PERMANENT_ID_TAG.length());
          break;
        }
      }
    }
    
    String memberBasedId = null;
    MutableShipStatsAPI stats = variant.getStatsForOpCosts();
    if (stats != null && stats.getFleetMember() != null) {
      String fleetMemberId = stats.getFleetMember().getId();
      if (fleetMemberId != null && !fleetMemberId.isEmpty()) {
        memberBasedId = "pyrejudge_" + fleetMemberId;
      }
    }
    
    String existingUuidFromData = getUuidFromPersistentData(memberBasedId);
    String validatedUuid = validateAndReconcileUuid(variant, existingUuidFromTag, existingUuidFromData, memberBasedId);
    
    if (validatedUuid != null) {
      updateUuidMappingInPersistentData(variant, validatedUuid, memberBasedId);
      return validatedUuid;
    }
    
    String newUuid = generateNewStableUuid(variant, memberBasedId);
    saveUuidToPersistentData(variant, newUuid, memberBasedId);
    variant.addTag(PERMANENT_ID_TAG + newUuid);
    
    return newUuid;
  }
  
  private String validateAndReconcileUuid(ShipVariantAPI variant, String tagUuid, String dataUuid, String memberBasedId) {
    if (tagUuid != null && tagUuid.equals(dataUuid)) {
      return tagUuid;
    }
    
    if (tagUuid != null && dataUuid == null) {
      saveUuidToPersistentData(variant, tagUuid, memberBasedId);
      return tagUuid;
    }
    
    if (tagUuid == null && dataUuid != null) {
      variant.addTag(PERMANENT_ID_TAG + dataUuid);
      return dataUuid;
    }
    
    if (tagUuid != null && dataUuid != null && !tagUuid.equals(dataUuid)) {
      saveUuidToPersistentData(variant, tagUuid, memberBasedId);
      return tagUuid;
    }
    
    return null;
  }
  
  private String generateNewStableUuid(ShipVariantAPI variant, String memberBasedId) {
    // 获取真实船体ID（处理D级船只）
    String hullId = variant.getHullSpec().getHullId();
    String trueHullId = hullId.endsWith("_default_D") ? hullId.substring(0, hullId.length() - "_default_D".length()) : hullId;
    
    String variantId = variant.getHullVariantId();
    
    String stableId;
    if (memberBasedId != null && !memberBasedId.isEmpty()) {
      stableId = trueHullId + "_" + variantId + "_" + memberBasedId;
    } else {
      String contentHash = generateContentBasedId(variant);
      stableId = trueHullId + "_" + variantId + "_" + contentHash;
    }
    
    String finalUuid = stableId;
    int counter = 0;
    while (isUuidExistsInPersistentData(finalUuid)) {
      counter++;
      String randomSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 4);
      finalUuid = stableId + "_" + counter + "_" + randomSuffix;
      
      if (counter > 100) {
        finalUuid = stableId + "_" + System.nanoTime();
        break;
      }
    }
    
    return finalUuid;
  }
  
  private String generateContentBasedId(ShipVariantAPI variant) {
    StringBuilder content = new StringBuilder();
    
    // 获取真实船体ID（处理D级船只）
    String hullId = variant.getHullSpec().getHullId();
    String trueHullId = hullId.endsWith("_default_D") ? hullId.substring(0, hullId.length() - "_default_D".length()) : hullId;
    
    content.append(trueHullId);
    content.append("_");
    content.append(variant.getHullVariantId());
    
    if (variant.getHullMods() != null) {
      content.append("_mods_").append(variant.getHullMods().size());
    }
    
    if (variant.getFittedWeaponSlots() != null) {
      content.append("_weapons_").append(variant.getFittedWeaponSlots().size());
    }
    
    int hashCode = content.toString().hashCode();
    return "content_" + Math.abs(hashCode);
  }
  
  private void saveUuidToPersistentData(ShipVariantAPI variant, String uuid, String memberBasedId) {
    try {
      Map<String, Object> uuidMapping = getUuidMappingData();
      
      // 获取真实船体ID（处理D级船只）
      String hullId = variant.getHullSpec().getHullId();
      String trueHullId = hullId.endsWith("_default_D") ? hullId.substring(0, hullId.length() - "_default_D".length()) : hullId;
      
      Map<String, Object> shipInfo = new HashMap<>();
      shipInfo.put("hullId", hullId);
      shipInfo.put("trueHullId", trueHullId);
      shipInfo.put("variantId", variant.getHullVariantId());
      shipInfo.put("memberBasedId", memberBasedId);
      shipInfo.put("timestamp", System.currentTimeMillis());
      
      uuidMapping.put(uuid, shipInfo);
      
      if (memberBasedId != null) {
        uuidMapping.put("member_to_uuid_" + memberBasedId, uuid);
      }
      
      Global.getSector().getPersistentData().put(UUID_MAPPING_KEY, uuidMapping);
      
    } catch (Exception e) {
    }
  }
  
  private String getUuidFromPersistentData(String memberBasedId) {
    try {
      if (memberBasedId == null) return null;
      
      Map<String, Object> uuidMapping = getUuidMappingData();
      Object uuidObj = uuidMapping.get("member_to_uuid_" + memberBasedId);
      if (uuidObj instanceof String) {
        return (String) uuidObj;
      }
    } catch (Exception e) {
    }
    return null;
  }
  
  private boolean isUuidExistsInPersistentData(String uuid) {
    try {
      Map<String, Object> uuidMapping = getUuidMappingData();
      return uuidMapping.containsKey(uuid);
    } catch (Exception e) {
      return false;
    }
  }
  
  private void updateUuidMappingInPersistentData(ShipVariantAPI variant, String uuid, String memberBasedId) {
    try {
      Map<String, Object> uuidMapping = getUuidMappingData();
      
      Object existingInfoObj = uuidMapping.get(uuid);
      if (existingInfoObj instanceof Map) {
        Map<String, Object> existingInfo = (Map<String, Object>) existingInfoObj;
        
        // 获取真实船体ID（处理D级船只）
        String currentHullId = variant.getHullSpec().getHullId();
        String currentTrueHullId = currentHullId.endsWith("_default_D") ? currentHullId.substring(0, currentHullId.length() - "_default_D".length()) : currentHullId;
        String currentVariantId = variant.getHullVariantId();
        
        String savedHullId = (String) existingInfo.get("hullId");
        String savedTrueHullId = (String) existingInfo.get("trueHullId");
        // 如果没有保存trueHullId（老数据），则从hullId计算
        if (savedTrueHullId == null && savedHullId != null) {
          savedTrueHullId = savedHullId.endsWith("_default_D") ? savedHullId.substring(0, savedHullId.length() - "_default_D".length()) : savedHullId;
        }
        String savedVariantId = (String) existingInfo.get("variantId");
        
        boolean needsUpdate = false;
        
        // 比较真实ID而不是当前ID
        if (!Objects.equals(currentTrueHullId, savedTrueHullId)) {
          needsUpdate = true;
        }
        
        if (!Objects.equals(currentVariantId, savedVariantId)) {
          needsUpdate = true;
        }
        
        if (needsUpdate) {
          existingInfo.put("hullId", currentHullId);
          existingInfo.put("trueHullId", currentTrueHullId);
          existingInfo.put("variantId", currentVariantId);
          existingInfo.put("memberBasedId", memberBasedId);
          existingInfo.put("lastUpdated", System.currentTimeMillis());
          
          if (memberBasedId != null) {
            uuidMapping.put("member_to_uuid_" + memberBasedId, uuid);
          }
          
          Global.getSector().getPersistentData().put(UUID_MAPPING_KEY, uuidMapping);
        }
      } else {
        saveUuidToPersistentData(variant, uuid, memberBasedId);
      }
    } catch (Exception e) {
    }
  }

  @Deprecated
  private String getShipPermanentId(ShipVariantAPI variant) {
    return generateStablePermanentId(variant);
  }

  @Deprecated
  private String getShipPermanentIdFromShip(ShipAPI ship) {
    if (ship != null && ship.getVariant() != null) {
      return generateStablePermanentId(ship.getVariant());
    }
    return "unknown_" + System.nanoTime();
  }

  @Override
  public void applyEffectsBeforeShipCreation(
      HullSize hullSize, MutableShipStatsAPI stats, String id) {
    if (Global.getSettings().getModManager().isModEnabled("pa_random_starsector")) {
      return;
    }
    
    String permanentId = generateStablePermanentId(stats.getVariant());
    Map<String, ShipSlotData> globalData = getGlobalSlotData();
    ShipSlotData shipData = globalData.get(permanentId);
    
    if (shipData != null && shipData.isRandomized) {
      String currentVariantHash = getVariantConfigHash(stats.getVariant());
      if (shipData.lastAppliedHash != null && shipData.lastAppliedHash.equals(currentVariantHash)) {
        return;
      }
      
      if (applyStoredSlotConfiguration(stats, shipData)) {
        shipData.lastAppliedHash = currentVariantHash;
        updateShipSlotData(shipData, stats.getVariant());
        return;
      }
    }
    
    performSlotRandomization(stats, permanentId);
  }
  
  private void updateShipSlotData(ShipSlotData shipData, ShipVariantAPI variant) {
    shipData.timestamp = System.currentTimeMillis();
    shipData.hullId = variant.getHullSpec().getHullId();
    shipData.variantId = variant.getHullVariantId();
    
    MutableShipStatsAPI stats = variant.getStatsForOpCosts();
    if (stats != null && stats.getFleetMember() != null) {
      shipData.memberId = stats.getFleetMember().getId();
    }
  }

  private void performSlotRandomization(MutableShipStatsAPI stats, String permanentId) {
    ShipVariantAPI variant = stats.getVariant();
    String variantId = variant.getHullVariantId();
    
    boolean randomizeTypeAll = false;
    boolean randomizeTypeShip = false;
    boolean randomizeSizeAll = false;
    boolean randomizeSizeShip = false;
    
    if (variantId != null) {
      String lowerVariantId = variantId.toLowerCase();
      
      if (lowerVariantId.contains("randomtypeall")) {
        randomizeTypeAll = true;
      } else if (lowerVariantId.contains("randomtypeship")) {
        randomizeTypeShip = true;
      }
      
      if (lowerVariantId.contains("randomsizeall")) {
        randomizeSizeAll = true;
      } else if (lowerVariantId.contains("randomsizeship")) {
        randomizeSizeShip = true;
      }
    }
    
    if (!randomizeTypeAll && !randomizeTypeShip && !randomizeSizeAll && !randomizeSizeShip) {
      return;
    }
    
    ShipHullSpecAPI originalSpec = variant.getHullSpec();
    ShipHullSpecAPI modifiedSpec = cloneHullSpec(originalSpec);
    if (modifiedSpec == null) {
      return;
    }
    
    List<WeaponSlotAPI> slots = modifiedSpec.getAllWeaponSlotsCopy();
    Map<String, SlotModification> modifications = new HashMap<>();
    Set<WeaponType> originalTypes = new HashSet<>();
    Set<WeaponSize> originalSizes = new HashSet<>();
    
    for (WeaponSlotAPI slot : slots) {
      if (!shouldExcludeSlot(slot)) {
        originalTypes.add(slot.getWeaponType());
        originalSizes.add(slot.getSlotSize());
      }
    }
    
    Random random = new Random(permanentId.hashCode());
    
    List<WeaponType> availableTypes;
    if (randomizeTypeAll) {
      availableTypes = getFilteredWeaponTypes(originalTypes, true);
    } else if (randomizeTypeShip) {
      availableTypes = getFilteredWeaponTypes(originalTypes, false);
    } else {
      availableTypes = new ArrayList<>();
    }
    
    List<WeaponSize> availableSizes;
    if (randomizeSizeAll) {
      availableSizes = new ArrayList<>(AVAILABLE_WEAPON_SIZES);
    } else if (randomizeSizeShip) {
      availableSizes = new ArrayList<>(originalSizes);
    } else {
      availableSizes = new ArrayList<>();
    }
    
    if (availableTypes.isEmpty() && availableSizes.isEmpty()) {
      return;
    }
    
    for (WeaponSlotAPI slot : slots) {
      if (shouldExcludeSlot(slot)) {
        continue;
      }
      
      WeaponType originalType = slot.getWeaponType();
      WeaponSize originalSize = slot.getSlotSize();
      WeaponType newType = originalType;
      WeaponSize newSize = originalSize;
      
      if (!availableTypes.isEmpty()) {
        WeaponType randomType = availableTypes.get(random.nextInt(availableTypes.size()));
        if (isValidWeaponType(randomType)) {
          newType = randomType;
        }
      }
      
      if (!availableSizes.isEmpty()) {
        newSize = availableSizes.get(random.nextInt(availableSizes.size()));
      }
      
      if (!isValidWeaponType(newType)) {
        continue;
      }
      
      if (modifySlot(slot, newType, newSize)) {
        modifications.put(
            slot.getId(), new SlotModification(originalType, originalSize, newType, newSize));
      }
    }
    
    variant.setHullSpecAPI(modifiedSpec);
    saveSlotConfiguration(permanentId, modifications, variant);
  }

  private ShipHullSpecAPI cloneHullSpec(ShipHullSpecAPI originalSpec) {
    try {
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      Class<?> hullSpecClass = originalSpec.getClass();
      MethodHandle cloneMethod =
          lookup.findVirtual(hullSpecClass, "clone", MethodType.methodType(Object.class));
      return (ShipHullSpecAPI) cloneMethod.invoke(originalSpec);
    } catch (Throwable t) {
      return null;
    }
  }

  private boolean modifySlot(WeaponSlotAPI slot, WeaponType type, WeaponSize size) {
    try {
      if (shouldExcludeSlot(slot)) {
        return false;
      }
      if (!isValidWeaponType(type)) {
        return false;
      }
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      Class<?> slotClass = slot.getClass();
      MethodHandle setWeaponTypeMethod =
          lookup.findVirtual(
              slotClass, "setWeaponType", MethodType.methodType(void.class, WeaponType.class));
      setWeaponTypeMethod.invoke(slot, type);
      MethodHandle setSlotSizeMethod =
          lookup.findVirtual(
              slotClass, "setSlotSize", MethodType.methodType(void.class, WeaponSize.class));
      setSlotSizeMethod.invoke(slot, size);
      if (slot.getWeaponType() != type) {
        return false;
      }
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  private void saveSlotConfiguration(
      String permanentId, Map<String, SlotModification> modifications, ShipVariantAPI variant) {
    Map<String, ShipSlotData> globalData = getGlobalSlotData();
    ShipSlotData shipData = new ShipSlotData(permanentId);
    shipData.modifications = modifications;
    shipData.isRandomized = true;
    
    updateShipSlotData(shipData, variant);
    
    globalData.put(permanentId, shipData);
  }

  private boolean applyStoredSlotConfiguration(MutableShipStatsAPI stats, ShipSlotData shipData) {
    ShipVariantAPI variant = stats.getVariant();
    if (shipData.modifications.isEmpty()) {
      return false;
    }
    ShipHullSpecAPI originalSpec = variant.getHullSpec();
    ShipHullSpecAPI modifiedSpec = cloneHullSpec(originalSpec);
    if (modifiedSpec == null) {
      return false;
    }
    boolean success = true;
    for (Map.Entry<String, SlotModification> entry : shipData.modifications.entrySet()) {
      WeaponSlotAPI slot = findSlotById(modifiedSpec, entry.getKey());
      if (slot != null) {
        SlotModification mod = entry.getValue();
        if (!modifySlot(slot, mod.newType, mod.newSize)) {
          success = false;
        }
      } else {
        success = false;
      }
    }
    if (success) {
      variant.setHullSpecAPI(modifiedSpec);
    }
    return success;
  }

  private WeaponSlotAPI findSlotById(ShipHullSpecAPI hullSpec, String slotId) {
    for (WeaponSlotAPI slot : hullSpec.getAllWeaponSlotsCopy()) {
      if (slot.getId().equals(slotId)) {
        return slot;
      }
    }
    return null;
  }

  private boolean shouldExcludeSlot(WeaponSlotAPI slot) {
    if (slot == null) {
      return true;
    }
    String slotType = slot.getWeaponType().toString();
    if (DEFAULT_EXCLUDED_SLOT_TYPES.contains(slotType)) {
      return true;
    }
    if (slot.isSystemSlot()) {
      return true;
    }
    if (slot.isBuiltIn()) {
      return true;
    }
    if (slot.isDecorative()) {
      return true;
    }
    if (slot.isStationModule()) {
      return true;
    }
    if (slot.isHidden()) {
      return true;
    }
    if (!slot.isWeaponSlot()) {
      return true;
    }
    return false;
  }

  @Override
  public boolean isApplicableToShip(ShipAPI ship) {
    if (Global.getSettings().getModManager().isModEnabled("pa_random_starsector")) {
      return false;
    }
    return ship != null && ship.getHullSpec() != null;
  }

  @Override
  public String getUnapplicableReason(ShipAPI ship) {
    if (ship == null || ship.getHullSpec() == null) {
      return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_invalid_ship");
    }
    if (Global.getSettings().getModManager().isModEnabled("pa_random_starsector")) {
      return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_mod_conflict");
    }
    return null;
  }

  @Override
  public boolean showInRefitScreenModPickerFor(ShipAPI ship) {
    return true;
  }

  @Override
  public void addPostDescriptionSection(
      TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
    float opad = 10f;
    Color h = Misc.getHighlightColor();
    tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.intro"), opad);
    if (ship != null && ship.getVariant() != null) {
      String permanentId = getShipPermanentIdFromShip(ship);
      Map<String, ShipSlotData> globalData = getGlobalSlotData();
      ShipSlotData shipData = globalData.get(permanentId);
      if (shipData != null && shipData.isRandomized && !shipData.modifications.isEmpty()) {
        tooltip.addPara(
            Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.randomized_slots",
                Moci_TextLoader.mapOf("slot_count", String.valueOf(shipData.modifications.size()))),
            opad, h,
            Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.randomized_slots_highlights",
                Moci_TextLoader.mapOf("slot_count", String.valueOf(shipData.modifications.size()))).toArray(new String[0]));
        tooltip.addPara(
            Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.ship_type",
                Moci_TextLoader.mapOf("hull_id", shipData.hullId != null ? shipData.hullId : Moci_TextLoader.getText(TEXT_ID, "description.unknown_ship"))),
            opad, h,
            Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.ship_type_highlights",
                Moci_TextLoader.mapOf("hull_id", shipData.hullId != null ? shipData.hullId : Moci_TextLoader.getText(TEXT_ID, "description.unknown_ship"))).toArray(new String[0]));
      } else {
        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.pending"), opad);
      }
      // tooltip.addPara("每个舰船实例都有独立的槽位配置，不会互相影响。", opad, h);
      // tooltip.addPara("通过变体ID控制随机化行为（randomtypeall/randomtypeship/randomsizeall/randomsizeship）", opad);
    }
  }

  @Override
  public String getDescriptionParam(int index, HullSize hullSize) {
    switch (index) {
      case 0:
        return Moci_TextLoader.getText(TEXT_ID, "description.param_any_ship");
      default:
        return null;
    }
  }

  private List<WeaponType> getFilteredWeaponTypes(
      Set<WeaponType> originalTypes, boolean useAllTypes) {
    List<WeaponType> availableTypes;
    if (useAllTypes) {
      availableTypes = new ArrayList<>(AVAILABLE_WEAPON_TYPES);
    } else {
      availableTypes = new ArrayList<>();
      for (WeaponType type : originalTypes) {
        if (AVAILABLE_WEAPON_TYPES.contains(type)) {
          availableTypes.add(type);
        }
      }
    }
    if (availableTypes.isEmpty()) {
      availableTypes.add(WeaponType.BALLISTIC);
    }
    return availableTypes;
  }

  private boolean isValidWeaponType(WeaponType type) {
    return AVAILABLE_WEAPON_TYPES.contains(type);
  }

  private String getVariantConfigHash(ShipVariantAPI variant) {
    StringBuilder hashBuilder = new StringBuilder();
    for (WeaponSlotAPI slot : variant.getHullSpec().getAllWeaponSlotsCopy()) {
      hashBuilder.append(slot.getWeaponType().toString());
      hashBuilder.append(slot.getSlotSize().toString());
    }
    return hashBuilder.toString();
  }
  
  public static void cleanupExpiredData() {
    try {
      long currentTime = System.currentTimeMillis();
      long maxAge = 30L * 24L * 60L * 60L * 1000L;
      
      Map<String, Object> uuidMapping = (Map<String, Object>) Global.getSector().getPersistentData().get(UUID_MAPPING_KEY);
      if (uuidMapping != null) {
        List<String> keysToRemove = new ArrayList<>();
        for (Map.Entry<String, Object> entry : uuidMapping.entrySet()) {
          String key = entry.getKey();
          Object value = entry.getValue();
          
          if (value instanceof Map) {
            Map<String, Object> shipInfo = (Map<String, Object>) value;
            Object timestampObj = shipInfo.get("timestamp");
            if (timestampObj instanceof Long) {
              long timestamp = (Long) timestampObj;
              if (currentTime - timestamp > maxAge) {
                keysToRemove.add(key);
              }
            }
          }
        }
        
        for (String key : keysToRemove) {
          uuidMapping.remove(key);
        }
        
        if (!keysToRemove.isEmpty()) {
          Global.getSector().getPersistentData().put(UUID_MAPPING_KEY, uuidMapping);
        }
      }
      
      Map<String, Object> globalData = Global.getSector().getPersistentData();
      Map<String, ShipSlotData> slotData = (Map<String, ShipSlotData>) globalData.get(GLOBAL_DATA_KEY);
      if (slotData != null) {
        List<String> keysToRemove = new ArrayList<>();
        for (Map.Entry<String, ShipSlotData> entry : slotData.entrySet()) {
          ShipSlotData shipData = entry.getValue();
          if (currentTime - shipData.timestamp > maxAge) {
            keysToRemove.add(entry.getKey());
          }
        }
        
        for (String key : keysToRemove) {
          slotData.remove(key);
        }
        
        if (!keysToRemove.isEmpty()) {
          globalData.put(GLOBAL_DATA_KEY, slotData);
        }
      }
      
    } catch (Exception e) {
    }
  }
  
  public static void testUuidPersistence() {
    try {
      Map<String, Object> uuidMapping = (Map<String, Object>) Global.getSector().getPersistentData().get(UUID_MAPPING_KEY);
      Map<String, ShipSlotData> slotData = (Map<String, ShipSlotData>) Global.getSector().getPersistentData().get(GLOBAL_DATA_KEY);
      
      int uuidCount = uuidMapping != null ? uuidMapping.size() : 0;
      int slotDataCount = slotData != null ? slotData.size() : 0;
      
      if (uuidMapping != null && slotData != null) {
        int consistentCount = 0;
        for (Map.Entry<String, ShipSlotData> entry : slotData.entrySet()) {
          String permanentId = entry.getKey();
          if (uuidMapping.containsKey(permanentId)) {
            consistentCount++;
          }
        }
      }
      
    } catch (Exception e) {
    }
  }
  
  public static void restoreUuidMappingsOnGameLoad() {
    try {
      cleanupExpiredData();
    } catch (Exception e) {
    }
  }
}
