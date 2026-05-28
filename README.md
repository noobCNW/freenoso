# 小说阅读 (XiaoShuoReader)

Android 离线小说阅读器。Kotlin + Jetpack Compose (Material 3) 实现,聚焦三件事:**省心导入**、**舒服阅读**、**朗读跟读**。

## 已有特性

### 导入与目录

- **三种格式**:TXT(自动编码识别 + 智能章节切分)、EPUB(epub4j-core 解析)、PDF(`PdfRenderer` 按页渲染)
- **大文件友好**:TXT > 5 MB 自动走流式解析(逐行扫,内存占用稳定),最坏情况按定长切片兜底,导入时显示进度
- **章节切分**:支持「第 X 章」「第 X 卷」「序章」「楔子」「正文 X」「===标题===」「【标题】」等多种常见样式;严格 + 宽松双模式 + 长度兜底,识别失败也不会崩
- **打包存储**:同一本书的所有章节文本合并写到一个 `content.bin`,`ChapterEntity` 只存 `byteOffset` / `byteLength`,导入数千章不再产生上万个小文件,跳章读取仍然瞬时
- **重新切分**:书架上长按图书 → 「重新切分章节」,可以无损用最新版规则重切已导入的书
- **目录侧滑面板**:支持按章节标题或章号搜索,「回到当前章」按钮一键定位

### 阅读体验

- **横向翻页 / 上下滚动**两种模式
- **5 套预设主题**:日间 / 夜间 / 护眼 / 羊皮纸 / 纯黑
- **字体**:内置 4 种 + 支持导入 .ttf / .otf 自定义字体
- **可调**:字号、行高倍数、段间距、页边距、保持屏幕常亮、是否显示章节大标题
- **章首大标题**:可在阅读设置里关掉(默认关),正文不会被"水印"挡住
- **自动连章**:阅读到一章末尾继续向左滑会出现"即将进入下一章"过渡页,松手自动加载下一章并定位到第一页
- **自动阅读 (自动翻页 / 自动滚动)**:底部"自动阅读"一键开关,横向模式按设定速度自动翻下一页 (动画过渡)、上下模式按设定速度平滑向下滚动;到章末自动续到下一章,无下一章自动停止;速度在阅读设置里 1~10 调节,与 TTS 朗读互斥 (开任一会停掉对方)
- **断点恢复**:阅读进度节流持久化,重开 App / 重启手机 / 重装(只要数据未清)都能从离开的那页继续

### 排版性能

- **单测量切片**:`Paginator` 对一整章只调用一次 `TextMeasurer.measure`,然后按行的 `top` / `bottom` 切页,千字章节几十毫秒就能排完
- **超长章节分块**:超过阈值时自动分块测量,避免单次测量过大
- **ViewModel 级 LRU 缓存**:同字号/行高/页宽/字体/边距/章节的排版结果会被缓存,翻回老章节秒开
- **后台预排**:进入一章时会在 `Dispatchers.Default` 上提前排好相邻章节,实际翻章基本无等待

### 书签

- 一键添加书签,可填备注、染色,支持快速跳回精确字符偏移

### TTS 朗读

- **4 个朗读引擎**:
  - **系统 TTS**(完全离线,免费,依赖手机自带的中文语音数据)
  - **讯飞普通版**(在线 WebAPI,需要 AppID/APIKey/APISecret;**已内置控制台默认免费开通的 5 个基础发音人** —— 讯飞·小燕 (`x4_xiaoyan`) / 讯飞·小露 (`x4_yezi`) / 讯飞·许久 (`aisjiuxu`) / 讯飞·小婧 (`aisjinger`) / 讯飞·许小宝 (`aisbabyxu`),开箱即用;若你账号还开通了其它特色发音人,可在「我的发音人」追加 vcn,同 vcn 的自定义会覆盖内置)
  - **讯飞超拟人**(在线 WebAPI,音质比普通版好一档,自带评书 / 电台 / 动漫等高表演力发音人;需要 AppID/APIKey/APISecret + Resource ID,与普通版共用同一组凭据;**已内置控制台默认开通的 5 个"聆"系列发音人** —— 聆飞逸 (`x6_lingfeiyi_pro`,男) / 聆小璇 (`x6_lingxiaoxuan_pro`,女) / 聆玉昭 (`x5_lingyuzhao_flow`,女) / 聆小玥 (`x6_lingxiaoyue_pro`,女) / 聆玉言 (`x6_lingyuyan_pro`,女);评书 / 自训等其它发音人同样在「我的超拟人发音人」里追加)
  - **matcha 离线神经 TTS**(`matcha-icefall-zh-baker`,开源 sherpa-onnx):**无需 API Key,APK 已预装完整模型 (~100MB)**,首次启动自动从 assets 拷贝到 `filesDir`,之后完全离线;内置标贝中文女声,支持语速调节(不支持音调)
