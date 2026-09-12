package com.wikrytas.coolplayer.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import com.wikrytas.coolplayer.data.CoverRepository
import com.wikrytas.coolplayer.models.Track
import com.wikrytas.coolplayer.ui.theme.NeonColors
import com.wikrytas.coolplayer.ui.theme.PlayerTheme

/**
 * Обложка трека: кэш CoverRepository → доизвлечение в фоне → albumArtUri.
 * Пока ничего нет — анимированная заглушка в цветах темы;
 * showName = false для миниатюр списка.
 */
@Composable
fun CoverImage(
    track: Track?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    theme: PlayerTheme? = null,
    showName: Boolean = true
) {
    val context = LocalContext.current
    val coverRepository = remember { CoverRepository.getInstance(context.applicationContext) }
    var cachedUri by remember(track?.id) { mutableStateOf(track?.let { coverRepository.cachedCoverUri(it) }) }

    LaunchedEffect(track?.id) {
        val t = track ?: return@LaunchedEffect
        if (cachedUri == null) {
            cachedUri = coverRepository.resolveCover(t)
        }
    }

    val model = cachedUri ?: track?.albumArtUri

    Box(
        modifier = modifier.clip(shape),
        contentAlignment = Alignment.Center
    ) {
        if (model == null) {
            AnimatedLogo(
                modifier = Modifier.fillMaxSize(),
                dim = true,
                top = theme?.backgroundTop ?: Color(0xFF081426),
                bottom = theme?.backgroundBottom ?: Color(0xFF050B18),
                glow1 = theme?.accent ?: NeonColors.Cyan,
                glow2 = theme?.accent ?: NeonColors.Purple,
                showName = showName
            )
        } else {
            SubcomposeAsyncImage(
                model = model,
                contentDescription = track?.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            ) {
                when (painter.state) {
                    is AsyncImagePainter.State.Success ->
                        Image(
                            painter = painter,
                            contentDescription = track?.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    else -> AnimatedLogo(
                        modifier = Modifier.fillMaxSize(),
                        dim = true,
                        top = theme?.backgroundTop ?: Color(0xFF081426),
                        bottom = theme?.backgroundBottom ?: Color(0xFF050B18),
                        glow1 = theme?.accent ?: NeonColors.Cyan,
                        glow2 = theme?.accent ?: NeonColors.Purple,
                        showName = showName
                    )
                }
            }
        }
    }
}