#!/usr/bin/env python3
"""把 `backend/data/dish-recipes.json` 写进数据库，作为**系统内置菜谱**。

    cd backend
    .venv/Scripts/python.exe ../tools/seed-dish-recipes.py --check    # 先看差异，不写
    .venv/Scripts/python.exe ../tools/seed-dish-recipes.py            # 写入 / 更新
    .venv/Scripts/python.exe ../tools/seed-dish-recipes.py --reset    # 删掉内置的重新灌

## 为什么 user_id 写 NULL

`recipes.user_id` 为 NULL 表示「系统内置菜谱，所有用户可见」
（见 `app/models/recipe.py` 的注释）。

这样 192 道菜在库里**只有一份**，任何用户 —— 包括评委新注册的账号 ——
点开推荐菜都是秒开，不用为每个用户各存一份，也不用调 AI。

## 幂等

按菜名 upsert，而且**只动 `user_id IS NULL` 的行**，绝不碰用户自己的菜谱。
所以改完 JSON 直接重跑就会更新，不会产生重复。

## 为什么用固定旧时间戳

列表接口按 `created_at` 倒序。如果内置菜谱拿「现在」当创建时间，
192 条会把用户自己刚生成的菜全挤到后面去，用户会以为自己的菜没了。

所以给一个固定的旧基准时间（2026-01-01）+ 按菜名序号递增：
既保证内置菜谱永远排在用户自己的菜之后，
又保证内置菜谱之间顺序稳定（不会每次查库都变）。

## 什么时候要跑

- 本地：改完 `dish-recipes.json` 之后
- 服务器：`tools/deploy.sh` 会在服务起来之后自动跑一次
- **换了 Supabase 项目之后必须重跑**（新库是空的）
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1] / "backend"
sys.path.insert(0, str(BACKEND_DIR))

from app.db.session import SessionLocal  # noqa: E402
from app.models.recipe import Recipe, RecipeIngredient  # noqa: E402
from app.services.food_image_service import resolve_image_url  # noqa: E402
from app.services.recipe_service import clean_unit  # noqa: E402

DATA_PATH = BACKEND_DIR / "data" / "dish-recipes.json"

# 内置菜谱的创建时间基准。挑一个明显早于任何真实使用的日期，
# 保证它们永远排在用户自己的菜谱之后。
BUILTIN_EPOCH = datetime(2026, 1, 1, 0, 0, 0, tzinfo=timezone.utc)


def _load() -> dict:
    if not DATA_PATH.exists():
        print(f"找不到 {DATA_PATH}")
        print("先跑 tools/generate-dish-recipes.py 生成它。")
        sys.exit(1)
    return json.loads(DATA_PATH.read_text(encoding="utf-8"))


def _builtin_rows(db) -> dict[str, Recipe]:
    """库里现有的内置菜谱，按菜名索引。"""
    rows = db.query(Recipe).filter(Recipe.user_id.is_(None)).all()
    return {r.name: r for r in rows}


def _apply(row: Recipe, dish: str, payload: dict, index: int) -> None:
    """把 JSON 里的字段刷到这一行上。"""
    ingredients = payload.get("ingredients") or []

    row.description = payload.get("description") or ""
    row.time_minutes = int(payload.get("time_minutes") or 30)
    row.difficulty = payload.get("difficulty") or "easy"
    row.steps = list(payload.get("steps") or [])
    row.nutrition = dict(payload.get("nutrition") or {})
    row.tags = list(payload.get("tags") or [])
    row.source = "builtin"
    # 顺手把配图算出来存下 —— 否则每次读列表都要重算一遍
    row.image_url = row.image_url or resolve_image_url(
        dish, [i.get("name", "") for i in ingredients]
    )
    # 序号决定内置菜谱之间的相对顺序，所以每次跑结果都一样
    row.created_at = BUILTIN_EPOCH + timedelta(seconds=index)

    # 配料整批换掉。`cascade="all, delete-orphan"` 会负责删掉旧的，
    # 所以直接赋新列表就行 —— 不要手动 delete 再 add，容易漏。
    #
    # ⚠️ `unit` 必须过 `clean_unit`：模型偶尔在单位字段里塞注释
    #（实测「汤匙（即oyster-sauce）」17 个字、「个（可选）」），
    # 而 `recipe_ingredients.unit` 是 VARCHAR(16)。
    # SQLite 不校验长度，所以本地测不出来；Postgres 会直接
    # StringDataRightTruncation → 整个灌库失败。
    row.ingredients = [
        RecipeIngredient(
            name=str(i.get("name", "")).strip(),
            quantity=float(i.get("quantity") or 0),
            unit=clean_unit(str(i.get("unit") or "g")),
            optional=bool(i.get("optional")),
        )
        for i in ingredients
        if str(i.get("name", "")).strip()
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description="把菜品库的做法灌进数据库")
    parser.add_argument("--check", action="store_true",
                        help="只报告差异，不写数据库")
    parser.add_argument("--reset", action="store_true",
                        help="先删掉所有内置菜谱再重新灌（不会碰用户自己的）")
    args = parser.parse_args()

    data = _load()
    if not data:
        print(f"{DATA_PATH.name} 是空的，先跑生成脚本。")
        return 1

    db = SessionLocal()
    try:
        existing = _builtin_rows(db)
        print(f"JSON 里 {len(data)} 道菜，库里现有内置菜谱 {len(existing)} 道")

        if args.reset and not args.check:
            removed = db.query(Recipe).filter(Recipe.user_id.is_(None)).delete(
                synchronize_session=False
            )
            db.commit()
            existing = {}
            print(f"--reset：已删除 {removed} 条内置菜谱（用户自己的没动）")

        missing = [d for d in sorted(data) if d not in existing]
        outdated = []
        for dish in sorted(data):
            row = existing.get(dish)
            if row is None:
                continue
            payload = data[dish]
            if (row.steps or []) != list(payload.get("steps") or []):
                outdated.append(dish)

        print(f"  待新建 {len(missing)} 道，内容有变化 {len(outdated)} 道")

        if args.check:
            print()
            if missing:
                print("还缺这些（前 20 个）：")
                for d in missing[:20]:
                    print(f"  - {d}")
            if outdated:
                print("这些内容变了（前 20 个）：")
                for d in outdated[:20]:
                    print(f"  - {d}")
            if not missing and not outdated:
                print("数据库已经是最新的，不用写。")
            print()
            print("（--check 模式没有写任何数据）")
            return 0

        created = updated = 0
        for index, dish in enumerate(sorted(data)):
            payload = data[dish]
            row = existing.get(dish)
            if row is None:
                row = Recipe(user_id=None, name=dish, source="builtin")
                db.add(row)
                created += 1
            else:
                updated += 1
            _apply(row, dish, payload, index)

        db.commit()

        total = db.query(Recipe).filter(Recipe.user_id.is_(None)).count()
        print()
        print("=" * 56)
        print(f"新建 {created} 道，更新 {updated} 道")
        print(f"库里现在有 {total} 道内置菜谱")
        print("=" * 56)

        # 抽一道出来验证真的写进去了 —— 光看条数不够，
        # 有可能字段全是空的但行数对
        sample = db.query(Recipe).filter(Recipe.user_id.is_(None)).first()
        if sample is not None:
            print()
            print(f"抽查「{sample.name}」：")
            print(f"  步骤 {len(sample.steps or [])} 条，"
                  f"配料 {len(sample.ingredients)} 样，"
                  f"热量 {((sample.nutrition or {}).get('calories_kcal'))} 千卡，"
                  f"用时 {sample.time_minutes} 分钟，"
                  f"难度 {sample.difficulty}")
            print(f"  第一步：{(sample.steps or ['（空）'])[0][:50]}")
        return 0
    finally:
        db.close()


if __name__ == "__main__":
    raise SystemExit(main())
