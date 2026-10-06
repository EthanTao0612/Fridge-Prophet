#!/usr/bin/env python3
"""为菜品库里的每一道菜**离线生成**一份完整做法，存成 JSON。

    cd backend
    .venv/Scripts/python.exe ../tools/generate-dish-recipes.py
    .venv/Scripts/python.exe ../tools/generate-dish-recipes.py --only 清炒西兰花
    .venv/Scripts/python.exe ../tools/generate-dish-recipes.py --limit 5

输入：`backend/app/services/dish_library.py` 的 `DISH_NAMES`（192 道）
输出：`backend/data/dish-recipes.json`

## 为什么要有这个脚本

在这之前，点开一道推荐菜是**现场调 AI** 生成做法（`POST /recipes/materialize`），
第一次点要等十几秒。Ethan 明确要求：

> 「我不要这种每次点开都 ai 现场生成的，我要那种本来就现成的」

所以改成：**离线生成一次 → 存进数据库 → 之后点开是秒开、且不花 AI 额度**。

## 为什么存 JSON 而不是直接写库

1. **能进 Git** —— 192 道菜的做法可以复核、可以 diff、可以回滚
2. **可重跑** —— 某道菜生成得不好，删掉那一条重跑就行
3. **和写库解耦** —— 写库是 `tools/seed-dish-recipes.py` 的事，
   那边要处理幂等、user_id=NULL 等数据库细节，混在一起会很难读

## 断点续跑

每生成一道就**立刻**写一次 JSON。中途 Ctrl+C 或者断网，
重新跑会跳过已经有的，接着生成剩下的。
192 次要跑 30-40 分钟，不能因为最后一道失败就全部重来。

## 为什么用「菜品自己的食材」当库存

`generate_recipes()` 在库存为空时**直接返回空列表**（那是给「冰箱空时
不该调模型」用的保护）。而离线生成时我们没有真实用户库存，
所以拿菜品库里的必需食材 + 可选食材拼一个合成库存给它当上下文。

这不会污染数据：合成库存只在内存里，不落库；
而且存进菜谱的 `available` 标记在每次读取时都会用**真实库存**重算
（`_with_availability`），所以合成库存填什么都不影响最终展示。
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import date, timedelta
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1] / "backend"
sys.path.insert(0, str(BACKEND_DIR))

from app.models.inventory import FoodInventory  # noqa: E402
from app.services.dish_library import DISH_CATEGORIES, DISH_INGREDIENTS, DISH_NAMES  # noqa: E402
from app.services.recipe_service import generate_recipes  # noqa: E402

OUT_PATH = BACKEND_DIR / "data" / "dish-recipes.json"


def _fake_inventory(dish: str) -> list[FoodInventory]:
    """用菜品自己的食材拼一个合成库存。

    为什么不用空列表：`generate_recipes()` 见空库存会直接返回 `[]`，
    那是「冰箱空时别调模型」的保护。离线生成时我们要的是
    「写一道标准做法」，所以给它「这些食材都有」的上下文。
    """
    required, optional = DISH_INGREDIENTS.get(dish, ((), ()))
    names = list(required) + list(optional)
    if not names:
        # 菜品库里理论上不会有这种条目，但真出现时给个兜底，
        # 总比让 generate_recipes 静默返回空、最后表现为「这道菜生成失败」好
        names = [dish]

    today = date.today()
    items: list[FoodInventory] = []
    for name in names:
        item = FoodInventory(
            user_id=0,          # 不落库，这个值不会被用到
            food_name=name,
            quantity=1,
            unit="份",
            storage_location="冷藏",
            purchase_date=today,
            expiry_date=today + timedelta(days=7),
        )
        items.append(item)
    return items


def _load_existing() -> dict:
    if not OUT_PATH.exists():
        return {}
    try:
        return json.loads(OUT_PATH.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        # JSON 坏了（比如上次写一半被强杀）—— 不要默默丢掉，
        # 报出来让人决定是修还是删
        print(f"⚠️  {OUT_PATH} 不是合法 JSON，无法续跑。")
        print("    修好它，或者删掉它从头生成。")
        sys.exit(1)


def _save(data: dict) -> None:
    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    # 先写临时文件再替换：避免写到一半被中断，留下半个坏 JSON
    tmp = OUT_PATH.with_suffix(".json.tmp")
    tmp.write_text(
        json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True),
        encoding="utf-8",
    )
    tmp.replace(OUT_PATH)


def main() -> int:
    parser = argparse.ArgumentParser(description="离线生成菜品库的做法")
    parser.add_argument("--only", help="只生成这一道（菜名要完全一致）")
    parser.add_argument("--limit", type=int, help="只生成前 N 道（调试用）")
    parser.add_argument("--force", action="store_true", help="已存在的也重新生成")
    args = parser.parse_args()

    dishes = sorted(DISH_NAMES)
    if args.only:
        if args.only not in DISH_NAMES:
            print(f"菜品库里没有「{args.only}」")
            return 1
        dishes = [args.only]
    elif args.limit:
        dishes = dishes[: args.limit]

    data = _load_existing()
    todo = [d for d in dishes if args.force or d not in data]

    print(f"菜品库共 {len(DISH_NAMES)} 道，本次要生成 {len(todo)} 道")
    if not todo:
        print("没有需要生成的，直接结束")
        return 0
    print(f"输出：{OUT_PATH.relative_to(BACKEND_DIR.parent)}")
    print("（每生成一道就写一次盘，中途断了可以重跑续上）")
    print()

    ok = 0
    failed: list[tuple[str, str]] = []
    started = time.time()

    for i, dish in enumerate(todo, 1):
        t0 = time.time()
        try:
            recipes, model = generate_recipes(
                inventory=_fake_inventory(dish),
                pref=None,
                health=None,
                count=1,
                max_time=None,
                expiring=[],
                # ⚠️ 不传 exclude：这里要的就是「照着这道菜写」，
                #    传了反而可能让它避开这道菜本身
                focus_dish=dish,
            )
        except Exception as exc:                      # noqa: BLE001
            # 单道失败不能让整批停 —— 记下来最后统一报，继续跑下一道
            failed.append((dish, f"{type(exc).__name__}: {exc}"))
            print(f"[{i:>3}/{len(todo)}] {dish:<12} ✗ {type(exc).__name__}")
            continue

        if not recipes:
            failed.append((dish, "模型没有返回菜谱"))
            print(f"[{i:>3}/{len(todo)}] {dish:<12} ✗ 空结果")
            continue

        r = recipes[0]
        steps = list(r.steps or [])
        data[dish] = {
            "name": r.name,
            "description": r.description,
            "time_minutes": r.time_minutes,
            "difficulty": r.difficulty,
            "tags": list(r.tags or []),
            "steps": steps,
            "nutrition": dict(r.nutrition or {}),
            "category": DISH_CATEGORIES.get(dish, ""),
            "image_key": DISH_NAMES.get(dish, ""),
            "ingredients": [
                {
                    "name": ing.name,
                    "quantity": ing.quantity,
                    "unit": ing.unit,
                    "optional": bool(ing.optional),
                }
                for ing in (r.ingredients or [])
            ],
            "_model": model,
            "_generated_at": date.today().isoformat(),
        }
        _save(data)

        ok += 1
        elapsed = time.time() - t0
        avg = (time.time() - started) / i
        remain = (len(todo) - i) * avg
        print(
            f"[{i:>3}/{len(todo)}] {dish:<12} ✓ {len(steps)} 步 {elapsed:>5.1f}s"
            f"   预计还要 {remain/60:.0f} 分钟"
        )

    print()
    print("=" * 56)
    print(f"成功 {ok} 道，失败 {len(failed)} 道，用时 {(time.time()-started)/60:.1f} 分钟")
    print(f"累计已生成 {len(data)}/{len(DISH_NAMES)} 道 → {OUT_PATH.name}")
    if failed:
        print()
        print("失败的（重跑本脚本会自动补这些）：")
        for dish, why in failed:
            print(f"  - {dish}：{why}")
    print("=" * 56)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
