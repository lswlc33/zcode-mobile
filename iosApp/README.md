# Xcode 侧的 Kotlin framework 集成说明

Xcode 工程通过 Build Phase 调 Gradle 任务 `:shared:embedAndSignAppleFrameworkForXcode`。
该任务由 KGP 提供，会按 Xcode 的平台/配置环境变量（`PLATFORM_NAME`、`SDK_NAME`、
`CONFIGURATION`、`ARCHS`）选择正确的 framework 变体并拷进 app bundle。

命令行无签名构建（CI 用）：

```bash
cd iosApp
xcodebuild -project ZcodeMobile.xcodeproj -scheme ZcodeMobile \
  -configuration Release -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO
```

生成的 `.app` 位于 `build/Build/Products/Release-iphonesimulator/ZcodeMobile.app`，
打包成 IPA 即为 CI 上传的产物（模拟器 IPA，无需签名即可装入模拟器）。
