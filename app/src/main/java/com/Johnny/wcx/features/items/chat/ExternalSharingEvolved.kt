package com.Johnny.wcx.features.items.chat

import android.content.pm.ShortcutManager
import androidx.activity.ComponentActivity
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

@Feature(
    name = "分享进化",
    categories = ["聊天"],
    description = "清理旧版本注册到桌面的「好友直达分享」快捷方式（长按微信图标时出现的那几项）。" +
        "新版本不再往桌面注册任何快捷方式；点本项可立即清除遗留项"
)
object ExternalSharingEvolved : ClickableFeature() {

    private const val TAG = "ExternalSharingEvolved"

    /**
     * 不再往桌面注册动态快捷方式。
     *
     * ## 为什么要移除这个行为
     *
     * 旧实现取「消息互动量前 3 的好友」，用 [android.content.pm.ShortcutManager.dynamicShortcuts]
     * 注册成 `sharing_target_<wxid>` 动态快捷方式。后果是**用户手机桌面长按微信图标时，
     * 会凭空多出三个好友名字**（实测截图确认），而这些项出现在系统桌面菜单里、与微信
     * 自己注册的「收付款/扫一扫/我的二维码」并列，用户完全无法分辨来源，也无处关闭。
     *
     * 而且它原本想做的「分享菜单优化」在该实现里并不存在——整段代码只注册快捷方式，
     * 没有对分享流程做任何改动。因此移除注册行为后，本功能只剩「清理遗留项」这一件事。
     */
    override fun onEnable() {
        // 主动清理：老版本可能已经把快捷方式写进系统了，升级后必须收回，
        // 否则桌面上那三个好友项会永久残留（已无任何代码会去清它们）。
        CoroutineScope(Dispatchers.Main).launch {
            delay(1.seconds) // 等桌面 ShortcutManager 就绪，避免早期调用被系统丢弃
            purgeLegacyShortcuts()
        }
    }

    /** 手动清理入口（点本项即执行）。 */
    override fun onClick(context: ComponentActivity) {
        purgeLegacyShortcuts()
    }

    /**
     * 清空本院注册的动态快捷方式。
     *
     * 只影响**本模块以自己身份注册**的那些 dynamic shortcuts（即 `sharing_target_*`），
     * ***不会*** 动微信自身在 `shortcuts.xml` 里声明的固定快捷方式（收付款/扫一扫/我的二维码）
     * ——那些属于微信，`ShortcutManager` 按调用方包名隔离。
     */
    private fun purgeLegacyShortcuts() {
        runCatching {
            val sm = HostInfo.application.getSystemService<ShortcutManager>()
            sm.removeAllDynamicShortcuts()
            WeLogger.i(TAG, "legacy desktop share shortcuts purged")
        }.onFailure { WeLogger.w(TAG, "purge legacy shortcuts failed", it) }
    }
}
