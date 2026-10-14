package com.example.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.DmSans
import com.example.ui.theme.Manrope
import com.example.ui.theme.Tokens

private data class SetupStep(
    val title: String,
    val subtitle: String,
    val cta: String
)

@Composable
fun OnboardingScreen(
    enabled: Boolean,
    selected: Boolean,
    onOpenKeyboardSettings: () -> Unit,
    onChooseKeyboard: () -> Unit,
    onPersonalize: () -> Unit
) {
    val step = when {
        !enabled -> 1
        !selected -> 2
        else -> 3
    }
    val steps = listOf(
        SetupStep("Turn on keyboard", "Allow it in Android keyboard settings", "Open keyboard settings"),
        SetupStep("Select keyboard", "Set it as your active keyboard", "Choose keyboard"),
        SetupStep("Make it yours", "Theme, height and extras", "Personalize")
    )

    val title = when (step) {
        3 -> "Almost there"
        2 -> "One more tap"
        else -> "Set up in 2 quick steps"
    }
    val lead = when (step) {
        3 -> "Your keyboard is live. Now give it your style."
        2 -> "Now tell Android to use it."
        else -> "Android needs your OK before a new keyboard can work. It takes under a minute."
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.Paper),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 22.dp)
        ) {
            // Top brand mark
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Tokens.Lime),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.Keyboard,
                    contentDescription = null,
                    tint = Tokens.Ink,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = title,
                color = Tokens.Ink,
                fontFamily = Manrope,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 29.sp,
                letterSpacing = (-1.2).sp,
                lineHeight = 33.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = lead,
                color = Tokens.Muted,
                fontFamily = DmSans,
                fontSize = 14.sp,
                lineHeight = 22.sp
            )

            Spacer(Modifier.height(16.dp))
            ProgressBar(step)
            Spacer(Modifier.height(16.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                steps.forEachIndexed { index, s ->
                    val n = index + 1
                    val done = n < step
                    val active = n == step
                    SetupCard(
                        number = n,
                        step = s,
                        done = done,
                        active = active,
                        onCta = {
                            when (n) {
                                1 -> onOpenKeyboardSettings()
                                2 -> onChooseKeyboard()
                                else -> onPersonalize()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressBar(step: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        repeat(3) { i ->
            val done = i < step - 1
            val current = i == step - 1
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        when {
                            current -> Tokens.Lime
                            done -> Tokens.Ink
                            else -> Tokens.Line
                        }
                    )
                    .then(
                        if (current) Modifier.border(1.dp, Tokens.LimeDeep, RoundedCornerShape(9.dp))
                        else Modifier
                    )
            )
        }
    }
}

@Composable
private fun SetupCard(
    number: Int,
    step: SetupStep,
    done: Boolean,
    active: Boolean,
    onCta: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (active) Modifier.shadow(10.dp, RoundedCornerShape(22.dp), clip = false)
                else Modifier
            )
            .clip(RoundedCornerShape(22.dp))
            .background(Tokens.Surface)
            .border(
                width = 1.dp,
                color = if (active) Tokens.Ink else Tokens.Line,
                shape = RoundedCornerShape(22.dp)
            )
            .padding(15.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            done -> Tokens.Ink
                            active -> Tokens.Lime
                            else -> Tokens.Chip
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (done) "✓" else number.toString(),
                    color = if (done) Tokens.Paper else Tokens.Ink,
                    fontFamily = Manrope,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = step.title,
                    color = Tokens.Ink,
                    fontFamily = DmSans,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (done) "Done" else step.subtitle,
                    color = Tokens.Muted,
                    fontFamily = DmSans,
                    fontSize = 12.sp
                )
            }
        }

        if (active) {
            Spacer(Modifier.height(14.dp))
            LimeButton(text = step.cta, onClick = onCta)
        }
    }
}

@Composable
fun LimeButton(text: String, modifier: Modifier = Modifier, dark: Boolean = false, onClick: () -> Unit) {
    val bg = if (dark) Tokens.Ink else Tokens.Lime
    val shadow = if (dark) androidx.compose.ui.graphics.Color(0x40000000) else Tokens.LimeDeep
    val fg = if (dark) Tokens.Paper else Tokens.Ink
    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = shadow,
                    topLeft = Offset(0f, size.height - 4.dp.toPx()),
                    size = Size(size.width, 4.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx())
                )
            }
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = fg,
            fontFamily = DmSans,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 15.sp
        )
    }
}
