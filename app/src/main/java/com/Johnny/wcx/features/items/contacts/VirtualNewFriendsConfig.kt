package com.Johnny.wcx.features.items.contacts

import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 「新的朋友」虚拟项的配置模型与持久化。
 *
 * 配置整体序列化为一段 JSON 存在 WePrefs 里——虚拟项数量由用户决定，用单个 JSON 字段
 * 比给每项分配一组 pref key 要好管理（增删项不需要迁移旧的 key）。
 */
object VirtualNewFriendsConfig {

    private const val TAG = "VirtualNewFriends.Cfg"

    /** 虚拟项 talker 前缀。用于识别「这一行是我们造的」，与真实好友申请区分。 */
    const val TALKER_PREFIX = "wcx_virtual_"

    private const val KEY_CONFIG = "virtual_new_friends_config"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /** 单个虚拟项。头像/昵称/验证语可编辑；其余字段由代码按模板推导。 */
    @Serializable
    data class Item(
        /**
         * 稳定标识，用于把列表里的行回指到具体这一项。
         * 用户改昵称后位置会变，不能拿下标当标识。
         */
        val id: String = "",
        /** 显示昵称，同时用作行上的主标题。 */
        val nickname: String = "",
        /** 好友验证消息文案（别人加我）。 */
        val verifyContent: String = "",
        /** 好友申请文案（我加别人）。与验证语共用行上的同一个控件。 */
        val applyContent: String = "",
        /** true 表示这一项当作「我加别人」（显示申请语），false 当作「别人加我」（显示验证语）。 */
        val isMine: Boolean = false,
        /** 自定义头像的文件路径；为空时用微信默认头像。 */
        val avatarPath: String = "",
    )

    @Serializable
    data class Config(
        /** 「近三天」分组下的虚拟项。 */
        val recent: List<Item> = emptyList(),
        /** 「三天前」分组下的虚拟项。 */
        val older: List<Item> = emptyList(),
        /**
         * 开启后，「三天前」的虚拟项也使用近三天的时间戳，微信据此把它们归到「近三天」分组。
         * 真实的「三天前」项不受影响——我们只改自己造出来的行的 lastModifiedTime。
         */
        val olderAsRecent: Boolean = false,
        /**
         * 真实好友申请的显示覆盖表：真实 talker → 要改成的显示内容。
         *
         * 只改渲染时看到的东西，**不碰微信数据库**，也不影响微信发给服务器的任何数据。
         * 随时可删，删了就恢复原样。
         */
        val overrides: Map<String, Override> = emptyMap(),
    ) {
        val isEmpty: Boolean get() = recent.isEmpty() && older.isEmpty()
    }

    /** 真实项覆盖：只记要改的字段，空字符串表示“不覆盖、用原值”。 */
    @Serializable
    data class Override(
        val nickname: String = "",
        /** 验证语：别人加我时对方填的消息。 */
        val verifyContent: String = "",
        /** 申请语：我加别人时我填的消息。与验证语共用行上的同一个控件。 */
        val applyContent: String = "",
        val avatarPath: String = "",
        /**
         * 非 null 时由配置指定这行属于哪种场景（虚拟项用）。
         * 真实项保持 null，交给行自身的原生文本来判断。
         */
        val isMine: Boolean? = null,
    )

    private var cached: Config? = null

    fun load(): Config {
        cached?.let { return it }
        val raw = WePrefs.getStringOrDef(KEY_CONFIG, "")
        val cfg = if (raw.isNullOrBlank()) {
            Config()
        } else {
            runCatching { json.decodeFromString<Config>(raw) }
                .onFailure { WeLogger.e(TAG, "配置解析失败，回退到默认值", it) }
                .getOrDefault(Config())
        }
        val fixed = normalize(cfg)
        cached = fixed
        // 旧配置补齐 id 后写回盘，避免每次启动都重算，也让配置始终是一致的形态。
        if (fixed !== cfg) {
            runCatching { WePrefs.putString(KEY_CONFIG, json.encodeToString(fixed)) }
                .onFailure { WeLogger.w(TAG, "回写补齐后的配置失败: ${it.message}") }
        }
        return fixed
    }

