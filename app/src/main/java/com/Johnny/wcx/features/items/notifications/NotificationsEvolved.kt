package com.Johnny.wcx.features.items.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Icon
import androidx.core.content.ContextCompat
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.RuntimeConfig
import com.Johnny.wcx.utils.hookBeforeDirectly
import com.Johnny.wcx.utils.TargetProcesses
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.features.api.net.models.protobuf.ChatRoomDataProto
import com.Johnny.wcx.utils.android.getSystemService
import com.Johnny.wcx.utils.collections.LruCache
import com.Johnny.wcx.utils.fs.KnownPaths
import com.Johnny.wcx.utils.strings.isGroupChatWxId
import com.Johnny.wcx.utils.strings.replaceEmojis
import com.Johnny.wcx.utils.strings.replaceRichContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.pathString
import kotlin.io.path.writeBytes
import kotlin.time.Duration.Companion.milliseconds

@Feature(
    name = "通知进化",
    categories = ["通知"],
    description = "让微信的新消息通知更易用\n1. 「快速回复」按钮\n2. 「标记为已读」按钮\n3. 使用原生对话样式 (MessagingStyle)"
)
@OptIn(ExperimentalSerializationApi::class)
object NotificationsEvolved : SwitchFeature(), IResolveDex {

    private const val TAG = "NotificationsEvolved"

    // com.tencent.mm.booter.notification.x.d(x, String talker, String content, int, int, boolean)
    // args[1] is the talker wxid. Anchored on a log string unique to that method.
    private val methodDealNotify by dexMethod {
        searchPackages("com.tencent.mm.booter.notification")
        matcher {
            paramCount(6)
            usingEqStrings("jacks dealNotify, talker:%s, msgtype:%d, tipsFlag:%d, isRevokeMesasge:%B content:%s")
        }
    }

    // talker wxid captured from x.d, read back in the synchronous Notification.Builder.build() hook
    private val currentTalker = ThreadLocal<String?>()

    override val shouldLoadInCurrentProcess get() = TargetProcesses.isInMain || TargetProcesses.currentType == TargetProcesses.PROC_PUSH

    private val lastGroupChatSender = LruCache<String, String>()

    // sender 头像缓存：senderKey(会话|发送者) -> Icon，异步预取，下一条通知构建时生效
    private val senderAvatarCache = LruCache<String, Icon>(maxLimit = 64)

    // senderKey -> wxid 映射：群聊的 senderKey 是「会话|昵称」，而头像磁盘文件按 wxid 命名，
    // 两者对不上会导致已缓存的头像读不出来（表现为头像占位）。预取时记下映射，构建通知时反查。
    private val senderWxidMap = LruCache<String, String>(maxLimit = 256)

    private data class HistoryEntry(val senderName: String, val text: String, val timestamp: Long, val senderKey: String)

    // Per-conversation message history rebuilt into MessagingStyle on each notification update.
    // Cleared when the user replies or marks as read; bounded to avoid unbounded growth.
    private val messageHistory = LinkedHashMap<String, ArrayDeque<HistoryEntry>>()
    private const val MAX_HISTORY = 7

    private const val ACTION_REPLY = "${PackageNames.WECHAT}.ACTION_WEKIT_REPLY"
    private const val ACTION_MARK_READ = "${PackageNames.WECHAT}.ACTION_WEKIT_MARK_READ"
    private const val ACTION_NOTIFICATION_OPENED = "${PackageNames.WECHAT}.ACTION_WEKIT_NOTIFICATION_OPENED"
    private const val ACTION_NOTIFICATION_DISMISSED = "${PackageNames.WECHAT}.ACTION_WEKIT_NOTIFICATION_DISMISSED"

    // WeChat's original contentIntent per convWxId, stored so we can fire it after clearing history.
    private val pendingContentIntents = HashMap<String, PendingIntent>()

    private lateinit var meAvatarIcon: Icon

    // sender 头像磁盘缓存目录：微信头像（CDN 或本地路径）落盘，二次进入通知直接读盘，
    // 避免每次都要网络下载导致头像加载不出来/缓慢
    // 头像缓存目录带版本：v3 起缓存微信原生样式小圆角头像，旧版（大圆角/直角方形）缓存不再读取
    private val avatarCacheDir by lazy { KnownPaths.moduleData / "notif_avatars_v3" }

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val targetWxId = intent.getStringExtra("extra_target_wxid") ?: return
            val notificationManager =
                context.getSystemService<NotificationManager>()