- **评书一键预设**:讯飞超拟人引擎下提供"切到儒雅大叔 + 慢速 + 略压音调 + 韵律强化"的一键应用,听感最接近评书
- **句子级流式合成 + 边播边合**:`SentenceSplitter` 按标点切句,`TtsController` 提前合成下 1~2 句,`ExoPlayer` 串播
- **章节自动连播**:一章读完自动续到下一章
- **句子高亮跟随**:朗读到哪一句,正文那一句被着色高亮,翻页同步跟随
- **MediaSession 前台 Service**:通知栏 / 锁屏 / 耳机线控
- **3 次合成失败自动停止**:并通过 `Snackbar` 上报错误,避免静默打转
- **设置页内试听**:每个引擎有独立的「试听当前音色」卡片,每条音色行还有独立"试听"按钮,免得来回切引擎调音色
- **`SecureKeyStore`**(`EncryptedSharedPreferences`)加密保存讯飞 API Key(仅在线引擎需要)

## 工程结构

```text
app/src/main/java/com/xs/reader/
  MainActivity.kt
  ReaderApp.kt
  data/
    db/         Room: Book / Chapter / Bookmark / TtsConfig (version=2)
    parser/     TxtParser / EpubParser / PdfParser  (统一 BookParser 接口)
    repo/       BookRepository (打包存储 + 重切分) / BookmarkRepository
    prefs/      DataStore: ReadingPrefs (主题/字体/字号/朗读偏好等)
  ui/
    AppRoot.kt           顶层 NavGraph
    shelf/               书架(长按弹出 重切分/删除)
    reader/              阅读器主屏 + 翻页 + 主题 + 字体 + 章首过渡页
      Paginator.kt       排版引擎(单测量切片)
      ReaderContent.kt   翻页主 UI
      ReaderViewModel.kt 章节加载 + LRU 排版缓存 + 预排 + TTS 编排
      ChapterDrawer.kt   目录侧滑面板(带搜索)
      pdf/PdfReader.kt   PDF 按页浏览
    bookmark/            书签列表
    settings/            通用设置 + TTS 设置(含试听 + 凭据表单)
    theme/               5 套阅读主题
  tts/
    TtsEngine.kt              统一接口 (synthesize → Flow<TtsAudio>)
    SystemTtsEngine.kt        Android 内置 (离线, 含语言数据自检与跳系统设置引导)
    XunfeiTtsEngine.kt        讯飞普通版 WebAPI /v2/tts (内置基础发音人 + 用户自加合并)
    XunfeiSuperTtsEngine.kt   讯飞超拟人 WebAPI /v1/private/<resource_id> (内置"聆"系列 + 用户自加合并)
    XunfeiVoicePreset.kt      发音人模型 + 内置 vcn 列表 (BUILTIN_XUNFEI / BUILTIN_XUNFEI_SUPER), 用户自定义 JSON 存 DataStore
    MatchaModelManager.kt     matcha 模型生命周期: assets 预装拷贝 / 在线散文件下载兜底 / 完整性校验 / OfflineTtsConfig 构建
    MatchaTtsEngine.kt        sherpa-onnx 离线神经 TTS (matcha-icefall-zh-baker + hifigan_v2)
    TtsEngineRegistry.kt      引擎注册表 (system / xunfei / xunfei_super / matcha_zh_baker)
    SentenceSplitter.kt       句子切片
    TtsController.kt          合成 ↔ 播放 编排,失败计数,句子级跟随
    TtsPlaybackService.kt     MediaSession 前台 Service
    SecureKeyStore.kt         加密保存讯飞 API Key (含 legacy xunfei_offline 命名空间迁移)
  di/                  Hilt 模块
```