    /**
     * 让所有项的 id 变得完整且全局唯一。
     *
     * 需要处理两类脏数据：
     *  1. 早期版本的 Item 没有 id 字段，反序列化后为 ""——虚拟行的 talker 是由 id
     *     拼出来的，id 为空会让行与配置项对不上，表现就是能打开编辑器但保存不生效。
     *  2. 早期版本的 id 在两个分组之间各自计数，近三天和三天前会各自出现 v1，
     *     两行因此共用同一个 talker——表现就是改一个头像另一个也跟着变。
     */
    private fun normalize(cfg: Config): Config {
        val used = mutableSetOf<String>()
        var changed = false

        fun fill(items: List<Item>, prefix: String): List<Item> = items.mapIndexed { i, item ->
            if (item.id.isNotBlank() && used.add(item.id)) {
                item
            } else {
                // id 缺失或与前面的项重复：重新分配一个没被占用的。
                var n = i + 1
                while ("$prefix$n" in used) n++
                val id = "$prefix$n"
                used += id
                changed = true
                item.copy(id = id)
            }
        }

        val recent = fill(cfg.recent, "r")
        val older = fill(cfg.older, "o")
        if (!changed) return cfg

        val fixed = cfg.copy(recent = recent, older = older)
        WeLogger.i(
            TAG,
            "已修正项 id（recent=${fixed.recent.map { it.id }} older=${fixed.older.map { it.id }}）"
        )
        return fixed
    }

    /** 写盘并更新缓存。返回**规范化后**的配置，调用方必须拿它回写自己的引用。 */
    fun save(config: Config): Config {
        // 写盘前统一补齐缺失/重复的 id——运行时新建的项也会走到这里，
        // 不能只在加载时修，否则新建的项照样没有 id。
        val fixed = normalize(config)
        cached = fixed
        runCatching {
            WePrefs.putString(KEY_CONFIG, json.encodeToString(fixed))
            WeLogger.i(
                TAG,
                "已保存：recent=${fixed.recent.size} older=${fixed.older.size} " +
                    "olderAsRecent=${fixed.olderAsRecent} overrides=${fixed.overrides.size}"
            )
        }.onFailure { WeLogger.e(TAG, "配置保存失败", it) }
        return fixed
    }

    /** 供设置界面编辑用：返回一份浅拷贝，避免直接改到缓存对象。 */
    fun copyOf(config: Config): Config = config.copy(
        recent = config.recent.toList(),
        older = config.older.toList(),
        overrides = config.overrides.toMap(),
    )

    /**
     * 造一个不会与现有项撞车的 id。
     *
     * 必须在**两个分组之间**也保持唯一：talker 只用 id 拼成，
     * 如果近三天和三天前各有一个 v1，两行就会共用同一个 talker，
     * 改一个头像会连带把另一个也改了。
     */
    fun newId(vararg groups: Collection<Item>): String {
        val used = groups.flatMap { it }.map { it.id }.toSet()
        var n = used.size + 1
        while ("v$n" in used) n++
        return "v$n"
    }

    /** 按 id 找到某项及其所在分组。找不到返回 null。 */
    fun findById(config: Config, id: String): Pair<Item, Boolean>? {
        config.recent.firstOrNull { it.id == id }?.let { return it to true }
        config.older.firstOrNull { it.id == id }?.let { return it to false }
        return null
    }

    /**
     * 读一个真实 talker 的显示覆盖。
     *
     * 虚拟项也能查到：它的 talker 就是前缀 + id，这里一并转成同一形状，
     * 让渲染层不用区分「虚拟项」和「被改过的真实项」。
     */
    fun findOverride(config: Config, talker: String?): Override? {
        if (talker.isNullOrBlank()) return null

        config.overrides[talker]?.let { return it }

        if (talker.startsWith(TALKER_PREFIX)) {
            val id = talker.removePrefix(TALKER_PREFIX)
            val item = findById(config, id)?.first
            if (item == null) {
                WeLogger.w(
                    "VirtualNewFriends.Cfg",
                    "findOverride 找不到虚拟项 id='$id' (talker='$talker'); " +
                        "recent=${config.recent.map { it.id }} older=${config.older.map { it.id }}"
                )
                return null
            }
            return Override(
                nickname = item.nickname,
                verifyContent = item.verifyContent,
                applyContent = item.applyContent,
                avatarPath = item.avatarPath,
                isMine = item.isMine,
            )
        }
        return null
    }
}
