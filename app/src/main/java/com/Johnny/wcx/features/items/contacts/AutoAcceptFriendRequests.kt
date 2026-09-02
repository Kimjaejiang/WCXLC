package com.Johnny.wcx.features.items.contacts
import de.robv.android.xposed.XC_MethodHook

import android.annotation.SuppressLint
import android.content.ContentValues
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.dexkit.dsl.dexConstructor
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.net.WeNetSceneApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.luckypray.dexkit.DexKitBridge
import kotlin.random.Random

@SuppressLint("SetTextI18n")
@Feature(
    name = "自动同意好友申请",
    categories = ["联系人与群组"],
    description = "自动同意好友申请，支持延迟设置、自动发送欢迎语、黑名单过滤"
)
object AutoAcceptFriendRequests : ClickableFeature(), IResolveDex,
    WeDatabaseListenerApi.IInsertListener {

    private const val TAG = "AutoAcceptFriendReq"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    // ==================== 持久化偏好 ====================
    private var masterEnabled by prefOption("aafr_master_enabled", false)
    private var delayMode by prefOption("aafr_delay_mode", 0) // 0 = fixed, 1 = random
    private var fixedDelayMs by prefOption("aafr_fixed_delay_ms", 1000)
    private var randomDelayMinMs by prefOption("aafr_random_delay_min_ms", 1000)
    private var randomDelayMaxMs by prefOption("aafr_random_delay_max_ms", 5000)
    private var welcomeText by prefOption("aafr_welcome_text", "你好，我已通过你的好友申请")
    private var sendWelcome by prefOption("aafr_send_welcome", true)
    private var blacklistJson by prefOption("aafr_blacklist", "[]")

    // 已处理的好友请求（防重复处理）
    private val processedRequests = mutableSetOf<String>()

    private fun getBlacklist(): Set<String> {
        return runCatching {
            json.decodeFromString<Set<String>>(blacklistJson)
        }.getOrDefault(emptySet())
    }

    enum class DelayMode(val value: Int, val description: String) {
        FIXED(0, "固定延迟"),
        RANDOM(1, "随机延迟")
    }

    // ==================== DexKit — 好友验证接受方法 ====================

    /**
     * 8.0.76 起 NetSceneVerifyUser 混淆为 pluginsdk.model.m3，旧日志字符串
     * "summerverify opcode[%s], verifyContent[%s], verifyScene[%s]" 被移除，
     * 接受操作的 opcode 改为 MM_VERIFYUSER_VERIFYOK 语义。
     * <init> 内含断言日志 "init MUST use opcode == MM_VERIFYUSER_VERIFYOK"，
     * 以此定位构造器（首参为 opcode）。
     */
    private const val OPCODE_VERIFY_ACCEPT = 3 // 8.0.77: MM_VERIFYUSER_VERIFYOK（实测构造器校验通过值，1/2 均被拒）

    // 好友验证接受方法：NetSceneVerifyUser / NetSceneAddFriend 等
    // 在 WeChat 中，接受好友验证的典型方法是 VerifyUserTask 或 NetSceneVerifyUser
    private val methodVerifyAccept by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings(
                "This NetSceneVerifyUser init MUST use opcode == MM_VERIFYUSER_VERIFYOK"
            )
        }
    }

    // 8.0.76 的 NetSceneVerifyUser 构造器（m3.<init>），用于主动构造"接受"请求
    // 使用内联查找版 dexConstructor（resolveInlineDex 时自动解析）
    private val ctorVerifyUserAccept by dexConstructor {
        searchPackages("com.tencent.mm.pluginsdk.model")
        matcher {
            usingEqStrings("This NetSceneVerifyUser init MUST use opcode == MM_VERIFYUSER_VERIFYOK")
        }
    }

    // 好友验证页面的 initView — 用于检测用户手动进入验证页面时提取信息
    // 备用：如果 NetScene 方法无法匹配，通过验证页面输入来触发
    private val methodVerifyOkClick by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings(
                "MicroMsg.VerifyUserUtil",
                "verify ok clicked"
            )
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        // 8.0.76+：NetSceneVerifyUser 混淆为 m3，<init> 含 MM_VERIFYUSER_VERIFYOK 断言日志
        methodVerifyAccept.find(dexKit, allowFailure = true) {
            matcher {
                usingEqStrings(
                    "This NetSceneVerifyUser init MUST use opcode == MM_VERIFYUSER_VERIFYOK"
                )
            }
        }

        // 旧版特征（8.0.7x 及更早）
        if (methodVerifyAccept.isPlaceholder) {
            methodVerifyAccept.find(dexKit, allowFailure = true) {
                matcher {
                    usingEqStrings(
                        "MicroMsg.NetSceneVerifyUser",
                        "summerverify opcode[%s], verifyContent[%s], verifyScene[%s]"
                    )
                }
            }
        }

        methodVerifyOkClick.find(dexKit, allowFailure = true) {
            matcher {
                usingEqStrings(
                    "MicroMsg.VerifyUserUtil",
                    "verify ok clicked"
                )
            }
        }
    }

    // ==================== 生命周期 ====================

    override fun onEnable() {
        WeLogger.i(TAG, "onEnable: masterEnabled=" + masterEnabled + " ctorVerifyUserAccept=" + !ctorVerifyUserAccept.isPlaceholder + " methodVerifyOkClick=" + !methodVerifyOkClick.isPlaceholder)
        WeDatabaseListenerApi.addListener(this)
        startRcontactPoller()
        // 检查 DexKit 方法是否成功解析
        val verifyUnavailable = methodVerifyAccept.isPlaceholder
        val verifyOkUnavailable = methodVerifyOkClick.isPlaceholder

        if (verifyUnavailable && verifyOkUnavailable) {
            WeLogger.w(TAG, "当前微信版本暂不支持自动同意好友申请 — DexKit 方法均未匹配")
            runCatching {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    showToast("当前微信版本暂不支持自动同意好友申请")
                }
            }
        }

        // 钩住好友验证接受方法，在微信内部接受好友请求时触发后续逻辑
        runCatching {
            if (!ctorVerifyUserAccept.isPlaceholder) {
                // 8.0.77: NetSceneVerifyUser 混淆为 p3, 接受入口在 <init>(opcode=VERIFYOK), 用构造器 hook
                ctorVerifyUserAccept.constructor.hookAfter {
                    // 8.0.76 的 <init>(opcode, userId, ticket, scene, ...) 与旧版
                    // NetSceneVerifyUser 接受方法参数布局不同，先防护再取参。
                    if (args.size < 3) return@hookAfter
                    if (!masterEnabled) return@hookAfter
                    val opcode = args[0] as? Int ?: return@hookAfter
                    // 8.0.76: MM_VERIFYUSER_VERIFYOK == 1；旧版接受 opcode == 2
                    if (opcode != OPCODE_VERIFY_ACCEPT && opcode != 2) return@hookAfter

                    // 8.0.76: args[1] = userId(encryptUsername), args[2] = ticket
                    // 旧版: args[1] = verifyContent("v2_encrypt@ticket@scene"), args[2] = verifyScene
                    val arg1 = (args[1] as? String)?.takeIf { it.isNotBlank() } ?: return@hookAfter

                    WeLogger.i(TAG, "friend request accepted via verify: arg1=$arg1")
                    WeLogger.i(
                        TAG,
                        "verify ctor args: size=" + args.size + " " +
                            args.joinToString(" | ") { a -> a?.let { it.javaClass.simpleName + "=" + it } ?: "null" }
                    )
                    // 欢迎语统一在 acceptFriendRequest 发送；此处只记录（本 hook 会同时被模块
                    // 构造与微信原生接受触发，若也发欢迎语会与模块路径重复发送）。
                }
            } else if (!methodVerifyAccept.isPlaceholder) {
                methodVerifyAccept.hookAfter {
                    if (args.size < 3) return@hookAfter
                    if (!masterEnabled) return@hookAfter
                    val opcode = args[0] as? Int ?: return@hookAfter
                    if (opcode != OPCODE_VERIFY_ACCEPT && opcode != 2) return@hookAfter
                    val arg1 = (args[1] as? String)?.takeIf { it.isNotBlank() } ?: return@hookAfter
                    WeLogger.i(TAG, "friend request accepted via legacy method: arg1=$arg1")
                }
            } else {
                WeLogger.w(TAG, "methodVerifyAccept not resolved, auto-accept will use database listener fallback")
            }
        }.onFailure { e ->
            WeLogger.e(TAG, "failed to hook verify accept", e)
        }
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        pollerScope.cancel()
        processedRequests.clear()
        processedWxids.clear()
    }

    // ==================== 数据库监听 — 检测好友验证消息 ====================

    override fun onInsert(table: String, values: ContentValues) {
        WeLogger.i(TAG, "onInsert: table=" + table + " masterEnabled=" + masterEnabled)
        if (table == "rcontact") {
            handleRcontactInsert()
            return
        }
        if (table == "VerifyRecordMsgInfo" || table == "fmessage_msginfo") {
            val content = values.getAsString("content") ?: values.getAsString("msgContent")
            WeLogger.i(TAG, "[verifyInsert] table=$table contentLen=${content?.length ?: 0}")
            if (!content.isNullOrEmpty()) {
                WeLogger.i(TAG, "[verifyInsert] content=$content")
                handleFriendVerifyContent(content)
            }
            return
        }
        if (table != "message") return
        if (!masterEnabled) return

        val msgInfo = runCatching { MessageInfo.fromContentValues(values) }
            .onFailure { WeLogger.e(TAG, "MessageInfo.fromContentValues failed", it) }
            .getOrNull() ?: return
        if (msgInfo.isSelfSender) return
        if (msgInfo.typeCode != MessageType.FRIEND_VERIFY.code) return

        val content = msgInfo.content ?: return
        if (content.isEmpty()) return

        handleFriendVerifyContent(content)
    }

    /** 解析好友验证消息 XML 内容并执行自动同意（onInsert 与 message 轮询共用） */
    private fun handleFriendVerifyContent(content: String) {
        val encryptUsername = extractXmlValue(content, "encryptusername")
            ?: extractAttr(content, "encryptusername")
        val ticket = extractXmlValue(content, "ticket") ?: extractAttr(content, "ticket")
        val scene = extractXmlValue(content, "scene") ?: extractAttr(content, "scene") ?: ""
        val fromUser = extractXmlValue(content, "fromusername") ?: extractAttr(content, "fromusername")

        if (encryptUsername.isNullOrEmpty() || ticket.isNullOrEmpty()) {
            WeLogger.d(TAG, "friend verify message missing required fields")
            return
        }

        handleVerifyAccept(encryptUsername, ticket, scene, fromUser)
    }

    /** 通用自动同意入口：去重/黑名单后按延迟执行 accept（验证记录表与验证消息共用） */
    private fun handleVerifyAccept(encryptUsername: String, ticket: String, scene: String, fromUser: String? = null) {
        // 去重检查：key 只用 encryptUsername（稳定标识）。ticket 是同一次申请
        // 每次写入都变的一次性防重放值（微信会连插多条 VerifyRecordMsgInfo，
        // 每条 ticket 都不同），不能进 key，否则同一申请会被重复 accept。
        val requestKey = encryptUsername
        if (requestKey in processedRequests) {
            WeLogger.d(TAG, "duplicate friend request, skipped")
            return
        }
        processedRequests.add(requestKey)
        // 清理过期记录
        if (processedRequests.size > 200) {
            processedRequests.clear()
        }

        // 黑名单检查
        val blacklist = getBlacklist()
        if (encryptUsername in blacklist) {
            WeLogger.i(TAG, "user $encryptUsername is in blacklist, skipped")
            return
        }
        // 也检查原始 wxId
        if (fromUser != null && fromUser in blacklist) {
            WeLogger.i(TAG, "user $fromUser is in blacklist, skipped")
            return
        }

        WeLogger.i(TAG, "auto-accepting friend request: encryptUsername=$encryptUsername ticket=$ticket scene=$scene")

        // 计算延迟
        val delayMs = when (delayMode) {
            DelayMode.RANDOM.value -> {
                val min = randomDelayMinMs.coerceAtLeast(0)
                val max = randomDelayMaxMs.coerceAtLeast(min)
                Random.nextLong(min.toLong(), (max + 1).toLong())
            }
            else -> fixedDelayMs.coerceAtLeast(0).toLong()
        }

        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                if (delayMs > 0) {
                    delay(delayMs)
                }
                acceptFriendRequest(encryptUsername, ticket, scene)
                WeLogger.i(TAG, "friend request accepted: encryptUsername=$encryptUsername")

                // 发送欢迎语
                if (sendWelcome && welcomeText.isNotBlank()) {
                    delay(1500) // 等待好友关系建立
                    val targetWxId = findNewFriendWxId(encryptUsername)
                    if (targetWxId.isNotEmpty()) {
                        WeMessageApi.sendText(targetWxId, welcomeText)
                        WeLogger.i(TAG, "welcome text sent to $targetWxId")
                    }
                }
            }.onFailure { e ->
                WeLogger.e(TAG, "failed to accept friend request", e)
            }
        }
    }

    /**
     * 构造 NetSceneVerifyUser 接受请求。8.0.77 构造器校验 opcode == MM_VERIFYUSER_VERIFYOK，
     * 实测该校验通过的值为 3（1/2 均报 "MUST use opcode == MM_VERIFYUSER_VERIFYOK"）。
     * 遍历 1..8 仅为兼容其他版本（若首个值即成功则不再尝试后续）。
     */
    private fun buildVerifyUserAccept(encryptUsername: String, ticket: String, scene: Int): Any? {
        var firstError: Throwable? = null
        for (op in 1..8) {
            try {
                val ns = ctorVerifyUserAccept.newInstance(op, encryptUsername, ticket, scene, "", 0, null, null)
                WeLogger.i(TAG, "verify accept ctor OK with opcode=$op")
                return ns
            } catch (e: Throwable) {
                if (firstError == null) firstError = e
            }
        }
        WeLogger.w(TAG, "all opcodes failed, firstError=" + (firstError?.cause ?: firstError))
        return null
    }

    private fun acceptFriendRequest(encryptUsername: String, ticket: String, scene: String) {
        // 8.0.77：构造 NetSceneVerifyUser，构造器校验 opcode == MM_VERIFYUSER_VERIFYOK，
        // 该常量真实值不明（1/2 均被拒），这里遍历候选 opcode 找到能通过校验的值。
        runCatching {
            if (!ctorVerifyUserAccept.isPlaceholder) {
                val sceneInt = scene.toIntOrNull() ?: 0
                val netScene = buildVerifyUserAccept(encryptUsername, ticket, sceneInt)
                if (netScene != null) {
                    WeNetSceneApi.sendNetScene(netScene)
                    WeLogger.i(TAG, "verify accept NetScene sent: encryptUsername=$encryptUsername")
                    return
                }
            }
        }.onFailure { e ->
            WeLogger.w(TAG, "8.0.76 accept via ctor failed, trying legacy path", e)
            WeLogger.w(TAG, "ctor accept cause: " + (e.cause?.toString() ?: "no cause"))
        }

        if (!methodVerifyAccept.isPlaceholder) {
            // 旧版：直接调用接受方法（opcode 2）
            methodVerifyAccept.method.invoke(
                null, // static method
                2, // opcode = accept
                "v2_$encryptUsername@$ticket@$scene", // verifyContent
                "", // verifyScene
                ""  // additional
            )
        } else {
            // 回退：通过数据库操作模拟接受
            // 这是简化的实现，实际接受需要调用微信的 NetScene 接口
            WeLogger.w(TAG, "methodVerifyAccept not available, using fallback accept")
            fallbackAccept(encryptUsername, ticket, scene)
        }
    }

    // ==================== rcontact 表监听 — 捕获免验证被添加 ====================

    private val mainHandler = Handler(Looper.getMainLooper())
    private val processedWxids = mutableSetOf<String>()
    private val pollerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastProcessedRowid = -1L
    private var lastMsgRowid = -1L

    /** 诊断：打印 rcontact 表真实列名 */
    private fun probeRcontact() {
        runCatching {
            WeDatabaseApi.rawQuery("SELECT * FROM rcontact LIMIT 1").use { cursor ->
                WeLogger.i(TAG, "[probe] columns=" + cursor.columnNames.joinToString(","))
            }
        }.onFailure { WeLogger.e(TAG, "[probe] failed: " + (it.message ?: it.toString())) }
    }

    /** 延迟轮询 rcontact：等微信数据库就绪后每 2s 查一次最新插入行 */
    private fun startRcontactPoller() {
        pollerScope.launch {
            var waited = 0L
            while (!WeDatabaseApi.isReady && waited < 60_000L) {
                delay(500)
                waited += 500
            }
            if (!WeDatabaseApi.isReady) {
                WeLogger.w(TAG, "[poller] db not ready after 60s, abort")
                return@launch
            }
            WeLogger.i(TAG, "[poller] started, db ready")
            probeRcontact()
            while (isActive) {
                delay(2000)
                if (masterEnabled) {
                    handleRcontactInsert()
                    handleMessageInsert()
                }
            }
        }
    }

    /** 查询 rcontact 最新插入行，按 5s 时间窗口过滤历史数据 */
    private fun handleRcontactInsert() {
        runCatching {
            if (lastProcessedRowid < 0) {
                // 首次：记录当前最大 rowid，跳过存量联系人
                WeDatabaseApi.rawQuery("SELECT MAX(rowid) FROM rcontact").use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) lastProcessedRowid = cursor.getLong(0)
                }
                WeLogger.i(TAG, "[rcontact] baseline rowid=$lastProcessedRowid")
                return
            }
            WeDatabaseApi.rawQuery(
                "SELECT rowid, username, type, ticket, nickname, encryptUsername FROM rcontact WHERE rowid > " +
                    lastProcessedRowid + " ORDER BY rowid ASC LIMIT 20"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val rowid = cursor.getLong(0)
                    val wxId = cursor.getString(1) ?: continue
                    val type = cursor.getInt(2)
                    val ticket = cursor.getString(3)
                    val nick = cursor.getString(4)
                    val encUsername = cursor.getString(5)
                    WeLogger.i(TAG, "[rcontactRow] rowid=$rowid wx=$wxId type=$type ticket=$ticket nick=$nick enc=$encUsername")
                    if (rowid > lastProcessedRowid) lastProcessedRowid = rowid
                    if (wxId in processedWxids) continue
                    when (type) {
                        2 -> {
                            WeLogger.i(TAG, "[autoAccept] detect friend apply wx=$wxId")
                            if (ticket.isNullOrEmpty()) continue
                            processedWxids.add(wxId)
                            val delayMs = if (delayMode == DelayMode.RANDOM.value) {
                                val min = randomDelayMinMs.coerceAtLeast(0).toLong()
                                val max = randomDelayMaxMs.coerceAtLeast(0).toLong().coerceAtLeast(min)
                                Random.nextLong(min, max + 1)
                            } else fixedDelayMs.coerceAtLeast(0).toLong()
                            val target = if (encUsername.isNullOrEmpty()) wxId else encUsername
                            mainHandler.postDelayed({
                                runCatching {
                                    acceptFriendRequest(target, ticket, "")
                                    WeLogger.i(TAG, "[acceptFriendRequest] OK wx=$wxId")
                                }.onFailure { WeLogger.e(TAG, "[acceptFriendRequest] FAIL wx=$wxId", it) }
                            }, delayMs)
                        }
                        1 -> {
                            WeLogger.i(TAG, "[autoAccept] NO-VERIFY someone add me wx=$wxId")
                            processedWxids.add(wxId)
                        }
                        else -> WeLogger.i(TAG, "[rcontactRow] type=$type ignored")
                    }
                }
            }
        }.onFailure { WeLogger.e(TAG, "[rcontactObs error] " + (it.message ?: it.toString())) }
    }

    /** 轮询 message 表：捕获 type=37 好友验证消息（自动同意触发） */
    private fun handleMessageInsert() {
        runCatching {
            if (lastMsgRowid < 0) {
                WeDatabaseApi.rawQuery("SELECT MAX(rowid) FROM message").use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) lastMsgRowid = cursor.getLong(0)
                }
                WeLogger.i(TAG, "[msg] baseline rowid=$lastMsgRowid")
                return
            }
            WeDatabaseApi.rawQuery(
                "SELECT rowid, content FROM message WHERE rowid > " + lastMsgRowid +
                    " AND type=37 ORDER BY rowid ASC LIMIT 20"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val rowid = cursor.getLong(0)
                    val content = cursor.getString(1)
                    if (rowid > lastMsgRowid) lastMsgRowid = rowid
                    if (content.isNullOrEmpty()) continue
                    WeLogger.i(TAG, "[msgRow] rowid=$rowid len=${content.length}")
                    handleFriendVerifyContent(content)
                }
            }
        }.onFailure { WeLogger.e(TAG, "[msgObs error] " + (it.message ?: it.toString())) }
    }

    private fun fallbackAccept(encryptUsername: String, ticket: String, scene: String) {
        // 回退方案：使用 WeChat 的 AddContact 或 VerifyUser 相关的 NetScene
        // 由于无法确定具体的 DexKit 签名，这里记录日志供用户参考
        WeLogger.w(TAG, "fallback accept not fully implemented, " +
                "request: encryptUsername=$encryptUsername, ticket=$ticket, scene=$scene")
        // 尝试通过 WeChat 的数据库操作来标记好友请求为已处理
        // 实际的自动接受功能需要 DexKit 成功解析 WeChat 的验证方法
    }

    private fun findNewFriendWxId(encryptUsername: String): String {
        // 尝试通过 encryptUsername 查找新好友的 wxId
        // 在 WeChat 中，好友请求被接受后，encryptUsername 会对应到实际 wxId
        return runCatching {
            WeDatabaseApi.rawQuery(
                "SELECT username FROM rcontact WHERE encryptUsername=?",
                arrayOf(encryptUsername)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) ?: "" else ""
            }
        }.getOrDefault("")
    }

    private fun extractWxIdFromVerifyContent(verifyContent: String): String {
        // 从 verifyContent 中提取 wxId
        // 格式通常是 "v2_encryptUsername@ticket@scene"
        return runCatching {
            val parts = verifyContent.split("@")
            if (parts.size >= 2) {
                val encryptUsername = parts[0].removePrefix("v2_")
                findNewFriendWxId(encryptUsername)
            } else ""
        }.getOrDefault("")
    }

    private fun extractXmlValue(xml: String, tag: String): String? {
        val regex = Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
        return regex.find(xml)?.groupValues?.getOrNull(1)?.trim()
    }

    /** 微信 8.0.77 验证消息 XML 中 encryptusername/ticket 等位于 <msg> 属性上，需属性提取 */
    private fun extractAttr(xml: String, name: String): String? {
        val regex = Regex("""$name\s*=\s*"([^"]*)"""")
        return regex.find(xml)?.groupValues?.getOrNull(1)
    }

    // ==================== UI ====================

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var localMasterEnabled by remember { mutableStateOf(masterEnabled) }
            var localDelayMode by remember { mutableStateOf(delayMode) }
            var localFixedDelayMs by remember { mutableStateOf(fixedDelayMs) }
            var localRandomMinMs by remember { mutableStateOf(randomDelayMinMs) }
            var localRandomMaxMs by remember { mutableStateOf(randomDelayMaxMs) }
            var localWelcomeText by remember { mutableStateOf(welcomeText) }
            var localSendWelcome by remember { mutableStateOf(sendWelcome) }
            var localBlacklistJson by remember { mutableStateOf(blacklistJson) }
            var localBlacklist by remember { mutableStateOf(getBlacklist().joinToString("\n")) }

            AlertDialogContent(
                title = { Text("自动同意好友申请") },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        // 总开关
                        ListItem(
                            modifier = Modifier.clickable { localMasterEnabled = !localMasterEnabled },
                            trailingContent = {
                                Switch(checked = localMasterEnabled, onCheckedChange = null)
                            },
                            headlineContent = { Text("启用自动同意", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("开启后自动同意收到的所有好友申请") }
                        )

                        if (localMasterEnabled) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // 延迟设置
                            Text("延迟设置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

                            DelayMode.values().forEach { mode ->
                                ListItem(
                                    modifier = Modifier.clickable { localDelayMode = mode.value },
                                    trailingContent = {
                                        Text(if (localDelayMode == mode.value) "✓" else "")
                                    },
                                    headlineContent = { Text(mode.description) }
                                )
                            }

                            when (localDelayMode) {
                                DelayMode.FIXED.value -> {
                                    val presets = listOf(0 to "立即", 500 to "0.5秒", 1000 to "1秒", 2000 to "2秒", 3000 to "3秒", 5000 to "5秒")
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        presets.forEach { (ms, label) ->
                                            FilterChip(
                                                selected = localFixedDelayMs == ms,
                                                onClick = { localFixedDelayMs = ms },
                                                label = { Text(label) }
                                            )
                                        }
                                    }
                                    OutlinedTextField(
                                        value = localFixedDelayMs.toString(),
                                        onValueChange = { v ->
                                            v.toIntOrNull()?.let { localFixedDelayMs = it.coerceIn(0, 60000) }
                                        },
                                        label = { Text("自定义延迟（毫秒）") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                    )
                                }
                                DelayMode.RANDOM.value -> {
                                    OutlinedTextField(
                                        value = localRandomMinMs.toString(),
                                        onValueChange = { v ->
                                            v.toIntOrNull()?.let { localRandomMinMs = it.coerceIn(0, 60000) }
                                        },
                                        label = { Text("最小延迟（毫秒）") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                    )
                                    Spacer(Modifier.padding(top = 4.dp))
                                    OutlinedTextField(
                                        value = localRandomMaxMs.toString(),
                                        onValueChange = { v ->
                                            v.toIntOrNull()?.let { localRandomMaxMs = it.coerceIn(0, 60000) }
                                        },
                                        label = { Text("最大延迟（毫秒）") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                    )
                                }
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // 欢迎语
                            ListItem(
                                modifier = Modifier.clickable { localSendWelcome = !localSendWelcome },
                                trailingContent = {
                                    Switch(checked = localSendWelcome, onCheckedChange = null)
                                },
                                headlineContent = { Text("自动发送欢迎语") },
                                supportingContent = { Text("通过好友申请后自动发送一条消息") }
                            )

                            if (localSendWelcome) {
                                OutlinedTextField(
                                    value = localWelcomeText,
                                    onValueChange = { localWelcomeText = it },
                                    label = { Text("欢迎语内容") },
                                    minLines = 2,
                                    maxLines = 4,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // 黑名单
                            Text("黑名单管理", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "黑名单中的用户不会自动同意（每行一个 wxId）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            OutlinedTextField(
                                value = localBlacklist,
                                onValueChange = { localBlacklist = it },
                                label = { Text("黑名单 wxId") },
                                minLines = 3,
                                maxLines = 8,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        val newBlacklist = localBlacklist.lines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .toSet()

                        masterEnabled = localMasterEnabled
                        delayMode = localDelayMode
                        fixedDelayMs = localFixedDelayMs
                        randomDelayMinMs = localRandomMinMs
                        randomDelayMaxMs = localRandomMaxMs
                        welcomeText = localWelcomeText
                        sendWelcome = localSendWelcome
                        blacklistJson = json.encodeToString(newBlacklist)
                        showToast("设置已保存")
                        onDismiss()
                    }) { Text("保存") }
                }
            )
        }
    }
}