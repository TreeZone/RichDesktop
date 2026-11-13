# 更新记录

记录每个版本「改了什么、为什么改、实测结论」。只看结论请看每节开头的加粗行。

> **稳定基线：0.23.17 / vc161** —— 内置 TTS、语音共存、画中画与打开方式都已实测通过，
> 后续以这一版为回滚点。

---

## 0.23.30 / vc174 — 换中英双语识别模型（feature/bilingual-asr 分支）

**一句话：识别模型从「纯中文 CTC」换成 `streaming-paraformer-bilingual-zh-en-int8`，
英文能直接吐出拉丁字母，"打开 QQ" 不用再靠猜读音。代价是 APK 179MB → 380MB、
CPU 约 2~3 倍。**

### 为什么换

纯中文 CTC 的词表是字节级 BPE，**一个拉丁字母都没有**——说 "QQ" 只能被听成
「扣扣/秋秋/酷酷」。0.23.29 用自动读音别名把这条路补了一半，但本质是"猜"。
双语模型直接在词表里带 26 个字母（8404 条 token），识别结果就是 `qq`。

### 本地实测对比（sherpa 2.0.1，与 APK 内 so 同版本，同一批音频）

| 音频 | zipformer-small 双语（48MB） | paraformer 双语（227MB） |
|---|---|---|
| 0.wav | 昨天是 MONDAY TODAY IS THE AFTER TOMORROW是星期 | 昨天是 monday today is… the day after tomorrow 是星期 |
| 2.wav | 这是频繁 | 这个是频繁的啊不认识接下来 **frequently** 频繁 |
| 4.wav | 嗯 TIME是及时 | 嗯 **on time** 标准是 **in time** 是及时叫他总是准时交他的作业… |
| 46.wav | 你好石刀 | 你好石头把厨房扫拖下 |

- 小号（48MB）也能出英文，但中英混说的长句几乎整段丢；paraformer 能接住。
- RTF：paraformer 0.13~0.17，小号 0.06（x86 单机，2 线程）。

### 体积与风险

- encoder.int8 158MB + decoder.int8 69MB → **APK 380MB**（原 179MB）。
- 两个 .onnx **不入 git**（227MB 会让 `.git` 永久膨胀），取法见
  `app/src/main/assets/asr/README.md`。
- 最大风险是车机实时性：这颗展锐 SoC 比 x86 慢得多，若 RTF 超过 1 会出现
  「说完半天不出字」。真出现就退回 48MB 的小号双语模型（代码只需换
  `VoiceAssets.ASR_MODEL_TAG` 和资源文件）。
- 仍然不支持热词（ContextGraph 只在 transducer）。

### 解码方法

`greedy_search`。这一族换解码方法踩过两次 native `exit(-1)`（CTC+beam 直接杀进程，
Java 拦不住），paraformer 只用本地验过的 greedy，不试 beam。

---

## 0.23.29 / vc173 — 「打开 QQ」能认了：英文字母自动转中文读音

**一句话：识别模型是纯中文的（1000 条 BPE，零拉丁字母），说 "QQ" 只会被听成
「扣扣/秋秋/酷酷」；现在给带字母的应用名自动生成这几个中文读法挂成别名，
走现成的别名通道直接命中，不用换模型、不加包体。**

### 为什么只能这么解

- 热词那条路已经死了：CTC 只支持 `greedy_search`，ContextGraph 只存在于 transducer，
  `VoiceHotwords.ensure()` 对 CTC 是空转（`VoiceAsr` 里已有注释）。
- 所以只能在匹配层把"英文名"翻译成"中国人会说出来的中文词"。

### 改了什么

新增 `VoiceLatin`：拉丁字母 → 中文读法（A→诶、Q→扣/秋/酷…），把应用名里的连续
字母段逐字母转换，汉字段原样保留：

| 应用名 | 自动生成的别名 |
|---|---|
| QQ | 扣扣 / 秋秋 / 酷酷 |
| QQ音乐 | 扣扣音乐 / 秋秋音乐 / 酷酷音乐 |
| B站 | 比站 |

- 同一读音只留一个代表字（秋/球/求 都是 qiu），最多 3 档，避免别名爆炸。
- 连续字母超过 5 个不转（gpsbridge 逐字母读出来没人这么说，硬转只会造出
  永远匹配不上的词），这类仍走人工别名表 + 声母骨架 + 候选。
- 纯中文名不生成——否则会给自己挂一条与名字完全相同的别名，白拿 +4 分。

### 待验证

说"打开扣扣/打开秋秋/打开酷酷"应直接打开 QQ；日志 `VOICE 判定 ...（说法="..."）`
里能看到 ASR 到底听成了什么，如果三种读法都不中，说明它听成了第四种字，把它补进表即可。

---

## 0.23.28 / vc172 — 换成「全员静止 → 一次探测」：卡死检测从常驻轮询改成事件驱动

