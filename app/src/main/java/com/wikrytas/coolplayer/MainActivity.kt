package com.wikrytas.coolplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wikrytas.coolplayer.data.AppLogger
import com.wikrytas.coolplayer.data.ThemeRepository
import com.wikrytas.coolplayer.ui.AudioViewModel
import com.wikrytas.coolplayer.ui.MediaControllerViewModel
import com.wikrytas.coolplayer.ui.PlayerViewModel
import com.wikrytas.coolplayer.ui.screens.MainScreen
import com.wikrytas.coolplayer.ui.screens.intro.IntroScreen
import com.wikrytas.coolplayer.ui.theme.NeonColors
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val MAX_CONNECT_RETRY = 5
    }

    private val audioViewModel: AudioViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by viewModels {
        PlayerViewModel.factory(application)
    }
    private val mediaControllerViewModel: MediaControllerViewModel by viewModels {
        MediaControllerViewModel.factory(application)
    }

    private var pendingUri by mutableStateOf<Uri?>(null)
    private var isReady by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLogger.init(applicationContext)
        AppLogger.i("Activity", "onCreate: action=${intent?.action}, data=${intent?.data}, sdk=${Build.VERSION.SDK_INT}")

        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        pendingUri = extractAudioUri(intent)
        pendingUri?.let { AppLogger.i("Activity", "ACTION_VIEW audio uri: $it") }

        splashScreen.setKeepOnScreenCondition { !isReady }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener { splashView ->
                splashView.view.animate()
                    .alpha(0f)
                    .setDuration(220)
                    .withEndAction { splashView.remove() }
                    .start()
            }
        }

        setContent {
            Root(
                onReady = { isReady = true },
                pendingUri = pendingUri,
                onPendingUriConsumed = { pendingUri = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AppLogger.i("Activity", "onNewIntent: action=${intent.action}, data=${intent.data}")
        setIntent(intent)
        val uri = extractAudioUri(intent)
        if (uri != null) pendingUri = uri
    }

    override fun onDestroy() {
        AppLogger.i("Activity", "onDestroy")
        super.onDestroy()
    }

    private fun extractAudioUri(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        val type = intent.type ?: contentResolver.getType(uri)
        return if (type?.startsWith("audio/") == true) uri else null
    }

    @Composable
    private fun Root(
        onReady: () -> Unit,
        pendingUri: Uri?,
        onPendingUriConsumed: () -> Unit
    ) {
        val themeRepository = remember { ThemeRepository(applicationContext) }
        val scope = rememberCoroutineScope()
        // FIX: параметр называется initialValue
        val introShown by themeRepository.introShown.collectAsStateWithLifecycle(initialValue = null)
        val introAlpha = remember { androidx.compose.animation.core.Animatable(1f) }

        LaunchedEffect(introShown) {
            AppLogger.d("Root", "introShown=$introShown")
            if (introShown != null) onReady()
        }

        when (introShown) {
            null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF05070F))
            )

            false -> Box(modifier = Modifier.fillMaxSize().alpha(introAlpha.value)) {
                IntroScreen(
                    onDone = {
                        scope.launch {
                            introAlpha.animateTo(0f, tween(350))
                            themeRepository.markIntroShown()
                        }
                    }
                )
            }

            true -> PermissionsAndPlayerScreen(
                pendingUri = pendingUri,
                onPendingUriConsumed = onPendingUriConsumed
            )
        }
    }

    @Composable
    private fun PermissionsAndPlayerScreen(
        pendingUri: Uri?,
        onPendingUriConsumed: () -> Unit
    ) {
        var hasPermission by remember { mutableStateOf<Boolean?>(null) }

        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

        val optionalPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        else emptyArray<String>()

        val allPermissions = requiredPermissions + optionalPermissions

        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { r ->
            val requiredGranted = requiredPermissions.all { perm -> r[perm] == true }
            AppLogger.i("Perms", "result=$r, requiredGranted=$requiredGranted")
            hasPermission = requiredGranted
        }

        LaunchedEffect(Unit) {
            val allRequiredGranted = requiredPermissions.all {
                ContextCompat.checkSelfPermission(this@MainActivity, it) == PackageManager.PERMISSION_GRANTED
            }
            AppLogger.d("Perms", "initial requiredGranted=$allRequiredGranted")
            if (allRequiredGranted) {
                hasPermission = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val hasNotifications = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!hasNotifications) {
                        launcher.launch(optionalPermissions)
                    }
                }
            } else {
                launcher.launch(allPermissions)
            }
        }

        when (hasPermission) {
            null -> BootScreen(stringResource(R.string.boot_check_perms))
            false -> PermissionDeniedScreen(onRetry = { launcher.launch(allPermissions) })
            true -> {
                val tracks by audioViewModel.tracks.collectAsStateWithLifecycle()
                val isLoading by audioViewModel.isLoading.collectAsStateWithLifecycle()
                val controller by mediaControllerViewModel.controller.collectAsStateWithLifecycle()
                val controllerError by mediaControllerViewModel.error.collectAsStateWithLifecycle()

                LaunchedEffect(tracks.size, isLoading, controller != null, controllerError) {
                    AppLogger.d(
                        "Main",
                        "state: tracks=${tracks.size}, isLoading=$isLoading, controller=${controller != null}, error=$controllerError"
                    )
                }

                when {
                    isLoading -> BootScreen(stringResource(R.string.boot_loading_library))

                    controllerError != null -> ErrorScreen(
                        message = stringResource(R.string.error_player_connect, MAX_CONNECT_RETRY),
                        onRetry = {
                            AppLogger.i("Main", "manual reconnect requested")
                            mediaControllerViewModel.reconnect()
                        }
                    )

                    controller == null -> BootScreen(stringResource(R.string.boot_connecting_player))

                    else -> MainScreen(
                        mediaController = controller!!,
                        tracks = tracks,
                        audioViewModel = audioViewModel,
                        playerViewModel = playerViewModel,
                        pendingUri = pendingUri,
                        onPendingUriConsumed = onPendingUriConsumed
                    )
                }
            }
        }
    }

    @Composable
    private fun BootScreen(status: String) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF05070F)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BootLogo()
                Spacer(modifier = Modifier.height(28.dp))
                AnimatedContent(
                    targetState = status,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                    label = "boot_status"
                ) { s ->
                    Text(
                        text = s,
                        color = Color(0xFF8FA3C8),
                        fontSize = 13.sp,
                        letterSpacing = 1.sp
                    )
                }
                Spacer(modifier = Modifier.height(18.dp))
                BootBar()
            }
        }
    }

    @Composable
    private fun BootLogo() {
        val grad = remember { Brush.linearGradient(listOf(NeonColors.Cyan, NeonColors.Purple)) }
        Canvas(modifier = Modifier.size(96.dp)) {
            val scale = size.width / 200f
            val p1 = Path().apply {
                moveTo(10f * scale, 100f * scale); quadraticBezierTo(22f * scale, 100f * scale, 28f * scale, 78f * scale)
                quadraticBezierTo(34f * scale, 56f * scale, 40f * scale, 100f * scale)
                quadraticBezierTo(46f * scale, 144f * scale, 52f * scale, 40f * scale)
                quadraticBezierTo(58f * scale, -64f * scale, 64f * scale, 100f * scale)
                quadraticBezierTo(70f * scale, 264f * scale, 76f * scale, 128f * scale)
                quadraticBezierTo(82f * scale, -8f * scale, 88f * scale, 100f * scale)
            }
            val p2 = Path().apply {
                moveTo(112f * scale, 100f * scale); quadraticBezierTo(124f * scale, 100f * scale, 130f * scale, 66f * scale)
                quadraticBezierTo(136f * scale, 32f * scale, 142f * scale, 100f * scale)
                quadraticBezierTo(148f * scale, 168f * scale, 154f * scale, 122f * scale)
                quadraticBezierTo(160f * scale, 76f * scale, 166f * scale, 100f * scale)
                quadraticBezierTo(172f * scale, 124f * scale, 178f * scale, 88f * scale)
                quadraticBezierTo(184f * scale, 52f * scale, 190f * scale, 100f * scale)
            }
            val play = Path().apply {
                moveTo(84f * scale, 62f * scale); lineTo(138f * scale, 100f * scale)
                lineTo(84f * scale, 138f * scale); close()
            }
            drawPath(p1, brush = grad, style = Stroke(width = 10f * scale, cap = StrokeCap.Round))
            drawPath(p2, brush = grad, style = Stroke(width = 10f * scale, cap = StrokeCap.Round))
            drawPath(play, brush = grad, style = Stroke(width = 8f * scale, join = StrokeJoin.Round))
            drawPath(play, color = Color(0x14FFFFFF))
        }
    }

    @Composable
    private fun BootBar() {
        val inf = rememberInfiniteTransition(label = "bootbar")
        val p by inf.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
            label = "p"
        )
        Canvas(modifier = Modifier.size(width = 120.dp, height = 3.dp)) {
            val w = size.width
            val h = size.height
            drawRoundRect(
                color = Color.White.copy(alpha = 0.10f),
                topLeft = Offset.Zero,
                size = Size(w, h),
                cornerRadius = CornerRadius(h / 2, h / 2)
            )
            val seg = w * 0.35f
            val x = -seg + p * (w + seg)
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(NeonColors.Cyan, NeonColors.Purple)),
                topLeft = Offset(x, 0f),
                size = Size(seg, h),
                cornerRadius = CornerRadius(h / 2, h / 2)
            )
        }
    }

    @Composable
    private fun ErrorScreen(message: String, onRetry: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF05070F), Color.Black))),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Плеер недоступен",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = message,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Попробовать снова",
                    color = NeonColors.Cyan,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(NeonColors.Cyan.copy(alpha = 0.1f))
                        .border(1.dp, NeonColors.Cyan.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .clickable { onRetry() }
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }
        }
    }

    @Composable
    private fun PermissionDeniedScreen(onRetry: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF05070F), Color.Black))),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Нужны разрешения",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "CoolPlayer не может работать без доступа к музыке и уведомлениям. Разрешите доступ, чтобы продолжить.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Предоставить разрешения",
                    color = NeonColors.Cyan,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(NeonColors.Cyan.copy(alpha = 0.1f))
                        .border(1.dp, NeonColors.Cyan.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .clickable { onRetry() }
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }
        }
    }
}