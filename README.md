# 浅笔记事 · 小说编辑器

<https://github.com/sueenthear/qianbiwriter>

> **名称说明**：应用名「**浅笔记事**」，包名 `com.qianbi.writer`，APK 归档为 `QianbiWriter-*.apk`；
> 工程文件夹仍沿用旧目录名 `Android4`。
> 之前装过旧包名（`com.aigames.android4`）版本的话，那是**另一个应用**：旧书稿在
> `Android/data/com.aigames.android4/files/NovelStudio/books/`，不会自动带过来 ——
> 把那些书文件夹拷到 `.../com.qianbi.writer/files/NovelStudio/books/`，或者用「导出 .book」再「导入」。

一个本地优先的 Android 小说写作工具：**一本书就是一个文件夹**，正文随时落盘，
可整本打包成带口令的 `.book` 包带走，也能原样导回来；书架仓库还能换成你自己选的外部文件夹。

| 能力 | 说明 |
| --- | --- |
| 书架 | 主界面网格展示，卡片显示**封面 + 书名**；点开进入**书本简介页** |
| 搜索 | 顶部搜索框：书名 / 作者 / 简介模糊匹配；`#玄幻#修仙` 多标签筛选（多标签之间是「与」） |
| 书本页 | 封面 / 书名 / 作者 / 标签组 / 简介 / 创建与最后改动时间 / **全书字数** + 可操作的目录树 |
| 多级嵌套 | 书 › 卷 › 卷 › 章 › …（深度任意）；**只有「章」能写正文，卷/目录只能写简介** |
| 目录页 | 每一级显示**类型标记（卷 / 章）+ 题目 + 本级简介**；新增统一在目录里做（`＋ 新卷/章` 或行内 `⋮`） |
| 文件夹存储 | 一本书 = 一个文件夹；正文子目录用 **32 位哈希**命名，章节重名也不冲突 |
| 书架仓库可换 | 默认放应用私有目录；也可**选一个外部文件夹**当书架（系统授权后长期有效） |
| 伪装模式 | 设置里可开：启动先显示一个**像样的 AI 聊天界面**，输入口令才进书架；输错显示 API 报错 |
| Web 协作 | 手机开服务器，**电脑浏览器里完整编辑**（局域网，访问码保护）；手机端有独立控制台，显示接入地址与电脑端动态 |
| 双向解析 | 导出 `.book`（zip 包，可选口令加密）→ 导入时校验口令后还原成书 |
| 实时保存 | 正文停止输入 0.7 秒即落盘；切换章节时会立刻补写一次，不丢字 |
| 目录加速 | 路径表缓存：一次扫描建索引，之后定位节点 / 读写正文不再重复扫盘；字数缓存按增量维护 |
| 字数统计 | 编辑页顶栏实时显示**本章字数 / 全书字数**（千分位） |
| 撤销 / 重做 | 放在编辑页**顶栏**，伸手就能按；连续输入自动合并成一步 |
| 字号调节 | 工具条 `A-` / `A+` 一键调，菜单里还能滑动调并实时预览；全局记住 |
| 快捷输入 | 侧边词表 **每本书独立**（存在该书文件夹里），点一下插到正文光标处 |

