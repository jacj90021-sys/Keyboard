@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.keyboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Hold-to-repeat timing for keys that erase (the backspace). A tap fires once on
 * press; holding waits [HOLD_INITIAL_DELAY_MS] and then keeps firing every
 * [HOLD_REPEAT_INTERVAL_MS] until the finger lifts, the way every other keyboard
 * behaves. Without this the only way to erase was to tap over and over.
 */
private const val HOLD_INITIAL_DELAY_MS = 400L
private const val HOLD_REPEAT_INTERVAL_MS = 55L

enum class KeyStyle {
    REGULAR,
    SPECIAL,
    LIME,
    FN,
    NUMPAD_SWITCH
}

/**
 * The gesture modifier for a key.
 *
 * Normal keys keep the tap/long-press behaviour. Keys with an [onRepeat] action
 * (the backspace) delete once as soon as the finger lands and then keep deleting
 * while it stays down, so holding erases the whole word instead of forcing a tap
 * per character.
 */
@Composable
private fun keyGestureModifier(
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onRepeat: (() -> Unit)?
): Modifier {
    // Hold the latest callback without restarting the collector: while a key
    // repeats, every delete recomposes the keyboard.
    val tick by rememberUpdatedState(onRepeat ?: onClick)

    // Same shape as FUTO's repeatablyClickableAction: clickable emits
    // Press/Release into the interaction source, and this collector turns a held
    // press into an initial action plus a repeating one. The pointer scope itself
    // forbids general suspension, so all timing lives here.
    LaunchedEffect(interactionSource, onRepeat) {
        if (onRepeat == null) return@LaunchedEffect
        var repeatJob: Job? = null
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    tick()
                    repeatJob?.cancel()
                    repeatJob = launch {
                        delay(HOLD_INITIAL_DELAY_MS)
                        while (isActive) {
                            tick()
                            delay(HOLD_REPEAT_INTERVAL_MS)
                        }
                    }
                }
                is PressInteraction.Release,
                is PressInteraction.Cancel -> {
                    repeatJob?.cancel()
                    repeatJob = null
                }
            }
        }
    }

    return if (onRepeat != null) {
        // The action is driven entirely by the interaction collector above, so
        // the click callback stays empty to avoid deleting twice per tap.
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = {}
        )
    } else {
        Modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SoftKey(
    text: String,
    modifier: Modifier = Modifier,
    palette: KeyboardPalette,
    style: KeyStyle = KeyStyle.REGULAR,
    subText: String? = null,
    isArmed: Boolean = false,
    fontSize: Int = 16,
    fontWeight: FontWeight? = null,
    keyHeight: Dp = 44.dp,
    showPopup: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onSlideDrag: ((Float) -> Unit)? = null,
    onRepeat: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // KEEP the previous behaviour: pressing ANY key fills it with the lime accent so
    // there is always a clear colour flash on key press.
    val isAccent = isArmed || style == KeyStyle.LIME || isPressed

    // 1.7 behaviour, unchanged: normal keys use the theme key colour, special keys
    // keep the same fill (only their text tone differs), and every pressed/active key
    // uses the theme accent.
    val bgColor = when {
        isAccent -> palette.lime
        else -> palette.keyBg
    }

    val shadowColor = when {
        isArmed || style == KeyStyle.LIME -> palette.limeShadow
        else -> palette.keyShadow
    }

    val textColor = when {
        isAccent -> palette.onAccent
        style == KeyStyle.SPECIAL -> palette.inkSecondary
        else -> palette.ink
    }

    val shape = RoundedCornerShape(9.dp)

    Box(
        modifier = modifier.testTag("key:$text"),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(keyHeight)
                .padding(horizontal = 1.dp)
                .offset(y = if (isPressed) 2.dp else 0.dp)
                .drawBehind {
                    if (!isPressed) {
                        // 3D key shadow depth effect
                        drawRoundRect(
                            color = shadowColor,
                            topLeft = Offset(0f, size.height - 2.dp.toPx()),
                            size = androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx()),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())
                        )
                    }
                }
                .clip(shape)
                .background(bgColor)
                .then(
                    if (onSlideDrag != null) {
                        Modifier.pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                onSlideDrag(dragAmount.x)
                            }
                        }
                    } else Modifier
                )
                .then(keyGestureModifier(interactionSource, onClick, onLongClick, onRepeat)),
            contentAlignment = Alignment.Center
        ) {
            if (subText != null) {
                Text(
                    text = subText,
                    color = palette.muted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 3.dp, end = 4.dp)
                )
            }

            Text(
                text = text,
                color = textColor,
                fontSize = fontSize.sp,
                fontWeight = fontWeight ?: if (isAccent) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                fontFamily = if (style == KeyStyle.FN) FontFamily.Monospace else FontFamily.Default,
                lineHeight = 12.sp
            )
        }

        // Key preview popup bubble (drawn above the key, outside the clipped surface)
        if (showPopup && isPressed) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-(keyHeight.value + 6)).dp)
                    .width(48.dp)
                    .height(keyHeight)
                    .shadow(4.dp, RoundedCornerShape(12.dp))
                    .background(palette.keyBg, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = text,
                    color = palette.ink,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Default
                )
            }
        }
    }
}