**一句话：原来每块屏每 1.2~30 秒扫一遍全系统 task（车机日志实测 8 分钟 110 次全校举），
本身就是负载；现在只看每块屏「最后一帧的时刻」，所有屏同时静止才探一次，稳态零 binder。**

### 为什么换

车机日志（`LSPosed_20261112_130304`）里，8 分钟出现 `findTask(...) 命中 (getRunningTasks)`
110 次、`OBS ... taskState=2/run0` 55 次，而被观测的那块蓝牙屏 8 分钟只出 2 帧。
也就是说：**画中画出帧极少，我们却在不停扫全系统 task 列表**。这类开销压在
system_server 和内核调度上，本进程 CPU 统计看不出来 —— 表现为「CPU 正常但整机越用越慢」。

### 新判据（`PaneGuard`）

- 只读 `PipPane.lastFrameAt()`（`onSurfaceTextureUpdated` 里维护，改成 volatile）。
- **全员静止** = 所有有应用的屏都超过阈值没出帧：窗口外 500ms、窗口内（用户刚操作过）1.2s。
- 一次静止期只探一次；有新帧就重开一期。
- 探测：一条线程、**一次** task 扫描（`PipPane.taskDisplays` 批量）+ 一次进程表扫描，
  所有屏共用；然后只对真正需要的屏动作。
- 武装（arm）时机：触摸画中画 / 打开应用 / 音频播放状态变化 / 桌面回前台，窗口 10 秒。

### 恢复动作不变，但「无感知」是有边界的

仍然走 `forceToDisplay` 的分流（不动 move-stack，见该方法的实测教训）：

- **task 还在这块虚拟屏上、只是 activity 被系统 stop** → 只补一次 resume，
  画面从冻住那一帧继续，这条是真无感知；
- **task 已被系统拽到主屏** → 删 task 在本屏重建，必然有一次应用重建，做不到无感知。

### 删掉的（约 400 行）

`PaneDoctor.java` 整个文件（`checkStall` + `stall` 表 + 指数退避 + 每 8 秒 ANR 全校举 +
32 秒 `FRAMES` 心跳 + `watchPlayback/kick/fastMode` 快巡检）、
`PipPane.probeAndRevive` 及 `observeOnly`（`/data/local/tmp/pipdesk_observe` 观察模式）、
逐屏 `probing` 闸门、`moveTries/lastReviveAt/lastForeignAt` 那套节流、
`Watchdog` 的常驻 800ms 主线程 ping（改成有 `/sdcard/pipdesk_dump` 或 `pipdesk_verbose` 才跑）。

### 待车机验证

1. 删掉车机上残留的 `/data/local/tmp/pipdesk_observe`、`/data/local/tmp/pipdesk_desk_nofocus`
   （这两个是 11-11 排查时留下的，观察模式会禁掉自愈并且每 2 秒扫一遍全系统）。
2. 卡的时候看日志里 `FREEZE ...` / `REVIVE-ALL ...` 是否出现、从静止到恢复多久。
3. 对比 `PERF` 行的 `p0=..f=` 与 8 分钟内 `findTask` 次数（应从 110 次降到接近 0）。

---

## 0.23.27 / vc171 — 性能：唤醒词不再反复重建 + 增加长跑诊断埋点

**一句话：语音每次唤醒后都要重建唤醒词模型（0.8~1.2 秒、2 线程吃满），正好落在「你说完话等结果」的当口；已改成常驻。启动也不再抢占首屏。**

### 改了什么

1. **聆听时不再释放唤醒词模型**（`beginListen`）。
   原代码 `kws.release(); kws = null;`，理由是「KWS 和 ASR 抢资源」。
   但 `onPcm` 是 if/else 结构，`S_LISTEN` 分支直接 return，**根本不喂 KWS**，
   两个模型不会同时解码。释放只省十几 MB，代价是回到待唤醒要重建：
   - 现场日志：**45 次「唤醒词模型加载完成」，17 次「聆听开始：KWS 已释放」**
   - 每次重建 824~1180ms，2 线程占满
   - 这 1 秒正好是用户说完话、等结果的时刻 → 体感"点语音助手反应慢"
   改成只 `resetStream()` 清解码状态，模型常驻。

2. **启动时唤醒词模型延后 1.5 秒加载**。
   原来进程一起来就同步加载（1180ms），和首帧、用户第一次点击重叠。
   埋点显示 `BOOT onCreate→首帧` 只有 76~263ms，所以慢的不是布局，是这 1 秒的模型初始化。

3. **性能埋点修正 + 增强**（`PerfMon`）
   - `pss` 改用 `/proc/self/status` 的 VmRSS。原来用已废弃的 `Debug.getPss()`，
     实测读数 365MB，而 `dumpsys meminfo` 的 PSS Total 只有 101MB，差 3.5 倍，
     拿它看"有没有涨"会一直看到虚高值。
   - 新增 `avail=`（/proc/meminfo 的 MemAvailable）：**车机 RAM 小，这个掉到几十 MB
     就是"越用越卡"的直接原因**（系统在换页）。手机内存大，永远看不到。
   - 新增 `win=`（FocusAnchor 窗口数）：窗口累积会直接拖垮合成。

