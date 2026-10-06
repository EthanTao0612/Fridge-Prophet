#!/usr/bin/env python3
"""从食材清单 CSV 生成代码里的词库。

    "C:/Users/Atao/.workbuddy-ai/binaries/python/envs/default/Scripts/python.exe" \
        tools/build-ingredient-lexicon.py

输入：`backend/data/ingredient-manifest.csv`
输出：`backend/app/services/ingredient_lexicon.py`（**自动生成，不要手改**）

## 为什么要生成而不是手写

清单有 **574 条**，每条都要「中文名 → 图片 key → 分类」三样信息。
手写等于把同一份数据抄第二遍，改一处就会两边不一致。

生成的好处：
- CSV 是**唯一数据源**，改词库 = 改 CSV 再跑一遍
- 574 条的映射不会抄错
- 以后补图（比如被内容策略拦下的「芸豆」）只要更新 CSV

## CSV 的列

    中文名,英文名,分类,子类,状态,字节数
    五花肉,pork-belly,一、肉蛋水产类,1. 猪肉,ok,167082

- **英文名**就是图片文件名（也是代码里的 key）
- **分类 + 子类**是两级，需要一起看才能定出我们的分类 ——
  比如「一、肉蛋水产类」下面既有猪肉（肉类）也有带鱼（水产）还有鸡蛋（蛋奶），
  光看顶层分不出来。
"""
from __future__ import annotations

import csv
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CSV_PATH = ROOT / "backend" / "data" / "ingredient-manifest.csv"
OUT_PATH = ROOT / "backend" / "app" / "services" / "ingredient_lexicon.py"

# —— 分类体系 ——
#
# 和清单的顶层分类对齐，12 个。
# 比原来 9 个多出「坚果 / 饮品 / 零食」——
# 清单里这三类各有几十条，塞进「其他」会让冰箱页那个分组变成杂物堆。
CATEGORIES: tuple[str, ...] = (
    "蔬菜", "水果", "肉类", "水产", "蛋奶", "豆制品",
    "主食", "坚果", "调味", "饮品", "零食", "其他",
)

# 顶层分类 → 我们的分类。没列出的走 SUB_MAP。
TOP_MAP: dict[str, str] = {
    "二、蔬菜类": "蔬菜",
    "三、水果及果制品": "水果",
    "四、乳制品与蛋奶制品": "蛋奶",
    "五、主食、谷物与薯类淀粉制品": "主食",
    "六、豆类、豆制品与植物蛋白": "豆制品",
    "七、坚果、种子与籽实": "坚果",
    "八、调料、香辛料与调味品": "调味",
    "九、液体食物与饮品": "饮品",
    "十、甜点、糖果与零食": "零食",
    "十一、可食用花与特殊食材": "其他",
    # 添加剂（泡打粉、小苏打、琼脂、吉利丁…）本质是厨房调料，
    # 归「调味」比归「其他」更好找。
    "十二、家庭常用添加与凝固剂": "调味",
}

# 「一、肉蛋水产类」必须靠子类拆 —— 顶层名字里三类混在一起了。
SUB_MAP: dict[str, str] = {
    "1. 猪肉": "肉类",
    "2. 禽肉": "肉类",
    "3. 牛羊及其他肉类": "肉类",
    "4. 蛋类": "蛋奶",
    "5. 淡水水产": "水产",
    "6. 海水鱼及海产": "水产",
    "7. 虾蟹贝类": "水产",
}

# 每个分类的万能图 key。
#
# 万能图是「图库里没有这个食材时」用的代表图。
# 有 574 张具体图之后它很少被用到，但**不能省** ——
# 用户随手输一个「空气炸锅鸡块」，总得有个东西显示，比空白占位好。
UNIVERSAL_KEYS: dict[str, str] = {
    "蔬菜": "_universal-vegetable",
    "水果": "_universal-fruit",
    "肉类": "_universal-meat",
    "水产": "_universal-seafood",
    "蛋奶": "_universal-dairy-egg",
    "豆制品": "_universal-soy",
    "主食": "_universal-staple",
    "坚果": "_universal-nut",
    "调味": "_universal-seasoning",
    "饮品": "_universal-drink",
    "零食": "_universal-snack",
    "其他": "_universal-other",
}