/**
 * The chips the Fn overlay offers. Fn is a deliberate mode (not an empty text
 * box), so these stay even when nothing has been typed.
 */
val fnSuggestions: List<String> = listOf("Esc", "Tab", "Ctrl", "Alt", "Home", "End")

/**
 * Which chips the suggestion strip shows.
 *
 * Real engine suggestions always win, then the Fn keys in Fn mode. With nothing
 * to show the strip stays EMPTY: it must never invent filler words, which is
 * what the old hardcoded "Yes / Okay / Thanks / Awesome" fallback did.
 */
fun visibleSuggestions(suggestionsOverride: List<String>, isFnMode: Boolean): List<String> = when {
    suggestionsOverride.isNotEmpty() -> suggestionsOverride
    isFnMode -> fnSuggestions
    else -> emptyList()
}

@Composable
fun SuggestionsBar(
    palette: KeyboardPalette,
    isExpanded: Boolean,
    isFnMode: Boolean,
    showChevron: Boolean,
    onToggleExpand: () -> Unit,
    onSuggestionClick: (String) -> Unit,
    suggestionsOverride: List<String> = emptyList()
) {
    val suggestions = visibleSuggestions(suggestionsOverride, isFnMode)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Expand/Collapse Chevron (only when both toolbar and suggestions are on)
        if (showChevron) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isExpanded) palette.paperDeep else palette.keyBg)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggleExpand
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Toggle Toolbar",
                    tint = palette.inkSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Suggestion Chips
        suggestions.forEach { word ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isFnMode) palette.specialKey else palette.keyBg)
                    .border(1.dp, palette.line, RoundedCornerShape(8.dp))
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSuggestionClick(word) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = word,
                    color = palette.ink,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun FloatingToolbar(
    palette: KeyboardPalette,
    onToolClick: (OverlayView) -> Unit,
    onMoreClick: () -> Unit,
    onAction: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // FUTO-shaped bar: a fixed 40.dp height and equal-width slots, so all seven
    // tools fit with no sliding. On a 360.dp phone each slot is ~47.dp, the same
    // order as FUTO's ~48.dp cells.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .background(palette.paper)
            .border(1.dp, palette.line, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolbarIconButton(
            icon = Icons.Default.Face,
            palette = palette,
            modifier = Modifier.weight(1f),
            testTag = "tool:emoji",
            onClick = { onToolClick(OverlayView.EMOJI) }
        )
        ToolbarIconButton(
            icon = Icons.Default.ContentPaste,
            palette = palette,
            modifier = Modifier.weight(1f),
            testTag = "tool:clipboard",
            onClick = { onToolClick(OverlayView.CLIPBOARD) }
        )
        ToolbarIconButton(
            icon = Icons.Default.Translate,
            palette = palette,
            modifier = Modifier.weight(1f),
            testTag = "tool:translate",
            onClick = { onToolClick(OverlayView.NONE) }
        )
        ToolbarIconButton(
            icon = Icons.Default.Gif,
            palette = palette,
            modifier = Modifier.weight(1f),
            testTag = "tool:gif",
            onClick = { onToolClick(OverlayView.NONE) }
        )
        ToolbarIconButton(
            icon = Icons.Default.Edit,
            palette = palette,
            modifier = Modifier.weight(1f),
            testTag = "tool:text_editor",
            onClick = { onAction("text_editor") }
        )
        ToolbarIconButton(
            icon = Icons.Default.Mic,
            palette = palette,
            modifier = Modifier.weight(1f),
            testTag = "tool:voice",
            onClick = { onAction("voice") }
        )
        ToolbarIconButton(
            icon = Icons.Default.MoreHoriz,
            palette = palette,
            isSpecial = true,
            modifier = Modifier.weight(1f),
            testTag = "tool:more",
            onClick = onMoreClick
        )
    }
}

