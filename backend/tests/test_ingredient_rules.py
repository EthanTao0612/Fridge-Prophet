"""食材匹配的回归测试。

    python tests/test_ingredient_rules.py

不需要 AI 密钥、不需要网络、不需要数据库。

## 测什么

匹配是**五级降级**的，每一级都在下面钉了断言：

| 级 | 依据 | 典型场景 |
|---|---|---|
| ① 挡刀区 | 手写规则，防加工品误配 | 番茄酱 → seasoning（不是番茄） |
| ② 词库精确 | 574 条标准中文名 | 五花肉 → pork-belly |
| ③ 别名 | 手写的高频俗称 | 车厘子 → cherry |
| ④ **子串匹配** | 名字被包含，**最长优先** | **肥羊肉 → 羊肉** |
| ⑤ 关键词兜底 | 手写规则，覆盖词库没有的写法 | — |

## 为什么必须有这个文件

这类错误**不会报错**，只会让冰箱列表里出现一张莫名其妙的图：

    牛油果 → 牛排的照片     （被单字「牛」抢走）
    番茄酱 → 新鲜番茄的照片  （原料规则太宽）
    凤梨酥 → 梨的照片       （被单字「梨」抢走）

配错图比没图更糟：用户会以为识别错了，而不是以为缺图。

## 词库是自动生成的

`ingredient_lexicon.py` 由 `tools/build-ingredient-lexicon.py` 生成。
这里只测**匹配逻辑**，不测词库内容本身 ——
词库的正确性由生成脚本的分类校验兜底。
"""
from __future__ import annotations

import sys
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_DIR))

