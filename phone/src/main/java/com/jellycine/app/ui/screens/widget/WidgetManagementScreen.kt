package com.jellycine.app.ui.screens.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.jellycine.app.ui.activity.JellyCineActivity
import com.jellycine.app.widget.MediaCompactWidgetProvider
import com.jellycine.app.widget.WidgetDataManager
import com.jellycine.app.widget.WidgetListProvider
import com.jellycine.app.widget.WidgetMediaItem
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetManagementScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var mediaList by remember { mutableStateOf(WidgetDataManager.getMediaList(context)) }

    fun refreshList() {
        mediaList = WidgetDataManager.getMediaList(context)
        MediaCompactWidgetProvider.updateAllWidgets(context)
        WidgetListProvider.notifyDataChanged(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("桌面小组件管理", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 顶部说明卡片
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Widgets,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "追剧小组件清单",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "支持单组件内展示多个媒体（滚动列表），也支持独立固定多个单条微件。点击海报直接起播并同步进度。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 快捷添加媒体清单（多条目列表）大组件到桌面
            item {
                Button(
                    onClick = {
                        WidgetListProvider.pinListWidget(context)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0284C7)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Widgets,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "添加【媒体清单（多条目列表）】微件到桌面",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            }

            if (mediaList.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Rounded.Widgets,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "暂无添加到小组件的剧目",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "在电影或剧集详情页点击【发送到桌面】即可加入清单",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            } else {
                items(mediaList, key = { it.mediaId }) { item ->
                    WidgetMediaCard(
                        item = item,
                        onPlayClick = {
                            playMedia(context, item)
                        },
                        onPinClick = {
                            pinItemToHome(context, item)
                        },
                        onDeleteClick = {
                            WidgetDataManager.removeMedia(context, item.mediaId)
                            refreshList()
                            Toast.makeText(context, "已从小组件清单移除", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun WidgetMediaCard(
    item: WidgetMediaItem,
    onPlayClick: () -> Unit,
    onPinClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 海报 (带点击播放提示)
            Box(
                modifier = Modifier
                    .size(width = 54.dp, height = 76.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF20232B))
                    .clickable { onPlayClick() }
            ) {
                val imageModel = item.localPosterPath?.let { File(it) } ?: item.posterUrl
                AsyncImage(
                    model = imageModel,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // 播放图标小蒙层
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "播放",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 中间信息
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp)
            ) {
                // SxxExx 与标题
                Text(
                    text = if (item.isMovie) item.title else "${item.seasonEpisode} · ${item.title}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                // 单集标题
                Text(
                    text = item.episodeTitle?.takeIf { it.isNotBlank() } ?: if (item.isMovie) "电影" else "全剧",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 进度条
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LinearProgressIndicator(
                        progress = { (item.progressPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = Color(0xFF00A4DC),
                        trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${item.progressPercent}%",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 右侧操作按钮
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPinClick) {
                    Icon(
                        imageVector = Icons.Rounded.Widgets,
                        contentDescription = "固定到桌面",
                        tint = Color(0xFF38BDF8)
                    )
                }
                IconButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

private fun playMedia(context: Context, item: WidgetMediaItem) {
    val playIntent = Intent(context, JellyCineActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        putExtra("shortcut_play_media_id", item.mediaId)
        if (!item.seriesId.isNullOrBlank()) {
            putExtra("shortcut_play_series_id", item.seriesId)
        }
        putExtra("shortcut_title", item.title)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    context.startActivity(playIntent)
}

private fun pinItemToHome(context: Context, item: WidgetMediaItem) {
    WidgetDataManager.setPendingPinMediaId(context, item.mediaId)
    val appWidgetManager = AppWidgetManager.getInstance(context)
    val component = ComponentName(context, MediaCompactWidgetProvider::class.java)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appWidgetManager.isRequestPinAppWidgetSupported) {
        val successCallbackIntent = Intent(context, MediaCompactWidgetProvider::class.java).apply {
            action = MediaCompactWidgetProvider.ACTION_PIN_SUCCESS
            putExtra(MediaCompactWidgetProvider.EXTRA_PINNED_MEDIA_ID, item.mediaId)
        }
        val successPendingIntent = android.app.PendingIntent.getBroadcast(
            context,
            item.mediaId.hashCode(),
            successCallbackIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
        )
        val success = appWidgetManager.requestPinAppWidget(component, null, successPendingIntent)
        Toast.makeText(
            context,
            if (success) "请在系统弹窗中确认添加" else "系统拦截了自动添加，可在桌面长按微件手动放置",
            Toast.LENGTH_SHORT
        ).show()
    } else {
        Toast.makeText(context, "请在手机主屏幕长按空白处，选择添加 JellyCine 小组件", Toast.LENGTH_LONG).show()
    }
}
