#!/usr/bin/env python3
"""从菜品清单生成两个产物。

    cd backend
    .venv/Scripts/python.exe ../tools/build-dish-library.py

> ⚠️ 用**后端环境**跑，不是那个装 Pillow 的独立环境 ——
> 这个脚本要 import 项目的服务模块（解析食材名），需要 pydantic 那套依赖。
> 对比：`tools/import-images.py` 用独立环境（它只需要 Pillow）。

| 产物 | 用途 |
|---|---|
| `backend/app/services/dish_library.py` | 代码用的菜品库（自动生成，不要手改） |
| `docs/12-菜品生图表.md` | 给 Ethan 拿去外部生图的表格 |

## 输入

`backend/data/dish-manifest.csv` —— 唯一数据源：

    菜名,分类,必需食材,可选食材,英文名
    西红柿炒鸡蛋,家常热菜,番茄|鸡蛋,小葱,scrambled eggs with tomatoes

- **必需食材 / 可选食材**：用「|」分隔的中文名，必须能在
  `ingredient_lexicon` 里解析出 key —— 解析不了直接报错退出，
  因为关联不上的菜在推荐时永远匹配不到。
- **英文名**：同时用作图片文件名（slug 化）和生图描述的主体。

## 为什么生成而不是手写

192 道菜 × 3 个字段（图片 key、必需食材、可选食材）= 576 条映射。
手写等于把 CSV 抄第二遍，改一处就两边不一致。

## 为什么用英文名当图片 key

菜名是中文，直接当文件名会有编码问题（Windows / Linux 表现还不一样）。
英文名天然是 ASCII，slug 化之后稳定。
"""
from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BACKEND = ROOT / "backend"
sys.path.insert(0, str(BACKEND))

CSV_PATH = BACKEND / "data" / "dish-manifest.csv"
OUT_CODE = BACKEND / "app" / "services" / "dish_library.py"
OUT_DOC = ROOT / "docs" / "12-菜品生图表.md"

# 菜谱图的风格后缀。
#
# ⚠️ 和食材图**完全不同**，别混用：
# 食材图是「纯白底电商产品图」（单个生食材、无餐具），
# 菜谱图是「成品菜 + 白色餐具 + 木质桌面 + 暖色调 + 背景虚化道具」。
RECIPE_STYLE = (
    "Professional food photography of {subject}, served in a white ceramic bowl "
    "on a rustic wooden table, 35-degree camera angle, soft diffused natural "
    "window light from the left, shallow depth of field, warm appetizing tones, "
    "blurred kitchen props in the background, high detail, editorial cookbook "
    "style. No text, no watermark, no people, no hands."
)

CODE_HEADER = '''"""菜品库 —— **自动生成，不要手改**。

由 `tools/build-dish-library.py` 从 `backend/data/dish-manifest.csv` 生成。

要改菜品：改 CSV，再跑一次生成脚本。

## 结构

- `DISH_NAMES`：菜名 -> 图片 key（key 是英文名的 slug，也是图片文件名）
- `DISH_CATEGORIES`：菜名 -> 分类（家常热菜 / 汤羹 / 凉菜 / 主食 / 早餐 / 小吃点心）
- `DISH_INGREDIENTS`：菜名 -> (必需食材 key 元组, 可选食材 key 元组)
- `BY_REQUIRED`：食材 key -> **必需**它的菜名元组
- `BY_ANY`：食材 key -> 用到它的菜名元组（必需 + 可选）

## 为什么必需和可选要分开建索引

推荐时用的是 `BY_REQUIRED`。如果两者混在一起，「蒜」「葱」「酱油」
这些调味料会出现几十次 —— 用户冰箱里有蒜，就以为能做 36 道菜，
结果点进去发现什么都缺。**必需食材才算「能做」。**

`BY_ANY` 留给「搜索」用：用户搜「鸡蛋」，应该能搜到所有用到鸡蛋的菜，
不管鸡蛋是主料还是配料。
"""
from __future__ import annotations

DISH_NAMES: dict[str, str] = {names}

DISH_CATEGORIES: dict[str, str] = {categories}

# 菜名 -> (必需食材, 可选食材)。元素都是**图片 key**，不是中文名。
DISH_INGREDIENTS: dict[str, tuple[tuple[str, ...], tuple[str, ...]]] = {ingredients}

# 食材 key -> **必需**它的菜名。推荐用这张。
BY_REQUIRED: dict[str, tuple[str, ...]] = {by_required}

# 食材 key -> 用到它的菜名（必需 + 可选）。搜索用这张。
BY_ANY: dict[str, tuple[str, ...]] = {by_any}

# 全部菜名，按 CSV 顺序。
ALL_DISHES: tuple[str, ...] = tuple(DISH_NAMES)
'''


def slugify(text: str) -> str:
    """英文名 -> 图片 key。只保留小写字母数字和短横线。"""
    s = text.lower().strip()
    s = re.sub(r"[^a-z0-9]+", "-", s)
    return s.strip("-")


def fmt_dict(d: dict, indent: str = "    ") -> str:
    if not d:
        return "{}"
    lines = ["{"]
    for k, v in d.items():
        lines.append(f'{indent}"{k}": {v!r},')
    lines.append("}")
    return "\n".join(lines)