### 现场实测（手机，13 小时连续运行）

| 指标 | 结果 |
|---|---|
| 线程数 | 31 → 29，**稳定，不涨** |
| fd | 138 → 173，稳定 |
| PSS | 101MB（dumpsys 口径），稳定 |
| onCreate→首帧 | 76~263ms |

**结论：不是内存/线程/fd 泄漏。** 手机不卡也印证了这一点。

### 待验证（车机）

车机「越用越卡」目前没有现场数据。装这版后，车机运行到发卡时导一份
`/sdcard/Android/data/com.cz.richdesk/files/logs/pipdesk-<日期>.log`，
看 `PERF ... avail= win= threads= fds=` 这几项随时间的变化即可定位：

- `avail` 持续下降 → 系统内存压力（要减内存驻留，优先语音引擎）
- `win` 增长 → 窗口泄漏
- `threads`/`fds` 增长 → 句柄泄漏
- 都不变但仍卡 → 渲染/合成瓶颈（看 `p0=... 305ms` 这列的帧间隔）

---

## 0.23.26 / vc170 — 修 0.23.25 的崩溃：CTC 不能用 beam search

**一句话：0.23.25 一点语音图标就闪退重启，根因是 CTC 模型走了 `modified_beam_search` —— sherpa 在 native 层直接 `exit(-1)`，Java 拦不住。CTC 只支持 greedy。**

### 现场与根因

用户实测：第一次点语音助手图标 → 桌面闪退重启；之后再点 → 收音框一闪就消失。

第二条其实是熔断在挡：进程崩过之后 `voice/asr.load` 标记生效，90 秒内直接跳过识别模型，
于是 `beginListen` 拿不到识别器，收音框立刻收起 —— 看着也像"闪退"。

根因在 Python 侧复现出来了，sherpa 自己打印得明明白白：

```
online-recognizer-ctc-impl.h:313 InitDecoder
Unsupported decoding method: modified_beam_search for streaming CTC models
```

这是个 `SHERPA_ONNX_LOGE` + `exit(-1)` —— **native 杀进程**，不是异常，`try/catch` 完全无效。

为什么会在 0.23.25 才炸：`Prefs.voiceBeam()` **默认 true**，旧代码据此设
`modified_beam_search`。14M transducer 时代这是对的（beam 抗跳字），换 CTC 后
同一个开关就成了定时炸弹。

### 修了什么

1. **`VoiceAsr` 写死 `greedy_search`**，不再读 `Prefs.voiceBeam`。
   CTC 没有自回归，beam 收益本来就小，greedy 足够。
   注释里写清了「为什么不能改回去」，并在 `Prefs.voiceBeam` 上标了警告。
2. **熔断标记带模型指纹**。原来标记是空文件、只看修改时间，于是换了模型之后
   上一次模型的崩溃记录还会把新模型挡住（用户装完新版立刻测，会以为"还是坏的"）。
   现在标记内容 = 当前 `ASR_MODEL_TAG`，不匹配就删除并继续加载。

### 教训（写下来避免再犯）

**换解码器类型时，除了模型文件、词表形态、配置入口，还要检查所有"解码参数"
是否被新解码器支持。** 0.23.24 换 transducer 大模型时这些参数都通用，所以没暴露；
CTC 只支持 greedy，而 beam 开关是全局默认开的。

另外：native `exit(-1)` 不会给 Java 任何机会，**只能靠"配置前先确认支持"来防**，
不能靠异常处理兜底。

### 验证

- dex 里 `modified_beam_search` 出现 **0 次**（说明代码里已无任何路径会传 beam）
- `greedy_search` 出现 2 次（配置 + 日志）
- `readTiny` / `asrTag` 等熔断逻辑已进包
- APK 182MB，与 0.23.25 一致

---

## 0.23.25 / vc169 — 识别模型换成 25MB CTC：**体积几乎不变，准确率追平 154MB 大模型**

**一句话：找到一个「单文件 25MB、字节级词表、准确率等同大模型」的流式 CTC 模型，替换掉原来 24MB 的 14M transducer。APK 只长大 2MB。**

### 为什么之前非要在「准」和「小」之间二选一，而这次不用

| 候选 | 包体积 | 词表 | 结论 |
|---|---|---|---|
| streaming-zipformer-zh-14M（原来用的） | 70MB（三件套 24MB） | 5539 个单汉字，**完整** | 准不准：错得离谱（见下表） |
| streaming-zipformer-zh-int8-2025-06-30 | 126MB（encoder 154MB） | 1687 token，**缺「酷抖哔哩驶」** | 准，但应用名念不出来 → 0.23.24 否决 |
| streaming-zipformer-multi-zh-hans-int8-2023-12-13 | 60MB | 1741 token，**同一套缺字词表** | 同样否决（实测确认是同一份词表） |
| **streaming-zipformer-small-ctc-zh-int8-2025-04-01** | **20MB（单文件 25MB）** | **字节级 BPE，无缺字** | **采用** |

