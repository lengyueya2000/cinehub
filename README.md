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
./gradlew :app:testDebugUnitTest    # 单元测试（约 245 例，不碰网络）
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

Release 开了 R8 混淆 + 资源裁剪（`app/proguard-rules.pro` 里有 kotlinx-serialization 的 keep 规则，以及持久化模型的 keep —— 后者是为了让 debug 包升级到 release 包时，用户已存下的播放源还能解出来）。release 包 3 MB 上下、debug 包 20 MB 上下 —— 确切数字每个版本都不同，以发布页上给的为准，这里只给个量级（曾经写过死的 2.8 / 21 MB，和实际对不上）。

如果依赖已经在本地 Gradle 缓存里，加 `--offline` 会明显更快。

### 装不上时按这个顺序查

APK「下载完了但装不上」在三件事上，按概率从高到低排：

1. **签名方案**。Android 7 以下的安装器只认 v1（JAR）签名，而 AGP 在 `minSdkVersion >= 24`
   时会默认关掉 v1 —— 结果就是 v2/v3 校验全过、手机上却一句「解析软件包时出现问题」。
   所以 `app/build.gradle.kts` 里显式写了 `enableV1Signing = true`。别信 `apksigner verify`
   的默认输出，它按包的 minSdk 选校验方案，要显式压低才看得到 v1 的结论：

   ```bash
   apksigner verify -v --min-sdk-version 21 app-release.apk | grep 'V1\|v1'
   # Verified using v1 scheme (JAR signing): true
   ```

2. **证书冲突**。同一包名只允许同证书覆盖安装，`INSTALL_FAILED_UPDATE_INCOMPATIBLE`
   在界面上往往只显示成「未安装应用」。手机里若还留着 debug 签名的那份，必须先卸掉 ——
   卸载会带走用户已添加的播放源和离线缓存，这是无法回避的，只能提前告知。

   ```bash
   adb install -r -d app-release.apk        # 看真实报错，比手机弹窗诚实
   adb shell dumpsys package net.cinehub.app | grep -i 'signatures\|versionCode'
   ```

3. **传输截断**。国内一些网盘/图床会在下载时改字节。装之前核一次 SHA-256：

   ```bash
   certutil -hashfile app-release.apk SHA256      # Windows
   sha256sum app-release.apk                       # Linux / macOS
   ```

   指纹和发布说明里给的对不上，就是包在传输路上被换了，不是签名的问题。

其余的都是系统策略而不是包的问题：Android 8+ 要在「安装未知应用」里给文件管理器放行，
部分国产 ROM 还会额外拦一次「风险应用」（走「仍要安装」）。


---

## 二、工程结构

```
app/src/main/java/net/cinehub/
├─ MainActivity.kt              单 Activity，edge-to-edge
├─ data/
│  ├─ SourceRepository.kt       播放源持久化（DataStore Preferences）+ 内置源种子
│  ├─ MediaRepository.kt        跨源聚合、健康检测、直链解析
│  ├─ WatchRepository.kt        收藏 + 观看记录（同一份 DataStore，按 Media.key）
│  ├─ DownloadRepository.kt     离线缓存：最多 3 集并发、HLS 分片、AES-128 解密、断点续传、写完才改名
│  ├─ DoubanRepository.kt       豆瓣公开榜单抓取 + 按榜落盘缓存
│  ├─ model/
│  │  ├─ Models.kt              Media / Episode / PlayLine / AggregatedMedia / Health + normalizeTitle
│  │  ├─ StreamResolution.kt    直链解析结果 + VideoPlatforms + StreamUrls
│  │  ├─ SourceConfig.kt        SourceConfig / SourceKind / HtmlRules / ApiRules
│  │  ├─ Watch.kt               Favorite / HistoryEntry（含「看不看完」的判据）
│  │  └─ Douban.kt              DoubanItem + 手写的榜单表（DoubanChart）
│  ├─ net/Http.kt               共用 OkHttp 客户端、UA、鉴权头、失败原因中文化
│  ├─ net/BrowserEngine.kt      挂了浏览器验证那道题的源：后台 WebView 同源取数
│  ├─ net/LocalKeyProxy.kt      站点给的密钥不是规范形态时，本机那道收拾密钥的代理
│  └─ source/
│     ├─ SourceAdapter.kt       适配器接口 + 工厂
│     ├─ M3uAdapter.kt          m3u / m3u8 播放列表
│     ├─ MaccmsAdapter.kt       苹果 CMS JSON 接口（vod_* 字段）
│     ├─ HtmlAdapter.kt         网页解析 + DefaultRules + player_aaaa 直链解析
│     ├─ AiMovieAdapter.kt      带签名鉴权的前端应用（懂片帝AI）
│     ├─ PpnixAdapter.kt        WordPress 主题型站点（PPnix）：script 里的分集 + 非规范密钥
│     └─ MinoAdapter.kt         自建签名接口源（MINO 型协议）
└─ ui/
   ├─ AppViewModel.kt           全部状态（源 / 首页 / 搜索 / 详情 / 播放 / 收藏 / 榜单）
   ├─ CineHubApp.kt             路由 + 底部 Tab + 系统返回键逐级回退
   ├─ components/Atoms.kt       Pill / StatusDot / PosterCard / SectionHead
   ├─ theme/                    Tokens（T）/ Dimens（Sp、R、Fs）/ Theme
   └─ screens/                  Home / Search / Detail / Player / Mine / Source
                                Library（豆瓣榜单）/ Downloads / Favorites / History
                                PlaybackSettings / DownloadSettings / SiteBrowser（站点页取链）
```

技术栈：Kotlin 2.0.21 · AGP 8.7.3 · Jetpack Compose（BOM 2024.10.01）· Material3 · Media3/ExoPlayer 1.4.1（HLS + OkHttp datasource）· OkHttp 4.12.0 · jsoup 1.18.1 · Coil 2.7.0 · DataStore Preferences 1.1.7 · kotlinx-serialization-json 1.6.3。`compileSdk 35` / `minSdk 24`，namespace 与 applicationId 均为 `net.cinehub`。

两点实现取舍，避免看依赖清单时产生误会：

- 路由是 `CineHubApp.kt` 里一个手写的 `sealed interface Route`（Home / Search / Detail / Player / Library / Sources / SourceManage / Downloads / Favorites / History），配 `BackHandler` 做逐级回退。十来个屏、状态又都在一个 ViewModel 里，手写路由比引一套 `NavHost` 更直接 —— 所以 `navigation-compose` 那条依赖已经删掉了（它一行都没被用到，留着只是多拽几个传递依赖让 R8 处理）。
- 播放器用的是 `DefaultHttpDataSource`（`PlayerScreen.kt`），不是 OkHttp 那个 datasource。抓取页面走 OkHttp，播放流走 Media3 自带实现，两者互不干扰。


