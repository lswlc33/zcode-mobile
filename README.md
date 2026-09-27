# zcode-mobile

ZCode 的第三方 Android 客户端 —— 原生 Kotlin 实现 ZCode 中继协议，手机直连你自己的桌面会话。

**非官方项目**，与 Z.ai 无隶属关系。包名使用独立的 `dev.zcodemobile.app` 命名空间，不使用官方标识。

## 它是什么

把桌面上的 ZCode 装进口袋：在手机上浏览工作区与会话、发送指令、审批工具调用、查看文件改动。命令始终由你桌面所连的那台机器执行——手机端只做控制层，不复制代码、不另起运行时。

```
手机 App ──wss──> zcode.z.ai 中继 ──> 你的桌面 ZCode ──> 本地 / SSH / WSL / Docker 工作区
```

会话内容由服务端下发的结构化行数据驱动，客户端只负责把它们投影成界面。正因为不在本地做任何重型工作，release 包只有 **2.2 MB**。

| 构建 | 体积 |
|---|---|
| `assembleRelease`（R8 + arm-only） | **2.2 MB** |
| `assembleDebug`（全 ABI，不混淆） | 23.7 MB |
| 参考：Trae Solo 官方包 | 110 MB |

## 功能

**浏览**

- 工作区列表、任务列表、Git 仓库/分支信息
- 会话列表，增量实时更新
- 会话历史分页：上滑加载更早的消息
- Markdown 富文本：标题、粗体、斜体、内联代码、带语法高亮的围栏代码块、表格、列表
- 文件变更卡片（`+N −M`）、长输出全屏查看器、思考过程展示
- 上下文用量显示

**操作**

- 发送消息、排队后续消息、中断正在运行的任务
- 审批 / 拒绝工具调用
- 附件上传：走系统文件选择器（SAF），无需存储权限，分片上传 + sha256 校验，单文件上限 20 MiB
- 模型与思考等级切换，模型目录来自服务端 `model-selection`
- 会话模式切换

**其他**

- 扫码配对，或手动粘贴链接
- 浅色 / 深色主题
- 配对凭证由 Android Keystore 支撑的 `EncryptedSharedPreferences` 加密保存

## 系统要求

| | |
|---|---|
| 手机 | Android 8.0+（minSdk 26） |
| 构建 | JDK 17 + Android SDK |
| 桌面端 | 支持手机远控的 ZCode 版本（实测 3.14.3） |

## 构建

需要 JDK 17 与 Android SDK（`local.properties` 写 `sdk.dir`，或设 `ANDROID_HOME`）。未检测到 SDK 时会自动跳过 `:app`，`:protocol` 与 `:verify` 仍可构建。

```bash
# 协议层单元测试（不需要 Android SDK）
./gradlew :protocol:test

# Android APK
./gradlew :app:assembleDebug      # -> app/build/outputs/apk/debug/
./gradlew :app:assembleRelease    # 有 keystore.properties 时已签名
```

Release 签名从仓库根目录的 `keystore.properties`（gitignored）读取，本地与 CI 使用同一份密钥，产物可互相覆盖安装。没有该文件时 release 构建回退为未签名，不影响其他模块。每次推送到 GitHub 会自动构建 release APK 并发布 prerelease（见 [.github/workflows/ci.yml](.github/workflows/ci.yml)）。

如果 `dl.google.com` / `repo1.maven.org` 不可达（例如在中国大陆），用附带的镜像脚本：

```bash
./gradlew --init-script gradle/mirrors.init.gradle.kts :app:assembleDebug
```

镜像只作用于本次调用，不改动项目本身的仓库声明。

## 配对与使用

1. 桌面端 ZCode 侧栏点手机图标，生成二维码 / 远控链接
2. App 内扫码，或在添加链接页粘贴
3. 连接后即可浏览工作区与会话，发送指令、审批、传附件

两点需要注意：

- 远控链接的 `sid`/`hash`/`t` **等同于一把握着就能操作你桌面的临时钥匙**，不要外传
- 桌面端**同时只允许一个手机端接入**，新连接会顶掉旧的；关闭桌面弹窗**不会**停止远控，需要点「停止」。刷新二维码会立即作废旧链接