> **matcha 离线 TTS 资源布局**:
>
> | 位置 | 内容 | 大小 |
> | --- | --- | --- |
> | `app/libs/sherpa-onnx-1.13.0.aar` | sherpa-onnx + onnxruntime JNI (双 ABI) | ~10 MB |
> | `app/src/main/assets/matcha-icefall-zh-baker/` | 预装模型 16 个散文件 (声学模型 + jieba 词典 + hifigan 声码器) | ~100 MB |
> | `filesDir/tts/matcha-icefall-zh-baker/` | 运行时工作目录 (首启从 assets 拷贝) | ~100 MB |
>
> `build.gradle.kts` 用 `abiFilters arm64-v8a, armeabi-v7a` 过滤 x86; `androidResources.noCompress` 包含 `onnx / fst / utf8`,避免 assets 被 deflate 压缩导致首启拷贝卡顿。

底部条 5 个文字按钮:**自动阅读 · 语音朗读 · 添加书签 · 章节目录 · 阅读设置**(顶部条只保留「返回」+ 书名,避免太多小图标)。

## 构建

### 环境要求

- JDK 17(Android Studio Hedgehog+ 自带的 JBR 即可)
- Android Studio Hedgehog 或更新,或本机已安装的 Gradle 8.9+
- AGP 8.5,Kotlin 2.0,Compose BOM 2024.09
- 最低支持 Android 7.0 (API 24),目标 API 34

### 在 Android Studio 里直接构建

打开本目录,等同步完成,Run 即可。

### 命令行构建(推荐,在 macOS / Linux 上最稳)

> 沙箱里跑 Gradle daemon 在某些 macOS 环境下会因获取本机 IP 失败而启动不起来。本机直接执行没问题。

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
cd /Users/apple/Desktop/n-obj/xiaoshuo