关键在词表形态：**字节级 BPE** 的 token 是一串 UTF-8 字节（`▁ƌŕş`），任何汉字都能拼出来，所以不会再出现「酷、抖、哔、哩、驶 不在词表」这种硬伤。

### 实测（同一批音频，sherpa-onnx 2.0.1 本地跑）

真实录音（官方测试集，长句）：

| 音频 | 旧 14M | 新 CTC 25MB | 154MB 大模型 |
|---|---|---|---|
| 1.wav | 全球金融**动量** ❌ | 全球金融**动荡** ✅ | 全球金融**动荡** ✅ |
| 8k.wav | **身处的飞机分析** ❌ | **深入的分析** ✅ | **深入的分析** ✅ |

合成短命令（用项目自带标贝 TTS 生成，最贴近实际用法）：

| 原句 | 旧 14M | 新 CTC |
|---|---|---|
| 打开音乐 | 打开音乐 ✅ | 打开音乐 ✅ |
| 打开导航 | **大开**导航 ❌ | 打开导航 ✅ |
| 导航已启动请注意行车安全 | **到行一**启动… ❌ | 导航已启动请注意行车安 ≈ |
| 打开酷狗音乐 | 大开**阔**狗音乐 ❌ | 打开**具**狗音乐 ❌ |
| 播放波点音乐 | 播放**播**点音乐 ❌ | 播放**播**点音 ❌ |

**新 CTC 3 胜 2 平**。「酷」被听成「具/阔」、「波点」被听成「播点」两版都错，
但后者是同音字，匹配层的 `pinyin:exact`（96 分直通）能救回来；前者靠候选确认。

### 代价：CTC 没有热词通道（明确接受）

sherpa 的热词用 ContextGraph 实现，而 ContextGraph **只存在于 transducer 解码器**
（`online-recognizer-transducer-impl.h`；`online-recognizer.cc` 里 hotwords-file
还要求 decoding-method 必须是 modified_beam_search）。CTC 走另一条解码路径，完全不读。

所以：
- 不再给识别器灌应用名热词（留着只会白写文件、白重建识别器）
- **别名变化不再重建识别器** —— 原来每次点候选都会 release + reload，
  下一次说话白等 1~2 秒加载。别名仍参与匹配层打分（声母骨架 / 拼音），不受影响
- 设置页里「写进识别热词表让输出收敛」的说明同步改成准确描述

### 顺带

- `VoiceAssets.ASR_MODEL_TAG` 改为 `zh-small-ctc-2025-04-01-v1`。
  **这一条不改的话，手机上旧的 asr 解压目录会被判「已解压」直接跳过，模型还是旧的。**
- `asrModelsOk()` 的文件清单从三件套改为 `model.int8.onnx + tokens.txt`
  （按旧清单校验会永远返回 false）。
- 模型配置从 `getTransducer()` 改为 `getZipformer2Ctc()`，并判了一次 null
  （Kotlin data class 的默认值不保证非空）。

### 验证

- APK 182MB（上一版 180MB，模型净增 2MB）
- 解包确认 `assets/asr/` 只有 `model.int8.onnx`(26MB) + `tokens.txt`
- dex 里有 `getZipformer2Ctc` / `zipformer2-ctc` / `model.int8.onnx` / 新指纹

---

## 0.23.24 / vc168 — 识别准确率：先修拾音链路（换模型那条路被词表否决）

**一句话：大模型试过了，词表缺字不能用；真问题在录音链路 —— ROM 可能给 48k 采样率而代码按 16k 喂模型（3 倍速失真），而且全程没有降噪与自动增益。**

### 试过并否决的换模型方案

`sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30`（encoder 154MB，源自 icefall
multi_zh-hans zipformer-large）。本地用 sherpa-onnx 2.0.1 实测：

| 项 | 结果 |
|---|---|
| 加载 | OK |
| 准确率 | 确实更好（把「全球金融**动荡**」识别对了，旧模型错成「动**量**」） |
| **词表** | **只有 1687 个有效 token**（旧模型 5539） |
| 缺字 | **酷、抖、哔、哩、驶** 全都不在词表里 |

`sherpa-onnx` 直接报 `Cannot find ID for token 酷`。也就是说「酷狗音乐」「抖音」
「哔哩哔哩」「行驶记录」这类词**永远识别不出来** —— 而语音打开应用恰恰依赖品牌字。
**结论：这个模型不能用**，assets/asr 已回退到词表完整的 14M 版本，
热词的建模单元也跟着从 `bpe` 改回 `cjkchar`、线程数从 4 改回 2
（只换文件不改单元 = 热词整份失效，这个坑记下来）。
（若以后要用大模型，必须选词表 ≥5000 汉字的版本，比如中英双语那个 487MB 的包。）

