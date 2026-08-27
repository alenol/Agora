package com.newoether.agora.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.AgoraApplication
import com.newoether.agora.R
import com.newoether.agora.api.local.LocalModelLoadState
import com.newoether.agora.api.local.LocalProvider
import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

private val FullRounded = RoundedCornerShape(24.dp)
private val TopRounded = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 5.dp, bottomEnd = 5.dp)
private val BottomRounded = RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
private val MidRounded = RoundedCornerShape(5.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsLocalModelPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val application = context.applicationContext as AgoraApplication
    val localProvider = remember {
        runCatching { application.requireContainer() }.getOrNull()?.localProvider
    }

    val scope = rememberCoroutineScope()
    val fallbackState = remember { MutableStateFlow(LocalModelLoadState.Idle) }
    val loadState by (localProvider?.loadState ?: fallbackState)
        .collectAsState(LocalModelLoadState.Idle)
    val localModels by viewModel.settings.localChatModels.collectAsState(emptyList<LocalChatModelConfig>())

    CollapsingSettingsLazyScaffold(
        title = stringResource(R.string.local_models_title),
        onBack = onBack,
        contentHorizontalPadding = 0.dp,
    ) {
        item(key = "local_model_status") {
            SectionLabel(text = stringResource(R.string.local_models_title), firstInPage = true)
        }

        // Overall engine status banner
        item(key = "local_model_banner") {
            val (bannerText, bannerTint) = when (val state = loadState) {
                LocalModelLoadState.Idle -> "当前没有加载任何本地模型" to MaterialTheme.colorScheme.onSurfaceVariant
                is LocalModelLoadState.Loading -> "正在加载：${state.modelId}" to MaterialTheme.colorScheme.primary
                is LocalModelLoadState.Loaded -> "已加载并常驻内存：${state.modelId}" to MaterialTheme.colorScheme.primary
                is LocalModelLoadState.Unloading -> "正在卸载：${state.modelId}" to MaterialTheme.colorScheme.primary
                is LocalModelLoadState.Error -> "加载失败：${state.message}" to MaterialTheme.colorScheme.error
            }
            CardSurface(shape = FullRounded) {
                SettingsItem(
                    headlineContent = { Text(bannerText, color = bannerTint) },
                    supportingContent = {
                        Text(
                            "在对话中发送消息会自动加载所选模型；这里的按钮可手动预热 / 卸载，并实时查看状态。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    },
                    leadingContent = {
                        val icon = when (loadState) {
                            LocalModelLoadState.Idle -> Icons.Default.CloudOff
                            is LocalModelLoadState.Loading,
                            is LocalModelLoadState.Unloading -> Icons.Default.AutoAwesome
                            is LocalModelLoadState.Loaded -> Icons.Default.CheckCircle
                            is LocalModelLoadState.Error -> Icons.Default.Error
                        }
                        Icon(icon, null, tint = bannerTint, modifier = Modifier.size(24.dp))
                    },
                )
            }
        }

        if (localProvider == null) {
            item(key = "local_model_unavailable") {
                CardSurface(shape = BottomRounded, addTopGap = true) {
                    SettingsItem(
                        headlineContent = { Text("本地模型管理器暂不可用") },
                        supportingContent = { Text("请稍候重试，或重启应用。") },
                        leadingContent = {
                            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                        },
                    )
                }
            }
            return@CollapsingSettingsLazyScaffold
        }

        if (localModels.isEmpty()) {
            item(key = "local_model_empty") {
                CardSurface(shape = BottomRounded, addTopGap = true) {
                    SettingsItem(
                        headlineContent = { Text("尚未导入本地模型") },
                        supportingContent = { Text("在 Providers ▸ Local 中导入一个 GGUF 模型后即可在此加载。") },
                        leadingContent = {
                            Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        },
                    )
                }
            }
            return@CollapsingSettingsLazyScaffold
        }

        items(localModels, key = { it.id }) { model ->
            val index = localModels.indexOf(model)
            val isFirst = index == 0
            val isLast = index == localModels.lastIndex
            val shape = when {
                localModels.size == 1 -> FullRounded
                isFirst -> TopRounded
                isLast -> BottomRounded
                else -> MidRounded
            }

            val state = loadState
            val isThisLoaded = state is LocalModelLoadState.Loaded && state.modelId == model.modelId
            val isThisLoading = state is LocalModelLoadState.Loading && state.modelId == model.modelId
            val isThisUnloading = state is LocalModelLoadState.Unloading && state.modelId == model.modelId
            val engineBusy = state is LocalModelLoadState.Loading || state is LocalModelLoadState.Unloading

            val (label, enabled, showSpinner, onClick) = when {
                // 加载中 → 显示「卸载」按钮（取消加载）
                isThisLoading -> Quad("卸载", true, true) { scope.launch(Dispatchers.IO) { localProvider.cancelLoad() }; Unit }
                // 卸载中 → 显示「加载」按钮（立即重新加载）
                isThisUnloading -> Quad("加载", true, true) { scope.launch(Dispatchers.IO) { localProvider.loadModel(model) }; Unit }
                // 已加载 → 显示「卸载」按钮
                isThisLoaded -> Quad("卸载", true, false) { scope.launch(Dispatchers.IO) { localProvider.unloadModel() }; Unit }
                // 其他模型操作中 → 本模型「加载」按钮禁用
                engineBusy -> Quad("加载", false, false) { }
                // 空闲 / 出错 → 显示「加载」按钮
                else -> Quad("加载", true, false) { scope.launch(Dispatchers.IO) { localProvider.loadModel(model) }; Unit }
            }

            val statusText = when {
                isThisLoading -> "加载中…"
                isThisUnloading -> "卸载中…"
                isThisLoaded -> "已加载"
                loadState is LocalModelLoadState.Error -> "上次加载失败"
                engineBusy -> "其他模型操作中…"
                else -> if (File(model.localFilePath).exists()) "未加载" else "文件缺失"
            }
            val statusColor = when {
                isThisLoaded -> MaterialTheme.colorScheme.primary
                loadState is LocalModelLoadState.Error && (loadState as LocalModelLoadState.Error).message.contains(model.modelId) ->
                    MaterialTheme.colorScheme.error
                File(model.localFilePath).exists().not() -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            CardSurface(shape = shape, addTopGap = !isFirst) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SettingsItem(
                        headlineContent = {
                            Text(model.alias.ifBlank { model.modelId }, fontWeight = FontWeight.Medium)
                        },
                        supportingContent = {
                            Text(
                                model.localFilePath + if (File(model.localFilePath).exists()) "" else "  (文件不存在)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Default.AutoAwesome,
                                null,
                                tint = if (isThisLoaded) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(24.dp),
                            )
                        },
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = statusColor,
                            modifier = Modifier.weight(1f),
                        )
                        if (showSpinner) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Button(
                            onClick = onClick,
                            enabled = enabled,
                        ) {
                            Text(label)
                        }
                    }
                }
            }
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