---

## 三、播放源：五种接入方式

用户加源不用等应用发版。`SourceConfig.kind` 决定用哪个适配器：

| `SourceKind` | 适配器 | 适用对象 |
|---|---|---|
| `M3u` | `M3uAdapter` | 任何 m3u / m3u8 直链或列表。最通用，不依赖站点结构，对方改版也不会失效 |
| `JsonApi` | `MaccmsAdapter` | 苹果 CMS（maccms）标准接口 `api.php/provide/vod`，字段固定为 `vod_*`，一个实现覆盖绝大多数「JSON 接口」型源 |
| `Html` | `HtmlAdapter` | 网页解析：按 CSS 选择器抓列表页与详情页，选择器存在 `SourceConfig.html` 里，用户可改 |
| `AiMovie` | `AiMovieAdapter` | 带签名鉴权的前端应用（如内置的懂片帝AI）。静态页没有内容，必须按站点自己的接口协议取数据：请求要 HMAC 签名、取播放地址要带站点下发的匿名会话。密钥是站点前端公开分发给每一个访客的常量，影枢照同一套协议把请求算出来发过去 |
| `MinoApi` | `MinoAdapter` | 自建签名接口（MINO 型 Apple-CMS 后端）。先 ECDH 握手派生签名钥与响应体钥，之后每请求带 HMAC-SHA256 签名头，响应正文可能是 AES-256-GCM 信封；分集给的是 `tk:` 票证，播放时换 play_ticket、再按票据下发的解析接口现算成 m3u8 |
| `PpnixApi` | `PpnixAdapter` | WordPress 主题型站点（如 www.ppnix.com）。前三步是普通网页解析，但分集只存在于详情页末尾那段 `classid/infoid/m3u8=[…]` script 里，且 `#EXT-X-KEY` 给的密钥不是规范形态（32 个 ASCII 字符，真密钥是前 16 个）—— 后者要经 `LocalKeyProxy` 收拾成 16 字节，播放器才解得开 |

### 内置的六个源

内置源的真实地址不在文档里写明：它们直接编进应用，界面上也只显示「内置地址已隐藏」。
用户自己加的源不受影响，照常显示完整地址。

| 优先级 | 名称 | 地址 | 类型 | 说明 |
|---|---|---|---|---|
| 1 | MINO自建 | 内置（已隐藏） | 自建签名接口 | 自有 / 有权限的 Apple-CMS 后端。ECDH 握手 + 每请求 HMAC 签名，分集给的是 `tk:` 票证，播放时换 play_ticket 再经下发的解析接口现算成 m3u8。内容全在 `/api/...` 下，站点根路径本来就回 404，所以健康栏改探服务接口点而不是根路径 |
| 2 | 北觅影视 | 内置（已隐藏） | 网页解析 | 用户提供。苹果 CMS 模板站，走 `player_aaaa` 直链解析 |
| 3 | 4kku | 内置（已隐藏） | 网页解析 | 苹果 CMS 的地址规则配 Tailwind 皮：三条线路，分集锚点不带线路标记，按播放地址尾段拆 |
| 4 | 懂片帝AI | 内置（已隐藏） | 签名接口 | 用户提供。带签名鉴权的前端应用：照它的协议取数据，不按 CSS 选择器抓 |
| 5 | 西瓜影视 | 内置（已隐藏） | JSON 接口 | 苹果 CMS 标准接口，但 `vod_play_url` 里存的是各家平台的播放页，要再过一道麒麟解析器（`ApiRules.resolveMode = qilin`）。标签只写实测站得住的两条：接口自己报的片库量、详情里切出来的线路数 |
| 6 | PPnix | 内置（已隐藏） | 网页解析 | WordPress 主题型站点。分集不在 HTML 里（详情页 `.playlists` 是空 div，由末尾那段 `classid/infoid/m3u8=[…]` script 渲染），且它给的密钥不是规范形态，要经一道本机代理收拾（见下文「PPnix 的密钥」）|

曾经内置过的 `4kvm.net`、旧的那条西瓜线路（id `xiguazx`）、高清点播、FreeOK 与「演示源」已撤下（见 `Seed.retiredIds`：老用户列表里的那几条会按 id 清掉，你自己手动加的源不受影响）。前两条的原因分别是：对匿名用户根本不给直链、接口里根本没有直链（见下文两节）；演示源那条公开测试流只用来验证解码链路，不该占着内置源的一格。高清点播与 FreeOK 的撤下原因是换了默认线路（MINO自建），它们的取数机制（解析站链、浏览器内核过 Cloudflare 题）保留在代码里，用户自己加回同类源照样能用。西瓜影视后来换了接入方式（接口里给的平台页交给解析器）重新内置，**用的是新 id `xiguavod`** —— 复用 `xiguazx` 会让它每次落盘都被 `retiredIds` 静默剔掉。

源可以增、删、启用/停用、调优先级（数字越小越优先）。加源时**先测试、后保存**：点「测试并保存」只做连通性探测，探测通过后按钮变成「保存并启用」，任何字段改动都会让上一次的探测结果作废 —— 这样反复点「测试」不会往列表里灌重复源。

顺序是用户的话，不是实现细节。**长按行首的拖动手柄就能换位置**（松手才落库；拖到一半反悔，拖回原位等于什么都没发生），也可以点某一行的「↑ 上移」。排出来的顺序决定两件事：搜索卡片上源徽标的先后、点卡片默认进哪个源。一次探测到的延迟不再能盖过这个顺序 —— `MediaRepository.search()` 先按 priority 排，只在同位次里比「连不上 / 慢 / 多久回」。

**内置源跟版本走**：升级之后它们在启动时按新版那一份同步（`SourceRepository.syncSeeds()`），不用去点任何按钮。「播放源管理」上方会说一次这次动了什么（补进来几条、按新版重写了几条、撤下几条），点「知道了」就消失。

覆盖的边界是这一节的规矩：

| 跟着版本变（会被覆盖） | 是你自己的（一条都不动） |
|---|---|
| 地址、接入方式、类型、标签、健康探点、解析规则、要不要藏地址 | 启停开关、你拖出来的位次、你填的站点凭证、这条源是哪天加的；以及**你自己添加的每一条源** |