            when (intent.action) {
                ACTION_REPLY -> {
                    val results = RemoteInput.getResultsFromIntent(intent) ?: return
                    val replyContent = results.getCharSequence("key_reply_content")?.toString()

                    if (replyContent.isNullOrEmpty())
                        return

                    WeLogger.i(TAG, "quick replying " + replyContent + " to " + targetWxId)
                    // 该接收器在 main 与 push 两个进程都会注册，一次回复广播会被两个进程各处理一次。
                    // 只有主进程具备发送所需的 NetScene 服务：push 进程里 NetSceneSendMsg 构造会抛异常
                    // （diag 里表现为成对的 sendtext ctor-obj6 failed / all-ctor-failed），
                    // 因此发送只在主进程执行，避免重复发送与 push 进程的必然失败。
                    val inMain = TargetProcesses.isInMain
                    val sendOk = if (inMain) {
                        runCatching { WeMessageApi.sendText(targetWxId, replyContent) }.getOrElse { false }
                    } else {
                        false
                    }
                    val readOk = runCatching { WeConversationApi.markAsRead(targetWxId) }.isSuccess
                    runCatching {
                        val f = java.io.File("/sdcard/Android/data/com.tencent.mm/WCX/diag.log")
                        f.parentFile?.mkdirs()
                        java.io.FileWriter(f, true).use { it.append(System.currentTimeMillis().toString() + " quickreply to=" + targetWxId + " len=" + replyContent.length + " send=" + (if (inMain) sendOk.toString() else "skip-nonmain") + " read=" + readOk + "\n") }
                    }
                    notificationManager.cancel(targetWxId.hashCode())
                }
                ACTION_MARK_READ -> {
                    WeLogger.i(TAG, "marking chat as read for $targetWxId")
                    WeConversationApi.markAsRead(targetWxId)
                    messageHistory.remove(targetWxId)
                    pendingContentIntents.remove(targetWxId)
                    notificationManager.cancel(targetWxId.hashCode())
                }

                ACTION_NOTIFICATION_OPENED -> {
                    // Notification was tapped — clear history, then hand off to WeChat's own intent.
                    messageHistory.remove(targetWxId)
                    pendingContentIntents.remove(targetWxId)?.send()
                }

                ACTION_NOTIFICATION_DISMISSED -> {
                    // Notification was swiped away — just clear history.
                    messageHistory.remove(targetWxId)
                    pendingContentIntents.remove(targetWxId)
                }
            }
        }
    }

    private val MESSAGE_REGEX = Regex("""^(\[\d+条])?(.+?)?: (.*)$""", RegexOption.DOT_MATCHES_ALL)

    override fun onEnable() {
        CoroutineScope(Dispatchers.IO).launch {
            while (runCatching { WeApi.selfWxId.isEmpty() }
                    .getOrDefault(true)) {
                delay(2000.milliseconds)
            }
            // 复用统一头像加载（磁盘缓存 → 本地路径 → CDN 下载 + 圆角裁剪）
            val meIcon = runCatching { loadAvatarIcon(WeApi.selfWxId) }.getOrNull()
            if (meIcon != null) {
                meAvatarIcon = Icon.createWithBitmap(meIcon)
            } else {
                WeLogger.w(TAG, "failed to fetch me avatar")
            }
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_REPLY)
            addAction(ACTION_MARK_READ)
            addAction(ACTION_NOTIFICATION_OPENED)
            addAction(ACTION_NOTIFICATION_DISMISSED)
        }
        ContextCompat.registerReceiver(
            HostInfo.application, notificationReceiver, filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Capture the exact talker wxid before WeChat builds the notification.
        // x.d → m0.a → e0.b → Notification.Builder.build() all run synchronously on
        // this thread, so the build() hook below reads it back via the ThreadLocal.
        methodDealNotify.hookBefore {
            currentTalker.set(args[1] as? String)
        }

        Notification.Builder::class.reflekt()
            .firstMethod { name = "build" }
            .hookBefore {
                val context = HostInfo.application

                val builder = thisObject as Notification.Builder
                val notif = builder.reflekt().firstField { type = Notification::class }
                    .get() as Notification
                val channelId = notif.channelId

                if (channelId != "message_channel_new_id") {
                    return@hookBefore
                }

                val notifTitle = notif.extras.getString(Notification.EXTRA_TITLE)
                    ?: "未知对话 (请向模块开发者报告错误)"
                val notifText =
                    notif.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                        ?: "未知内容 (请向模块开发者报告错误)"

                // 1. Resolve exact WXID from the talker captured in the x.d hook
                val convWxId = currentTalker.get()
                if (convWxId == null) {
                    WeLogger.w(TAG, "no talker captured for $notifTitle, skipping enhancements")
                    return@hookBefore
                }

                val match = MESSAGE_REGEX.find(notifText)

                var senderName: String
                var text: String
                if (match == null) {
                    WeLogger.w(
                        TAG,
                        "failed to match message regex, using raw sender name & text content"
                    )
                    // 群聊里 notifTitle 是**群名**，不是发送者。图片/定位/表情这类通知
                    // 的正文没有「昵称: 」前缀，正则会失配；此时若把群名当发送者，
                    // 通知会显示成群名、头像也查不到（见 resolveSenderWxid 里的 @chatroom 说明）。
                    // 退回上一次该群已知的发送者，没有就留空（宁可不显示，也不要显示错的人）。
                    senderName = if (convWxId.isGroupChatWxId) {
                        lastGroupChatSender[convWxId] ?: ""
                    } else {
                        notifTitle
                    }
                    text = notifText
                } else {
                    senderName = match.groupValues[2].takeIf { it.isNotEmpty() }
                        ?.also { lastGroupChatSender[convWxId] = it }
                        ?: lastGroupChatSender[convWxId] ?: run {
                            WeLogger.w(
                                TAG,
                                "couldn't find sender name in either notification or cache"
                            )
                            notifTitle
                        }
                    text = match.groupValues[3]
                }

                // 诊断：替换前的通知原文。表情类占位符的真实形态只能靠实测确定——
                // 微信的表情名表既不在 dex（UTF-8 精确字节搜索 0 命中）也不在
                // files/public/emoji 的 xml 里，无法离线取得权威映射，故先记录原文。
                // 若正文含 [xxx]，把 xxx 补进 MessageTextUtils 的对应映射表即可。
                runCatching {
                    val raw = text
                    val tags = Regex("\\[[^]]+]").findAll(raw).map { it.value }.toList()
                    WeLogger.i(
                        TAG,
                        "raw notif text=[$raw] tags=${if (tags.isEmpty()) "none" else tags.joinToString(" ")}"
                    )
                }

                text = text
                    .replaceRichContent()
                    .replaceEmojis()

                WeLogger.i(TAG, "enhancing notification for $notifTitle ($convWxId)")

                // 2. Build the MessagingStyle, accumulating messages so that "2" doesn't
                //    erase "1" when the user hasn't acted on the notification yet.
                // TODO: add cropping
                val mePerson = Person.Builder().setName("我")
                    .apply {
                        if (::meAvatarIcon.isInitialized)
                            setIcon(meAvatarIcon)
                    }
                    .build()
                val messagingStyle = Notification.MessagingStyle(mePerson)

                if (convWxId.isGroupChatWxId) {
                    messagingStyle.isGroupConversation = true
                    // 群名同样可能含 emoji 占位符（见渲染 Person 名处的说明），一并还原。
                    messagingStyle.conversationTitle = notifTitle.replaceRichContent().replaceEmojis()
                } else {
                    senderName = notifTitle
                }

                // 群聊里解析不出发送者（见上面的正则失配分支）就别记进历史：
                // 记一条空名字会让这条消息永久显示成「无名氏」，还会把后续正确解析
                // 的头像/名字覆盖掉。直接跳过，等微信下一次推送带上昵称再展示。
                if (senderName.isEmpty()) {
                    WeLogger.w(TAG, "群聊未解析出发送者，跳过本次增强 conv=$convWxId")
                    return@hookBefore
                }

                // Append the new message to this conversation's history, then replay
                // the whole history into the style so previous messages are not lost.
                val history = messageHistory.getOrPut(convWxId) { ArrayDeque() }
                history.addLast(HistoryEntry(senderName, text, System.currentTimeMillis(), senderCacheKey(convWxId, senderName)))
                while (history.size > MAX_HISTORY) history.removeFirst()

                for (entry in history) {
                    // 昵称/群名里的表情占位符要还原成图案，理由同正文：
                    //
                    // 微信会把**昵称本身**里的 emoji 也转成 `[名称]`（实测：群名「18🈲🐭」在
                    // 通知里变成「18[表情][老鼠]」）。这个转换发生在微信构建通知之前，
                    // 所以 EXTRA_TITLE / Person 名拿到的就已经是 tag 文本，`replaceEmojis`
                    // 若不在这里再跑一遍，通知标题就会显示方括号文字。
                    //
                    // 注意只替换**渲染用的副本**：entry.senderName 原值仍用于头像查找
                    // （resolveSenderWxid / senderKey 都按微信库里的原始昵称匹配），
                    // 就地改掉会让头像查不到。
                    val displayName = entry.senderName.replaceRichContent().replaceEmojis()
                    val personBuilder = Person.Builder().setName(displayName)
                    // 头像三级查找：内存 → 映射反查磁盘 → 按 key 直接读磁盘。
                    //
                    // 之前群聊（senderKey 含 "|"）被排除在磁盘兜底之外，而磁盘文件名按 wxid 生成，
                    // 单聊的 senderKey 恰好就是 wxid、群聊的却不是 —— 两者叠加导致群聊通知
                    // 在内存缓存未命中时（首条、被 LRU 挤掉、预取未完成）必然显示占位。
                    //
                    // 另外这里全部只读「本扩展自己的」缓存，而首条通知时它是空的（异步预取还没回来）。
                    // 所以还要补一层微信自己的头像缓存：同步读盘，已聊过的人必然命中。
                    val cachedIcon = senderAvatarCache[entry.senderKey]
                    if (cachedIcon != null) {
                        personBuilder.setIcon(cachedIcon)
                    } else {
                        val resolvedWxid = senderWxidMap[entry.senderKey]
                            ?: resolveSenderWxid(convWxId, entry.senderName)
                            ?.also { senderWxidMap[entry.senderKey] = it }

                        val bmp = resolvedWxid?.let { loadAvatarIconFromCache(it) }
                            ?: loadAvatarIconFromCache(entry.senderKey)
                            ?: resolvedWxid?.let { loadAvatarFromWeChatCache(it) }

                        if (bmp != null) personBuilder.setIcon(Icon.createWithBitmap(bmp))
                    }
                    messagingStyle.addMessage(entry.text, entry.timestamp, personBuilder.build())
                }

                builder.style = messagingStyle

                // 交给 MessagingStyle 独占渲染后，必须清掉微信原本写进 extras 的标题与内容。
                //
                // 否则同一段文字会被渲染两遍：群聊里 conversationTitle 已设成 notifTitle，
                // 系统 header 又照 EXTRA_TITLE 再画一次标题，于是「标题下方又多显示一行标题」；
                // 单聊同理——MessagingStyle 拿 Person 名当标题，EXTRA_TEXT 里那句
                // 「张三：你好」还会作为正文再出现一次。
                //
                // 折叠态不会因此失去标题：系统会改用 style 自己的 conversationTitle / Person 名。
                builder.setContentTitle(null)
                builder.setContentText(null)

                // 2.5. Wrap WeChat's contentIntent so tapping the notification clears
                //      history before handing off to WeChat's own chat-open flow.
                //      Also attach a deleteIntent to catch swipe-dismiss.
                val originalContentIntent = notif.contentIntent
                if (originalContentIntent != null) {
                    pendingContentIntents[convWxId] = originalContentIntent
                    val openIntent = Intent(ACTION_NOTIFICATION_OPENED).apply {
                        setPackage(PackageNames.WECHAT)
                        putExtra("extra_target_wxid", convWxId)
                    }
                    builder.setContentIntent(
                        PendingIntent.getBroadcast(
                            context, convWxId.hashCode(), openIntent,
                            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                }
                val dismissIntent = Intent(ACTION_NOTIFICATION_DISMISSED).apply {
                    setPackage(PackageNames.WECHAT)
                    putExtra("extra_target_wxid", convWxId)
                }
                builder.setDeleteIntent(
                    PendingIntent.getBroadcast(
                        context, convWxId.hashCode(), dismissIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )

                // 3. Quick Reply Action
                val remoteInput = RemoteInput.Builder("key_reply_content")
                    .setLabel("输入回复内容...")
                    .build()

                val replyIntent = Intent(ACTION_REPLY).apply {
                    setPackage(PackageNames.WECHAT)
                    putExtra("extra_target_wxid", convWxId)
                }
                val replyPendingIntent = PendingIntent.getBroadcast(
                    context, convWxId.hashCode(), replyIntent,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                val replyAction = Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_send),
                    "回复", replyPendingIntent
                ).addRemoteInput(remoteInput).build()

                // 4. Mark as Read Action
                val readIntent = Intent(ACTION_MARK_READ).apply {
                    setPackage(PackageNames.WECHAT)
                    putExtra("extra_target_wxid", convWxId)
                }
                val readPendingIntent = PendingIntent.getBroadcast(
                    context, convWxId.hashCode(), readIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val readAction = Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_view),
                    "标为已读", readPendingIntent
                ).build()

                // Apply actions directly to the builder
                builder.addAction(replyAction)
                builder.addAction(readAction)

                // 异步预取发送者头像，供下一条通知的 MessagingStyle 使用
                prefetchSenderAvatar(convWxId, senderName)
            }

        // 合并同一会话的多条通知（通知栏同一会话只保留最新一条）
        hookNotifyIdMerge()
    }

    // ==================== 通知合并：同一会话的通知 id 统一为会话哈希 ====================
    // 微信原始 notify id -> convWxId 映射：微信已读消息时用原始 id 调 NotificationManager.cancel()，
    // 需经该映射转换成合并后的 id 才能真正取消，并同步清空该会话的 MessagingStyle history，
    // 否则已读消息会在下一条通知里被「带出」。
    private val notifyIdMap = HashMap<Int, String>()

    private fun hookNotifyIdMerge() {
        val notifCls = Notification::class.java
        val nmCls = android.app.NotificationManager::class.java
        runCatching {
            nmCls.getMethod("notify", Int::class.javaPrimitiveType, notifCls)
                .hookBeforeDirectly {
                    val convWxId = currentTalker.get()
                    if (convWxId != null) {
                        val n = args[1] as? Notification
                        if (n != null && n.channelId == "message_channel_new_id") {
                            val origId = args[0] as Int
                            args[0] = convWxId.hashCode()
                            recordNotifyId(origId, convWxId)
                        }
                        currentTalker.remove()
                    }
                }
            WeLogger.i(TAG, "notify(int,Notification) hook registered")
        }.onFailure { WeLogger.w(TAG, "hook notify(int) failed", it) }
        runCatching {
            nmCls.getMethod("notify", String::class.java, Int::class.javaPrimitiveType, notifCls)
                .hookBeforeDirectly {
                    val convWxId = currentTalker.get()
                    if (convWxId != null) {
                        val n = args[2] as? Notification
                        if (n != null && n.channelId == "message_channel_new_id") {
                            val origId = args[1] as Int
                            args[1] = convWxId.hashCode()
                            recordNotifyId(origId, convWxId)
                        }
                        currentTalker.remove()
                    }
                }
            WeLogger.i(TAG, "notify(tag,int,Notification) hook registered")
        }.onFailure { WeLogger.w(TAG, "hook notify(tag,int) failed", it) }

        // 微信已读/清理通知：把原始 id 转换回合并后的 id 才能真正取消，
        // 并清空该会话 history，避免已读消息在下一条通知里被带出。
        runCatching {
            nmCls.getMethod("cancel", Int::class.javaPrimitiveType)
                .hookBeforeDirectly {
                    val origId = args[0] as Int
                    synchronized(notifyIdMap) {
                        val convWxId = notifyIdMap.remove(origId)
                        if (convWxId != null) {
                            messageHistory.remove(convWxId)
                            pendingContentIntents.remove(convWxId)
                            args[0] = convWxId.hashCode()
                            WeLogger.i(TAG, "wechat cancelled notif for $convWxId (merged id)")
                        }
                    }
                }
            WeLogger.i(TAG, "cancel(int) hook registered")
        }.onFailure { WeLogger.w(TAG, "hook cancel(int) failed", it) }
        runCatching {
            nmCls.getMethod("cancel", String::class.java, Int::class.javaPrimitiveType)
                .hookBeforeDirectly {
                    val origId = args[1] as Int
                    synchronized(notifyIdMap) {
                        val convWxId = notifyIdMap.remove(origId)
                        if (convWxId != null) {
                            messageHistory.remove(convWxId)
                            pendingContentIntents.remove(convWxId)
                            args[1] = convWxId.hashCode()
                            WeLogger.i(TAG, "wechat cancelled notif(tag) for $convWxId (merged id)")
                        }
                    }
                }
            WeLogger.i(TAG, "cancel(tag,int) hook registered")
        }.onFailure { WeLogger.w(TAG, "hook cancel(tag,int) failed", it) }
    }

    private fun recordNotifyId(origId: Int, convWxId: String) {
        synchronized(notifyIdMap) {
            if (notifyIdMap.size >= 128) notifyIdMap.clear()
            notifyIdMap[origId] = convWxId
        }
    }

    // ==================== 发送者头像：异步预取 + 缓存 ====================
    private fun senderCacheKey(convWxId: String, senderName: String): String =
        if (convWxId.isGroupChatWxId) "$convWxId|$senderName" else convWxId

    /**
     * 微信会在通知标题里给**非文本消息**附上类型标记，如「李艳[表情]: xxx」。
     * 这些标记不是昵称的一部分，直接拿去查库必然落空 —— 实测带后缀的发送者
     * 三次查询全部命中不了，表现就是头像空白。
     */
    private val SENDER_TYPE_SUFFIX = Regex(
        "\\[(表情|图片|语音|视频|动画表情|文件|位置|名片|链接|音乐|小程序|卡券|红包|转账|拍一拍)\\]$"
    )

    /** 剥掉微信附加的消息类型后缀并归一化空白。 */
    private fun cleanSenderName(raw: String): String =
        raw.trim().replace(SENDER_TYPE_SUFFIX, "").trim()

    /** 模糊比较用：去掉空白与零宽字符，让「看着一样」的名字也能对上。 */
    private fun normalizeName(raw: String): String =
        raw.filterNot { it.isWhitespace() || it == '\u200B' || it == '\uFEFF' }

    private fun resolveSenderWxid(convWxId: String, senderNameRaw: String): String? {
        if (senderNameRaw.isBlank()) return null
        if (!convWxId.isGroupChatWxId) return convWxId

        // 「李艳[表情]」这类后缀必须先剥掉，否则 roomdata / rcontact 都查不到。
        val senderName = cleanSenderName(senderNameRaw)
        if (senderName.isBlank()) return null

        // 群聊必须**先**按群成员反查，不能先查 rcontact。
        //
        // 通知上显示的名字是**群昵称**（roomdata.members[].displayName），而 rcontact
        // 是全量联系人表。两者很容易撞名：群里的「白白」和好友列表里的「白白」是
        // 完全不同的两个人，而 rcontact 那条 `nickname = ?` 查询带 LIMIT 1，
        // 取到的是**任意一个**同名的好友 —— 于是通知上挂着别人的头像。
        //
        // 群成员表是「这个群里到底有谁」的权威来源，拿它反查必然命中正确的人；
        // 只有群里查不到时，才轮到 rcontact 兜底。
        runCatching {
            val blob = WeDatabaseApi.executeQuery(
                "SELECT roomdata FROM chatroom WHERE chatroomname = '" +
                    convWxId.replace("'", "''") + "'"
            ).firstOrNull()?.get("roomdata") as? ByteArray
            if (blob != null) {
                val roomData = ProtoBuf.decodeFromByteArray<ChatRoomDataProto>(blob)
                // 先精确匹配（绝大多数情况）。命中不了再按归一化名字兜底：微信有时会把
                // 昵称里的空格/零宽字符改写，或长昵称在通知里被截断。
                // 兜底仍限定在「该群成员」范围内，不会退到 rcontact 去撞名。
                val exact = roomData.members.firstOrNull { it.displayName == senderName }
                val matched = exact ?: run {
                    val target = normalizeName(senderName)
                    if (target.isEmpty()) null
                    else roomData.members.firstOrNull { m ->
                        val n = normalizeName(m.displayName)
                        n.isNotEmpty() && (n == target || n.startsWith(target))
                    }
                }
                if (exact == null && matched != null) {
                    WeLogger.i(
                        TAG,
                        "resolveSenderWxid: 群成员模糊命中 name=$senderName → ${matched.displayName}"
                    )
                }
                matched?.wxId?.takeIf { it.isNotEmpty() }
            } else null
        }.getOrNull()?.let {
            WeLogger.i(TAG, "resolveSenderWxid: 群成员命中 conv=$convWxId name=$senderName → $it")
            return it
        }

        // 群成员表没命中（昵称带后缀、被截断等）：按昵称/备注在联系人表里匹配。
        // 注意这里仍可能撞名，只作为兜底。
        //
        // 必须排除 %@chatroom：rcontact 里**群聊自身**也有一条记录，其 nickname 就是群名。
        // 群聊里那些正文没有「昵称: 」的通知（图片/定位/表情/语音）会走到这里，
        // 拿群名去查就会命中群自己 —— 头像查的是群 ID（取不到，表现为空白），
        // 发送者也被显示成群名。实测踩过：
        //   resolveSenderWxid: 群成员未命中，回退 rcontact name=测试 → 59058485546@chatroom
        runCatching {
            val esc = senderName.replace("'", "''")
            WeDatabaseApi.executeQuery(
                "SELECT username FROM rcontact WHERE (nickname = '$esc' OR conRemark = '$esc') " +
                    "AND username NOT LIKE '%@chatroom' LIMIT 1"
            ).firstOrNull()?.get("username")?.toString()
        }.getOrNull()?.let {
            WeLogger.w(TAG, "resolveSenderWxid: 群成员未命中，回退 rcontact conv=$convWxId name=$senderName → $it（可能撞名）")
            return it
        }
        // 精确匹配失败：LIKE 模糊兜底（备注/昵称可能有空格/符号差异）
        return runCatching {
            val esc = senderName.replace("'", "''")
            WeDatabaseApi.executeQuery(
                "SELECT username FROM rcontact WHERE (nickname LIKE '%$esc%' OR conRemark LIKE '%$esc%') " +
                    "AND username NOT LIKE '%@chatroom' LIMIT 1"
            ).firstOrNull()?.get("username")?.toString()
        }.getOrNull()
    }

    private fun prefetchSenderAvatar(convWxId: String, senderName: String) {
        val key = senderCacheKey(convWxId, senderName)
        if (senderAvatarCache[key] != null) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val wxid = resolveSenderWxid(convWxId, senderName) ?: run {
                    WeLogger.w(TAG, "预取头像：解析不到 wxid key=$key")
                    return@runCatching
                }
                // 先记映射再下载：即使这次下载失败，磁盘里也可能已有他人预取过的缓存，
                // 记下 wxid 才能让构建通知时的反查命中。
                senderWxidMap[key] = wxid
                val bmp = loadAvatarIcon(wxid) ?: run {
                    WeLogger.w(TAG, "预取头像：加载失败 wxid=$wxid key=$key")
                    return@runCatching
                }
                senderAvatarCache[key] = Icon.createWithBitmap(bmp)
                WeLogger.i(TAG, "预取头像成功 key=$key wxid=$wxid")
            }.onFailure { WeLogger.w(TAG, "预取头像失败 key=$key: ${it.message}") }
        }
    }

    // ==================== 头像加载：磁盘缓存 → 本地路径 → CDN 下载 + 圆角 ====================

    private fun avatarCacheFileFor(wxid: String): Path =
        avatarCacheDir / (wxid.replace(Regex("[^\\w@.-]"), "_") + ".png")

    /** 只读磁盘缓存（不触网）：通知构建时同步调用，命中立即显示头像（首次异步预取未完成时兜底）。 */
    private fun loadAvatarIconFromCache(wxid: String): Bitmap? = runCatching {
        val f = avatarCacheFileFor(wxid)
        if (f.exists() && f.toFile().length() > 0) BitmapFactory.decodeFile(f.pathString) else null
    }.getOrNull()

    /**
     * 直接读微信自己的头像缓存（不触网、同步、必然命中已聊过的人）。
     *
     * 微信把头像按 md5(wxid) 分层落盘：
     *   <userDataDir>/avatar/<md5[0:2]>/<md5[2:4]>/<md5>/
     * 目录内 `user_<md5>.png` 是主头像，`small_*` 是小图补充。虽然叫 .png，
     * 实际可能是 JPEG（96x96）或 PNG（156x156 / 416x416），BitmapFactory 都能解。
     *
     * 这是首条通知头像缺失的正解：原来只能靠异步 CDN 下载，首次必然晚于通知构建；
     * 而这里的数据微信早就落盘了（截图里能看到头像就说明本地有），读盘是毫秒级的。
     */
    private fun loadAvatarFromWeChatCache(wxid: String): Bitmap? {
        if (wxid.isBlank()) return null
        return runCatching {
            val md5 = java.security.MessageDigest.getInstance("MD5")
                .digest(wxid.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

            val dir = RuntimeConfig.userDataDir / "avatar" / md5.substring(0, 2) / md5.substring(2, 4) / md5
            if (!dir.exists()) return@runCatching null

            // 优先 user_*.png（主头像，覆盖最广），再退到 small_*（改名过的用户）
            val candidates = listOf(
                dir / "user_$md5.png",
                dir / "user_hd_$md5.png",
            ) + runCatching {
                Files.list(dir).use { s ->
                    s.filter { it.fileName.toString().startsWith("small_") }
                        .sorted()
                        .limit(1)
                        .toList()
                }
            }.getOrDefault(emptyList())

            candidates.firstOrNull { it.exists() && it.toFile().length() > 0 }
                ?.let { BitmapFactory.decodeFile(it.pathString) }
        }.getOrNull()
    }

    /**
     * 统一头像加载（圆角化）：
     * 1. 磁盘缓存命中直接读盘（二次进入通知立即显示，不再网络下载）；
     * 2. 微信数据库返回的头像 URL 可能是本地缓存路径（img_flag.reserved2），直接 decodeFile；
     * 3. 否则按 CDN URL 下载（带 UA/重定向/超时，失败时用 rcontact 兜底）。
     * 返回已缩放 + 圆角裁剪的 Bitmap。
     */
    private fun loadAvatarIcon(wxid: String): Bitmap? {
        if (wxid.isBlank()) return null

        // 1. 磁盘缓存
        val cacheFile = avatarCacheFileFor(wxid)
        runCatching {
            if (cacheFile.exists() && cacheFile.toFile().length() > 0) {
                BitmapFactory.decodeFile(cacheFile.pathString)?.let { return it }
            }
        }

        // 2. 微信自己的头像缓存（本地读盘，毫秒级，不用等网络）
        loadAvatarFromWeChatCache(wxid)?.let { return finishAvatar(it, wxid, cacheFile) }

        // 3. 微信头像 URL（可能是 CDN 地址或本地缓存路径）
        var bmp: Bitmap? = null
        var urlStr = runCatching { WeDatabaseApi.getAvatarUrl(wxid) }.getOrNull() ?: ""
        // img_flag 没有时查 rcontact（bigHeadImgUrl/smallHeadImgUrl/avatarUrl）。
        // 同一个值后面还要用来做 CDN 兜底，所以只查一次、复用。
        if (urlStr.isBlank()) urlStr = queryContactAvatarFallback(wxid)

        if (urlStr.isNotEmpty()) {
            if (urlStr.startsWith("http://") || urlStr.startsWith("https://")) {
                bmp = downloadAvatarBitmap(urlStr)
            } else {
                // 本地缓存路径（如 /data/data/.../avatar/xxx 或文件名）
                runCatching {
                    val f = File(urlStr)
                    if (f.exists()) bmp = BitmapFactory.decodeFile(f.path)
                }
            }
        }

        // 3. CDN 兜底：上面的 URL 下载失败时，若它不是 http 地址（是本地文件名），
        //    再查一次 rcontact 拿 CDN 地址重试。
        if (bmp == null && !urlStr.startsWith("http")) {
            val fallback = queryContactAvatarFallback(wxid)
            if (fallback.startsWith("http://") || fallback.startsWith("https://")) {
                bmp = downloadAvatarBitmap(fallback)
            }
        }

        if (bmp == null) {
            WeLogger.w(TAG, "头像加载失败 wxid=$wxid url=${urlStr.take(80)}")
            return null
        }

        return finishAvatar(bmp, wxid, cacheFile)
    }

    /** 缩放至通知尺寸 → 圆角裁剪 → 落盘缓存。各头像来源共用这一收尾。 */
    private fun finishAvatar(src: Bitmap, wxid: String, cacheFile: Path): Bitmap {
        var bmp = src

        // 缩放至通知头像尺寸
        val maxSize = 192
        if (bmp.width > maxSize || bmp.height > maxSize) {
            val scale = maxSize.toFloat() / maxOf(bmp.width, bmp.height)
            bmp = Bitmap.createScaledBitmap(
                bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true
            )
        }

        // 圆角裁剪（微信原生样式：小圆角圆角矩形）
        val rounded = toRoundedBitmap(bmp)

        // 落盘缓存，下次直接读盘
        runCatching {
            Files.createDirectories(avatarCacheDir)
            val baos = java.io.ByteArrayOutputStream()
            rounded.compress(Bitmap.CompressFormat.PNG, 100, baos)
            cacheFile.writeBytes(baos.toByteArray())
        }
        return rounded
    }

    /** 圆角矩形裁剪（微信原生样式：小圆角，10% 半径——非直角正方形，也非大圆角） */
    private fun toRoundedBitmap(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val radius = minOf(src.width, src.height) * 0.1f
        canvas.drawRoundRect(0f, 0f, src.width.toFloat(), src.height.toFloat(), radius, radius, paint)
        return out
    }

    /** 头像 CDN 下载（UA/重定向/超时，与侧边栏一致的行为） */
    private fun downloadAvatarBitmap(urlStr: String): Bitmap? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                doInput = true
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                connection.inputStream.use { BitmapFactory.decodeStream(it) }
            }
        } catch (e: Throwable) {
            WeLogger.w(TAG, "下载头像失败: ${e.message}")
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /** fallback: rcontact 表里读头像 URL */
    private fun queryContactAvatarFallback(wxid: String): String = try {
        val rows = WeDatabaseApi.executeQuery(
            "SELECT COALESCE(bigHeadImgUrl, smallHeadImgUrl, avatarUrl, '') AS u " +
                    "FROM rcontact WHERE username='" + wxid.replace("'", "''") + "'"
        )
        if (rows.isNotEmpty()) rows[0]["u"]?.toString() ?: "" else ""
    } catch (e: Throwable) {
        WeLogger.w(TAG, "queryContactAvatarFallback 失败", e)
        ""
    }
}
