package com.Johnny.wcx.features.items.chat

import android.app.Activity
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.ujhhgtg.reflekt.utils.toClass
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import org.luckypray.dexkit.DexKitBridge

/**
 * 发送多张图片/视频时自动勾选「发送后合并展示」。
 *
 * 实现：hook 发送预览页（ImagePreviewUI）生命周期，布局渲染后自动勾选合并 CheckBox。
 * 微信点图进预览时本身会勾选，本功能在微信未勾选时兜底自动勾选。
 *
 * 已知限制：相册（AlbumPreviewUI）底部不预览直接发送时，微信的合并设置依赖真实触摸输入管道，
 * 程序化勾选无法触发微信内部合并字段设置（防自动化），该场景暂不支持自动合并。
 */
@Feature(name = "发送后合并显示", categories = ["聊天"], description = "发送多张图片/视频时自动勾选「发送后合并显示」选项")
object AutoEnableMergeDisplayOnSend : SwitchFeature(), IResolveDex {

    private const val TAG = "AutoMergeDisplay"

    private var previewActivity: Activity? = null

    override fun resolveDex(dexKit: DexKitBridge) {
        // 无需 DexKit 解析
    }

    override fun onEnable() {
        WeLogger.i(TAG, "onEnable 执行")
        try {
            hookLifecycle("com.tencent.mm.plugin.gallery.ui.ImagePreviewUI", "onCreate")
            hookLifecycle("com.tencent.mm.plugin.gallery.ui.ImagePreviewUI", "onResume")
            WeLogger.i(TAG, "onEnable hook 注册成功")
        } catch (e: Throwable) {
            WeLogger.i(TAG, "onEnable hook 注册失败: ${e.message}")
        }
    }

    private fun hookLifecycle(className: String, methodName: String) {
        try {
            val cls = className.toClass()
            XposedBridge.hookAllMethods(cls, methodName, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    previewActivity = activity
                    scheduleAutoCheck(activity)
                }
            })
            WeLogger.i(TAG, "已hook生命周期: $className::$methodName")
        } catch (e: Throwable) {
            WeLogger.i(TAG, "hook生命周期失败 $className::$methodName: ${e.message}")
        }
    }

    /** 发送页布局渲染后自动勾选「发送后合并展示」 */
    private fun scheduleAutoCheck(activity: Activity) {
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val a = previewActivity ?: return@postDelayed
                val root = a.window?.decorView ?: return@postDelayed
                root.post {
                    val cb = findMergeCb(root)
                    if (cb != null) {
                        if (!cb.isChecked) {
                            cb.performClick()
                            WeLogger.i(TAG, "预览页自动勾选完成 isChecked=${cb.isChecked}")
                        }
                    }
                }
            } catch (e: Throwable) {
                WeLogger.i(TAG, "自动勾选失败: ${e.message}")
            }
        }, 800)
    }

    /** 递归找「发送后合并展示」CheckBox（text/contentDescription/相邻TextView文本匹配） */
    private fun findMergeCb(root: android.view.View?): android.widget.CheckBox? {
        if (root == null) return null
        // 1. CheckBox 自身 text/desc 匹配
        if (root is android.widget.CheckBox) {
            val text = root.text?.toString() ?: ""
            val desc = root.contentDescription?.toString() ?: ""
            if (text.contains("合并") || desc.contains("合并") || text.contains("group")) return root
        }
        // 2. TextView 含"合并"字样 → 向上逐层父容器找 CheckBox（兄弟或祖先）
        if (root is android.widget.TextView) {
            val t = root.text?.toString() ?: ""
            if (t.contains("合并")) {
                var p: android.view.ViewParent? = root.parent
                while (p is android.view.ViewGroup) {
                    val cb = findCheckBoxInGroup(p)
                    if (cb != null) return cb
                    p = p.parent
                }
            }
        }
        // 3. 递归子 View
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                val res = findMergeCb(root.getChildAt(i))
                if (res != null) return res
            }
        }
        return null
    }

    /** 在指定 ViewGroup 内找 CheckBox（直接子级） */
    private fun findCheckBoxInGroup(group: android.view.ViewGroup): android.widget.CheckBox? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is android.widget.CheckBox) return child
        }
        return null
    }
}