### 真正在修的两个问题

1. **采样率没有对齐（最致命）**
   `VoiceMic.sampleRate()` 有定义、但**全工程没有任何调用者**。
   `AudioRecord.Builder` 只是"请求" 16k，车机 HAL 完全可以给 48000，而代码会把
   48k 的样本按 16k 直接喂给模型 —— 声音变成 3 倍速的"花栗鼠"，音调全错，
   识别结果必然一塌糊涂，而且日志里看不出来（它不报错）。
   现在：`realSr != 16000` 时按线性插值转到 16k 再送 ASR，并在开麦日志里
   明确打出 `实际=xxxxxHz（与模型不一致 → 已启用重采样到 16000）`。

2. **全程没有降噪 / 自动增益**
   车机是远场拾音（麦在顶棚/仪表台，人离 0.5~1m，还有空调路噪），不给 AGC 时
   说话声只有几百 LSB，动态范围远低于模型训练分布。
   现在：开麦后按 session 启用系统 `NoiseSuppressor` + `AutomaticGainControl`
   （不可用就静默跳过），日志里记录 `降噪=开/无 自动增益=开/无`。

3. 顺带：唤醒后补录 300ms → **500ms**。300ms 不够，"打开音乐"的「打」字常被切掉，
   识别成「开音乐」自然匹配不上；多带的唤醒词尾巴由 `cleanWake()` 剥掉。

### 现场怎么确诊

开麦日志现在是这一行（关键看「实际」和两个 effect）：

```
VOICE 麦克风已开 source=MIC 请求=16000 实际=16000Hz 降噪=开 自动增益=开
```

- 「实际」不是 16000 → 说明之前一直在喂错采样率，这一版是修复；
- 「降噪/自动增益」都是「无」→ 该 ROM 不提供这两个 effect，需要改用软件侧处理。

---

## 0.23.23 / vc167 — 找不到就说找不到，候选不再报总数

**一句话：匹配不到时直说「找不到 xx 这个应用」，不再把一堆无关应用列出来；多个候选只问「要选第几个？」，不报总数。**

- **根因**：`VoiceIntent.appendLatinCandidates()` 原来**无条件**把所有英文名应用
  （最多 60 个）塞进候选，完全不看说法是否相关。于是一句"打开某个不存在的应用"
  也会弹出一屏跟自己说的东西毫无关系的应用，还要逐页翻。
  现在按**骨架相关性**过滤：只有说法与该应用别名声母骨架有对应（`skeletonScore > 0`）
  才进候选；一个都不沾边 → 返回 NONE → 直接播报"找不到"。
- **候选播报**去掉「共 N 个，说「下一页」看更多」，只留一句「要选第几个？」；
  页数改由屏幕提示承载（「第 1/3 页 · 说序号 / 说名字，或直接点」），不占语音时间。
- **单一候选直接打开**：只有 1 个候选且分数 ≥60 时不再问"第几个"（没有第二个可选，
  问了是白问）；分数 <60 的仍走候选让用户确认，避免误开。
- 找不到的文案从「没找到「xxx」」改为「找不到 xxx 这个应用」，更像人话。

**取舍说明**：英文名应用第一次说时，若与任何已学别名都不沾边，会直接回"找不到"，
不会再给一屏候选让你挑。要打开它可以点一次候选让它学到别名（或以后加显式的"搜索"指令）。

---

## 0.23.22 / vc166 — 语音自激的真正断点 + 越跑越卡的根因

**一句话：播报期间那几帧音频压根不送进 ASR/KWS（物理隔离），同时修掉「旧桌面实例把新实例的画面线摘掉」这个会随运行时间累积的死循环。**

### 语音：0.23.21 为什么还是没用

| 漏洞 | 后果 |
|---|---|
| 只在 `finish()`（断句完成）时过滤 | 播报音频早就整段录进 ASR 了，过滤的是残渣 |
| 守卫回落到固定 1200ms | 3 秒的句子，后 1.8 秒的播报照样被录进去 |
| **KWS 完全没管** | TTS 说「我在 / 要选第几个」被唤醒词听回去 → 又唤醒 → 又说「我在」→ 无限循环 |

改法（`Voice.onPcm`）：

1. 播报期**这一帧直接 `return`，不调 `asr.accept()`** —— ASR 里根本不存在这段音频，不需要事后判断；
2. 唤醒词喂数据同样过守卫 —— 断掉「TTS 自己把自己唤醒」这条最隐蔽的回路；
3. 守卫解除的第一帧先 `asr.begin()`，避免播报前残留和播报后的话粘成一句；
4. 守卫时长以**字数估算为硬下限**（`900 + 260×字数`），不再依赖引擎回调是否诚实；
5. 每次进入屏蔽写一行 `VOICE 播报期屏蔽麦克风` —— 界面无法自证，日志可以。

### 卡顿：三个随时间单调恶化的资源问题

