package com.example.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.DmSans
import com.example.ui.theme.Manrope
import com.example.ui.theme.Tokens

/**
 * Full-screen "Add language" picker.
 *
 * A top bar (back + title), a search field, and a scrolling list of the
 * languages that can be added. Tapping a language reports it to the caller,
 * which appends it to the active list and returns to Languages & Layouts. This
 * replaces the old bottom sheet with a proper full-screen route.
 */
@Composable
internal fun AddLanguageScreen(
    existing: Set<String>,
    onPick: (LanguageOption) -> Unit,
    onBack: () -> Unit
) {
    var query by remember { mutableStateOf("") }

    val results = remember(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            languageOptions
        } else {
            languageOptions.filter {
                it.name.contains(q, ignoreCase = true) ||
                    it.nativeName.contains(q, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.Paper)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackCircle(onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Add language",
                color = Tokens.Ink,
                fontFamily = Manrope,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 21.sp,
                letterSpacing = (-0.7).sp
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .height(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Tokens.Surface)
                .border(1.dp, Tokens.Line, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = Tokens.Muted,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(
                    color = Tokens.Ink,
                    fontFamily = DmSans,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                ),
                cursorBrush = SolidColor(Tokens.Ink),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            text = "Search languages",
                            color = Tokens.Muted,
                            fontFamily = DmSans,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                    }
                    inner()
                }
            )
            if (query.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = "Clear",
                    tint = Tokens.Muted,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { query = "" }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(results, key = { it.name }) { option ->
                val added = option.name in existing
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = !added) { onPick(option) }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = option.name,
                            color = Tokens.Ink,
                            fontFamily = DmSans,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = option.nativeName,
                            color = Tokens.Muted,
                            fontFamily = DmSans,
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.5.sp
                        )
                    }
                    Icon(
                        imageVector = if (added) Icons.Default.Check else Icons.Default.Add,
                        contentDescription = null,
                        tint = if (added) Tokens.Muted else Tokens.Ink,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}