@Composable
fun ToolbarIconButton(
    icon: ImageVector,
    palette: KeyboardPalette,
    modifier: Modifier = Modifier,
    isSpecial: Boolean = false,
    testTag: String? = null,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .drawBehind {
                // FUTO draws a round pad, not a stretched rounded rectangle: a
                // 32.dp circle centred in the 40.dp slot, so seven fit side by
                // side without sliding.
                drawCircle(
                    color = if (isSpecial) palette.paperDeep else palette.keyBg,
                    radius = 16.dp.toPx()
                )
            }
            .clip(CircleShape)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = palette.ink,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * The voice-input window.
 *
 * FUTO opens a *window* for the mic instead of starting a recognizer silently
 * behind the keyboard, so the user can see the mic is live, watch words appear
 * as they speak, and stop. This is that window for the platform recognizer: a
 * pulsing mic while [state] is active, the live caption underneath, and a
 * one-tap way out when the engine is missing or the mic was refused.
 */
@Composable
fun VoiceInputOverlay(
    palette: KeyboardPalette,
    state: VoiceInputState,
    onStop: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val active = state.isActive
    // Draw-only pulse: it scales/alpha-fades a halo through graphicsLayer so the
    // animation never re-measures the keyboard on each frame.
    val transition = rememberInfiniteTransition(label = "voice")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "voice-pulse"
    )

    val dismiss = {
        if (active) onStop()
        onClose()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(230.dp)
            .padding(horizontal = 8.dp)
            .testTag("voice:overlay"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Voice input",
                color = palette.ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = dismiss,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("voice:close")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close voice input",
                    tint = palette.inkSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .graphicsLayer(
                        scaleX = 0.65f + 0.55f * pulse,
                        scaleY = 0.65f + 0.55f * pulse,
                        alpha = if (active) 0.30f * (1f - pulse) else 0f
                    )
                    .clip(CircleShape)
                    .background(palette.lime)
            )
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(if (active) palette.lime else palette.keyBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = if (active) palette.onAccent else palette.inkSecondary,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = voiceStatusText(state),
            color = if (state.phase == VoicePhase.ERROR) palette.ink else palette.inkSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )

        if (state.partial.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "\u201C${state.partial}\u201D",
                color = palette.ink,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.weight(1f))

        VoiceActionButton(
            label = if (active) "Stop" else "Close",
            primary = active,
            palette = palette,
            testTag = "voice:action",
            onClick = dismiss
        )

        Spacer(Modifier.height(6.dp))
    }
}

/** The one-line status under the mic for each phase of the session. */
internal fun voiceStatusText(state: VoiceInputState): String = when (state.phase) {
    VoicePhase.IDLE -> "Tap the mic to speak"
    VoicePhase.STARTING -> "Starting\u2026"
    VoicePhase.LISTENING -> "Listening\u2026"
    VoicePhase.DONE -> "Added"
    VoicePhase.ERROR, VoicePhase.UNAVAILABLE, VoicePhase.PERMISSION ->
        state.message ?: "Voice input failed"
}

