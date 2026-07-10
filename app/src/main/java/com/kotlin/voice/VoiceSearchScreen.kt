package com.kotlin.voice

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.kotlin.voice.ui.theme.VoiceTheme
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun VoiceSearchScreen(
    modifier: Modifier = Modifier,
    viewModel: VoiceSearchViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // rms is read only through this lambda (inside MicHero), so high-frequency updates
    // don't invalidate the rest of the screen.
    val rmsState = viewModel.rms.collectAsStateWithLifecycle()

    VoiceEffectsHandler(viewModel, context, lifecycleOwner)

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.onStop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startListening() else viewModel.onPermissionDenied()
    }

    val onMicClick: () -> Unit = {
        when {
            state.isListening -> viewModel.stopListening()
            hasAudioPermission(context) -> viewModel.startListening()
            else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    VoiceSearchContent(
        state = state,
        modifier = modifier,
        rms = { rmsState.value },
        onMicClick = onMicClick,
        onQueryChange = viewModel::onQueryChange,
        onEngineSelected = viewModel::onEngineSelected,
        onSearchNow = viewModel::searchNow,
        onCancelCountdown = viewModel::cancelCountdown,
        onClearQuery = viewModel::onClearQuery,
        onHistoryClick = viewModel::onHistoryClick,
        onRemoveHistory = viewModel::removeHistory,
        onClearHistory = viewModel::clearHistory,
    )
}

@Composable
private fun VoiceEffectsHandler(
    viewModel: VoiceSearchViewModel,
    context: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
) {
    LaunchedEffect(viewModel, lifecycleOwner) {
        // Only act on effects while at least STARTED, so we never startActivity in the background.
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is VoiceEffect.OpenUrl -> openUrl(context, effect.url)
                    is VoiceEffect.ShowMessage -> Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSearchContent(
    state: VoiceUiState,
    modifier: Modifier = Modifier,
    rms: () -> Float,
    onMicClick: () -> Unit,
    onQueryChange: (String) -> Unit,
    onEngineSelected: (SearchEngine) -> Unit,
    onSearchNow: () -> Unit,
    onCancelCountdown: () -> Unit,
    onClearQuery: () -> Unit,
    onHistoryClick: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(title = { Text(stringResource(R.string.app_title)) })
        },
        bottomBar = {
            if (!LocalInspectionMode.current) AdBanner()
        },
    ) { padding ->
        val gradient = Brush.verticalGradient(
            listOf(
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                MaterialTheme.colorScheme.background,
            )
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient)
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            MicHero(isListening = state.isListening, rms = rms, onClick = onMicClick)
            Spacer(Modifier.height(16.dp))

            val status = if (state.isListening) stringResource(R.string.listening) else stringResource(R.string.tap_to_speak)
            Text(
                text = status,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.isListening && state.partialText.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.partialText,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.height(20.dp))
            AnimatedVisibility(visible = state.hasResult && !state.isListening) {
                ResultCard(
                    state = state,
                    onQueryChange = onQueryChange,
                    onEngineSelected = onEngineSelected,
                    onSearchNow = onSearchNow,
                    onCancelCountdown = onCancelCountdown,
                    onClearQuery = onClearQuery,
                )
            }

            if (state.history.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                RecentSearches(
                    history = state.history,
                    onHistoryClick = onHistoryClick,
                    onRemoveHistory = onRemoveHistory,
                    onClearHistory = onClearHistory,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MicHero(isListening: Boolean, rms: () -> Float, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1600, easing = LinearEasing)),
        label = "pulseFraction",
    )
    // SpeechRecognizer rms(dB) is roughly -2..10; map that span to 0..1 so normal speech drives the animation.
    val level by animateFloatAsState(
        targetValue = if (isListening) ((rms() + 2f) / 12f).coerceIn(0f, 1f) else 0f,
        animationSpec = tween(120),
        label = "level",
    )
    val primary = MaterialTheme.colorScheme.primary

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(240.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val maxRadius = size.minDimension / 2f
            if (isListening) {
                val rings = 3
                for (i in 0 until rings) {
                    val f = (pulse + i.toFloat() / rings) % 1f
                    val radius = lerp(maxRadius * 0.32f, maxRadius, f) * (0.85f + 0.15f * level)
                    drawCircle(color = primary.copy(alpha = (1f - f) * 0.35f), radius = radius)
                }
                drawCircle(
                    color = primary.copy(alpha = 0.18f + 0.25f * level),
                    radius = maxRadius * (0.42f + 0.35f * level),
                )
            } else {
                val breathe = 0.5f + 0.5f * sin(pulse * 2f * PI.toFloat())
                drawCircle(
                    color = primary.copy(alpha = 0.10f + 0.06f * breathe),
                    radius = maxRadius * (0.55f + 0.05f * breathe),
                )
            }
        }

        val buttonColor by animateColorAsState(
            targetValue = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            label = "micColor",
        )
        FloatingActionButton(
            onClick = onClick,
            containerColor = buttonColor,
            modifier = Modifier.size(88.dp),
        ) {
            Icon(
                imageVector = if (isListening) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = stringResource(
                    if (isListening) R.string.stop_listening else R.string.start_voice_input
                ),
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultCard(
    state: VoiceUiState,
    onQueryChange: (String) -> Unit,
    onEngineSelected: (SearchEngine) -> Unit,
    onSearchNow: () -> Unit,
    onCancelCountdown: () -> Unit,
    onClearQuery: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.your_search)) },
                singleLine = true,
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = onClearQuery) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.clear_text))
                        }
                    }
                },
            )

            Text(
                text = stringResource(R.string.search_engine),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchEngine.entries.forEach { engine ->
                    FilterChip(
                        selected = state.engine == engine,
                        onClick = { onEngineSelected(engine) },
                        label = { Text(engine.label) },
                    )
                }
            }

            if (state.countdownActive) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { state.countdownProgress },
                            modifier = Modifier.size(44.dp),
                        )
                        Text(
                            text = state.countdownSecondsLeft.toString(),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    FilledTonalButton(onClick = onSearchNow, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Search, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.search_now))
                    }
                    OutlinedButton(onClick = onCancelCountdown) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            } else {
                FilledTonalButton(
                    onClick = onSearchNow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.search))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(state.query))
                    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.copy))
                }
                TextButton(onClick = { shareText(context, state.query) }) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.share))
                }
            }
        }
    }
}

