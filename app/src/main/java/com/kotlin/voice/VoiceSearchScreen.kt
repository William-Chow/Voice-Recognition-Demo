package com.kotlin.voice

import android.Manifest
import android.app.Activity
import android.os.Build
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
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

    val activity = remember(context) { context.findActivity() }
    var showRationale by remember { mutableStateOf(false) }
    var showBlocked by remember { mutableStateOf(false) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        when {
            granted -> viewModel.startListening()
            // Denied with "don't ask again": the system dialog won't come back, so offer Settings.
            activity != null && !activity.shouldShowMicRationale() -> showBlocked = true
            else -> viewModel.onPermissionDenied()
        }
    }

    val onMicClick: () -> Unit = {
        when {
            state.isListening -> viewModel.stopListening()
            hasAudioPermission(context) -> viewModel.startListening()
            // Denied once already — explain why the mic is needed before asking again.
            activity?.shouldShowMicRationale() == true -> showRationale = true
            else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    if (showRationale) {
        MicPermissionDialog(
            body = R.string.permission_needed,
            confirm = R.string.continue_action,
            onConfirm = {
                showRationale = false
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onDismiss = { showRationale = false },
        )
    }
    if (showBlocked) {
        MicPermissionDialog(
            body = R.string.permission_blocked,
            confirm = R.string.open_settings,
            onConfirm = {
                showBlocked = false
                openAppSettings(context)
            },
            onDismiss = { showBlocked = false },
        )
    }

    VoiceSearchContent(
        state = state,
        modifier = modifier,
        rms = { rmsState.value },
        onMicClick = onMicClick,
        onQueryChange = viewModel::onQueryChange,
        onEngineSelected = viewModel::onEngineSelected,
        onLanguageSelected = viewModel::onLanguageSelected,
        onThemeModeSelected = viewModel::onThemeModeSelected,
        onDynamicColorChanged = viewModel::onDynamicColorChanged,
        onAutoSearchMillisSelected = viewModel::onAutoSearchMillisSelected,
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
    onLanguageSelected: (String?) -> Unit,
    onThemeModeSelected: (ThemeMode) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onAutoSearchMillisSelected: (Long) -> Unit,
    onSearchNow: () -> Unit,
    onCancelCountdown: () -> Unit,
    onClearQuery: () -> Unit,
    onHistoryClick: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }

    if (showSettings) {
        SettingsSheet(
            settings = state.settings,
            onThemeModeSelected = onThemeModeSelected,
            onDynamicColorChanged = onDynamicColorChanged,
            onAutoSearchMillisSelected = onAutoSearchMillisSelected,
            onDismiss = { showSettings = false },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.app_title)) },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
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

            // Only when the recognizer actually offers a choice — never a one-item picker.
            if (state.languages.size > 1) {
                LanguagePicker(
                    languages = state.languages,
                    selectedTag = state.languageTag,
                    onLanguageSelected = onLanguageSelected,
                )
            }

            Spacer(Modifier.height(20.dp))
            // Above the result card so the engine can be chosen before speaking, not only after.
            EngineSelector(selected = state.engine, onEngineSelected = onEngineSelected)

            Spacer(Modifier.height(16.dp))
            AnimatedVisibility(visible = state.hasResult && !state.isListening) {
                ResultCard(
                    state = state,
                    onQueryChange = onQueryChange,
                    onSearchNow = onSearchNow,
                    onCancelCountdown = onCancelCountdown,
                    onClearQuery = onClearQuery,
                )
            }

            // Nothing said yet and nothing to recall: explain the auto-search instead of
            // leaving half the screen blank, since opening a browser on its own is surprising.
            if (!state.hasResult && !state.isListening && state.history.isEmpty()) {
                Spacer(Modifier.height(32.dp))
                EmptyStateHint()
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
    // Only animate while listening. An infinite transition that keeps running when nothing is
    // happening redraws this Canvas every frame for as long as the screen is open.
    val pulse = if (isListening) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val fraction by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1600, easing = LinearEasing)),
            label = "pulseFraction",
        )
        fraction
    } else {
        0f
    }
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
                drawCircle(color = primary.copy(alpha = 0.13f), radius = maxRadius * 0.575f)
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

            if (state.countdownActive) {
                Text(
                    text = stringResource(R.string.auto_search_in, state.countdownSecondsLeft),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    settings: VoiceSettings,
    onThemeModeSelected: (ThemeMode) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onAutoSearchMillisSelected: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                text = stringResource(R.string.settings),
                style = MaterialTheme.typography.headlineSmall,
            )

            SettingsSection(stringResource(R.string.settings_auto_search)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    VoiceSettings.AUTO_SEARCH_CHOICES.forEach { millis ->
                        FilterChip(
                            selected = settings.autoSearchMillis == millis,
                            onClick = { onAutoSearchMillisSelected(millis) },
                            label = {
                                Text(
                                    if (millis == VoiceSettings.AUTO_SEARCH_OFF) {
                                        stringResource(R.string.auto_search_off)
                                    } else {
                                        stringResource(R.string.auto_search_seconds, (millis / 1000).toInt())
                                    }
                                )
                            },
                        )
                    }
                }
            }

            SettingsSection(stringResource(R.string.settings_appearance)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { onThemeModeSelected(mode) },
                            label = { Text(stringResource(mode.labelRes())) },
                        )
                    }
                }
            }

            // Material You only exists from Android 12; below that the switch would do nothing.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_dynamic_color),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.settings_dynamic_color_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = settings.dynamicColor, onCheckedChange = onDynamicColorChanged)
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@StringRes
private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

