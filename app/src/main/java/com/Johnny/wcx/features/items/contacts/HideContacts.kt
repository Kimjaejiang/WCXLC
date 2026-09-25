package com.Johnny.wcx.features.items.contacts

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.chatting.ChattingUI
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.ui.WeChatInputBarApi
import com.Johnny.wcx.features.api.ui.WeMainActivityBeautifyApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.items.contacts.HideContacts.hookNewMessageNotification
import com.Johnny.wcx.features.items.contacts.HideContacts.methodAddressMvvmListPreprocessList
import com.Johnny.wcx.features.items.contacts.HideContacts.methodFtsSearchChatroomMemberTask
import com.Johnny.wcx.features.items.contacts.HideContacts.methodMultiTalkOnInvite
import com.Johnny.wcx.features.items.contacts.HideContacts.methodVoipShowFloatingCard
import com.Johnny.wcx.features.items.contacts.HideContacts.temporarilyShown
import com.Johnny.wcx.features.items.secret_friend.HideConversations
import com.Johnny.wcx.features.items.secret_friend.SecretFriendManager
import com.Johnny.wcx.features.items.secret_friend.SecretFriendState
import com.Johnny.wcx.features.items.contacts.hidecontacts.installListHooks
import com.Johnny.wcx.features.items.contacts.hidecontacts.installMomentsHooks
import com.Johnny.wcx.features.items.contacts.hidecontacts.installSchedules
import com.Johnny.wcx.features.items.contacts.hidecontacts.installSearchHooks
import com.Johnny.wcx.features.items.contacts.hidecontacts.installSqlHooks
import com.Johnny.wcx.features.items.contacts.hidecontacts.installVoipHooks
import com.Johnny.wcx.features.items.contacts.hidecontacts.rewriteMomentsFeedSql
import com.Johnny.wcx.features.items.contacts.hidecontacts.uninstallSchedules
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.getSystemService
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.now
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.MatchType
import java.lang.ref.WeakReference
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import java.lang.reflect.Modifier as JavaModifier