两件事值得单独说清楚，它们都是「别替用户做决定」：

- **启停一起覆盖**，等于每次升级把你关掉的线路悄悄打开；**位次一起覆盖**，等于新版本写的第一名自动成为默认线路（priority == 1 就是界面上那颗「默认」芯片，也是进播放器时挑的那一条）。所以这两样一律按盘上那份留着。
- **删掉的内置源会自动补回**，并且补到队尾（按 `Seed.sources` 里的相对顺序），不带着种子里写死的名次挤进第一格。所以「删了还会回来」这件事是这一版的设计，不是 bug：内置源是产品自带的线路，不是一份可改配置。要长期不要某一条，把它**停用**（停用会被保留），或者等它进 `retiredIds`。

这一版起不再有「这个安装见过哪些内置源 id」那份账（原 `syncNewSeeds` 的判据）：既然缺的一律补回，分辨「你删的」和「版本没带」就没有意义了。

**内置源那一行的「编辑」换成了「凭证」**：名称、地址、接入方式、解析路径都是版本的内容，改完下次启动就被覆盖回去，留一个叫「编辑」的按钮只是在骗用户。需要一份自己的变体（换域名、换线路、改规则）就自己添加一条源 —— 用户添加的源任何同步路径都不碰，改一次就一直是他那份。

但**站点凭证是例外**：它是你自己的东西，同步明确保留（见上表右栏），所以内置源那一行仍然给一个入口，只是弹层里只剩「站点凭证」和它的两种带法（Cookie / Bearer）—— 那几格内容字段在弹层里不显示，落库时按原配置原样带着。内置源那一行另外保留开关、上移和删除：启停与位次是同步时明确保留的两样，删除则是「这一趟先不要它」。

「播放源管理」里那个「有 N 个内置源不在列表里 · 补回」的提示还留着，但它只管**当场**：你这一趟里删掉的内置源，到下次启动才会自己回来，点一下补回不用重开应用。走的是同一条并表规则，不会出现「手动补的那份和自动同步的那份长得不一样」。你自己添加的源和已有的启停 / 排序在任何一条路上都不受影响。

---

## 四、三件值得单独说的事

### 1. 苹果 CMS 的 `player_aaaa`

苹果 CMS 默认模板的站点会把真实播放地址写进播放页里一个页面级 JS 对象：

```js
var player_aaaa = { "flag":"...", "url":"https://.../index.m3u8", ... }
```

这是**公开的页面数据**，不是绕过登录，所以 `HtmlAdapter` 照读。但它被限制在显式开关后面：

```kotlin
val resolveMode: String = MODE_NONE            // 默认不解析
const val MODE_MACCMS_PLAYER = "maccmsPlayer"  // 声明了才去读 player_aaaa
const val MODE_MACCMS_PARSE = "maccmsParse"    // player_aaaa 给的是凭据，拿去问它自己的解析站
const val MODE_CLIENT_SCRIPT = "clientScript"  // 地址由站点脚本现算，不解析
const val MODE_SESSION = "session"             // 只对登录用户开放，要凭证自己填
```

只有 `DefaultRules.maccms` / `tailwind` 声明了 `MODE_MACCMS_PLAYER`，`DefaultRules.hq` 声明 `MODE_MACCMS_PARSE`。`clientScript` 与 `session` 两档现在没有内置源在用（用过的那条已撤下），规则里留着是给手动添加的站点声明用。没配规则的站点留在 `none`。

### 2. 「拿不到直链」分几种，不是一个可空字符串

`StreamResolution` 是密封接口，因为每种情况对用户意味着完全不同的下一步 —— 提示语混为一谈等于没提示：

| 结果 | 含义 | 播放器里的说法 |
|---|---|---|
| `Ready(url)` | 拿到了能直接交给 ExoPlayer 的地址 | 正常播放 |
| `ThirdPartyPage(pageUrl, platform)` | 源给的是腾讯视频 / 优酷 / 爱奇艺 / B 站那类平台的播放页 | 「这个源给的是腾讯视频的播放页，不是视频文件本身。影枢只在本机解码直链，不会去解析、转码别人的页面。」 |
| `NeedsSession` | 直链只对登录用户开放 —— 填自己的凭证有可能走通 | 「直链只对登录用户开放。可以在「播放源管理」里填入你自己的站点凭证（Cookie / token），影枢带着它去请求。」 |
| `NeedsClientScript` | 地址由源站自己的页面脚本现算（WASM 签名），不执行脚本就构造不出请求 —— **填凭证也没用** | 「直链地址由源站页面里的脚本现算（带签名），不执行它的脚本就构造不出请求。影枢不跑站点脚本，所以这里只能如实说拿不到 —— 填凭证也解决不了这一步。」 |
| `Failed(reason)` | 规则缺失、模板改版、网络错误 | 「解析直链失败：<原因>」 |

四种「拿不到」的情况下，面板都会同时给出「重试」和「用右侧的源切换 →」，右侧的切源按钮在任何状态下都可用。点播放会**立刻**切进播放器并显示「正在解析直链…」，解析完再换成结论 —— 点下去就有反馈，不会出现「点了没反应」的观感。

成因由**适配器自己**判断（只有它知道自己那个站的规则），`MediaRepository` 只负责在适配器抛异常时兜底。早先这里按 `episodeUrl.startsWith("http")` 归因，结果把「源自己的播放页」也误报成「第三方平台的播放页」—— 4K影视 就是这么被冤枉的。

判断第三方平台用域名白名单（`VideoPlatforms.platformOf`：`v.qq.com` / `youku.com` / `iqiyi.com` / `bilibili.com` …），不是看它是不是 http 地址。

`MaccmsAdapter` 只放行媒体后缀的地址（`.m3u8 .mp4 .mkv .flv .ts .mpd .mov`），所以 `xiguazx.cc` 返回的第三方播放页 URL 永远不会被当作流交给播放器（那只会让 ExoPlayer 抛一个用户看不懂的错）。

### 3. 地址不在 HTML 里的两种站：4K影视 现算签名，高清点播 交给解析站

排查「点了播放没反应」时查清的机制，记录在此以免下次重复踩。4kvm 已不再内置，但这一类站点的判断（`resolveMode = clientScript`）仍然留着：