@Composable
private fun EmptyStateHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.GraphicEq,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.empty_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LanguagePicker(
    languages: List<SpeechLanguage>,
    selectedTag: String?,
    onLanguageSelected: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val followAppLabel = stringResource(R.string.language_follow_app)
    val currentLabel = languages.firstOrNull { it.tag == selectedTag }?.label ?: followAppLabel

    Box {
        TextButton(onClick = { expanded = true }) {
            Icon(
                Icons.Filled.Language,
                contentDescription = stringResource(R.string.speech_language),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(currentLabel)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LanguageMenuItem(followAppLabel, selected = selectedTag == null) {
                onLanguageSelected(null)
                expanded = false
            }
            languages.forEach { language ->
                LanguageMenuItem(language.label, selected = selectedTag == language.tag) {
                    onLanguageSelected(language.tag)
                    expanded = false
                }
            }
        }
    }
}

@Composable
private fun LanguageMenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        trailingIcon = {
            if (selected) Icon(Icons.Filled.Check, contentDescription = null)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EngineSelector(selected: SearchEngine, onEngineSelected: (SearchEngine) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.search_engine),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchEngine.entries.forEach { engine ->
                FilterChip(
                    selected = selected == engine,
                    onClick = { onEngineSelected(engine) },
                    label = { Text(engine.label) },
                )
            }
        }
    }
}

@Composable
private fun MicPermissionDialog(
    @StringRes body: Int,
    @StringRes confirm: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.permission_title)) },
        text = { Text(stringResource(body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.not_now)) }
        },
    )
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
    // Nothing is constructed or requested until UMP says we may request ads.
    val canRequestAds by AdsConsent.canRequestAds.collectAsStateWithLifecycle()
    if (canRequestAds) AdBannerView(modifier)
}

@Composable
private fun AdBannerView(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // The window's own width, not the device's: Configuration.screenWidthDp over-reports in
    // split-screen and freeform, which would size the banner wider than the space it gets.
    val widthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }

    val adView = remember(widthDp) {
        AdView(context).apply {
            val size = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp.value.toInt())
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

    AndroidView(
        factory = { adView },
        // Scaffold does not inset arbitrary bottomBar content, so on edge-to-edge devices the
        // gesture bar is drawn over the ad. Besides looking broken, AdMob requires that ads
        // not be obscured.
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Activity.shouldShowMicRationale(): Boolean =
    ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        "package:${context.packageName}".toUri(),
    )
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.speech_error, Toast.LENGTH_SHORT).show()
    }
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

@Composable
private fun PreviewContent() {
    VoiceSearchContent(
        state = VoiceUiState(
            query = "weather today",
            history = listOf("kotlin coroutines", "nearest coffee shop", "material 3 theming"),
        ),
        rms = { 0f },
        onMicClick = {},
        onQueryChange = {},
        onEngineSelected = {},
        onLanguageSelected = {},
        onThemeModeSelected = {},
        onDynamicColorChanged = {},
        onAutoSearchMillisSelected = {},
        onSearchNow = {},
        onCancelCountdown = {},
        onClearQuery = {},
        onHistoryClick = {},
        onRemoveHistory = {},
        onClearHistory = {},
    )
}

@Preview(showBackground = true)
@Composable
private fun VoiceSearchPreview() {
    VoiceTheme { PreviewContent() }
}

/** The manifest claims supportsRtl="true"; this makes that claim checkable without a device. */
@Preview(showBackground = true, name = "RTL", locale = "ar")
@Composable
private fun VoiceSearchRtlPreview() {
    VoiceTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            PreviewContent()
        }
    }
}

@Preview(showBackground = true, name = "zh-rCN", locale = "zh-rCN")
@Composable
private fun VoiceSearchChinesePreview() {
    VoiceTheme { PreviewContent() }
}
