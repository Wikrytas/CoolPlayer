package com.wikrytas.coolplayer.models

import android.net.Uri
import androidx.compose.runtime.Immutable

@Immutable
data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val duration: Long,
    val albumArtUri: Uri? = null,
    val dateAdded: Long = 0L
)