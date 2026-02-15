package com.lootbag;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("lootbag")
public interface LootbagConfig extends Config
{
	@ConfigItem(
		keyName = "authServerUrlProd",
		name = "Auth Server URL",
		description = "The URL of the authentication server (lootbag-server)",
		hidden = true
	)
	default String authServerUrl()
	{
		return "https://api.lootbag.gg/auth/plugin-login";
	}



	
	@ConfigItem(
		keyName = "syncServerUrlProd",
		name = "Sync Server URL",
		description = "The URL of the sync server (lootbag-stash)",
		hidden = true
	)
	default String syncServerUrl()
	{
		return "https://stash.lootbag.gg/sync/bank";
	}

	@ConfigItem(
		keyName = "username",
		name = "Username",
		description = "Your Lootbag username (email)",
		hidden = true
	)
	default String username()
	{
		return "";
	}

	@ConfigItem(
		keyName = "password",
		name = "Password",
		description = "Your Lootbag password",
		secret = true,
		hidden = true
	)
	default String password()
	{
		return "";
	}
}
