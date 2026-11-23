# 传送站 TransferHub

把一台安卓手机变成局域网里的“万能传送站”：iPhone、Mac、Windows、Linux、其他安卓设备**不用装任何 App**，扫码后用浏览器就能和它互传文件，也能借它在两台设备之间中转文件。

## 当前功能（v0.1 · MVP）

- 一键启动传送站（前台服务，息屏后继续运行）
- 二维码 / 网址访问，每次启动生成新的访问令牌，同一网络中的陌生人无法访问
- 网页端：多文件上传（带进度和速度）、拖拽上传、下载、自动刷新文件列表、深色模式
- 收到的文件保存在手机的「下载/TransferHub」
- 在 App 内选择本机文件分享给其他设备，也可以从相册、文件管理器等任意 App「分享 → 传送站」
- 中转：A 设备上传，B 设备下载
- 可选：允许网页端删除文件

## 使用方法

1. 手机连接 Wi-Fi，或者打开系统热点让其他设备连进来
2. 打开「传送站」，点击 **启动传送站**
3. 其他设备用相机扫描屏幕上的二维码（或在浏览器输入下面的网址）
4. 在网页上传或下载文件

> 提示：如果手机同时开着热点和 Wi-Fi，界面上会出现多个网络标签，选择对方所在的网络后再扫码。

## 下载

正式版下载页：**https://kalijerry.github.io/local-file-transfer/**

也可以直接用永久链接下载最新正式版：`https://github.com/kalijerry/local-file-transfer/releases/latest/download/TransferHub.apk`

## 分支与发版流程

| 操作 | 会发生什么 |
|---|---|
| 提 PR / 合并进 `main` | 只编译 APK，放在 Actions 的构建产物（Artifacts）里供测试，**不发版** |
| 推送 `vX.Y.Z` 标签 | 编译正式版 APK，自动创建 GitHub Release，下载页随之显示新版本 |
| 修改 `site/` 并合并进 `main` | 重新部署下载页 |

发版步骤（标签必须打在 `main` 上已有的提交上，否则会被拒绝）：

```bash
git checkout main && git pull
```

```bash
git tag v0.1.0
```

```bash
git push origin v0.1.0
```

- 只有严格的 `vX.Y.Z` 格式才会触发发版，`v1.0`、`v1.0.0-beta`、`release-1` 等格式都会被忽略。
- 正式版的 `versionCode = X×1000000 + Y×1000 + Z`，保证新版本总能覆盖安装旧版本。
- 日常构建的版本号为 `0.0.<构建号>-dev`，只用于测试。装过正式版后，不能再覆盖安装开发版（系统不允许降级），需要先卸载。

### 签名

默认使用仓库里的调试密钥（`app/debug.keystore`）签名。要使用正式密钥，在仓库的 **Settings → Secrets and variables → Actions** 中添加以下四项，之后打标签时就会自动用正式密钥签名：

| Secret | 内容 |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | 密钥库文件的 base64（`base64 -w0 release.jks`） |
| `RELEASE_KEYSTORE_PASSWORD` | 密钥库密码 |
| `RELEASE_KEY_ALIAS` | 密钥别名 |
| `RELEASE_KEY_PASSWORD` | 密钥密码 |

> 注意：更换签名密钥后，新版本无法覆盖安装旧签名的版本，需要先卸载。最好在发布第一个正式版之前就决定好用哪把密钥。

### 首次启用下载页

在仓库 **Settings → Pages → Build and deployment → Source** 中选择 **GitHub Actions**，然后在 Actions 页面手动运行一次 *Deploy download page*（之后修改 `site/` 并合并进 `main` 时会自动部署）。

## 本地编译

需要 JDK 17+ 和 Android SDK（API 35）：

```bash
./gradlew assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 技术结构

| 模块 | 说明 |
|---|---|
| `service/HubService.kt` | 前台服务：管理服务器、Wi-Fi 锁、唤醒锁，监听网络变化 |
| `server/TransferServer.kt` | 内置 Ktor (CIO) HTTP 服务器，流式上传和下载 |
| `data/HubRepository.kt` | 传送站文件索引：接收写入 MediaStore，分享文件引用与持久化 |
| `assets/web/index.html` | 给其他设备用的传输网页（无外部依赖，离线可用） |
| `ui/HubScreen.kt` | Jetpack Compose 主界面 |
| `site/` | GitHub Pages 下载页（在浏览器端读取最新 Release） |

### HTTP 接口

所有 `/api` 请求都需要带访问令牌：`?t=<token>`，或者 `X-Hub-Token` 请求头。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 传输网页 |
| GET | `/api/info` | 传送站信息 |
| GET | `/api/files` | 文件列表 |
| GET | `/api/files/{id}` | 下载（`&inline=1` 在浏览器中直接打开） |
| PUT | `/api/upload?name=<文件名>` | 上传，请求体就是文件内容 |
| DELETE | `/api/files/{id}` | 删除（需要在 App 中开启） |

## 路线图

- [x] **阶段 1**：局域网模式 + 网页上传下载 + 二维码
- [ ] **阶段 2**：一键热点（LocalOnlyHotspot），界面上同时显示 Wi-Fi 二维码和网址二维码
- [ ] **阶段 3**：断点续传、文字 / 剪贴板互传、传输记录、多设备管理
- [ ] **阶段 4**：NFC 碰一碰（HCE 模拟标签），无人值守的“嵌入式模式”（开机自启）
- [ ] **阶段 5**：Wi-Fi Direct 高速直连，兼容 LocalSend 协议
