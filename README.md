# 冰箱先知 Fridge Prophet

> 拍一张冰箱照片，AI 自动知道「有什么、还能吃多久、今天吃什么、缺什么」，
> 并根据不同用户的饮食目标生成个性化菜谱和采购清单。

参赛项目。本仓库包含后端服务与 Android 客户端。

---

## 一、当前进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| 阶段一 | FastAPI 后端（数据模型 / 接口 / AI 接入 / 采购算法） | ✅ **已完成，251 项端到端测试全部通过** |
| 阶段二 | Android 客户端（Kotlin + Compose） | ✅ **已完成，真机验收通过** |
| 阶段三 | 后端部署到公网 | ⏳ 待开始（等云账号） |
| 阶段四 | 打包签名 APK | 🔄 **签名已用 apksigner 验证，CI 已跑通；待配 Secrets 让 CI 出正式签名包** |

> 目前唯一挡在「可演示」前面的，是 **`DASHSCOPE_API_KEY`**（阿里云百炼）。
> 没配它后端会走 MOCK 模式 —— 整条链路能跑通，但拍照识别返回的是内置演示数据。
> 拿到 Key 后填进 `backend/.env` 重启即可，代码不用动。

> **不知道从哪下手？先看 [`docs/00-行动清单.md`](docs/00-行动清单.md)。**
> 那份文档把全部待办按依赖顺序排好了，并标注了哪一步需要你亲自做。
>
> **想先确认后端是活的？** 跑起服务后浏览器打开 <http://127.0.0.1:8000/docs>，
> 按 [`docs/05-用接口文档跑通全链路.md`](docs/05-用接口文档跑通全链路.md) 点一遍，
> 不用装 Android 就能验证「识别 → 入库 → 菜谱 → 采购」整条链路。

## 二、技术选型

| 层 | 选型 | 说明 |
|---|---|---|
| 客户端 | Kotlin + Jetpack Compose + MVVM | 原生 Android，与策划书一致 |
| 后端 | Python 3.13 + FastAPI + SQLAlchemy 2.0 | 承载全部 AI 编排与业务规则 |
| 数据库 | Supabase 托管 PostgreSQL | 免运维；开发期自动退回 SQLite |
| 图片存储 | Supabase Storage | 冰箱照片，免费 1GB，自带 CDN |
| AI | 通义千问 Qwen-VL（百炼平台） | 视觉识别 + 菜谱生成，国内直连 |
| 部署 | 腾讯云轻量（中国香港节点） | 免备案，国内访问快 |

### 架构

```
┌─────────────────┐
│  Android App    │  Kotlin + Compose
│  (拍照/库存/菜谱) │
└────────┬────────┘
         │  HTTPS + JWT
         ▼
┌─────────────────┐
│  FastAPI 后端    │  ← 规则系统负责约束
│  ├ 视觉识别接口   │     AI 负责理解和生成
│  ├ 菜谱生成接口   │
│  ├ 库存/采购计算  │  ← 纯确定性算法，不用 AI
│  └ 用户画像       │
└────────┬────────┘
         │
    ┌────┴────┐
    ▼         ▼
┌────────┐ ┌──────────────┐
│Supabase│ │通义千问 Qwen-VL│
│ PG+存储 │ │  视觉 + 语言   │
└────────┘ └──────────────┘
```

## 三、目录结构

