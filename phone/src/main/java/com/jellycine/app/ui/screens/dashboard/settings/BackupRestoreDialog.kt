package com.jellycine.app.ui.screens.dashboard.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.jellycine.app.backup.BackupEnvelopeInfo
import com.jellycine.app.backup.BackupPreview
import com.jellycine.app.backup.ConfigBackupPayload
import com.jellycine.app.backup.PasswordRequiredException
import com.jellycine.shared.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun BackupRestoreDialog(
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTabIndex by remember { mutableIntStateOf(0) }

    // Export states
    var isEncryptEnabled by remember { mutableStateOf(true) }
    var exportPassword by remember { mutableStateOf("") }
    var exportConfirmPassword by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var isConfirmPasswordVisible by remember { mutableStateOf(false) }
    var includeCredentials by remember { mutableStateOf(true) }
    var isExporting by remember { mutableStateOf(false) }

    // Import states
    var isImporting by remember { mutableStateOf(false) }
    var pendingImportRawJson by remember { mutableStateOf<String?>(null) }
    var showPasswordPrompt by remember { mutableStateOf(false) }
    var decryptPassword by remember { mutableStateOf("") }
    var isDecryptPasswordVisible by remember { mutableStateOf(false) }
    var decryptError by remember { mutableStateOf<String?>(null) }
    var pendingPreview by remember { mutableStateOf<BackupPreview?>(null) }

    // System File Save (CreateDocument) Launcher
    var pendingExportJson by remember { mutableStateOf<String?>(null) }
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null && pendingExportJson != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(pendingExportJson!!.toByteArray(Charsets.UTF_8))
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.backup_export_success), Toast.LENGTH_SHORT).show()
                        pendingExportJson = null
                        onDismiss()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // System File Open (OpenDocument) Launcher
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val content = context.contentResolver.openInputStream(uri)?.use { input ->
                        input.bufferedReader(Charsets.UTF_8).readText()
                    }
                    if (content.isNullOrBlank()) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.backup_invalid_format), Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                    withContext(Dispatchers.Main) {
                        handleRawImportContent(
                            content = content,
                            viewModel = viewModel,
                            scope = scope,
                            onNeedPassword = {
                                pendingImportRawJson = content
                                decryptPassword = ""
                                decryptError = null
                                showPasswordPrompt = true
                            },
                            onPreviewReady = { preview ->
                                pendingPreview = preview
                            },
                            onError = { err ->
                                Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                            }
                        )
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "读取备份文件失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // Helper: Trigger File Export
    fun executeExport(onExportReady: (String) -> Unit) {
        if (isEncryptEnabled) {
            if (exportPassword.isBlank()) {
                Toast.makeText(context, context.getString(R.string.backup_password_empty), Toast.LENGTH_SHORT).show()
                return
            }
            if (exportPassword != exportConfirmPassword) {
                Toast.makeText(context, context.getString(R.string.backup_password_mismatch), Toast.LENGTH_SHORT).show()
                return
            }
        }

        isExporting = true
        scope.launch {
            val pass = if (isEncryptEnabled) exportPassword else null
            val result = viewModel.exportConfiguration(pass, includeCredentials)
            isExporting = false
            if (result.isSuccess) {
                onExportReady(result.getOrThrow())
            } else {
                Toast.makeText(context, "导出配置失败: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF3B82F6).copy(alpha = 0.15f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.CloudSync,
                                    contentDescription = null,
                                    tint = Color(0xFF3B82F6),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.backup_dialog_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Tab Switcher
                SecondaryTabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.clip(RoundedCornerShape(14.dp))
                ) {
                    Tab(
                        selected = selectedTabIndex == 0,
                        onClick = { selectedTabIndex = 0 },
                        text = { Text(stringResource(R.string.backup_tab_export), fontWeight = FontWeight.SemiBold) },
                        icon = { Icon(Icons.Rounded.FileUpload, contentDescription = null) }
                    )
                    Tab(
                        selected = selectedTabIndex == 1,
                        onClick = { selectedTabIndex = 1 },
                        text = { Text(stringResource(R.string.backup_tab_import), fontWeight = FontWeight.SemiBold) },
                        icon = { Icon(Icons.Rounded.FileDownload, contentDescription = null) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Content Area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (selectedTabIndex == 0) {
                        // Export View
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.backup_export_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // Encrypt Card
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                            Text(
                                                text = stringResource(R.string.backup_encrypt_toggle),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = stringResource(R.string.backup_encrypt_hint),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = isEncryptEnabled,
                                            onCheckedChange = { isEncryptEnabled = it }
                                        )
                                    }

                                    AnimatedVisibility(
                                        visible = isEncryptEnabled,
                                        enter = fadeIn(),
                                        exit = fadeOut()
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 14.dp),
                                            verticalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            OutlinedTextField(
                                                value = exportPassword,
                                                onValueChange = { exportPassword = it },
                                                label = { Text(stringResource(R.string.backup_password_label)) },
                                                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                                trailingIcon = {
                                                    IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                                        Icon(
                                                            imageVector = if (isPasswordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                                            contentDescription = null
                                                        )
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(12.dp)
                                            )

                                            OutlinedTextField(
                                                value = exportConfirmPassword,
                                                onValueChange = { exportConfirmPassword = it },
                                                label = { Text(stringResource(R.string.backup_confirm_password_label)) },
                                                visualTransformation = if (isConfirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                                trailingIcon = {
                                                    IconButton(onClick = { isConfirmPasswordVisible = !isConfirmPasswordVisible }) {
                                                        Icon(
                                                            imageVector = if (isConfirmPasswordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                                            contentDescription = null
                                                        )
                                                    }
                                                },
                                                isError = exportPassword.isNotEmpty() && exportConfirmPassword.isNotEmpty() && exportPassword != exportConfirmPassword,
                                                supportingText = {
                                                    if (exportPassword.isNotEmpty() && exportConfirmPassword.isNotEmpty() && exportPassword != exportConfirmPassword) {
                                                        Text(
                                                            text = stringResource(R.string.backup_password_mismatch),
                                                            color = MaterialTheme.colorScheme.error
                                                        )
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Credentials Option Card
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(
                                            text = stringResource(R.string.backup_include_credentials),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = stringResource(R.string.backup_include_credentials_hint),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = includeCredentials,
                                        onCheckedChange = { includeCredentials = it }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            // Action Buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        executeExport { exportedJson ->
                                            shareBackupFile(context, exportedJson)
                                        }
                                    },
                                    enabled = !isExporting,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.backup_export_btn_share))
                                }

                                Button(
                                    onClick = {
                                        executeExport { exportedJson ->
                                            pendingExportJson = exportedJson
                                            val timeStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                                            createDocumentLauncher.launch("jellycine_backup_$timeStr.json")
                                        }
                                    },
                                    enabled = !isExporting,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (isExporting) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Rounded.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.backup_export_btn_save))
                                    }
                                }
                            }
                        }
                    } else {
                        // Import View
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.backup_import_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // Pick File Card
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { openDocumentLauncher.launch(arrayOf("application/json", "*/*")) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(18.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Rounded.FolderOpen,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.backup_import_btn_file),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "支持从文件管理、下载列表或网盘选择备份文件",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                                }
                            }

                            // Clipboard Card
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                                        if (clipText.isNullOrBlank()) {
                                            Toast.makeText(context, context.getString(R.string.backup_clipboard_empty), Toast.LENGTH_SHORT).show()
                                        } else {
                                            handleRawImportContent(
                                                content = clipText,
                                                viewModel = viewModel,
                                                scope = scope,
                                                onNeedPassword = {
                                                    pendingImportRawJson = clipText
                                                    decryptPassword = ""
                                                    decryptError = null
                                                    showPasswordPrompt = true
                                                },
                                                onPreviewReady = { preview ->
                                                    pendingPreview = preview
                                                },
                                                onError = { err ->
                                                    Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                                }
                                            )
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(18.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color(0xFF10B981).copy(alpha = 0.15f),
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Rounded.ContentPaste,
                                                contentDescription = null,
                                                tint = Color(0xFF10B981),
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.backup_import_btn_clipboard),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "直接读取剪贴板中粘贴的备份文本内容",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Decrypt Password Input Dialog
    if (showPasswordPrompt) {
        AlertDialog(
            onDismissRequest = {
                showPasswordPrompt = false
                pendingImportRawJson = null
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.backup_decrypt_title))
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.backup_decrypt_desc))
                    OutlinedTextField(
                        value = decryptPassword,
                        onValueChange = {
                            decryptPassword = it
                            decryptError = null
                        },
                        label = { Text("解密密码") },
                        visualTransformation = if (isDecryptPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { isDecryptPasswordVisible = !isDecryptPasswordVisible }) {
                                Icon(
                                    imageVector = if (isDecryptPasswordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
                        isError = decryptError != null,
                        supportingText = {
                            if (decryptError != null) {
                                Text(text = decryptError!!, color = MaterialTheme.colorScheme.error)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val raw = pendingImportRawJson ?: return@Button
                        scope.launch {
                            val result = viewModel.parseBackup(raw, decryptPassword)
                            if (result.isSuccess) {
                                showPasswordPrompt = false
                                pendingPreview = result.getOrThrow()
                            } else {
                                decryptError = result.exceptionOrNull()?.message ?: "解密失败"
                            }
                        }
                    }
                ) {
                    Text("解密")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPasswordPrompt = false
                        pendingImportRawJson = null
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }

    // Confirm Restore Preview Dialog
    pendingPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { pendingPreview = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color(0xFF10B981))
                    Text(stringResource(R.string.backup_preview_title))
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val dateFormatted = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(preview.createdAt))
                    Text("导出时间: $dateFormatted", style = MaterialTheme.typography.bodyMedium)
                    if (preview.appVersionName.isNotBlank()) {
                        Text("导出版本: v${preview.appVersionName}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("包含服务器: ${preview.serverCount} 个", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)

                    if (preview.payload.servers.isNotEmpty()) {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                preview.payload.servers.forEach { s ->
                                    Text("• ${s.displayName} (${s.username})", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "恢复将更新本地已保存的服务器及播放器/界面偏好，现有重复服务器将自动合并。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val countResult = viewModel.restoreConfiguration(preview.payload)
                            pendingPreview = null
                            if (countResult.isSuccess) {
                                val count = countResult.getOrDefault(0)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.backup_import_success, count),
                                    Toast.LENGTH_LONG
                                ).show()
                                onDismiss()
                            } else {
                                Toast.makeText(
                                    context,
                                    "恢复失败: ${countResult.exceptionOrNull()?.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.backup_confirm_import_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPreview = null }) {
                    Text("取消")
                }
            }
        )
    }
}

private fun handleRawImportContent(
    content: String,
    viewModel: SettingsViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    onNeedPassword: () -> Unit,
    onPreviewReady: (BackupPreview) -> Unit,
    onError: (String) -> Unit
) {
    val inspectResult = viewModel.inspectBackup(content)
    if (inspectResult.isFailure) {
        onError("不是有效的 JellyCine 备份文件")
        return
    }
    val info = inspectResult.getOrThrow()
    if (info.isEncrypted) {
        onNeedPassword()
    } else {
        scope.launch(Dispatchers.Main) {
            val previewResult = viewModel.parseBackup(content, null)
            if (previewResult.isSuccess) {
                onPreviewReady(previewResult.getOrThrow())
            } else {
                onError("解析失败: ${previewResult.exceptionOrNull()?.message}")
            }
        }
    }
}

private fun shareBackupFile(context: Context, jsonContent: String) {
    try {
        val cacheDir = File(context.cacheDir, "backups").also { it.mkdirs() }
        val timeStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val backupFile = File(cacheDir, "jellycine_backup_$timeStr.json")
        backupFile.writeText(jsonContent, Charsets.UTF_8)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            backupFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "JellyCine 配置备份")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享备份文件"))
    } catch (e: Exception) {
        Toast.makeText(context, "分享文件失败: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
