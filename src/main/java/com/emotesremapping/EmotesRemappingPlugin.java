/*
 * Copyright (c) 2024, EmotesRemapping
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.emotesremapping;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.inject.Provides;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Value;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.ScriptID;
import net.runelite.api.annotations.Component;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import static net.runelite.api.widgets.WidgetConfig.transmitAction;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetType;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.cluescrolls.clues.emote.Emote;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Emotes Remapping",
	description = "Favorite and reorder emotes in the emote tab, with a Crack the Clue 3 vault helper",
	tags = {"emote", "favorites", "favourites", "sort", "ctc3", "crack", "clue", "vault"}
)
public class EmotesRemappingPlugin extends Plugin
{
	private static final String CONFIG_FAVORITES = "favorites";

	// Crack the Clue 3 emote sequence (in order)
	private final List<Emote> CTC3_EMOTES = ImmutableList.of(Emote.PANIC, Emote.NO, Emote.BECKON, Emote.LAUGH,
		Emote.SHRUG, Emote.CRY, Emote.SPIN, Emote.YES, Emote.THINK, Emote.DANCE, Emote.BLOW_KISS, Emote.WAVE,
		Emote.BOW, Emote.PANIC, Emote.HEADBANG, Emote.JUMP_FOR_JOY, Emote.ANGRY);

	@AllArgsConstructor
	enum Emote2Anim
	{
		PANIC(Emote.PANIC, 2105),
		NO(Emote.NO, 856),
		BECKON(Emote.BECKON, 864),
		LAUGH(Emote.LAUGH, 861),
		SHRUG(Emote.SHRUG, 2113),
		CRY(Emote.CRY, 860),
		SPIN(Emote.SPIN, 2107),
		YES(Emote.YES, 855),
		THINK(Emote.THINK, 857),
		DANCE(Emote.DANCE, 866),
		BLOW_KISS(Emote.BLOW_KISS, 1374),
		WAVE(Emote.WAVE, 863),
		BOW(Emote.BOW, 858),
		PANIC_TWO(Emote.PANIC, 2105),
		HEADBANG(Emote.HEADBANG, 2108),
		JUMP_FOR_JOY(Emote.JUMP_FOR_JOY, 2109),
		ANGRY(Emote.ANGRY, 859),
		;

		private final Emote emote;
		private final int animationId;

		public static Emote2Anim of(final int animationId, final int currentIndex)
		{
			if (animationId == PANIC.animationId && currentIndex >= 10)
			{
				return PANIC_TWO;
			}
			return Arrays.stream(values()).filter(emote -> emote.animationId == animationId).findAny().orElse(null);
		}
	}

	// Varrock basement vault: region and tiles in front of the gates
	private static final int VAULT_REGION_ID = 12697;
	private static final WorldPoint[] VAULT_TILES = {
		new WorldPoint(3191, 9825, 0),
		new WorldPoint(3192, 9825, 0),
		new WorldPoint(3191, 9824, 0),
		new WorldPoint(3192, 9824, 0),
	};

	private static final int FAVORITE_OP = 2;
	private static final int DEFAULT_EMOTE_WIDTH = 42;
	private static final int DEFAULT_EMOTE_HEIGHT = 48;
	private static final int DEFAULT_CONTAINER_WIDTH = 190;
	private static final int UI_REFRESH_TICKS = 8;
	private static final int CLICK_LOCK_TIMEOUT_TICKS = 10;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private EmotesRemappingConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private EmotesRemappingOverlay overlay;

	private boolean uiDirty;
	private int pendingUiRefreshTicks;
	private int emoteIndex;

	// Set after clicking an emote; blocks further emote clicks until the
	// animation actually starts (or times out) to prevent misclicks
	private boolean clickLocked;
	private int clickLockTicksRemaining;

	@Override
	protected void startUp()
	{
		scheduleUiRefresh(UI_REFRESH_TICKS);
		overlayManager.add(overlay);

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(this::applyUiIfOpen);
		}
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		emoteIndex = 0;

		clientThread.invokeLater(() ->
		{
			teardownUi();
			redrawEmotes();
		});
	}

	@Provides
	EmotesRemappingConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(EmotesRemappingConfig.class);
	}

	// ---------------------------------------------------------------------------
	// Reordering / favorites (based on emote-favorites)
	// ---------------------------------------------------------------------------

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded widgetLoaded)
	{
		if (widgetLoaded.getGroupId() != InterfaceID.EMOTE)
		{
			return;
		}

		scheduleUiRefresh(UI_REFRESH_TICKS);
	}

	@Subscribe
	public void onClientTick(ClientTick clientTick)
	{
		if (clickLocked && --clickLockTicksRemaining <= 0)
		{
			clickLocked = false;
		}

		if ((!uiDirty && pendingUiRefreshTicks <= 0) || !isEmoteTabOpen())
		{
			return;
		}

		if (applyUi())
		{
			uiDirty = false;
			if (pendingUiRefreshTicks > 0)
			{
				--pendingUiRefreshTicks;
			}
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!EmotesRemappingConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		scheduleUiRefresh(UI_REFRESH_TICKS);
	}

	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		scheduleUiRefresh(UI_REFRESH_TICKS);
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (event.getMenuAction() != MenuAction.CC_OP)
		{
			return;
		}

		Widget widget = event.getWidget();
		if (widget == null || !isEmoteWidget(widget))
		{
			return;
		}

		EmoteEntry entry = findEntryForWidget(widget);
		if (entry == null)
		{
			return;
		}
		String emoteKey = entry.getKey();

		if (clickLocked)
		{
			// Block all emote clicks until the previous emote's animation started
			event.consume();
			return;
		}

		String option = event.getMenuOption();
		if ("Favorite".equals(option) || "Unfavorite".equals(option))
		{
			Set<String> favorites = getFavorites();
			if ("Favorite".equals(option))
			{
				favorites.add(emoteKey);
			}
			else
			{
				favorites.remove(emoteKey);
			}

			setFavorites(favorites);
			scheduleUiRefresh(UI_REFRESH_TICKS);
			event.consume();
			return;
		}

		// Clicking an emote locks further clicks until the animation begins
		if ("Perform".equals(option) || option.startsWith("Perform ")
			|| "Loop".equals(option) || option.startsWith("Loop "))
		{
			clickLocked = true;
			clickLockTicksRemaining = CLICK_LOCK_TIMEOUT_TICKS;
		}
	}

	private void scheduleUiRefresh(int ticks)
	{
		uiDirty = true;
		pendingUiRefreshTicks = Math.max(pendingUiRefreshTicks, ticks);
	}

	private void applyUiIfOpen()
	{
		if (isEmoteTabOpen())
		{
			scheduleUiRefresh(UI_REFRESH_TICKS);
			applyUi();
		}
	}

	private boolean applyUi()
	{
		Widget contents = client.getWidget(InterfaceID.Emote.CONTENTS);
		Widget scrollable = client.getWidget(InterfaceID.Emote.SCROLLABLE);
		Widget uiOverlay = client.getWidget(InterfaceID.Emote.OVERLAY);
		if (contents == null || scrollable == null)
		{
			return false;
		}

		if (uiOverlay != null)
		{
			clearOverlay(uiOverlay);
		}

		rebuildEmoteLayout(contents, scrollable);
		return true;
	}

	private void clearOverlay(Widget uiOverlay)
	{
		uiOverlay.deleteAllChildren();
	}

	private void rebuildEmoteLayout(Widget emoteContainer, Widget scrollable)
	{
		Widget[] children = emoteContainer.getDynamicChildren();
		if (children == null || children.length == 0)
		{
			return;
		}

		List<EmoteEntry> emotes = collectEmoteEntries(children);
		if (emotes.isEmpty())
		{
			return;
		}

		int emoteWidth = getWidgetWidth(emotes.get(0).getClickbox());
		int emoteHeight = getWidgetHeight(emotes.get(0).getClickbox());
		int containerWidth = scrollable.getWidth() > 0 ? scrollable.getWidth() : scrollable.getOriginalWidth();
		if (containerWidth <= 0)
		{
			containerWidth = DEFAULT_CONTAINER_WIDTH;
		}
		int columns = Math.max(1, containerWidth / Math.max(1, emoteWidth));

		Set<String> favorites = getFavorites();
		List<EmoteEntry> orderedEmotes = getOrderedEmotes(emotes);
		List<EmoteEntry> visibleEmotes = new ArrayList<>();

		for (EmoteEntry emote : orderedEmotes)
		{
			boolean favorite = favorites.contains(emote.getKey());
			boolean visible = passesFilter(favorite);

			if (visible)
			{
				visibleEmotes.add(emote);
			}
			else
			{
				resetEntryWidgetState(emote, true);
			}
		}

		for (int i = 0; i < visibleEmotes.size(); i++)
		{
			EmoteEntry emote = visibleEmotes.get(i);
			int x = (i % columns) * emoteWidth;
			int y = (i / columns) * emoteHeight;

			applyEntryPosition(emote, x, y);
			applyEntryHiddenState(emote, false);
			applyEntryOpacity(emote, 0);
			prepareGraphic(emote.getGraphic());
			applyWidgetActions(emote.getClickbox(), emote.getKey(), favorites);
			revalidateEntry(emote);
		}

		int totalRows = Math.max(1, (visibleEmotes.size() + columns - 1) / columns);
		scrollable.setScrollHeight(totalRows * emoteHeight);
		scrollable.revalidateScroll();
	}

	private List<EmoteEntry> collectEmoteEntries(Widget[] children)
	{
		List<EmoteEntryBuilder> builders = new ArrayList<>();
		for (Widget child : children)
		{
			if (child == null)
			{
				continue;
			}

			EmoteEntryBuilder builder = findOrCreateBuilder(builders, child.getOriginalX(), child.getOriginalY());
			if (child.getType() == WidgetType.GRAPHIC && child.getSpriteId() >= 0)
			{
				builder.graphic = child;
			}
			else if (child.getType() == WidgetType.RECTANGLE)
			{
				builder.clickbox = child;
				String label = getWidgetLabel(child);
				if (label.isEmpty())
				{
					label = deriveLabelFromActions(child);
				}
				if (!label.isEmpty())
				{
					builder.label = stripTags(label);
				}
			}
		}

		List<EmoteEntry> emotes = new ArrayList<>();
		for (EmoteEntryBuilder builder : builders)
		{
			Widget clickbox = builder.clickbox != null ? builder.clickbox : builder.graphic;
			Widget graphic = builder.graphic != null ? builder.graphic : builder.clickbox;
			if (clickbox == null || graphic == null)
			{
				continue;
			}

			if (builder.label.isEmpty())
			{
				continue;
			}

			emotes.add(new EmoteEntry(
				clickbox,
				graphic,
				builder.label,
				builder.label));
		}
		return emotes;
	}

	private EmoteEntryBuilder findOrCreateBuilder(List<EmoteEntryBuilder> builders, int originalX, int originalY)
	{
		for (EmoteEntryBuilder builder : builders)
		{
			if (builder.originalX == originalX && builder.originalY == originalY)
			{
				return builder;
			}
		}

		EmoteEntryBuilder builder = new EmoteEntryBuilder(originalX, originalY);
		builders.add(builder);
		return builder;
	}

	private List<EmoteEntry> getOrderedEmotes(List<EmoteEntry> emotes)
	{
		List<EmoteEntry> ordered = new ArrayList<>(emotes);
		Set<String> favorites = getFavorites();
		String nextCtc3 = getNextCtc3Name();

		switch (config.sortMode())
		{
			case ALPHABETICAL:
				ordered.sort(Comparator.comparing(emote -> sortableName(emote.getDisplayName())));
				break;
			case FAVORITES_FIRST:
				ordered.sort(Comparator.comparingInt(emote -> favorites.contains(emote.getKey()) ? 0 : 1));
				break;
			default:
				break;
		}

		if (nextCtc3 != null)
		{
			// While in the vault, arrange the emotes following the Crack the
			// Clue 3 sequence: the upcoming emotes come first (in order),
			// acting as favorites; everything else keeps its relative order
			Map<String, Integer> ctc3Order = new HashMap<>();
			for (int i = 0; i < CTC3_EMOTES.size(); i++)
			{
				ctc3Order.putIfAbsent(sortableName(CTC3_EMOTES.get(i).getName()), i);
			}

			int startIndex = ctc3Order.getOrDefault(nextCtc3, 0);
			ordered.sort(Comparator.comparingInt(emote ->
			{
				Integer idx = ctc3Order.get(sortableName(emote.getDisplayName()));
				if (idx == null || idx < startIndex)
				{
					return CTC3_EMOTES.size();
				}
				return idx;
			}));
		}

		return ordered;
	}

	private String getNextCtc3Name()
	{
		Emote current = getCurrentEmote();
		return current == null ? null : sortableName(current.getName());
	}

	private boolean passesFilter(boolean favorite)
	{
		return !config.showFavoritesOnly() || favorite;
	}

	private void applyEntryPosition(EmoteEntry entry, int x, int y)
	{
		entry.getClickbox().setPos(x, y, WidgetPositionMode.ABSOLUTE_LEFT, WidgetPositionMode.ABSOLUTE_TOP);
		entry.getGraphic().setPos(x, y, WidgetPositionMode.ABSOLUTE_LEFT, WidgetPositionMode.ABSOLUTE_TOP);
	}

	private void applyEntryHiddenState(EmoteEntry entry, boolean hidden)
	{
		entry.getClickbox().setHidden(hidden);
		entry.getGraphic().setHidden(hidden);
	}

	private void applyEntryOpacity(EmoteEntry entry, int opacity)
	{
		applyOpacity(entry.getClickbox(), opacity);
		applyOpacity(entry.getGraphic(), opacity);
	}

	private void revalidateEntry(EmoteEntry entry)
	{
		entry.getClickbox().revalidate();
		entry.getGraphic().revalidate();
	}

	private void prepareGraphic(Widget graphic)
	{
		int widgetConfig = graphic.getClickMask();
		widgetConfig &= ~(transmitAction(0) | transmitAction(1) | transmitAction(FAVORITE_OP));
		graphic.setClickMask(widgetConfig);
		graphic.setNoClickThrough(false);
		graphic.setHasListener(false);
		clearGraphicActions(graphic);
	}

	private void resetEntryWidgetState(EmoteEntry entry, boolean hidden)
	{
		clearWidgetActions(entry.getClickbox());
		clearGraphicActions(entry.getGraphic());
		clearWidgetVisualState(entry.getClickbox(), hidden);
		clearWidgetVisualState(entry.getGraphic(), hidden);
		revalidateEntry(entry);
	}

	private void applyWidgetActions(Widget widget, String emoteKey, Set<String> favorites)
	{
		int widgetConfig = widget.getClickMask();
		widgetConfig |= transmitAction(FAVORITE_OP);
		widget.setClickMask(widgetConfig);
		widget.setAction(FAVORITE_OP, favorites.contains(emoteKey) ? "Unfavorite" : "Favorite");
	}

	private void clearWidgetActions(Widget widget)
	{
		widget.setAction(FAVORITE_OP, null);
	}

	private void clearGraphicActions(Widget widget)
	{
		widget.setAction(0, null);
		widget.setAction(1, null);
		widget.setAction(FAVORITE_OP, null);
		widget.setName("");
	}

	private void clearWidgetVisualState(Widget widget, boolean hidden)
	{
		widget.setHidden(hidden);
		applyOpacity(widget, 0);
	}

	private void applyOpacity(Widget widget, int opacity)
	{
		widget.setOpacity(opacity);

		Widget[] staticChildren = widget.getStaticChildren();
		if (staticChildren != null)
		{
			for (Widget child : staticChildren)
			{
				if (child != null)
				{
					applyOpacity(child, opacity);
				}
			}
		}

		Widget[] dynamicChildren = widget.getDynamicChildren();
		if (dynamicChildren != null)
		{
			for (Widget child : dynamicChildren)
			{
				if (child != null)
				{
					applyOpacity(child, opacity);
				}
			}
		}

		Widget[] nestedChildren = widget.getNestedChildren();
		if (nestedChildren != null)
		{
			for (Widget child : nestedChildren)
			{
				if (child != null)
				{
					applyOpacity(child, opacity);
				}
			}
		}
	}

	private void redrawEmotes()
	{
		Widget widget = client.getWidget(InterfaceID.Emote.UNIVERSE);
		if (widget == null)
		{
			return;
		}

		runWidgetListener(widget, widget.getOnVarTransmitListener());
		runWidgetListener(widget, widget.getOnLoadListener());
	}

	private boolean runWidgetListener(Widget widget, Object[] listener)
	{
		if (listener == null)
		{
			return false;
		}

		client.createScriptEventBuilder(listener)
			.setSource(widget)
			.build()
			.run();
		return true;
	}

	private void teardownUi()
	{
		Widget uiOverlay = client.getWidget(InterfaceID.Emote.OVERLAY);
		if (uiOverlay != null)
		{
			uiOverlay.deleteAllChildren();
		}

		Widget contents = client.getWidget(InterfaceID.Emote.CONTENTS);
		if (contents == null)
		{
			return;
		}

		Widget[] children = contents.getDynamicChildren();
		if (children == null)
		{
			return;
		}

		for (EmoteEntry entry : collectEmoteEntries(children))
		{
			resetEntryWidgetState(entry, false);
		}
	}

	private boolean isEmoteTabOpen()
	{
		return client.getWidget(InterfaceID.Emote.CONTENTS) != null;
	}

	private boolean isEmoteWidget(Widget widget)
	{
		return WidgetUtil.componentToInterface(widget.getId()) == InterfaceID.EMOTE;
	}

	private int getWidgetWidth(Widget widget)
	{
		int width = widget.getWidth() > 0 ? widget.getWidth() : widget.getOriginalWidth();
		return width > 0 ? width : DEFAULT_EMOTE_WIDTH;
	}

	private int getWidgetHeight(Widget widget)
	{
		int height = widget.getHeight() > 0 ? widget.getHeight() : widget.getOriginalHeight();
		return height > 0 ? height : DEFAULT_EMOTE_HEIGHT;
	}

	private String getWidgetLabel(Widget widget)
	{
		String name = widget.getName();
		if (name != null && !name.isEmpty())
		{
			return name;
		}

		String text = widget.getText();
		if (text != null && !text.isEmpty())
		{
			return text;
		}

		Widget[] staticChildren = widget.getStaticChildren();
		if (staticChildren != null)
		{
			for (Widget child : staticChildren)
			{
				if (child != null)
				{
					String label = getWidgetLabel(child);
					if (!label.isEmpty())
					{
						return label;
					}
				}
			}
		}

		Widget[] dynamicChildren = widget.getDynamicChildren();
		if (dynamicChildren != null)
		{
			for (Widget child : dynamicChildren)
			{
				if (child != null)
				{
					String label = getWidgetLabel(child);
					if (!label.isEmpty())
					{
						return label;
					}
				}
			}
		}

		return "";
	}

	private EmoteEntry findEntryForWidget(Widget widget)
	{
		Widget contents = client.getWidget(InterfaceID.Emote.CONTENTS);
		if (contents == null)
		{
			return null;
		}

		Widget[] children = contents.getDynamicChildren();
		if (children == null)
		{
			return null;
		}

		for (EmoteEntry entry : collectEmoteEntries(children))
		{
			if (entry.getClickbox() == widget || entry.getGraphic() == widget)
			{
				return entry;
			}
		}

		return null;
	}

	private String deriveLabelFromActions(Widget widget)
	{
		String[] actions = widget.getActions();
		if (actions == null)
		{
			return "";
		}

		for (String action : actions)
		{
			if (action == null || action.isEmpty())
			{
				continue;
			}

			String stripped = stripTags(action);
			if (stripped.startsWith("Perform "))
			{
				return stripped.substring("Perform ".length()).trim();
			}
			if (stripped.startsWith("Loop "))
			{
				return stripped.substring("Loop ".length()).trim();
			}
		}

		return "";
	}

	private Set<String> getFavorites()
	{
		return getStringSet(CONFIG_FAVORITES);
	}

	private void setFavorites(Set<String> favorites)
	{
		setStringSet(CONFIG_FAVORITES, favorites);
	}

	private Set<String> getStringSet(String key)
	{
		String value = configManager.getConfiguration(EmotesRemappingConfig.GROUP, key);
		if (value == null || value.isEmpty())
		{
			return new LinkedHashSet<>();
		}

		return new LinkedHashSet<>(Arrays.asList(value.split("\\|")));
	}

	private void setStringSet(String key, Set<String> values)
	{
		if (values.isEmpty())
		{
			configManager.unsetConfiguration(EmotesRemappingConfig.GROUP, key);
		}
		else
		{
			configManager.setConfiguration(EmotesRemappingConfig.GROUP, key, String.join("|", values));
		}
	}

	private static String stripTags(String input)
	{
		if (input == null || input.isEmpty())
		{
			return "";
		}

		return input.replaceAll("<[^>]+>", "").trim();
	}

	private static String sortableName(String input)
	{
		return input == null ? "" : input.toLowerCase();
	}

	// ---------------------------------------------------------------------------
	// Crack the Clue 3 vault helper (based on ctc3-emote)
	// ---------------------------------------------------------------------------

	@Subscribe
	public void onAnimationChanged(final AnimationChanged event)
	{
		if (!config.enableCTC3() || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		Player localPlayer = client.getLocalPlayer();
		if (localPlayer != event.getActor() || !isInVault(localPlayer))
		{
			return;
		}

		final Emote2Anim emote = Emote2Anim.of(localPlayer.getAnimation(), emoteIndex);
		if (emote == null)
		{
			return;
		}
		emoteIndex++;
		clickLocked = false;
		// Refresh the layout so the next CTC3 emote gets pinned first
		scheduleUiRefresh(UI_REFRESH_TICKS);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN
			|| gameStateChanged.getGameState() == GameState.HOPPING)
		{
			emoteIndex = 0;
		}
	}

	private boolean isInVault(Player localPlayer)
	{
		if (localPlayer == null || localPlayer.getWorldLocation().getRegionID() != VAULT_REGION_ID)
		{
			return false;
		}

		WorldPoint location = localPlayer.getWorldLocation();
		for (WorldPoint tile : VAULT_TILES)
		{
			if (location.equals(tile))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns the number of CTC3 emotes remaining, or -1 if not in the vault.
	 */
	public int getRemainingEmotes()
	{
		if (client == null || config == null
			|| !config.enableCTC3() || !isInVault(client.getLocalPlayer()))
		{
			return -1;
		}
		return CTC3_EMOTES.size() - emoteIndex;
	}

	public Emote getCurrentEmote()
	{
		if (client == null || config == null
			|| !config.enableCTC3() || !isInVault(client.getLocalPlayer()))
		{
			return null;
		}

		if (emoteIndex >= CTC3_EMOTES.size())
		{
			emoteIndex = 0;
			return null;
		}
		return CTC3_EMOTES.get(emoteIndex);
	}

	void highlightWidget(java.awt.Graphics2D graphics, Widget toHighlight, Widget container, Rectangle padding, String text)
	{
		padding = MoreObjects.firstNonNull(padding, new Rectangle());

		net.runelite.api.Point canvasLocation = toHighlight.getCanvasLocation();

		if (canvasLocation == null)
		{
			return;
		}

		net.runelite.api.Point windowLocation = container.getCanvasLocation();

		if (windowLocation.getY() > canvasLocation.getY() + toHighlight.getHeight()
			|| windowLocation.getY() + container.getHeight() < canvasLocation.getY())
		{
			return;
		}

		// Visible area of widget
		java.awt.geom.Area widgetArea = new java.awt.geom.Area(
			new Rectangle(
				canvasLocation.getX() - padding.x,
				Math.max(canvasLocation.getY(), windowLocation.getY()) - padding.y,
				toHighlight.getWidth() + padding.x + padding.width,
				Math.min(
					Math.min(windowLocation.getY() + container.getHeight() - canvasLocation.getY(), toHighlight.getHeight()),
					Math.min(canvasLocation.getY() + toHighlight.getHeight() - windowLocation.getY(), toHighlight.getHeight())) + padding.y + padding.height
			));

		net.runelite.client.ui.overlay.OverlayUtil.renderHoverableArea(graphics, widgetArea, client.getMouseCanvasPosition(),
			EmotesRemappingOverlay.HIGHLIGHT_FILL_COLOR, EmotesRemappingOverlay.HIGHLIGHT_BORDER_COLOR, EmotesRemappingOverlay.HIGHLIGHT_HOVER_BORDER_COLOR);

		if (text == null)
		{
			return;
		}

		java.awt.FontMetrics fontMetrics = graphics.getFontMetrics();

		net.runelite.client.ui.overlay.components.TextComponent textComponent = new net.runelite.client.ui.overlay.components.TextComponent();
		// Draw the text just above the emote icon
		textComponent.setPosition(new java.awt.Point(
			canvasLocation.getX() + toHighlight.getWidth() / 2 - fontMetrics.stringWidth(text) / 2,
			canvasLocation.getY() - 2));
		textComponent.setText(text);
		textComponent.render(graphics);
	}

	void scrollToWidget(@Component int list, @Component int scrollbar, Widget... toHighlight)
	{
		final Widget parent = client.getWidget(list);
		int averageCentralY = 0;
		int nonnullCount = 0;
		for (Widget widget : toHighlight)
		{
			if (widget != null)
			{
				averageCentralY += widget.getRelativeY() + widget.getHeight() / 2;
				nonnullCount += 1;
			}
		}
		if (nonnullCount == 0)
		{
			return;
		}
		averageCentralY /= nonnullCount;
		final int newScroll = Math.max(0, Math.min(parent.getScrollHeight(),
			averageCentralY - parent.getHeight() / 2));

		client.runScript(
			ScriptID.UPDATE_SCROLLBAR,
			scrollbar,
			list,
			newScroll
		);
	}

	@Value
	@AllArgsConstructor
	private static class EmoteEntry
	{
		Widget clickbox;
		Widget graphic;
		String key;
		String displayName;
	}

	private static class EmoteEntryBuilder
	{
		private final int originalX;
		private final int originalY;
		private Widget clickbox;
		private Widget graphic;
		private String label = "";

		private EmoteEntryBuilder(int originalX, int originalY)
		{
			this.originalX = originalX;
			this.originalY = originalY;
		}
	}
}