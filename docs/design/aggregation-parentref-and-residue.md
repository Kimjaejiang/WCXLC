# 归拢的显示机制与「模块不加载」残留问题

> 微信 8.0.78 实测结论，2026-09-20。
>
> 本文记录一次失败的方案验证（「写陌生 `parentRef` 值让微信自动还原」），
> 以及它顺带查清的微信原生机制。**结论是否定性的，避免后人重复踩坑。**

## 一、微信主页如何决定「显示哪些会话」

### 实测抓到的原生 SQL

在模块的 `SQLiteWrapper.rawQuery` 探针上抓到主页会话列表查询：

```sql
SELECT rconversation.username, rconversation.parentRef
FROM rconversation
WHERE (rconversation.flag > ?)
  AND ( (rconversation.parentRef = ?)                        -- ① 锚点值
        OR (rconversation.parentRef is null OR rconversation.parentRef = '') )  -- ② 根会话
```

分页版本进一步确认了排序与条数：

```sql
SELECT ... FROM rconversation
WHERE (rconversation.flag <= ?)
  AND ( ((parentRef is null OR parentRef = '') OR (parentRef = ?))
        AND ( ...会话类型白名单... ) )
ORDER BY flag desc LIMIT 30 OFFSET 0
```

### 结论

**主页只显示两类会话：**

| `parentRef` 取值 | 是否出现在主页 |
|---|---|
| `NULL` / `''` | ✅ 显示（普通会话） |
| `conversationboxservice` / `message_fold`（锚点值，占位符 ①） | ✅ 显示（微信原生折叠入口） |
| `wekit_folder_*`（本模块写入） | ❌ 不显示 → 表现为「被折叠」 |
| **任何其他值** | ❌ **不显示 → 永久隐藏** |

即：**微信不认识的值不会 fallback 成普通会话，而是直接被过滤掉。**

## 二、被证伪的方案

### 原设想

> 让归拢文件夹的显示**依赖模块权限**：模块活着才显示，模块不在时微信自然还原。
> 核心是**不写「微信自己能认」的东西** —— 写一个微信不认识的 `parentRef` 值。

### 实测过程

单会话实验，样本 `filehelper`（文件传输助手，原始 `parentRef` 为空）：

```
EXP filehelper oldParent=                                    ← 原本是普通主页会话
EXP wrote foreign parentRef on filehelper (was )             ← 写入 zzz_unknown_parent_test
```

**结果：文件传输助手从主页消失。**

补充验证：**在 LSPosed 中关闭模块后，它依然消失、不恢复。**

### 结论

| 假设 | 实测 |
|---|---|
| 微信把不认识的值当普通会话显示 | ❌ 不显示 |
| 微信会回退/容错处理 | ❌ 直接过滤 |
| 模块关闭后行为不同 | ❌ 与模块无关，纯微信行为 |

**写入陌生值 ≠ 还原成普通会话，而是「永久隐藏且无恢复入口」——比原方案更糟。**

### 推论

任何**写入非空非锚点值**的做法，效果都等于永久隐藏。
所以「模块不在时自动还原」在**落库路线**上不可能实现，只能改走**查询层拦截**。

## 三、尚未解决的问题：模块不加载时的残留

归拢把状态写进微信库（`rconversation.parentRef`、`rcontact`、`img_flag`），
而微信自己也认 `parentRef`，**模块在不在它都照样折叠**。

| 场景 | 模块是否加载 | 残留能否清理 |
|---|---|---|
| 用户在模块内关闭归拢 | ✅ | ✅ `onDisable` → `releaseAllFolders()` |
| 用户勾选模块但微信未重启 | ✅（下次冷启动） | ✅ `recoverResidualFolders()` |
| **用户在 LSPosed 取消勾选** | ❌ 一行代码都不跑 | ❌ **谁都清不了** |
| 用户回退到旧版模块 | 视版本 | ❌ 旧版无回收逻辑 |

**第四行是根本矛盾**：清理代码属于模块，而「禁用」= 不加载 = 不执行。
所以在当前架构下，靠 `onDisable` 或任何模块内钩子都无法覆盖该场景。

### 现有缓解

`recoverResidualFolders()` 挂在 `WeMainActivityBeautifyApi.methodDoOnCreate`
（每次冷启动必跑，**不依赖 `isEnabled`**），与 `HideContacts.migrateLegacyHiddenParentRef` 同思路。
它覆盖上表第 2、4 行场景，但**救不了「取消勾选」**。

## 四、实验残留的还原

实验期间被改写的两个会话已还原：

| 会话 | 还原值 |
|---|---|
| `filehelper` | `''`（回到主页） |
| `notifymessage` | `wekit_folder_1789310889784`（回到「公众号通知」文件夹） |