```
Fridge-Prophet/
├── README.md                  ← 你在这里
├── start-backend.bat          ← 【双击这个】一键启动后端，不用会命令行
├── .gitignore                 ← 已排除 .env / 签名密钥 / 构建产物
├── .github/workflows/
│   └── android.yml            ← 推代码自动构建，产物在 Actions 页面下载
├── docs/                      ← 每一步的详细操作指南
│   ├── 00-行动清单.md          ← 从这里开始：全部待办按依赖排序
│   ├── 01-账号注册.md          ← Supabase / 百炼 / 腾讯云 怎么开
│   ├── 02-本地运行.md          ← 怎么在本地把后端和 App 都跑起来
│   ├── 03-部署到公网.md        ← 服务器 + Nginx + systemd + HTTPS
│   ├── 04-打包APK.md           ← 签名密钥 / release 构建 / GitHub Actions
│   ├── 05-用接口文档跑通全链路.md
│   ├── 06-本轮优化说明.md      ← 第一轮反馈修复
│   └── 07-第二轮反馈修复.md    ← 家庭共享 / 做菜扣库存 / 配色 / 真机验收
├── tools/
│   ├── start-backend.sh       ← 后端启动脚本（.bat 实际调用的就是它）
│   ├── new-keystore.sh        ← 一键生成签名密钥并写好配置
│   ├── import-images.py       ← 批量导入菜谱图 / 食材图
│   ├── dedupe-recipes.py      ← 清理库里的重复菜谱行
│   └── verify-recipe-dedupe.py← 打真实库验证去重效果
├── build-debug.bat            ← 【双击】自动检测局域网 IP 并打出真机能用的包
├── make-keystore.bat          ← 【双击】生成签名密钥
├── backend/                   ← 后端服务（已完成，251 项测试全过）
│   ├── app/
│   │   ├── main.py
│   │   ├── core/              config.py（全部配置）· security.py（JWT）
│   │   ├── db/                引擎与会话 · migrate.py（轻量自动补列）
│   │   ├── models/            ORM：用户 / 家庭 / 库存 / 菜谱 / 采购 / 广场（18 张表）
│   │   ├── schemas/           Pydantic 出入参
│   │   ├── services/          AI 调用 · 视觉识别 · 菜谱生成 · 采购计算 · 家庭共享 · 图片存储
│   │   └── api/v1/            auth / users / family / inventory / vision
│   │                          recipes / shopping / tips / social
│   ├── scripts/               check_supabase.py · check_ai.py · smoke_http.py
│   ├── tests/test_e2e.py      251 项端到端测试
│   ├── requirements.txt
│   ├── .env.example
│   └── run.py
└── android/                   ← Android 客户端（Kotlin + Compose）
    ├── keystore.properties.example  签名配置模板（复制成 keystore.properties 用）
    ├── gradle/libs.versions.toml   版本目录（全部版本已逐一验证存在）
    ├── app/build.gradle.kts        构建配置 · 签名配置 · 后端地址注入
    ├── app/src/test/java/.../      契约测试（MockWebServer，不需要真机）
    │   ├── DeleteContractTest.kt   删除接口的 204 空响应体契约
    │   └── FamilyContractTest.kt   家庭接口的 null 契约（抓出过一个真 bug）
    └── app/src/main/java/com/fridgeprophet/app/
        ├── core/            令牌存储 · 统一错误封装 · 跨页刷新总线
        ├── data/
        │   ├── remote/      DTO · Retrofit 接口 · OkHttp 客户端
        │   └── repository/  六个仓库，界面只跟它们打交道
        ├── di/              Hilt 模块
        └── ui/
            ├── components/  复用组件（卡片 / 标签 / 空状态）
            ├── theme/       白绿橙品牌色 · 卡片与背景的三层灰阶
            └── screens/     splash · auth · onboarding · main(6 Tab)
                             home · plaza · fridge · recipes · shopping · profile
                             scan · family · tips
```

## 四、后端已完成的能力

**AI 模块（策划书第十四节）**

| 模块 | 实现位置 | 说明 |
|---|---|---|
| AI 1 视觉识别 | `services/vision_service.py` | 照片 → 食材名称/数量/单位/置信度 |
| AI 2 菜谱生成 | `services/recipe_service.py` | 库存 + 画像 → 结构化菜谱 JSON |
| AI 3 用户画像 | `api/v1/recipes.py` | 记录点击/收藏/烹饪/跳过，推导口味偏好 |
| AI 4 采购规划 | `services/shopping_service.py` | 需要 − 现有 = 缺少，自动合并同类项 |

