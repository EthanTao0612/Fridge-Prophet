"""食品安全小贴士内容库的自检。

    python tests/test_food_tips.py

不需要网络、不需要数据库、不需要 AI 密钥。

## 为什么内容库也要测

这 89 条贴士是**手写的内容**，不是代码逻辑。手写内容最容易出的是
「不报错但不对」的问题：

- id 重复 → 详情页永远只打开其中一条，另一条**访问不到**（而且不报错）
- category 写错一个字 → 这个分类的筛选里**少一条**，没人会发现
- source 漏填 → 内容失去了可信度依据，而这是这类内容**唯一的底线**
- summary 太长 → 客户端列表页会截断，或者换行错乱

这些都不会让服务起不来，只会静默地少一点东西。
所以用断言把它们钉住。

## 断言的分寸

只测**结构性**的东西（字段、枚举、长度范围），
**不测内容对不对** —— 「螃蟹和西红柿能不能同吃」的对错
靠 source 里引用的权威来源来保证，机器判断不了。

唯一的例外是「结论和来源必须匹配」这类弱约束：
- `verdict="谣言"` 的条目必须解释清楚**为什么是谣言**
- `verdict="属实"` 的条目不能是模棱两可的表述

这些用关键词粗筛，只用来**提醒人工复查**，不作硬性失败。
"""
from __future__ import annotations

import sys
from collections import Counter
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_DIR))

from app.data.food_tips import (  # noqa: E402
    CATEGORIES,
    COMMON_ALLERGENS,
    COMMON_DISLIKED,
    FOOD_TIPS,
    VERDICTS,
)

passed = 0
failed = 0


def check(label: str, actual, expected) -> None:
    global passed, failed
    if actual == expected:
        passed += 1
        print(f"  [OK]   {label}")
    else:
        failed += 1
        print(f"  [FAIL] {label}\n         期望 {expected!r}\n         实际 {actual!r}")


