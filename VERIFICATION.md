# 初版验证记录

以下为改名前 1.0.0 的初版验证记录。当前拾光 1.0.1 的构建、更新功能与测试范围见 [测试说明](docs/TESTING.md)。

初版完成时间：2026-11-15 03:38（Europe/Paris）。

交付版本：相册清理 1.0.0，Kotlin / Jetpack Compose 安卓原生应用；支持 Android 10 及以上。

## 已完成的验证

| 检查 | 结果 |
| --- | --- |
| Debug、签名 Release 构建 | 通过 |
| 规则单元测试 | 8 项通过，0 失败 |
| Android 14 模拟器设备测试 | 3 项通过，0 失败 |
| Android Lint | 0 错误，25 条警告，主要为依赖更新提示、编码建议与工具静态分析提示 |
| Release APK 签名 | APK Signature Scheme v2 校验通过 |
| Release 安装与启动 | 在 720 × 1280 Android 14 模拟器上通过 |
| 四向滑动 | 左 / 下待删除，右 / 上保留，四个方向均实际操作通过 |
| 撤销与清单恢复 | 恢复原来的照片、逐张恢复待删除照片、Activity 重建后记录保留，均通过 |
| 拍摄时间与元数据 | MediaStore 读取、EXIF 补充读取、文件大小、像素尺寸、本地记录持久化通过 |
| 详情缩放 | 实际双击放大至 3.0 倍，查看后不改变整理选择，通过 |
| 系统分享 | 原图系统分享面板实际打开，通过 |
| 外观 | 浅色、深色实际切换及外观设置持久化通过 |
| 取消删除 | 取消应用内确认和系统确认后，原图数量不变、待删除清单保留，通过 |
| 确认删除 | 系统确认后，生成的测试原图数量减少 1、待删除清单清空，通过 |
| 进程重启 | 已保留照片不再出现，通过 |
| 权限撤销 | 安全返回授权页，重新授权后继续工作，通过 |
| 运行时异常日志 | 本轮操作未发现 AndroidRuntime 崩溃记录 |

真正删除的照片已校验为 `PhotoClean_Sample_` 开头的模拟器测试图片。

最终构建命令：

```powershell
.\gradlew.bat connectedDebugAndroidTest testDebugUnitTest lintDebug assembleRelease --console=plain
```

## 交付文件

- `artifacts/相册清理-v1.0.0.apk`：可直接安装的签名发布包。
- `artifacts/相册清理-v1.0.0-源码.zip`：完整可导入源码，包含 Gradle Wrapper、测试、说明和验证记录；不包含本机路径、私有签名密钥或构建缓存。
- `artifacts/SHA256.json`：安装包与源码包的大小及 SHA-256。
- `artifacts/verified-build.log`：最终成功构建日志。
- `artifacts/release-smoke.json`：最终发布包操作检查记录。
- `artifacts/deletion-smoke.log`：实际系统删除及取消、重启、权限撤销检查日志。
- `artifacts/apk-signature.txt`：签名校验结果。
- `artifacts/light.png`、`dark.png`、`detail.png`、`zoomed.png`、`share.png`、`pending.png`、`system-delete.png`：模拟器实际截图。

## 真机验收范围

目前实际运行验证使用 Android 14 模拟器。Android 10 逐张删除授权、Android 14 部分照片授权、外置存储和超大照片的内存表现，仍需在对应真机上验收。

应用中的“撤销”用于正式删除前的选择和待删除清单。系统永久删除完成后，应用不提供原图恢复。
