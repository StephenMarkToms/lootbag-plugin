package gg.lootbag;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("example")
public interface ExampleConfig extends Config
{
	@ConfigItem(
		keyName = "name",
		name = "Name",
		description = "Name to use when logging into Lootbag.gg"
	)
	default String name()
	{
		return "";
	}

	@ConfigItem(
			keyName = "token",
			name = "Token",
			description = "Token used when logging into Lootbag.gg",
			secret = true
	)
	default String token()
	{
		return "";
	}
}
