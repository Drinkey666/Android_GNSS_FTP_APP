# VMF3 本地账号配置

工程已将原先写在 `MainActivity.kt` 中的 VMF3 账号和密码迁移到工程根目录的 **`vmf3.local.properties`**。该文件已被 Git 忽略，不上传到 GitHub。

## 本机使用

原账号已在本机迁移，不需要重新填写。正常同步 Gradle、编译并安装新 APK 后，VMF3 下载仍使用原账号；下载地址、文件筛选、认证方式和 PPP 参数没有修改。

## 新电脑或从 GitHub 克隆后

1. 将根目录 `vmf3.local.properties.example` 复制为 `vmf3.local.properties`。
2. 填写自己的 `vmf3.username` 和 `vmf3.password`，不要加引号。
3. 在 Android Studio 同步 Gradle，重新编译、安装 APK。

配置文件按 Java Properties 格式读取，值中的反斜杠应写为两个反斜杠。非 ASCII 字符可以使用 `\uXXXX` 表示。示例文件只包含空字段，可安全上传；真实配置不要提交或发送给他人。

未提供本地配置时仍能编译工程，两个 BuildConfig 字段默认为空，界面沿用原有“未配置 VMF3 账号密码，跳过对流层预报下载”提示。

## 工作方式

`app/build.gradle.kts` 在构建时读取本地配置，生成 `BuildConfig.VMF3_USERNAME` / `BuildConfig.VMF3_PASSWORD`。界面将这两个值传给现有 VMF3 下载函数，不再在源码中写死个人凭据。

## 安全边界

这次处理解决的是**凭据不进入 Git 仓库**，不是 APK 的密钥防提取方案。凭据仍会进入本机生成的 BuildConfig 和 APK，不能将 APK 当作安全的秘密存储。

不要上传 `app/build`、APK、包含真实配置的压缩工程或生成的 BuildConfig。若要公开分发 APK，应另行实现用户输入账号并按设备安全方案存储，或使用服务端认证代理，而不是把个人账号打包给所有用户。

`.gitignore` 也排除了 Kotlin/Python 缓存、历史同步备份和本地构建日志；这些本机辅助文件仍保留在磁盘，只是不提交。