1. **旧实例把新实例的画面线摘掉**（结构性，最致命）
   虚拟屏槽位按 `PipDesk-N` 复用，重建时新旧两个 Activity 抢同一个槽位：
   新实例 `bind()` 接上 Surface → 旧实例 `onDestroy → pip.release() → detach(key)`
   把**新实例刚接上**的 Surface `setSurface(null)+release()`。
   → 新 TextureView 永远没有 producer，`frames` 不再增长 → 听诊器判"停帧" →
   每 1.2s 探测一次 → 越跑越卡。
   修法：`Slot.owner` 属主标识，`detach(key, owner)` / `releaseOwned(key, owner)`
   只有属主本人才能摘。
2. **停帧探测无退避**：静态页面本来就不出帧，被永久判停 → 每秒一次探测
   （每次一条新线程 + `getRunningTasks` + `getRunningAppProcesses` 两次全校举）。
   改成指数退避 1.2→2.4→4.8→9.6→19.2→30s，一有帧归零；并加在途闸门，上一次没跑完不再起。
   快巡检也不再被音频回调无限续期。
3. **日志队列无上限**：`newSingleThreadExecutor` 的 `LinkedBlockingQueue` 只进不出，
   车机 /sdcard 走 FUSE 写不快 → 内存随时间线单调上涨。改成有界 256 + 丢最旧。
4. **单画退出检测每次 250ms 全量 `dumpsys`**：fork 2 个进程 + AMS 遍历全部 task
   （输出 1~2MB），多个调用点还会并发。改成 1.2s 共享缓存 + `grep` 只取两类行 +
   同目标在途闸门。

**判据**：卡的时候导日志，`REVIVE` / `MULTI-STALL` / `ANR pane` 的密度应明显下降；
语音看有没有 `VOICE 播报期屏蔽麦克风`，以及有没有再出现「没听清，再说一遍」的连续重复。

---

## 0.23.21 / vc165 — 播报期「硬抑制」，彻底断掉自说自听

**一句话：不再靠文本相似度猜，改成「我们是不是正在出声」，播报期间收到的语音一律不算指令。**

- 旧方案（0.23.19）为什么会循环：只在断句完成时比对「识别文本 vs 最近播报文本」，本质是猜。
  四个漏洞：① ASR 把播报识别成别的字 → 相似度不够就漏；② 时间窗按字数估算，TTS 有延迟对不上；
  ③ 长度门槛把 ASR 吐出的短片段排除；④ 只在断句一处拦，**超时路径不受控**。
  漏一次就回一句「没找到…」，这句话又被听回去 → 自激，停不下来。
- 新判据：
  - `LocalTts.speaking`：`play()` 起点置 true、`finally` 置 false（内置引擎精确）。
  - `VoiceTts.sysSpeaking`：系统引擎用 `UtteranceProgressListener.onStart/onDone/onError`；
    `stop()` 里手动清（`tts.stop()` 不保证回调 onDone）。
  - `withinSpeechGuard()`：正在出声（带 `est+3000ms` 超时兜底防卡死）或刚说完 1.2 秒内 → 屏蔽。
- 两处生效：
  1. `finish()` 入口整条丢弃（不匹配、不回复、不续命）；
  2. **控制循环 `if (state == S_LISTEN && !withinSpeechGuard())`** —— 播报期间连超时判定都不做，
     彻底断掉「收工 → 又被听回 → 再收工」的空转。
- 原 `isSayEcho` 降级为窗口外兜底（防 ASR 延迟吐残片），日志区分
  `播报期抑制` / `忽略播报残片`。
- 代价（明确接受）：播报那 1~2 秒内用户抢话会被吞，必须等它念完。比无限循环强。
- 排查提示：若仍循环，看日志有没有 `播报期抑制`。没有 = 引擎没报播放结束状态，
  需改成按 PCM 输出电平判断。

---

## 0.23.20 / vc164 — 唤醒后压住背景音乐

**一句话：用系统音频焦点，唤醒即暂停音乐，播报念完且状态收起后再恢复。**

- 不用「发 KEYCODE_MEDIA_PAUSE」那套：焦点是系统契约，abandon 之后播放器自己接着放，
  不误伤别的 app，也不用管对方是哪个播放器。
- `AudioFocusRequest(AUDIOFOCUS_GAIN_TRANSIENT)` + `USAGE_ASSISTANT` / `CONTENT_TYPE_SPEECH`
  + **`setWillPauseWhenDucked(true)`**（明确要「暂停」而不是「把音量压低一点」）。
- 请求点：`beginListen()`（唤醒/点语音助手）、`say()`（每次播报）。
  释放点：`endUtterance()` 状态切换后、`cancelListening()`、`stop()`。
- **`releaseFocusWhenSpoken()`**：按 `lastSayAt + 900 + 260×字数` 估算这句何时念完，
  用 `removeCallbacks` 去重后延后释放 —— 保证「播报完毕 + 卡片收起」都满足才恢复，
  又不会因为 cancel 之后还要说「没听清」而提前放开盖掉播报尾巴。