**三个关键设计决策**

1. **规则负责约束，AI 负责生成。** 模型只写菜名、步骤、营养估算；
   「食材有没有、缺多少」由后端拿真实库存重算（`_recompute_availability`），
   不采信模型自报的 `available` 字段。这样才不会出现「AI 说你有豆腐，其实冰箱里没有」。

2. **采购计算不用 AI。** 「需要 − 现有 = 缺多少」是纯算术，交给模型只会算错。
   含单位归一化（kg→g、升→ml）和跨菜谱同类项合并。

3. **识别结果不直接入库。** `POST /vision/scan` 只返回候选，
   必须再调 `POST /inventory/confirm` 才写入。这是策划书强调的用户确认机制。

4. **跨用户的数据可见范围只能有一处定义。** 家庭共享靠
   `services/family_service.py::visible_user_ids()` —— 全项目**唯一**决定
   「谁的数据算我的」的地方，库存 / 菜谱 / 采购的读查询全部走它。
   漏改一处就会出现「冰箱里看得到、菜谱里看不到」这种自相矛盾。

5. **单位对不上时不猜。** 做菜扣库存时，库存记「1 盒豆腐」、菜谱要「300 g」，
   预览里的建议量**留空**让用户自己填。给 0 是错的（用户以为扣了，其实没有），
   给 300 更错（可能把整盒扣没）。和缺料判断是同一条原则。

**家庭共享**

家人各自用自己的账号登录，凭**邀请码**加入同一个家庭，之后冰箱、菜谱、
采购清单全家共享；个人画像和健康数据仍然私有。

| 接口 | 作用 |
|---|---|
| `GET/POST /family` | 看我的家庭 / 创建家庭 |
| `POST /family/join` | 用邀请码加入 |
| `POST /family/invite-code` | 换码（顺便定新成员的身份：成员 / 只读） |
| `PATCH` `DELETE` `/family/members/{id}` | 调身份 / 移出（只有家庭主能做） |
| `POST /family/leave` | 退出（**家庭主退出 = 解散家庭**） |

> ⚠️ 和「家人的忌口」（`/users/family`）**不是一回事**，两者并存：
> 前者是**账号关联**（共享实际数据），后者是**忌口档案**
> （爷爷奶奶没账号就记忌口，爸妈有账号就邀请进来）。

**做菜扣库存**

浏览菜谱时可以点「我做这道菜了」：先给一份**扣减预览**（会用掉什么、各多少、
扣完哪样会用光），用户可以改量、可以取消，确认后才真的扣减。

| 接口 | 作用 |
|---|---|
| `GET /recipes/{id}/cook-plan` | 只算不扣的预览 |
| `POST /recipes/{id}/cook` | 按实际用量扣减 |

**无密钥也能完整演示。** 未配置 `DASHSCOPE_API_KEY` 时自动降级为 MOCK 模式，
返回内置食材与菜谱，整条链路照常跑通。比赛现场网络出问题时这是保命机制。

## 五、路线图

### 现在 → 你要做的第一件事

**先跑本地验证，不需要任何账号。**

**最简单的做法：在文件管理器里找到项目根目录，双击 `start-backend.bat`。**

> 所谓「项目根目录」就是包含 `README.md`、`backend`、`android`、`tools` 这几个东西的文件夹，
> 也就是本文件所在的位置。不需要打开任何终端。

双击后会弹出一个黑窗口，脚本自动完成：建虚拟环境 → 装依赖 → 复制配置 →
打印你的局域网 IP → 启动服务。首次运行需要 1-3 分钟装依赖，之后几秒就好。

窗口里出现这两行就成功了：

```
  接口文档: http://127.0.0.1:8000/docs
  健康检查: http://127.0.0.1:8000/health
```