- 播放页 `/play/{id}` 的静态 HTML 里**零**出现 `m3u8` / `.mp4` / `player_aaaa` / `videoUrl` / `playUrl`。
- 真实地址由 `/static/wasm/nbmovie_wasm.<hash>.js` 导出的 `build_play_url(dataid, secret_key, quality, play_key)` 现算，再 `fetch(url)`。参数来自页面：`dataid` 是分集锚点上的 `dataid` 属性，`secret_key` 是 href 里 `/play/` 之后那段，`play_key` 是页面里的 `window.myuserlink`（匿名时是页面生成的一串 base64）。
- 把它跑出来长这样：`/video/play?p=40112&v=ch4n6z3tg&q=1080&s=<32位签名>&t=<毫秒时间戳>&k=<base64>`，其中 `s` 是 WASM 里算的。
- 即使把地址正确构造出来，服务端仍然回 `{"code":401,"message":"请先登录并开通VIP会员"}`。**所以这个源对匿名用户本来就不给直链**，不是影枢的解析器写错了。

影枢不执行对方的脚本：那是别人的页面逻辑，跑不跑得住全看它哪天改版。应用做的是把成因说清楚（`NeedsClientScript`），并让用户能一键切到能给出直链的源。

曾经内置的**高清点播**是另一形态。它和 4kvm 的区别值得写清楚，因为「读 `player_aaaa`」这条看着通用的路正是在这里断的：

- 它的播放页**有** `player_aaaa`，`encrypt` 还是 `0`，`url` 字段也确实有值 —— 照 `MODE_MACCMS_PLAYER` 读，字段一个都不缺。
- 但那个值不是地址。一部片 13 条线路逐条实测：`from` 为 `qq` / `youku` 的给的是平台播放页，剩下的 `PK` / `PJ` / `PD` / `lzm3u8` 之类给的全是一串 base64url 样子的凭据（`tMdfce8tdq93oJKE2…`），没有一条以 `http` 开头。
- 站点自己怎么用这串凭据：`/static/js/player.js` 见 `player_list[from].ps == "1"` 就把线路名改写成 `parse`，于是加载的是 `/static/player/parse.js`（`PA.js`、`PK.js` 这些实测 404 —— 它们从来不被请求）。`parse.js` 明写着那张解析站的 iframe：`?url=<凭据>&next=//<域名><下一集路径>&title=<页面标题第一段>`。iframe 里那颗 344 KB、jsjiami.com.v7 混淆过的脚本把**自己 query string 去掉开头 `url=` 之后的整串**当签名原文，POST 给同站 `player/api.php`；响应的 `url` 还是密文，AES 的 key/iv 由同一次响应下发（各自再套一层 RC4）。
- `MaccmsParseResolver` 走的就是这条链，`resolveMode = maccmsParse`：MD5 + RC4 + AES-128-CBC 全是 JDK 自带的，没为它加任何依赖。三处容易抄错的地方都拿 node 探针按「中间某集 / 最后一集 / 只有一集的电影」三种页面对过 —— 最难发现的一处是：脚本会把原文里第一个 `.html` 之后全砍掉，剧集因此切在下一集的路径上，而**最后一集和电影**没有东西可砍，`&title=<片名>` 就留在原文里参与签名，少了它恰恰只在这两种页面上签错。解析站的域名也不写死在 APK 里，运行时从 `parse.js` 读（那是个 punycode 中文域名，这类站换域名是常事）。
- 它排在最前那条线路叫「推荐」（`data-form="PB"`，旁边还有「推荐L」「推荐K」两条是别的线路），
  影枢放不出来：同一集同一线路，官网播放器放得出来，这里拿到的却是一份「开头几条 `#EXTINF` 后面不带地址、
  接着是几条图片 CDN 的 `.image`」的清单，ExoPlayer 报 Source error。两边为什么不一样**还没定案**
  （要同一集两边各抓一份清单对比）。在此之前这条直接不列出来（`HtmlRules.lineDrop`，名字**正好等于**才舍）：
  留在选单里只是让用户每次起播都白等一次失败，而自动换源也照样会走到它。
- 它的反爬形态记一条，别误判成「源接不进来」：`/bofang/` 那一页会随机先回 403，正文只有一句 `window.location.href = "<同一条地址>"` 并 `Set-Cookie`；带着这份 Cookie 再要同一条地址就是 200（实测同一集不带 cookie 连要两次都是 403，带着就一直 200）。分类页 / 搜索页 / 详情页没有这道题。
- 被它判成可疑之后换的是另一道墙：**任何页面都先回 403，正文只有一张「滑动验证」的空壳页**。这道题影枢自己答：`SlideChallenge` 读它自己那份 `huadong_*.js` 里现给的 `key` / `value` 和交卷地址（不写死，脚本每次下发的都不一样），按脚本那套 `stringtoHex` —— 逐字符取码点加一再拼成十进制串 —— 再 md5 一次打过去；答对了它才 `Set-Cookie` 落下放行用的那颗。**解开的是 cookie，不是 IP**（实测：刚答完题，同一台出口 IP 不带这颗 cookie 再要首页，照样 403）。cookie 按 host 记在 `Http` 那份内存 jar 里（不落盘，它本来也只活两小时），之后对同一个站的请求都带上，A 站的绝不带进 B 站。一台站三分钟之内只答一次 —— 反复交卷就是把限流踩深的东西。答过了还是不通，那句 403 才照实说「影枢答过一次还是没过」。
- 还有一条实测到的量：**同一个 IP 连着打十几个请求，站点会整片改回 429**（过一会儿又改回 403 那道题）。
自动重试不因此提前换源 —— 429 是这个源对**这一次**的回答，同源的下一条线路往往就给得出，所以规则是「先把这一个源的线路走完，再换下一个源」；只有写在规则上的那几种（地址要站点脚本现算 / 要凭证 / 没配解析规则）才直接跳源，因为那种换线路不会有第二个答案。

`Http.failureReason()` 同样把状态码翻成人话：`567 → 安全策略拦截`、`403 → 拒绝访问`、`404 → 地址不存在`、`429 → 请求过于频繁`、`5xx → 站点异常 N`。源卡片上的红色标签因此是有信息量的，不是一句「连接失败」了事。

曾经内置的 **FreeOK** 是第三种墙，和前两种都不一样：题不在站点自己身上，而在它前面那道 CDN 反爬服务里。Cloudflare 的 managed challenge 对纯 HTTP 客户端一律回 403 `Just a moment...` —— 每一个路径都是，把请求头拼得再像浏览器也是。**差的不是头，是有没有真的执行它那段题脚本。** 这一类不靠伪造过：抄来的 `cf_clearance` 绑的就是答题那台浏览器的 UA + 出口 IP + TLS 指纹，换一次网络就废。所以 `BrowserEngine` 走的是让引擎自己答：一颗**后台 WebView** 先把这一源打开一次，题由 Chromium 自己做完，`cf_clearance` 落在它自己的 cookie 里（和「站点页取链」共用同一份 `CookieManager`，落盘、跨启动有效），之后每一趟取数就是**同源** `fetch()` 去问它本来就在问的那个接口 —— 不新增权限、不碰对方的验证逻辑，只是终于有了对方要求的那件事：一个真的浏览器。

