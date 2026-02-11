package com.lootbag;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class LootbagPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(LootbagPlugin.class);
		RuneLite.main(args);
	}
}
