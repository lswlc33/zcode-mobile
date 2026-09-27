# Trae Solo（字节跳动）架构研究

对 `trae_solo_cn_65533815a_v23722_26_bb87_1788314930.apk` 的分析结论。

**本文档只是分析记录，不含该应用的任何代码或资源。** 该项目未使用其任何内容。

---

## 基本情况

| 项 | 值 |
|---|---|
| 包名 | `com.bytedance.trae.cn` |
| Application | `com.bytedance.trae.TraeApplication` |
| 主 Activity | `com.bytedance.trae.home.MainActivity` |
| 自有类数 | 约 11,500（命名空间 `com.bytedance.trae.*`） |
| 总体积 | APK 110 MB / 解压 247 MB / 2974 文件 |
| dex | 9 个，共约 96 MB，76,818 个类 |
| 原生库 | 约 200 个，仅 `arm64-v8a` + `armeabi-v7a`（无 x86） |
| 构建 | Gradle，Kotlin Android 插件 `2.0.255-beta-10038`，Gradle `7.6.3-20251020084922+0000` |

工具链版本号均为字节内部定制版，非公开发行版。

## 技术栈判断

**不是** Flutter / React Native / Hermes / Expo / Cordova / Ionic —— 均无匹配符号。

UI 层是**四套方案并存**：

| 方案 | 证据 |
|---|---|
| Lynx（字节自研跨端框架） | `liblynx*.so`、`libskity.so`（canvas 渲染器）、`assets/lynx_core.js`、`com/lynx/tasm` 1368 个类 |
| AnnieX（服务端驱动 UI，跑在 Lynx 上） | `AnnieXHostActivity` + `Abs*MethodIDL` 系列 JSBridge 契约 |
| Jetpack Compose + Compose Multiplatform | META-INF 版本清单 + `org/jetbrains/compose/resources`；业务类以 `Cmp` 前缀命名 |
| 传统 XML + ViewBinding/DataBinding | `conversation/databinding` 下 135 个类 |

业务模块（按自有类数）：`kmp/` 5838（Kotlin Multiplatform 共享层）、
`conversation/` 5493、`home/` 1151、`login/` 993、`im/` 993。

网络：Retrofit2 + OkHttp3 + TTNet（Cronet/QUIC）+ 自研长连接 `FrontierConnection`（protobuf）。

AI 渲染：`libcmark_gfm_flow.so` + Markwon + commonmark，assets 内含 Latin Modern 数学字体
（`jlm_cmex10`、`jlm_cmsy10` 等），说明代码块与公式本地渲染。

音视频：火山引擎 RTC（`libbytertc*`、`libvolcenginertc`）、ByteNN 端侧推理（`libbytenn.so`）。

基础设施：`libnpth*` 性能监控全家桶、`libbytehook`/`libshadowhook` hook、`libreparo` 热修复、
GeckoX 离线包、Turing 风控、隐藏水印、华为/小米/vivo/OPPO/荣耀推送、抖音+微信登录。

## 为什么不能"复刻后精简"

1. **原生库不可还原**：约 60 MB 是闭源 C/C++ 库，源码不在 APK 内，无法从二进制恢复
2. **动态化内容不在包内**：Lynx/AnnieX 页面运行时从字节 CDN 拉取
3. **构建配置缺失**：无 `build.gradle` 依赖图、无 keystore、无签名配置
4. **后端完全绑定**：网络、鉴权、存储全指向字节云服务；改接 ZCode 后端等于全部重写
5. **许可问题**：其自有代码**未混淆**（仅约 6% 的类是短名），`conversation.chat.*` 全可读。
   精简后公开发布等于再分发字节的受版权保护表达

结论：克隆是全成本零复用——你会删掉后端层、鉴权层和原生库，然后仍然要从头实现 ZCode 协议。

## 仍然值得借鉴的设计

即便不复用代码，其工程组织有参考价值：

- **`kmp/` 共享层 + 平台适配器**：`AndroidKmpHostInfo`、`AndroidKmpKeyValueStore`、
  `AndroidKmpBridgeInitializer` 等一批宿主适配器，把平台能力收敛到明确边界。
  本项目 `protocol/` 模块的 `WebSocketFactory` 注入是同一思路。
- **AI 会话的领域切分**：`conversation/` 下 chat / voice / brainstorm / inputcontext /
  widget / prompt / skill / attachment / billing 的划分，对移动端会话 UI 有参考意义。
- **四套 UI 方案共存**：说明大型客户端按页面性质选择渲染技术，而非一刀切。
  本项目选择「原生协议 + 原生 Compose」而非混合，是因为 ZCode 的渲染需求
  （Markdown、diff、终端）在 Compose 生态里可解，且协议已完全公开。
