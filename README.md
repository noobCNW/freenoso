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

| 引擎 | 引擎 ID | 是否需配置 | 是否联网 | 说明 |
| --- | --- | --- | --- | --- |
| 系统 TTS | `system` | 否 | 完全离线 | 依赖手机自带 / 已安装的 TTS 引擎与中文语音数据。设置页内有「前往系统 TTS 设置」按钮一键跳转下载语音数据 |
| 讯飞普通版 | `xunfei` | **需要** | 在线 | 在 [讯飞开放平台](https://www.xfyun.cn/) 注册 → 创建应用 → 开通「在线语音合成」,把 AppID/APIKey/APISecret 填到 TTS 设置 |
| 讯飞超拟人 | `xunfei_super` | **需要** | 在线 | 开通「超拟人合成」,同一组 AppID/APIKey/APISecret + Resource ID(WebAPI 地址末段,形如 `mcd9m97e6`) |
| matcha 离线神经 | `matcha_zh_baker` | 否 | 完全离线 | APK 已预装模型;首启 `ReaderApp` 自动从 assets 拷贝到 `filesDir`,设置页可看进度。无预装包的构建可在线散文件下载(见下文) |

> 讯飞普通版与超拟人**共用一组凭据**,保存在 `SecureKeyStore` 的 `xunfei` 命名空间;超拟人额外需要 Resource ID。所有 Key 经 `EncryptedSharedPreferences` 加密,仅存本机。
>
> **历史说明**:早期版本曾集成讯飞 AIKit「离线语音合成 (高品质版)」(`xunfei_offline`),因控制台授权与 SDK 类型不匹配(MSC vs AIKit)及装机额度等问题已移除,由开源 matcha + sherpa-onnx 替代。旧版 `xunfei_offline` 密钥会在升级时自动合并进 `xunfei` 命名空间。

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

#### matcha 离线引擎 · 开发与排坑

**模型组成**(sherpa-onnx 把声学模型与声码器分开发布,本仓库在 assets 里已合并齐全):

| 文件 | 来源 | 作用 |
| --- | --- | --- |
| `model-steps-3.onnx` | [HF: csukuangfj/matcha-icefall-zh-baker](https://huggingface.co/csukuangfj/matcha-icefall-zh-baker) | Matcha 声学模型 (3-step, 移动端推荐) |
| `hifigan_v2.onnx` | [GitHub Release: vocoder-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/vocoder-models) | HiFi-GAN 声码器 (~3.6 MB) |
| `lexicon.txt` / `tokens.txt` / `*.fst` | 同上 HF repo | 拼音词典 + 文本正则化 |
| `dict/**` | 同上 HF repo | jieba 中文分词 (lexicon 模式必需) |

**就位流程** (`MatchaModelManager`):

1. `ReaderApp.onCreate` 后台调用 `ensureReady()`;
2. 若 `filesDir/tts/matcha-icefall-zh-baker/` 已完整 → `Ready`;
3. 否则若 APK 内 `assets/matcha-icefall-zh-baker/` 齐全 → `Installing`,逐文件拷贝 (支持断点续传,已存在且大小合理的文件跳过);
4. 否则 → `Downloading`,按 16 个散文件从 hf-mirror.com / huggingface.co / GitHub 依次尝试;
5. `MatchaTtsEngine` 在首次 `synthesize` 时于 IO 线程懒加载 `OfflineTts` (~80 MB 进 RAM,勿放主线程)。

**在线下载兜底 URL**(无预装 assets 的精简包时用):

- 声学 / 词典:`https://hf-mirror.com/csukuangfj/matcha-icefall-zh-baker/resolve/main/<path>`
- 声码器:`https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/hifigan_v2.onnx`

> 不要用 `tts-models/matcha-icefall-zh-baker.tar.bz2` 整包:官方 tar 里**不含** `hifigan_v2.onnx`,解压后完整性校验必失败。

**常见坑**:

- **`noCompress` 必配**:后缀 `onnx` / `fst` / `utf8` 已在 `build.gradle.kts` 的 `androidResources.noCompress` 中;新增同类大文件请同步加入,否则首启拷贝极慢。
- **磁盘双份占用**:APK ~171 MB + 拷贝后 `filesDir` ~100 MB,设备上合计约 270 MB;删模型只清 `filesDir`,APK 内 assets 仍在。
- **只支持 arm**:与 sherpa-onnx AAR 一致,`abiFilters` 仅 `arm64-v8a` / `armeabi-v7a`,模拟器请用 arm64 镜像。
- **不支持音调**:matcha baker 单 speaker,`TtsRequest.pitch` 被忽略;语速映射为 sherpa `lengthScale = 1/speed`。
- **首次合成慢**:除拷贝外,第一次 `OfflineTts` 加载 ONNX 约 3~10 秒,属正常;实例由 `MatchaTtsEngine` 单例复用。
- **Git 大文件**:`model-steps-3.onnx` (~72 MB) 与 `sherpa-onnx-*.aar` (~54 MB) 超过 GitHub 50 MB 建议值但未超 100 MB 硬限;仓库变大时可考虑 Git LFS。

**更新预装模型**(开发者):

```bash
# 在项目根执行,写入 app/src/main/assets/matcha-icefall-zh-baker/
ASSET=app/src/main/assets/matcha-icefall-zh-baker
HF=https://hf-mirror.com/csukuangfj/matcha-icefall-zh-baker/resolve/main
mkdir -p "$ASSET/dict/pos_dict"
curl -fsSL "$HF/model-steps-3.onnx" -o "$ASSET/model-steps-3.onnx"
curl -fsSL "$HF/lexicon.txt" -o "$ASSET/lexicon.txt"
# ... 其余 13 个文件同理,见 MatchaModelManager.MODEL_FILES
curl -fsSL https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/hifigan_v2.onnx \
  -o "$ASSET/hifigan_v2.onnx"
```

凭据表单底部有统一的「保存」操作栏(仅讯飞在线引擎):输入框是局部 state,变化后会显示"有未保存的修改",点了「保存」才真正写到加密存储。

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

matcha 引擎额外注意:`synthesize` 返回 `FloatArray` 转 16-bit PCM;合成在 `Dispatchers.IO`,`OfflineTts` 由 `MatchaTtsEngine` 全局单例持有,切引擎或删模型时需 `close()` 释放 native 句柄。

### matcha 设置页 UI

选中 `matcha_zh_baker` 时显示 `MatchaModelCard`,状态机:

| 状态 | UI |
| --- | --- |
| `Missing` | 「立即安装」或「下载离线模型」(取决于 `hasBundledAssets()`) |
| `Installing` | 进度条 + 当前拷贝文件名 |
| `Downloading` | 进度条 + 当前下载文件名 (在线兜底) |
| `Ready` | ✓ 已就绪 + 「删除」 |
| `Failed` | 错误信息 + 重试 |

## 已知限制 / 未完成

- 扫描型 PDF 拿不到文字层,目前 PDF 只支持按页浏览,不支持朗读
- TXT 章节标题模式覆盖了主流网文 / 出版物,极个别非标题但形似(如某些诗体)可能误判,可在书架长按 → 「重新切分章节」重新尝试
- matcha 仅标贝单女声,无多说话人 / 无音调调节;音质与延迟取决于设备 CPU,中低端机长句合成可能数秒
- APK 因预装模型约 171 MB,Git 仓库含 ~110 MB 二进制;上架应用商店前可考虑 productFlavor(预装版 vs 在线下载精简版)或 Git LFS
- 模拟器需 arm64 镜像;x86 模拟器无法加载 sherpa-onnx native 库

## 开发约定

- 提交保持小颗粒,提交信息聚焦"为什么改"而非"改了什么"
- 新增的工程文件请同步在本 README 的 `工程结构` 段加一行
- 修讯飞 API 或 matcha 模型流程时务必同步检查 `EngineHint` / `MatchaModelCard` 文案,以及 `SystemTtsEngine`、`MatchaTtsEngine` 报错里对其它引擎的引用,避免出现"建议切换到不存在的引擎"
- 调整 `ReadingPrefs` 字段时要顺手把 `buildPageCacheKey`(`ReaderContent.kt`)的拼接也加上,否则 LRU 缓存可能在偏好变化后还命中旧排版
- 往 `app/src/main/assets/` 加任何**自定义后缀**的二进制资源(SDK 模型、字典、词库等),如果调用方会用 `AssetManager.openFd()` 读 fd,务必同步在 `app/build.gradle.kts` 的 `androidResources.noCompress` 加这个后缀,否则运行时会抛 "...probably compressed" 的 `FileNotFoundException`
