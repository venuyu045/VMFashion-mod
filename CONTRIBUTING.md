# 贡献指南

> 感谢你愿意改这个 mod。这份文档只讲**怎么在这个仓库里干活**：
> 环境、命令、代码风格、提交规范、PR 清单。
>
> 玩家排障看 [docs/troubleshooting.md](docs/troubleshooting.md)；
> 做时装包看 [docs/pack-format.md](docs/pack-format.md)；
> 想理解内部结构看 [docs/architecture.md](docs/architecture.md)。

- [1. 环境要求](#1-环境要求)
- [2. 构建](#2-构建)
- [3. 常用脚本](#3-常用脚本)
- [4. 单元测试](#4-单元测试)
- [5. 无图形自检](#5-无图形自检)
- [6. 26.3 API 签名怎么查](#6-263-api-签名怎么查)
- [7. 代码风格](#7-代码风格)
- [8. 新增一个 mixin 的流程](#8-新增一个-mixin-的流程)
- [9. 提交规范](#9-提交规范)
- [10. PR 检查清单](#10-pr-检查清单)
- [11. CI 跑什么](#11-ci-跑什么)

---

## 1. 环境要求

| 项目 | 版本 | 说明 |
|---|---|---|
| JDK | **25** | 编译和运行都需要。低版本会直接 `UnsupportedClassVersionError` |
| Gradle | 9.7.1 | 用仓库自带的 `./gradlew`，不用单独装 |
| Minecraft | **26.3** | Mojang 官方名（无 Yarn / 无重映射），mixin 的 descriptor 必须与官方 jar 完全一致 |
| Fabric Loader | 0.20.1 | |
| Fabric API | 0.165.0+26.3 | |
| GeckoLib | 5.5.7 | `com.geckolib.*`，**不是** 4.x 的 `software.bernie.geckolib` |
| Mod Menu | 21.0.0 | 可选（`compileOnly`），只在开发时编译用 |

依赖的许可证（GeckoLib MIT / Fabric Apache-2.0 / JUnit EPL-2.0 等）见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

内存提醒：Gradle daemon 大约要 400MB+，**不要并行跑多个 Gradle 任务**
（`org.gradle.jvmargs=-Xmx1G`，同时开两个很容易 OOM）。仓库里的 `tools/dev/gradle.sh`
用 `flock` 把 Gradle 串行化了，推荐都用它。

---

## 2. 构建

```bash
# 标准构建（会跑 check → test，产出 jar）
./gradlew build

# 依赖已缓存 / 无网络时
./gradlew build --offline

# 只跑测试
./gradlew test
```

产物：

```
build/libs/vmfashion-mc26.3-<version>.jar           # 发布用
build/libs/vmfashion-mc26.3-<version>-sources.jar   # 源码 jar
```

版本号只改一处：`gradle.properties` 的 `mod_version`（`fabric.mod.json` 里的 `${version}`
由 `processResources` 展开）。用脚本改更稳妥：

```bash
bash tools/dev/set-version.sh 0.2.0
```

---

## 3. 常用脚本

`tools/` 下的脚本都是**独立可执行**的（不参与构建、不进 jar），
建议按下面的顺序在提交前跑一遍。

### 3.1 快速编译检查（2 秒）

```bash
bash tools/dev/check.sh          # javac + 预生成 classpath，不启动 Gradle
bash tools/dev/check.sh --cp     # 只重新生成 classpath 缓存
```

适合"改一行就看看能不能编译"。**正式验证仍然要 `./gradlew build`。**

### 3.2 串行 Gradle（内存受限环境）

```bash
bash tools/dev/gradle.sh test --offline
bash tools/dev/gradle.sh build --offline
```

和 `./gradlew` 完全等价，只是用 `flock` 串行化，避免多个任务同时跑。

### 3.3 无图形自检

```bash
./gradlew build            # 需要先有 build/classes/java/client
bash tools/verify/run.sh
```

### 3.4 语言文件校验

```bash
bash tools/dev/check-lang.sh
```

校验 `zh_cn.json` / `en_us.json` 的 key 集合完全一致、JSON 合法、没有空值。
新增文案时**两个文件都要加**，否则英文玩家会看到 `vmfashion.gui.foo` 这种原始 key。

### 3.5 jar 结构校验

```bash
bash tools/dev/check-jar.sh              # 默认取 build/libs 下最新的主 jar
bash tools/dev/check-jar.sh path/to.jar
```

检查图标 / 语言文件 / 示例包 / `fabric.mod.json` 版本占位符是否展开 / 入口类是否存在。

### 3.6 其它

| 脚本 | 用途 |
|---|---|
| `bash tools/dev/list-i18n-todo.sh` | 列出"玩家可见但硬编码中文"的位置（i18n 待办清单）；`--count` 只输出数量 |
| `bash tools/dev/release-notes.sh 0.2.0` | 从 `CHANGELOG.md` 抽某个版本的发布说明到 `build/release-notes.md`（抽不到会用兜底说明，不会让发布失败） |
| `bash tools/dev/set-version.sh 0.2.0` | 改 `gradle.properties` 的 `mod_version` |
| `python3 tools/dev/make-icon.py` | 重新生成 `assets/vmfashion/icon.png`（128×128，纯 zlib 手写 PNG，不依赖 Pillow；会覆盖现有图标） |

---

## 4. 单元测试

### 4.1 铁律：只测纯逻辑

**测试代码不能加载任何 Minecraft 类。** 原因：测试 JVM 没有游戏环境，
加载 `Minecraft` / `NativeImage` / `TextureManager` 会直接失败。

可以测：包解析、zip 安全、退避策略、持久化、分块、哈希、缓存淘汰。
不能测：渲染、GUI、贴图上传、mixin。

### 4.2 位置与依赖

```
src/test/java/com/venus/vmfashion/<包名>/XxxTest.java
```

- JUnit 5（`org.junit.jupiter.api.Test`），断言用 `org.junit.jupiter.api.Assertions`；
- **不要引入新的测试依赖**（`build.gradle` 里只有 `junit-jupiter` + `junit-platform-launcher`）；
- `build.gradle` 已经把 `sourceSets.client.output` 加进测试的编译/运行 classpath，
  所以测试可以直接引用 `PackLoader` 这类客户端代码。

### 4.3 需要 Fabric 目录的类怎么测

**重构成接受 `Path` 参数**，保留无参重载给生产代码：

```java
// 生产代码
public static LoadResult reload() { return reload(packsDir()); }

// 测试用
public static LoadResult reload(Path root) { ... }   // 不碰 FabricLoader
```

同类先例：`PackLoader#reload(Path)`、`LoadoutStore#loadInto(LocalLoadout, Path)`、
`LoadoutStore#saveNow(Path, Map)`。

### 4.4 临时目录用 `@TempDir`

```java
@TempDir
Path root;

@Test
void loadsPackFromTempDir() throws IOException {
    // 在 root 里造 pack.json + cosmetics/xxx/...
    PackLoader.LoadResult result = PackLoader.reload(root);
    assertEquals(1, result.cosmeticCount());
}
```

### 4.5 现有测试地图

| 测试 | 覆盖 |
|---|---|
| `cosmetic/PackLoaderTest` | 正常包 / 缺 pack.json / 坏 JSON / 声明式 cosmetics / 自动发现 / meta.json 覆盖 / anim 命名 / 路径逃逸 / 重复 id / scale 非法值 |
| `cosmetic/PackLoaderServerDirTest` | `packs/server/<packId>/` 命名空间与"用户包优先" |
| `cosmetic/LoadoutStoreTest` | 写入-读回 / 原子写不留 `.tmp` / 损坏文件降级 / 未知字段 |
| `cosmetic/CosmeticRegistryTest` | 全名 / 唯一短名 / 歧义返回 null |
| `cosmetic/CosmeticResourceIdTest` | 贴图 ID 不撞车（`A:B` vs `A_B`） |
| `net/SafeZipTest` | 路径逃逸 / 符号链接 / 大小写冲突 / zip bomb 上限 |
| `net/PackDownloaderTest` | sha256 / 字节数校验 / 失败清理 |
| `net/PackCatalogTest` | 清单解析 / 404 降级 |
| `net/RetryPolicyTest` | 退避 / 抖动 / `Retry-After` / 熔断 |
| `net/FashionApiErrorTest` | 错误码解析 |
| `loadout/RequestBatchingTest` | 200 个一批分块、不丢数据 |
| `util/HashUtilTest` | 哈希工具 |

---

## 5. 无图形自检

```bash
./gradlew build
bash tools/verify/run.sh
```

在没有显卡/显示器的环境里验证（当前是 **5 步**）：

1. **渲染链路**：用真实生产代码把示例包的 `.geo.json` 烘焙成 `BakedGeoModel`，
   遍历骨骼把顶点写进一个记录型 `VertexConsumer`，检查：
   - 包围盒符合"脚底原点 / Y 向上"；
   - 叠加 `applyModelSpace` 后落在原版玩家模型空间；
   - X 轴镜像方向正确；
   - `attachedBone` 跟随头部旋转；
2. **mixin 目标方法**：解析每个 mixin 的 `@Mixin` / `@Inject`，用 ASM 检查目标类里
   确实存在对应的 `name + descriptor`；
3. **缩放原点**：确认 `scale` 是绕挂载 pivot（头饰放大不会飞出去），不是绕脚底；
4. **emissive 遮罩**：逐 quad 过滤（UV 落在透明区域的面不发光、全透明 → 空遮罩）；
5. **动画解析**：`anim.json` 能解析成动画、动画里的骨骼名在烘焙后的模型里**真的存在**、
   时长 > 0、采样数值符合预期。

> 它**不能**替代真机进游戏看画面：着色器 / 光照 / 渲染顺序只能在有 GL 的环境确认。

---

## 6. 26.3 API 签名怎么查

26.3 **不重映射**（Mojang 官方名），所以方法名 / descriptor 必须和官方 jar 完全一致，
写 mixin 前**必须**用 `javap` 核对，不能凭记忆。

### 6.1 拿到编译期 classpath

```bash
./gradlew -q -I tools/verify/print-classpath.gradle printClientCp --offline \
  | sed 's/^CLIENT_CP=//' > /tmp/cp.txt
```

### 6.2 查类和方法

```bash
# 看某个类的全部成员（含 private）
javap -p -cp "$(cat /tmp/cp.txt)" net.minecraft.client.renderer.entity.layers.CapeLayer

# 找类名（模糊匹配）：loom 缓存里的官方 jar
MC_JAR="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/fabric-loom/26.3/minecraft-client-only.jar"
unzip -l "$MC_JAR" | grep -i 'Layer.class' | head

# 看方法签名对应的字节码（确认某个 RenderType 到底调了什么）
javap -c -p -cp "$(cat /tmp/cp.txt)" net.minecraft.client.renderer.rendertype.RenderTypes | head -60
```

### 6.3 mixin descriptor 的写法

`@Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V")`

- 用 **JVM descriptor**（`L...;` 全限定名，无空格）；
- 写错时 `bash tools/verify/run.sh` 的第二步会直接报"找不到目标方法"；
- `vmfashion.mixins.json` 里 `"defaultRequire": 1`：目标不存在会让 mixin 应用失败（游戏崩溃），
  所以**宁可多查一次 javap**。

---

## 7. 代码风格

### 7.1 注释

- **中文注释，解释"为什么"，不要复述代码**；
- 对外公开的类 / 方法写 javadoc；
- 踩过的坑（API 签名变化、字节码结论）写在类注释里，避免下一个人再踩。

### 7.2 防御式解析

任何来自**磁盘 / 网络**的数据都不能让客户端崩溃：

- 单条坏数据只记日志 + 跳过，不影响同批其它数据；
- 数值统一走 `sanitize` / `clamp`（Gson 反序列化会绕过 setter）；
- 解析失败要有**兜底返回值**，不要 `throw` 到主循环。

参考：`PackLoader`（坏包跳过）、`ModConfig#sanitize`、`SafeZip`（把 zip 当恶意输入）。

### 7.3 热路径零分配

渲染 / 每 tick 的代码里：

- 不要 `new` 集合 / `String.format` / 正则；
- 复用 `ThreadLocal` scratch（`GeoModelRenderer.SCRATCH`）；
- 快照按 tick 缓存（`RenderCache`）；
- 位掩码代替 `Set`（`HiddenParts`）。

### 7.4 线程约束

| 线程 | 规则 |
|---|---|
| 渲染线程 | 只读；不要做磁盘 / 网络 IO；不要碰非线程安全容器 |
| 网络回调 | 只能写并发容器；**碰 `Minecraft` / GL 必须 `Minecraft.getInstance().execute(...)` 回主线程** |
| 后台 worker | 下载 / 解压 / 计算；装完包回主线程 `reload`（贴图上传要 GL 上下文） |

### 7.5 其它

- 魔法数字给常量 + 注释；
- 玩家可见文案**一律**走 `Component.translatable`（`assets/vmfashion/lang/{zh_cn,en_us}.json`），
  中英 key 集合必须一致（`bash tools/dev/check-lang.sh` 校验）；
  `SyncService#describeStatus` / `#describeApiError` 这类对外接口直接返回 `Component`，
  不要在调用点再拼字符串；
- `bash tools/dev/list-i18n-todo.sh` 用来扫"玩家可见但硬编码中文"的位置，**目标输出为空**
  （目前为空）；包加载警告等**开发者向诊断信息仍是中文**，这是有意的，不用迁移；
- 新增/修改文案时**两个语言文件一起改**，跑 `bash tools/dev/check-lang.sh`；
- 不要提交 `build/`、`logs/`、`run/`、IDE 配置（`.gitignore` 已覆盖）。

---

## 8. 新增一个 mixin 的流程

1. `javap` 核对目标类的**方法名 + descriptor**（见 §6）；
2. 在 `src/client/java/com/venus/vmfashion/mixin/` 新建类，`@Mixin(目标类.class)`；
3. 把类名加进 `src/main/resources/vmfashion.mixins.json` 的 `client` 数组；
4. `bash tools/dev/check.sh` 编译；
5. `./gradlew build && bash tools/verify/run.sh` —— 第二步会验证 descriptor；
6. 真机进游戏验证（自检脚本不能验证着色器 / 光照 / 渲染顺序）。

> 跨模块传递数据请复用现有载体：`FashionRenderState`（挂在 `AvatarRenderState` 上的
> `@Unique` 字段），不要在 mixin 里塞静态可变状态（多人同屏会串号）。

---

## 9. 提交规范

用 [Conventional Commits](https://www.conventionalcommits.org/) 前缀，**正文用中文**：

```
feat(render): 支持 hideParts 隐藏盔甲/手持物等 9 个渲染层
fix(pack): 修复 anim.json 自动发现永远失败
docs: 补时装包制作教程与架构文档
test(net): 补 SafeZip 符号链接条目的用例
refactor(gui): 衣柜界面拆出卡片渲染
chore(build): 升级 loom 到 1.18.4
perf(render): 复用 PoseStack，消除每 quad 分配
```

要求：

- **一个提交一件事**（功能 + 它的测试可以在一起；顺手格式化不要混进来）；
- 正文说明"为什么"和"影响面"，不要只写"fix bug"；
- 提交前工作区必须**能编译**（`bash tools/dev/check.sh` 通过）；
- 不要提交二进制大文件 / 生成的 jar。

---

## 10. PR 检查清单

提交 PR 前逐条确认：

- [ ] `bash tools/dev/check.sh` 通过
- [ ] `./gradlew test --offline` 全绿（新增逻辑有对应单测）
- [ ] `./gradlew build --offline` 通过
- [ ] `bash tools/verify/run.sh` 输出 `ALL CHECKS PASSED` + `ALL MIXIN TARGETS RESOLVED`
- [ ] 改了文案 → `bash tools/dev/check-lang.sh` 通过（中英 key 一致）
- [ ] 改了 jar 内容 / 元数据 → `bash tools/dev/check-jar.sh` 通过
- [ ] 新增 mixin 已加进 `vmfashion.mixins.json`，且 descriptor 用 `javap` 核对过
- [ ] 新增配置项有默认值，旧 `config.json` 仍能加载（缺失字段用默认值）
- [ ] 新增/修改的 `pack.json` 字段：缺失时回退默认值（老包不能坏）
- [ ] 热路径（渲染 / 每 tick）没有新增分配
- [ ] 网络 / 磁盘解析失败有兜底，不会抛到主循环
- [ ] 文档同步更新（`docs/pack-format.md` / `docs/architecture.md` / `docs/troubleshooting.md`）
- [ ] 没有提交 `build/`、`logs/`、IDE 配置、本地绝对路径

---

## 11. CI 跑什么

`.github/workflows/build.yml`（push 到 `main` / PR / 手动触发）：

```
1. 检查出 + JDK 25 + Gradle 缓存
2. ./gradlew build --stacktrace      # build 依赖 check → test，同时跑单元测试
3. bash tools/verify/run.sh          # 无图形自检：渲染链路 + mixin descriptor
4. bash tools/dev/check-lang.sh      # 中英语言文件 key 一致
5. bash tools/dev/check-jar.sh       # 发布产物结构
6. 上传 build/libs/*.jar（失败时上传测试报告）
```

`.github/workflows/release.yml`：打 `v*` tag 时构建并发布（Release notes 由
`tools/dev/release-notes.sh` 从 `CHANGELOG.md` 抽取）。

> 本地能过 CI 基本就能过；差异主要在**网络**（CI 不 `--offline`）和**内存**
> （CI runner 比容器宽松）。本地跑 `--offline` 通过不代表 CI 一定通过，
> 但反过来基本成立。
