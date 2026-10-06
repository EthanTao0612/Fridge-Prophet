"""把菜品库的做法灌进数据库 —— **可复用的一份实现**。

## 为什么抽成模块而不是只放脚本里

`tools/seed-dish-recipes.py` 是给人用的命令行入口；
但**测试也需要同一份数据**：`/recipes/generate` 现在是「查库挑菜」
（不调 AI），测试库是空的话它永远返回空，
一堆断言会挂 —— 而失败信息看起来像「生成接口坏了」，排查方向全错。

在测试里手抄一遍灌库逻辑是不行的：两份实现迟早会不一致
（改了 CSV 忘了改测试），那时候测试测的就不是线上跑的东西了。

所以：**逻辑在这里，脚本和测试都调它。**
"""
from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

from sqlalchemy.orm import Session

from app.models.recipe import Recipe, RecipeIngredient
from app.services.food_image_service import resolve_image_url
from app.services.recipe_service import clean_unit

# 内置菜谱的创建时间基准。挑一个明显早于任何真实使用的日期，
# 保证它们永远排在用户自己的菜谱之后。
#
# 为什么重要：列表按 `created_at` 倒序。如果内置菜谱拿「现在」当创建时间，
# 192 条会把用户自己刚生成的菜全挤到后面去，用户会以为自己的菜没了。
BUILTIN_EPOCH = datetime(2026, 1, 1, 0, 0, 0, tzinfo=timezone.utc)


def data_path(backend_dir: Path) -> Path:
    return backend_dir / "data" / "dish-recipes.json"


def load_dish_data(path: Path) -> dict:
    """读 `dish-recipes.json`。文件不存在或坏了都会抛，由调用方决定怎么处理。"""
    return json.loads(path.read_text(encoding="utf-8"))


def builtin_rows(db: Session) -> dict[str, Recipe]:
    """库里现有的内置菜谱，按菜名索引。"""
    return {r.name: r for r in db.query(Recipe).filter(Recipe.user_id.is_(None)).all()}


def apply_payload(row: Recipe, dish: str, payload: dict, index: int) -> None:
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


def seed_dish_library(db: Session, data: dict, *, reset: bool = False) -> tuple[int, int]:
    """把 data 里的菜灌进库。返回 (新建数, 更新数)。

    **幂等**：按菜名 upsert，而且只动 `user_id IS NULL` 的行，
    绝不碰用户自己的菜谱。
    """
    if reset:
        db.query(Recipe).filter(Recipe.user_id.is_(None)).delete(
            synchronize_session=False
        )
        db.commit()

    existing = builtin_rows(db)
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
        apply_payload(row, dish, payload, index)

    db.commit()
    return created, updated