@Feature(
    name = "隐藏联系人", categories = ["密友功能"], description =
//        """隐藏指定的联系人
//隐藏位置:
//1. 首页对话列表
//2. 通讯录内联系人&群聊列表
//3. 首页搜索界面
//4. 锁屏自动关闭聊天界面
//5. 摇一摇设备关闭聊天界面
//6. 朋友圈信息流
//7. 联系人选择页面
//8. 音视频通话与群通话 (来电横幅、铃声、通知、通话记录)
//9. 通讯录内新的朋友 (列表、头像、红点)
//10. 桌面角标与底栏未读计数
//11. 朋友圈消息列表 (点赞与评论)
//12. 共同好友朋友圈动态下的内联点赞/评论 (非 SnsComment 表, 随动态本身下发)
//13. 发现页「N 位朋友的新动态」头像与红点
//14. 新消息通知 (含微信在 push 进程内直接弹出的轻量推送通知)
//15. 群聊内 @成员选择器
//16. 群成员列表 (查看全部群成员、删除成员、添加管理员、转让群主、群成员记录)
//17. 收藏列表
//18. 视频号点赞列表 (朋友❤过)
//19. 全局搜索 (联系人、聊天记录、群成员、共同群聊、服务通知、小商店、AI 对话)
//20. 通讯录底部「N 位联系人」与「N 个群聊」计数
//21. 拍一拍消息
//22. 微信运动排行榜
//另可配置「定时显示/隐藏」: 按每周重复或单次的时间自动切换临时显示状态, 只改显示, 不改动隐藏列表
//注 1: 临时显示 (#show / 三击标题 / 定时任务) 只恢复界面上的显示, 不恢复通知
//注 2: 除拍一拍外, 以上均为「不显示」而非「删除」, 取消隐藏后内容会原样回来
//注 3: 拍一拍是唯一的破坏性隐藏 — 消息在写入数据库前就被取消, 取消隐藏也无法找回;
//      是否被抑制取决于消息到达那一刻的临时显示状态
//
//【与密友功能的关系】本功能与「密友功能」共用同一份名单（见 hiddenContacts 的 KDoc），
// 归入同一分类。两者的分工：密友侧提供名单管理、身份伪装、锁屏/离开自动恢复等通用能力；
// 本功能提供上列 22 个隐藏面中密友侧未覆盖的部分（通话、摇一摇、角标计数、拍一拍、
// 收藏、视频号点赞、群成员列表、微信运动等），这些实现全部保留，未做删减。"""
"隐藏指定的联系人（与密友名单共用，提供通话/拍一拍/角标计数等隐藏面）"
)
object HideContacts : ClickableFeature(), IResolveDex, WeChatInputBarApi.IInputBarListener,
    WeDatabaseListenerApi.IQueryListener {

    private const val TAG = "HideContacts"

    /**
     * 主控 [com.Johnny.wcx.features.items.secret_friend.SecretFriendManager] 的开关存储键
     * ——即它的 `@Feature(name)`，`SwitchFeature` 用 `name` 作 pref 键，其 `defaultEnabled = true`。
     */
    private const val MASTER_FEATURE_NAME = "密友名单管理"

    /**
     * 启用状态跟随密友主控 [com.Johnny.wcx.features.items.secret_friend.SecretFriendManager]。
     *
     * ## 为什么不直接返回 false
     *
     * 曾经的写法是 `shouldEnableOnStartup = false`，指望主控在 `onEnable` 里把它推成 true。
     * **那是错的**，两端会同时失效：
     * 1. [SwitchFeature.startup] 已把 `_isEnabled` 读成 pref 值（历史/默认均为 true），
     *    而 `isEnabled` 的 setter 有 `if (_isEnabled == value) return` 短路——
     *    主控再赋 true 时值相同，**不会**触发 `enable()`，`onEnable()` 永不执行；
     * 2. 于是本类安装的全部 hook（**命令解析、长按手势、SQL 过滤**）根本没装上，
     *    表现为「长按 / 点按 / 命令」三个解锁入口同时失效。
     *
     * ## 为什么读 pref 而不是读 SecretFriendManager.isEnabled
     *
     * 特性加载顺序不保证本类晚于主控，读对方的 `isEnabled` 可能拿到尚未初始化的 false。
     * 直接读**同一个存储键**（主控的 pref 键 = 它的 `@Feature(name)`，兜底 defaultEnabled=true），
     * 结果与主控一致且与加载顺序无关。
     *
     * 运行期切换仍由 [com.Johnny.wcx.features.items.secret_friend.SecretFriendManager] 的
     * onEnable/onDisable 驱动 isEnabled。
     */
    override val shouldEnableOnStartup: Boolean
        get() = WePrefs.getBoolOrDef(MASTER_FEATURE_NAME, true)

    // 名单存储键已移至 SecretFriendState.KEY_LEGACY_HIDDEN_CONTACTS（"hidden_contacts"）。
    // 该键现在只在首次读取时用于一次性迁移进 maskList，不再是数据源；保留旧键以便回滚。

    // One-time flag: older versions hid chats by writing parentRef='hidden_conv_parent'. Once we've
    // cleared that stale marker for the current hidden set (so #show / un-hide work again), we never
    // need to re-check. New hides rely purely on the query-time filter and never set the marker.
    private const val KEY_LEGACY_MIGRATED = "hidden_parentref_migrated"

    /**
     * 隐藏名单。**已与「密友功能」合并**，本属性只是密友 [SecretFriendState] 名单的视图：
     * 读写都转发过去，不再有自己的存储。
     *
     * 合并原因：两者语义本就是包含关系——本类原有的 setter 已经会对新隐藏的联系人调
     * [WeConversationApi.setDnd]，那正是密友 `MaskItem.tipMode` 的语义；密友侧还额外
     * 支持身份伪装与「时间戳式」临时显示（可自动到期，且跨进程可读）。保留两套只会
     * 让同一份意图在两条名单里各存一半（即「两个列表不同步」）。
     *
     * 旧键 `hidden_contacts` 的数据由 [SecretFriendState.getMaskItems] 在首次读取时
     * 自动并入 maskList，旧键本身保留不删，便于回滚排查。
     *
     * 一个有意保留的语义差别：本属性用 [SecretFriendState.getStoredWxIds]（**不受**密友
     * 主控开关「密友名单管理」约束），而不是密友各隐藏开关用的 [SecretFriendState.getWxIds]。
     * 原因是本功能有自己的开关，用户关掉密友主控、但没关「隐藏联系人」时，
     * 隐藏联系人理应继续按名单生效；若跟随主控，就会出现「关了一个开关，另一个也跟着失效」。
     * 反过来，要停用隐藏联系人就用它自己的开关。这一点在合并后需要保持，不要「顺手对齐」。
     */
    @Deprecated("已合并进密友名单，请直接用 SecretFriendState", ReplaceWith("SecretFriendState"))
    var hiddenContacts
        get() = SecretFriendState.getStoredWxIds()
        set(value) {
            // Muting is a server-synced oplog (OpenImOpLogLogic), so only send it for contacts that
            // were just added — the previous version re-sent it for the entire set on every save.
            // NB: un-hiding deliberately does NOT restore the prior mute state; doing so would
            // overwrite a mute the user set themselves. See the design doc.
            val newlyHidden = value - SecretFriendState.getStoredWxIds()
            SecretFriendState.setWxIds(value)
            for (convId in newlyHidden) {
                WeConversationApi.setDnd(convId, true)
            }
            // 名单变动后必须对账，否则「取消勾选」只改了名单、不会把已删除的会话行重建回来，
            // 主页上的那个人要等到他再发消息才重新出现。密友自己的写路径
            // (SecretFriendManager) 同样在 setWxIds 之后调用本方法。
            HideConversations.reconcileOnListChange()
        }

    private object ScreenOffReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return

            val chattingUi = chattingUi?.get() ?: return
            val wxId = chattingUi.intent.getStringExtra("Chat_User")
            if (temporarilyShown || wxId !in hiddenContacts) return

            exitToMainActivity()
        }
    }

    private var chattingUi: WeakReference<ChattingUI>? = null

    // Registered against the application context, exactly once. It used to be registered on the
    // LauncherUI Activity inside doOnCreate — so every Activity recreation added another
    // registration — while onDisable unregistered against the application context, a different
    // Context, which throws and was being swallowed. Net effect: the receiver outlived the feature
    // and kept kicking the user out of hidden chats on screen-off after it was turned off.
    private var screenOffReceiverRegistered = false

    private fun registerScreenOffReceiver() {
        if (screenOffReceiverRegistered) return
        // ACTION_USER_PRESENT used to be in this filter but onReceive never handled it.
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        HostInfo.application.registerReceiver(ScreenOffReceiver, filter)
        screenOffReceiverRegistered = true
        WeLogger.d(TAG, "registered screen off receiver")
    }

    private fun unregisterScreenOffReceiver() {
        if (!screenOffReceiverRegistered) return
        screenOffReceiverRegistered = false
        runCatching { HostInfo.application.unregisterReceiver(ScreenOffReceiver) }
            .onFailure { WeLogger.w(TAG, "failed to unregister screen off receiver", it) }
    }

    private object ShakeDetector : SensorEventListener {

        private var sensorManager: SensorManager? = null
        private var lastShakeTime: Long = 0
        private const val SHAKE_THRESHOLD = 4.5f // higher = harder shake required

        fun start(context: Context) {
            WeLogger.d(TAG, "starting shake detector")

            if (sensorManager != null) return

            sensorManager = context.getSystemService<SensorManager>()
            val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

            sensorManager?.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_UI
            )
        }

        fun stop() {
            WeLogger.d(TAG, "stopping shake detector")

            sensorManager?.unregisterListener(this)
            sensorManager = null
        }

        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return

            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val gForce = sqrt((x * x + y * y + z * z).toDouble()).toFloat() / SensorManager.GRAVITY_EARTH

            if (gForce > SHAKE_THRESHOLD) {
                val now = System.currentTimeMillis()
                if (lastShakeTime + 1000 > now) return // 1-second debounce
                lastShakeTime = now

                exitToMainActivity()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // unused
        }
    }

    private fun exitToMainActivity() {
        WeLogger.d(TAG, "leaving conversation page")
        val ctx = HostInfo.application
        val intent = Intent(ctx, LauncherUI::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        ctx.startActivity(intent)
    }

    override fun onEnable() {
        // 与主控状态对齐。旧 pref 键「隐藏联系人」可能残留 false（用户在本项被隐藏前进过设置关掉它），
        // 而 startup() 已据此把 _isEnabled 置为 false；shouldEnableOnStartup 走的是主控 pref（true），
        // 于是会出现「_isEnabled=false 但 hook 已装」的矛盾状态——界面显示关、功能却生效，
        // 且用户下次点开关会走向 disable（而它本来就没启用）。这里一次性纠正。
        if (!_isEnabled) _isEnabled = true

        // --- home screen conversation list ---

        // Hide at query time: inject `username NOT IN (...)` into WeChat's list queries so hidden
        // contacts are filtered on every full read. Covers the homepage conversation list, the
        // contact selector / 群聊 / 标签 / 公众号 lists, and global search.
        installSqlHooks()

        // Block the per-row live-update notification that WeChat fires (type 3) when a new
        // message arrives. Without this the native ConversationStorage dispatcher pushes the
        // hidden contact's row directly to the list adapter — bypassing the SQL hook above —
        // and the contact reappears until the next full query. Cancelling the notification at
        // source means the adapter never sees the row, so there is no flash at all.
        hookNewMessageNotification()

        // Drop 拍一拍 messages sent by a hidden contact before they become a row.
        hookPatMessage()

        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            migrateLegacyHiddenParentRef()

            val context = thisObject!!.reflekt()
                .firstField { type { it isSubclassOf Activity::class } }
                .get()!! as Activity

            registerScreenOffReceiver()

            // 标题手势（「多击标题解除」/「长按标题解除」）由各自的开关负责装配，
            // 本功能不再参与——原「三击标题」与前者重复，已删除。
        }

        // --- shake to leave ---

        ChattingUI::class.reflekt().apply {
            firstMethod { name = "onResume" }.hookAfter {
                val activity = thisObject as ChattingUI

                chattingUi = WeakReference(activity)

                val wxId = activity.intent.getStringExtra("Chat_User")
                if (temporarilyShown || wxId !in hiddenContacts) return@hookAfter

                ShakeDetector.start(activity)
            }

            firstMethod { name = "onPause" }.hookAfter {
                chattingUi?.clear()
                chattingUi = null
                ShakeDetector.stop()
            }
        }

        // --- adapter/list-level surfaces (通讯录, @成员选择器, 群成员列表, 收藏, 视频号点赞) ---
        //
        // Everything whose rows never pass through a query the SQL rewriter above can see. See
        // hidecontacts/HideContactsLists.kt.

        installListHooks()

        // --- global search results the SQL rewriter cannot reach ---
        //
        // 群聊内搜索成员 and 共同群聊好友建议. See hidecontacts/HideContactsSearch.kt.

        installSearchHooks()

        // NB: the 通讯录 -> 群聊 list (ChatroomContactAdapter) is NOT hooked at the adapter level.
        // Its cursor comes from ContactStorage.y(), whose SQL carries `from rcontact` + `pyInitial`
        // and is therefore already filtered by rewriteContactSelectorSql at the SQLite wrapper.
        //
        // A previous implementation additionally shifted adapter positions via a `hiddenPositions`
        // set. That was dead code (the set was always empty), and it was wrong in three ways: it
        // folded an ascending-only shift over an unordered MutableIntSet, it remapped getView but
        // not getItem/getItemId (so ChatroomContactUI's click listener would open the WRONG chat),
        // and it ignored temporarilyShown. Deleting it means a resolve failure degrades to "hidden
        // contact stays visible" instead of "tapping a row opens someone else's chat".

        // --- voip ---

        installVoipHooks()

        // --- moments inline likes/comments (mutual-friend posts) ---

        installMomentsHooks()

        // --- command ---

        WeChatInputBarApi.addListener(this)

        // --- moments feed ---

        WeDatabaseListenerApi.addListener(this)

        // --- notification ---
        //
        // Deliberately NOT installed here. WeChat raises new-message notifications from
        // CoreService, which the host manifest pins to :push — a process this feature never loads
        // in — so a dealNotify hook registered from here would never fire where it matters. Both
        // notification hooks now live in HideContactsNotifications, which loads in main + push and
        // reads this feature's persisted state out of WePrefs.

        // --- 定时显示/隐藏 ---
        //
        // Arms one AlarmManager alarm per enabled entry and applies whichever fire time most recently
        // passed while the process was down. See hidecontacts/HideContactsSchedule.kt — the catch-up
        // deliberately touches nothing but temporarilyShown, since no Activity exists at this point.

        installSchedules()

        WeConversationApi.reloadConversations()
    }

    override fun onDisable() {
        uninstallSchedules()
        unregisterScreenOffReceiver()
        ShakeDetector.stop()
        chattingUi?.clear()
        chattingUi = null
        WeChatInputBarApi.removeListener(this)
        WeDatabaseListenerApi.removeListener(this)
        // 不调用 SecretFriendTitleGesture.reset()：本功能已不注册任何手势源，
        // reset 会连带拆掉密友「多击标题解除」的监听器——关一个功能不该让另一个失效。
        // 各源在各自 onDisable 里 unregister，分发点自行收敛。
        // 关闭功能时**只能**清临时显示标记，绝不能走 [SecretFriendState.tempOff]：
        // tempOff 会执行 HideConversations.removeSecretRows()（逐条删除主页会话行），
        // 那是「隐藏」语义。用户关掉本功能的本意是「不要隐藏了」，此时再删一遍会话行
        // 会让主页直接少掉这些聊天（记录还在，但要等对方再发消息才重新出现）——
        // 功能关闭反而不比开着更容易理解。故此处用 clearTemporarilyShown()。
        SecretFriendState.clearTemporarilyShown()
        WeConversationApi.reloadConversations()
    }

    /**
     * Toggles the temporary-show state. Mirrors the `#show` / `#hide` input-bar commands for
     * use by gesture-based triggers (e.g. triple-clicking the main-screen title).
     *
     * 不在此处自行 `reloadConversations()`：临时显示的开关两个方向都涉及会话行的重建/删除，
     * 那两步已由 [SecretFriendState] 的对应方法内部完成（且必须先于刷新发生），
     * 在这里再刷一次只会用到尚未修正的行状态。
     */
    internal fun toggleTemporarilyShown(context: Context) {
        if (temporarilyShown) {
            temporarilyShown = false
            showToast(context, "已恢复隐藏联系人")
        } else {
            temporarilyShown = true
            showToast(context, "已临时显示所有隐藏的联系人")
        }
    }

    /**
     * Writes the temporary-show state without any UI, for non-interactive callers — currently the
     * 定时显示/隐藏 scheduler, whose startup catch-up runs at process attach where no Activity (and
     * therefore no Toast) exists.
     *
     * 注意这里**不能**用 `if (temporarilyShown == shown) return` 提前返回：该读取的是
     * 「当前时间 < 到期时间戳」，定时器补跑时时间戳往往已经过期，于是 `shown=false` 会命中
     * 这个守卫而整体跳过 —— 时间戳不归零、临时显示期间产生的会话行也清不掉。
     * 交给 setter 与 [SecretFriendState.tempOff] 无条件收敛即可（幂等）。
     */
    internal fun setTemporarilyShown(shown: Boolean) {
        temporarilyShown = shown
    }

    override fun onTextChanged(chatFooter: ChatFooter, text: String) {
        when (text) {
            "#show" -> {
                chatFooter.lastText = ""
                if (temporarilyShown) {
                    showToast(chatFooter.context, "已经是临时显示状态")
                    return
                }
                temporarilyShown = true
                showToast(chatFooter.context, "已临时显示所有隐藏的联系人, 输入 #hide 恢复隐藏")
            }

            "#hide" -> {
                chatFooter.lastText = ""
                // 不按 temporarilyShown 提前返回：到期后它已是 false，但那正是需要执行
                // tempOff 收敛（清零时间戳 + 清理临时显示期间新产生的行）的时刻。
                temporarilyShown = false
                showToast(chatFooter.context, "已恢复隐藏所有联系人")
            }
        }
    }

    override fun onQuery(sql: String): String? = rewriteMomentsFeedSql(sql)

    // The parentRef marker older versions wrote via WeConversationApi.setConversationsVisibility to
    // hide a chat. WeChat's native list filter (m4.O) hides rows whose parentRef isn't null/empty.
    private const val LEGACY_HIDDEN_PARENT_REF = "hidden_conv_parent"

    // One-time cleanup for users upgrading from the parentRef-based hiding: clear the stale marker
    // for our currently-hidden chats. Without this, WeChat's own filter keeps hiding a chat (until
    // its next message resets parentRef) even after the user un-hides it, since un-hiding only drops
    // it from our set and never touched parentRef. Scoped to our hidden set so we don't disturb rows
    // hidden by 显隐全部对话 (ToggleAllConversationsVisibility), which shares the same marker.
    private fun migrateLegacyHiddenParentRef() {
        if (WePrefs.getBoolOrFalse(KEY_LEGACY_MIGRATED)) return

        val hidden = hiddenContacts
        if (hidden.isEmpty()) {
            WePrefs.putBool(KEY_LEGACY_MIGRATED, true)
            return
        }

        // DB not ready yet: leave the flag unset so we retry on the next launch.
        if (!WeDatabaseApi.isReady) return

        try {
            val inClause = hidden.joinToString(",") { "'${it.replace("'", "''")}'" }
            WeDatabaseApi.execStatement(
                "UPDATE rconversation SET parentRef = '' " +
                        "WHERE parentRef = '$LEGACY_HIDDEN_PARENT_REF' " +
                        "AND username IN ($inClause)"
            )
            WePrefs.putBool(KEY_LEGACY_MIGRATED, true)
            WeLogger.d(TAG, "cleared legacy hidden parentRef markers for ${hidden.size} chats")
        } catch (ex: Exception) {
            WeLogger.w(TAG, "failed to clear legacy hidden parentRef markers", ex)
        }
    }

    /**
     * 临时显示标记。**已与密友合并**，转发到 [SecretFriendState] 的时间戳语义。
     *
     * 旧实现是主进程内的 `var temporarilyShown = false`，有两个实际缺陷：
     * 1. 进程重启即丢失——临时显示中杀进程再回来，隐藏会「自己恢复」，用户无从预期；
     * 2. 跨进程不可见——通知进程读不到它（见 HideContactsNotifications 的 KDoc 说明），
     *    于是「临时显示期间仍按隐藏处理」，通知被错误抑制。
     * 密友用 prefs 里的到期时间戳，两个问题一并解决，还额外获得「到期自动恢复」。
     *
     * 读取：纯查时间戳，不触发任何副作用（hook 路径上会被高频调用）。
     * 写入：转调 [SecretFriendState] 的完整语义——true 走 [SecretFriendState.tempShowForMinutes]
     * 以保证「为隐藏而删掉的会话行被重建」，false 走 [SecretFriendState.tempOff] 以保证
     * 「临时显示期间新产生的行被清掉」。只改标记而不做这两步，界面会看起来没反应。
     */
    private var temporarilyShown: Boolean
        get() = SecretFriendState.isTemporarilyShown()
        set(value) {
            val was = SecretFriendState.isTemporarilyShown()
            if (value == was) return
            if (value) {
                // 走「长期」而非默认 30 分钟：本属性的调用方有两类，
                // - 交互式 (#show / 三击标题)：用户随后会手动 #hide；
                // - 定时任务 (HideContactsSchedule)：语义是「显示到下次定时触发」。
                // 两者都不是「30 分钟后自动收回」。若这里用 tempShowForMinutes，
                // 定时显示会在半小时后被到期定时器悄悄撤回，与用户配置的
                // 「08:00 显示 / 20:00 隐藏」不符。
                SecretFriendState.tempShowUntil(SecretFriendState.TEMP_SHOW_FOREVER)
            } else {
                SecretFriendState.tempOff()
            }
        }

    /**
     * The predicate every hook should use: a contact counts as hidden only while the temporary-show
     * escape hatch (`#show` / triple-tap title) is off.
     */
    internal fun isHiddenNow(wxId: String): Boolean = !temporarilyShown && wxId in hiddenContacts

    /** For SQL rewriters, which bail wholesale rather than testing individual wxids. */
    internal val isTemporarilyShown: Boolean get() = temporarilyShown

    internal val autoRejectVoipEnabled: Boolean get() = autoRejectVoip

    /** 自动拒接开关，键与 [com.Johnny.wcx.features.items.secret_friend.AutoRejectVoip] 共用。 */
    private var autoRejectVoip by prefOption("hide_auto_reject", false)

    // 「三击标题切换显隐」（hide_triple_click_title）已删除：与密友「多击标题解除」是同一
    // 手势，且其触发次数可调，能力完全覆盖固定三击。旧 pref 键留存但不再读取。

    // Hooks the ConversationStorage notify dispatcher to cancel per-row update events (type 3)
    // for hidden contacts before they reach list adapters. WeChat fires b(3, storage, talker)
    // synchronously after every new message, pin, or unread-state change; without this hook the
    // adapter sees the row immediately — before any SQL query runs — so the contact reappears
    // regardless of the query-rewrite filter. Cancelling the notification at source is
    // race-free: the hidden contact never reaches the adapter at all.
    //
    // Event type 5 (global reload) is not suppressed — that is the path reloadConversations() uses to
    // trigger a full re-query (which our SQL hook then filters correctly). The empty-talker check
    // additionally guards the "" sentinel used by reloadConversations().
    /**
     * Re-entrancy guard for [hookNewMessageNotification].
     *
     * `markAsRead` mutates the conversation via `ConversationStorage.updateUnreadByTalker`, and that
     * mutation makes the storage fire *this very notification again* for the same talker. Without a
     * guard the hook body re-enters itself with every condition still satisfied, recursing until the
     * thread's 4 MB stack is exhausted — which killed WeChat on startup (SIGSEGV in the guard page,
     * surfacing inside xlog's printf because ART only inserts stack-overflow checks in Java frames,
     * not in JNI) whenever a hidden contact had unread messages waiting to sync.
     *
     * Thread-local because WeChat dispatches this notification synchronously on the calling thread.
     */
    private val markingAsRead = ThreadLocal.withInitial { false }

    private fun hookNewMessageNotification() {
        val method = WeConversationApi.methodNotifyConversationChanged
        if (method.isPlaceholder) {
            WeLogger.w(TAG, "conversation notify method not resolved; new-message suppression unavailable")
            return
        }

        method.hookBefore {
            val eventType = args[0] as? Int ?: return@hookBefore
            if (eventType != 3) return@hookBefore
            if (temporarilyShown) return@hookBefore
            val talker = args[2] as? String ?: return@hookBefore
            if (talker.isEmpty()) return@hookBefore
            if (talker !in hiddenContacts) return@hookBefore

            // Already inside our own markAsRead: this is the storage echoing our write back at us.
            // Still cancel the event so the row never reaches an adapter, but do not write again.
            if (markingAsRead.get() == true) {
                result = null
                return@hookBefore
            }

            markingAsRead.set(true)
            try {
                WeConversationApi.markAsRead(talker)
            } finally {
                markingAsRead.set(false)
            }
            result = null
        }
    }

    /**
     * Suppresses 拍一拍 ("… 拍了拍 …") from a hidden contact.
     *
     * `PatMsgExtension.insertPatMsg` is the single writer of the pat system message: it either
     * creates a new `922746929` row or merges the pat into the existing one, and in both branches it
     * is what refreshes the conversation's digest and bumps it to the top of the homepage list.
     * Cancelling the call therefore removes the message row *and* the list disturbance in one go —
     * neither the conversation-list rewriter nor the per-row notification suppressor can help here,
     * because the row's talker is the *chat*, not the patter.
     *
     * The substituted return value is the method's own no-op result (`Pair.create(0L, 0L)`, taken
     * from its `t8.N0(...)` guard at `nq3/l.java:568` / `ti3/l.java:266`), so every caller sees a
     * shape it already handles: msgId 0 means "nothing was inserted".
     *
     * Only `fromUser` is tested. `talker` is the conversation the pat lands in, and a hidden
     * *conversation* is already handled by the query-time filter; `pattedUser` is frequently the
     * local user, whom we must never treat as hidden.
     *
     * NB this is the **only destructive** surface in 隐藏联系人: everywhere else a hidden contact's
     * content is merely not displayed and comes back verbatim once it is un-hidden, but here the row
     * is never written in the first place, so neither 临时显示 nor removing the contact from the
     * hidden list can recover it. It also means whether a given pat survives depends on the
     * [temporarilyShown] state *at the instant the message arrived*, not at the instant it is read.
     * Documented in the `@Feature` blurb; changing it would require buffering the pats instead.
     */
    private fun hookPatMessage() {
        if (methodPatMsgInsert.isPlaceholder) {
            WeLogger.w(TAG, "pat-message insert wasn't resolved; 拍一拍 stays visible")
            return
        }

        methodPatMsgInsert.hookBefore {
            val fromUser = args[1] as? String ?: return@hookBefore
            if (!isHiddenNow(fromUser)) return@hookBefore

            WeLogger.d(TAG, "suppressed a pat message from a hidden contact")
            result = android.util.Pair.create(0L, 0L)
        }
    }

    /**
     * 「隐藏联系人」的点击入口。
     *
     * 原先这里承载四个子项（配置隐藏列表 / 自动拒接 / 定时显示隐藏 / 三击标题），
     * 现已按职责拆开：
     * - **配置隐藏列表** → 委托给 [SecretFriendManager] 的名单编辑。名单自合并起
     *   就与密友共用一份（见 [hiddenContacts]），这里再放一个并列的编辑器只会
     *   让用户以为存在两份名单；直接复用密友那一份，行为与它完全一致。
     * - **自动拒绝音视频通话** → [com.Johnny.wcx.features.items.secret_friend.AutoRejectVoip]
     *   （pref 键沿用 `hide_auto_reject`，用户设置不丢）。
     * - **定时显示/隐藏** → [com.Johnny.wcx.features.items.secret_friend.HideSchedules]。
     * - **三击标题切换显隐** → 删除。与密友「多击标题解除」是同一手势（点标题 N 次
     *   切换显隐），且两者现已共用 [SecretFriendTitleGesture] 分发点；
     *   「多击标题解除」的触发次数可调，能力完全覆盖固定三击。
     */
    override fun onClick(context: ComponentActivity) {
        SecretFriendManager.onClick(context)
    }

    //    private val methodMainAdapterPerformSearch by dexMethod()

    // WeChat's SQLite wrapper query: d95.b0.f(String sql, String[] args, int) -> Cursor. The
    // homepage conversation-list cursor (com.tencent.mm.storage.m4.A/B) is built through this
    // wrapper, NOT the standard SQLiteDatabase.rawQuery path WeDatabaseListenerApi hooks, so we
    // intercept it directly — the same chokepoint ConversationGrouping/AggregateChats use.
    internal val methodSqliteWrapperRawQuery by dexMethod(allowFailure = true) {
        matcher {
            modifiers = JavaModifier.PUBLIC
            usingEqStrings("sql is null ", "DB IS CLOSED ! {%s}")
            paramTypes("java.lang.String", "java.lang.String[]", "int")
            returnType("android.database.Cursor")
        }
    }
    /**
     * `AddressLiveList.e(List snapshotList)` — the 通讯录 MvvmList preprocessor.
     * See hidecontacts/HideContactsLists.kt for why this is the right cut point.
     */
    internal val methodAddressMvvmListPreprocessList by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.ui.contact.address.AddressLiveList"
            usingEqStrings("snapshotList")
        }
    }

    /**
     * `AtSomeoneLiveList.e(List snapshotList)` — the @成员选择器's MvvmList preprocessor, structurally
     * identical to [methodAddressMvvmListPreprocessList] and filtered by the same shared helper.
     *
     * `AtSomeoneLiveList` declares only `<init>`, `c()` (the log tag) and `e(List)`, and `"snapshotList"`
     * is the `o.g(...)` null-check literal that only `e` carries — unambiguous on 8.0.76
     * (`ui/chatting/atsomeone/AtSomeoneLiveList.java:35-36`) and on 8.0.69 (same file, same lines).
     *
     * `allowFailure` because this is a single opt-in surface: if the class is renamed on some version
     * in 8.0.65–8.0.76 we want the @成员 list to stay unfiltered, not to fail dex resolution for the
     * whole feature and take every other hidden-contact surface down with it.
     */
    internal val methodAtSomeoneMvvmListPreprocessList by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.ui.chatting.atsomeone.AtSomeoneLiveList"
            usingEqStrings("snapshotList")
        }
    }

    /**
     * `cc.d(List usernames)` — `SeeRoomMemberUI`'s adapter rebuild (`tc.d` on 8.0.69).
     *
     * Among the classes that carry the `"MicroMsg.SeeRoomMemberUI"` tag (the Activity itself plus its
     * adapter and three listeners) exactly one declares a `void (List)` method: the adapter, at
     * `chatroom/ui/cc.java:96` on 8.0.76 and `chatroom/ui/tc.java:95` on 8.0.69.
     */
    internal val methodSeeRoomMemberSetMemberList by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.SeeRoomMemberUI")
            }
            paramTypes("java.util.List")
            returnType("void")
        }
    }

    /**
     * `SelectMemberUI.V6()` — the member-username list the @全体成员 / 删除成员 / 邀请 adapters load
     * from (`j7()` on 8.0.69).
     *
     * The class name is real, and it declares exactly one no-argument `List`-returning method on both
     * trees (`chatroom/ui/SelectMemberUI.java:115` on 8.0.76, `:178` on 8.0.69), so class + arity +
     * return type is unambiguous without needing the obfuscated method name.
     */
    internal val methodSelectMemberUiGetMemberList by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.chatroom.ui.SelectMemberUI"
            paramCount = 0
            returnType("java.util.List")
        }
    }

    /**
     * `c.r(List)` — `FavoriteAdapter`'s single data-list setter (`c.t(List)` on 8.0.69).
     *
     * `"MicroMsg.FavoriteAdapter"` occurs in exactly one class app-wide
     * (`plugin/fav/ui/adapter/c.java` on both trees). That class declares two `void (List)` methods —
     * `d(List)` and `r(List)` — and only `r` uses the tag (in its catch block,
     * `c.java:1054` on 8.0.76 / `c.java:1051` on 8.0.69), so the tag + shape pair resolves to `r`
     * alone. `d(List)` uses no string constants at all.
     */
    internal val methodFavoriteAdapterSetDataList by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.FavoriteAdapter")
            paramTypes("java.util.List")
            returnType("void")
        }
    }

    /**
     * The 视频号 like-drawer *refresh* callback — `jd.call(Object)` on 8.0.76
     * (`plugin/finder/feed/jd.java:50`), `zc.call(Object)` on 8.0.69.
     *
     * Two methods on each tree pair `"Finder.DrawerPresenter"` with `"[refreshData] Cost="`
     * (8.0.76: `feed/jd` + `feed/s5`; 8.0.69: `feed/zc` + `feed/t5`) — the like drawer and the
     * comment drawer. They are told apart by the builder whose retry-view they drive: the like
     * drawer carries `"…/FinderLikeDrawerBuilder"`, the comment drawer
     * `"…/FinderTimelineDrawerBuilder"`. All three strings together resolve to exactly one method on
     * each tree. Strings are preferred over a structural discriminator here because `allowFailure`
     * only guards the 0-hit case — a multi-hit would `error(...)` and take the whole feature down.
     */
    internal val methodFinderLikeDrawerRefresh by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings(
                "Finder.DrawerPresenter",
                "[refreshData] Cost=",
                "com/tencent/mm/plugin/finder/view/builder/FinderLikeDrawerBuilder"
            )
        }
    }

    /**
     * The 视频号 like-drawer *load-more* callback — `yc.call(Object)` on 8.0.76
     * (`plugin/finder/feed/yc.java:31`), `sc.call(Object)` on 8.0.69.
     *
     * `"Finder.DrawerPresenter"` + `"[loadMoreData] empty!"` matches two methods per tree (8.0.76:
     * `feed/yc` + `feed/v3`; 8.0.69: `feed/sc` + `feed/x3`). Unlike the refresh callback there is no
     * third string to separate them — the competing method carries *only* those two constants — so
     * the discriminator has to be structural: the like-drawer one calls
     * `FinderItem.getUnsignedId()` to stamp each entry with its feed id, the other never does.
     * `FinderItem` is an unobfuscated (kept) class, so that method name is stable across versions.
     */
    internal val methodFinderLikeDrawerLoadMore by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("Finder.DrawerPresenter", "[loadMoreData] empty!")
            invokeMethods {
                add { name = "getUnsignedId" }
                matchType = MatchType.Contains
            }
        }
    }

    /**
     * SearchChatroomMemberTask 的任务体 — 群聊内搜索成员（搜索结果里「群名(N)包含:某人」）。
     *
     * ## 8.0.78 实测（`classes12.dex`）
     *
     * `"SearchChatroomMemberTask"` 全 dex 只出现一次，在 **`com.tencent.mm.plugin.fts.logic.s0`**
     * 的 `getName()` 里。旧注释写的 `fts.logic.q0` 在 8.0.78 只是个
     * `implements Comparator` 的成员排序器（`s0` 构造 `q0` 来给 `memberlist` 排序），
     * 挂在它上面等于完全没生效 —— 这正是「群里的人还能被搜到」的直接原因之一。
     *
     * ## 任务体形态
     *
     * 8.0.78 为 `public void r(u73.v vVar)`；`u73.v` 即 FTSResult 持有者。成员来源分两段：
     * ① 一条 `MATCH ... AND type = 131075 AND subtype = 38 AND aux_index = ?`（? = 群 id，即群成员卡片）；
     * ② 读 `chatroom.memberlist`（`;` 分隔文本列）后按 `;` 切分，逐个用 `y3.e0/f0` 解析出显示名。
     * 成员不在 SQL 行内，只能在任务体出口过滤 —— 见 HideContactsSearch.kt。
     */
    internal val methodFtsSearchChatroomMemberTask by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings("SearchChatroomMemberTask")
            }
            paramCount = 1
            returnType("void")
        }
    }

    /**
     * SearchCommonChatroomUserTask 的任务体 — 共同群聊好友建议（「群名包含:某人」那类结果行）。
     *
     * ## 8.0.78 实测（`classes12.dex`）
     *
     * `"SearchCommonChatroomUserTask"` 全 dex 只出现一次，在 **`com.tencent.mm.plugin.fts.logic.j`**
     * 的 `getName()` 里 —— 旧注释锚定的 `fts.logic.h` 在 8.0.78 已是
     * `BuildSingleChatroomMemberTask`（FTS 索引构建器），挂在它上面等于完全没生效。
     *
     * ## 为什么改挂「类 + 单参数返回 void」而不是继续写死任务体名
     *
     * 8.0.78 的任务体是 `public void r(u73.v vVar)`（`u73.v` 即 FTSResult 持有者），而旧注释写的是
     * `p(FTSResult)` —— 方法名在版本间会漂移（`q0`/`s0`/`j`/`h` 各自的任务体名并不统一）。
     * 因此这里定位「该类里唯一的 1 参数 void 方法」，不依赖具体方法名；
     * 该类除 `<init>(m, FTSRequest)`（2 参数）与 `getName()`（返回 String）外只有任务体一个候选。
     *
     * 任务体内 SQL（`SELECT content FROM <meta> NOT INDEXED JOIN <index> ... MATCH '...' AND
     * entity_id <= 50 ... LIMIT 10`）不在 [rewriteFtsSql] 的任何规则内，且成员名是从 `content`
     * 里按 `c.c` 正则切出来的，所以只能在任务体出口过滤 —— 见 HideContactsSearch.kt。
     */
    internal val methodFtsSearchCommonChatroomUserTask by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings("SearchCommonChatroomUserTask")
            }
            paramCount = 1
            returnType("void")
        }
    }

    /**
     * `ContactStorage.getNormalContactCount(boolean includeBlack, String[], String...) -> int`
     * (`j4.O` on 8.0.76 `storage/j4.java:460`, `l3.O` on 8.0.69 `storage/l3.java:434`) — the
     * 通讯录 「N 位联系人」 footer.
     *
     * The log format string is unique to this method on both trees. Its result is adjusted rather
     * than its SQL rewritten: the statement ends in a bare `or username = 'weixin'`
     * (`j4.java:487` / `l3.java:461`), so an appended `AND ...` would bind to that OR's right
     * operand and silently do nothing. See hidecontacts/HideContactsLists.kt.
     */
    internal val methodNormalContactCount by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings(
                "MicroMsg.ContactStorage",
                "getNormalContactCount, sql:%s, result:%d, includeBlack:%s, time:%d"
            )
        }
    }

    /**
     * `PatMsgExtension.insertPatMsg(String talker, String fromUser, String pattedUser, String
     * suffix, int createTime, long svrId) -> android.util.Pair` — 拍一拍
     * (`nq3/l.java:560` on 8.0.76, `ti3/l.java:260` on 8.0.69).
     *
     * `"insert pat msg %d %s %s"` appears in exactly one method per tree (`nq3/l.java:620`,
     * `ti3/l.java:320`); pairing it with the class tag keeps the match method-local.
     */
    internal val methodPatMsgInsert by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.PatMsgExtension", "insert pat msg %d %s %s")
        }
    }

    /**
     * `fb4.z0.D0(SnsInfo, SnsObject, Context, rs, boolean, d8, String, Map, Map, List)` —
     * `SnsUtil.snsInfoToSnsStruct`. Turns a post's raw `SnsObject` (attrBuf) into the UI-facing
     * struct for every Moments renderer. See hidecontacts/HideContactsMoments.kt for why this is
     * the right chokepoint for a hidden contact's inline likes/comments on someone else's post.
     */
    internal val methodSnsInfoToSnsStruct by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("snsInfoToSnsStruct", "com.tencent.mm.plugin.sns.data.SnsUtil", "mSnsInfo is null, why?")
        }
    }

    /**
     * `static void c3.H(c3 netSceneSnsSync, SnsObject snsObject)` (8.0.76; the same method is
     * `c3.I` on 8.0.69) — `NetSceneSnsSync`'s inlined `updateSyncDataCache`, the single writer of
     * the 发现 tab's "N 位朋友的新动态" state. See hidecontacts/HideContactsMoments.kt.
     *
     * Matched on the `SnsMethodCalculate.markStartTimeMs/markEndTimeMs` pair alone, deliberately.
     * The obvious extra anchors ("preRdUsername" / "isCoverPreRd" /
     * "updateSyncDataCache build previousRedDotInfo error") belong to a `previousRedDotInfo`
     * telemetry block that only exists from 8.0.76 onwards — including them would make this
     * delegate resolve to 0 hits on 8.0.65–8.0.75, and since it has no `allowFailure` that failure
     * marks the *whole* HideContacts feature as Failed, so `FeaturesLoader` would skip `startup()`
     * and every other hidden-contact surface would silently stop working.
     *
     * The narrower pair is still unambiguous: `"updateSyncDataCache"` occurs nowhere in the app
     * except inside this one method (8.0.76 `c3.java:106`/`:141`, 8.0.69 `c3.java:103`/`:111`), and
     * every user of it necessarily also carries the class-name constant from the same call. The
     * method shape — `static void (c3, SnsObject)` — is identical on both trees, so the hook body
     * needs no per-version branching.
     */
    internal val methodSnsSyncUpdateRedDotCache by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("updateSyncDataCache", "com.tencent.mm.plugin.sns.model.NetSceneSnsSync")
        }
    }

    // ── VoIPMP / ILink (the stack that actually runs on 8.0.7x) ──────────────────────────────
    // See hidecontacts/HideContactsVoip.kt for how these fit together.

    /** `ZIDL_ibmKH7hbMB.ZIDL_FBV(long, int, int, long, long, byte[] username, byte[][], boolean)` */
    internal val methodVoipMpLaunchIncomingCard by dexMethod(allowFailure = true) {
        matcher {
            // 8.0.76 changed from "launchInComingCardAsync: " to "[volume report] launchInComingCardAsync: "
            usingStrings("MicroMsg.VoIPMP.CoreV2", "launchInComingCardAsync: ")
        }
    }

    /**
     * `mp5.q2.qa(Context, int, is4.r, long, long, String username, ArrayList, boolean)` — the
     * banner/notification/ringtone dispatcher. q2 declares exactly one 8-parameter method, so the
     * class anchor plus the parameter count is unambiguous.
     */
    internal val methodVoipMpLaunchBanner by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.VoIPMP.Launcher", "closeReceiverBanner")
            }
            paramCount = 8
            returnType("void")
        }
    }

    /** `mp5.q2.Qa()` — "rejectByShortCut", the entry WeChat's own quick-reject uses. */
    internal val methodVoipMpReject by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.VoIPMP.CoreV2", "rejectByShortCut")
            paramCount = 0
            returnType("void")
        }
    }

    /**
     * `nq5.e.a(String username, boolean videoCall, boolean outCall, long, boolean)` — the incoming
     * ringtone. NB: this is NOT the old `MicroMsg.RingPlayer` / "playSound, type: ..." match, which
     * resolved to the call-ENDED tone and therefore never silenced anything.
     */
    internal val methodVoipMpStartRing by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.VoIPMPRingtoneController", "startRing() called with: username = ")
        }
    }

    /** `xp5.b.d(String username, boolean, boolean, boolean)` — starts the VoIP foreground service. */
    internal val methodVoipMpStartFgs by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.VoIPMPVoIPNotificationHelper", "startFGS isBindVoIPForegroundService ")
        }
    }

    /** `mp5.q2.Ii(String toUser, ...)` — VoIPMP call-record insertion (未接听 / 已取消 / duration). */
    // 8.0.76: ZIDL 与 mp5.q2 两条插入路径共存 → Multiple methods found, 取第一个(ZIDL, 与 8.0.77 同目标);
    // 8.0.77: 唯一匹配。hook 侧有参数结构防护, 版本不匹配时自动 no-op。
    internal val methodVoipMpInsertMsg by dexMethod(allowMultiple = true, allowFailure = true) {
        matcher {
            // 8.0.77: VoIPMP 通话记录插入移入 ZIDL 层, 不再打 Launcher tag;
            // toUser 是第 2 个参数 (UTF-8 字节数组)。
            usingEqStrings("insertMsg() called with: toUser = ")
        }
    }

    // ── multitalk (群通话), used when the VoIPMP multitalk experiment is off ───────────────────

    /** `v0.G(MultiTalkGroup)` — MultiTalkManager.onInviteMultiTalk. */
    internal val methodMultiTalkOnInvite by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings(
                "MicroMsg.MT.MultiTalkManager",
                "onInviteMultiTalk All Var Value:\n isMute: %b isHandsFree: %b isCameraFace: %b multiTalkStatus: %s groupIsNull: %b"
            )
        }
    }

    /**
     * `v0.g(isReject, isMissCall, isPhoneCall, isNetworkError, boolean, boolean)` —
     * exitCurrentMultiTalk. Declared on the same `v0` (MultiTalkManager) as [methodMultiTalkOnInvite],
     * so the invite hook's `thisObject` is the receiver to invoke this on — no separate singleton
     * lookup needed.
     *
     * NB: do NOT resolve a singleton getter by referencing `methodExitMultiTalk.method` from another
     * matcher block. With `allowFailure = true` a failed resolution leaves a placeholder, and reading
     * `.method` on a placeholder throws — which would take down dex resolution for the whole feature
     * on a cold cache.
     */
    internal val methodExitMultiTalk by dexMethod(allowFailure = true) {
        matcher {
            usingStrings("exitCurrentMultiTalk: isReject %b isMissCall %b isPhoneCall %b isNetworkError %b")
        }
    }

    // ── legacy v2protocal stack (only reached when the peer downgrades) ───────────────────────

    /** `nr4.y.x(...)` — the incoming float card. Shared by both stacks, so live on 8.0.76 as well. */
    internal val methodVoipShowFloatingCard by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings(".ui.voip.VoipFloatView")
            paramCount = 8
        }
    }

    /**
     * `nr4.y.z(Context, String toUser)` — AnimatedVoipBaseFloatCardManager.showFinishCard, the
     * "已拒绝通话" banner shown *after* a rejection. Distinct from [methodVoipShowFloatingCard]
     * (the incoming card) even though both live on `nr4.y`, so suppressing the incoming card does
     * not cover it.
     *
     * The bare "showFinishCard" string constant occurs only in this method (the lambda classes
     * carry longer `...$showFinishCard$3$2$...` constants, which `usingEqStrings` will not match).
     */
    internal val methodVoipShowFinishCard by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("showFinishCard", "(Landroid/content/Context;Ljava/lang/String;)V")
            paramCount = 2
        }
    }
    internal val methodVoipAcceptIncomingCall by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.plugin.voip")
        matcher {
            usingEqStrings("MicroMsg.VoipIncomingCallManager", "acceptIncomingCal, roomInfo:")
        }
    }
    internal val methodVoipStartAcceptVoip by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.plugin.voip")
        matcher {
            usingEqStrings("MicroMsg.VoipIncomingCallManager", "startAcceptVoIP, roomInfo:")
        }
    }
    internal val methodVoipServiceExSetInviteContent by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.Voip.VoipServiceEx", "Failed to setInviteContent during calling, status =")
        }
    }
    internal val methodVoipServiceExReject by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.Voip.VoipServiceEx", "Failed to reject with calling, status =")
        }
    }

    /** `j0.j(String content, a65.j4 addMsg)` — server-pushed `<voipmsg>` bubble (msg type 50). */
    internal val methodVoipBubbleHandle by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.VoIPBubbleHelper", "handlerBubbleMsg: parse bubble info error")
        }
    }

    /**
     * `b2.d(String talker, String, int, int, String, boolean, k0, r96.l)` — legacy call-record
     * insertion.
     *
     * NB: do NOT match on "insertMsg() called with: voipInfo = " — those strings live in the
     * synthetic Runnable `b2$$a.run()`, which takes ZERO parameters, so the previous matcher made
     * `args[0]` throw on every legacy call record.
     *
     * 匹配方式（8.0.78 修正）：原先用 `declaredClass { usingEqStrings(TAG, callagainUrl) }`，
     * 但 DexKit 在该块内判定的是「**方法自身**是否引用这些串」，而这两个串只出现在 b2 的
     * `a`/`b`/`i` 里，`d` 本身一条都不含（它把工作委托给 `b2$$a` Runnable），因此永不命中。
     * 改为：先在整包内按「类中含 callagain URL」框定宿主类，再在该类内用签名（8 参数、
     * 返回 void）唯一确定 `d`——`b2` 只有这一个 8 参数 void 方法。
     *
     * `usingEqStrings` 放在类级 matcher 时按「类（含其所有方法）引用该串」判定，故此处
     * 不再嵌套 declaredClass，而是直接以类名锚定：`com.tencent.mm.plugin.voip.model.b2`
     * 是 8.0.78 的实测位置（classes16.dex）。
     */
    internal val methodVoipLegacyInsertMsg by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.plugin.voip.model.b2"
            paramCount = 8
            returnType("void")
        }
    }

