# 拾光开发指南

用随机照片卡片整理手机相册的安卓原生应用，使用 Kotlin、Jetpack Compose 和系统 MediaStore。支持 Android 10 及以上。

## 使用

- 授权后，从手机可访问的全部相册随机抽取照片。已保留或已加入待删除清单的照片不会再次出现，整理记录在关闭应用后继续保留。
- 左滑、下滑：加入待删除清单。右滑、上滑：保留。也可使用底部按钮。
- 点按“撤销”可以逐步撤销选择；打开待删除清单可逐张恢复。正式删除前，原图保持在相册中。
- 清单内点按“确认删除”，检查数量和大小后继续，由安卓系统确认永久删除。取消系统确认会保留未删除的清单。永久删除完成后无法在本应用内撤销。
- 轻触照片查看详情；双指缩放、放大后拖动、双击放大或复原。查看详情不会做出保留或删除选择。
- 主界面和详情页均可分享原图，使用安卓系统分享面板。
- 显示拍摄时间、文件大小、像素宽高和相册名称。相册索引缺失拍摄时间时，读取当前照片的 EXIF；原图也未记录时，明确显示“添加时间”。
- 右上角设置支持跟随系统、浅色、深色；可调整照片权限或重新整理已保留照片。

滑动包含方向提示、越过阈值的触觉反馈、飞出动画和未达到阈值时的回弹。切换照片、页面、进度和恢复清单也有动画。

## 隐私与权限

照片在本机处理，无账号、广告或统计组件；整理记录和外观设置保存在私有存储。用户主动检查或下载更新时连接 GitHub，分享由系统交给所选应用。完整说明见 [隐私说明](PRIVACY.md)。

Android 14 及以上支持“仅选中的照片”，界面会说明当前范围，并提供重新选择入口。返回应用时重新检查权限和相册。无法访问的待删除照片仍保留在清单中，避免权限变化丢失整理记录。

Android 11 及以上使用系统批量永久删除请求，每批最多 200 张；Android 10 按照片请求系统授权后删除。外置存储使用照片实际所在卷的 URI。

## 开发与构建

工具链：JDK 21、Gradle 8.13、AGP 8.13.2、Kotlin/Compose Compiler 2.4.20、Compose UI 1.9.4、Material 3 1.4.0；compileSdk/targetSdk 36，minSdk 29，Java/Kotlin 字节码目标 17。

1. 用 Android Studio 打开本目录，安装 Android SDK 36。
2. 在 `local.properties` 设置本机 `sdk.dir`，或配置 `ANDROID_HOME`。
3. 执行：

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
.\gradlew.bat assembleDebug assembleDebugAndroidTest assembleRelease
python tools/run_device_tests.py emulator-5554
```

Debug 安装包位于 `app/build/outputs/apk/debug/app-debug.apk`。Release 构建：

```powershell
.\gradlew.bat assembleRelease
```

本地发布签名通过 `.signing/release.properties` 配置（不进入源码归档）。没有该文件时只产生未签名 Release APK。签名密钥用于后续覆盖安装更新，请自行备份并保管，不要分享。

Debug 应用 ID 为 `com.albumclean.app.debug`，Release 为 `com.albumclean.app`，可以同时安装，整理记录分别保存。

Windows 中文路径使用 `android.overridePathCheck=true`。插件仓库中的 R8 9.1.43 用于匹配 Kotlin 2.4 产物。

## 代码入口

- `MainActivity.kt`：权限、系统删除授权、分享与应用生命周期。
- `PhotoViewModel.kt`：随机队列、保存选择、连续撤销、刷新与错误反馈。
- `data/PhotoRepository.kt`：MediaStore 查询和 SQLite 整理记录。
- `data/Photo.kt`：照片元数据、格式化和滑动/队列规则。
- `ui/CleanApp.kt`：主界面、清单、外观设置及滑动动画。
- `ui/PhotoImage.kt`：异步照片解码、内存缓存和原生缩放视图。

详情图先显示预览，再根据设备内存解码更高清版本；最长边最多 8192 像素、总像素最多 1600 万，以限制超大照片的内存占用。分享始终使用原始文件。

## 验证

规则测试覆盖四个滑动方向、短距离/斜向滑动、去重、刷新不换当前照片、撤销重新入队、元数据格式和 EXIF 时区。设备测试覆盖真实 MediaStore 读取/持久化、保留/待删除/恢复、Activity 重建、浅深主题和详情缩放，生成的样本位于模拟器内 `Pictures/PhotoCleanTest`。

`tools/release_smoke.py` 在 `emulator-5554` 上检查发布包的四向滑动、主题、缩放、分享、系统删除取消/确认和进程重启；真正删除的对象会先校验为 `PhotoClean_Sample_` 开头的生成图片。这个脚本只用于测试模拟器。

本机构建记录及测试产物放在 `artifacts/`，不进入 Git；展示截图位于 `docs/screenshots/`。验证范围见 [测试说明](TESTING.md)。

## 发布到 GitHub

正式包使用 `com.albumclean.app`，后续更新需保留该 ID 和同一签名。版本号在 `app/build.gradle.kts` 中维护：显示版本采用 `主版本.次版本.修订号`，`versionCode` 必须递增。

1. 完成测试，提交并确认 Git 工作区干净。
2. 使用本机签名配置运行 `.\gradlew.bat assembleRelease`。
3. 运行 `python tools/package_release.py`，从当前提交导出源码并生成更新清单。
4. 为该提交创建 `v版本号` 正式 Release，上传以下文件：

| 文件 | 用途 |
| --- | --- |
| `Glimpse-版本号.apk` | 已签名安装包 |
| `android-update.json` | 版本、包名、大小、SHA-256 与源码提交 |
| `Glimpse-版本号-source.zip` | Git 提交对应的源码 |
| `SHA256SUMS.txt` | 下载文件校验值 |

源码包不含发布者的签名密钥。自行签名的构建无法接受签名不同的官方更新；请自行维护后续签名，或安装官方 Release 包。

更新协议及安装约束见 [更新说明](UPDATES.md)。

## 排障

| 现象 | 处理 |
| --- | --- |
| 找不到 SDK | 检查 Android Studio 的 SDK 安装、`local.properties` 或 `ANDROID_HOME` |
| Java / Kotlin 目标不一致 | 使用 JDK 21，保留项目内 JVM 17 目标配置 |
| 看不到全部照片 | 检查系统照片权限；部分授权只返回选中的照片 |
| 覆盖安装提示签名不一致 | 使用原签名构建，不同签名不能直接覆盖安装 |
| 调试版无法检查更新 | 调试包 ID 独立，通过 Android Studio 或 ADB 更新 |
| GitHub 请求受限 | 稍后重试，或打开 Releases 手动下载 |

## 平台参考

- [Android 照片读取和修改](https://developer.android.com/training/data-storage/shared/media)
- [Android 14 部分照片访问](https://developer.android.com/about/versions/14/changes/partial-photo-video-access)
- [MediaStore 删除请求](https://developer.android.com/reference/android/provider/MediaStore#createDeleteRequest(android.content.ContentResolver,%20java.util.Collection%3Candroid.net.Uri%3E))
