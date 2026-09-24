package com.jellycine.app.ui.screens.dashboard.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.StarRate
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.jellycine.app.BuildConfig
import com.jellycine.app.ota.OtaUpdateChecker
import com.jellycine.shared.R
import com.jellycine.shared.preferences.Preferences
import kotlinx.coroutines.launch

private const val GithubUrl = "https://github.com/sureshfizzy/JellyCine"
private const val PrivacyUrl = "https://github.com/sureshfizzy/JellyCine/blob/main/PRIVACY"
private const val LicenseUrl = "https://github.com/sureshfizzy/JellyCine/blob/main/LICENSE"
private const val PlayStoreUrl = "https://play.google.com/store/apps/details?id=com.jellycine.app"
private const val BuyMeACoffeeUrl = "https://www.buymeacoffee.com/Sureshfizzy"
private const val PatreonUrl = "https://www.patreon.com/c/sureshs/membership"
private const val ContactEmail = "Sureshfizzy0503@gmail.com"
private const val GithubIconUrl = "https://cdn.simpleicons.org/github/white?viewbox=auto"
private const val BuyMeACoffeeIconUrl = "https://cdn.simpleicons.org/buymeacoffee?viewbox=auto"
private const val PatreonIconUrl = "https://cdn.simpleicons.org/patreon/white?viewbox=auto"