from app.services.ingredient_image_service import (  # noqa: E402
    _exists,
    EXTRA_ALIASES,
    GUARD_RULES,
    KEYWORD_RULES,
    available_keys,
    resolve_ingredient_image,
    resolve_ingredient_key,
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
        print(f"  [FAIL] {label}  期望 {expected!r}，实际 {actual!r}")


def main() -> int:
    print("=== 1. 挡刀区：加工品不能命中原料的图 ===")
    # 「番茄酱」含「番茄」、「花生油」含「花生」—— 不拦的话会配新鲜番茄/花生米的图，
    # 而且分类也跟着错（番茄酱会被归进「蔬菜」）。
    #
    # ⚠️ 这一组只留**词库覆盖不到**的复合词。
    # 「生抽」「蚝油」这些词库里有更精确的 key（light-soy-sauce / oyster-sauce），
    # 拦成笼统的 seasoning 反而是倒退 —— 所以下面断言的是具体 key。
    for name, want in [
        ("番茄酱", "seasoning"), ("花生油", "seasoning"),
        ("食用油", "seasoning"), ("苹果醋", "seasoning"),
        ("生抽", "light-soy-sauce"), ("老抽", "dark-soy-sauce"),
        ("酱油", "soy-sauce"), ("蚝油", "oyster-sauce"),
        ("醋", "vinegar"), ("料酒", "cooking-wine"),
        ("豆瓣酱", "doubanjiang"),
    ]:
        check(f"{name} -> {want}", resolve_ingredient_key(name), want)

    print("\n=== 2. 挡刀区：单字关键词不许抢走别的食材 ===")
    check("牛油果 不被「牛」抢走", resolve_ingredient_key("牛油果"), "avocado")
    check("花生米 不被「米」抢走", resolve_ingredient_key("花生米"), "peanut")
    check("鸡精 不被「鸡」抢走", resolve_ingredient_key("鸡精"), "seasoning")
    # 葡萄柚指向「柚子」（pomelo）而不是单独的 grapefruit ——
    # 清单里只有柚子的图，两者外观接近，用它的图比退到万能图好。
    check("葡萄柚 不被「葡萄」抢走", resolve_ingredient_key("葡萄柚"), "pomelo")
    # 笼统的「淀粉」词库里没有，走别名指向 cornstarch（有图）；
    # 而「木薯淀粉」这种具体的仍然走词库，拿到自己的专属图。
    check("淀粉 -> cornstarch（有图，不是笼统的 starch）",
          resolve_ingredient_key("淀粉"), "cornstarch")
    check("木薯淀粉 走词库，拿到专属图",
          resolve_ingredient_key("木薯淀粉"), "tapioca-starch")
    check("鸡腿菇 不被「鸡腿」抢走", resolve_ingredient_key("鸡腿菇"), "mushroom")
    check("羊奶 不被「羊」抢走", resolve_ingredient_key("羊奶"), "milk")

    print("\n=== 3. 词库精确匹配（574 条标准名）===")
    for name, want in [
        ("五花肉", "pork-belly"), ("排骨", "pork-ribs"), ("牛腩", "beef-brisket"),
        ("三文鱼", "salmon"), ("带鱼", "hairtail-fish"), ("鱿鱼", "squid"),
        ("章鱼", "octopus"), ("鸭蛋", "duck-egg"), ("鹌鹑蛋", "quail-egg"),
        ("鸡胸肉", "chicken-breast"), ("鸡翅", "chicken-wing"),
        ("平菇", "oyster-mushroom"), ("银耳", "white-fungus"),
        ("大葱", "scallion"), ("紫菜", "nori"), ("海带", "kelp"),
    ]:
        check(f"{name} -> {want}", resolve_ingredient_key(name), want)

    print("\n=== 4. ⭐ 子串匹配：「肥羊肉」要能认成羊肉 ===")
    # 这是 Ethan 明确提的需求：用户输的词库里没有，但要能理解。
    # 做法是「名字被包含，最长优先」。
    for name, want in [
        ("肥羊肉", "lamb"),        # 词库只有「羊肉」
        ("羔羊肉", "lamb"),
        ("内蒙古羔羊肉", "lamb"),   # 名字很长，照样能捞出核心词
        ("新鲜菠菜", "spinach"),
        ("小番茄", "tomato"),
        ("冷冻虾仁", "peeled-shrimp"),
        ("澳洲牛排", "beef-steak"),
        ("有机胡萝卜", "carrot"),
        ("精选五花肉", "pork-belly"),
    ]:
        check(f"{name} -> {want}", resolve_ingredient_key(name), want)

    print("\n=== 5. ⭐ 最长优先：别被更短的名字抢走 ===")
    # 「杏鲍菇」含「杏」、「凤梨酥」含「梨」—— 如果按短的先匹配就会配错图。
    # 按名字长度倒序扫，第一次命中就是最长的那个。
    check("杏鲍菇 -> 杏鲍菇（不是杏）",
          resolve_ingredient_key("杏鲍菇"), "king-oyster-mushroom")
    check("新鲜杏鲍菇 -> 杏鲍菇（不是杏）",
          resolve_ingredient_key("新鲜杏鲍菇"), "king-oyster-mushroom")
    check("凤梨酥 -> 凤梨酥（不是梨）",
          resolve_ingredient_key("凤梨酥"), "pineapple-cake")
    check("凤梨 -> 菠萝", resolve_ingredient_key("凤梨"), "pineapple")
    check("银杏 -> 白果（不是杏）", resolve_ingredient_key("银杏"), "ginkgo-nut")

    print("\n=== 6. 别名：词库里没有但用户常说的 ===")
    for name in EXTRA_ALIASES:
        got = resolve_ingredient_key(name)
        check(f"{name} -> {EXTRA_ALIASES[name]}", got, EXTRA_ALIASES[name])

    print("\n=== 6b. ⚠️ 别名的值必须是图片 key，不能是中文名 ===")
    # 这个错犯过三次：酸笋->酸菜、椰蓉->椰子、香椿->香椿芽。
    # 每次都是「看起来配好了」，实际指向一个不存在的 key，
    # 静默退到万能图 —— 不报错，很难发现。
    import re as _re

    bad_targets = [
        f"{k} -> {v}" for k, v in EXTRA_ALIASES.items()
        if not _re.fullmatch(r"[a-z0-9]+(-[a-z0-9]+)*", v)
    ]
    check("EXTRA_ALIASES 的值都是合法的 key（小写字母数字短横线）", bad_targets, [])

    bad_guard = [
        f"{kws[0]} -> {v}" for kws, v in GUARD_RULES
        if not _re.fullmatch(r"[a-z0-9]+(-[a-z0-9]+)*", v)
    ]
    check("GUARD_RULES 的值都是合法的 key", bad_guard, [])

    # 值指向的 key 必须真的有图（或者明确是「故意没图」的占位 key）
    NO_IMAGE_BY_DESIGN = {"leafy-green", "seeds", "grapefruit", "starch"}
    dangling = [
        f"{k} -> {v}" for k, v in EXTRA_ALIASES.items()
        if not _exists(v) and v not in NO_IMAGE_BY_DESIGN
    ]
    check("别名的值都指向真实存在的图", dangling, [])

    print("\n=== 7. 关键词兜底：词库完全没有的写法 ===")
    # 这一级是最后一道防线，用构造出来的名字测（真实食材基本都被词库覆盖了）
    check("某个不存在的猪类食材 -> 走肉类关键词",
          resolve_ingredient_key("野猪里脊"), "pork")
    check("认不出来 -> None（交给万能图）",
          resolve_ingredient_key("夸克胶子等离子体"), None)
    check("空字符串 -> None", resolve_ingredient_key(""), None)

    print("\n=== 8. 规则表自身的健康检查 ===")
    # 挡刀区必须比关键词区优先，否则拦不住
    check("挡刀区非空", len(GUARD_RULES) > 0, True)
    check("关键词兜底区非空", len(KEYWORD_RULES) > 0, True)
    # 挡刀区里不该有重复关键词（说明有冗余规则）
    seen: dict[str, str] = {}
    dup: list[str] = []
    for keywords, key in GUARD_RULES:
        for kw in keywords:
            if kw in seen:
                dup.append(f"{kw}({seen[kw]} / {key})")
            seen[kw] = key
    check("挡刀区没有重复关键词", dup, [])

    print("\n=== 9. 只返回磁盘上真实存在的图 ===")
    keys_on_disk = set(available_keys())
    print(f"         磁盘上现有 {len(keys_on_disk)} 张食材图")
    check("磁盘上图片数量 > 500（574 张新批次已导入）", len(keys_on_disk) > 500, True)

    for name in ("番茄", "五花肉", "三文鱼", "苹果"):
        check(f"{name} 能取到图", resolve_ingredient_image(name) is not None, True)
    # 规则算出了 key 但没图时，必须退到万能图或 None，不能返回 404 地址
    check("完全没有的食材 -> None 或万能图（不会是裂图）",
          resolve_ingredient_image("夸克胶子等离子体") is None
          or resolve_ingredient_image("夸克胶子等离子体").startswith("/static/"), True)

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
