use utf8;
use strict;

my $f1 = "app/src/main/java/com/Johnny/wcx/features/items/system/ForceTabletMode.kt";
my $content1 = <<'KTEOF';
package com.Johnny.wcx.features.items.system

import android.content.Context
import android.os.Build
import android.widget.Button
import androidx.compose.material3.Text
import androidx.core.view.isGone
import androidx.core.view.isVisible
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.TargetProcesses
import com.Johnny.wcx.utils.WeLogger
import com.tencent.mmkv.MMKV

@Feature(name = "强制平板模式", categories = ["系统与隐私"], description = "让微信将当前设备识别为平板")
object ForceTabletMode : SwitchFeature(), IResolveDex {

    // 与 KSP 生成的 Feature name 一致（FeaturesProvider 启动早期尚未运行，提前读开关需硬编码同名 key）
    private const val PREFS_KEY = "强制平板模式"

    private val methodIsTablet by dexMethod {
        matcher {
            usingEqStrings("Lenovo TB-9707F", "eebbk")
        }
    }
    private val methodIsTablet2 by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.UIUtils", "isRoyoleFoldableDevice!!!")
        }
    }
    private val methodOtherDeviceLoginButtonIsVisible by dexMethod {
        matcher {
            usingEqStrings("loginAsOtherDeviceBtn")
        }
    }
    private val methodCgiCheckLoginAsPad by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.CgiCheckLoginAsPad", "/cgi-bin/micromsg-bin/checkloginaspad")
        }
    }
    // DexKit 进程内检索"该账户尚未获取体验资格"提示方法（微信混淆，不硬编码类名），
    // hook 跳过以避免平板模式下无体验资格提示打断使用。allowFailure 保证检索失败时静默降级。
    private val methodNoQualificationTip by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("该账户尚未获取体验资格")
        }
    }

    /**
     * 早期设备伪装：在微信 Application.attachBaseContext 执行之前调用（UnifiedEntryPoint hookBefore），
     * 确保微信读取本地登录凭证时 Build 已是平板标识，避免 token(平板) 与运行环境(手机) 不匹配导致本地销毁会话登出。
     * 无 DexKit 依赖（零扫描延迟）；子进程跳过，避免 MMKV 未初始化崩溃。
     */
    fun tryApplyEarlySpoof(base: Context): Boolean {
        if (!TargetProcesses.isInMain) return false
        return runCatching {
            if (!MMKV.isInitialized()) MMKV.initialize(base)
            val enabled = WePrefs.getBoolOrDef(PREFS_KEY, false)
            if (!enabled) return@runCatching false
            val spoof =
                mapOf(
                    "MANUFACTURER" to "Lenovo",
                    "BRAND" to "Lenovo",
                    "MODEL" to "TB-9707F",
                    "DEVICE" to "TB-9707F",
                    "PRODUCT" to "TB-9707F",
                    "FINGERPRINT" to "Lenovo/TB-9707F/TB-9707F:13/TKQ1.221013.002/user/release-keys",
                )
            spoof.forEach { (n, v) ->
                val field = Build::class.java.getDeclaredField(n)
                field.isAccessible = true
                field.set(null, v)
            }
            WeLogger.i("ForceTabletMode", "early tablet device spoof applied")
            true
        }.getOrElse {
            WeLogger.w("ForceTabletMode", "early tablet spoof failed", it)
            false
        }
    }

    override fun onEnable() {
        // 设备伪装：把 Build 设备描述字段伪装为平板（与 isTablet hook 同一机型 Lenovo TB-9707F），
        // 使微信上报的设备指纹与平板身份一致，避免开启后重启微信被判定为"设备变化"而掉线。
        // 登录态绑定标识（ANDROID_ID、微信 deviceId 等）保持真实不动，仅改描述性字段。
        // 注意：早期伪装已在 tryApplyEarlySpoof（attachBaseContext hookBefore）执行，此处幂等补充。
        runCatching {
            val spoof =
                mapOf(
                    "MANUFACTURER" to "Lenovo",
                    "BRAND" to "Lenovo",
                    "MODEL" to "TB-9707F",
                    "DEVICE" to "TB-9707F",
                    "PRODUCT" to "TB-9707F",
                    "FINGERPRINT" to "Lenovo/TB-9707F/TB-9707F:13/TKQ1.221013.002/user/release-keys",
                )
            spoof.forEach { (name, value) ->
                val field = Build::class.java.getDeclaredField(name)
                field.isAccessible = true
                field.set(null, value)
            }
        }.onFailure { WeLogger.e("ForceTabletMode", "device spoof failed", it) }

        methodIsTablet.hookBefore {
            result = true
        }

        methodIsTablet2.hookBefore {
            result = true
        }

        methodOtherDeviceLoginButtonIsVisible.hookBefore {
            val view = args[0] as? Button? ?: return@hookBefore
            if (view.isGone) view.isVisible = true
        }

        "com.tencent.mm.plugin.account.ui.LoginHistoryUI".toClass().reflekt().firstMethod("initView").hookAfter {
            val btn = thisObject!!.reflekt().firstField {
                type = Button::class
            }.get()!! as Button
            btn.isVisible = true
        }

        // 去掉强制"可以平板登录"的 hook：会触发微信尝试平板登录，账号无体验资格时提示"该账户尚未获取体验资格"
        // methodCgiCheckLoginAsPad.hookBefore { result = true }

        methodNoQualificationTip.hookBefore {
            result = null
        }
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState) {
            showComposeDialog(context) {
                AlertDialogContent(
                    title = { Text(text = "警告") },
                    text = { Text(text = "此功能可能导致账号异常, 确定要启用吗?") },
                    confirmButton = {
                        Button(onClick = {
                            applyToggle(true)
                            onDismiss()
                        }) {
                            Text("确定")
                        }
                    },
                    dismissButton = {
                        TextButton(onDismiss) {
                            Text("取消")
                        }
                    }
                )
            }
            return false
        }

        return true
    }
}
KTEOF
$content1 =~ s/\r?\n/\r\n/g;
open my $out1, ">:encoding(UTF-8)", $f1 or die "write fail1";
print $out1 $content1;
close $out1;

