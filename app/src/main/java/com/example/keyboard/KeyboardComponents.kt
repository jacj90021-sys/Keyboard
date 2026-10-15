@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.keyboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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

enum class KeyStyle {
    REGULAR,
    SPECIAL,
    LIME,
    FN,
    NUMPAD_SWITCH
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
    onSlideDrag: ((Float) -> Unit)? = null
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
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                    onLongClick = onLongClick
                ),
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

@Composable
fun SuggestionsBar(
    palette: KeyboardPalette,
    isExpanded: Boolean,
    isFnMode: Boolean,
    showChevron: Boolean,
    onToggleExpand: () -> Unit,
    onSuggestionClick: (String) -> Unit
) {
    val defaultSuggestions = remember { listOf("Yes", "Okay", "Thanks", "Awesome") }
    val fnSuggestions = remember { listOf("Esc", "Tab", "Ctrl", "Alt", "Home", "End") }
    val suggestions = if (isFnMode) fnSuggestions else defaultSuggestions

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
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .background(palette.paper)
            .border(1.dp, palette.line, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
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
            .height(35.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSpecial) palette.paperDeep else palette.keyBg)
            .border(1.dp, palette.line, RoundedCornerShape(10.dp))
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

data class ActionCardItem(
    val title: String,
    val icon: ImageVector,
    val actionId: String
)

@Composable
fun AllActionsGrid(
    palette: KeyboardPalette,
    onClose: () -> Unit,
    onActionClick: (String) -> Unit
) {
    val actions = remember {
        listOf(
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
    }

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
                    modifier = Modifier.size(32.dp)
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

@Composable
fun EmojiPickerGrid(
    palette: KeyboardPalette,
    onClose: () -> Unit,
    onEmojiClick: (String) -> Unit
) {
    val categories = remember {
        listOf(
            "😀" to listOf("😀","😃","😄","😁","😆","😅","😂","🤣","🥲","🥹","😊","😇","🙂","🙃","😉","😌","😍","🥰","😘","😋","😛","😜","🤪","😝","🤑","🤗","🤭","🤫","🤔","🤐","🤨","😐","😑","😶","😏","😒","🙄","😬","🤥","😌","😔","😪","🤤","😴","😷","🤒","🤕","🤢","🤮","🤧","🥵","🥶","🥴","😵","🤯","🤠","🥳","😎","🤓"),
            "👍" to listOf("👍","👎","👌","🤌","🤏","✌️","🤞","🫰","🤟","🤘","🤙","👈","👉","👆","👇","☝️","✋","🤚","🖐️","🖖","👋","💪","✍️","🙏","🤝","👏","🙌","👐","🤲"),
            "❤️" to listOf("❤️","🧡","💛","💚","💙","💜","🖤","🤍","🤎","💔","❤️‍🔥","❤️‍🩹","❣️","💕","💞","💓","💗","💖","💘","💝"),
            "🌿" to listOf("🌿","🌱","🌲","🌳","🌴","🌵","🌾","☘️","🍀","🍁","🍂","🍃","🍄","🌰","🌸","🌺","🌼","🌻","🌞","⭐","🌟","⚡","🔥"),
            "☕" to listOf("☕","🍵","🧃","🥤","🍶","🍺","🍻","🥂","🍷","🥃","🍸","🍹","🍔","🍟","🍕","🌭","🥪","🌮","🌯","🥗","🍱","🍜","🍣","🍰","🍩")
        )
    }

    var selectedCategoryIndex by remember { mutableIntStateOf(0) }
    val currentEmojis = categories[selectedCategoryIndex].second

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
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = palette.ink,
                    modifier = Modifier.size(18.dp)
                )
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(categories.indices.toList()) { index ->
                    val isSelected = index == selectedCategoryIndex
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) palette.lime else palette.keyBg)
                            .border(1.dp, palette.line, RoundedCornerShape(8.dp))
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { selectedCategoryIndex = index }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = categories[index].first, fontSize = 16.sp)
                    }
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(currentEmojis) { emo ->
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(palette.keyBg)
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onEmojiClick(emo) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = emo, fontSize = 18.sp)
                }
            }
        }
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
                    modifier = Modifier.size(32.dp)
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