三条由这颗引擎的形状决定的约束写在那儿，别当它是免费的：只能问当前文档自己那个源（跨源被 CORS 掐掉，所以要先把文档挪过去）、全应用排队走一颗（并发打进去只会把题重做几遍）、第一次按十几秒算。所以只有源配置上 `needsBrowser = true` 的那一条走这条路，其余源照旧走 OkHttp 一点不变；而它目前只对 JSON 接口型源生效 —— 网页解析那类要的是渲染完的 DOM，不是这份正文，换过去得另搭一层。

实测这一条走通了：release 包（R8 混淆过，JS 桥的 keep 规则见 `proguard-rules.pro`）在模拟器上「查一次」把 FreeOK 列进了可换的源、切过去之后 `ac=detail` 给出五条线路、播放器拿到了真流并出过画面；源健康栏量到 `553ms`（那是题已经答完、cookie 还在的那一次）。

### 4. 懂片帝AI 是带签名鉴权的前端应用

v1.0.4 之前它按「网页解析」接入，但它是带签名鉴权的前端应用 —— 静态页里没有内容，按 CSS 选择器什么都抓不到，所以搜索里它一直哑火。v1.0.4 给它单写了 `AiMovieAdapter`，按站点自己的接口协议取数据：

- **请求签名**：站点前端对每个请求算 HMAC-SHA256（`msg = "{METHOD}\n{pathname}{search}\n{timestamp_ms}\n{nonce}"`），密钥是站前端公开分发的常量。影枢照同一套协议把请求算出来发过去 —— 用的是站点发给任何一个访客的密钥，不是任何人的账号凭据。
- **匿名会话**：`POST /v1/users/anonymous` 建一个站点自己的匿名账号并 `Set-Cookie`，这就是前端未登录访客的默认流程。影枢用的就是站点发给本机的这一份匿名会话。
- **直链**：`GET /v1/playback/entry/{variant}` 返回的 `line_options[]` 里，`url_kind == m3u8` 且本身以 `http` 开头的条目能直接交给 ExoPlayer；`resolve://` 票证地址（还要站点服务端二次解析、额外花额度）影枢不碰，如实说「拿不到」。
- **额度保护**：取一集的线路要花解析额度（实测每集 3 万 token，匿名每日 50 万），所以详情页只列分集、不预取每集线路，点哪一集才在播放时解析 —— 打开一部 22 集的剧不会把额度刷掉一半。
- **线路探活**：站点给的几十个备份很多已经 404，按它的标记挑一条就播只会看到「Source error」。所以播放前对前几条真的探一下，只把能取到 m3u8 正文的那条交给 ExoPlayer。

### 5. PPnix：分集不在 HTML 里，密钥也不规范

PPnix（`www.ppnix.com`）是 WordPress 主题型站点，不是苹果 CMS 模板。浏览 / 搜索 / 详情三步就是普通网页解析，卡住的是另外两件，所以单独写了 `PpnixAdapter`：

- **分集不在 HTML 里。** 详情页的 `.playlists` 是个空 `div`，由页面末尾那段 `<script>classid=2;infoid=8509;m3u8=['1','2',…]</script>` 在浏览器里渲染出来。照 `HtmlRules.episodeSelector` 去 DOM 里找锚点，一条都找不到 —— 界面表现是「这一部没有分集」，而它其实有 47 集。所以 `PpnixScript` 直接解析那段脚本，电影与电视剧靠参数形状区分（电影是 `['1080P']`，电视剧是 `['1'..'47']`）。
- **取链地址是现拼的。** `/info/m3u8/{infoid}/{参数}.m3u8`，参数就是分集那一串。站点对还没上片的那一集回 301 到首页，所以取链前先确认一次「这一集真的有清单」，再决定是给地址还是照实说「这一集还没有片源」。

#### 密钥不是规范形态，所以要一道本机代理

这件事值得单独写，因为它是**播放器层面的障碍**，不是解析层面的：

HLS 规范（RFC 8216）说 `#EXT-X-KEY` 的 `URI` 指向的文件里存的就是原始密钥字节，AES-128 就是 16 字节。PPnix 也给了一个这样的地址，但回的不是 16 字节密钥，而是 **32 个 ASCII 字符**（形如 `ba9bf05693b9fa202d922dd43a08f281`），真正的密钥是它**前 16 个字符**。

播放器不知道这件事。Media3 的 `Aes128DataSource` 把响应原样交给 `SecretKeySpec` —— 32 字节对 AES 是合法长度（那是 AES-256），于是它不报错，安安静静解出一堆乱码，用户看到的是「解析直链失败」或者一个不动画黑屏。而那个类是包级私有的，影枢替换不了它。

所以能干净介入的点只剩一个：**在密钥到达播放器之前，把它换成规范的 16 字节**。这就是 `LocalKeyProxy` 的全部职责：

```
播放器 ──┬─→ 本机代理 ──→ PPnix（要 m3u8 / 要密钥）
         └─→ IPFS 网关（要分片，直连，不过代理）
```

它做两件事：转发 m3u8 时把 `#EXT-X-KEY` 那行的 `URI` 改写成本机地址（`METHOD`、`IV`、分片地址一概不碰）；收到密钥请求时去向站点要那一串，截成 16 字节回给播放器。**分片不过代理** —— 一部 47 集的剧，代理总共只搬 47 份 m3u8 和 47 次 16 字节。

三处约束写在那儿，别当它是免费的：只听 `127.0.0.1`（绑 `0.0.0.0` 等于给同局域网每一台设备开一个无鉴权的解密代理）、端口随机取空闲口（写死会和应用商店里随便哪个同类应用撞上，撞上的表现是这一侧 listen 失败、播放器连一个没人应答的端口等到超时）、用完就关（引用计数归零才真关端口，因为换集换线路都会重放一次媒体源）。

顺带一条实测记录：`IV=0x00…00` 是站点**显式写**的，那就是它真的用全零加密（按 HLS 规范缺省时的分片序号解不开，试过）。所以影枢照原样转达 IV，不替它「补规范」。

