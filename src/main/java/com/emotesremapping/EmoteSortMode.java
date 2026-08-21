package com.emotesremapping;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum EmoteSortMode
{
	NONE("None"),
	ALPHABETICAL("Alphabetical"),
	FAVORITES_FIRST("Favorites First"),
	;

	private final String name;

	@Override
	public String toString()
	{
		return name;
	}
}