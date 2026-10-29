# 无图形环境自检

这个目录不是构建的一部分，只是**在没有显卡/显示器时**验证渲染链路的脚本。
（正式构建只包含 `src/`，不会打进 jar。）

## 用法

```bash
cd VMFashion-mod
./gradlew build
bash tools/verify/run.sh
```

## 它验证什么

`Verify.java`（用真实的生产代码 `CosmeticAssets` / `GeoModelRenderer` / `CosmeticRenderDispatcher`）：

1. **资源接入**：用 GeckoLib 的 `GeckoLibGsonLoader` 把示例包里的 `.geo.json` 烘焙成 `BakedGeoModel`；
2. **几何正确性**：用 `GeoModelRenderer` 遍历骨骼，把顶点写进一个记录型 `VertexConsumer`，
   检查包围盒符合"脚底原点 / Y 向上"的 Bedrock 约定（背板在背后、皇冠在头顶）；
3. **坐标系换算**：叠加 `applyModelSpace` 的变换后，顶点必须落在原版玩家模型空间（Y 向下、脖子为原点）；
4. **X 轴镜像**：JSON 里的 +X 必须落在玩家左手边（GeckoLib 烘焙时会取反一次，这里要翻回来）；
5. **attachedBone**：通过反射调用 `applyAttachment`，检查低头/转头时皇冠跟着动。

`VerifyMixins.java`：解析每个 mixin 的 `@Mixin` / `@Inject`，用 ASM 检查目标类里
确实存在对应 `name + descriptor` 的方法（26.x 不重映射，descriptor 必须完全一致）。

> 注意：这套脚本验证的是"几何数据 + 变换矩阵 + mixin 目标"，
> **不能**替代真机进游戏看画面。着色器/光照/渲染顺序只能在有 GL 的环境里确认。