浏览器打开 <http://127.0.0.1:8000/docs> 就能看到全部接口。
**这个窗口不要关**，关掉服务就停了。按 `Ctrl+C` 可以停止。

<details>
<summary>如果你习惯用命令行（点击展开）</summary>

```bash
cd "G:/workbuddy/任务路径/Fridge-Prophet"    # 进入项目根目录
bash tools/start-backend.sh
```

`start-backend.bat` 做的事和这条命令完全一样，只是帮你把「打开终端、cd 到目录」这两步省掉了。

手动分步执行：

```bash
cd "G:/workbuddy/任务路径/Fridge-Prophet/backend"

# 建虚拟环境 —— 这步不能省
C:/Users/Atao/.workbuddy-ai/binaries/python/versions/3.13.12/python.exe -m venv .venv

# 装依赖
.venv/Scripts/python.exe -m pip install -r requirements.txt

# 复制配置
cp .env.example .env

# 启动
.venv/Scripts/python.exe run.py
```

</details>

> **别直接敲 `python run.py`。** 系统 Python 里没有这些依赖，
> 会报 `ModuleNotFoundError: No module named 'uvicorn'`。
> 必须用虚拟环境里的解释器 `.venv/Scripts/python.exe`。

`DASHSCOPE_API_KEY` 留空会自动进 MOCK 模式，`DATABASE_URL` 留空自动用 SQLite。
**零配置就能把整条链路走通** —— 这是刻意的设计，让你在花一分钱之前先确认产品是通的。

**然后并行开三个账号**（详细步骤见 [`docs/01-账号注册.md`](docs/01-账号注册.md)）：

- [ ] **Supabase** — 托管数据库 + 图片存储
- [ ] **阿里云百炼** — 通义千问 API Key
- [ ] **腾讯云** — 轻量服务器（买**中国香港**节点，免备案）

> 完整排序、时间估算、以及哪一步会卡住哪一步，见 [`docs/00-行动清单.md`](docs/00-行动清单.md)。

### 阶段二：Android 客户端 —— ✅ 完成

```
assembleDebug    BUILD SUCCESSFUL in 1m 30s   →  app-debug.apk    17.8 MB
assembleRelease  BUILD SUCCESSFUL in 4m 11s   →  app-release.apk   2.8 MB
```

release 包体积只有 debug 的 **16%**，因为开了 R8 代码混淆 + 资源压缩。
这一步提前验证掉了一个高风险项：R8 很容易把 Hilt 的依赖注入、
kotlinx.serialization 的 JSON 解析这类「靠反射工作」的代码误删，
典型症状是**编译通过、一打开 App 就崩**。`proguard-rules.pro` 里的保留规则已验证有效。

已落地：

1. ✅ 工具链装到 `G:/Android/`：JDK 17 + Gradle 8.14.5 + SDK platform-36 / build-tools 36.0.0
2. ✅ Kotlin + Compose + Hilt + Retrofit + CameraX 工程骨架，含 Gradle Wrapper
3. ✅ 5 个 Tab 全部接上真实接口：首页 / 冰箱 / 菜谱 / 采购 / 我的
4. ✅ CameraX 拍照 → 上传 → 识别结果确认页（可改名、改数量、手动补录）
5. ✅ 启动页自动判断去向 + 登录注册 + 5 步引导问卷
6. ✅ 菜谱详情页：食材齐全度标记、烹饪步骤、营养估算（带免责声明）、收藏 / 做过 / 跳过
7. ✅ 采购清单：勾选、乐观更新、一键写回冰箱

#### 为什么依赖版本看起来这么「旧」

四个版本被三条约束连锁锁死，**动一个就要重验整条链**：

