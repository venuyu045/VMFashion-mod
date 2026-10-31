# tools/preview —— 无头时装预览渲染器

把一件 Bedrock 时装（`model.geo.json` + `texture.png` + `anim.json`）渲染成 PNG 效果图，
**不需要显卡、不需要 Minecraft 客户端**：几何和骨骼变换走生产代码，光栅化用纯 CPU + JDK 自带能力。

```bash
# 前提：build/classes/java/client 已存在（跑过一次 ./gradlew build 即可，不必每次重建）
bash tools/preview/render.sh <包目录> <cosmeticId> <输出目录>

# 例子
bash tools/preview/render.sh src/main/resources/assets/vmfashion/default_pack crown build/preview/crown
bash tools/preview/render.sh src/main/resources/assets/vmfashion/official_pack official:dragon_wings build/preview/dragon_wings
```

`cosmeticId` 可以写成 `dragon_wings`，也可以写成全局 ID `official:dragon_wings`（会自动拆掉包名前缀）。

## 输出

| 文件 | 内容 |
| --- | --- |
| `back.png` | 正后方（主视角） |
| `front34.png` | 前侧 3/4 |
| `side.png` | 侧面（+X） |
| `top.png` | 俯视 |
| `anim-0.png` … `anim-3.png` | 动画 t = 0、¼、½、¾ 周期（与 `back` 同一台相机，方便逐帧对比） |
| `thumb.png` | 1:1 缩略图，"人 + 时装"完整入画、模型水平居中 |
| `report.txt` | 自检报告（包围盒、对称率、UV/贴图检查、动画幅度、文件指纹……） |

视角图默认 1280×960、缩略图 512×512，2× 超采样抗锯齿。画面里那套灰色半透明方块是
**原版玩家比例参考剪影**（头 8×8×8 @ y24..32、身体 8×12×4、手臂 4×12×4 @ x±6、腿 4×12×4），
坐标系是 E 空间：脚底原点、Y 向上、+Z 背后、单位 1/16 格（玩家身高 32 单位）。

## 可选参数

| 参数 | 说明 |
| --- | --- |
| `--ss=N` | 超采样倍数 1..4（默认 2） |
| `--size=1280x960` | 视角图尺寸 |
| `--thumb=512` | 缩略图边长 |
| `--fov=32` | 竖直视场角 |
| `--views=back,front34,side` | 只出指定视角（可选 `back` / `front34` / `side` / `top`） |
| `--no-label` | 不画左下角标注 |
| `--no-silhouette` | 不画参考剪影 |

## 链路（每一环都是生产代码）

```
.geo.json ──GeckoLibGsonLoader#bakeGeckoLibModelFile──▶ BakedGeoModel      （与游戏内同一套烘焙）
BakedGeoModel ──GeoModelRenderer#render──▶ VertexConsumer                  （与游戏内同一套骨骼/顶点数学）
   └─ QuadRecorder 记下每个顶点的 x,y,z,u,v,nx,ny,nz
动画：BedrockAnimations.parse ─▶ AnimationTrack#sample ─▶ BoneSnapshot ─▶ bone.frameSnapshot
      （GeoModelRenderer 不带 AnimationFrame 时正是读 frameSnapshot，和生产端挂/还原快照同一条路）
顶点 + texture.png ──SoftRasterizer──▶ BufferedImage ──ImageIO──▶ PNG
```

`SoftRasterizer` 是一个很小的软光栅化器：透视相机（按"必须入镜的点集"自动取景）、z-buffer、
纹理最近邻采样 + alpha cutout（alpha < 0.5 丢弃）、按面法线的方向光（环境光 + 主光 + 补光 + 边缘光）、
背景渐变 + 地面阴影椭圆。

## 自检在查什么

`report.txt` 里的指标是为了在**没有人眼**的情况下先抓一遍常见毛病：

- **左右对称**：顶点级 —— 每个四边形沿 X 镜像后能否找到对应块（`100%` = 几何完全对称）；
  画面级 —— 把渲染图左右翻转逐像素比较（`back` 视角的平均色差应 < 2/255）。
  几何对称但贴图/光照不对称时只有画面级能看出来。
- **贴图 UV**：UV 是否越界、四边形引用的 texel 是否全透明（全透明 = 画出来看不见，
  通常就是 UV 打错或贴图给错）。
- **动画**：相邻帧顶点最大位移；0 就说明动画没生效（骨骼名对不上 / 通道没解析 / 快照没挂上）。
- **文件指纹**：模型/贴图的大小 + 修改时间 —— 模型正在被别人改时，报告能对上"这批图是哪一版"。

## 已知限制

- **不渲染 emissive 发光层**：纯 CPU 光栅化没有泛光/自发光管线，带 `emissive.png` 的时装在预览里
  只显示基础色（报告里会注明）。
- **不做半透明混合排序**：`material: translucent` 的时装按 cutout 渲染，观感会和游戏内略有差异。
- **纹理只做最近邻采样**：贴图被强烈缩小的地方（斜视的薄面）会有摩尔纹/噪点，加 `--ss=3` 可缓解。
- **预览不套 `pack.json` 的 `scale`**：按模型原始尺寸出图（非 1.0 时报告里会提示）。
