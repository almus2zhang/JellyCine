package com.jellycine.app.ui.components.common

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jellycine.shared.R
import com.jellycine.shared.preferences.Preferences

@Composable
fun NoImageModeToggleButton(
    modifier: Modifier = Modifier,
    size: Dp = 34.dp
) {
    val context = LocalContext.current
    val preferences = remember { Preferences(context) }
    val noImageMode by preferences.NoImageModeEnabled()
        .collectAsState(initial = preferences.isNoImageModeEnabled())

    val iconSize = if (size <= 34.dp) 20.dp else 24.dp

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable {
                val nextState = !noImageMode
                preferences.setNoImageModeEnabled(nextState)
                Toast.makeText(
                    context,
                    if (nextState) {
                        context.getString(R.string.no_image_mode_on)
                    } else {
                        context.getString(R.string.no_image_mode_off)
                    },
                    Toast.LENGTH_SHORT
                ).show()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (noImageMode) Icons.Rounded.Image else Icons.Rounded.HideImage,
            contentDescription = if (noImageMode) {
                stringResource(R.string.no_image_mode_off)
            } else {
                stringResource(R.string.no_image_mode_on)
            },
            tint = if (noImageMode) MaterialTheme.colorScheme.primary else Color.White,
            modifier = Modifier.size(iconSize)
        )
    }
}