| 约束 | 结论 |
|---|---|
| KSP 的 Kotlin 版本上限 | Kotlin 卡在 **2.2.21**（KSP 只发到 `2.2.21-2.0.5`） |
| AGP 9 默认开启的 `android.newDsl` 与标准 Kotlin 插件冲突 | AGP 卡在 **8.13.2**（8.x 最后一代） |
| AGP 8.13.2 的 compileSdk 上限是 36 | 所有 AndroidX 库必须用 compileSdk 36 时代的版本 |

由此推出的两个具体取舍：

- **Hilt 停在 2.58**。Dagger 2.59 给 Hilt Gradle 插件加了 AGP 9 支持，并明确声明
  「AGP 9 is now a requirement」；2.59.1 更把最低 AGP 硬设为 9.0.0。
  而升 AGP 9 会连锁要求 Gradle 9.1+ 和更高 Kotlin —— 正好撞上上面两条约束。
- **hilt-navigation-compose 停在 1.3.0**。1.4.0 传递依赖
  `lifecycle-viewmodel-compose:2.11.0`，而 2.11.0 要求 compileSdk 37 + AGP 9.1.0。
  1.3.0 依赖的是 2.9.1，正好落在 compileSdk 36 的能力范围内。

还有一个 Windows 特有的坑：项目路径 `G:\workbuddy\任务路径\...` 含中文，
AGP 默认拒绝在非 ASCII 路径下构建，已在 `android/gradle.properties` 里用
`android.overridePathCheck=true` 放行。

> 想升级这套版本时，请从 `android/gradle/libs.versions.toml` 顶部的注释开始读——
> 那里记了每条约束的原因。

### 阶段三：部署到公网 —— ⏳ 等云账号

按 [`docs/03-部署到公网.md`](docs/03-部署到公网.md) 走：

1. 买腾讯云轻量**中国香港**节点（内地节点要 ICP 备案，7-20 个工作日，比赛等不起）
2. 服务器装 Python 3.13 + Nginx，代码上传后建虚拟环境装依赖
3. systemd 守护进程，配开机自启 + 崩溃自动重启
4. Nginx 反向代理 + certbot 上 HTTPS

> **两个已预判的坑**：
> ① Supabase 免费版闲置 7 天会暂停项目，演示前最致命 —— `docs/03` 里给了保活 cron，部署完就配。
> ② Android 从 API 28 起默认禁止明文 HTTP，release 包**必须**走 HTTPS，否则请求全被系统拦掉。

### 阶段四：打包签名 APK —— 🔄 签名已验证，待配 CI Secrets

```bash
# 1. 生成密钥（一次性。已经做过就跳过 —— 密钥别重复生成！）
#    双击项目根目录的 make-keystore.bat

# 2. 打正式包
cd android
export JAVA_HOME="G:/Android/jdk-17.0.20.1+1"
"G:/Android/gradle-8.14.5/bin/gradle.bat" assembleRelease \
  -PAPI_BASE_URL=https://你的正式域名/

# 3. 验签名（关键，别跳过）
"G:/Android/Sdk/build-tools/36.0.0/apksigner.bat" verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

已完成的部分：

- ✅ 密钥已生成（`android/release.jks` + `keystore.properties`，都在 .gitignore 里）
- ✅ **签名已用 `apksigner` 验证过**：release 包的证书是
  `CN=FridgeProphet, OU=Mobile, O=FridgeProphet, C=CN`，
  不是 debug 的 `CN=Android Debug`
- ✅ release 构建链路验证通过（含 R8 混淆，包体 3.3 MB）
- ✅ GitHub Actions 流水线跑通，推代码自动出包（`app-debug` + `app-release`）
- ✅ 后端地址可注入（`-PAPI_BASE_URL` / 环境变量 / `gradle.properties`），
  换服务器不用改代码

**待做**：把密钥配到 GitHub Secrets，CI 才能出**正式签名**的 release 包。
现在 CI 上没配 Secrets，走的是 debug 签名 —— 构建照样成功，
只有查证书才能发现。

> ⚠️ **必须用 `apksigner verify --print-certs` 查证书，不能只看「构建成功」。**
> 缺 `keystore.properties` 时 Gradle 会**静默退回 debug 签名**：
> 构建成功、APK 能装，但签名是错的。

完整步骤见 [`docs/04-打包APK.md`](docs/04-打包APK.md)。

## 六、快速验证（现在就能跑）

**后端：**

```bash
# 一条命令：建环境 + 装依赖 + 复制配置 + 打印局域网 IP + 启动
bash tools/start-backend.sh
```

启动后打开这个地址：http://127.0.0.1:8000/docs —— 这是 FastAPI 自动生成的交互式接口文档，
所有接口都能直接在网页上点着调试，不用装 Postman。

**两套测试，测的不是一回事：**

```bash
cd backend