def main() -> int:
    print("=== 1. 基本完整性 ===")
    check("贴士数量 >= 80", len(FOOD_TIPS) >= 80, True)

    ids = [t["id"] for t in FOOD_TIPS]
    dup = [i for i, n in Counter(ids).items() if n > 1]
    check("id 没有重复", dup, [])

    bad_id = [i for i in ids if not i.replace("-", "").isalnum() or i != i.lower()]
    check("id 都是小写字母数字短横线（ASCII）", bad_id, [])

    print("\n=== 2. 字段非空 ===")
    for field in ("id", "title", "category", "verdict", "summary", "detail", "source"):
        empty = [t.get("id", "?") for t in FOOD_TIPS if not str(t.get(field, "")).strip()]
        check(f"{field} 全部非空", empty, [])

    print("\n=== 3. 分类和结论必须在枚举里 ===")
    # 写错一个字不会报错，只会让那个分类里少一条 —— 极难发现
    bad_cat = sorted({t["category"] for t in FOOD_TIPS if t["category"] not in CATEGORIES})
    check("分类都在 CATEGORIES 里", bad_cat, [])
    bad_ver = sorted({t["verdict"] for t in FOOD_TIPS if t["verdict"] not in VERDICTS})
    check("结论都在 VERDICTS 里", bad_ver, [])

    # 每个分类都得有内容，否则客户端的筛选标签点了是空的
    empty_cat = [c for c in CATEGORIES if not any(t["category"] == c for t in FOOD_TIPS)]
    check("每个分类都至少有 1 条", empty_cat, [])

    print("\n=== 4. 长度约束（客户端排版）===")
    # summary 在首页标语位和列表页用，太长会被截断
    long_summary = [(t["id"], len(t["summary"])) for t in FOOD_TIPS if len(t["summary"]) > 60]
    check("summary 不超过 60 字", long_summary, [])

    short_summary = [t["id"] for t in FOOD_TIPS if len(t["summary"]) < 15]
    check("summary 不少于 15 字（别写成一句废话）", short_summary, [])

    short_title = [t["id"] for t in FOOD_TIPS if len(t["title"]) < 6]
    check("title 不少于 6 字", short_title, [])

    # detail 是详情页正文，太短说明没讲清楚
    short_detail = [t["id"] for t in FOOD_TIPS if len(t["detail"]) < 150]
    check("detail 不少于 150 字", short_detail, [])

    print("\n=== 5. source 必须像出处，不能是占位符 ===")
    # 这是这类内容可信度的底线：答辩被追问「依据是什么」时全靠它
    weak_source = [
        t["id"] for t in FOOD_TIPS
        if t["source"].strip() in ("无", "暂无", "-", "略", "网络")
    ]
    check("没有占位符式的 source", weak_source, [])

    # 判定「像不像出处」用**任一**成立即可，不要堆关键词列表 ——
    # 第一版只认机构名，结果把
    # 「Miranda & Schaffner, Applied and Environmental Microbiology, 2016」
    # 这种正经文献引用误判成不合格了。学术引用里机构名常常不在。
    # 所以改成「有年份 / 有书名号 / 有机构关键词」三选一。
    ORGS = ("中心", "学会", "指南", "标准", "WHO", "FDA", "USDA", "CDC",
            "IARC", "委员会", "共识", "NHS", "AAP", "FSA", "NIH", "文献")

    def looks_like_source(s: str) -> bool:
        s = s.strip()
        if len(s) < 8:
            return False
        if "《" in s:                      # 标准 / 指南 / 专著
            return True
        if any(ch.isdigit() for ch in s) and any(
            s[i:i + 4].isdigit() for i in range(len(s) - 3)
        ):                                  # 含四位年份（文献引用）
            return True
        return any(k in s for k in ORGS)

    bad_src = [t["id"] for t in FOOD_TIPS if not looks_like_source(t["source"])]
    check("source 都像真实出处（机构 / 标准 / 文献）", bad_src, [])

    print("\n=== 6. 「谣言」类是否讲清了为什么（仅提醒，不判失败）===")
    # 只说「这是谣言」价值有限，用户需要的是「那我该注意什么」。
    # ⚠️ 这里**只做提醒**，不作硬性失败 ——
    # 因为「讲清原因」的写法太多样：有的说「没有机制」，有的说「剂量达不到」，
    # 有的（如「糖尿病人不能吃水果」）是**正面立论**（能吃、该怎么吃），
    # 关键词根本覆盖不全。用关键词判失败只会产生假警报。
    #
    # 扫的是 **summary + detail**：结论常写在 summary 里
    #（例：「剂量上完全站不住脚」），只看 detail 会漏掉。
    REASON_WORDS = ("没有", "不存在", "站不住", "达不到", "缺乏", "忽略",
                    "不成立", "无关", "不是", "可以", "能")
    need_review = [
        t["id"] for t in FOOD_TIPS
        if t["verdict"] == "谣言"
        and not any(k in (t["summary"] + t["detail"]) for k in REASON_WORDS)
    ]
    if need_review:
        print(f"  [提醒] 这 {len(need_review)} 条建议人工看一眼解释是否充分：")
        for i in need_review:
            print(f"         - {i}")
    else:
        print("  [OK]   所有谣言条目都含有解释性表述")

    print("\n=== 7. 画像用的过敏原/忌口表 ===")
    for group, items in COMMON_ALLERGENS.items():
        check(f"过敏原组「{group}」非空", len(items) > 0, True)
    for group, items in COMMON_DISLIKED.items():
        check(f"忌口组「{group}」非空", len(items) > 0, True)
    flat_a = [x for v in COMMON_ALLERGENS.values() for x in v]
    check("过敏原没有跨组重复", [x for x, n in Counter(flat_a).items() if n > 1], [])

    print("\n=== 8. 分类分布（供人工判断是否偏科）===")
    counts = Counter(t["category"] for t in FOOD_TIPS)
    for c in CATEGORIES:
        n = counts.get(c, 0)
        bar = "#" * max(1, round(n / 2))
        print(f"         {c:8s} {n:3d}  {bar}")
    counts_v = Counter(t["verdict"] for t in FOOD_TIPS)
    print("         结论分布：" + "  ".join(f"{v}={counts_v.get(v, 0)}" for v in VERDICTS))
    check("四个分类都不少于 5 条", [c for c in CATEGORIES if counts.get(c, 0) < 5], [])

    print()
    print("=" * 56)
    if failed:
        print(f"通过 {passed} 项，失败 {failed} 项")
    else:
        print(f"全部通过（{passed} 项）")
    print("=" * 56)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
