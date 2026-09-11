package com.wikrytas.coolplayer.ui.theme

import androidx.compose.ui.graphics.Color

data class PlayerTheme(
    val name: String,
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val accent: Color
)

object PlayerThemes {
    val Cyberpunk = PlayerTheme("Киберпанк", Color(0xFF0F0C29), Color(0xFF302B63), Color(0xFF00FFFF))
    val Neon = PlayerTheme("Неон", Color(0xFF1A0020), Color(0xFF4A0E4E), Color(0xFFFF2EC4))
    val Sunset = PlayerTheme("Закат", Color(0xFF200122), Color(0xFF6F0000), Color(0xFFFFA17F))
    val Ocean = PlayerTheme("Океан", Color(0xFF000428), Color(0xFF004E92), Color(0xFF00C9FF))
    val Forest = PlayerTheme("Лес", Color(0xFF0F2027), Color(0xFF2C5364), Color(0xFF43E97B))
    val Mono = PlayerTheme("Монохром", Color(0xFF111111), Color(0xFF333333), Color(0xFFFFFFFF))

    val all = listOf(Cyberpunk, Neon, Sunset, Ocean, Forest, Mono)

    fun byName(name: String): PlayerTheme = all.find { it.name == name } ?: all.first()

    fun next(current: PlayerTheme): PlayerTheme {
        val index = all.indexOf(current)
        return all[(index + 1) % all.size]
    }
}