- 兜底：估算失败立刻放，上限 8 秒，绝不永久持有。
- 日志判据：`音频焦点已获得 → 背景音乐暂停` / `被拒（车机不响应焦点）` / `释放音频焦点 → 背景音乐恢复`。
  **若长期「被拒」，说明车机播放器不理会焦点，需要改发 media key 兜底。**

## 0.23.19 / vc163 — 修「TTS 自说自听」+ 关收音框立即停播

**一句话：自己播报的话不会再被当成指令；点遮罩立刻静音。**

- 回采：播报「要选第几个？」被麦克风收回去 → ASR 吐同一句 → 当指令去匹配应用名 →
  回「没找到「要选第几个」」。原 `ECHO_WORDS` 只认「我在/嗯/喂」这类短语气词，挡不住新播报语。
- 修复：`say()` 记 `lastSayText/lastSayAt`；`finish()` 入口用 `isSayEcho()` 判，
  命中整条丢弃（不当指令、不进匹配、不续命）。
  两道门槛防误伤：① 识别串短于播报串 60% 直接排除；② 汉字覆盖率 ≥75% 才算回采。
  ——用户答「第一个」对「要选第几个」只覆盖 66%，不会被吞。
- 停播：`VoiceTts` 新增 `stop()`（只停当前这句，不销毁引擎），`cancelListening()` 第一件事调它。

## 0.23.18 / vc162 — 候选播报精简

**一句话：不再逐个念应用名，只问「要选第几个」。**

- 原来播「您要说哪个？第一个XXX，第二个YYY…」要十几秒，用户只能干等，还容易被硬闸收掉。
- 现在只念 **「要选第几个？」**，多页时追加「共 N 个，说「下一页」看更多」。
- 名字不再念，但**屏幕候选列表照旧（每页 9 条）**，说序号 / 说名字 / 点一下都能选。
- 连带收益：`listenAnchor = 1200 + 300×字数` 随文本变短从 6s 降到 3s，整轮交互压到 2 秒内。

---

## 0.23.17 / vc161 — 内置 TTS 回到标贝 matcha（稳定版）

**一句话：换回第一个语音模型，吐字清楚、时长正常，实测可交付。**

- 模型：`matcha-icefall-zh-baker`（标贝中文单女声）+ `vocos-22khz-univ.onnx` 声码器。
- **关键 API**：matcha 必须走 `OfflineTtsModelConfig.setMatcha(OfflineTtsMatchaModelConfig)`。
  走 `setVits` 那条路是不通的——`OfflineTtsVitsModelConfig` 根本没有 vocoder 字段（javap 已确认），
  配 matcha 的结果就是「模型加载成功但一句不响」。
- 资源：模型 73MB + 声码器 52MB + jieba 词典 13MB + lexicon 1.4MB + 3 个 fst，APK 180MB。
- ruleFsts = `phone.fst,date.fst,number.fst`（逗号分隔，**phone 必须排第一**）。
- `TTS_MODEL_TAG` → `matcha-baker-zh-v1`。**换模型必须同步改它**，否则手机上旧的解压目录
  会被「文件已存在」逻辑跳过，变成旧模型配新参数（历史事故，见 0.23.13）。
- 新增 `LocalTts.probe()` 启动自检：加载后立刻合成两句并写日志采样点数，静默无声不用盲听。
- 实测判据（回归用）：

  | 文本 | 采样点 | 时长 |
  |---|---|---|
  | 你好（2 字） | 12112 | 549ms |
  | 导航已启动，请注意行车安全（13 字） | 62702 | 2843ms |

  时长随字数近似线性增长（≈0.22s/字）才正常。

## 0.23.16 / vc160 — 给 huayan 补拼音词典（已废弃）

**一句话：能出声了，但吐字发糊，用户实测否决，不再走这条路。**

- 用官方预生成 `lexicon-zh-g2pw.txt`（`tts-models` release，2MB / 68012 条，格式 `一 y i 4 _`，
  由 `scripts/piper/generate_lexicon_zh.py` 生成），删掉已无用的 espeak-ng-data（省 18MB）。
- 自检「你好」只有 3584 采样点 / 0.16 秒，明显偏短——音素与模型训练分布对不上。
- **结论：v2.0.x 上的 piper 中文模型不要碰**，音素级偏差调 noiseScale/lengthScale 修不好。

## 0.23.15 / vc159 — 内置 TTS 换 huayan（失败，完全无声）

**一句话：引擎显示「就绪」但一句都不响，根因是 sherpa-onnx v2.0.0 删掉了 espeak-ng。**

- 换 `vits-piper-zh_CN-huayan-medium`（63MB 模型 + 907B tokens），按旧文档配了
  `setDataDir(espeak-ng-data)`、`setLexicon("")`，加载成功 `sr=22050`，然后全程无声。
