package com.emotesremapping;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class EmotesRemappingPluginTest
{
	private EmotesRemappingPlugin plugin;

	@Before
	public void setUp()
	{
		plugin = new EmotesRemappingPlugin();
	}

	@Test
	public void testPluginInitialization()
	{
		Assert.assertNotNull(plugin);
	}

	@Test
	public void testGetCurrentEmoteWithoutClient()
	{
		// Without a logged-in client the current emote should be null
		Assert.assertNull(plugin.getCurrentEmote());
	}

	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(EmotesRemappingPlugin.class);
		RuneLite.main(args);
	}
}