package com.itemdescriptions;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Provides;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.ClientTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.Keybind;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.HotkeyListener;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.inject.Inject;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

@PluginDescriptor(
        name = "Item Descriptions",
        description = "Shows short OSRS Wiki descriptions when hovering items",
        tags = {"item", "description", "wiki", "tooltip"}
)
public class ItemDescriptionsPlugin extends Plugin {
    private static final int MAX_LINE_LENGTH = 55;
    private static final long RETRY_DELAY_MS = 60_000L;

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
    private final Map<Integer, Boolean> loading = new ConcurrentHashMap<>();
    private final Map<Integer, Long> retryAfter = new ConcurrentHashMap<>();

    private boolean showHotkeyPressed;

    private boolean readMoreExpanded;
    private boolean readMoreHotkeyPressed;

    private int hoveredItemId = -1;

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
    }

    @Override
    protected void shutDown() {
        keyManager.unregisterKeyListener(showHotkeyListener);
        keyManager.unregisterKeyListener(readMoreHotkeyListener);

        showHotkeyPressed = false;

        readMoreExpanded = false;
        readMoreHotkeyPressed = false;

        hoveredItemId = -1;

        descriptions.clear();
        loading.clear();
        retryAfter.clear();
    }

    @Subscribe
    public void onClientTick(ClientTick event) {
        if(config.hideInBank() && client.getWidget(InterfaceID.Bankmain.ITEMS) != null) {
            resetHoveredItem();
            return;
        }

        MenuEntry[] entries = client.getMenu().getMenuEntries();

        if(entries == null || entries.length == 0) {
            resetHoveredItem();
            return;
        }

        MenuEntry entry = entries[entries.length - 1];
        int itemId = entry.getItemId();

        if(itemId <= 0) {
            resetHoveredItem();
            return;
        }

        itemId = itemManager.canonicalize(itemId);

        if(hoveredItemId != itemId) {
            readMoreExpanded = readMoreHotkeyPressed;
            hoveredItemId = itemId;
        }

        if(config.showOnlyWithHotkey() && !showHotkeyPressed) {
            return;
        }

        String description = descriptions.get(itemId);

        if(description == null) {
            loadDescription(itemId);
            return;
        }

        ItemComposition item = itemManager.getItemComposition(itemId);

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

        StringBuilder tooltip = new StringBuilder();

        tooltip
                .append("<col=ff981f>")
                .append(item.getName())
                .append("</col>")
                .append("<br>")
                .append("<col=cccccc>")
                .append(tooltipDescription)
                .append("</col>");

        if(!alwaysShowFull && truncated) {
            tooltip
                    .append("<br>")
                    .append("<col=999999>");

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

        tooltipManager.add(new Tooltip(tooltip.toString()));
    }

    private void resetHoveredItem() {
        hoveredItemId = -1;

        if(!readMoreHotkeyPressed) {
            readMoreExpanded = false;
        }
    }

    private void loadDescription(int itemId) {
        Long retryTimestamp = retryAfter.get(itemId);

        if(retryTimestamp != null && retryTimestamp > System.currentTimeMillis()) {
            return;
        }

        if(loading.putIfAbsent(itemId, true) != null) {
            return;
        }

        ItemComposition item = itemManager.getItemComposition(itemId);
        String itemName = item.getName();

        executorService.submit(() -> {
            try {
                String description = fetchWikiDescription(itemName);

                if(description != null && !description.isEmpty()) {
                    descriptions.put(itemId, description);
                    retryAfter.remove(itemId);
                } else {
                    retryAfter.put(itemId, System.currentTimeMillis() + RETRY_DELAY_MS);
                }
            } finally {
                loading.remove(itemId);
            }
        });
    }

    private String fetchWikiDescription(String itemName) {
        HttpUrl base = HttpUrl.parse("https://oldschool.runescape.wiki/api.php");

        if(base == null) {
            return null;
        }

        HttpUrl url = base.newBuilder()
                .addQueryParameter("action", "query")
                .addQueryParameter("prop", "extracts")
                .addQueryParameter("exintro", "1")
                .addQueryParameter("explaintext", "1")
                .addQueryParameter("redirects", "1")
                .addQueryParameter("format", "json")
                .addQueryParameter("titles", itemName)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "RuneLite Item Descriptions/1.0.0")
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            if(!response.isSuccessful() || response.body() == null) {
                return null;
            }

            JsonObject root = new JsonParser()
                    .parse(response.body().string())
                    .getAsJsonObject();

            if(!root.has("query")) {
                return null;
            }

            JsonObject pages = root
                    .getAsJsonObject("query")
                    .getAsJsonObject("pages");

            if(pages == null) {
                return null;
            }

            for(Map.Entry<String, JsonElement> pageEntry : pages.entrySet()) {
                JsonObject page = pageEntry
                        .getValue()
                        .getAsJsonObject();

                if(!page.has("extract")) {
                    continue;
                }

                String extract = page
                        .get("extract")
                        .getAsString();

                if(extract == null || extract.trim().isEmpty()) {
                    continue;
                }

                return cleanDescription(extract);
            }
        } catch (IOException ignored) {
        }

        return null;
    }

    private String cleanDescription(String text) {
        return text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
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
}