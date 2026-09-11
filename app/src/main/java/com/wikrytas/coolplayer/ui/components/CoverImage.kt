package com.wikrytas.coolplayer.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.wikrytas.coolplayer.data.CoverRepository
import com.wikrytas.coolplayer.models.Track

@Composable
fun CoverImage(
    track: Track?,
    modifier: Modifier = Modifier,
    shape: Shape? = null
) {
    val context = LocalContext.current
    val coverRepo = remember { CoverRepository.getInstance(context) }
    var coverUri by remember(track?.id) { mutableStateOf<Uri?>(null) }
    var isLoading by remember(track?.id) { mutableStateOf(true) }

    LaunchedEffect(track?.id) {
        val t = track ?: run {
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        coverUri = coverRepo.resolveCover(t)
        isLoading = false
    }

    val base = if (shape != null) modifier.clip(shape) else modifier

    Box(modifier = base.background(Color(0xFF1A1A2E)), contentAlignment = Alignment.Center) {
        if (coverUri != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(coverUri).crossfade(true).build(),
                contentDescription = track?.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = track?.title?.take(1)?.uppercase() ?: "♪",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}