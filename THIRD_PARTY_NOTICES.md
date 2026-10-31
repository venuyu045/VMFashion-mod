# 第三方许可声明（Third-Party Notices）

本仓库（VMFashion / Venus Mc Fashion）以 **MIT** 许可证发布，见 [LICENSE](LICENSE)。
Copyright (c) 2026 Venus。

VMFashion 是**纯客户端模组**，构建产物 `vmfashion-mc26.3-<version>.jar` 里
**只包含本项目自己的代码与资源**（`com/venus/vmfashion/**`、`assets/vmfashion/**`、
`fabric.mod.json`、`vmfashion.mixins.json`、本项目的 LICENSE），
**没有内嵌（shade / include）任何第三方库**。

下面这些项目**不随本仓库分发**，只作为编译期 / 运行期依赖存在，由玩家或构建环境自行获取，
各自遵循其原始许可证。

---

## 1. 运行期必需依赖（玩家需要自己安装）

| 项目 | 版本 | 许可证 | 说明 |
|---|---|---|---|
| [GeckoLib 5](https://modrinth.com/mod/geckolib) | 5.5.7 | **MIT** | Bedrock 几何 / 动画渲染引擎（`com.geckolib.*`）。本模组用它的 `GeckoLibGsonLoader` 烘焙模型、并复刻其顶点数学 |
| [Fabric Loader](https://fabricmc.net/) | 0.20.1 | **Apache-2.0** | 模组加载器与入口点（`ClientModInitializer`） |
| [Fabric API](https://github.com/FabricMC/fabric) | 0.165.0+26.3 | **Apache-2.0** | 用到的模块：客户端命令、生命周期事件、按键映射、资源重载 |

> 许可证来源：GeckoLib 5.5.7 的 `fabric.mod.json` 声明 `"license": "MIT"`（jar 内含 `LICENSE.txt`）；
> Fabric API 0.165.0+26.3 的 `fabric.mod.json` 声明 `"license": "Apache-2.0"`（jar 内含 `LICENSE-fabric-api`）；
> Fabric Loader 0.20.1 的 jar 内含 `LICENSE_fabric-loader`（Apache-2.0）。
> 三者都在 `fabric.mod.json` 的 `depends` 里声明，**不会**被打进本模组的 jar。

## 2. 可选依赖（玩家可以不装）

| 项目 | 版本 | 许可证 | 说明 |
|---|---|---|---|
| [Mod Menu](https://modrinth.com/mod/modmenu) | 21.0.0 | **MIT** | 装了它才会在模组列表里显示本模组的「配置」按钮（指向衣柜界面）。构建期是 `compileOnly`，运行期由 `fabric.mod.json` 的 `suggests` 声明，**不打包** |

> 许可证来源：Mod Menu 21.0.0 的 `fabric.mod.json` 声明 `"license": "MIT"`（jar 内含 `LICENSE_modmenu`）。

## 3. 仅构建 / 测试期使用（不进 jar）

| 项目 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| [JUnit 5](https://junit.org/junit5/) | 5.11.4 | **EPL-2.0** | 单元测试（`org.junit.jupiter`）。只在 `src/test/java` 使用 |
| [Gradle](https://gradle.org/) | 9.7.1 | **Apache-2.0** | 构建工具。仓库里提交了官方的 `gradle/wrapper/gradle-wrapper.jar` |
| Fabric Loom | 1.18.4 | 构建期插件（Fabric 项目） | 由 `gradlew` 从 Fabric 的 Maven 仓库拉取，不进 jar |

> 许可证来源：`junit-bom:5.11.4` 的 POM 声明 `Eclipse Public License v2.0`。

## 4. Minecraft（不属于本仓库）

| 项目 | 版本 | 许可 | 说明 |
|---|---|---|---|
| Minecraft: Java Edition | 26.3 | [Mojang EULA](https://www.minecraft.net/en-us/eula) / 商业许可 | 本仓库**不包含**任何 Minecraft 代码或资源。构建时由 Fabric Loom 从本地安装或官方源获取，只用于编译；产物 jar 里不含 Minecraft 类 |

> 使用本模组需要你自己合法拥有 Minecraft: Java Edition。

---

## 5. 本项目自己的资源

| 文件 | 说明 | 许可证 |
|---|---|---|
| `src/main/resources/assets/vmfashion/official_pack/**` | 内置官方时装包（`official`：深渊龙翼的模型 / 贴图 / 发光层 / 动画） | MIT（本项目原创） |
| `src/main/resources/assets/vmfashion/icon.png` | 模组图标，由 `tools/dev/make-icon.py` 纯代码生成 | MIT（本项目原创） |
| `src/main/resources/assets/vmfashion/lang/*.json` | 中英文文案 | MIT |

> 如果你要分发**自己制作的时装包**，包里的模型 / 贴图版权归你（或原作者），
> 请自行在 `pack.json` 的 `license` 字段里声明。

---

## 6. 如果你要重新分发本模组

- 直接分发官方 Release 的 jar 即可，无需附加任何第三方许可证文件（jar 内没有第三方代码）；
- 但如果你的整合包**同时**分发了 GeckoLib / Fabric API / Mod Menu，请按各自的许可证
  （MIT / Apache-2.0 / MIT）保留它们的版权与许可声明；
- 不要移除 `fabric.mod.json` 里的 `license`、`authors`、`contact` 字段。

---

*本文件随依赖版本更新。版本号来源：`gradle.properties`（`geckolib_version`、`loader_version`、
`fabric_api_version`、`modmenu_version`、`junit_version`）与 `gradle/wrapper/gradle-wrapper.properties`。*
