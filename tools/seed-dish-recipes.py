#!/usr/bin/env python3
"""把 `backend/data/dish-recipes.json` 写进数据库，作为**系统内置菜谱**。

    cd backend
    .venv/Scripts/python.exe ../tools/seed-dish-recipes.py --check    # 先看差异，不写
    .venv/Scripts/python.exe ../tools/seed-dish-recipes.py            # 写入 / 更新
    .venv/Scripts/python.exe ../tools/seed-dish-recipes.py --reset    # 删掉内置的重新灌

## 真正的实现在哪

核心逻辑在 **`app/services/dish_seed.py`** —— 这里只是一层命令行外壳。

为什么分开：**测试也需要灌同一份数据**。`/recipes/generate` 现在是
「查库挑菜」（不调 AI），测试库空的话它永远返回空。
如果测试里手抄一遍灌库逻辑，两份实现迟早会不一致
（改了 CSV 忘了改测试），那时候测试测的就不是线上跑的东西了。

## 什么时候要跑

- 本地：改完 `dish-recipes.json` 之后
- 服务器：`tools/deploy.sh` 会在服务起来之后自动跑一次
- **换了 Supabase 项目之后必须重跑**（新库是空的）
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1] / "backend"
sys.path.insert(0, str(BACKEND_DIR))

from app.db.session import SessionLocal  # noqa: E402
from app.models.recipe import Recipe  # noqa: E402
from app.services import dish_seed  # noqa: E402

DATA_PATH = dish_seed.data_path(BACKEND_DIR)


def main() -> int:
    parser = argparse.ArgumentParser(description="把菜品库的做法灌进数据库")
    parser.add_argument("--check", action="store_true",
                        help="只报告差异，不写数据库")
    parser.add_argument("--reset", action="store_true",
                        help="先删掉所有内置菜谱再重新灌（不会碰用户自己的）")
    args = parser.parse_args()

    if not DATA_PATH.exists():
        print(f"找不到 {DATA_PATH}")
        print("先跑 tools/generate-dish-recipes.py 生成它。")
        return 1

    data = dish_seed.load_dish_data(DATA_PATH)
    if not data:
        print(f"{DATA_PATH.name} 是空的，先跑生成脚本。")
        return 1

    db = SessionLocal()
    try:
        existing = dish_seed.builtin_rows(db)
        print(f"JSON 里 {len(data)} 道菜，库里现有内置菜谱 {len(existing)} 道")

        missing = [d for d in sorted(data) if d not in existing]
        outdated = [
            d for d in sorted(data)
            if d in existing
            and (existing[d].steps or []) != list(data[d].get("steps") or [])
        ]
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

        created, updated = dish_seed.seed_dish_library(db, data, reset=args.reset)

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
