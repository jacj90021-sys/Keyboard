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
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.DmSans
import com.example.ui.theme.Tokens

enum class PopularTileType { NUMROW, TOOLBAR, SUGGEST, SPECIAL, FN, POPUP }

@Composable
fun PopularTile(
    type: PopularTileType,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) Tokens.Lime else Tokens.Chip)
            .clickable { onClick() }
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 10.dp)
    ) {
        Column {
            Spacer(Modifier.height(16.dp))
            Box(modifier = Modifier.fillMaxWidth().height(46.dp)) {
                KeyboardIllustration(type)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = label,
                color = Tokens.Ink,
                fontFamily = DmSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                lineHeight = 15.sp
            )
        }
        // Corner check circle
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 1.dp, y = (-1).dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(if (selected) Tokens.Ink else Tokens.Surface)
                .border(
                    width = 1.6.dp,
                    color = Tokens.Ink,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Tokens.Lime,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

@Composable
private fun Gk() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(9.dp))
            .background(GkGray)
    )
}

@Composable
private fun WhiteBar(
    modifier: Modifier,
    content: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(Color.White)
            .padding(horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        content = content
    )
}

@Composable
fun KeyboardIllustration(type: PopularTileType) {
    Box(modifier = Modifier.fillMaxSize()) {
        Gk()
        when (type) {
            PopularTileType.NUMROW -> {
                WhiteBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 3.dp)
                        .height(18.dp)
                        .align(Alignment.TopStart)
                        .offset(y = 13.dp)
                ) {
                    listOf("1", "2", "3", "4", "5", "6").forEach {
                        Text(it, color = Tokens.Ink, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            PopularTileType.TOOLBAR -> {
                WhiteBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 3.dp)
                        .height(20.dp)
                        .align(Alignment.TopStart)
                        .offset(y = 3.dp)
                ) {
                    listOf("☺", "▤", "文", "GIF").forEach {
                        Text(it, color = Tokens.Ink, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            PopularTileType.SUGGEST -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 3.dp)
                        .height(20.dp)
                        .align(Alignment.TopStart)
                        .offset(y = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    listOf("Yes", "Okay").forEach { w ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(7.dp))
                                .background(Color.White),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(w, color = Tokens.Ink, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            PopularTileType.SPECIAL -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = 8.dp, y = 13.dp)
                        .size(width = 28.dp, height = 26.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Text("a", color = Tokens.Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "%",
                        color = Tokens.Ink,
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 3.dp)
                    )
                }
            }

            PopularTileType.FN -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = 8.dp, y = 13.dp)
                        .size(width = 36.dp, height = 26.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Fn", color = Tokens.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            PopularTileType.POPUP -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = (-2).dp)
                        .size(width = 32.dp, height = 32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Text("B", color = Tokens.Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
