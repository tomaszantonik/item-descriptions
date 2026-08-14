package com.itemdescriptions;

import net.runelite.client.config.*;

import java.awt.event.KeyEvent;

@ConfigGroup("itemdescriptions")
public interface ItemDescriptionsConfig extends Config {
    @ConfigItem(
            keyName = "visibleLines",
            name = "Visible lines",
            description = "Maximum number of description lines shown before Read more.",
            position = 0
    )
    @Range(min = 1, max = 10)
    default int visibleLines() {
        return 3;
    }

    @ConfigItem(
            keyName = "alwaysShowFullDescription",
            name = "Always show full description",
            description = "Always display the complete description and disable Read more.",
            position = 1
    )
    default boolean alwaysShowFullDescription() {
        return false;
    }

    @ConfigItem(
            keyName = "hideInBank",
            name = "Hide in bank",
            description = "Do not show item descriptions while the bank is open.",
            position = 2
    )
    default boolean hideInBank() {
        return false;
    }

    @ConfigItem(
            keyName = "showOnlyWithHotkey",
            name = "Show only with hotkey",
            description = "Only display item descriptions while the configured hotkey is held.",
            position = 3
    )
    default boolean showOnlyWithHotkey() {
        return false;
    }

    @ConfigItem(
            keyName = "showHotkey",
            name = "Show description hotkey",
            description = "Hold this key to display item descriptions when Show only with hotkey is enabled.",
            position = 4
    )
    default Keybind showHotkey() {
        return Keybind.NOT_SET;
    }

    @ConfigItem(
            keyName = "readMoreHotkey",
            name = "Read more hotkey",
            description = "Press this key to toggle the complete item description. Holding it keeps full descriptions open while moving between items.",
            position = 5
    )
    default Keybind readMoreHotkey() {
        return new Keybind(KeyEvent.VK_SHIFT, 0);
    }
}