### 6. 拿到了直链不等于出画面 —— 播放器这侧还有三件事

直链解析成功只是前半段。播放器这侧没配好，用户看到的仍然是「点了播放后一个不动的黑屏」，跟拿不到直链长得一模一样 —— 所以这三处是分开处理的：

| 处理 | 原因 |
|---|---|
| 数据源带 `Referer` / `Origin`（取自 `Playing.sourceBaseUrl`） | 源站 CDN 常按 Referer 放行。宿主 `curl` 不带 Referer 也返回 200，不代表设备侧、更不代表所有 CDN 节点都放行 —— 带上源站根地址是最省事且正确的做法 |
| HLS 走 `setAllowChunklessPreparation(true)` | 完整的 1 小时分段列表有十几万行，默认准备要把它整个拉完才起播。允许 chunkless 后拿到 master playlist 就能开始解码，起播快得多 |
| 监听 `onPlaybackStateChanged` / `onPlayerError` | 首帧之前显示「缓冲中…」、失败时把 `PlaybackException` 的 message 显示出来并给「重试」。不这样做的话，用户只能靠猜是网络慢还是地址坏了。 |
| 缓冲提示报「已等几秒 / 已缓冲几秒 / 多快在攒 / 几秒没有新数据」，**不报百分比** | 这条链路上没有可信的百分比：ExoPlayer 拿到数据就成批灌满几十秒，`bufferedPosition - currentPosition` 是阶跃的，百分比会 0 → 100% 瞬间完成，还会出现「缓冲中 100%」却仍在等（数据够了、解码器没出帧）。速度那一项是 `PlayerBuffering.SpeedMeter` 自己按最近 2 秒的字节数算的，**不是** Media3 的带宽估计 —— 估计只在真的发生加载时更新，连接一挂就冻结在最后一次成功的值上，而那一刻恰恰最需要说真话。「几秒没有新数据」单独算一项：追播中卡住时缓冲量已经大于一秒，那句「已缓冲 N 秒」自己不会动，没有这一项整行字就是静止的，用户分不清是在走还是死了。字节与速率的写法只有一份（`data/ByteFormat`），缓存页和播放器用同一个函数 —— 同一个量在两屏上必须是同一个数。没量到就整项不写。 |

### 取地址的几条做法

拿不到直链时它明说拿不到，不假装能播；`FailureReason` 把成因带到界面上，不压成一句「解析失败」。

- **地址在源站脚本里现算的：不跑它的脚本，而是在源站自己的页面里接住。**
  打开该页让站点把地址算给它自己的播放器，`shouldInterceptRequest` 只挑出页面已经请求过的那条媒体地址交给用户选。
- **源站给的是爱奇艺 / 腾讯那类平台播放页：照源站自己的做法交给它的解析端。**
  不少聚合站的 `vod_play_url` 里根本没有直链，它的 `playerconfig.js` 会把平台页地址交给一个解析端换回可播流 ——
  这个端点前缀就是 `SourceConfig.parseUrl`，影枢像源站自己的播放器一样把地址交给它，然后在旁边接住页面请求的那条流。
- **站点要求请求带签名时，按它前端公开的那套协议自己算。**
  懂片帝AI 就是这样（见上一节）：HMAC 的密钥是站点分发给了每一个访客的常量。
  要用的东西以站点发给本机的为限（公开常量、匿名会话、随列表下发的密钥）。
- **加密 HLS 里的 `METHOD=AES-128`：取密钥、按协议给的 IV（没给就用分片序号）解密。**
  这是任何 HLS 客户端都在做的标准一步，缓存照这个做。
  `SAMPLE-AES` 那类走的是另一条路（条件解密方案在对方手里），当前实现不支持，界面上如实说缓存不了。

- **豆瓣只读公开榜单，不碰它的账号。**
  片库那一屏是豆瓣的榜单（`j/search_subjects` 与 `j/chart/top_list`）—— 不登录、不带任何 Cookie 或凭证，
  带的就是一个浏览器访客本来就会带的那点东西（UA + Referer，豆瓣对裸 UA 直接拒）。
  豆瓣**不给播放地址**，所以点一条之后是影枢拿片名去用户自己接的源里搜；
  搜不到就明说「豆瓣有这部，接的源里没搜到同名」，并把去豆瓣的路递过去，不拿一部名字相近的片糊上去。
  它哪天收紧这些接口，界面就如实写原因，不会装成「这个榜没片」。

应用本身不托管、不存储、不分发任何视频内容，也不内置任何资源站：仓库里带的六个内置源都是用户提供的公开站点，或自己搭的（MINO自建）。加什么源、看什么内容，由使用者自己决定。

---

## 五、跨源聚合与续播

- **搜索**并发打所有启用的源，结果按 `Media.key` 归并：**标题（去空白与标点）+ 年份**。一部片只出一条，展开能看到它在每个源的可播性徽标（青色 = 播放源语义色）。
  - 归并键去标点是因为同一部片在各源的写法差别很大：「暗影蜘蛛侠 黑白版」/「暗影蜘蛛侠（黑白版）」/「暗影蜘蛛侠：黑白版」应该合并成一条。
  - 同一个源对同一部片返回多条（多线路）时，归并后每个源只保留一条徽标，否则界面上会出现两个一模一样的源 chip。
  - 没答上话的源不静悄悄消失：`MediaRepository.search()` 返回 `SearchOutcome(items, failures)`，
    搜索页据此分两种话说 —— 「这几个源里都没有」和「有 N 个源没连上（原因）」是两回事，前者该换关键词，后者该去播放源管理看。
- **详情页的「播放源」用原标题去各源搜，再用归一化键比对**：拿去了标点的键当搜索词，站点的 LIKE 匹配会一条都不给
  （「暗影蜘蛛侠：黑白版」→「暗影蜘蛛侠黑白版」），表现就是搜索卡片挂三个源、点进去只剩另外两个。
  年份按「两边都写了且不同才算另一部片」判。`HtmlAdapter.detail()` 会尽量从页面取年份，取不到时由 `AppViewModel`
  从卡片/收藏/记录里带过来 —— 详情页少了年份，同一部片就会在收藏和观看记录里裂成两条（键一条带 `|2026`、一条不带）。
- **离线缓存是排队的**：同时最多 3 集在跑，其余停在「排队中」，跑完一格补一格（`DownloadRepository.pump()`）。
  「缓存全部」一次点下去几十集全发出去等于拿手机当下载机，站点那边也会被当成刷接口。
  每一集写到 `.part` 上，整条走完了才改名成正式文件 —— 「文件在而且非空」从此等于「这一条是完整的」，
  半截分片不会冒充下完的、离线播放也不会点开一个坏文件。
