package com.itemdescriptions;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Provides;
import net.runelite.api.*;
import net.runelite.api.events.ClientTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.Keybind;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.HotkeyListener;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.inject.Inject;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

@PluginDescriptor(
        name = "Item Descriptions",
        description = "Shows detailed OSRS item information when hovering items",
        tags = {"item", "description", "wiki", "tooltip", "stats", "equipment", "price"}
)
public class ItemDescriptionsPlugin extends Plugin {
    private static final int MAX_LINE_LENGTH = 55;

    private static final String DESCRIPTIONS_URL = "https://raw.githubusercontent.com/tomaszantonik/item-descriptions/master/data/item-descriptions.json";

    private static final String COLOR_TITLE = "ff981f";
    private static final String COLOR_TEXT = "cccccc";
    private static final String COLOR_SECTION = "ffb83f";
    private static final String COLOR_MUTED = "999999";
    private static final String COLOR_SEPARATOR = "555555";
    private static final String COLOR_POSITIVE = "00ff00";
    private static final String COLOR_NEGATIVE = "ff5555";

    @Inject
    private Client client;

    @Inject
    private ItemManager itemManager;

    @Inject
    private TooltipManager tooltipManager;

    @Inject
    private OkHttpClient okHttpClient;

    @Inject
    private ScheduledExecutorService executorService;

    @Inject
    private KeyManager keyManager;

    @Inject
    private ItemDescriptionsConfig config;

    private final Map<Integer, String> descriptions = new ConcurrentHashMap<>();

    private boolean showHotkeyPressed;
    private boolean readMoreExpanded;
    private boolean readMoreHotkeyPressed;

    private int hoveredItemId = -1;
    private long hoveredSince;

    private final HotkeyListener showHotkeyListener = new HotkeyListener(() -> config.showHotkey()) {
        @Override
        public void hotkeyPressed() {
            showHotkeyPressed = true;
        }

        @Override
        public void hotkeyReleased() {
            showHotkeyPressed = false;
        }
    };

    private final HotkeyListener readMoreHotkeyListener = new HotkeyListener(() -> config.readMoreHotkey()) {
        @Override
        public void hotkeyPressed() {
            readMoreHotkeyPressed = true;
            readMoreExpanded = !readMoreExpanded;
        }

        @Override
        public void hotkeyReleased() {
            readMoreHotkeyPressed = false;
        }
    };

    @Override
    protected void startUp() {
        keyManager.registerKeyListener(showHotkeyListener);
        keyManager.registerKeyListener(readMoreHotkeyListener);

        loadDescriptions();
    }

    @Override
    protected void shutDown() {
        keyManager.unregisterKeyListener(showHotkeyListener);
        keyManager.unregisterKeyListener(readMoreHotkeyListener);

        showHotkeyPressed = false;
        readMoreExpanded = false;
        readMoreHotkeyPressed = false;
        hoveredItemId = -1;
        hoveredSince = 0;

        descriptions.clear();
    }

    @Subscribe
    public void onClientTick(ClientTick event) {
        if(client.isMenuOpen()) {
            resetHoveredItem();
            return;
        }

        MenuEntry[] entries = client.getMenu().getMenuEntries();

        if(entries == null || entries.length == 0) {
            resetHoveredItem();
            return;
        }

        MenuEntry entry = entries[entries.length - 1];

        if(!isLocationEnabled(entry)) {
            resetHoveredItem();
            return;
        }

        HoveredItem hoveredItem = getHoveredItem(entry);

        if(hoveredItem == null || hoveredItem.itemId <= 0) {
            resetHoveredItem();
            return;
        }

        int itemId = itemManager.canonicalize(hoveredItem.itemId);
        int quantity = Math.max(1, hoveredItem.quantity);

        if(hoveredItemId != itemId) {
            readMoreExpanded = readMoreHotkeyPressed;
            hoveredItemId = itemId;
            hoveredSince = System.currentTimeMillis();
        }

        if(System.currentTimeMillis() - hoveredSince < config.tooltipDelay()) {
            return;
        }

        if(config.showOnlyWithHotkey() && !showHotkeyPressed) {
            return;
        }

        ItemComposition item = itemManager.getItemComposition(itemId);
        String description = null;

        if(config.showDescription()) {
            description = descriptions.get(itemId);
        }

        String itemAction = getItemAction(entry);
        String tooltip = buildTooltip(itemId, quantity, item, description, itemAction);

        tooltipManager.add(new Tooltip(tooltip));
    }

