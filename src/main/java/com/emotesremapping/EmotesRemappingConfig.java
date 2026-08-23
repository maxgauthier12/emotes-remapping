package com.emotesremapping;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(EmotesRemappingConfig.GROUP)
public interface EmotesRemappingConfig extends Config
{
	String GROUP = "emotesremapping";

	String KEY_SORT_MODE = "sortMode";
	String KEY_SHOW_FAVORITES_ONLY = "showFavoritesOnly";

	@ConfigSection(
		name = "Reordering",
		description = "Emote reordering and favorites options",
		position = 0
	)
	String reorderingSection = "reordering";

	@ConfigSection(
		name = "Crack the Clue 3",
		description = "Crack the Clue 3 vault helper options",
		position = 1
	)
	String ctc3Section = "ctc3";

	@ConfigItem(
		keyName = KEY_SORT_MODE,
		name = "Sort Mode",
		description = "How to sort emotes in the tab",
		section = reorderingSection,
		position = 0
	)
	default EmoteSortMode sortMode()
	{
		return EmoteSortMode.NONE;
	}

	@ConfigItem(
		keyName = KEY_SHOW_FAVORITES_ONLY,
		name = "Show Favorites Only",
		description = "Only show favorite emotes in the emote tab",
		section = reorderingSection,
		position = 1
	)
	default boolean showFavoritesOnly()
	{
		return false;
	}

	@ConfigItem(
		keyName = "enableCTC3",
		name = "Enable CTC3 helper",
		description = "Highlight the next emote to perform for Crack the Clue 3 while in the Varrock basement vault",
		section = ctc3Section,
		position = 0
	)
	default boolean enableCTC3()
	{
		return true;
	}
}