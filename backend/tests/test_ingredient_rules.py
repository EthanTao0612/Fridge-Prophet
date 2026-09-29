"""食材配图规则的回归测试。

    python tests/test_ingredient_rules.py

不需要 AI 密钥、不需要网络、不需要数据库 —— 只测规则表的**顺序**。

## 为什么必须有这个文件

`INGREDIENT_RULES` 是「从上往下匹配、命中即停」的，所以**顺序就是语义**。
里面有几个**单字关键词**（`牛`、`米`、`鱼`、`鸡`、`粉`、`蛋`、`葱`…），
它们会抢走本不该属于自己的食材，而**这种错误不会报错**，
只会让冰箱列表里出现一张莫名其妙的图：

    牛油果 → 牛排的照片       （被 `牛` 抢走）
    花生米 → 米饭的照片       （被 `米` 抢走）
    鱿鱼   → 鱼的照片         （被 `鱼` 抢走，海鲜规则白写）
    鸡精   → 鸡肉的照片       （被 `鸡` 抢走）

配错图比没图更糟：用户会以为识别错了，而不是以为缺图。
所以每一条「挡刀规则」都在下面钉一个断言。

`resolve_ingredient_key()`（只算 key、不查磁盘）就是为这个测试拆出来的 ——
图还没生成时也能验规则是否正确。
"""
from __future__ import annotations

import sys
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_DIR))

from app.services.ingredient_image_service import (  # noqa: E402
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
    print("=== 1. 挡刀规则：单字关键词不许抢走别的食材 ===")
    # 每一行对应 INGREDIENT_RULES 顶部「挡刀区」的一条规则。
    # 这些 key 目前大多还没有配图（返回 None 是正常的），
    # 这里断言的是**它没有被别的规则抢走**。
    check("牛油果 不被 beef 的「牛」抢走", resolve_ingredient_key("牛油果"), "avocado")
    check("鳄梨 同上", resolve_ingredient_key("鳄梨"), "avocado")
    check("花生米 不被 rice 的「米」抢走", resolve_ingredient_key("花生米"), "peanut")
    check("花生 同上", resolve_ingredient_key("花生"), "peanut")
    check("南瓜子 不被 pumpkin 抢走", resolve_ingredient_key("南瓜子"), "seeds")
    check("西瓜子 不被 watermelon 抢走", resolve_ingredient_key("西瓜子"), "seeds")
    check("松子 同上", resolve_ingredient_key("松子"), "seeds")
    check("鸡精 不被 chicken 的「鸡」抢走", resolve_ingredient_key("鸡精"), "seasoning")
    check("葡萄柚 不被 grape 的「葡萄」抢走", resolve_ingredient_key("葡萄柚"), "grapefruit")
    check("淀粉 不被 noodles 的「粉」抢走", resolve_ingredient_key("淀粉"), "starch")
    check("青柠 不被 lemon 抢走", resolve_ingredient_key("青柠"), "lime")
    check("猕猴桃 不被「桃」类规则抢走", resolve_ingredient_key("猕猴桃"), "kiwi")
    check("核桃 不被「桃」类规则抢走", resolve_ingredient_key("核桃"), "walnut")

    print("\n=== 2. 海鲜必须排在鱼前面（「鱿鱼」含「鱼」）===")
    check("鱿鱼 -> seafood", resolve_ingredient_key("鱿鱼"), "seafood")
    check("章鱼 -> seafood", resolve_ingredient_key("章鱼"), "seafood")
    check("墨鱼 -> seafood", resolve_ingredient_key("墨鱼"), "seafood")
    check("蟹 -> seafood", resolve_ingredient_key("蟹"), "seafood")
    # 但不能把真正的鱼也抢走
    check("三文鱼 仍然是 fish", resolve_ingredient_key("三文鱼"), "fish")
    check("带鱼 仍然是 fish", resolve_ingredient_key("带鱼"), "fish")

    print("\n=== 3. 蛋奶必须排在肉类前面（「鸡蛋」含「鸡」）===")
    check("鸡蛋 -> egg", resolve_ingredient_key("鸡蛋"), "egg")
    check("鸭蛋 -> egg", resolve_ingredient_key("鸭蛋"), "egg")
    check("鹌鹑蛋 -> egg", resolve_ingredient_key("鹌鹑蛋"), "egg")
    check("鸡胸肉 仍然是 chicken", resolve_ingredient_key("鸡胸肉"), "chicken")
    check("鸡翅 仍然是 chicken", resolve_ingredient_key("鸡翅"), "chicken")

    print("\n=== 4. 蔬菜类不互相抢 ===")
    check("洋葱 -> onion（不被 seasoning 的「葱」抢）", resolve_ingredient_key("洋葱"), "onion")
    check("大葱 -> seasoning", resolve_ingredient_key("大葱"), "seasoning")
    check("西红柿 -> tomato", resolve_ingredient_key("西红柿"), "tomato")
    check("玉米 -> corn（不被 rice 的「米」抢）", resolve_ingredient_key("玉米"), "corn")
    check("西兰花 -> broccoli", resolve_ingredient_key("西兰花"), "broccoli")
    check("青椒 -> green-pepper", resolve_ingredient_key("青椒"), "green-pepper")

    print("\n=== 5. 扩过的规则要能命中 ===")
    check("平菇 -> mushroom（补了单字「菇」）", resolve_ingredient_key("平菇"), "mushroom")
    check("银耳 -> mushroom", resolve_ingredient_key("银耳"), "mushroom")
    check("黑胡椒 -> seasoning（补了「胡椒」）", resolve_ingredient_key("黑胡椒"), "seasoning")
    check("花椒 -> seasoning", resolve_ingredient_key("花椒"), "seasoning")

    print("\n=== 6. 只返回磁盘上真实存在的图（没有的必须退化成 None）===")
    # 这是「代码先铺好、图后补」的安全保证：
    # 规则算出了 key，但文件还没生成时，绝不能返回一个 404 的 URL。
    keys_on_disk = set(available_keys())
    print(f"         磁盘上现有 {len(keys_on_disk)} 张食材图")

    for name in ("牛油果", "花生米", "猕猴桃", "葡萄柚", "淀粉"):
        key = resolve_ingredient_key(name)
        url = resolve_ingredient_image(name)
        if key in keys_on_disk:
            check(f"{name} 有图 -> 返回 URL", url is not None, True)
        else:
            check(f"{name} 还没配图 -> 返回 None（走占位样式）", url, None)

    # 反向确认：有图的食材一定要能取到 URL
    for name in ("鸡蛋", "西红柿", "鸡胸肉", "西兰花"):
        check(f"{name} 有图且能取到", resolve_ingredient_image(name) is not None, True)

    print("\n=== 7. 匹配不上的返回 None，不许瞎猜 ===")
    check("「xyzzy」-> None", resolve_ingredient_key("xyzzy"), None)
    check("空字符串 -> None", resolve_ingredient_key(""), None)

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
