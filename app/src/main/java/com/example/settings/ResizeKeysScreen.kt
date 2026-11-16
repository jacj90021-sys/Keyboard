package com.example.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.keyboard.KeyboardPrefs
import com.example.keyboard.KeyboardPrefsRepository
import com.example.keyboard.PaperLimeKeyboardView
import com.example.keyboard.ResizeLimits
import com.example.keyboard.ResizeMode
import com.example.keyboard.ResizeSettings
import com.example.keyboard.ResizeSide
import com.example.keyboard.getPalette
import com.example.ui.theme.DmSans
import com.example.ui.theme.Tokens
import kotlinx.coroutines.launch

/**
 * "Resize keyboard".
 *
 * Clean-room rewrite of the old −/+ stepper. The screen offers four modes —
 * Standard (the default full-width board), One hand, Split and Float — over a
 * live preview of the real keyboard. Drag the handles on the preview to resize;
 * every change is clamped to a sane range, and Apply persists the settings so
 * the IME keyboard picks them up.
 */
@Composable
fun ResizeKeysPage(prefs: KeyboardPrefs, repository: KeyboardPrefsRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val palette = remember(prefs.theme) { getPalette(prefs.theme) }
    var settings by remember { mutableStateOf(prefs.resizeSettings) }
    var applied by remember { mutableStateOf(false) }

    PageScaffold(title = "Resize keyboard", onBack = onBack) {
        SegControl(
            options = ResizeMode.entries.map { it.label },
            selectedIndex = settings.mode.ordinal,
            modifier = Modifier.fillMaxWidth(),
            onSelect = { settings = settings.copy(mode = ResizeMode.entries[it]) }
        )

        Spacer(Modifier.height(16.dp))
        Label("Preview — drag the handles")

        ResizeStage(
            settings = settings,
            prefs = prefs,
            palette = palette,
            onSettings = { settings = it; applied = false }
        )

        Spacer(Modifier.height(16.dp))

        when (settings.mode) {
            ResizeMode.STANDARD -> Label("Full-width standard keyboard")
            ResizeMode.ONE_HANDED -> {
                Label("Side")
                SegControl(
                    options = ResizeSide.entries.map { it.label },
                    selectedIndex = settings.oneHandedSide.ordinal,
                    modifier = Modifier.fillMaxWidth(),
                    onSelect = { settings = settings.copy(oneHandedSide = ResizeSide.entries[it]) }
                )
            }
            ResizeMode.SPLIT -> Label("Keyboard share: ${(settings.splitFraction * 100).toInt()}%")
            ResizeMode.FLOATING -> Label(
                "Size: ${settings.floatingWidth.toInt()} × ${settings.floatingHeight.toInt()} dp"
            )
        }

        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { settings = ResizeSettings.Default; applied = false },
                modifier = Modifier.weight(1f)
            ) { Text("Reset") }
            Button(
                onClick = {
                    scope.launch {
                        repository.setResizeSettings(settings)
                        applied = true
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text(if (applied) "Applied" else "Apply") }
        }
        if (applied) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Saved. The keyboard now uses this size.",
                color = Tokens.Muted,
                fontFamily = DmSans,
                fontSize = 12.sp
            )
        }
    }
}

/** The preview stage: a "screen" with the real keyboard sized per mode and a
 *  set of draggable handles overlaid. */
@Composable
private fun ResizeStage(
    settings: ResizeSettings,
    prefs: KeyboardPrefs,
    palette: com.example.keyboard.KeyboardPalette,
    onSettings: (ResizeSettings) -> Unit
) {
    val density = LocalDensity.current
    var stageWidthPx by remember { mutableStateOf(1) }

    val alignment = when (settings.mode) {
        ResizeMode.ONE_HANDED ->
            if (settings.oneHandedSide == ResizeSide.LEFT) Alignment.BottomStart else Alignment.BottomEnd
        ResizeMode.FLOATING -> Alignment.BottomStart
        else -> Alignment.BottomCenter
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .onSizeChanged { stageWidthPx = it.width }
            .clip(RoundedCornerShape(18.dp))
            .background(Tokens.Chip)
            .border(1.dp, Tokens.Line, RoundedCornerShape(18.dp))
    ) {
        Box(
            modifier = Modifier
                .align(alignment)
                .then(stageSizeModifier(settings))
        ) {
            PaperLimeKeyboardView(
                palette = palette,
                prefs = prefs.copy(keyHeight = settings.effectiveKeyHeight(prefs.keyHeight)),
                preview = true,
                onCommitText = {},
                onDelete = {},
                onEnter = {}
            )
        }

        ResizeHandles(settings = settings, stageWidthPx = stageWidthPx, onSettings = onSettings)
    }
}

private fun stageSizeModifier(settings: ResizeSettings): Modifier = when (settings.mode) {
    ResizeMode.STANDARD ->
        Modifier.fillMaxWidth().padding(horizontal = settings.sidePadding.dp)
    ResizeMode.ONE_HANDED ->
        Modifier.width(settings.oneHandedWidth.dp)
    ResizeMode.SPLIT ->
        Modifier.fillMaxWidth(settings.splitFraction.coerceIn(0.1f, 0.9f))
    ResizeMode.FLOATING ->
        Modifier
            .width(settings.floatingWidth.dp)
            .offset(x = settings.floatingX.dp, y = (-settings.floatingY).dp)
}