//    private val classVoipService by dexClass()
//    private val classVoipManager by dexClass()
//    private val classIncomingVoipInvite by dexClass()
//    private val classIncomingVoipILinkInvite by dexClass()
//    private val classMultiTalkInvite by dexClass()
//    private val classVoipFloatCard by dexClass()
//    private val classRecentForwardInfoHelperV3 by dexClass()
//    private val classContactRecommendHelperV3 by dexClass()

    /**
     * 与 [SplitGroupCall.resolveDex] 同一套思路：先探测 legacy MultiTalk 架构是否还在，
     * 不在就把相关锚点标记为「主动缺席」，而不是让它们以「找不到」的形态挂进自检报告。
     *
     * 实测 8.0.78（classes16.dex 等全量 dex）已彻底移除该架构：
     * - TAG `MicroMsg.MT.MultiTalkManager`、日志串 `onInviteMultiTalk All Var Value` /
     *   `exitCurrentMultiTalk: isReject`、以及类 `Lcom/tencent/mm/modeltalkroom/MultiTalkGroup;`
     *   全部为空命中；这不是特征串漂移，是代码本身不存在。
     * - 因此 [methodMultiTalkOnInvite] / [methodExitMultiTalk] 在这版微信上永远不会命中，
     *   继续按「锚点失效」上报只会长期挂着一个修不好的假故障。
     *
     * 注意：只标这两个。`methodVoipLegacyInsertMsg` 对应的 v2protocal 栈仍存在
     * （`MicroMsg.VoipPluginManager` 与 callagain URL 均在 classes16 命中），必须照常解析。
     */
    override fun resolveDex(dexKit: DexKitBridge) {
        if (methodMultiTalkOnInvite.isPlaceholder || methodExitMultiTalk.isPlaceholder) {
            val reason = "legacy MultiTalk architecture is absent in this WeChat version"
            WeLogger.w(TAG, "legacy MultiTalk absent; marking its anchors intentionally absent")
            methodMultiTalkOnInvite.setPlaceholderDescriptor(reason)
            methodExitMultiTalk.setPlaceholderDescriptor(reason)
        }
    }
}