private val AboutCardColor = Color(0xFF0B0E12)
private val AboutBorderColor = Color.White.copy(alpha = 0.08f)
private val AboutSecondaryText = Color.White.copy(alpha = 0.70f)
private val AboutAccent = Color(0xFF5AA9FA)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBackPressed: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { Preferences(context) }
    val otaChecker = remember { OtaUpdateChecker(context) }

    var updateStatus by remember {
        mutableStateOf<OtaUpdateChecker.UpdateStatus>(OtaUpdateChecker.UpdateStatus.NoUpdate)
    }
    var isChecking by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var isDownloading by remember { mutableStateOf(false) }

    // Auto-check for updates when entering the screen (skips if current version was ignored)
    LaunchedEffect(Unit) {
        isChecking = true
        updateStatus = otaChecker.checkForUpdate(
            checkIgnored = true,
            ignoredVersionCode = preferences.getIgnoredOtaVersion()
        )
        isChecking = false
    }

    fun performCheck() {
        scope.launch {
            isChecking = true
            updateStatus = otaChecker.checkForUpdate(
                checkIgnored = false,
                ignoredVersionCode = preferences.getIgnoredOtaVersion()
            )
            isChecking = false
        }
    }

    fun performDownload(url: String) {
        scope.launch {
            isDownloading = true
            downloadProgress = 0
            val result = otaChecker.downloadApk(url) { progress ->
                downloadProgress = progress
            }
            isDownloading = false
            result.onSuccess { apkFile ->
                updateStatus = OtaUpdateChecker.UpdateStatus.ReadyToInstall(apkFile)
            }.onFailure { e ->
                updateStatus = OtaUpdateChecker.UpdateStatus.Error(
                    e.message ?: "Download failed"
                )
            }
        }
    }

    fun performIgnore(versionCode: Int, versionName: String) {
        preferences.setIgnoredOtaVersion(versionCode)
        updateStatus = OtaUpdateChecker.UpdateStatus.NoUpdate
        Toast.makeText(
            context,
            context.getString(R.string.ota_version_ignored_toast, versionName),
            Toast.LENGTH_SHORT
        ).show()
    }

    fun performUnignore() {
        preferences.clearIgnoredOtaVersion()
        updateStatus = (updateStatus as? OtaUpdateChecker.UpdateStatus.UpdateAvailable)?.copy(isIgnored = false)
            ?: OtaUpdateChecker.UpdateStatus.NoUpdate
        Toast.makeText(
            context,
            context.getString(R.string.ota_version_unignored_toast),
            Toast.LENGTH_SHORT
        ).show()
    }

    fun performDismissInstall() {
        updateStatus = OtaUpdateChecker.UpdateStatus.NoUpdate
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.about_title),
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back_button),
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            item {
                AboutHeader(
                    onGithubClick = { openUrl(context, GithubUrl) },
                    onCoffeeClick = { openUrl(context, BuyMeACoffeeUrl) },
                    onPatreonClick = { openUrl(context, PatreonUrl) }
                )
            }

            // — OTA Update Section —
            item { AboutSectionLabel(stringResource(R.string.ota_section_update)) }
            item {
                OtaUpdateCard(
                    updateStatus = updateStatus,
                    isChecking = isChecking,
                    isDownloading = isDownloading,
                    downloadProgress = downloadProgress,
                    onCheckClick = { performCheck() },
                    onDownloadClick = { url -> performDownload(url) },
                    onInstallClick = { apkFile -> otaChecker.installApk(apkFile) },
                    onIgnoreClick = { versionCode, versionName -> performIgnore(versionCode, versionName) },
                    onUnignoreClick = { performUnignore() },
                    onDismissInstall = { performDismissInstall() }
                )
            }

            item { AboutSectionLabel(stringResource(R.string.about_section_project)) }
            item {
                AboutSectionCard {
                    AboutActionRow(
                        icon = Icons.Rounded.Policy,
                        title = stringResource(R.string.about_privacy_policy),
                        subtitle = stringResource(R.string.about_privacy_policy_subtitle),
                        onClick = { openUrl(context, PrivacyUrl) }
                    )
                    HorizontalDivider(color = AboutBorderColor)
                    AboutActionRow(
                        icon = Icons.Rounded.Gavel,
                        title = stringResource(R.string.about_open_source_license),
                        subtitle = stringResource(R.string.about_open_source_license_subtitle),
                        onClick = { openUrl(context, LicenseUrl) }
                    )
                }
            }

            item { AboutSectionLabel(stringResource(R.string.about_section_connect)) }
            item {
                AboutSectionCard {
                    AboutActionRow(
                        icon = Icons.Rounded.StarRate,
                        title = stringResource(R.string.about_rate_app),
                        subtitle = stringResource(R.string.about_rate_app_subtitle),
                        onClick = { openUrl(context, PlayStoreUrl) }
                    )
                    HorizontalDivider(color = AboutBorderColor)
                    AboutActionRow(
                        icon = Icons.Rounded.Email,
                        title = stringResource(R.string.about_contact_developer),
                        subtitle = stringResource(R.string.about_contact_developer_subtitle),
                        onClick = { composeEmail(context) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutHeader(
    onGithubClick: () -> Unit,
    onCoffeeClick: () -> Unit,
    onPatreonClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.jellycine_logo),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.size(112.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Surface(
            color = AboutAccent.copy(alpha = 0.14f),
            shape = RoundedCornerShape(999.dp),
            border = BorderStroke(1.dp, AboutAccent.copy(alpha = 0.28f))
        ) {
            Text(
                text = stringResource(R.string.about_version_chip, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        Spacer(modifier = Modifier.height(26.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BrandIconButton(
                contentDescription = stringResource(R.string.about_source_code),
                imageUrl = GithubIconUrl,
                onClick = onGithubClick
            )
            BrandIconButton(
                contentDescription = stringResource(R.string.about_buy_me_a_coffee),
                imageUrl = BuyMeACoffeeIconUrl,
                onClick = onCoffeeClick
            )
            BrandIconButton(
                contentDescription = stringResource(R.string.about_patreon),
                imageUrl = PatreonIconUrl,
                onClick = onPatreonClick
            )
        }
    }
}

@Composable
private fun BrandIconButton(
    contentDescription: String,
    imageUrl: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .semantics { this.contentDescription = contentDescription }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            modifier = Modifier.size(30.dp)
        )
    }
}

@Composable
private fun AboutSectionLabel(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = Color.White.copy(alpha = 0.88f),
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 2.dp)
    )
}

@Composable
private fun AboutSectionCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AboutCardColor),
        border = BorderStroke(1.dp, AboutBorderColor),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(content = content)
    }
}

@Composable
private fun AboutActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            color = AboutAccent.copy(alpha = 0.12f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = AboutAccent,
                    modifier = Modifier.size(19.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = AboutSecondaryText
            )
        }

        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = title,
            tint = Color.White.copy(alpha = 0.42f)
        )
    }
}

