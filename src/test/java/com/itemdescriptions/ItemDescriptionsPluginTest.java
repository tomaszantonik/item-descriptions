package com.itemdescriptions;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ItemDescriptionsPluginTest {
    public static void main(String[] args) throws Exception {
        ExternalPluginManager.loadBuiltin(ItemDescriptionsPlugin.class);
        RuneLite.main(args);
    }
}