# 用本机已安装的 Gradle 8.9 (避免 wrapper 在不同 Gradle 版本结构差异下的坑)
~/.gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9/bin/gradle assembleDebug --no-daemon
```

产物:`app/build/outputs/apk/debug/app-debug.apk`(约 **171 MB**,含 ~100 MB matcha 预装模型 + ~10 MB sherpa-onnx native .so;`onnx / fst / utf8` 已配 `noCompress`,APK 内以 stored 模式存放)

直接装到连着的设备:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> **Gradle Wrapper 排坑**:若 `./gradlew` 报 `NoClassDefFoundError: org/gradle/wrapper/IDownload`,说明 `gradle/wrapper/gradle-wrapper.jar` 损坏,可从本机 Gradle 8.9 缓存里复制一份替换,或直接用上面「本机 Gradle 8.9」路径。

## TTS 配置说明

| 引擎 | 是否需配置 | 是否联网 | 说明 |
| --- | --- | --- | --- |
| 系统 TTS | 否 | 完全离线 | 依赖手机自带 / 已安装的 TTS 引擎与中文语音数据。设置页内有「前往系统 TTS 设置」按钮一键跳转下载语音数据 |
| 讯飞普通版 | **需要** | 在线 | 在 [讯飞开放平台](https://www.xfyun.cn/) 注册 → 创建应用 → 开通「在线语音合成」,把 AppID/APIKey/APISecret 填到 TTS 设置 → 讯飞普通版凭据 |
| 讯飞超拟人 | **需要** | 在线 | 在控制台开通「超拟人合成」,把 AppID/APIKey/APISecret + Resource ID(WebAPI 地址末段,形如 `mcd9m97e6`)填到 TTS 设置 → 讯飞超拟人凭据 |
| 讯飞离线 (高品质) | **需要** + 首次激活 | 首次联网,之后离线 | 在控制台开通「离线语音合成 (高品质版)」对应的 SDK 包,把 AppID/APIKey/APISecret 填进 TTS 设置 → 讯飞离线凭据,**点「立即激活」并保持联网,看到状态变成"已激活 ✓"就能完全离线朗读**。每个设备激活会消耗一个装机额度,控制台可见剩余额度 |

> 三个讯飞引擎的凭据**完全独立保存**(因为讯飞每个能力包是独立计费的,通常不会共用同一组 Key):普通版、超拟人、离线分别在自己的表单里维护。所有 Key 都通过 `EncryptedSharedPreferences`(`SecureKeyStore`)加密保存在设备上,不上传到任何服务器。

#### 讯飞在线引擎 · 内置 + 自定义发音人

讯飞 WebAPI **没有「列出当前账号已开通发音人」的查询接口**,但控制台「发音人授权管理」默认开通的基础发音人是固定那几个,所以 APP 把它们写死成了内置音色,**填完 Key 直接就能在「可用音色」里选**,不用再手抄 vcn。

**内置音色(已默认开通,直接可用)**:

- 讯飞普通版:讯飞·小燕 (`x4_xiaoyan`) / 讯飞·小露 (`x4_yezi`) / 讯飞·许久 (`aisjiuxu`) / 讯飞·小婧 (`aisjinger`) / 讯飞·许小宝 (`aisbabyxu`)
- 讯飞超拟人:聆飞逸 (`x6_lingfeiyi_pro`) / 聆小璇 (`x6_lingxiaoxuan_pro`) / 聆玉昭 (`x5_lingyuzhao_flow`) / 聆小玥 (`x6_lingxiaoyue_pro`) / 聆玉言 (`x6_lingyuyan_pro`)

**追加你账号特有的其它发音人**(评书 / 动漫 / 自训等需要单独领取或付费的 vcn):

1. 登陆 [讯飞开放平台](https://www.xfyun.cn/) → 控制台 → 我的应用 → **能力管理 → 在线语音合成 / 超拟人合成 → 发音人授权管理**,看「特色发音人」里你已经领取或购买过的 vcn
2. APP 里:朗读引擎 → 选中对应引擎 → 「**+ 添加发音人**」→ 把 vcn 和显示名填进去保存
3. 同 vcn 的自定义会**覆盖**内置 (如果你想给某个内置发音人改个显示名,或者给超拟人 `x6_lingyuyan_pro` 标 `style=pingshu` 套评书参数,直接加同 vcn 的预设即可)

> 如果填错了 vcn 或者填了一个**未在你账号下开通**的 vcn,合成时会返回 `code=11200 LiccCheck`,APP 会把这个错码翻成人话提示;此时把那条预设删了重添,或回控制台领取/购买对应发音人即可。

> 超拟人发音人如果想自动套"评书"效果,在添加对话框「风格」字段填 `pingshu`,合成时引擎会自动加上 `oral_level=low + rhy=1` 让讯飞侧出韵律。

#### 讯飞离线引擎 · 排坑指南

- **包名必须匹配**:讯飞控制台为每个 SDK 包绑定固定的 Android 包名,本仓库用的是 `com.xs.reader`。如果换成你自己的 AppID,记得到讯飞控制台「能力管理 → 离线语音合成 → 应用包名」加入 `com.xs.reader`(或改 `applicationId`)。否则鉴权返回 `code=18307`
- **首次激活必须联网**:本地 SDK 启动时会向讯飞授权服务器拉鉴权协议,一旦本机激活成功,授权信息会缓存到 `filesDir/iflytek/`,之后离线可用
- **音色文件已打包进 APK**:无需用户手动下载。`XunfeiOfflineSdkManager` 第一次调用 `ensureReady()` 会把 `assets/iflytek/xtts/*` 拷到 `filesDir/iflytek/xtts/`,SDK 从那里读取
- **只支持 arm 架构**:`build.gradle.kts` 已用 `abiFilters` 把 x86 / x86_64 排除,所以模拟器请用 arm64 镜像调试
- **首次启动慢**:第一次合成会触发 SDK 全局 init + 联网激活,通常 5~15 秒,设置页底部的「立即激活」按钮可以提前完成激活,正式朗读时就不会卡顿
- **`.dat` / `.irf` 必须配 `noCompress`**:AGP 默认会用 deflate 压缩 assets,而被压缩的资源不能 `AssetManager.openFd()` 直接读 fd,会抛 `FileNotFoundException("This file can not be opened as a file descriptor; it is probably compressed")`。`build.gradle.kts` 里通过 `androidResources { noCompress += listOf("dat", "irf", "jet") }` 让讯飞音色资源以 stored 模式打包,代价是 APK 多 ~12 MB(从 60M → 72M),收益是首次拷贝可以走 fd 零拷贝、且 SDK 内部任何 fd 访问也不会失败

凭据表单底部有统一的「保存」操作栏:输入框是局部 state,变化后会显示"有未保存的修改",点了「保存」才真正写到加密存储,避免每按一键就持久化、也让"未保存"的语义清晰。

## 关键实现要点

### 排版

`Paginator.paginate(text, ...)` 是 `suspend` 函数,内部:

1. 整章一次 `TextMeasurer.measure`(超长章节会自动分块);
2. 遍历返回的 `TextLayoutResult`,根据每行的 `top` / `bottom` + 当前可用高度,把行划分到一页;
3. 每页保留 `startOffset` / `endOffset` / 已构造好的 `AnnotatedString`,后续可直接 `Text(...)`。

`ReaderViewModel` 维护一个 LRU `pageCache`,key 包含字号 / 行高 / 字体 / 段间距 / 页边距 / 是否显示章首大标题 / 容器内尺寸,任何一项变了缓存自动失效。

### 章节自动连章

`HorizontalPager` 自带的 overscroll 会消费掉边界的 drag delta,靠 `NestedScrollConnection` 监听越界很不稳。所以最后采用「过渡页」方案:

- 当前章不是最后一章时,`pagerState.pageCount = pages.size + 1`,末尾追加一个文字提示页;
- 用户滑到那一页后,通过 `pagerState.settledPage` 确认已停留,触发 `vm.nextChapter()`;
- 配合 `pendingNextChapter` guard 防止同章节重复触发。

### TXT 章节识别

`TxtParser.tryExtractChapterTitle` 集中处理标题候选,支持:

- 章节前缀:`第 X 章 / 卷 / 节 / 回 / 篇 / 集`、`楔子 / 序章 / 正文 X`、`Chapter X` 等
- 包裹符:`=== ... ===`、`【 ... 】`、`〖 ... 〗`、纯空白居中行

严格模式(行级正则,要求整行匹配)失败时落到宽松模式(行内锚点),再失败按 4000 字符定长切片兜底。

### TTS 编排

`TtsController.playChapter` 内部协程编排:

1. `SentenceSplitter` 把章节切成句子;
2. 当前句送 `engine.synthesize(...)` 拿到 `TtsAudio`,作为 `MediaItem` push 进 `ExoPlayer`;
3. 提前合成下一句(并发数受限);
4. 当前句播放结束 → `onSentenceChanged(range)` 上抛给 ViewModel,UI 高亮跟随;
5. 连续 3 次合成失败,停止整章并填 `errorMessage`,UI `SnackbarHost` 弹出。

## 已知限制 / 未完成

- 扫描型 PDF 拿不到文字层,目前 PDF 只支持按页浏览,不支持朗读
- TXT 章节标题模式覆盖了主流网文 / 出版物,极个别非标题但形似(如某些诗体)可能误判,可在书架长按 → 「重新切分章节」重新尝试
- 讯飞离线引擎只随包带了晓燕 / 晓峰两位中文发音人,如要更多发音人需要自行去讯飞控制台下载对应的 `.dat` / `.irf` 文件丢进 `app/src/main/assets/iflytek/xtts/` 并重新打包
- `app/libs/sherpa-onnx-1.13.0.aar` 已下载但未在依赖里启用,Sherpa-ONNX 离线神经 TTS 引擎实现尚未完工(待补 `SherpaOfflineTtsEngine` + 模型下载 UI);如不需要可直接删该文件释放仓库空间

## 开发约定

- 提交保持小颗粒,提交信息聚焦"为什么改"而非"改了什么"
- 新增的工程文件请同步在本 README 的 `工程结构` 段加一行
- 修讯飞 API 时务必同步检查 `EngineHint` 文案,以及 `SystemTtsEngine` 报错文本里对其它引擎的引用,避免出现"建议切换到不存在的引擎"
- 调整 `ReadingPrefs` 字段时要顺手把 `buildPageCacheKey`(`ReaderContent.kt`)的拼接也加上,否则 LRU 缓存可能在偏好变化后还命中旧排版
- 往 `app/src/main/assets/` 加任何**自定义后缀**的二进制资源(SDK 模型、字典、词库等),如果调用方会用 `AssetManager.openFd()` 读 fd,务必同步在 `app/build.gradle.kts` 的 `androidResources.noCompress` 加这个后缀,否则运行时会抛 "...probably compressed" 的 `FileNotFoundException`