@Composable
private fun RecentSearches(
    history: List<String>,
    onHistoryClick: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.recent_searches),
                style = MaterialTheme.typography.titleSmall,
            )
            TextButton(onClick = onClearHistory) {
                Text(stringResource(R.string.clear_all))
            }
        }
        history.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onHistoryClick(item) }
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = item,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
                IconButton(onClick = { onRemoveHistory(item) }) {
                    Icon(
                        Icons.Filled.Clear,
                        contentDescription = stringResource(R.string.remove_from_history),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun AdBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val adView = remember {
        AdView(context).apply {
            val size = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, configuration.screenWidthDp)
            setAdSize(size)
            adUnitId = context.getString(R.string.admob_banner_ad_unit_id)
            loadAd(AdRequest.Builder().build())
        }
    }

    // Destroy is tied to the AdView's own lifetime (same key as its remember).
    DisposableEffect(adView) {
        onDispose { adView.destroy() }
    }
    // Pause/resume follows the lifecycle owner; only the observer is added/removed here.
    DisposableEffect(lifecycleOwner, adView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> adView.pause()
                Lifecycle.Event.ON_RESUME -> adView.resume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(factory = { adView }, modifier = modifier.fillMaxWidth())
}

private fun hasAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.browser_not_found, Toast.LENGTH_SHORT).show()
    }
}

private fun shareText(context: Context, text: String) {
    if (text.isBlank()) return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.share_via)))
}

@Preview(showBackground = true)
@Composable
private fun VoiceSearchPreview() {
    VoiceTheme {
        VoiceSearchContent(
            state = VoiceUiState(
                query = "weather today",
                engine = SearchEngine.GOOGLE,
                history = listOf("kotlin coroutines", "nearest coffee shop", "material 3 theming"),
            ),
            rms = { 0f },
            onMicClick = {},
            onQueryChange = {},
            onEngineSelected = {},
            onSearchNow = {},
            onCancelCountdown = {},
            onClearQuery = {},
            onHistoryClick = {},
            onRemoveHistory = {},
            onClearHistory = {},
        )
    }
}
