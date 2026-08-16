package com.itemdescriptions;

import net.runelite.client.config.*;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

@ConfigGroup("itemdescriptions")
public interface ItemDescriptionsConfig extends Config {
    @ConfigSection(
            name = "Description",
            description = "Item description settings.",
            position = 0
    )
    String DESCRIPTION_SECTION = "description";

    @ConfigSection(
            name = "Item info",
            description = "Price and item information.",
            position = 1
    )
    String ITEM_INFO_SECTION = "itemInfo";

    @ConfigSection(
            name = "Equipment",
            description = "Equipment statistics and comparison.",
            position = 2
    )
    String EQUIPMENT_SECTION = "equipment";

    @ConfigSection(
            name = "Display",
            description = "Tooltip display settings.",
            position = 3
    )
    String DISPLAY_SECTION = "display";

    @ConfigItem(
            keyName = "showDescription",
            name = "Show description",
            description = "Show the OSRS Wiki item description.",
            section = DESCRIPTION_SECTION,
            position = 0
    )
    default boolean showDescription() {
        return true;
    }

    @ConfigItem(
            keyName = "visibleLines",
            name = "Visible lines",
            description = "Maximum number of description lines shown before Read more.",
            section = DESCRIPTION_SECTION,
            position = 1
    )
    @Range(min = 1, max = 10)
    default int visibleLines() {
        return 3;
    }

    @ConfigItem(
            keyName = "alwaysShowFullDescription",
            name = "Always show full description",
            description = "Always display the complete description.",
            section = DESCRIPTION_SECTION,
            position = 2
    )
    default boolean alwaysShowFullDescription() {
        return false;
    }

    @ConfigItem(
            keyName = "readMoreHotkey",
            name = "Read more hotkey",
            description = "Press to expand or collapse the full description. Holding it keeps descriptions expanded while moving between items.",
            section = DESCRIPTION_SECTION,
            position = 3
    )
    default Keybind readMoreHotkey() {
        return new Keybind(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK);
    }

    @ConfigItem(
            keyName = "showGePrice",
            name = "GE price",
            description = "Show the Grand Exchange price.",
            section = ITEM_INFO_SECTION,
            position = 0
    )
    default boolean showGePrice() {
        return true;
    }

    @ConfigItem(
            keyName = "showHighAlch",
            name = "HA value",
            description = "Show the High Alchemy value.",
            section = ITEM_INFO_SECTION,
            position = 1
    )
    default boolean showHighAlch() {
        return true;
    }

    @ConfigItem(
            keyName = "showItemValue",
            name = "Base value",
            description = "Show the item's base game value.",
            section = ITEM_INFO_SECTION,
            position = 2
    )
    default boolean showItemValue() {
        return false;
    }

    @ConfigItem(
            keyName = "showWeight",
            name = "Weight",
            description = "Show the item's weight.",
            section = ITEM_INFO_SECTION,
            position = 3
    )
    default boolean showWeight() {
        return true;
    }

    @ConfigItem(
            keyName = "showEquipmentStats",
            name = "Equipment stats",
            description = "Show combat bonuses for equippable items.",
            section = EQUIPMENT_SECTION,
            position = 0
    )
    default boolean showEquipmentStats() {
        return true;
    }

    @ConfigItem(
            keyName = "compareEquipped",
            name = "Compare equipped",
            description = "Show how your stats would change if the item was equipped.",
            section = EQUIPMENT_SECTION,
            position = 1
    )
    default boolean compareEquipped() {
        return true;
    }

    @ConfigItem(
            keyName = "hideUnchangedStats",
            name = "Hide unchanged stats",
            description = "Hide equipment stats that would not change.",
            section = EQUIPMENT_SECTION,
            position = 2
    )
    default boolean hideUnchangedStats() {
        return false;
    }

    @ConfigItem(
            keyName = "hideZeroStats",
            name = "Hide zero stats",
            description = "Hide equipment stats with a value of zero.",
            section = EQUIPMENT_SECTION,
            position = 3
    )
    default boolean hideZeroStats() {
        return false;
    }

    @ConfigItem(
            keyName = "showInInventory",
            name = "Show in inventory",
            description = "Show item information when hovering items in the inventory.",
            section = DISPLAY_SECTION,
            position = 0
    )
    default boolean showInInventory() {
        return true;
    }

    @ConfigItem(
            keyName = "showInBank",
            name = "Show in bank",
            description = "Show item information when hovering items in the bank.",
            section = DISPLAY_SECTION,
            position = 1
    )
    default boolean showInBank() {
        return true;
    }

    @ConfigItem(
            keyName = "showOnEquippedItems",
            name = "Show on equipped items",
            description = "Show item information when hovering currently equipped items.",
            section = DISPLAY_SECTION,
            position = 2
    )
    default boolean showOnEquippedItems() {
        return true;
    }

    @ConfigItem(
            keyName = "showItemAction",
            name = "Show item action",
            description = "Show the current item action next to the item name, for example [Use], [Wear] or [Remove].",
            section = DISPLAY_SECTION,
            position = 3
    )
    default boolean showItemAction() {
        return true;
    }

    @ConfigItem(
            keyName = "showOnlyWithHotkey",
            name = "Show only with hotkey",
            description = "Only display item information while the configured hotkey is held.",
            section = DISPLAY_SECTION,
            position = 4
    )
    default boolean showOnlyWithHotkey() {
        return false;
    }

    @ConfigItem(
            keyName = "showHotkey",
            name = "Show hotkey",
            description = "Hold this key to display item information when Show only with hotkey is enabled.",
            section = DISPLAY_SECTION,
            position = 5
    )
    default Keybind showHotkey() {
        return Keybind.NOT_SET;
    }
}