my $f2 = "app/src/main/java/com/Johnny/wcx/loader/startup/UnifiedEntryPoint.kt";
my $content2 = <<'KTEOF';
package com.Johnny.wcx.loader.startup

import android.app.Application
import android.content.Context
import dalvik.system.InMemoryDexClassLoader
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import com.Johnny.wcx.features.items.system.ForceTabletMode
import com.Johnny.wcx.loader.abc.IHookBridge
import com.Johnny.wcx.loader.abc.ILoaderService
import com.Johnny.wcx.loader.utils.HybridClassLoader
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.hookAfterDirectly
import com.Johnny.wcx.utils.hookBeforeDirectly
import com.Johnny.wcx.utils.reflection.ClassLoaders

object UnifiedEntryPoint {

    private const val TAG = "UnifiedEntryPoint"

    fun entry(
        loaderService: ILoaderService,
        hookBridge: IHookBridge?,
        initialClassLoader: ClassLoader,
        modulePath: String
    ) {
        StartupInfo.hookBridge = hookBridge

        val self = ClassLoaders.MODULE
        val selfParent = self.parent
        if (self is InMemoryDexClassLoader) {
            // The Zygisk payload's parent is the system loader. Keep the payload loader
            // separately so HybridClassLoader can search its DEX without parent delegation.
            HybridClassLoader.moduleClassLoader = self
        }
        HybridClassLoader.moduleParentClassLoader = selfParent
        self.reflekt()
            .firstField { name = "parent"; superclass() }
            .set(HybridClassLoader)

        WeLogger.d(TAG, "hooking Application.attachBaseContext")

        val attachMethod = "com.tencent.mm.app.Application".toClass(initialClassLoader).reflekt()
            .firstMethod { name = "attachBaseContext" }
        // 系统设备伪装最先执行（无 DexKit 依赖）：微信读取本地登录凭证前 B
