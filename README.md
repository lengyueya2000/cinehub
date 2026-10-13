# 影枢 CineHub

Android 视频聚合播放器。核心产品逻辑只有一句话：**用户自带播放源，应用把内容抓回来自己重新渲染画面**。

它不是网页套壳。源站只提供数据（片名、封面、分集、直链），抓回来后被归一化成影枢自己的数据模型，由本机的 Media3 / ExoPlayer 渲染画面。所以用户看到的界面、播放器、选集抽屉，全部是影枢的，不是别人的网页。

- UI 概念稿与设计说明：[design/README.md](design/README.md)
- 设计系统 Master：[design-system/cinehub/MASTER.md](design-system/cinehub/MASTER.md)
- 成品截图：[design/shots/](design/shots/)（`app-01-home.png` … `app-09-source-saved.png`）
- 下载：到 [Releases](https://github.com/lengyueya2000/cinehub/releases) 取签名的 `app-release.apk`

---

## 一、快速开始

```bash
./gradlew :app:assembleDebug        # 调试包
./gradlew :app:assembleRelease      # 发布包（需要签名凭据，见下）
```

产物在 `app/build/outputs/apk/{debug,release}/`。

安装到已连接的设备 / 模拟器：

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n net.cinehub/.MainActivity
```

首次构建前需要在 `local.properties` 里指定 SDK 路径（该文件已 gitignore）：

```properties
sdk.dir=/path/to/android-sdk
```

### 构建与签名

Release 包的签名凭据放在仓库根目录的 `keystore.properties`（已 gitignore，不入库）：

```properties
storeFile=.signing/cinehub-release.jks
storePassword=…
keyAlias=cinehub
keyPassword=…
```

**这个文件不存在时 `assembleRelease` 会直接失败**，不会退化成一个装不上的未签名 APK。发布流程里最容易出错的就是签名这一步（Android 只允许同证书的包覆盖安装，证书一换老用户就装不上更新），所以宁可让构建停住。

换机器时用 `keytool` 重建：

```bash
mkdir -p .signing
keytool -genkeypair -keystore .signing/cinehub-release.jks -alias cinehub \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -storepass '<口令>' -keypass '<口令>' \
  -dname "CN=CineHub, OU=Release, O=lengyueya2000, L=Beijing, ST=Beijing, C=CN"
```

当前发布证书的 SHA-256 指纹：`10:61:03:3E:DF:BB:36:4B:69:B8:56:18:26:3B:B0:ED:67:8C:B5:9C:0A:C7:86:74:ED:90:32:4A:FD:9C:F9:E`

Release 开了 R8 混淆 + 资源裁剪（`app/proguard-rules.pro` 里有 kotlinx-serialization 的 keep 规则，以及持久化模型的 keep —— 后者是为了让 debug 包升级到 release 包时，用户已存下的播放源还能解出来）。打包后 2.6 MB，debug 包 26 MB。

如果依赖已经在本地 Gradle 缓存里，加 `--offline` 会明显更快。


---

## 二、工程结构

```
app/src/main/java/net/cinehub/
├─ MainActivity.kt              单 Activity，edge-to-edge
├─ data/
│  ├─ SourceRepository.kt       播放源持久化（DataStore Preferences）+ 五个内置源
│  ├─ MediaRepository.kt        跨源聚合、健康检测、直链解析
│  ├─ model/
│  │  ├─ Models.kt              Media / Episode / PlayLine / AggregatedMedia / Health
│  │  ├─ StreamResolution.kt    直链解析结果 + VideoPlatforms + StreamUrls
│  │  └─ SourceConfig.kt        SourceConfig / SourceKind / HtmlRules / ApiRules
│  ├─ net/Http.kt               共用 OkHttp 客户端、UA、鉴权头、失败原因中文化
│  └─ source/
│     ├─ SourceAdapter.kt       适配器接口 + 工厂
│     ├─ M3uAdapter.kt          m3u / m3u8 播放列表
│     ├─ MaccmsAdapter.kt       苹果 CMS JSON 接口（vod_* 字段）
│     └─ HtmlAdapter.kt         网页解析 + DefaultRules + player_aaaa 直链解析
└─ ui/
   ├─ AppViewModel.kt           全部状态（源 / 首页 / 搜索 / 详情 / 播放）
   ├─ CineHubApp.kt             路由 + 底部 Tab + 系统返回键逐级回退
   ├─ components/Atoms.kt       Pill / StatusDot / PosterCard / SectionHead
   ├─ theme/                    Tokens（T）/ Dimens（Sp、R、Fs）/ Theme
   └─ screens/                  Home / Search / Detail / Player / Source
```

技术栈：Kotlin 2.0.21 · AGP 8.7.3 · Jetpack Compose（BOM 2024.10.01）· Material3 · Media3/ExoPlayer 1.4.1（HLS + OkHttp datasource）· OkHttp 4.12.0 · jsoup 1.18.1 · Coil 2.7.0 · DataStore Preferences 1.1.7 · kotlinx-serialization-json 1.6.3。`compileSdk 35` / `minSdk 24`，namespace 与 applicationId 均为 `net.cinehub`。

两点实现取舍，避免看依赖清单时产生误会：

- 路由是 `CineHubApp.kt` 里一个手写的 `sealed interface Route`（Home / Search / Detail / Player / Sources），配 `BackHandler` 做逐级回退。`navigation-compose` 在依赖里但没被用到 —— 只有五个屏、状态又都在一个 ViewModel 里，手写路由比引一套 NavHost 更直接。
- 播放器用的是 `DefaultHttpDataSource`（`PlayerScreen.kt`），不是 OkHttp 那个 datasource。抓取页面走 OkHttp，播放流走 Media3 自带实现，两者互不干扰。


---

## 三、播放源：四种接入方式

用户加源不用等应用发版。`SourceConfig.kind` 决定用哪个适配器：

| `SourceKind` | 适配器 | 适用对象 |
|---|---|---|
| `M3u` | `M3uAdapter` | 任何 m3u / m3u8 直链或列表。最通用，不依赖站点结构，对方改版也不会失效 |
| `JsonApi` | `MaccmsAdapter` | 苹果 CMS（maccms）标准接口 `api.php/provide/vod`，字段固定为 `vod_*`，一个实现覆盖绝大多数「JSON 接口」型源 |
| `Html` | `HtmlAdapter` | 网页解析：按 CSS 选择器抓列表页与详情页，选择器存在 `SourceConfig.html` 里，用户可改 |
| `AiMovie` | `AiMovieAdapter` | 带签名鉴权的前端应用（如 dongpiandi.ai）。静态页没有内容，必须按站点自己的接口协议取数据：请求要 HMAC 签名、取播放地址要带站点下发的匿名会话。密钥是站点前端公开分发的常量，照它的协议发请求，不猜算法、不绕过校验 |

### 内置的五个源

| 优先级 | 名称 | 地址 | 类型 | 说明 |
|---|---|---|---|---|
| 1 | 4K影视 | `https://4kvm.net` | 网页解析 | 用户提供。详情页即播放页，直链由站点自己的 WASM 现算签名，故不解析（`resolveMode = clientScript`） |
| 2 | 北觅影视 | `https://v.luttt.com` | 网页解析 | 用户提供。苹果 CMS 模板站，走 `player_aaaa` 直链解析 |
| 3 | 西瓜影院 | `https://xiguazx.cc` | JSON 接口 | 用户提供。标准 maccms API，10 万+ 片库 |
| 4 | 懂片帝AI | `https://dongpiandi.ai` | 签名接口 | 用户提供。带签名鉴权的前端应用：照它的协议取数据，不按 CSS 选择器抓 |
| 5 | 演示源 | `https://test-streams.mux.dev/.../x36xhzz.m3u8` | M3U 列表 | 公开测试流，用来验证「本机解码」这条链路真的通 |

源可以增、删、启用/停用、调优先级（数字越小越优先）。加源时**先测试、后保存**：点「测试并保存」只做连通性探测，探测通过后按钮变成「保存并启用」，任何字段改动都会让上一次的探测结果作废 —— 这样反复点「测试」不会往列表里灌重复源。

内置源被删掉后不需要重装：「播放源管理」页会在列表上方提示「有 N 个内置源不在列表里」，点「补回」按 id 补回缺失的那几个（`SourceRepository.restoreDefaults()`），你自己添加的源和已有的启停 / 排序都不受影响。

---

## 四、三件值得单独说的事

### 1. 苹果 CMS 的 `player_aaaa`

`v.luttt.com` 这类站点用的是苹果 CMS 默认模板，它会把真实播放地址写进播放页里一个页面级 JS 对象：

```js
var player_aaaa = { "flag":"...", "url":"https://.../index.m3u8", ... }
```

这是**公开的页面数据**，不是绕过登录，所以 `HtmlAdapter` 照读。但它被限制在显式开关后面：

```kotlin
val resolveMode: String = MODE_NONE            // 默认不解析
const val MODE_MACCMS_PLAYER = "maccmsPlayer"  // 声明了才去读 player_aaaa
const val MODE_CLIENT_SCRIPT = "clientScript"  // 地址由站点脚本现算，不解析
const val MODE_SESSION = "session"             // 只对登录用户开放，不代为登录
```

只有 `DefaultRules.maccms` / `indexPhp` 声明了 `MODE_MACCMS_PLAYER`，`DefaultRules.k4` 声明 `MODE_CLIENT_SCRIPT`。没配规则的站点留在 `none`。

### 2. 「拿不到直链」分几种，不是一个可空字符串

`StreamResolution` 是密封接口，因为每种情况对用户意味着完全不同的下一步 —— 提示语混为一谈等于没提示：

| 结果 | 含义 | 播放器里的说法 |
|---|---|---|
| `Ready(url)` | 拿到了能直接交给 ExoPlayer 的地址 | 正常播放 |
| `ThirdPartyPage(pageUrl, platform)` | 源给的是腾讯视频 / 优酷 / 爱奇艺 / B 站那类平台的播放页 | 「这个源给的是腾讯视频的播放页，不是视频文件本身。影枢只在本机解码直链，不会去解析、转码别人的页面。」 |
| `NeedsSession` | 直链只对登录用户开放 —— 填自己的凭证有可能走通 | 「直链只对登录用户开放，影枢不代为登录。可以在「播放源管理」里填入你自己的站点凭证，让影枢只做转发。」 |
| `NeedsClientScript` | 地址由源站自己的页面脚本现算（WASM 签名），不执行脚本就构造不出请求 —— **填凭证也没用** | 「直链地址由源站页面里的脚本现算（带签名），不执行它的脚本就构造不出请求。影枢不执行站点脚本、也不逆它的签名，所以这里只能如实说拿不到 —— 填凭证也解决不了这一步。」 |
| `Failed(reason)` | 规则缺失、模板改版、网络错误 | 「解析直链失败：<原因>」 |

四种「拿不到」的情况下，面板都会同时给出「重试」和「用右侧的源切换 →」，右侧的切源按钮在任何状态下都可用。点播放会**立刻**切进播放器并显示「正在解析直链…」，解析完再换成结论 —— 点下去就有反馈，不会出现「点了没反应」的观感。

成因由**适配器自己**判断（只有它知道自己那个站的规则），`MediaRepository` 只负责在适配器抛异常时兜底。早先这里按 `episodeUrl.startsWith("http")` 归因，结果把「源自己的播放页」也误报成「第三方平台的播放页」—— 4K影视 就是这么被冤枉的。

判断第三方平台用域名白名单（`VideoPlatforms.platformOf`：`v.qq.com` / `youku.com` / `iqiyi.com` / `bilibili.com` …），不是看它是不是 http 地址。

`MaccmsAdapter` 只放行媒体后缀的地址（`.m3u8 .mp4 .mkv .flv .ts .mpd .mov`），所以 `xiguazx.cc` 返回的第三方播放页 URL 永远不会被当作流交给播放器（那只会让 ExoPlayer 抛一个用户看不懂的错）。

### 3. 4K影视 的直链是 WASM 现算的

这是排查「点了播放没反应」时查清的机制，记录在此以免下次重复踩：

- 播放页 `/play/{id}` 的静态 HTML 里**零**出现 `m3u8` / `.mp4` / `player_aaaa` / `videoUrl` / `playUrl`。
- 真实地址由 `/static/wasm/nbmovie_wasm.<hash>.js` 导出的 `build_play_url(dataid, secret_key, quality, play_key)` 现算，再 `fetch(url)`。参数来自页面：`dataid` 是分集锚点上的 `dataid` 属性，`secret_key` 是 href 里 `/play/` 之后那段，`play_key` 是页面里的 `window.myuserlink`（匿名时是页面生成的一串 base64）。
- 把它跑出来长这样：`/video/play?p=40112&v=ch4n6z3tg&q=1080&s=<32位签名>&t=<毫秒时间戳>&k=<base64>`，其中 `s` 是 WASM 里算的。
- 即使把地址正确构造出来，服务端仍然回 `{"code":401,"message":"请先登录并开通VIP会员"}`。**所以这个源对匿名用户本来就不给直链**，不是影枢的解析器写错了。

影枢不去执行对方的脚本、也不逆它的签名 —— 那既是在绕对方的反爬，也不稳定。应用做的是把成因说清楚（`NeedsClientScript`），并让用户能一键切到能给出直链的源。

`Http.failureReason()` 同样把状态码翻成人话：`567 → 安全策略拦截`、`403 → 拒绝访问`、`404 → 地址不存在`、`429 → 请求过于频繁`、`5xx → 站点异常 N`。源卡片上的红色标签因此是有信息量的，不是一句「连接失败」了事。

### 4. 懂片帝AI 是带签名鉴权的前端应用

v1.0.4 之前它按「网页解析」接入，但它是带签名鉴权的前端应用 —— 静态页里没有内容，按 CSS 选择器什么都抓不到，所以搜索里它一直哑火。v1.0.4 给它单写了 `AiMovieAdapter`，按站点自己的接口协议取数据：

- **请求签名**：站点前端对每个请求算 HMAC-SHA256（`msg = "{METHOD}\n{pathname}{search}\n{timestamp_ms}\n{nonce}"`），密钥是站前端公开分发的常量。影枢照同一套协议发请求，不去猜、也不去破任何校验算法。
- **匿名会话**：`POST /v1/users/anonymous` 建一个站点自己的匿名账号并 `Set-Cookie`，这就是前端未登录访客的默认流程。影枢不代为登录任何人的账号，只用站点发给本机的匿名会话。
- **直链**：`GET /v1/playback/entry/{variant}` 返回的 `line_options[]` 里，`url_kind == m3u8` 且本身以 `http` 开头的条目能直接交给 ExoPlayer；`resolve://` 票证地址（还要站点服务端二次解析、额外花额度）影枢不碰，如实说「拿不到」。
- **额度保护**：取一集的线路要花解析额度（实测每集 3 万 token，匿名每日 50 万），所以详情页只列分集、不预取每集线路，点哪一集才在播放时解析 —— 打开一部 22 集的剧不会把额度刷掉一半。
- **线路探活**：站点给的几十个备份很多已经 404，按它的标记挑一条就播只会看到「Source error」。所以播放前对前几条真的探一下，只把能取到 m3u8 正文的那条交给 ExoPlayer。

### 5. 拿到了直链不等于出画面 —— 播放器这侧还有三件事

直链解析成功只是前半段。播放器这侧没配好，用户看到的仍然是「点了播放后一个不动的黑屏」，跟拿不到直链长得一模一样 —— 所以这三处是分开处理的：

| 处理 | 原因 |
|---|---|
| 数据源带 `Referer` / `Origin`（取自 `Playing.sourceBaseUrl`） | 源站 CDN 常按 Referer 放行。宿主 `curl` 不带 Referer 也返回 200，不代表设备侧、更不代表所有 CDN 节点都放行 —— 带上源站根地址是最省事且正确的做法 |
| HLS 走 `setAllowChunklessPreparation(true)` | 完整的 1 小时分段列表有十几万行，默认准备要把它整个拉完才起播。允许 chunkless 后拿到 master playlist 就能开始解码，起播快得多 |
| 监听 `onPlaybackStateChanged` / `onPlayerError` | 首帧之前显示「缓冲中…」、失败时把 `PlaybackException` 的 message 显示出来并给「重试」。不这样做的话，用户只能靠猜是网络慢还是地址坏了。 |

### 安全边界（刻意的约束）

应用**不绕过任何站点的鉴权**。它只转发用户自己粘贴的凭证（`authToken` + `AuthMode.Cookie / Bearer`），不代替用户登录，不去猜对方的校验算法 —— 能看什么取决于用户在该站自己拥有的权限。拿不到直链时它明说拿不到，不假装能播。

应用本身不托管、不存储、不分发任何视频内容，也不内置任何资源站：仓库里带的五个源地址都是用户提供的公开站点，或公开测试流（`test-streams.mux.dev`）。加什么源、看什么内容，由使用者自己决定。

---

## 五、跨源聚合与续播

- **搜索**并发打所有启用的源，结果按 `Media.key` 归并：**标题（去空白与标点）+ 年份**。一部片只出一条，展开能看到它在每个源的可播性徽标（青色 = 播放源语义色）。
  - 归并键去标点是因为同一部片在各源的写法差别很大：「暗影蜘蛛侠 黑白版」/「暗影蜘蛛侠（黑白版）」/「暗影蜘蛛侠：黑白版」应该合并成一条。
  - 同一个源对同一部片返回多条（多线路）时，归并后每个源只保留一条徽标，否则界面上会出现两个一模一样的源 chip。
- **切源不丢进度**：播放位置存在 `AppViewModel._resumePosition` 里，详情页和播放器里换源时带过去。播放器底栏写着「播放进度已跨源同步 / 切换源不丢进度」。

---

## 六、已知环境限制

这两条是实测到的、与代码无关的外部限制，记录在此以免被误当成 bug：

| 现象 | 结论 |
|---|---|
| `v.luttt.com` 从模拟器返回 **HTTP 567**（腾讯 EdgeOne 安全拦截页），但从宿主机 `curl` 返回 200 —— 即使两者出口 IP 相同 | 设备侧网络环境被拦。换 UA、补全 Chrome 请求头、退 HTTP/1.1、锁 TLS 1.2 均无效（宿主 curl 8.21/Schannel 通，设备 curl 8.0.1/BoringSSL 不通）。应用如实显示为「连接失败 · 安全策略拦截 · 不可用」，不假装可用 |
| `xiguazx.cc` 的图片主机 `img.bwcgee.cn` 对封面返回 **403**（带 Referer、换 https 都不行） | 该源搜索结果行的封面会留空。详情页用的 `vcover-vt-pic.puui.qpic.cn` 正常返回 200 |

另外，`西瓜影院` 的播放线路指向 `m.yichengwlkj.com` 这类 Next.js 客户端应用，服务端渲染的 HTML 里**没有** `__NEXT_DATA__`，也**零**出现 `m3u8` / `videoUrl` / `playUrl`。不执行客户端 JS 就拿不到地址，所以该源被正确归类为「拿不到直链」，而不是硬塞一个坏地址给播放器。（`西瓜影院` 的 `vod_play_url` 给的是腾讯 / 优酷 / B 站 / 爱奇艺 的播放页，所以点播放时提示的是「这个源给的是腾讯视频的播放页」。）

---

## 七、在模拟器上手工验证

```bash
adb shell pm clear net.cinehub && adb shell am start -n net.cinehub/.MainActivity
```

三点工具上的坑：

1. `adb shell input text` **不能注入非 ASCII**（`InputShellCommand.sendText` 会抛 `NullPointerException`）。要测中文关键词得装一个 ADBKeyboard（`com.android.adbkeyboard/.AdbIME`），并先让输入框拿到焦点，再 `am broadcast -a ADB_INPUT_TEXT --es msg "功夫女足"`。装完还要 `settings put secure enabled_input_methods "com.android.adbkeyboard/.AdbIME:<原来的输入法>"`，光 `ime enable` 在有些模拟器上选不中。
2. 别凭肉眼估坐标点。用 `adb shell uiautomator dump` 拿真实 bounds 再点 —— Compose 的命中区域和视觉位置经常差几十像素。
3. `uiautomator dump /sdcard/u.xml` 在 Git Bash 下会被 MSYS 路径转换改写成 `G:/git/Git/sdcard/u.xml`，导致 `adb pull` 找不到文件。用 `/data/local/tmp/u.xml` 当落点，或者 `MSYS_NO_PATHCONV=1`。

验证「本机解码」这条链路：把除「演示源」外的源全部停用 → 重启应用 → 首页 Hero 会变成演示源 → 进去点「播放 正片」。播放器应显示真实画面与 `00:0x / 10:34` 的进度（该测试流为 HLS，约 10 分 34 秒），这就证明画面是 Media3 在本机解出来的，而不是套了谁的网页。测完记得把源都打开 —— 停用是持久化的。

---

## 八、许可

[MIT](LICENSE)。仅限个人学习与技术研究使用，请遵守你所在地区的法律法规以及各内容站点的服务条款。

