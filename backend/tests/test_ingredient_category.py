"""食材分类的回归测试。

    python tests/test_ingredient_category.py

不需要 AI 密钥、不需要网络、不需要数据库。

## 为什么必须有这个文件

分类是冰箱页分组的依据，错了不会报错，只会让食材出现在错误的组里
（「番茄酱」跑到蔬菜组、「花生油」跑到坚果里），很难发现。

而且分类有**三个来源**（图片 key / 名字关键词 / AI 返回值），
三者之间以及和图片规则表之间必须保持一致 —— 这个文件就是钉这些一致性的。
"""
from __future__ import annotations

import sys
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_DIR))

from app.services.ingredient_category import (  # noqa: E402
    CATEGORIES,
    CATEGORY_IMAGE_KEY,
    KEY_CATEGORY,
    guess_category_by_name,
    normalize_category,
    resolve_category,
    sort_key,
    universal_image_key,
)
from app.services.ingredient_image_service import INGREDIENT_RULES  # noqa: E402

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
    print("=== 1. 两张表必须对得上 ===")
    rule_keys = {key for _kw, key in INGREDIENT_RULES}
    # ⚠️ `_universal-*` 要排除掉。
    #
    # 它们**本身就是分类的代表图**（「主食」这个分类的代表就是
    # `_universal-staple.jpg`），所以「这个 key 属于哪个分类」对它没有意义 ——
    # 硬要标的话就是自己指向自己。
    #
    # 拼写错误不用怕漏：`test_ingredient_rules.py` 的 6b 会校验
    # 「规则的值都指向真实存在的图」，写错一个字母就会被抓住。
    universal_keys = {k for k in rule_keys if k.startswith("_universal-")}
    unmapped = sorted(rule_keys - set(KEY_CATEGORY) - universal_keys)
    check("图片规则表里每个 key 都标了分类", unmapped, [])

    # KEY_CATEGORY 允许包含规则表还没有的 key —— 那是**给后续扩充预留的**
    #（docs/09 里列了要补的水果、肉类、水产等）。
    # 所以这里只做两件事：报告预留了哪些，并校验命名格式没问题（防拼错）。
    reserved = sorted(set(KEY_CATEGORY) - rule_keys)
    print(f"         （另有 {len(reserved)} 个是预留 key，等图片补上后启用）")
    bad_format = [k for k in KEY_CATEGORY if not k.replace("-", "").isalnum() or k != k.lower()]
    check("所有 key 都是小写英文 + 短横线（防拼错）", bad_format, [])

    print("\n=== 2. 分类名必须是合法的 9 个之一 ===")
    bad = sorted({c for c in KEY_CATEGORY.values() if c not in CATEGORIES})
    check("KEY_CATEGORY 的值都是合法分类", bad, [])
    check("每个分类都有万能图 key", sorted(set(CATEGORIES) - set(CATEGORY_IMAGE_KEY)), [])

    print("\n=== 3. 瓜类陷阱：水果和蔬菜必须分清 ===")
    # 「瓜」是这里最容易出错的字。西瓜/哈密瓜是水果，黄瓜/冬瓜/南瓜是蔬菜。
    # 水果那一条必须排在蔬菜前面，并且把西瓜、哈密瓜明确写进水果。
    for name, want in [
        ("西瓜", "水果"), ("哈密瓜", "水果"), ("甜瓜", "水果"), ("木瓜", "水果"),
        ("黄瓜", "蔬菜"), ("冬瓜", "蔬菜"), ("南瓜", "蔬菜"),
        ("丝瓜", "蔬菜"), ("苦瓜", "蔬菜"),
    ]:
        check(f"{name} -> {want}", resolve_category(name), want)

    print("\n=== 4. 图库里没有的食材，靠名字也要能分组 ===")
    # 这正是 Ethan 要的场景：外部生成的图不可能覆盖所有食材。
    for name, want in [
        ("杨桃", "水果"), ("榴莲", "水果"), ("山竹", "水果"),
        ("百香果", "水果"), ("菠萝蜜", "水果"),
        ("鲍鱼", "水产"), ("海参", "水产"), ("扇贝", "水产"),
        ("鸭脖", "肉类"), ("鸡爪", "肉类"),
        ("鹅蛋", "蛋奶"), ("羊奶", "蛋奶"),
        ("小米粥", "主食"), ("油条", "主食"),
    ]:
        check(f"{name} -> {want}", resolve_category(name), want)

    # 海带、紫菜按清单的分类归「蔬菜」（子类：水生蔬菜与海藻），不是水产。
    # 这条容易想当然，单独钉一下。
    check("海带 -> 蔬菜（清单里归水生蔬菜）", resolve_category("海带"), "蔬菜")
    check("紫菜 -> 蔬菜（同上）", resolve_category("紫菜"), "蔬菜")

    print("\n=== 5. 加工调味料：不能被原料规则抢走 ===")
    # 「番茄酱」含「番茄」、「花生油」含「花生」—— 会命中原料的图片规则，
    # 于是既配错图（新鲜番茄）、又分错类（归进蔬菜）。必须在挡刀区拦掉。
    for name in ["番茄酱", "花生油", "生抽", "老抽", "酱油", "蚝油",
                 "醋", "苹果醋", "料酒", "食用油", "豆瓣酱"]:
        check(f"{name} -> 调味", resolve_category(name), "调味")

    print("\n=== 6. 单字关键词的误伤（例外区）===")
    check("豆浆 -> 豆制品（不被蔬菜的「豆」抢）", resolve_category("豆浆"), "豆制品")
    check("豆腐 -> 豆制品", resolve_category("豆腐"), "豆制品")
    check("油菜 -> 蔬菜（不被调味的「油」抢）", resolve_category("油菜"), "蔬菜")
    check("油麦菜 -> 蔬菜", resolve_category("油麦菜"), "蔬菜")
    check("食用油 -> 调味", resolve_category("食用油"), "调味")

    print("\n=== 7. AI 返回值的归一化 ===")
    # 百炼对同一个意思可能有好几种写法，都要能归到同一个分类
    for raw, want in [
        ("海鲜", "水产"), ("鱼类", "水产"), ("水产", "水产"), ("海产", "水产"),
        ("红肉", "肉类"), ("禽肉", "肉类"), ("肉类", "肉类"),
        ("乳制品", "蛋奶"), ("蛋类", "蛋奶"), ("奶制品", "蛋奶"), ("蛋奶", "蛋奶"),
        ("青菜", "蔬菜"), ("菌菇", "蔬菜"), ("蔬菜", "蔬菜"),
        ("调料", "调味"), ("调味品", "调味"), ("调味", "调味"),
        ("谷类", "主食"), ("谷物", "主食"), ("主食", "主食"),
        ("其它", "其他"), ("其他", "其他"),
    ]:
        check(f"AI 说「{raw}」-> {want}", normalize_category(raw), want)

    check("AI 说了个不认识的词 -> None（交给调用方决定）", normalize_category("赛博朋克"), None)
    check("AI 什么都没说 -> None", normalize_category(""), None)
    check("None -> None", normalize_category(None), None)

    print("\n=== 8. resolve_category 永远不返回 None ===")
    for name in ["", "xyzzy", "完全不认识的东西", "？？？"]:
        got = resolve_category(name)
        check(f"{name!r} -> 有分类（{got}）", got in CATEGORIES, True)

    print("\n=== 9. 图片 key 优先于关键词（保证分类和配图一致）===")
    # 「番茄」既含「茄」也命中 tomato 规则。key 优先，所以归蔬菜 ——
    # 如果让关键词先跑，结果可能不一致，用户会看到「水果组里配了蔬菜图」。
    check("番茄 -> 蔬菜（跟图片一致）", resolve_category("番茄"), "蔬菜")
    check("牛油果 -> 水果（不被 beef 的「牛」影响）", resolve_category("牛油果"), "水果")
    check("鸡精 -> 调味（不被 chicken 的「鸡」抢）", resolve_category("鸡精"), "调味")

    print("\n=== 10. 万能图 key ===")
    for cat in CATEGORIES:
        key = universal_image_key(cat)
        check(f"{cat} 有万能图 key（{key}）", bool(key and key.startswith("_")), True)
    check("不认识的分类 -> None", universal_image_key("赛博朋克"), None)

    print("\n=== 11. 万能图降级链 ===")
    # 真实场景：外部生成的图不可能覆盖所有食材。
    # 有 574 张具体图之后，触发万能图的情况少了很多 ——
    # 所以这里用**真的不存在**的食材名来测，不能用「杨桃」
    #（杨桃已经有 star-fruit.jpg 了，走的是具体图那条路）。
    from app.core.config import settings  # noqa: E402
    from app.services.ingredient_image_service import resolve_ingredient_image  # noqa: E402

    img_dir = settings.STATIC_DIR / "ingredients"
    probe = img_dir / "_universal-fruit.jpg"
    created = False
    if not probe.exists():
        # 借一张现有的图来验证降级链；验完删掉，不留垃圾
        src = img_dir / "apple.jpg"
        if src.exists():
            probe.write_bytes(src.read_bytes())
            created = True

    try:
        if probe.exists():
            # 「火星果」认不出具体 key，但名字里的「果」让它属于水果
            check("火星果（词库没有）-> 退到水果万能图",
                  resolve_ingredient_image("火星果"), "/static/ingredients/_universal-fruit.jpg")
            # 有具体图的仍然用具体图，不能被万能图顶掉
            check("杨桃 有具体图，不用万能图",
                  resolve_ingredient_image("杨桃"), "/static/ingredients/star-fruit.jpg")
            check("西红柿 同上",
                  resolve_ingredient_image("西红柿"), "/static/ingredients/tomato.jpg")
            # 没有万能图的分类，仍然返回 None（客户端走占位样式）
            # 鸭脖以前没有图、只能显示占位；现在词库里有 duck-neck 了
            check("鸭脖 现在有具体图",
                  resolve_ingredient_image("鸭脖"), "/static/ingredients/duck-neck.jpg")
        else:
            print("  [SKIP] 找不到可借用的图片，跳过降级链验证")
    finally:
        if created and probe.exists():
            probe.unlink()

    # 注意别用「赛博朋克炒饭」这种名字测 —— 它含「饭」，会被主食规则命中，
    # 那是**正确**行为（它确实是以饭为主料的）。要测「认不出来」，
    # 得用一个真的不含任何食材关键词的名字。
    # 词库里没有、但能归到某个分类的食材 → 退到该分类的万能图。
    # 这是「万能图」存在的意义：比空白占位好，也不会配错具体图。
    check("完全不认识的食材 -> 退到「其他」万能图",
          resolve_ingredient_image("夸克胶子等离子体"),
          "/static/ingredients/_universal-other.jpg")
    # 但**空名字必须返回 None** —— 空名字没有分类意义，
    # 给它配一张「其他」的图，客户端会拿去配一个空的食材行。
    check("空名字 -> None（不配图）", resolve_ingredient_image(""), None)

    print("\n=== 12. 显示顺序 ===")
    check("蔬菜排第一", sort_key("蔬菜"), 0)
    check("其他排最后", sort_key("其他"), len(CATEGORIES) - 1)
    check("不认识的分类也排最后", sort_key("赛博朋克"), len(CATEGORIES))

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