- 根因（下载 v2.0.1 源码确认）：`csrc/` 里已搜不到任何 `espeak-ng-data`，so 里只剩一条
  `espeak-ng is no longer used` 的告警。`OfflineTtsVitsImpl::InitFrontend()` 在
  `is_piper && lexicon 非空` 时才建 `Lexicon`；lexicon 为空只打一行日志
  `"phoneme_codepoints is empty, no lexicon, and no frontend available"`，
  然后**返回空 samples——不抛异常、不崩溃**。这就是「就绪但无声」的真相。
- 附带坑：`Lexicon::InitLanguage` 在模型 metadata 的 language 为空时
  `SHERPA_ONNX_EXIT(-1)` 直接杀进程。

## 0.23.14 / vc158 — 内置 TTS 响度/亮度/延迟优化（保留 melo）

- `LocalTts.play` 加**峰值归一化（到 0.85）+ 轻度预加重（系数 0.92）**：
  响度拉满解决嘈杂环境听不清，提升高频让音色更亮、更有辨识度，之后 clamp 防削顶。
- 打开设置页即后台加载模型，点试播即时出声。

## 0.23.13 / vc157 — 修「选中内置女声直接闪退」

- 根因：`VoiceAssets.extract` 有「文件已存在就跳过」逻辑。手机上还留着旧标贝模型的解压目录，
  换 melo 后再点内置，拷贝被跳过 → 实际仍是旧模型，却用 melo 的配置去加载 → 原生层 abort。
- 修复：加 `.modeltag` 指纹文件（`TTS_MODEL_TAG`），指纹不符就整个目录清掉重解。

## 0.23.12 / vc156 — 内置 TTS 换 melo

- 标贝 matcha 断句偶有句间停顿（"语音助理"被切开），换 `melo-tts-zh_en`
  （CppJieba 分词 + 按词边界安排韵律）。`setDictDir(p+"dict")`，ruleFsts 含 new_heteronym。
- `VoiceAssets.extract` 改为**递归解压**（melo 的 dict/ 子目录必须跟着解出来）。

## 0.23.11 / vc155 — 设置合并 + 试播台词 + 断句优化

## 0.23.10 / vc154 — 内置离线 TTS（matcha 首次引入）

- 新增 `LocalTts.java`：sherpa matcha-icefall-zh-baker 标贝中文女声 + vocos 声码器。
- 动机：系统 TTS 缺失或入口被关掉时语音回复完全没声，内置引擎不依赖系统。

## 0.23.9 / vc153 — 英文应用名候选可翻页

- 英文名 app 可能几十个，改成**收全部 + 稳定排序 + 每页 9 条翻页**，也能直接说名字检索。
- 别名改存**声母骨架**（`Pinyin.initials`）只用于排序，上限 78 分、**永不静默执行**。

## 0.23.8 / vc152 — 英文应用名识别口径

- ASR 是纯中文模型（词表全汉字），英文名永远吐不出来 → **猜不中必弹候选**，不做「记一次别名」。
- 点选过的汉字串写进 `VoiceHotwords` 让解码收敛，收敛是概率性的，兜底常开。

## 0.23.7 / vc151 — 语音匹配误判 + 唤醒词残字

- 车机上有互为子串的 app（「波点音乐」与「音乐」）→ 加覆盖率门槛（≥70%）+ 该档上限 72 分，
  避免短名字靠「被说法包含」拿高分。
- 同音错字（波/播）不算错：整名读音一致给 96 分 `pinyin:exact` 直接命中。
- 唤醒词尾巴（小瑞/小雷/我在）同时从字幕和匹配文本里剥掉，共用 `VoiceIntent.cleanWake()`；
  **不做单字前缀剥除**，避免误伤「小红书」。

## 0.23.6 / vc150 — 语音共存架构改版

**一句话：不再主动停采让麦。**

- 车机 AudioPolicyService `allowCapture=1` 允许多路并发录音（百度、厂商 gocsdk、高德可同时开麦），
  让路只会让自己的常驻唤醒词长时间失效。
- 常驻音源固定 `MediaRecorder.AudioSource.MIC`（source 1）；`VOICE_RECOGNITION` 在这台车机上
  常读到全 0 死寂。
- 拿到死寂只做 `recycleMic()` 换 input 路由重开；让路仅作 HAL 真独占时的兜底。

## 0.23.5 / vc149 — 修 0.23.4 两个实测 bug

## 0.23.4 / vc148 — 打开方式口径 + 画中画位置绑定

- 设置页 `open_mode` = **默认**打开方式（持久、管所有常规路径）；
  应用页长按 = **临时**打开方式（只对本次生效，不写 Prefs、不写 pane_map、不建绑定）。
- 「指定画中画位置」是**建立绑定**（持久），与临时打开区分。
- 所有「选当前值」的选择器统一用 `OptionPicker`（标题 + 分隔线 + 当前项淡底 + 右侧 ✓ + Ripple）。

## 0.23.2 ~ 0.23.3 — 画中画收尾

- ASR native 崩溃熔断；单画首开「第一次不显示」看门狗修复。
