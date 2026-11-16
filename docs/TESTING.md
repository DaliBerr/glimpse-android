# 测试说明

## 1.0.2 回归测试（2026-11-16）

构建号 3。新增 `PendingDeletionTest`，使用 Robolectric 在 API 29 / 34 下验证 SQLite 持久化、前台刷新和删除前核对。全部 JVM 测试共 31 项：30 项通过，1 项因 API 29 没有系统回收站而跳过。

| 本次检查 | 结果 |
| --- | --- |
| `testDebugUnitTest` | 30 项通过、1 项跳过，0 失败 |
| `lintDebug` | 通过，0 错误 |
| `assembleDebug` / `assembleDebugAndroidTest` | 通过 |
| `assembleRelease` | 通过，使用原正式密钥签名；包名 `com.albumclean.app`、版本 1.0.2、构建号 3 |
| Android 14 / API 34 模拟器设备测试 | 10 项通过，0 失败，包含真实 MediaStore 回归和更新器测试 |
| Release APK 签名 | APK Signature Scheme v2 校验通过，与已发布 1.0.1 的证书一致 |

新增覆盖：

- 已在系统相册删除的待删除项自动移出清单，重新创建 Repository 后保持一致。
- 已移入系统回收站的照片仅清理应用记录，不永久删除系统回收站中的文件。
- 混合清单区分仍存在、已删除和暂时无法访问的照片；删除前再检查刷新后的外部变化。
- 部分权限、权限在查询中被撤销、存储卷离线、查询返回 `null` 或抛出异常时保留记录。
- 照片仍在写入或同一 URI 的文件已被修改时，不提交旧记录对应的删除请求。

JVM 测试使用受控的 MediaStore Provider；API 29 的卷枚举使用测试框架配置的挂载卷，以补齐 Robolectric 未提供的存储 Binder 数据。设备测试在 Android 14 模拟器上运行，对应真实 MediaStore 的外部删除、回收站、部分访问和删除前再核对场景；更新器使用当前正式签名 APK 验证下载、缓存、签名和安装意图。

完整设备测试首次运行发现用例共用照片样本，导致已删除记录影响后续用例。改为每个用例独立生成样本后，10 项全部通过。生产逻辑未因该测试隔离修正而改变。

正式构建在保留原密钥的本机完成。证书 SHA-256 为 `47b4732d832313babf6bd66d97230d07c4ed832cc2c077ed6b70bbb286777cf1`。签名配置和私钥不进入 Git 或发布附件。本轮构建日志与设备测试结果分别保存在本机 `artifacts/release-1.0.2-build.log`、`artifacts/release-1.0.2-device.log`。尚未进行真机测试。

## 1.0.1 验证记录

版本：拾光 Glimpse 1.0.1（构建号 2），2026-11-15。

| 检查 | 结果 |
| --- | --- |
| Debug / 签名 Release 构建 | 通过 |
| JVM 规则测试 | 13 项通过 |
| Android 14 模拟器设备测试 | 6 项通过 |
| Android Lint | 通过，仍有依赖更新与编码风格等警告 |
| APK 签名 | 使用本机正式签名，更新要求证书一致 |

### 照片整理

规则测试覆盖四向滑动、短距离与斜向手势、队列去重、刷新保留当前照片、撤销回队、文件大小和 EXIF 时间。

设备测试覆盖真实 MediaStore 读取、本地持久化、保留 / 待删除 / 恢复、Activity 重建、浅深主题、详情双击放大以及查看后不改变整理选择。

初版发布包在 Android 14 模拟器上实际操作过四向滑动及撤销、系统分享、取消应用内 / 系统删除确认、确认删除、进程重启和权限撤销。真正删除的对象为生成的测试图片。

### 更新

规则测试验证降级拒绝、调试包隔离、元数据边界、固定 HTTPS 来源、包名 / 版本 / 构建号 / 最低系统版本 / 签名证书匹配。

设备测试使用真实签名 Release APK 和可控的网络响应，验证：

- 更新清单与附件摘要校验，错误字段和不存在的正式版本。
- 下载并校验、缓存恢复、只读 FileProvider 安装意图。
- 错误签名、取消下载、下载后被修改的 APK 均无法进入可安装状态。

这些测试不执行静默安装，也不代替不同品牌手机上的完整系统安装流程验收。

## 运行

```powershell
.\gradlew.bat testDebugUnitTest lintDebug
.\gradlew.bat assembleDebug assembleDebugAndroidTest assembleRelease
python tools/run_device_tests.py emulator-5554
```

设备测试助手会安装调试 APK，将正式 APK 放进调试应用私有缓存作为签名验证样本，运行测试并恢复测试用的安装来源权限。Windows 通过 Base64 传输样本，避免命令管道改变二进制内容。

测试样本位于模拟器的 `Pictures/PhotoCleanTest`。请使用专门的模拟器或测试设备。

## 仍需补充的真机验证

Android 10 逐张删除授权、Android 14 部分照片授权、不同品牌的更新安装界面、外置存储和超大照片 / 大相册表现，仍需在对应设备上验证。

测试失败或设备行为差异欢迎通过 Issues 反馈系统版本、机型与复现步骤，请使用合成图片。