> 全部核心逻辑都有纯 JVM 单元测试（13 个，见 [§6](#6-测试)），不需要设备即可回归。

---

## 1. 快速开始

先从仓库拉下来（任意平台都能构建，无需密钥 —— 见 §9）：

```powershell
git clone https://github.com/sueenthear/qianbiwriter.git
cd qianbiwriter
```

```powershell
cd E:\aigames\Android4

# 1) 环境自检：JDK / SDK / platform / build-tools / adb / Wrapper / 签名
pwsh -File .\scripts\check-env.ps1

# 2) 构建 release APK（自动归档为 QianbiWriter-release-v1.0.apk）
pwsh -File .\scripts\build.ps1

# 3) 构建 debug APK
pwsh -File .\scripts\build.ps1 -Variant debug

# 4) 跑核心逻辑单元测试（不需要设备）
.\gradlew.bat :app:testDebugUnitTest

# 5) 安装到已连接设备并启动
pwsh -File .\scripts\install.ps1 -Variant release -Launch
```

| 命令 | 产物 |
| --- | --- |
| `build.ps1` | `QianbiWriter-release-v1.0.apk`（约 6.45 MB） |
| `build.ps1 -Variant debug` | `QianbiWriter-debug-v1.0.apk`（约 9.44 MB） |

> 运行时依赖只有 AndroidX / Compose 与 `androidx.documentfile`（SAF 目录支持）：
> JSON 用 `org.json`、压缩用 `java.util.zip`、加密用 `javax.crypto`，没有网络库、没有 ORM。

---

## 2. 页面与用法走查

**页面层级**（顶栏左上角箭头与**系统返回键**行为一致，逐级回退）：

> 返回键优先级：先关掉开着的抽屉 / 菜单，再回上一级页面（编辑页 → 书本页 → 书架页）；
> 在编辑页按返回时会**先把还没落盘的字补写一次**，手快也不会丢字。

```
书架页 ──点一本书──▶ 书本简介页 + 目录页 ──点某一章 / 右上角「写作」──▶ 编辑页
   ▲                        ▲                                        │
   └────────返回─────────────┴──────────────── 返回 ───────────────────┘
```

### 2.1 书架页（主界面）
- 网格卡片：**封面**（没有封面就用书名首字 + 渐变占位）+ **书名**。
- 顶部搜索框：直接输入按书名 / 作者 / 简介过滤；输入 `#标签` 按标签过滤，支持多个，
  例如 `#玄幻 #修仙`、`#玄幻#修仙` 都行。标签行里点标签也能快速加/去筛选条件。
- 右上角：`ⓘ` **书架仓库**（当前仓库 + 换目录）、`导入`、`新建`。
- 卡片右上角 `⋮`（或长按卡片）：书本信息 / 设置、导出 `.book`、删除。
- 点卡片 → 进入**书本页**（不是直接进编辑器）。

### 2.2 书本简介页 + 目录页
- 上半部分是**简介区**：封面、书名、作者、标签组、创建时间、最后改动时间、**全书字数**、
  目录节点数，以及简介正文。太长时这一块自己滚动。
- 下半部分是**目录**：多级树、可折叠，行右侧 `⋮` 能做全部目录操作
  （新建子级 / 同级、改题简介、上移、下移、移动到…、删除）；顶部有 `＋ 新卷/章`。
- 每一级显示**类型标记（卷 / 章）+ 题目 + 本级简介**（没写简介时退回显示哈希短码与子级数）。
- 目录用**底色 + 左侧色条**按类型区分：**金色 = 卷（只能写简介）**、**薄荷 = 章（可写正文）**，目录头部有一行图例。
- 新增时选类型：**目录（卷）**用来分组、只能写简介；**正文（章）**才是能写字的那一级。
- 右上角 `写作` 直接跳到**最近改动过的那一章**继续写。
- 点**章** → 进入编辑页并定位到那一章；点**卷** → **不**进编辑页，直接弹出这一卷的**简介编辑**浮窗。
- `⋮` 菜单：书本信息 / 设置、导出 `.book`、删除这本书。

### 2.3 新建与书本信息
- `新建`：填书名（必填）、作者、标签即可创建，创建后直接进入书本页。
- 书本信息 / 设置：**更换或移除封面**（自动压成 JPEG，长边最大 1080）、
  书名、作者、简介、标签组（空格 / 逗号 / 顿号 / `#` 分隔），
  以及只读的**创建时间、最后改动时间**和书文件夹名。

### 2.4 编辑页
- **顶栏**（从左到右）：返回书本页、当前章节名 + 保存状态与字数、
  **撤销**、**重做**、目录、词表、`⋮`。
  - 撤销 / 重做就在顶栏，右手拇指不用够底部；不可用时自动变灰。
  - 状态行形如：`已保存 12:30:05 · 本章 1,024 字 · 全书 12,345 字`。
- 顶栏下面是**面包屑**：`书名 › 卷 › 章 › 节`，一眼知道在哪一层。
- **工具条**（横向滑动）：`改题/简介`、`删除`、`A-`、`A+`。
  - 新增章节统一在**目录**里做；排序（上移 / 下移）与换父级（移动到…）也只在目录行右侧 `⋮` 里。
- 目录里点**卷**同样直接弹简介编辑浮窗（不切正文编辑）；点**章**才切换编辑目标。
- 选中**目录（卷）**时不出现输入框，只提示"这一级只能写简介"，并给一个「编辑本卷简介」按钮；
  要写正文就选中下面的**章**。
- **正文**用衬线字体，行高随字号自动按 1.75 倍走，适合长时间写作。
- 左侧抽屉 = 目录，右侧抽屉 = 快捷词表；点遮罩关闭。

### 2.5 字数统计与字号
- **字数**：编辑页顶栏实时显示本章与全书字数，写一个字就更新（全书字数按当前章增量算，
  不用反复扫盘）；书本页也会显示全书字数。
- **字号**：两种调法，都会**全局记住**（下次打开还是这个大小）——
  - 工具条上 `A-` / `A+` 一下一档（13–30 sp）；
  - `⋮ → 正文字号…` 打开滑块，实时预览示例文字，右下角可「恢复默认」。

### 2.6 快捷输入（每本书独立）
右侧抽屉按**分组**列出词条（默认分组是「角色」，可改成地名 / 术语…）：

- 点词条 → 插入到正文光标处（替换选中内容）；插入后焦点回到正文。
- `＋` 新增词条：名称（按钮上显示的）、插入文本（留空则插入名称）、分组。
- 词条 `✕` 删除。
- **词表跟着书走**：存在该书的 `glossary.json` 里，换一本书就是另一份，导出 `.book` 时一起打包。

### 2.7 导入 / 导出
- **导出**：`⋮ → 导出 .book`（书架卡片菜单、书本页、编辑页都能进），
  可留空口令（明文包）或设口令（整包加密），然后走系统文件选择器选保存位置。
- **导入**：书架 `导入` → 选文件。若包里是加密的，会先读明文头部发现"需要口令"，
  弹框输入；**口令不对立刻报错**，不会产生半本书。
- 导入产生的是**新书副本**（重新分配书 id，绝不覆盖已有书）。

### 2.8 设置界面（书架仓库 / 伪装模式）
书架右上角 `⚙` 打开设置：

| 操作 | 效果 |
| --- | --- |
| **选择外部文件夹** | 打开系统目录选择器（SAF）。选中后申请**持久读写权限**，之后每次启动都指向它 |
| **恢复默认** | 回到应用私有目录（默认仓库） |
| 当前仓库 | 对话框里直接显示仓库名与真实位置 |

- 换成外部文件夹后，书文件夹就直接躺在你选的目录里（例如 `Documents/我的小说/星海拾遗_4f2a9c7e/`），
  任何文件管理器、同步盘、电脑上都能直接看到与备份。
- **原来的书不会自动搬过去**：用「导出 .book」→「导入」搬一次即可
  （也可以直接把旧仓库里的书文件夹拷进新文件夹，结构一样，应用会自动识别）。
- 系统未授予持久权限、或权限后来被撤销 / 目录被删时，应用会**自动回落到私有目录**，不会卡死。

**伪装模式**

- 设置里打开开关并设置一个**解锁口令**（默认 `qianbi2026`，建议改成自己的）；
- 之后每次启动，应用先显示一个普通的 **AI 聊天界面**（顶栏「智能助手 · 在线」、气泡对话、输入框），
  在里面输入口令 → 进入真正的书架；
- 输入**像密钥的一串字符**（不含中文、不含空格）但不对 → 显示一条 **API 报错气泡**
  （`401 Unauthorized` / `429 Too Many Requests` / `400 model_not_found` 随机 + 随机 `request_id`），
  外人看到的是"这个聊天应用的密钥坏了"，而不是"这是个上锁的应用"；
- 输入带中文或空格的普通内容 → 给一句笼统回复，界面保持可用，看起来就是个 AI 助手；
- 设置里的「**立即进入伪装界面**」可以随时重新锁定（口令只存在本机 SharedPreferences 里）。

> 口令比对区分大小写、忽略首尾空白；口令与开关都不进书稿文件夹，也不随 `.book` 导出。

### 2.9 Web 协作（在电脑浏览器里写）

手机当服务器，电脑用浏览器连上来，**同一套书稿、同一份磁盘数据**。

**开起来只要三步**

1. 先到书的**简介页** `⋮ →「开启 Web 共享」`：只有被勾上的书会出现在电脑端（默认全部关闭）；
2. 书架右上角 `⤴` → **启动服务器并进入控制台**（会申请通知权限，前台服务需要它）；
3. 控制台里的「接入地址」给出完整链接，例如 `http://192.168.1.7:8765/?t=k7m2pxqd`，
   点「复制」发到电脑浏览器打开即可。

**控制台是一个独立页面，不是随手能关掉的弹窗**

- 点 `⤴` 就进入控制台，它占满整个屏幕，**只有点底部的「关闭 Web 协作」才会退出**；
- 返回键不退（只给一句提示）；服务器若从别处停了（通知栏的「停止共享」、被系统回收），
  控制台会跟着自动退出；
- **同一时间只让一边写**：控制台开着的时候手机这边不做编辑 —— 两头同时改同一节必然打架，
  索性一次把编辑权交给一边。要自己在手机上写，就先关掉 Web 协作；
- 「接入地址 / 电脑端动态 / 访问码 / 端口 / 保活」全在这一页里，不用来回弹窗。

**在电脑上能做什么**（和手机端基本对齐）

| 区域 | 能力 |
| --- | --- |
| 左栏书架 | 只列出**开了 Web 共享**的书；`书名 / 作者 / 简介` 与 `#标签` 搜索，语义和手机端一致 |
| 中栏目录 | 每层一根竖线 + 缩进，一眼看出子父级；卷金章绿，色标跟着层级一起缩进；`↑↓` 上下移、`✎` 改标题、`↰` 提到上一层、`✕` 删除；顶部「＋卷 / ＋章」 |
| 右栏编辑 | 改标题、编简介、写正文；停止输入 2.5 秒自动保存，`Ctrl+S` 立即保存；实时字数 |

- **卷 / 目录**在电脑上同样只有简介可写，正文输入框是灰的——和手机端规则一致；
- 每 5 秒对一次手机上的目录：手机端改了标题或加了章，电脑会自动跟上（有未保存改动时先不动）；
- **冲突保护**：保存时带上你读到的版本时间戳。若这一节已被手机（或另一个浏览器窗口）改过，
  服务端返回 409，页面顶部弹出黄条——**「载入最新」**或**「用我的覆盖」**，由你决定谁赢。

**手机端能看见电脑在干什么**

控制台里的「电脑端动态」会实时列出每一台电脑的请求（最新在最上面）：

```
14:32:07  打开协作页面
14:32:08  查看书架
14:32:09  打开目录      《星海拾遗》 · 192.168.1.7
14:32:41  保存一节      《星海拾遗》 · 192.168.1.7
14:33:02  被挡在门外     访问码不对 · 192.168.1.9
```

- 动作名是人话（打开目录 / 保存一节 / 删除一节 / 改标题…），失败会标黄并说明原因（访问码不对 / 这本书没有开共享 / 版本冲突）；
- 第二行是**书名 · 来源电脑的 IP**，一眼看出是谁在动；
- 只保留**最新 150 条**，且只放在内存里：**服务器一停就清空**，不写进任何文件、也不随 `.book` 导出。

**访问码**

- 默认随机 8 位（去掉了 `l / 1 / o / 0` 这些照着屏幕念容易错的字符），**可以自定义**；
- 链接里的 `?t=` 就是它；电脑首次打开后记在浏览器 `localStorage` 里，之后不用再输；
- 页面一载入就把地址栏里的访问码抹掉，免得留在浏览历史或截图里；
- 改访问码 / 改端口后，正在跑的服务器会自动重启（约半秒）。

**保活（准备长时间在电脑上写）**

| 手段 | 作用 |
| --- | --- |
| 前台服务 + 常驻通知 | 系统不回收；通知里直接显示接入地址，还带「停止共享」按钮 |
| `WifiLock` | 熄屏后 WiFi 不进省电模式，连接不断 |
| `PARTIAL_WAKE_LOCK` | 屏幕关掉后 CPU 不睡，TCP 不会半死不活 |
| 关掉电池优化 | 控制台里显示当前状态，一键跳系统页；**国内 ROM 尤其需要** |

> 代价是稍微费电，写完记得关掉服务器。Android 15+ 对 `dataSync` 类前台服务有每日时长限制，
> 长时间挂着可能被系统收走，重新打开开关即可。

**安全边界（重要）**

- 服务器**只监听局域网**，不对外网开放；没有云、没有账号、不请求任何外部服务；
- 权限只加了 `INTERNET` 与局域网 / 前台服务 / 通知那几项，**没有任何存储权限**
  （书稿本来就在应用目录或你选定的 SAF 目录里，服务器直接用同一套 `FsEntry` 读写）；
- **未开启 Web 共享的书，就算知道书 id 也返回 403**（与"书不存在"同样的响应，猜不出你有哪些书）；
- 网页本身（`/`、`/web/`）不含任何内容，所以不校验访问码；**所有 `/api/` 接口都要**；
- 全站是明文 HTTP——别在公共 WiFi 下开，不确定就把服务器关掉。

---

## 3. 数据格式：一本书就是一个文件夹

书架仓库（**表里的根目录就是它**）：

| 仓库 | 位置 |
| --- | --- |
| 默认 | `Android/data/com.qianbi.writer/files/NovelStudio/books/`（应用外部私有目录，**不需要任何存储权限**） |
| 外部（可选） | 你在系统选择器里挑的任意文件夹，例如 `Documents/我的小说/` |

```
<书架仓库>/
└─ 星海拾遗_4f2a9c7e/                 ← 书名slug_书id前8位
   ├─ book.info                       ← 书级元数据
   ├─ cover.jpg                       ← 封面（可选）
   ├─ glossary.json                   ← 快捷输入词表（每本书一份）
   └─ book/                           ← 正文部分
      ├─ 8c1d…（32 位哈希）/           ← 第一卷
      │  ├─ this.info                 ← 本级元数据
      │  ├─ content.txt               ← 本级正文（非叶子也能有）
      │  ├─ 1f0b…（32 位哈希）/        ← 第一章
      │  │  ├─ this.info
      │  │  └─ content.txt
      │  └─ 77ae…（32 位哈希）/        ← 另一章，标题可以重名
      └─ 3b9e…（32 位哈希）/           ← 直接挂在正文根下的一章（书-章）
```

### 3.1 `book.info`（书级元数据）

```json
{
  "format": 1,
  "id": "4f2a9c7e1b3d8a06f5c2e9147ab0d358",
  "title": "星海拾遗",
  "author": "某人",
  "summary": "简介…",
  "tags": ["科幻", "悬疑"],
  "createdAt": 1765000000000,
  "modifiedAt": 1765001234567,
  "cover": "cover.jpg",
  "order": ["8c1d…", "3b9e…"],
  "share": false
}
```

`order` 是**正文根下的子节点顺序**——因为文件夹名是哈希，排不出顺序，顺序必须单独记。
`share` 标记**这本书是否允许电脑端（Web 协作）编辑**，默认 `false`；旧数据没有这个字段时也按关闭处理。

### 3.2 `book/<32位哈希>/this.info`（每级节点的元数据）

```json
{
  "format": 1,
  "id": "8c1d0a7f5b2e39c4d6a8f1702e5b9c43",
  "title": "第一卷 · 启程",
  "summary": "本卷简介…",
  "createdAt": 1765000000000,
  "modifiedAt": 1765001111111,
  "children": ["1f0b…", "77ae…"],
  "kind": "group"
}
```

- 文件夹名 = `id` = **32 位小写十六进制**（MD5 长度），由「标题 + 纳秒时间 + 16 字节随机盐」派生，
  所以**章节重名不会有任何冲突**，改名也不会动文件夹（引用始终稳定）。
- `children` 记本级子节点顺序。
- `kind` 只有两个值：**`group`（卷 / 目录）** 与 **`chapter`（章）**。
  - 只有 `chapter` 会写 `content.txt`；对 `group` 调写入接口会被直接拒绝（返回 null，不落盘）。
  - 「章」可以挂到任何一级下面（卷里、卷的卷里，甚至别的章下面），层级不限。
  - 旧数据没有 `kind` 字段时自动推断：**有子级 → 卷，没有子级 → 章**。

### 3.3 容错
- `book/` 下手工丢进去的哈希文件夹会被自动接管：补写 `this.info`（题目先用文件夹名）并排进 `order`。
- 磁盘上多出来但 `children` 里没记的文件夹，会按名字排到末尾并写回顺序。
- 缺 `book.info` 的书文件夹会被补一份元数据（书名为文件夹名）。

---

## 4. `.book` 包格式与双向解析

`.book` 就是一个 zip（内部结构与书文件夹一一对应），可以选择用口令整包加密：

```
偏移   长度   内容
0      8      magic  "NBOOKPKG"
8      1      version = 1
9      1      flags   bit0 = 是否口令加密
10     4      iterations  PBKDF2 迭代次数（明文包为 0）
14     16     salt       随机盐（明文包全 0）
30     32     verifier   HMAC(macKey, "NBOOK-VERIFY-V1")（明文包全 0）
62     …      payload
               ├ 明文包：zip 原始字节
               └ 加密包：AES-256-CTR(zip) + 末尾 32B HMAC(macKey, 密文)
```

**加密方案**（`data/CryptoUtil.kt`）：

| 环节 | 做法 |
| --- | --- |
| 口令派生 | PBKDF2-HMAC-SHA256，120000 次迭代，16 字节随机盐 → 64 字节 |
| 加密密钥 | 派生结果的**前 32 字节** → AES-256-CTR |
| 认证密钥 | 派生结果的**后 32 字节** → HMAC-SHA256 |
| 快速验密 | 头部 `verifier` 与本地重算值比对，**口令错立刻抛错**，不必等整包解完 |
| 完整性 | 包尾 32 字节 HMAC 覆盖全部密文，篡改 / 截断会被检出 |

> CTR 计数器每次从 0 开始，但每次导出都用新随机盐 → 密钥唯一，因此不会出现密钥流复用。

**导入校验链**：魔数 → 版本 → （需要口令时）`verifier` 比对 → 尾部 HMAC → 解 zip → 必须有 `book.info`
→ 有同 id 的书时重新分配 id（副本导入，不覆盖）。解包还有 zip-slip 路径越界防护和解压体积上限。

---

## 5. 工程结构

```
Android4/
├─ app/src/main/java/com/qianbi/writer/
│  ├─ MainActivity.kt                # 唯一 Activity：拉起 NovelApp
│  ├─ data/                          # 逻辑层（可脱机单测）
│  │  ├─ FsEntry.kt                  # 文件系统抽象 + java.io.File 后端（原子写）
│  │  ├─ DocFsEntry.kt               # SAF 后端：用户自选的外部文件夹（DocumentFile）
│  │  ├─ StoragePrefs.kt             # 书架仓库位置（私有目录 / 外部 tree uri）与持久授权
│  │  ├─ EditorPrefs.kt              # 写作偏好：正文字号 / 行高（全局）
│  │  ├─ Models.kt                   # BookMeta / NodeInfo / BookDoc / Glossary + 文件名常量
│  │  ├─ BookStore.kt                # 书本仓库：加载/建删改/移动/正文读写/词表/封面/字数
│  │  ├─ BookPackage.kt              # .book 导出与导入（zip + 口令）
│  │  ├─ CryptoUtil.kt               # PBKDF2 / AES-256-CTR / HMAC-SHA256
│  │  ├─ HashUtil.kt                 # 32 位哈希 id、文件夹名 slug
│  │  ├─ WebPrefs.kt                 # Web 协作偏好：服务器开关 / 端口 / 访问码
│  │  └─ ImageUtil.kt                # 封面解码降采样 / 压成 JPEG
│  ├─ web/                           # Web 协作服务端（纯 java.net，无第三方库）
│  │  ├─ Http.kt                     # 极简 HTTP/1.1 解析与响应
│  │  ├─ WebApi.kt                   # REST 路由 + JSON（共享边界与冲突检测）
│  │  ├─ WebServer.kt                # ServerSocket + 线程池 + 端口重试 + 静态资源
│  │  ├─ WebService.kt               # 前台服务：常驻通知 + WifiLock + WakeLock
│  │  ├─ KeepAlive.kt                # 电池优化状态查询与跳转
│  │  ├─ WebLog.kt                   # 电脑端动态：内存环形缓冲，停服即清空
│  │  └─ NetInfo.kt                  # 枚举局域网 IPv4，拼出接入 URL
│  └─ ui/                            # Compose 界面
│     ├─ NovelApp.kt                 # 三个目的地：书架 → 书本页 → 编辑页
│     ├─ HomeScreen.kt               # 书架网格、搜索、新建/导入/导出/删除/换仓库
│     ├─ BookDetailScreen.kt         # 书本简介页 + 目录页（写作入口）
│     ├─ EditorScreen.kt             # 编辑页：顶栏(撤销/重做/字数) + 工具条(含字号) + 目录/词表抽屉
│     ├─ Dialogs.kt                  # 书本信息、导出、口令、节点信息、移动、词条
│     ├─ PromptDialog.kt             # 通用单行输入框（新建章节等）
│     ├─ Common.kt                   # 搜索解析、时间格式、TagChip 等小组件
│     ├─ WebConsoleScreen.kt         # Web 协作控制台（独立页面）：地址 / 动态 / 端口 / 访问码 / 保活
│     └─ Theme.kt                    # Material3 深色主题
├─ app/src/main/assets/web/         # 电脑端网页（原生 JS，零框架）：index.html / style.css / app.js
├─ app/src/test/java/com/qianbi/writer/
│  ├─ data/NovelCoreTest.kt        # 结构 / 嵌套 / 排序 / 移动 / 打包加解密
│  ├─ data/NodeKindTest.kt         # 卷章类型规则（只有章能写正文）
│  └─ web/WebServerTest.kt         # Web 服务器端到端（真套接字打请求）
├─ scripts/{check-env,build,install}.ps1                          # 环境自检 / 构建 / 安装
├─ gradle/wrapper/                   # Gradle 8.9 Wrapper（无需本机装 Gradle）
└─ README.md
```

**存储层为什么长这样**：`BookStore` 只认 [FsEntry]（`list / child / readBytes / writeBytes / mkdirs /
deleteRecursively`…），于是同一套"书 › 卷 › 章"逻辑同时跑在
`FileFsEntry`（私有目录，写入用临时文件 + 改名保证原子）与
`DocFsEntry`（外部文件夹，走 `DocumentFile` / `ContentResolver`）之上，上层代码一行都不用改。

### 技术栈

| 项目 | 版本 |
| --- | --- |
| 语言 | Kotlin 2.0.21（`kotlin.plugin.compose`） |
| UI | Jetpack Compose（BOM 2024.09.03）+ Material3 |
| 存储抽象 | `androidx.documentfile:documentfile:1.0.1`（SAF 外部目录） |
| 构建 | Gradle 8.9（Wrapper）+ AGP 8.5.2 + JDK 17 |
| SDK | compileSdk 34 / targetSdk 34 / **minSdk 26** |
| 包名 | `com.qianbi.writer`（debug 后缀 `.debug`） |

依赖仓库在 `settings.gradle.kts` 中优先走阿里云镜像，失败回退 `google()` / `mavenCentral()`。

---

## 6. 测试

```powershell
.\gradlew.bat :app:testDebugUnitTest          # 全部通过（28 个用例）
# 报告：app/build/reports/tests/testDebugUnitTest/index.html
```

覆盖的是**最容易出错、也最关键**的部分（纯 JVM，不需要设备；跑在 `FileFsEntry` 后端上）：

| 用例 | 验证内容 |
| --- | --- |
| 书本文件夹结构与任意深度嵌套 | 5 级嵌套、`this.info` 落位、32 位哈希命名、非叶子放正文、面包屑、标签 |
| 重名章节落在不同的哈希文件夹 | 同名章节互不覆盖、顺序正确 |
| 删除排序与跨级移动 | 上移/下移、删除、跨级 reparent、拒绝移动到自己的子孙下 |
| 手工丢进 book 目录的文件夹会被自动接管 | 容错：补 `this.info` 并排进顺序 |
| PBKDF2 派生与 AES-CTR 往返 | 加解密往返、同盐可复现、口令错时 verifier 不匹配 |
| 明文包导出导入往返 | 结构与内容逐字节一致（`this.info` / `content.txt` / 词表） |
| 加密包的密码校验与往返 | 头部报"需口令"、错口令抛错且**不留残书**、正确口令后内容一致 |
| 密文被篡改会被 HMAC 检出 | 改 1 个字节即拒绝导入 |
| 不是 book 包的输入会被拒绝 | 魔数校验 |
| 导入的书直接出现在书架并可继续编辑 | 导入后可继续写入并读回 |
| 只有章能写正文，目录（卷）只能写简介 | 对卷调写入被拒绝、磁盘上不生成 `content.txt`；类型落盘可读回 |
| 卷可以套卷，章可以挂在任意层级 | 卷 › 卷 › 章 › 章 四层；最深一层照样能写；字数增量维护正确 |
| 旧数据没有 kind 字段时按有无子级推断 | 无子级 → 章（可写），有子级 → 卷（不可写）|
| **Web**：未共享的书既不给列表也访问不到 | 书列表只含共享的书；未共享 / 不存在的 id 都是 403 |
| **Web**：访问码校验 | 码不对或没带码 → 401；网页本身（`/`、`/web/x.css`）不校验、`..` 穿越被拒 |
| **Web**：目录树层级与类型 | 卷 depth 0 / 章 depth 1、`parentId` 对得上、`writable` 只有章为真 |
| **Web**：读单节 | 正文与字数读回正确 |
| **Web**：别的 Store 实例改了书立刻可见 | 服务器每请求重算索引，不留旧缓存 |
| **Web**：保存正文与简介 | 落盘内容正确 |
| **Web**：冲突保护 | 旧时间戳 → 409 且带回服务器内容；`base=0` 可强制覆盖 |
| **Web**：卷不能写正文 | 往卷里写正文 → 400 `not_writable` |
| **Web**：新建 / 移动 / 删除 | 都落到磁盘；跨层级移动后顺序正确 |
| **Web**：改标题 | 标题变更写回 `this.info` |
| **Web**：未知路径 | → 404 |
| **Web**：首选端口被占也能起来 | 端口被占用时自动往后找 |
| **Web**：每次操作都记进手机端动态 | 带动作名、书名、状态与来源 IP；401 / 403 也照记 |
| **Web**：动态只留最新若干条 | 环形缓冲上限 150，最新的在最前，清空后为空 |

> `DocFsEntry`（外部文件夹后端）依赖 Android 的 `DocumentFile`，本地 JVM 测不了，
> 需要在真机上过一遍：换仓库 → 新建书 → 写几章 → 重启应用看是否还在。

---

## 7. 本机实测记录

| 检查项 | 结果 |
| --- | --- |
| JDK | `C:\Program Files\Microsoft\jdk-17.0.12.7-hotspot`（17.0.12） |
| Android SDK | `C:\Android\Sdk`（platform `android-34`、build-tools `34.0.0`、`adb 1.0.41`） |
| Gradle Wrapper | 8.9，分发已缓存于 `%USERPROFILE%\.gradle\wrapper\dists` |
| `:app:compileDebugKotlin` | `BUILD SUCCESSFUL`（仅 `Icons.Filled.ArrowBack` 一条弃用提示） |
| `:app:testDebugUnitTest` | `BUILD SUCCESSFUL`（`--rerun-tasks` 强制重跑），**28 个用例全部通过**（NovelCoreTest 10 + NodeKindTest 3 + WebServerTest 15） |
| `assembleDebug` | `BUILD SUCCESSFUL` → `QianbiWriter-debug-v1.0.apk`（9.44 MB） |
| `assembleRelease` | `BUILD SUCCESSFUL` → `QianbiWriter-release-v1.0.apk`（6.45 MB） |
| `node --check app.js` | 电脑端脚本语法检查通过 |
| APK 权限 | `aapt2 dump permissions`：`INTERNET` / `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` / `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` / `POST_NOTIFICATIONS` / `WAKE_LOCK` / `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（**无存储权限**） |
| APK 组件 | `aapt2 dump xmltree`：`service com.qianbi.writer.web.WebService`（`exported=false`、`foregroundServiceType=0x1`=`dataSync`）；Activity `windowSoftInputMode=0x10`（`adjustResize`） |
| APK 身份 | `aapt2 dump badging`：`package com.qianbi.writer`、label「浅笔记事」、启动 Activity `com.qianbi.writer.MainActivity`；debug 变体包名 `com.qianbi.writer.debug` |
| 设备实机 | 未连接 USB 设备，**未做真机点击走查**（见下） |

**已知未验证项**（本机没有 Android 设备，请在真机上过一遍）：

1. **换成外部文件夹当书架**（SAF 目录选择、持久权限、在外部目录里增删改书）；
2. 真机上的 SAF 导出/导入（文件选择器、`.book` 落盘）；
3. 三段式导航（书架 → 书本页 → 编辑页）与顶栏撤销 / 重做的实际手感；
4. 字号滑块在真机上的预览与记忆；
5. 封面选图与长列表滚动性能；
6. 软键盘弹起时的编辑区布局（用了 `enableEdgeToEdge`；`windowSoftInputMode` 已显式设为 `adjustResize`，
   避免系统平移与 Compose 内边距各抬一次）；
7. **Web 协作整条链路**：手机开服务器 → 电脑浏览器打开 → 编辑 → 熄屏后是否仍连着；
   改端口 / 访问码后的自动重启；电池优化跳转页在各家 ROM 上能否打开。

---

## 8. 环境要求与配置

| 组件 | 要求 | 检查方式 |
| --- | --- | --- |
| JDK | 17 或更高，`JAVA_HOME` 指向它 | `java -version` |
| Android SDK | `platforms;android-34`、`build-tools;34.0.0` | `pwsh -File .\scripts\check-env.ps1` |
| platform-tools | 提供 `adb`（安装到设备时需要） | `adb version` |
| PowerShell | 7.x（`pwsh`）推荐 | `pwsh -v` |
| Gradle | **不需要**单独安装 | Wrapper 自动下载 8.9 |

| 位置 | 作用 | 备注 |
| --- | --- | --- |
| `local.properties` | `sdk.dir=C\:\\Android\\Sdk` | 不入库，换机器需改 |
| `JAVA_HOME` | Gradle 使用的 JDK | 指向 JDK 17 |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | SDK 路径（可选） | 命令行参数 > 环境变量 > `local.properties` |
| `gradle.properties` | `-Xmx2048m`、`android.useAndroidX=true` | 内存不足时调大 `-Xmx` |

用 sdkmanager 补齐组件：

```powershell
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"
sdkmanager --licenses
```

---

## 9. 常用命令与脚本

```powershell
pwsh -File .\scripts\check-env.ps1                     # 环境自检
pwsh -File .\scripts\build.ps1 -Variant release -Clean # 清理后构建 release
pwsh -File .\scripts\install.ps1 -Launch               # 装 debug 并启动

.\gradlew.bat assembleDebug          # 构建 debug APK
.\gradlew.bat assembleRelease        # 构建 release APK
.\gradlew.bat :app:testDebugUnitTest # 跑核心逻辑单测
.\gradlew.bat installDebug           # 构建并安装
.\gradlew.bat clean                  # 清理

# 直接看书本文件夹（默认仓库；真机 / 模拟器）
adb shell ls /sdcard/Android/data/com.qianbi.writer/files/NovelStudio/books
adb pull /sdcard/Android/data/com.qianbi.writer/files/NovelStudio/books ./books-backup

adb install -r .\QianbiWriter-release-v1.0.apk            # 安装（-r 覆盖）
adb shell am start -n com.qianbi.writer/.MainActivity
adb logcat -s AndroidRuntime:E                        # 只看崩溃日志
```

脚本参数：`check-env.ps1 [-SdkDir <path>]`（退出码 = 未通过项数）；
`build.ps1 [-Variant release|debug] [-Clean] [-NoCopy]`；
`install.ps1 [-Variant release|debug] [-Launch] [-ApkPath <path>]`。

### 签名

**仓库里不含 `release.keystore`** —— 签名密钥不进版本库。`app/build.gradle.kts` 在根目录
找不到它时会自动回退到 debug 签名，所以 clone 下来直接 `assembleRelease` 也能出包
（只是签名不同，不能覆盖安装你已签名的版本）。

要有自己的正式签名，先生成一个：

```powershell
keytool -genkeypair -v -keystore release.keystore -alias 你的别名 `
  -keyalg RSA -keysize 2048 -validity 10000
```

再把 `app/build.gradle.kts` 里 `signingConfigs` 的口令与别名改成你自己的。
更稳的做法是把口令放进 `~/.gradle/gradle.properties` 或环境变量，别提交进仓库。

```powershell
& "$env:ANDROID_HOME\build-tools\34.0.0\apksigner.bat" verify --print-certs .\QianbiWriter-release-v1.0.apk
```

---

## 10. 想改什么改哪里

| 想改什么 | 改哪里 |
| --- | --- |
| 应用名（桌面显示） | `app/src/main/res/values/strings.xml` → `app_name` |
| 主题色 | `ui/Theme.kt` 的 `darkColorScheme(...)` |
| 字号范围 / 默认值 | `data/EditorPrefs.kt` → `MIN_FONT_SP` / `MAX_FONT_SP` / `DEFAULT_FONT_SP` |
| 行高比例 | `data/EditorPrefs.kt` → `lineHeightSp()`（默认字号 × 1.75） |
| 正文字体 | `ui/EditorScreen.kt` 里 `BasicTextField` 的 `textStyle`（`FontFamily.Serif`） |
| 自动保存节流时间 | `ui/EditorScreen.kt` 里 `LaunchedEffect` 中的 `delay(700)` |
| 撤销合并间隔 | `ui/EditorScreen.kt` 的 `EditorHistory.onEdit`（`>= 600` 毫秒） |
| 默认书架位置 | `data/StoragePrefs.kt` → `defaultBooksDir()` |
| PBKDF2 迭代次数 | `data/CryptoUtil.kt` → `DEFAULT_ITERATIONS`（注意会改变包格式兼容性） |
| 版本号 | `app/build.gradle.kts` → `versionCode` / `versionName` |
| 启动图标 | `res/drawable/ic_launcher_foreground.xml`、`ic_launcher_background.xml` |
| 最低支持版本 | `app/build.gradle.kts` → `minSdk` |

---

## 11. 常见问题

**Q1：书稿存在哪？换手机怎么搬？**
默认在 `Android/data/com.qianbi.writer/files/NovelStudio/books/`；也可以在书架右上角 `ⓘ` 里
**换成自己选的外部文件夹**（例如 `Documents/我的小说`），那样文件管理器/同步盘直接可见。

**Q2：卸载应用书稿会没吗？**
默认仓库在应用私有目录里，**卸载会一起删掉**。两个保险做法：
把书架仓库换成外部文件夹；或者常用「导出 .book」另存一份。

**Q3：快捷词表是每本书一份吗？**
是。词表存在该书的 `glossary.json` 里，A 书的角色名不会跑到 B 书去；
导出 `.book` 时词表一起打包，换设备导入后还在。

**Q4：撤销 / 重做在哪？**
编辑页顶栏右侧（书名右边），不可用时是灰的。一次撤销会回到最近一段连续输入之前
（连续输入 0.6 秒内算一段）。

**Q5：字号怎么调？改完会记住吗？**
编辑页工具条 `A-` / `A+`，或 `⋮ → 正文字号…` 拖滑块。设置是全局的，下次打开仍是这个字号。

**Q6：换成外部文件夹后，原来的书不见了？**
换仓库只是换了"看哪个文件夹"，旧仓库的书还在原处、不会被删。
把书搬过去有两种办法：用「导出 .book」→「导入」；或者直接把旧仓库里的书文件夹拷进新文件夹
（结构完全一样，应用能自动识别）。

**Q7：文件管理器看不到 `Android/data`？**
Android 11+ 对部分文件管理器限制了该目录。用「导出 .book」、`adb pull`，
或者干脆把书架换成外部文件夹。

**Q8：导入说"密码错误"，但我确定没输错？**
口令区分大小写与空格；包头的 verifier 校验是精确比对，不存在"差不多对"。

**Q9：导入后书名多了个 `_2` 后缀？**
说明书架里已有同一本书（同 id），导入**不会覆盖**原书，而是作为副本落盘。

**Q10：构建报 `SDK location not found`**
检查 `local.properties` 的转义格式（冒号前要有反斜杠）：
```properties
sdk.dir=C\:\\Android\\Sdk
```
或设置环境变量 `ANDROID_HOME`。

**Q11：依赖下载慢 / 超时**
`settings.gradle.kts` 已把阿里云仓库放在最前。需要代理时在 `%USERPROFILE%\.gradle\gradle.properties` 加：
```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7890
```

**Q12：`adb devices` 看不到设备**
1. 手机开「开发者选项 → USB 调试」，插线后选「传输文件」；
2. 弹框选"始终允许"；
3. 仍不行：`adb kill-server; adb start-server`。

**Q13：安装报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`**
设备上已有同包名但签名不同的应用，先卸载：`adb uninstall com.qianbi.writer`
（debug 包名是 `com.qianbi.writer.debug`）。

**Q14：`Unsupported class file major version` / `Unsupported Java`**
Gradle 用到了 JDK 8。把 `JAVA_HOME` 指向 JDK 17，重开终端，再 `.\gradlew.bat --stop`。

**Q15：构建产物在哪？**
`app/build/outputs/apk/<variant>/app-<variant>.apk`；用 `build.ps1` 会自动复制到项目根目录并重命名。

**Q16：电脑打不开那个地址？**
按顺序查：① 手机和电脑在**同一个 WiFi**（手机连热点就都要连它，别一个 WiFi 一个热点）；
② 用的是手机上那串**带 `?t=` 的完整链接**，缺访问码会被挡在访问码页；
③ 手机端控制台里的「接入地址」是不是空的——为空说明没拿到局域网 IP，确认 WiFi 真的连上了（而不是只有蜂窝）；
④ 服务器开关是不是还在「正在运行」；⑤ 电脑上的代理软件可能拦了局域网地址，临时关掉试试。

**Q17：电脑上的改动，手机什么时候能看到？两边同时改怎么办？**
手机端每次进书都会重新扫盘，退出编辑页再进就是最新的。电脑这边每 5 秒拉一次手机上的目录，
手机改了标题或加了章会自动跟上（电脑有未保存改动时会先不动，避免冲掉你正在写的）。
两边同时改同一节时，后保存的一方会看到「载入最新 / 用我的覆盖」，由你决定谁赢。

**Q18：开着服务器费电吗？会被系统杀掉吗？**
开着时持有 WiFi 锁和 CPU 唤醒锁，确实比平时费电，写完请关掉服务器。
被杀的头号原因是没关电池优化——控制台「保活」一节会显示状态并给一键跳转入口。
被杀后重新打开开关即可；书稿不会因此损坏（正文都是临时文件 + 改名的原子写）。

**Q19：手机和电脑能同时改同一本书吗？**
不能，而且是有意的。点 `⤴` 进入控制台就等于把编辑权交给电脑：控制台开着时手机这边不做编辑，
控制台一关电脑立刻断开。想让手机重新能写，关掉控制台即可。
万一还是撞上了（比如同一个浏览器开了两个窗口），后保存的一方会看到「载入最新 / 用我的覆盖」。
