package com.Johnny.wcx.features.items.chat

import android.app.Activity
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Pause
import com.composables.icons.materialsymbols.outlined.Play_arrow
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.IWeContact
import com.Johnny.wcx.features.api.net.models.protobuf.FavInfoProto
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.AudioUtils
import com.Johnny.wcx.utils.RuntimeConfig
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.android.showToastSuspend
import com.Johnny.wcx.utils.coerceToInt
import com.Johnny.wcx.utils.hookBeforeDirectly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.io.path.exists

@Feature(
    name = "转发收藏语音",
    categories = ["聊天"],
    description = "在收藏页长按语音可直接转发给好友或群聊"
)
@OptIn(ExperimentalSerializationApi::class)
object ForwardFavoriteVoices : SwitchFeature() {

    private const val TAG = "ForwardFavoriteVoices"

    /**
     * 收藏主菜单里「转发」项的 id。
     *
     * 微信在 FavoriteIndexUI 的菜单点击入口 K7(int,int,LinearLayout,m3) 里统一分发，
     * 长按语音点「转发」时 args[0] 恒为 3（探针三次复现一致）。微信随后会按收藏类型
     * 做检查并弹「收藏的语音消息不能转发」，所以必须在它之前拦下来。
     */
    private const val MENU_ID_FORWARD = 3

    /** 收藏类型：3 = 语音。 */
    private const val FAV_TYPE_VOICE = 3

    override fun onEnable() {
        runCatching {
            val cls = "com.tencent.mm.plugin.fav.ui.FavoriteIndexUI".toClass()
            val menuHandler = cls.declaredMethods.firstOrNull { it.name == "K7" && it.parameterCount == 4 }
                ?: error("FavoriteIndexUI.K7/4 not found")

            menuHandler.isAccessible = true
            menuHandler.hookBeforeDirectly {
                val menuId = args[0] as? Int ?: return@hookBeforeDirectly
                if (menuId != MENU_ID_FORWARD) return@hookBeforeDirectly

                val favItem = args[3] ?: return@hookBeforeDirectly
                if (favItemType(favItem) != FAV_TYPE_VOICE) return@hookBeforeDirectly

                val ctx = thisObject as? Activity ?: return@hookBeforeDirectly
                val voiceFilePath = favVoiceFilePath(favItem)
                if (voiceFilePath == null) {
                    showToast(ctx, "语音未缓存，请先在收藏中播放一次")
                    return@hookBeforeDirectly
                }

                // 拦下来：不再进入微信的类型检查，直接走模块自己的转发流程。
                // 原方法返回 void，置 result = null 阻止其后续逻辑。
                result = null
                showVoiceForwardDialog(ctx, voiceFilePath, favItem)
            }
            WeLogger.i(TAG, "menu intercept installed on FavoriteIndexUI.K7/4")
        }.onFailure {
            WeLogger.e(TAG, "failed to install menu intercept", it)
        }
    }

    private fun favItemType(favItem: Any): Int? =
        runCatching {
            favItem.reflekt().firstFieldOrNull { name = "field_type"; superclass() }?.get() as? Int
        }.getOrNull()

    /**
     * 取收藏语音的本地文件路径。
     *
     * 优先用 favProto 里的 filePath；为空时按微信的缓存命名规则拼：
     * <userDataDir>/favorite/<cacheName.hashCode() and 0xFF>/<cacheName>.<fileCacheType>
     */
    private fun favVoiceFilePath(favItem: Any): String? = runCatching {
        val favProto = favItem.reflekt().firstFieldOrNull { name = "field_favProto"; superclass() }?.get()
            ?: return@runCatching null
        val bytes = favProto.reflekt().firstMethodOrNull { name = "getData"; superclass() }?.invoke() as? ByteArray
            ?: return@runCatching null

        val voiceInfo = ProtoBuf.decodeFromByteArray<FavInfoProto>(bytes).voiceInfo

        voiceInfo.filePath?.takeIf { it.isNotEmpty() }
            ?: run {
                val cacheName = voiceInfo.fileCacheName
                val bucketId = cacheName.hashCode() and 0xFF
                (RuntimeConfig.userDataDir / "favorite" / bucketId.toString() /
                    "$cacheName.${voiceInfo.fileCacheType}").absolutePathString()
            }
    }.getOrNull()

