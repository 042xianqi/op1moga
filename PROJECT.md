# OP1 魔改（op1moga）· 项目总览

> 最后更新：2026-10-21 07:15 UTC

---

## 一、目的（为什么做这件事）

把 **Operit（Op1）** 作为底座进行魔改，解决它在实际使用中的短板，同时吸收
**太墟（taixu）** 的成熟功能，最终产出一个「任务处理快 + 功能全 + UI 顺手」的个人 Agent 客户端。

### 三方现状实测对比

| 维度 | Op1 `AAswordman/Operit` | Op2 `AAswordman/Operit2` | 太墟 `wkbin/taixu` |
|---|---|---|---|
| 任务处理速度 | **最快** | 未验证 | 慢 |
| 稳定性 | 良好 | ✗ 内测阶段，不能正常使用 | **稳定** |
| 功能丰富度 | 中 | 疑似更多 | **最多** |
| UI 质量 | ✗ 主人不满意 | 未知 | 较好 |
| 模型绑定粒度 | ✗ 全局一个模型 | 未知 | **✅ 一个对话一个模型** |
| 代码规模（Kotlin） | 1159 文件 / 45.9 万行 | 多模块化架构 | harness 272 文件 / 4.75 万行 |
| 构建可行性 | ✅ 已验证可出 APK | 未知 | — |

### 结论：为什么选 Op1 当底座

- 改 **太墟**：它慢是**架构性**的（47k 行 harness + koin 多模块 + 完整工具链），提速要重构主循环，
  比加功能难一个数量级；而且主人天天在用，改坏代价高。→ **只作为功能/契约参考源**。
- 改 **Op2**：内测阶段、跑不起来，等于在没地基的地方盖楼。→ **不用**。
- 改 **Op1**：引擎已经跑得动，在它之上加壳、换皮、补功能，投入产出比最高。→ **选定**。

---

## 二、已确认的两个痛点与根因取证

### 痛点 1：模型只能全局应用，不能「一个对话一个模型」

实测代码证据（Op1 基线 v1.12.2）：

```
ChatEntity.kt                          → modelConfigId 字段命中 0 处（会话表没有这一列）
MessageProcessingDelegate.kt:318       → configId = getConfigIdForFunction(FunctionType.CHAT)
                                         ↑ 全局映射，不区分会话

【但 override 通道已经存在，只差 UI 接线】
ChatServiceCore.sendUserMessage(chatModelConfigIdOverride: String? = null)   ← 参数已存在
  → MessageCoordinationDelegate.sendUserMessage(chatModelConfigIdOverride)   ← 已透传
    → MessageProcessingDelegate:762  chatModelConfigIdOverride ?: 全局配置    ← 已生效
      → ChatViewModel 调用处：传该参数的位置 = 0 处                          ← 断点在这里
```

**判定：这不是架构改造，是「加一列 + 接一根线 + 加个选择器」。**

太墟的现成契约（可直接照抄）：

```
taixu-ref/harness/.../session/SessionModelSwitcher.kt:48
  val previousProfile = session.modelId?.let { modelDao.findById(it) }
  ... modelId = profile.model
会话实体持有 modelId + providerId，切换时写回会话表，不碰全局配置。
```

改造成本估算：`ChatEntity` 加列 + Room 迁移（当前 version 21 → 22，共 40 条 MIGRATION）
+ `ChatViewModel` 传参 + 聊天页模型选择器 ≈ **1~2 天量级**。

### 痛点 2：UI 不好看

```
ui/ 目录 9 个子域（common/components/error/features/floating/main/permissions/recovery/theme）
UI 代码量：约 22 万行
最重的单文件：ToolPkgComposeDslScreen 4733 行、ModelConfigScreen 2530 行
主题层：theme/ 已完整（Color.kt / Theme.kt / LiquidGlass / WaterGlass / ThemeColorSchemeResolver）
```

**判定：换皮不用碰 22 万行业务 UI。** 改 Theme 层
（`Color.kt` + `Theme.kt` + `ThemeColorSchemeResolver`）即可全局换色/换圆角/换字体。

---

## 三、计划（分四阶段）

### 阶段 1 — 地基（进行中）

- [x] 建立独立仓库 `op1moga`，托底 Op1 基线源码
- [x] 补齐 `terminal` 子模块到 pin commit `0458689`（Pty.pid 编译必需）
- [x] 打通 GitHub Actions 云端构建链路（**不在手机本机跑**）
- [ ] CI 稳定产出 APK 工件
- [ ] 决定：是否转为真 Fork 以保留上游同步能力（**待主人拍板**）