HEADER = '''"""食材词库 —— **自动生成，不要手改**。

由 `tools/build-ingredient-lexicon.py` 从 `backend/data/ingredient-manifest.csv` 生成。

要改词库：改 CSV，再跑一次生成脚本。

数据来源：Ethan 在外部程序批量生成的 {total} 张食材图，
每张都带中文名、英文 key、两级分类。

- `INGREDIENT_ALIASES`：中文名 → 图片 key（也是匹配用的标识符）
- `KEY_CATEGORY`：图片 key → 分类
- `CATEGORIES`：全部分类，顺序就是冰箱页的显示顺序
- `UNIVERSAL_KEYS`：每个分类的万能图 key
"""
from __future__ import annotations

# 分类顺序 = 冰箱页显示顺序。常吃的放前面，「其他」垫底。
CATEGORIES: tuple[str, ...] = {categories}

# 中文名 → 图片 key。**精确匹配**用这张表。
INGREDIENT_ALIASES: dict[str, str] = {aliases}

# 图片 key → 分类。
KEY_CATEGORY: dict[str, str] = {key_category}

# 分类 → 万能图 key。
UNIVERSAL_KEYS: dict[str, str] = {universal}
'''


def fmt_dict(d: dict[str, str], indent: str = "    ") -> str:
    """把字典格式化成多行源码，每行一条方便 diff。"""
    if not d:
        return "{}"
    lines = ["{"]
    for k, v in d.items():
        lines.append(f'{indent}"{k}": "{v}",')
    lines.append("}")
    return "\n".join(lines)


def main() -> int:
    if not CSV_PATH.exists():
        print(f"找不到清单：{CSV_PATH}")
        return 1

    with CSV_PATH.open(encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))

    aliases: dict[str, str] = {}
    key_category: dict[str, str] = {}
    unmapped: list[tuple[str, str, str]] = []
    dup_names: list[str] = []

    for r in rows:
        name = (r.get("中文名") or "").strip()
        key = (r.get("英文名") or "").strip()
        top = (r.get("分类") or "").strip()
        sub = (r.get("子类") or "").strip()
        if not name or not key:
            continue

        # 子类优先 —— 「肉蛋水产类」光看顶层分不出猪牛羊肉和鱼虾蛋
        category = SUB_MAP.get(sub) or TOP_MAP.get(top)
        if category is None:
            unmapped.append((name, top, sub))
            category = "其他"

        if name in aliases and aliases[name] != key:
            dup_names.append(f"{name}: {aliases[name]} / {key}")
        aliases[name] = key
        key_category[key] = category

    # 检查分类是否都在体系内
    bad = sorted({c for c in key_category.values() if c not in CATEGORIES})
    if bad:
        print(f"⚠️ 生成了不存在的分类：{bad}")
        return 1

    src = HEADER.format(
        total=len(rows),
        categories=repr(CATEGORIES).replace("'", '"'),
        aliases=fmt_dict(aliases),
        key_category=fmt_dict(key_category),
        universal=fmt_dict(UNIVERSAL_KEYS),
    )
    OUT_PATH.write_text(src, encoding="utf-8")

    print(f"已生成 {OUT_PATH.relative_to(ROOT)}")
    print(f"  中文名 -> key：{len(aliases)} 条")
    print(f"  key -> 分类：{len(key_category)} 条")
    print()
    print("分类分布：")
    counts: dict[str, int] = {}
    for c in key_category.values():
        counts[c] = counts.get(c, 0) + 1
    for c in CATEGORIES:
        n = counts.get(c, 0)
        bar = "█" * max(1, round(n / 4))
        print(f"  {c:6s} {n:4d}  {bar}")

    if dup_names:
        print()
        print(f"⚠️ 有 {len(dup_names)} 个中文名对应了多个 key（只保留最后一个）：")
        for d in dup_names[:10]:
            print("   ", d)

    if unmapped:
        print()
        print(f"⚠️ 有 {len(unmapped)} 条没匹配上分类，已归「其他」：")
        for name, top, sub in unmapped[:10]:
            print(f"    {name}  顶层={top!r} 子类={sub!r}")
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