def main() -> int:
    if not CSV_PATH.exists():
        print(f"找不到清单：{CSV_PATH}")
        return 1

    with CSV_PATH.open(encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))

    # 解析食材名 -> key。解析不了就报错，不要带着坏数据往下走。
    from app.services.ingredient_image_service import resolve_ingredient_key

    def parse_keys(field: str, dish: str) -> tuple[str, ...]:
        keys: list[str] = []
        for name in (field or "").split("|"):
            name = name.strip()
            if not name:
                continue
            key = resolve_ingredient_key(name)
            if key is None:
                print(f"❌ 「{dish}」的食材「{name}」解析不出 key。")
                print("   要么改用词库里的标准名，要么在 EXTRA_ALIASES 里补别名。")
                raise SystemExit(1)
            keys.append(key)
        return tuple(keys)

    names: dict[str, str] = {}
    categories: dict[str, str] = {}
    ingredients: dict[str, tuple[tuple[str, ...], tuple[str, ...]]] = {}
    by_required: dict[str, list[str]] = {}
    by_any: dict[str, list[str]] = {}
    slug_seen: dict[str, str] = {}
    problems: list[str] = []

    for r in rows:
        dish = (r.get("菜名") or "").strip()
        en = (r.get("英文名") or "").strip()
        if not dish or not en:
            continue

        key = slugify(en)
        if key in slug_seen and slug_seen[key] != dish:
            problems.append(f"图片 key 撞车：{dish} 和 {slug_seen[key]} 都是 {key}")
        slug_seen[key] = dish

        req = parse_keys(r.get("必需食材"), dish)
        opt = parse_keys(r.get("可选食材"), dish)

        names[dish] = key
        categories[dish] = (r.get("分类") or "家常热菜").strip()
        ingredients[dish] = (req, opt)

        # 必需和可选分开建索引，理由见文件头的说明
        for k in set(req):
            by_required.setdefault(k, []).append(dish)
        for k in set(req) | set(opt):
            by_any.setdefault(k, []).append(dish)

    if problems:
        print("❌ 发现问题：")
        for p in problems:
            print("   ", p)
        return 1

    # —— 产物一：代码 ——
    OUT_CODE.write_text(
        CODE_HEADER.format(
            names=fmt_dict(names),
            categories=fmt_dict(categories),
            ingredients=fmt_dict(ingredients),
            by_required=fmt_dict({k: tuple(v) for k, v in sorted(by_required.items())}),
            by_any=fmt_dict({k: tuple(v) for k, v in sorted(by_any.items())}),
        ),
        encoding="utf-8",
    )

    # —— 产物二：给 Ethan 的生图表 ——
    doc = [
        "# 12 · 菜品生图表",
        "",
        "> **自动生成，不要手改** —— 由 `tools/build-dish-library.py` 从",
        "> `backend/data/dish-manifest.csv` 生成。",
        ">",
        f"> 共 **{len(rows)} 道菜**。生成完把图片放进 `.tmp-images-dishes/`，",
        "> 文件名用表里的「文件名」列（不带 `.jpg`），我再导入。",
        "",
        "## 生图提示词模板",
        "",
        "**风格后缀固定不动，只换主体描述**：",
        "",
        "```",
        RECIPE_STYLE.format(subject="{表里的英文描述}"),
        "```",
        "",
        "> ⚠️ **菜谱图和食材图的风格完全不同，别混用模板。**",
        "> 食材图是「纯白底电商产品图」（单个生食材、不要餐具）；",
        "> 菜谱图是「成品菜 + 白色餐具 + 木质桌面 + 暖色调 + 背景虚化道具」。",
        "",
        "## 参数",
        "",
        "| 项 | 值 |",
        "|---|---|",
        "| 尺寸 | **1280×960**（横构图，菜谱详情的通栏图用） |",
        "| 数量 | 每道 1 张 |",
        "| 格式 | PNG（导入脚本会自动转 JPG） |",
        "",
        "## 清单",
        "",
        "| # | 菜名 | 分类 | 文件名 | 英文描述 |",
        "|---|---|---|---|---|",
    ]
    for i, r in enumerate(rows, 1):
        dish = (r.get("菜名") or "").strip()
        en = (r.get("英文名") or "").strip()
        cat = (r.get("分类") or "").strip()
        doc.append(f"| {i} | {dish} | {cat} | `{slugify(en)}` | {en} |")

    doc += [
        "",
        "## 生成之后的导入",
        "",
        "```bash",
        'python tools/import-images.py --kind recipes --src ".tmp-images-dishes"',
        "```",
        "",
        "> 文件名必须和表里的「文件名」列完全一致 —— 导入脚本靠它认菜。",
    ]
    OUT_DOC.write_text("\n".join(doc) + "\n", encoding="utf-8")

    print(f"✅ 代码：{OUT_CODE.relative_to(ROOT)}")
    print(f"✅ 表格：{OUT_DOC.relative_to(ROOT)}")
    print()
    print(f"菜品 {len(names)} 道，涉及 {len(by_any)} 种食材")
    import collections

    print("分类分布：")
    for c, n in collections.Counter(categories.values()).most_common():
        print(f"   {c:8s} {n:4d}")
    print()
    print("【必需食材】出现最多的 Top 12（这些才是决定「能不能做」的）：")
    for k, v in sorted(by_required.items(), key=lambda kv: -len(kv[1]))[:12]:
        print(f"   {k:22s} {len(v):3d} 道")
    return 0


if __name__ == "__main__":
    sys.exit(main())