@Composable
private fun VoiceActionButton(
    label: String,
    primary: Boolean,
    palette: KeyboardPalette,
    testTag: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(40.dp)
            .widthIn(min = 132.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (primary) palette.lime else palette.keyBg)
            .testTag(testTag)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (primary) palette.onAccent else palette.ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

data class ActionCardItem(
    val title: String,
    val icon: ImageVector,
    val actionId: String
)

/**
 * The single source of truth for the All Actions grid. Kept top-level so the
 * keyboard's grid and the settings "Actions" page cannot drift apart.
 */
val allActionCards: List<ActionCardItem> = listOf(
    ActionCardItem("Language switch key", Icons.Default.Language, "language"),
    ActionCardItem("Undo", Icons.AutoMirrored.Filled.Undo, "undo"),
    ActionCardItem("Redo", Icons.AutoMirrored.Filled.Redo, "redo"),
    ActionCardItem("Text Editor", Icons.Default.Edit, "text_editor"),
    ActionCardItem("Clipboard Manager", Icons.Default.ContentPaste, "clipboard"),
    ActionCardItem("Theme Switcher", Icons.Default.Palette, "theme"),
    ActionCardItem("Keyboard Modes", Icons.Default.Keyboard, "modes"),
    ActionCardItem("Emojis", Icons.Default.Face, "emojis"),
    ActionCardItem("Voice Input", Icons.Default.Mic, "voice"),
    ActionCardItem("Settings", Icons.Default.Settings, "settings"),
    ActionCardItem("Paste from Clipboard", Icons.Default.ContentPasteGo, "paste"),
    ActionCardItem("Cut to Clipboard", Icons.Default.ContentCut, "cut"),
    ActionCardItem("Copy to Clipboard", Icons.Default.ContentCopy, "copy"),
    ActionCardItem("Select All", Icons.Default.SelectAll, "select_all"),
    ActionCardItem("Arrow Up", Icons.Default.KeyboardArrowUp, "arrow_up"),
    ActionCardItem("Arrow Down", Icons.Default.KeyboardArrowDown, "arrow_down"),
    ActionCardItem("Arrow Left", Icons.AutoMirrored.Filled.ArrowBack, "arrow_left"),
    ActionCardItem("Arrow Right", Icons.AutoMirrored.Filled.ArrowForward, "arrow_right"),
    ActionCardItem("WordStyles", Icons.Default.FormatBold, "word_styles"),
    ActionCardItem("AutoCorrect", Icons.Default.Spellcheck, "auto_correct"),
    ActionCardItem("Hide keyboard", Icons.Default.KeyboardHide, "hide_keyboard"),
    ActionCardItem("Translate", Icons.Default.Translate, "translate")
)

@Composable
fun AllActionsGrid(
    palette: KeyboardPalette,
    onClose: () -> Unit,
    onActionClick: (String) -> Unit
) {
    val actions = allActionCards

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .padding(horizontal = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("allactions:back")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = palette.ink,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = "All Actions",
                    color = palette.ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            TextButton(
                onClick = {},
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.line),
                colors = ButtonDefaults.textButtonColors(containerColor = palette.keyBg)
            ) {
                Text("Edit actions", color = palette.ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }


        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(actions) { action ->
                Box(
                    modifier = Modifier
                        .height(60.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.keyBg)
                        .border(1.dp, palette.line, RoundedCornerShape(10.dp))
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onActionClick(action.actionId) }
                        )
                        .padding(6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = null,
                            tint = palette.ink,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = action.title,
                            color = palette.ink,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * The emoji catalogue: a header emoji plus the entries under it. Kept top-level
 * so the keypad and the settings "Emoji & media" page read the same list.
 */
val emojiCategories: List<Pair<String, List<String>>> = listOf(
    "😀" to listOf("😀","😃","😄","😁","😆","😅","😂","🤣","🥲","🥹","😊","😇","🙂","🙃","😉","😌","😍","🥰","😘","😋","😛","😜","🤪","😝","🤑","🤗","🤭","🤫","🤔","🤐","🤨","😐","😑","😶","😏","😒","🙄","😬","🤥","😌","😔","😪","🤤","😴","😷","🤒","🤕","🤢","🤮","🤧","🥵","🥶","🥴","😵","🤯","🤠","🥳","😎","🤓"),
    "👍" to listOf("👍","👎","👌","🤌","🤏","✌️","🤞","🫰","🤟","🤘","🤙","👈","👉","👆","👇","☝️","✋","🤚","🖐️","🖖","👋","💪","✍️","🙏","🤝","👏","🙌","👐","🤲"),
    "❤️" to listOf("❤️","🧡","💛","💚","💙","💜","🖤","🤍","🤎","💔","❤️‍🔥","❤️‍🩹","❣️","💕","💞","💓","💗","💖","💘","💝"),
    "🌿" to listOf("🌿","🌱","🌲","🌳","🌴","🌵","🌾","☘️","🍀","🍁","🍂","🍃","🍄","🌰","🌸","🌺","🌼","🌻","🌞","⭐","🌟","⚡","🔥"),
    "☕" to listOf("☕","🍵","🧃","🥤","🍶","🍺","🍻","🥂","🍷","🥃","🍸","🍹","🍔","🍟","🍕","🌭","🥪","🌮","🌯","🥗","🍱","🍜","🍣","🍰","🍩")
)

/**
 * Display titles for [emojiCategories], index-aligned. The picker uses them in its
 * header and as the accessibility label for each tab; the settings page keeps
 * showing the header glyph.
 */
val emojiCategoryNames: List<String> = listOf(
    "Smileys & Emotion",
    "People & Body",
    "Hearts",
    "Animals & Nature",
    "Food & Drink"
)

@Composable
fun EmojiPickerGrid(
    palette: KeyboardPalette,
    recentEmojis: List<String> = emptyList(),
    panelHeight: Dp = 300.dp,
    onClose: () -> Unit,
    onBackspace: () -> Unit = {},
    onEmojiClick: (String) -> Unit
) {
    // Tabs are keyed by title, not index, so prepending a "Recent" tab the moment
    // the first emoji is used cannot shift the selection out from under the user.
    val tabs = remember(recentEmojis) {
        buildList {
            if (recentEmojis.isNotEmpty()) add(EmojiTab("Recent", "🕒", recentEmojis))
            emojiCategories.forEachIndexed { index, (glyph, items) ->
                add(EmojiTab(emojiCategoryNames.getOrElse(index) { glyph }, glyph, items))
            }
        }
    }
    var selectedTitle by remember { mutableStateOf("") }
    val selectedTab = tabs.firstOrNull { it.title == selectedTitle } ?: tabs.first()
    val gridState = rememberLazyGridState()

    // Jump back to the top whenever the category changes, so a search-free switch
    // always lands on the first row (FUTO snaps the strip the same way).
    LaunchedEffect(selectedTab.title) { gridState.scrollToItem(0) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(panelHeight)
            .testTag("emoji:panel")
    ) {
        // Slim header: back chevron + the active category name. This replaces the
        // floating toolbar row, which the view hides while the panel is open.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("emoji:back")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to keyboard",
                    tint = palette.ink,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = selectedTab.title,
                color = palette.ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp)
            )
        }

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(8),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 4.dp)
        ) {
            items(selectedTab.items) { emo ->
                EmojiChoiceCell(palette = palette, emoji = emo, onClick = { onEmojiClick(emo) })
            }
        }

        // Bottom navigation, FUTO-order: ABC on the left, the scrollable category
        // strip in the middle, backspace on the right.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onClose,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .height(32.dp)
                    .testTag("emoji:abc"),
                contentPadding = PaddingValues(horizontal = 10.dp),
                colors = ButtonDefaults.textButtonColors(containerColor = palette.keyBg)
            ) {
                Text("ABC", color = palette.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }

            LazyRow(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                itemsIndexed(tabs) { index, tab ->
                    val isSelected = tab.title == selectedTab.title
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) palette.lime else Color.Transparent)
                            .testTag("emoji:tab:$index")
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { selectedTitle = tab.title }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tab.glyph,
                            fontSize = 18.sp,
                            color = if (isSelected) palette.onAccent else palette.inkSecondary
                        )
                    }
                }
            }

            IconButton(
                onClick = onBackspace,
                modifier = Modifier
                    .size(40.dp)
                    .testTag("emoji:backspace")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = "Backspace",
                    tint = palette.ink,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private data class EmojiTab(val title: String, val glyph: String, val items: List<String>)

/**
 * One emoji cell. FUTO draws a bare glyph on the board (its cells have a ripple,
 * not a filled key), which is what makes the grid read as a picker and not a
 * calculator. A pressed wash stands in for the ripple.
 */
@Composable
private fun EmojiChoiceCell(
    palette: KeyboardPalette,
    emoji: String,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (pressed) palette.keyBg else Color.Transparent)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(text = emoji, fontSize = 24.sp)
    }
}

@Composable
fun ClipboardListPanel(
    palette: KeyboardPalette,
    clips: List<String>,
    onClose: () -> Unit,
    onClearAll: () -> Unit,
    onClipClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .padding(horizontal = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("clipboard:back")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = palette.ink,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = "Clipboard History",
                    color = palette.ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            TextButton(onClick = onClearAll) {
                Text("Clear All", color = palette.inkSecondary, fontSize = 11.sp)
            }
        }

        if (clips.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No clipboard items yet",
                    color = palette.muted,
                    fontSize = 13.sp
                )
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                clips.forEach { clip ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.keyBg)
                            .border(1.dp, palette.line, RoundedCornerShape(8.dp))
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onClipClick(clip) }
                            )
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = clip,
                            color = palette.ink,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "Paste",
                            color = palette.limeShadow,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
