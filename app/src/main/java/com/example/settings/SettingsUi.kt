package com.example.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.keyboard.ThemeChoice
import com.example.keyboard.themeFor
import com.example.ui.theme.DmSans
import com.example.ui.theme.Manrope
import com.example.ui.theme.Tokens

val GkGray = Color(0x1478786E)

@Composable
fun BrandMark(size: Int = 43) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.32f).dp))
            .background(Tokens.Lime),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Keyboard,
            contentDescription = null,
            tint = Tokens.Ink,
            modifier = Modifier.size((size * 0.56f).dp)
        )
    }
}

@Composable
fun SectionHeading(title: String, subtitle: String? = null) {
    Column(modifier = Modifier.padding(horizontal = 2.dp)) {
        Text(
            text = title,
            color = Tokens.Ink,
            fontFamily = Manrope,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 25.sp,
            letterSpacing = (-0.95).sp
        )
        if (subtitle != null) {
            Spacer(Modifier.height(7.dp))
            Text(
                text = subtitle,
                color = Tokens.Muted,
                fontFamily = DmSans,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                modifier = Modifier.widthIn(max = 320.dp)
            )
        }
    }
}

/**
 * The chip-style switch used across the settings screens.
 * Off = chip track + line knob. On = ink track + lime knob.
 */
@Composable
fun ToggleSwitch(
    on: Boolean,
    modifier: Modifier = Modifier,
    onColor: Color = Tokens.Ink,
    knobOnColor: Color = Tokens.Lime,
    trackOffColor: Color = Tokens.Line
) {
    Box(
        modifier = modifier
            .size(width = 46.dp, height = 26.dp)
            .clip(CircleShape)
            .background(if (on) onColor else trackOffColor),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(start = if (on) 23.dp else 3.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(if (on) knobOnColor else Tokens.Surface)
        )
    }
}

@Composable
fun SegControl(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Tokens.Chip)
            .padding(4.dp)
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .then(if (on) Modifier.background(Tokens.Surface) else Modifier)
                    .clickable { onSelect(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (on) Tokens.Ink else Tokens.Muted,
                    fontFamily = DmSans,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onToggle() }
            .padding(horizontal = 15.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Tokens.Ink,
                fontFamily = DmSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = Tokens.Muted,
                fontFamily = DmSans,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        ToggleSwitch(on = checked, modifier = Modifier.alphaIfDisabled(enabled))
    }
}

private fun Modifier.alphaIfDisabled(enabled: Boolean): Modifier =
    if (enabled) this else this.then(Modifier)

@Composable
fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailingValue: String? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Box(
            modifier = Modifier
                .size(37.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Tokens.Chip),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Tokens.Ink, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Tokens.Ink,
                fontFamily = DmSans,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitle,
                color = Tokens.Muted,
                fontFamily = DmSans,
                fontSize = 12.sp
            )
        }
        if (trailingValue != null) {
            Text(
                text = trailingValue,
                color = Tokens.Muted,
                fontFamily = DmSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Tokens.Muted,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
fun ThemeSwatch(choice: ThemeChoice, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val theme = themeFor(choice)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Tokens.Surface)
            .border(
                width = 2.dp,
                color = if (selected) Tokens.Ink else Tokens.Line,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable { onClick() }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(theme.bg)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = choice.displayName,
            color = Tokens.Ink,
            fontFamily = DmSans,
            fontWeight = FontWeight.Bold,
            fontSize = 11.5.sp,
            textAlign = TextAlign.Center
        )
    }
}