### 阶段 2 — 痛点修复（优先）

- [ ] **per-chat 模型绑定**：会话表加 `modelConfigId` 列 + Room 迁移 + 接线 + 选择器
- [ ] 参照太墟 `SessionModelSwitcher` 的契约，注明「对齐太墟 XXX」

### 阶段 3 — 功能移植（主人点单制）

规则：**主人说哪个功能，宁宁就只搬哪个**，不做全量对比清单轰炸。

候选（来自太墟，待主人点名）：
- 工作流可视化编排
- MCP 能力面板
- 记忆系统 / 长期事实记忆
- 虚拟屏隔离操作
- 其他主人指定的

### 阶段 4 — UI 改版

1. 先换 Theme 层（全局配色/圆角/字体）→ 看整体效果
2. 再按需动局部页面

---

## 四、仓库与路径

### Git 仓库

| 项目 | 地址 | 可见性 |
|---|---|---|
| **主工作仓库（魔改落地）** | https://github.com/042xianqi/op1moga | **public** |
| Op1 上游参考 | https://github.com/AAswordman/Operit | public |
| Op2 参考 | https://github.com/AAswordman/Operit2 | public |
| 太墟参考 | https://github.com/wkbin/taixu | public |

### 本地路径

| 用途 | 路径 | 说明 |
|---|---|---|
| 魔改工作树 | `/workspace/op1moga` | git 仓库，origin 指向 op1moga |
| Op1 原始快照 | `/workspace/op1-src` | codeload tar.gz 解包，只读参考 |
| Op2 源码 | `/root/Operit2` | HEAD `bb552fff` |
| 太墟源码 | `/workspace/taixu-ref` | HEAD `57e5f12` Release v0.21.0 |
| 当前工作区 | `/workspace/novel-lib` | 会话默认工作目录 |

### 构建产物与日志

| 用途 | 位置 |
|---|---|
| CI 实时状态 | `.ci-watch.log`（后台进程 `ciwatch` 每 60 秒写入） |
| CI 监视脚本 | `.ci-watch.sh` |
| APK 工件（CI 产出后） | GitHub Actions run 页面 → Artifacts |
| 本地构建笔记 | `/sdcard/Download/operit-out/Operit-BUILD-NOTES.md` |

---

## 五、CI 构建状态

### 关键决策：构建在 GitHub Actions 云端，不在手机本机

手机是 aarch64 PRoot 环境，本地完整构建需要 45 分钟以上，且内存吃紧（曾 OOM）。
改为仓库 CI 构建。

### 历史 run

| Run ID | 触发 | 结果 | 说明 |
|---|---|---|---|
| 39550775562 | push | ❌ failure | 30 分钟时 runner 收到 shutdown signal |
| 39550775552 | push | ❌ failure | 同上（tests workflow） |
| 39554433679 | dispatch | ❌ failure | 31 分钟被 shutdown signal 砍，前 19 步全 success |
| 39558269032 | dispatch | ⏹ cancelled | 被后一个 run 的 concurrency 顶掉 |
| **39558432795** | dispatch | 🔄 **in_progress** | 仓库转 public 后重跑 |

### 已排除的死因（有日志证据）

- **不是** `timeout-minutes: 240`（才跑 31 分钟）
- **不是** 磁盘满（`no space / ENOSPC / Killed / 137` → 0 命中）
- **不是** OOM（无 `OutOfMemoryError`）
- **不是** 编译错误（日志 `e:` error 行 = 0，全是 `w:` warning）
- **确认是** runner 被外部 shutdown 信号中断（两次同签名）

### 应对措施

1. 仓库从 private 转为 **public** → public 仓库 Actions 分钟数免费无限
2. 缓存预热后重跑
3. 后台监视器持续跟踪

---

## 六、待主人拍板的两件事

1. **仓库要不要转为真 Fork（`042xianqi/Operit`）？**
   - 现状是从上游 tar.gz 快照建仓 → 无法 merge/rebase 上游更新
   - 上游迭代快（今年已到 v1.12.2），长期魔改建议转 Fork
   - 代价：需重新落地一次基线

2. **第一个动哪个功能？**
   - 推荐：**per-chat 模型绑定**（痛点最痛、改动最小、有太墟现成契约）