    /** 先预览确认，再选转发对象。 */
    private fun showVoiceForwardDialog(ctx: Activity, voiceFilePath: String, favItem: Any) {
        val durationMs = favItemVoiceDurationMs(favItem).coerceAtLeast(0)

        showComposeDialog(ctx) {
            val player = remember { MediaPlayer() }
            var isPlaying by remember { mutableStateOf(false) }
            var currentPositionMs by remember { mutableLongStateOf(0L) }
            var prepared by remember { mutableStateOf(false) }
            var prepareError by remember { mutableStateOf<String?>(null) }
            var showPicker by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                if (!voiceFilePath.toPath().exists()) {
                    prepareError = "语音未缓存，请先在收藏中播放一次"
                    return@LaunchedEffect
                }
                // 语音是 SILK 容器，MediaPlayer 解不了，先转成 MP3 再播。
                val playable = withContext(Dispatchers.IO) {
                    AudioUtils.silkToPlayableMp3(voiceFilePath, previewCacheDir(ctx))
                }
                if (playable == null) {
                    prepareError = "无法试听（转码失败）"
                    return@LaunchedEffect
                }
                runCatching {
                    player.setDataSource(playable)
                    player.prepare()
                    player.setOnCompletionListener {
                        isPlaying = false
                        currentPositionMs = durationMs
                    }
                    prepared = true
                }.onFailure {
                    prepareError = "无法试听（播放器错误）"
                    WeLogger.w(TAG, "preview prepare failed for $playable", it)
                }
            }

            DisposableEffect(Unit) {
                onDispose { runCatching { player.release() } }
            }

            LaunchedEffect(isPlaying) {
                while (isPlaying && prepared) {
                    currentPositionMs = runCatching { player.currentPosition.toLong() }
                        .getOrDefault(currentPositionMs)
                    delay(200.milliseconds)
                }
            }

            fun togglePlay() {
                if (!prepared || prepareError != null) return
                runCatching {
                    if (player.isPlaying) {
                        player.pause()
                        isPlaying = false
                    } else {
                        if (durationMs > 0 && currentPositionMs >= durationMs) {
                            player.seekTo(0)
                            currentPositionMs = 0L
                        }
                        player.start()
                        isPlaying = true
                    }
                }.onFailure {
                    showToast(ctx, it.message ?: "播放失败")
                }
            }

            if (showPicker) {
                VoiceForwardPicker(
                    title = "选择转发对象",
                    onDismiss = onDismiss,
                    onConfirm = { wxIds ->
                        onDismiss()
                        forwardVoiceTo(ctx, voiceFilePath, durationMs, wxIds)
                    },
                )
                return@showComposeDialog
            }

            AlertDialogContent(
                title = { Text("转发收藏语音") },
                text = {
                    VoicePreviewBar(
                        isPlaying = isPlaying,
                        currentPositionMs = currentPositionMs,
                        totalDurationMs = durationMs,
                        prepareError = prepareError,
                        onTogglePlay = ::togglePlay,
                    )
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                },
                confirmButton = {
                    // 试听失败也能转发：发送走的是文件拷贝 + 元数据，与能否试听无关。
                    Button({ showPicker = true }) { Text("转发") }
                })
        }
    }

    private fun favItemVoiceDurationMs(favItem: Any): Long = runCatching {
        val favProto = favItem.reflekt().firstFieldOrNull { name = "field_favProto"; superclass() }?.get()
            ?: return@runCatching 0L
        val bytes = favProto.reflekt().firstMethodOrNull { name = "getData"; superclass() }?.invoke() as? ByteArray
            ?: return@runCatching 0L
        ProtoBuf.decodeFromByteArray<FavInfoProto>(bytes).voiceInfo.duration.toLong()
    }.getOrDefault(0L)

    private fun forwardVoiceTo(ctx: Activity, voiceFilePath: String, durationMs: Long, wxIds: Set<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            val effectiveDuration = if (durationMs > 0) durationMs.coerceToInt()
            else AudioUtils.getDurationMsSafe(voiceFilePath).coerceToInt()

            var success = 0
            wxIds.forEach { wxId ->
                if (runCatching { WeMessageApi.sendVoice(wxId, voiceFilePath, effectiveDuration) }.getOrDefault(false)) {
                    success++
                }
            }
            showToastSuspend(
                if (success == wxIds.size) "已转发到 ${wxIds.size} 个对象"
                else "已转发 $success/${wxIds.size} 个对象 (部分失败)"
            )
        }
    }
}

/** 联系人选择器：复用「转发消息」的选择体验。 */
@Composable
private fun VoiceForwardPicker(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var contacts by remember { mutableStateOf<List<IWeContact>?>(null) }

    LaunchedEffect(Unit) {
        contacts = withContext(Dispatchers.IO) {
            WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()
        }
    }

    val loaded = contacts ?: return

    ContactsSelector(
        title = title,
        contacts = loaded,
        initialSelectedWxIds = emptySet(),
        onDismiss = onDismiss,
        onConfirm = { selected ->
            if (selected.isEmpty()) return@ContactsSelector
            onConfirm(selected)
        },
    )
}

@Composable
private fun VoicePreviewBar(
    isPlaying: Boolean,
    currentPositionMs: Long,
    totalDurationMs: Long,
    prepareError: String?,
    onTogglePlay: () -> Unit,
) {
    val progress =
        if (totalDurationMs > 0) min(1f, currentPositionMs.toFloat() / totalDurationMs.toFloat()) else 0f
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(surfaceColor)
            .clickable(enabled = prepareError == null, onClick = onTogglePlay)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isPlaying) MaterialSymbols.Outlined.Pause else MaterialSymbols.Outlined.Play_arrow,
            contentDescription = if (isPlaying) "暂停" else "播放",
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatDuration(currentPositionMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = prepareError ?: formatDuration(totalDurationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (prepareError != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** 试听转码产物的缓存目录（SILK → MP3 的结果落在这里，按内容复用）。 */
private fun previewCacheDir(ctx: android.content.Context): java.io.File =
    java.io.File(ctx.cacheDir, "voice_preview")

private fun String.toPath() = java.io.File(this).toPath()