- **一集内部按分片并发取**：默认同时开 4 条连接取同一集的切片（`DownloadRepository._segParallel`，「我的 → 下载设置」里可选 1/2/4/8）。
  上限给到 8 是因为再多就是在拿站点当靶子，CDN 容易把这种频率当成爬虫拒掉 —— 设置页把这句话写在按钮下面。
  改动只作用于**之后开始**的任务：正在下的那条已经按旧并发数起好了 worker 池，要暂停再点「继续」才会换（设置页也这么写，不假装即时生效）。
  HLS 的分片列表不给字节数，所以「总共多大」在第一个分片落地前只能估 —— 估出来的带「约」，
  下完那一格转成实际落盘字节，「约」字跟着消失（`DownloadTask.sizeEstimated`）。
- **缓存跑着的时候有前台服务举牌**（`data/DownloadService`，`dataSync` 型）：有任务在跑就常驻一条低优先级通知
  （「几集缓存中 · 多快」，每秒跟着速度刷），一个不剩就摘牌 —— 没有它，用户切去别的应用转一圈，系统就可能把进程收掉，
  回来满屏「排队中」变「已暂停」。生命周期只有 `DownloadRepository.syncService()` 一个入口（挂在 `pump()` 末尾，
  排队/暂停/续传/跑完每一条路都经过它）；通知那条链每秒的发射同时兼作兜底重举（服务被系统单独收掉后举回来）。
  12+ 从后台举牌会被平台拦下 —— 拦了就当没牌子，下载照跑，不为一条通知砸用户的任务。
  没要 `POST_NOTIFICATIONS`：决定进程优先级的是服务本身，通知可见与否不影响它。
- **首页 = 豆瓣「剧集 · 热门」那一张榜**：一屏的画面全出自这一张榜（`DoubanRepository`，落盘键 `tv/热门/recommend`），
  进来这一屏取一次；盘上有旧的先上屏，旁边写着「取回于 今天 14:18」，最新一次没成则旧榜留着、
  上面加一句「最新一次没刷新成功：…」—— 一次网络抖动不该把首页冲成空白。
  以前这一屏摆的是「第一个能答上话的播放源的第 1 页」，底下配三排从这一页现取的类型 / 地区 / 年份筛选芯片，
  内容还连同「几点取回」和「用的是哪套源顺序」一起落盘（`HomeRepository`，12 小时 + `id:priority` 指纹才重拉）。
  换成豆瓣之后这些依据全没了：首页不再由哪个源决定内容，「均来自〈源名〉」和「能连上 / 连不通」就成了
  替源作的保；榜单次序是豆瓣给的，页面上也不该再造一层过滤。于是芯片、`HomeRepository` 和那套指纹一起删了。
  代价写在按钮上：海报点下去不等于能播 —— 走的是下面那条「拿片名去跨源找」，所以那颗叫「找这一部」。
  首页这一份和片库各用各的状态：在片库切到「美剧」不会把首页换了脸。
- **这一集放不出来时，先试这个源的另一条线路，再换源**：三种失败来源（取链没拿到地址、播放器报错、
  地址通了却 20 秒不出第一帧）汇进同一个 `reportPlaybackFailure`。一轮里的一格 = 一个「源 · 线路」组合：
  把当前源剩下的线路先试完（换线路不重取详情，那份 `MediaDetail` 就在手上，懂片帝那种连线路包都缓存好了），
  才轮到下一个源（换源每次都要重新拉一遍详情）—— 便宜的先试。源的次序就是「播放源管理」里拖出来的那个次序。
  进度带的是这一轮的锚点（`Round.anchorSec`），同样不读那个每 500ms 被播放器覆写的活值。
  认账靠那条 `episodeUrl`：迟到的播放器回调会带上**它当时在放哪条地址**，不然同一源内换线路时，
  上一拍的错误会被算到新那一笔头上、把本该试的线路跳掉。全试完了横幅把「哪个源哪条线路为什么不行」
  一条条摊开，留一颗「再试一轮」。只有一条线路的源（M3U 列表、没给官方线路的懂片帝）不出现「线路」两个字。
  有意不做的：播到一半的卡顿不换源 —— 那和用户自己拖进度条长得一样，误判的代价比救回来的收益大。
- **切源不丢进度**：播放位置存在 `AppViewModel._resumePosition` 里，详情页和播放器里换源时带过去。播放器底栏写着「播放进度已跨源同步 / 切换源不丢进度」。
  起点是随那一次播放请求冻结的（`Playing.resumeFrom`），不读那个每 500ms 被播放器覆写的活值 —— 换源时 `player.stop()` 会把活值冲成 0。
- **全屏播放器里也有「源」这一格**：底栏那颗芯片与选集 / 线路 / 画质平级，带的就是查到的源数（「源 2」）。
  从首页、继续观看直接进播放器时手上没有这份比对，芯片不带数字，面板里给一颗「查一次」现搜所有源 ——
  一次网络扇出、几秒起，所以不在起播时自动跑。这份列表按**片名指纹**留在播放器手上，换源、换集都不必重查
  （各源的 key 写法不一样，拿 key 认片会每换一次源就退回「没查过」）。面板底下还会单说「另有 N 个源没答上话」：
  「别的源没有这一部」和「有几个源没查到」是两回事。
- **「画质」那一格说的是画面，不是设置**：底栏芯片报的是**此刻真的在放**哪一档（读 `Tracks.Group.isSelected`），
  面板里另有一句「此刻在放 NNNNP」。原来它报的是用户设的上限、没设就写「自动」—— 而「自动」既不是画质也不是档，
  用户问「现在几 P」它答不上来；更糟的是上限写 1080、网络撑不住掉到 480 的时候，芯片还写着 1080。
  上限这一格只能往下压（`setMaxVideoSize`），压不出更高的，所以默认那一项改叫「最高（撑不住才降）」而不是「自动」：
  它就是不封顶，和"选最高"是同一件事，名字别再让人以为那是两种选择。
  起播偏向最高档靠的是 `PlayerBuffering.START_AT_BITRATE`（1.5 → 6 Mbps）：那是 ABR 的**初始估计**，
  估低了开头要往上爬好几轮，看到的就是「先糊一会儿才清楚」。代价反过来也真 —— 出口本来就慢时，
  开头几秒会因为去追一条下不动的档而多缓冲一会儿。两边不兼得，这里选了「起手就清楚」。
