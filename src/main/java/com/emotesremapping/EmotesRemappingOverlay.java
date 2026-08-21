package com.emotesremapping;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.cluescrolls.clues.emote.Emote;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

public class EmotesRemappingOverlay extends Overlay
{
	static final Color HIGHLIGHT_BORDER_COLOR = Color.ORANGE;
	static final Color HIGHLIGHT_HOVER_BORDER_COLOR = HIGHLIGHT_BORDER_COLOR.darker();
	static final Color HIGHLIGHT_FILL_COLOR = new Color(0, 255, 0, 20);

	private final EmotesRemappingPlugin plugin;
	private final Client client;

	private boolean hasScrolled;

	@Inject
	private EmotesRemappingOverlay(EmotesRemappingPlugin plugin, Client client)
	{
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		this.plugin = plugin;
		this.client = client;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}

		Emote currentEmote = plugin.getCurrentEmote();

		if (currentEmote == null || !currentEmote.hasSprite())
		{
			return null;
		}

		Widget emoteContainer = client.getWidget(InterfaceID.Emote.CONTENTS);

		if (emoteContainer == null || emoteContainer.isHidden())
		{
			return null;
		}

		Widget emoteWindow = client.getWidget(InterfaceID.Emote.UNIVERSE);

		if (emoteWindow == null)
		{
			return null;
		}

		Widget firstEmoteWidget = null;
		Widget secondEmoteWidget = null;

		for (Widget emoteWidget : emoteContainer.getDynamicChildren())
		{
			if (emoteWidget.getSpriteId() == currentEmote.getSpriteId())
			{
				if (firstEmoteWidget == null)
				{
					firstEmoteWidget = emoteWidget;
				}
				else if (secondEmoteWidget == null)
				{
					secondEmoteWidget = emoteWidget;
				}
				plugin.highlightWidget(graphics, emoteWidget, emoteWindow, null,
					renderCounterText(graphics));
			}
		}
		if (!hasScrolled)
		{
			hasScrolled = true;
			plugin.scrollToWidget(InterfaceID.Emote.SCROLLABLE, InterfaceID.Emote.SCROLLBAR, firstEmoteWidget, secondEmoteWidget);
		}
		return null;
	}

	/**
	 * Returns the counter text to draw above the next emote icon,
	 * or null if there are no emotes left.
	 */
	private String renderCounterText(Graphics2D graphics)
	{
		int remaining = plugin.getRemainingEmotes();
		if (remaining <= 0)
		{
			return remaining == 0 ? "Done!" : null;
		}
		return String.valueOf(remaining);
	}
}