## 项目结构

```
protocol/        纯 Kotlin JVM，零 Android 依赖
  Vql.kt                 VQL 二进制序列化
  Json.kt                零依赖 JSON 编解码
  Wire.kt                CRC32、Channel 协议常量、WebSocket 抽象
  RelayTransport.kt      中继握手 / HMAC / 心跳 / bootstrap / 帧封装
  ChannelClient.kt       Channel RPC 多路复用
  ConversationApi.kt     v4 会话 + 命令面门面
  ConversationReducer.kt 快照/增量 → 客户端投影（纯函数）
  Commands.kt            命令信封构造 + 待审批项解析
  src/test/              golden 单元测试（含实测字节/CRC 值）
app/             Android 客户端（Compose + OkHttp + CameraX/ZXing）
verify/          JVM 实机验证入口（verifyRead / verifyCommands）
tools/relay-probe/  Node 参考实现（协议逆向的第一手证据）
docs/            协议规格与架构研究
```

`protocol` 不依赖 Android，所以同一份代码在 JVM（测试/验证）与 Android（App）上都能跑。WebSocket 通过 `WebSocketFactory` 注入：JVM 用 `java.net.http.WebSocket`，Android 用 OkHttp。

## 测试与验证

- **95 项 JVM 单元测试**（协议层 90 + 应用层 5）全部通过。协议层的 golden 测试嵌入了实机抓包的字节与 CRC 值，协议漂移时会直接失败
- `verify` 模块提供 JVM 全链路验证（读路径 / 写路径），Node 参考实现与 Kotlin 实现互为交叉验证
- 仪器测试在真机环境跑通全链路：WebSocket 握手 → bootstrap → v4 订阅 → 快照渲染 → 命令确认 → 附件上传拿到 `zcode-artifact://` 回执

运行 JVM 验证需要一个有效的远控链接（链接是凭证，通过属性注入，不写进仓库）：

```bash
printf '%s' "<remote url>" > tools/relay-probe/link.txt

./gradlew :verify:verifyRead       # 读路径（工作区 / 任务 / 会话快照）
./gradlew :verify:verifyCommands   # 写路径（发消息 / 审批 / 中断）
```

设备上验证：

```bash
./gradlew -PrelayLink="$(cat tools/relay-probe/link.txt)" :app:connectedDebugAndroidTest
```

加 `-e keepLink true` 会把链接留在应用里，方便手动点进 UI 查看。

## 已知限制

- 终端视图、真实 diff 视图尚未实现
- 扫码的解码路径经单元测试验证，但尚未扫过实物码
- WebSocket 分片重组（`fragmentCount > 1`）按参考实现推导，未在实测中触发

## 安全

- 客户端只把链接中的 `hash` 存入 Keystore 支撑的加密存储，从不写日志、不打进 APK
- 服务端 `model-selection` 响应中内嵌 provider 明文 API key；`ModelCatalogService` 只提取 providerId / 显示名 / modelId / 思考等级，原始响应不落盘、不进日志、不回显到界面
- `tools/relay-probe/` 的抓包产物（含真实会话流量）已全部 gitignore，仓库只保留 `.mjs` 参考源码
- 不要把远控链接提交到任何地方——`link.txt` 已在忽略列表中，但请保持这个习惯

## 文档

- [`docs/PROTOCOL.md`](docs/PROTOCOL.md) —— 中继协议的完整规格与实测记录
- [`docs/TRAE-SOLO-ANALYSIS.md`](docs/TRAE-SOLO-ANALYSIS.md) —— Trae Solo 架构研究
- [`docs/HANDOVER.md`](docs/HANDOVER.md) —— 项目交接报告（当前状态与设计决策）
- [`tools/relay-probe/`](tools/relay-probe/) —— Node 参考实现

## 法律

- ZCode 开源部分（`zai-org/ZCode`）为 Apache-2.0，本项目对其 schema 的引用遵循该许可
- 协议层为独立实现：基于公开前端 bundle 的静态分析与自有会话的流量观测，不含官方客户端代码
- 未使用任何 Trae Solo（字节跳动）的代码、资源或原生库
- Apache-2.0 不授予商标权，故本项目不使用 ZCode / Z.ai 官方标识