@Composable
private fun OtaUpdateCard(
    updateStatus: OtaUpdateChecker.UpdateStatus,
    isChecking: Boolean,
    isDownloading: Boolean,
    downloadProgress: Int,
    onCheckClick: () -> Unit,
    onDownloadClick: (String) -> Unit,
    onInstallClick: (java.io.File) -> Unit,
    onIgnoreClick: (Int, String) -> Unit,
    onUnignoreClick: () -> Unit,
    onDismissInstall: () -> Unit
) {
    AboutSectionCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Current status display
            when {
                isChecking -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = AboutAccent,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = stringResource(R.string.ota_checking),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AboutSecondaryText
                        )
                    }
                }

                isDownloading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(40.dp),
                            color = AboutAccent.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Download,
                                    contentDescription = null,
                                    tint = AboutAccent,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.ota_downloading, downloadProgress),
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { downloadProgress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                                color = AboutAccent,
                                trackColor = AboutBorderColor,
                                strokeCap = StrokeCap.Round,
                            )
                        }
                    }
                }

                updateStatus is OtaUpdateChecker.UpdateStatus.UpdateAvailable -> {
                    val info = updateStatus.info
                    val isIgnored = updateStatus.isIgnored
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Surface(
                                modifier = Modifier.size(40.dp),
                                color = if (isIgnored) Color.White.copy(alpha = 0.08f) else Color(0xFF2E7D32).copy(alpha = 0.18f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isIgnored) Icons.Rounded.NotificationsOff else Icons.Rounded.Download,
                                        contentDescription = null,
                                        tint = if (isIgnored) Color.White.copy(alpha = 0.6f) else Color(0xFF66BB6A),
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.ota_new_version, info.versionName),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                    if (isIgnored) {
                                        Surface(
                                            color = Color.White.copy(alpha = 0.12f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.ota_ignored_badge),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.White.copy(alpha = 0.7f),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                if (info.changelog.isNotBlank()) {
                                    Text(
                                        text = info.changelog,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = AboutSecondaryText
                                    )
                                }
                            }
                        }

                        // Action Buttons Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { onDownloadClick(info.apkUrl) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AboutAccent,
                                    contentColor = Color.Black
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.ota_download),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    if (isIgnored) {
                                        onUnignoreClick()
                                    } else {
                                        onIgnoreClick(info.versionCode, info.versionName)
                                    }
                                },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color.White.copy(alpha = 0.8f)
                                ),
                                border = BorderStroke(1.dp, AboutBorderColor),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = if (isIgnored) {
                                        stringResource(R.string.ota_unignore_version)
                                    } else {
                                        stringResource(R.string.ota_ignore_version)
                                    },
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }

                updateStatus is OtaUpdateChecker.UpdateStatus.ReadyToInstall -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier.size(40.dp),
                                color = Color(0xFF2E7D32).copy(alpha = 0.18f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.InstallMobile,
                                        contentDescription = null,
                                        tint = Color(0xFF66BB6A),
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.ota_install_ready),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Text(
                                    text = stringResource(R.string.ota_install),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AboutSecondaryText
                                )
                            }
                        }

                        // Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { onInstallClick(updateStatus.apkFile) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF2E7D32),
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.InstallMobile,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.ota_install_now),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            OutlinedButton(
                                onClick = onDismissInstall,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color.White.copy(alpha = 0.8f)
                                ),
                                border = BorderStroke(1.dp, AboutBorderColor),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.ota_install_later),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }

                updateStatus is OtaUpdateChecker.UpdateStatus.Error -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onCheckClick),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(40.dp),
                            color = Color(0xFFC62828).copy(alpha = 0.18f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.SystemUpdate,
                                    contentDescription = null,
                                    tint = Color(0xFFEF5350),
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.ota_error),
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                            Text(
                                text = updateStatus.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = AboutSecondaryText
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.42f)
                        )
                    }
                }

                // NoUpdate — show check button
                else -> {
                    AboutActionRow(
                        icon = Icons.Rounded.SystemUpdate,
                        title = stringResource(R.string.ota_check_update),
                        subtitle = stringResource(R.string.ota_latest_version),
                        onClick = onCheckClick
                    )
                }
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    launchIntent(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

private fun composeEmail(context: Context) {
    launchIntent(
        context,
        Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$ContactEmail")
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.about_feedback_subject))
        }
    )
}

private fun launchIntent(context: Context, intent: Intent) {
    runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