- **退到后台就停，唯一的例外是小窗**：播放器挂了 `LifecycleEventObserver`，`ON_STOP` 时 `pause()`。影枢不给**播放**做前台服务、也不发播放通知，
  放任它在后台放着，用户既找不到入口也关不掉；回来时不自动续播。小窗里不许停 —— 那三条抱怨的「看不见、关不掉」，
  那颗悬浮窗本身就是入口和关得掉。（前台服务只用在一处：`data/DownloadService` 给**离线缓存**举牌，见「离线缓存」一节。）
- **小窗播放：在播放器里按 Home 就把画面缩成系统那颗悬浮窗**（`MainActivity.onUserLeaveHint`）。
  发起权只能在 Activity（只有它收得到「用户按了 Home」），而「放不放得开、什么形状」只有播放器那一屏知道 ——
  所以那条 500ms 的轮询顺手报一次视频宽高比（`AppViewModel.pipRatio`，还没解出画面就是 `null`，
  那时候按 Home 就还是普通退出；这一屏卸掉时也报回 `null`，否则在首页按 Home 会缩出一颗放上一部片的小窗）。
  小窗里只有画面：那颗窗收不到手势，摆一颗点不动的「重试」比留黑屏更骗人，所以顶栏、控件、告知横幅、
  报错与暂停那颗全不摆，进小窗时借全屏那一档一次、出来原样还回去。片名与源名写在系统的标题副标题上。
  边界三条：这能力要 Android 8.0（API 26）以上，7.x 上按 Home 就是普通退出；比例只认系统收的 1.0～2.1，
  竖屏的片子会拿到一颗方窗、两边留黑边；进小窗那一刻定下的比例不跟着换源改，换到另一家的片同样只是黑边。
- **倍速记的是全局那一档，不是这一集**：`PlaybackRepository`（单独一份 `cinehub_playback` DataStore）存上一次选的倍速，
  起播、换集、换线路、换源、重开应用都读它 —— 以前它是播放器那一屏的 `remember(1f)`，同一部剧连看八集要点八次。
  面板里那几档（`speedChoices`）与自定义滑杆能拖到的头尾（`speedRange`，0.5~3.0）都由仓库给出，界面上不再抄第二份字面量：
  两件事分开报，是因为「值得摆成芯片的几档」和「允许停在哪一段」本来就可以各自改。有意不做按作品记忆：那会让
  「这一部 1.25、下一部回到 1.0」变成用户得自己记的事，而倍速是他手上调出来的手感，不是片子的属性。
- **自动连播默认关，跳过片头/片尾默认 90 秒**，两样都在「我的 → 播放设置」里改，也从播放器底部那颗「连播」芯片直接翻。
  连播默认关：自动接下一集是替用户做主的行为，没打招呼就换片比多看一眼按钮更打扰人（老用户盘上那份由 `auto_next_chosen_v1`
  区分「谁真点过」，没点过的一律跟到新默认）。跳过是**一个数管两头**（国产剧片头片尾时长接近，两个框是负担）：
  起播后越过这个数就跳，播到只剩这一段就跳到结尾；整集不足三倍的不跳（不然一集短片会被跳没）、手动拖回开头不再跳、
  拖进片尾区不算播进片尾区（那是用户自己拖的，播放器不该替他按结束）。
- **收藏与观看记录都挂在 `Media.key` 上**，不是挂在某个源的 id 上 —— 同一部片从另一个源进来，收藏还是那一条，进度也还是那一条。
  位置每 10 秒落一次盘、退出播放时补一次，所以最多丢 10 秒；「看不看完」取 `min(30 秒, 时长的 10%)` 且不低于 5 秒 ——
  固定 30 秒会把一条 30 秒的预览在第 5 秒就判成看完，续播提示永远出不来。首页「继续观看」只列没看完的。
- **片库 = 豆瓣榜单，点一条走的是影枢自己的跨源搜索**。豆瓣只给片名、评分、封面和条目页，不给播放地址，
  所以这一步是拿片名去搜、**只认归一化后完全同名**的那一条：差一点就猜进去，用户看到的是另一部片，比说搜不到更糟。
  同名有好几部时（翻拍、续集、同名综艺）按豆瓣给的 `year` 挑；源那边没写年份就只有「唯一一条候选」才敢认，
  两条以上就说「对不上 $year 年那部」，不硬指。没命中就把「去豆瓣看」递到手上。
- **搜索历史落盘，不是内存**（`SearchHistoryRepository`，单独一份 `cinehub_search` DataStore，最多 8 条）。
  曾经它是 ViewModel 里的一个内存 `StateFlow`，重启就没了，而搜索页那句「只存在这台设备上」一直写着 ——
  那句话得是真的。冷启动头几帧内删掉/清空的那几条由墓碑记着，读盘回来时不把它们合并回去。
- **搜索页不摆源的健康状况**：那是「我的 → 播放源管理」那一页的职责。搜索页只回答「这部片在哪个源里有」——
  结果行上「这条来自哪个源」的小字留着（点开详情要用），空态里那份带延迟毫秒的体检报告整块删了。

---

## 六、已知环境限制

这两条是实测到的、与代码无关的外部限制，记录在此以免被误当成 bug：

| 现象 | 结论 |
|---|---|
| 某内置网页解析源从模拟器返回 **HTTP 567**（腾讯 EdgeOne 安全拦截页），但从宿主机 `curl` 返回 200 —— 即使两者出口 IP 相同 | 设备侧网络环境被拦。换 UA、补全 Chrome 请求头、退 HTTP/1.1、锁 TLS 1.2 均无效（宿主 curl 8.21/Schannel 通，设备 curl 8.0.1/BoringSSL 不通）。应用如实显示为「连接失败 · 安全策略拦截 · 不可用」，不假装可用 |
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

验证「本机解码」这条链路：内置源里没有可控的测试流了（`demo_m3u` 已下线，见 `Seed.retiredIds`），要复现这条只能在「播放源管理」里手动加一个源，地址填公开测试流（`https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8`），点进去播「正片」。播放器应显示真实画面与 `00:0x / 10:34` 的进度（这条 HLS 约 10 分 34 秒），这就证明画面是 Media3 在本机解出来的，而不是套了谁的网页。手动加的源不会被自动补回，删掉就干净了。

---

## 八、许可

[MIT](LICENSE)。仅限个人学习与技术研究使用，请遵守你所在地区的法律法规以及各内容站点的服务条款。