    private void loadDescriptions() {
        executorService.submit(() -> {
            Request request = new Request.Builder()
                    .url(DESCRIPTIONS_URL)
                    .header("User-Agent", "RuneLite Item Descriptions/1.0.0")
                    .build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                if(!response.isSuccessful() || response.body() == null) {
                    return;
                }

                JsonObject root = new JsonParser()
                        .parse(response.body().string())
                        .getAsJsonObject();

                for(Map.Entry<String, JsonElement> entry : root.entrySet()) {
                    try {
                        int itemId = Integer.parseInt(entry.getKey());
                        descriptions.put(itemId, entry.getValue().getAsString());
                    } catch (NumberFormatException ignored) {
                    }
                }
            } catch (IOException ignored) {
            }
        });
    }

    private boolean isLocationEnabled(MenuEntry entry) {
        int groupId = WidgetUtil.componentToInterface(entry.getParam1());

        switch (groupId) {
            case InterfaceID.BANKMAIN:
                return config.showInBank();

            case InterfaceID.INVENTORY:
            case InterfaceID.BANKSIDE:
                return config.showInInventory();

            case InterfaceID.WORNITEMS:
                return config.showOnEquippedItems();

            default:
                return true;
        }
    }

    private HoveredItem getHoveredItem(MenuEntry entry) {
        int widgetId = entry.getParam1();
        int groupId = WidgetUtil.componentToInterface(widgetId);

        Widget widget = client.getWidget(widgetId);

        if(widget != null) {
            if(groupId == InterfaceID.WORNITEMS) {
                Widget itemWidget = widget.getChild(1);

                if(itemWidget != null && itemWidget.getItemId() > 0) {
                    return new HoveredItem(itemWidget.getItemId(), itemWidget.getItemQuantity());
                }
            } else {
                Widget itemWidget = widget.getChild(entry.getParam0());

                if(itemWidget != null && itemWidget.getItemId() > 0) {
                    return new HoveredItem(itemWidget.getItemId(), itemWidget.getItemQuantity());
                }

                if(widget.getItemId() > 0) {
                    return new HoveredItem(widget.getItemId(), widget.getItemQuantity());
                }
            }
        }

        if(entry.getItemId() > 0) {
            return new HoveredItem(entry.getItemId(), 1);
        }

        return null;
    }

    private String getItemAction(MenuEntry entry) {
        if(!config.showItemAction()) {
            return null;
        }

        String option = entry.getOption();

        if(option == null) {
            return null;
        }

        option = option.trim();

        if(option.isEmpty()) {
            return null;
        }

        return option;
    }

    private String buildTooltip(int itemId, int quantity, ItemComposition item, String description, String itemAction) {
        StringBuilder tooltip = new StringBuilder();

        tooltip
                .append("<col=")
                .append(COLOR_TITLE)
                .append(">")
                .append(item.getName())
                .append("</col>");

        if(itemAction != null) {
            tooltip
                    .append(" ")
                    .append("<col=")
                    .append(COLOR_MUTED)
                    .append(">[")
                    .append(itemAction)
                    .append("]</col>");
        }

        boolean hasSection = false;

        if(config.showDescription() && description != null) {
            tooltip.append("<br>");
            appendDescription(tooltip, description);
            hasSection = true;
        }

        String infoSection = buildInfoSection(itemId, quantity, item);

        if(infoSection != null) {
            appendSectionSpacing(tooltip, hasSection);
            tooltip.append(infoSection);
            hasSection = true;
        }

        if(config.showEquipmentStats()) {
            String equipmentSection = buildEquipmentSection(itemId);

            if(equipmentSection != null) {
                if(hasSection) {
                    appendSeparator(tooltip);
                } else {
                    tooltip.append("<br>");
                }

                tooltip.append(equipmentSection);
            }
        }

        return tooltip.toString();
    }

    private String buildInfoSection(int itemId, int quantity, ItemComposition item) {
        StringBuilder section = new StringBuilder();

        if(config.showGePrice()) {
            int gePrice = itemManager.getItemPrice(itemId);

            if(gePrice > 0) {
                appendPriceLine(section, "GE", gePrice, quantity);
            }
        }

        if(config.showHighAlch()) {
            int highAlch = item.getHaPrice();

            if(highAlch > 0) {
                appendPriceLine(section, "HA", highAlch, quantity);
            }
        }

        if(config.showItemValue()) {
            int value = item.getPrice();

            if(value > 0) {
                appendPriceLine(section, "Value", value, quantity);
            }
        }

        if(config.showWeight()) {
            ItemStats itemStats = itemManager.getItemStats(itemId);

            if(itemStats != null && itemStats.getWeight() != 0) {
                appendStatLine(section, "Weight", formatWeight(itemStats.getWeight()), null);
            }
        }

        if(section.length() == 0) {
            return null;
        }

        return section.toString();
    }

    private void appendPriceLine(StringBuilder builder, String name, int unitPrice, int quantity) {
        if(builder.length() > 0) {
            builder.append("<br>");
        }

        long totalPrice = (long) unitPrice * quantity;

        builder
                .append("<col=")
                .append(COLOR_TEXT)
                .append(">")
                .append(name)
                .append(": ")
                .append(formatGp(totalPrice))
                .append("</col>");

        if(quantity > 1) {
            builder
                    .append(" ")
                    .append("<col=")
                    .append(COLOR_MUTED)
                    .append(">(")
                    .append(formatCompactNumber(unitPrice))
                    .append(" ea)</col>");
        }
    }

    private void appendDescription(StringBuilder tooltip, String description) {
        boolean alwaysShowFull = config.alwaysShowFullDescription();
        boolean showFullDescription = alwaysShowFull || readMoreExpanded || readMoreHotkeyPressed;

        int visibleLines = Math.max(1, Math.min(10, config.visibleLines()));

        String compactDescription = getCompactDescription(description);
        boolean truncated = isDescriptionTruncated(compactDescription, visibleLines);

        String tooltipDescription;

        if(showFullDescription) {
            tooltipDescription = formatFullDescription(description);
        } else {
            tooltipDescription = wrapText(compactDescription, visibleLines);
        }

        tooltip
                .append("<col=")
                .append(COLOR_TEXT)
                .append(">")
                .append(tooltipDescription)
                .append("</col>");

        if(!alwaysShowFull && truncated) {
            tooltip
                    .append("<br>")
                    .append("<col=")
                    .append(COLOR_MUTED)
                    .append(">");

            if(showFullDescription) {
                tooltip
                        .append("Collapse [")
                        .append(getReadMoreKeybindName())
                        .append("]");
            } else {
                tooltip
                        .append("Read more [")
                        .append(getReadMoreKeybindName())
                        .append("]");
            }

            tooltip.append("</col>");
        }
    }

    private String buildEquipmentSection(int itemId) {
        ItemStats itemStats = itemManager.getItemStats(itemId);

        if(itemStats == null || !itemStats.isEquipable()) {
            return null;
        }

        ItemEquipmentStats equipment = itemStats.getEquipment();

        if(equipment == null) {
            return null;
        }

        EquipmentTotals currentTotals = getCurrentEquipmentTotals();
        EquipmentTotals newTotals = getTotalsAfterEquipping(equipment, currentTotals);

        StringBuilder section = new StringBuilder();
        boolean compare = config.compareEquipped();

        StringBuilder attack = new StringBuilder();

        appendEquipmentStat(attack, "Stab", equipment.getAstab(), compare ? newTotals.astab - currentTotals.astab : null);
        appendEquipmentStat(attack, "Slash", equipment.getAslash(), compare ? newTotals.aslash - currentTotals.aslash : null);
        appendEquipmentStat(attack, "Crush", equipment.getAcrush(), compare ? newTotals.acrush - currentTotals.acrush : null);
        appendEquipmentStat(attack, "Magic", equipment.getAmagic(), compare ? newTotals.amagic - currentTotals.amagic : null);
        appendEquipmentStat(attack, "Ranged", equipment.getArange(), compare ? newTotals.arange - currentTotals.arange : null);

        if(attack.length() > 0) {
            appendSubsection(section, "Attack", attack.toString());
        }

        StringBuilder defence = new StringBuilder();

        appendEquipmentStat(defence, "Stab", equipment.getDstab(), compare ? newTotals.dstab - currentTotals.dstab : null);
        appendEquipmentStat(defence, "Slash", equipment.getDslash(), compare ? newTotals.dslash - currentTotals.dslash : null);
        appendEquipmentStat(defence, "Crush", equipment.getDcrush(), compare ? newTotals.dcrush - currentTotals.dcrush : null);
        appendEquipmentStat(defence, "Magic", equipment.getDmagic(), compare ? newTotals.dmagic - currentTotals.dmagic : null);
        appendEquipmentStat(defence, "Ranged", equipment.getDrange(), compare ? newTotals.drange - currentTotals.drange : null);

        if(defence.length() > 0) {
            appendSubsection(section, "Defence", defence.toString());
        }

        StringBuilder other = new StringBuilder();

        appendEquipmentStat(other, "Strength", equipment.getStr(), compare ? newTotals.str - currentTotals.str : null);
        appendEquipmentStat(other, "Ranged str", equipment.getRstr(), compare ? newTotals.rstr - currentTotals.rstr : null);
        appendEquipmentFloatStat(other, "Magic dmg", equipment.getMdmg(), compare ? newTotals.mdmg - currentTotals.mdmg : null, "%");
        appendEquipmentStat(other, "Prayer", equipment.getPrayer(), compare ? newTotals.prayer - currentTotals.prayer : null);

        if(equipment.getAspeed() > 0) {
            appendStatLine(other, "Attack speed", Integer.toString(equipment.getAspeed()), null);
        }

        if(other.length() > 0) {
            appendSubsection(section, "Other", other.toString());
        }

        if(section.length() == 0) {
            return null;
        }

        return section.toString();
    }

    private EquipmentTotals getCurrentEquipmentTotals() {
        EquipmentTotals totals = new EquipmentTotals();

        ItemContainer worn = client.getItemContainer(InventoryID.WORN);

        if(worn == null) {
            return totals;
        }

        for(Item item : worn.getItems()) {
            if(item == null || item.getId() <= 0) {
                continue;
            }

            ItemStats stats = itemManager.getItemStats(item.getId());

            if(stats == null || stats.getEquipment() == null) {
                continue;
            }

            totals.add(stats.getEquipment());
        }

        return totals;
    }

    private EquipmentTotals getTotalsAfterEquipping(ItemEquipmentStats newEquipment, EquipmentTotals currentTotals) {
        EquipmentTotals totals = new EquipmentTotals(currentTotals);

        ItemContainer worn = client.getItemContainer(InventoryID.WORN);

        if(worn == null) {
            totals.add(newEquipment);
            return totals;
        }

        removeEquippedSlot(totals, worn, newEquipment.getSlot());

        if(newEquipment.isTwoHanded()) {
            removeEquippedSlot(totals, worn, EquipmentInventorySlot.SHIELD.getSlotIdx());
        }

        if(newEquipment.getSlot() == EquipmentInventorySlot.SHIELD.getSlotIdx()) {
            Item weapon = worn.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());

            if(weapon != null && weapon.getId() > 0) {
                ItemStats weaponStats = itemManager.getItemStats(weapon.getId());

                if(weaponStats != null && weaponStats.getEquipment() != null && weaponStats.getEquipment().isTwoHanded()) {
                    totals.subtract(weaponStats.getEquipment());
                }
            }
        }

        totals.add(newEquipment);

        return totals;
    }

    private void removeEquippedSlot(EquipmentTotals totals, ItemContainer worn, int slot) {
        Item equippedItem = worn.getItem(slot);

        if(equippedItem == null || equippedItem.getId() <= 0) {
            return;
        }

        ItemStats equippedStats = itemManager.getItemStats(equippedItem.getId());

        if(equippedStats == null || equippedStats.getEquipment() == null) {
            return;
        }

        totals.subtract(equippedStats.getEquipment());
    }

    private void appendEquipmentStat(StringBuilder builder, String name, int value, Integer difference) {
        if(config.hideZeroStats() && value == 0) {
            return;
        }

        if(config.hideUnchangedStats() && difference != null && difference == 0) {
            return;
        }

        appendStatLine(builder, name, formatSigned(value), difference == null ? null : formatDifference(difference));
    }

    private void appendEquipmentFloatStat(StringBuilder builder, String name, float value, Float difference, String suffix) {
        if(config.hideZeroStats() && value == 0) {
            return;
        }

        if(config.hideUnchangedStats() && difference != null && difference == 0) {
            return;
        }

        appendStatLine(builder, name, formatSignedFloat(value) + suffix, difference == null ? null : formatDifference(difference) + suffix);
    }

    private void appendStatLine(StringBuilder builder, String name, String value, String difference) {
        if(builder.length() > 0) {
            builder.append("<br>");
        }

        builder
                .append("<col=")
                .append(COLOR_TEXT)
                .append(">")
                .append(name)
                .append(": ")
                .append(value)
                .append("</col>");

        if(difference != null) {
            builder.append(" ");

            if(difference.startsWith("+")) {
                builder
                        .append("<col=")
                        .append(COLOR_POSITIVE)
                        .append(">(")
                        .append(difference)
                        .append(")</col>");
            } else if(difference.startsWith("-")) {
                builder
                        .append("<col=")
                        .append(COLOR_NEGATIVE)
                        .append(">(")
                        .append(difference)
                        .append(")</col>");
            } else {
                builder
                        .append("<col=")
                        .append(COLOR_MUTED)
                        .append(">(")
                        .append(difference)
                        .append(")</col>");
            }
        }
    }

    private void appendSubsection(StringBuilder builder, String title, String content) {
        if(builder.length() > 0) {
            builder.append("<br><br>");
        }

        builder
                .append("<col=")
                .append(COLOR_SECTION)
                .append(">")
                .append(title)
                .append("</col>")
                .append("<br>")
                .append(content);
    }

    private void appendSeparator(StringBuilder builder) {
        builder
                .append("<br>")
                .append("<col=")
                .append(COLOR_SEPARATOR)
                .append(">----------------</col>")
                .append("<br>");
    }

    private void appendSectionSpacing(StringBuilder builder, boolean hasPreviousSection) {
        if(hasPreviousSection) {
            builder.append("<br><br>");
        } else {
            builder.append("<br>");
        }
    }

    private String formatGp(long value) {
        return formatCompactNumber(value) + " gp";
    }

    private String formatCompactNumber(long value) {
        if(value >= 1_000_000_000L) {
            return formatCompactValue(value / 1_000_000_000.0) + "B";
        }

        if(value >= 1_000_000L) {
            return formatCompactValue(value / 1_000_000.0) + "M";
        }

        if(value >= 1_000L) {
            return formatCompactValue(value / 1_000.0) + "K";
        }

        return Long.toString(value);
    }

    private String formatCompactValue(double value) {
        if(value >= 100) {
            return String.format(Locale.US, "%.0f", value);
        }

        if(value >= 10) {
            return String.format(Locale.US, "%.1f", value)
                    .replaceAll("\\.0$", "");
        }

        return String.format(Locale.US, "%.2f", value)
                .replaceAll("0+$", "")
                .replaceAll("\\.$", "");
    }

    private String formatWeight(double weight) {
        String value;

        if(weight == Math.rint(weight)) {
            value = Integer.toString((int) weight);
        } else {
            value = String.format(Locale.US, "%.3f", weight)
                    .replaceAll("0+$", "")
                    .replaceAll("\\.$", "");
        }

        if(weight > 0) {
            value = "+" + value;
        }

        return value + " kg";
    }

    private String formatSigned(int value) {
        if(value > 0) {
            return "+" + value;
        }

        return Integer.toString(value);
    }

    private String formatSignedFloat(float value) {
        if(value > 0) {
            return "+" + trimFloat(value);
        }

        return trimFloat(value);
    }

    private String formatDifference(int value) {
        if(value > 0) {
            return "+" + value;
        }

        return Integer.toString(value);
    }

    private String formatDifference(float value) {
        if(value > 0) {
            return "+" + trimFloat(value);
        }

        return trimFloat(value);
    }

    private String trimFloat(float value) {
        if(value == Math.round(value)) {
            return Integer.toString(Math.round(value));
        }

        return String.format(Locale.US, "%.1f", value);
    }

    private void resetHoveredItem() {
        hoveredItemId = -1;
        hoveredSince = 0;

        if(!readMoreHotkeyPressed) {
            readMoreExpanded = false;
        }
    }

    private String getCompactDescription(String text) {
        return text
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String formatFullDescription(String text) {
        StringBuilder result = new StringBuilder();
        String[] paragraphs = text.split("\\n+");

        boolean firstParagraph = true;

        for(String paragraph : paragraphs) {
            paragraph = paragraph.trim();

            if(paragraph.isEmpty()) {
                continue;
            }

            if(!firstParagraph) {
                result.append("<br><br>");
            }

            result.append(wrapText(paragraph, Integer.MAX_VALUE));

            firstParagraph = false;
        }

        return result.toString();
    }

    private String wrapText(String text, int maxLines) {
        StringBuilder result = new StringBuilder();
        String[] words = text.split(" ");

        int lineLength = 0;
        int lines = 1;

        for(String word : words) {
            int additionalLength = lineLength == 0 ? word.length() : word.length() + 1;

            if(lineLength + additionalLength > MAX_LINE_LENGTH) {
                if(lines >= maxLines) {
                    result.append("...");
                    break;
                }

                result.append("<br>");
                lines++;
                lineLength = 0;
            }

            if(lineLength > 0) {
                result.append(" ");
                lineLength++;
            }

            result.append(word);
            lineLength += word.length();
        }

        return result.toString();
    }

    private boolean isDescriptionTruncated(String text, int maxLines) {
        String[] words = text.split(" ");

        int lineLength = 0;
        int lines = 1;

        for(String word : words) {
            int additionalLength = lineLength == 0 ? word.length() : word.length() + 1;

            if(lineLength + additionalLength > MAX_LINE_LENGTH) {
                lines++;
                lineLength = 0;

                if(lines > maxLines) {
                    return true;
                }
            }

            if(lineLength > 0) {
                lineLength++;
            }

            lineLength += word.length();
        }

        return false;
    }

    private String getReadMoreKeybindName() {
        if(config.readMoreHotkey() == null || Keybind.NOT_SET.equals(config.readMoreHotkey())) {
            return "Not set";
        }

        return config.readMoreHotkey().toString();
    }

    @Provides
    ItemDescriptionsConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(ItemDescriptionsConfig.class);
    }

    private static class HoveredItem {
        private final int itemId;
        private final int quantity;

        private HoveredItem(int itemId, int quantity) {
            this.itemId = itemId;
            this.quantity = quantity;
        }
    }

    private static class EquipmentTotals {
        private int astab;
        private int aslash;
        private int acrush;
        private int amagic;
        private int arange;

        private int dstab;
        private int dslash;
        private int dcrush;
        private int dmagic;
        private int drange;

        private int str;
        private int rstr;
        private float mdmg;
        private int prayer;

        private EquipmentTotals() {
        }

        private EquipmentTotals(EquipmentTotals other) {
            astab = other.astab;
            aslash = other.aslash;
            acrush = other.acrush;
            amagic = other.amagic;
            arange = other.arange;

            dstab = other.dstab;
            dslash = other.dslash;
            dcrush = other.dcrush;
            dmagic = other.dmagic;
            drange = other.drange;

            str = other.str;
            rstr = other.rstr;
            mdmg = other.mdmg;
            prayer = other.prayer;
        }

        private void add(ItemEquipmentStats stats) {
            astab += stats.getAstab();
            aslash += stats.getAslash();
            acrush += stats.getAcrush();
            amagic += stats.getAmagic();
            arange += stats.getArange();

            dstab += stats.getDstab();
            dslash += stats.getDslash();
            dcrush += stats.getDcrush();
            dmagic += stats.getDmagic();
            drange += stats.getDrange();

            str += stats.getStr();
            rstr += stats.getRstr();
            mdmg += stats.getMdmg();
            prayer += stats.getPrayer();
        }

        private void subtract(ItemEquipmentStats stats) {
            astab -= stats.getAstab();
            aslash -= stats.getAslash();
            acrush -= stats.getAcrush();
            amagic -= stats.getAmagic();
            arange -= stats.getArange();

            dstab -= stats.getDstab();
            dslash -= stats.getDslash();
            dcrush -= stats.getDcrush();
            dmagic -= stats.getDmagic();
            drange -= stats.getDrange();

            str -= stats.getStr();
            rstr -= stats.getRstr();
            mdmg -= stats.getMdmg();
            prayer -= stats.getPrayer();
        }
    }
}