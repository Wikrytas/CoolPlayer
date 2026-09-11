package com.wikrytas.coolplayer.models

enum class SortMode(val label: String) {
    TITLE("По названию"),
    ARTIST("По исполнителю"),
    DURATION("По длительности"),
    DATE_ADDED("Сначала новые")
}