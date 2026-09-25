package com.jellycine.app.ui.components.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jellycine.data.model.BaseItemDto
import com.jellycine.shared.R
import java.util.Locale

private val BannerCardBg = Color(0xFF1E1E1E)
private val BannerCardBorder = Color.White.copy(alpha = 0.08f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoImageBannerItemCard(
    item: BaseItemDto,
    displayName: String,
    modifier: Modifier = Modifier,
    fillMaxWidth: Boolean = false,
    useLandscapeLayout: Boolean = false,
    dynamicWidth: Boolean = false,
    onClick: () -> Unit = {}
) {
    val bannerWidth = if (useLandscapeLayout) 230.dp else 190.dp
    val bannerHeight = 56.dp

    val episodeCount = when {
        item.type == "Series" && item.userData?.unplayedItemCount != null -> item.userData?.unplayedItemCount
        item.type == "Series" && item.episodeCount != null && item.episodeCount!! > 0 -> item.episodeCount!!
        item.type == "Series" && item.recursiveItemCount != null && item.recursiveItemCount!! > 0 -> item.recursiveItemCount!!
        else -> null
    }

    val isPlayed = item.userData?.played == true
    val isFullyWatched = item.type == "Series" && item.userData?.unplayedItemCount == 0
    val yearText = item.productionYear?.toString() ?: item.premiereDate?.take(4)

    val cardModifier = when {
        fillMaxWidth -> {
            modifier
                .fillMaxWidth()
                .height(bannerHeight)
        }
        dynamicWidth -> {
            modifier
                .widthIn(min = 72.dp, max = 220.dp)
                .wrapContentWidth()
                .height(bannerHeight)
        }
        else -> {
            modifier
                .width(bannerWidth)
                .height(bannerHeight)
        }
    }

    Card(
        modifier = cardModifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = BannerCardBg),
        border = BorderStroke(1.dp, BannerCardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        onClick = onClick
    ) {
        Box(
            modifier = if (dynamicWidth) Modifier.fillMaxHeight().wrapContentWidth() else Modifier.fillMaxSize()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .then(
                        if (fillMaxWidth || !dynamicWidth) Modifier.fillMaxWidth()
                        else Modifier.wrapContentWidth()
                    )
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Column(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = if (dynamicWidth && yearText == null && episodeCount == null && !isPlayed) {
                        Alignment.CenterHorizontally
                    } else {
                        Alignment.Start
                    }
                ) {
                    Text(
                        text = displayName,
                        color = Color.White,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        yearText?.let { year ->
                            Text(
                                text = year,
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 11.5.sp,
                                maxLines = 1
                            )
                        }
                        item.communityRating?.let { rating ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Star,
                                    contentDescription = null,
                                    tint = Color(0xFFFFB800),
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = String.format(Locale.US, "%.1f", rating),
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        if (item.type == "Episode") {
                            val s = item.parentIndexNumber
                            val e = item.indexNumber
                            if (s != null && e != null) {
                                Text(
                                    text = "S${s}:E${e}",
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                if (episodeCount != null && episodeCount > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.padding(start = 6.dp)
                    ) {
                        Text(
                            text = "$episodeCount",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                } else if (isFullyWatched || (episodeCount == null && isPlayed)) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Watched",
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(16.dp)
                    )
                }
            }

            val positionTicks = item.userData?.playbackPositionTicks ?: 0L
            val totalTicks = item.runTimeTicks ?: 0L
            if (positionTicks > 0 && totalTicks > 0) {
                val progress = (positionTicks.toFloat() / totalTicks.toFloat()).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(progress)
                        .height(2.5.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoImageBannerFolderCard(
    item: BaseItemDto,
    displayName: String,
    modifier: Modifier = Modifier,
    fillMaxWidth: Boolean = true,
    itemCount: Int? = null,
    onClick: () -> Unit = {}
) {
    val bannerHeight = 56.dp
    val cardModifier = if (fillMaxWidth) {
        modifier
            .fillMaxWidth()
            .height(bannerHeight)
    } else {
        modifier
            .width(190.dp)
            .height(bannerHeight)
    }

    Card(
        modifier = cardModifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = BannerCardBg),
        border = BorderStroke(1.dp, BannerCardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    color = Color(0xFF0080FF).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = Color(0xFF0080FF),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Column(
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = displayName,
                        color = Color.White,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (itemCount != null && itemCount > 0) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.view_all_folder_item_count, itemCount),
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.5.sp,
                            maxLines = 1
                        )
                    }
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.35f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun SkeletonBannerCard(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.05f),
                        Color.White.copy(alpha = 0.02f)
                    )
                ),
                shape = RoundedCornerShape(10.dp)
            )
            .border(1.dp, Color.White.copy(alpha = 0.04f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(14.dp)
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.25f)
                    .height(10.dp)
                    .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(3.dp))
            )
        }
    }
}