/** Five draggable handles: left/right/top/bottom edges and the centre. */
@Composable
private fun BoxScope.ResizeHandles(
    settings: ResizeSettings,
    stageWidthPx: Int,
    onSettings: (ResizeSettings) -> Unit
) {
    val density = LocalDensity.current
    val dpPerPx = 1f / density.density

    fun Float.toDp(): Float = this * dpPerPx

    // Left / right edges adjust the horizontal size (mode dependent).
    Handle(
        modifier = Modifier.align(Alignment.CenterStart).offset(x = (-11).dp),
        onDrag = { dx, _ ->
            val d = dx.toDp()
            onSettings(
                when (settings.mode) {
                    ResizeMode.STANDARD ->
                        settings.copy(sidePadding = (settings.sidePadding + d).coerceIn(0f, ResizeLimits.MAX_SIDE_PADDING))
                    ResizeMode.ONE_HANDED ->
                        settings.copy(oneHandedWidth = (settings.oneHandedWidth - d).coerceIn(ResizeLimits.MIN_ONE_HANDED_WIDTH, ResizeLimits.MAX_ONE_HANDED_WIDTH))
                    ResizeMode.SPLIT ->
                        settings.copy(splitFraction = (settings.splitFraction - d / (stageWidthPx * dpPerPx)).coerceIn(ResizeLimits.MIN_SPLIT_FRACTION, ResizeLimits.MAX_SPLIT_FRACTION))
                    ResizeMode.FLOATING ->
                        settings.copy(floatingWidth = (settings.floatingWidth - d).coerceIn(ResizeLimits.MIN_FLOATING_WIDTH, ResizeLimits.MAX_FLOATING_WIDTH))
                }
            )
        }
    )
    Handle(
        modifier = Modifier.align(Alignment.CenterEnd).offset(x = 11.dp),
        onDrag = { dx, _ ->
            val d = dx.toDp()
            onSettings(
                when (settings.mode) {
                    ResizeMode.STANDARD ->
                        settings.copy(sidePadding = (settings.sidePadding - d).coerceIn(0f, ResizeLimits.MAX_SIDE_PADDING))
                    ResizeMode.ONE_HANDED ->
                        settings.copy(oneHandedWidth = (settings.oneHandedWidth + d).coerceIn(ResizeLimits.MIN_ONE_HANDED_WIDTH, ResizeLimits.MAX_ONE_HANDED_WIDTH))
                    ResizeMode.SPLIT ->
                        settings.copy(splitFraction = (settings.splitFraction + d / (stageWidthPx * dpPerPx)).coerceIn(ResizeLimits.MIN_SPLIT_FRACTION, ResizeLimits.MAX_SPLIT_FRACTION))
                    ResizeMode.FLOATING ->
                        settings.copy(floatingWidth = (settings.floatingWidth + d).coerceIn(ResizeLimits.MIN_FLOATING_WIDTH, ResizeLimits.MAX_FLOATING_WIDTH))
                }
            )
        }
    )
    // Top edge: height.
    Handle(
        modifier = Modifier.align(Alignment.TopCenter).offset(y = (-11).dp),
        onDrag = { _, dy ->
            val d = dy.toDp()
            onSettings(
                when (settings.mode) {
                    ResizeMode.FLOATING ->
                        settings.copy(floatingHeight = (settings.floatingHeight - d).coerceIn(ResizeLimits.MIN_FLOATING_HEIGHT, ResizeLimits.MAX_FLOATING_HEIGHT))
                    else ->
                        settings.copy(heightAddition = (settings.heightAddition - d).coerceIn(ResizeLimits.MIN_HEIGHT_ADDITION, ResizeLimits.MAX_HEIGHT_ADDITION))
                }
            )
        }
    )
    // Bottom edge: bottom gap.
    Handle(
        modifier = Modifier.align(Alignment.BottomCenter).offset(y = 11.dp),
        onDrag = { _, dy ->
            val d = dy.toDp()
            onSettings(
                settings.copy(bottomPadding = (settings.bottomPadding + d).coerceIn(0f, ResizeLimits.MAX_BOTTOM_PADDING))
            )
        }
    )
    // Centre: move the floating keyboard.
    Handle(
        modifier = Modifier.align(Alignment.Center),
        onDrag = { dx, dy ->
            if (settings.mode == ResizeMode.FLOATING) {
                onSettings(
                    settings.copy(
                        floatingX = (settings.floatingX + dx.toDp()).coerceAtLeast(0f),
                        floatingY = (settings.floatingY - dy.toDp()).coerceAtLeast(0f)
                    )
                )
            }
        }
    )
}

@Composable
private fun BoxScope.Handle(
    modifier: Modifier,
    onDrag: (Float, Float) -> Unit
) {
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(Tokens.Lime)
            .border(2.dp, Tokens.Ink, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures { _, amount -> onDrag(amount.x, amount.y) }
            }
    )
}