# ① 进程内测试（251 项）：直接调应用，不起网络栈，快
.venv/Scripts/python.exe tests/test_e2e.py

# ② HTTP 冒烟测试（62 项）：打真实运行中的服务，走网络栈
.venv/Scripts/python.exe scripts/smoke_http.py

# 部署后验线上（只有这个能发现 Nginx 配错、证书没生效）
.venv/Scripts/python.exe scripts/smoke_http.py --base-url https://你的域名
```

**Android：**

```bash
cd "G:/workbuddy/任务路径/Fridge-Prophet/android"

export JAVA_HOME="G:\Android\jdk-17.0.20.1+1"

# 打 debug 包。默认后端地址是 10.0.2.2:8000（模拟器专用），
# 真机调试要换成电脑的局域网 IP：
"G:/Android/gradle-8.14.5/bin/gradle.bat" installDebug \
  -PAPI_BASE_URL=http://192.168.1.23:8000/

# 产物
ls app/build/outputs/apk/debug/app-debug.apk
```

**更省事的办法：** 双击项目根目录的 **`build-debug.bat`** ——
它会自动检测本机局域网 IP、注入、构建，并把 APK 复制到
`outputs/fridge-prophet-debug-<你的IP>.apk`。

**客户端单元测试（不需要真机）：**

```bash
cd android
export JAVA_HOME="G:/Android/jdk-17.0.20.1+1"
"G:/Android/gradle-8.14.5/bin/gradle.bat" testDebugUnitTest
```

跑的是 Retrofit 的**接口契约测试**（用 MockWebServer 起一个假后端），
验证「空响应体 / null 响应体 / 错误码」这些容易被忽略的边界。
这类问题**只在真机上才暴露**，但契约测试能在本地就抓住 ——
`FamilyContractTest` 就靠它抓出过一个真 bug（没加入家庭时家庭页报错）。

后端地址支持三种注入方式，优先级从高到低：

| 方式 | 写法 | 适用场景 |
|---|---|---|
| 命令行参数 | `-PAPI_BASE_URL=https://...` | 临时切换，推荐 |
| 环境变量 | `export API_BASE_URL=https://...` | CI 流水线 |
| 配置文件 | 写进 `android/gradle.properties` | 本地长期固定 |

都不配则用默认值：debug 是 `http://10.0.2.2:8000/`，release 是占位域名 `https://api.example.com/`。
结尾的 `/` 会自动补上，不用自己加。

> ⚠️ **真机上如果包内地址是 `10.0.2.2`，App 会静默退回登录页** ——
> 看起来像「账号被清空了」，实际只是连不上后端（`10.0.2.2` 是**模拟器专用**地址）。
> 排查顺序：① 后端起了吗 ② 手机和电脑在同一 Wi-Fi 吗 ③ 包里的地址是局域网 IP 吗。
> 用 `build-debug.bat` 构建就不会踩这个坑。

> 为什么不做成硬编码：换服务器地址是部署阶段的日常操作，写死在代码里会导致
> 地址被提交进 Git、CI 无法注入、别人 clone 下来指向你的服务器。
