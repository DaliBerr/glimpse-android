# 应用内更新

入口：**设置 → 应用更新**。流程参考 [Vibe Halo](https://github.com/DaliBerr/Vibe-Halo) 的 GitHub 正式版本分发方式，使用 Android 标准 API 实现。

## 用户流程

1. 点击“检查更新”，读取本仓库最新正式 Release。
2. 查看可用版本和大小，由用户决定是否下载。
3. 下载完成后校验 SHA-256、包名、版本、构建号、签名证书和最低安卓版本。
4. 首次安装需先授权“允许来自此来源的应用”。
5. 点击“安装更新”，打开系统安装界面，由用户确认。

下载可取消。校验完成的包保存在私有缓存，下次启动会重新校验；未完成下载需要重新发起。调试版使用独立 ID，不检查正式更新。

## 发布契约

仓库固定为 `DaliBerr/glimpse-android`，标签为 `v主版本.次版本.修订号`，APK 为 `Glimpse-版本号.apk`。清单附件命名为 `android-update.json`：

```json
{
  "schema": 1,
  "packageName": "com.albumclean.app",
  "versionName": "1.0.1",
  "versionCode": 2,
  "apkName": "Glimpse-1.0.1.apk",
  "size": 123456,
  "sha256": "安装包的64位小写十六进制SHA-256",
  "sourceCommit": "对应的40位Git提交SHA"
}
```

示例大小与摘要为说明用途，正式内容由 `tools/package_release.py` 生成。更新器会同时核对 GitHub Release 附件的大小和摘要。

地址根据固定仓库与经过校验的版本构造，只允许 GitHub API、该仓库附件及 GitHub 下载 CDN 的 HTTPS 地址。单个 APK 上限为 64 MiB。

## 安装约束

- 构建号递增，不允许降级。
- 包名与签名证书必须和当前安装包一致。
- 安装前再次校验缓存文件。
- FileProvider 只开放更新 APK 子目录，并只授予临时读取权限。
- 没有自动检查、自动下载或静默安装。

签名配置与发布步骤见 [开发指南](DEVELOPMENT.md)。
