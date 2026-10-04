"""用户自定义的食材折叠箱。

## 和 `FoodInventory.category` 的区别，别搞混

- `FoodInventory.category` 是**自动分类**：按食材属性算出来的
  （猪肉 → 肉类），来源是 `ingredient_category.resolve_category()`，
  **用户改不了，也不该改**。
- `FoodCategory` 是**用户自己建的折叠箱**：按用途分组，
  比如「火锅材料」「本周要吃完」「给娃做辅食的」。

两者**并存**：一件食材既在「肉类」里，也可以在「火锅材料」里。
用户问过这个问题，确认要并存 —— 因为「属性」和「用途」是两个维度，
强行二选一反而别扭。

## 为什么是多对多

用户确认过「一个食材能同时放进多个折叠箱」：
猪肉既属于「火锅材料」，也属于「本周要吃完」。
所以需要一张关联表，而不是在 `FoodInventory` 上加一个外键。
"""
from __future__ import annotations

from datetime import datetime, timezone

from sqlalchemy import DateTime, ForeignKey, Integer, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.db.session import Base

# 折叠箱名字长度。够放「给娃做辅食的」这种了。
NAME_MAX_LEN = 32

# 一个人最多建多少个折叠箱。
#
# 为什么不放开：折叠箱是手动的，多了没人维护，冰箱页会变成一长串空箱子。
# 12 个足够覆盖「按用途分组」的需求，也逼着用户删掉不用的。
MAX_CATEGORIES_PER_USER = 12


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


class FoodCategory(Base):
    """一个用户自定义的折叠箱。"""

    __tablename__ = "food_categories"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    # 创建者。可见范围是**家庭**（见 family_service.visible_user_ids）——
    # 冰箱是共享实体，家人也应该看到同一个折叠箱，而不是各看各的。
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)

    name: Mapped[str] = mapped_column(String(NAME_MAX_LEN))
    # 显示顺序。用户没指定时按创建时间排，新建的排后面。
    sort_order: Mapped[int] = mapped_column(Integer, default=0)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, onupdate=_utcnow
    )


class FoodCategoryItem(Base):
    """折叠箱 ↔ 食材 的关联。多对多，一行代表「某食材在某个箱子里」。

    ⚠️ **不要依赖数据库的 ON DELETE CASCADE。**
    开发用的 SQLite **默认不开启外键约束**（`PRAGMA foreign_keys` 默认关），
    所以 `ondelete="CASCADE"` 在 SQLite 上根本不生效 ——
    删掉食材后关联行会留下来变成孤儿，冰箱页就会出现「箱子里有 3 件，
    但只显示 2 件」这种对不上的情况。

    所以删除时**一律在代码里手动清关联**（见 api/v1/food_category.py 和
    api/v1/inventory.py 的删除逻辑），两种数据库上行为一致。
    """

    __tablename__ = "food_category_items"
    __table_args__ = (
        # 同一个食材不能重复加进同一个箱子。
        # 没有这个约束的话，用户连点两次「加入」就会在箱子里看到两条一样的。
        UniqueConstraint("category_id", "inventory_id", name="uq_category_inventory"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    category_id: Mapped[int] = mapped_column(
        ForeignKey("food_categories.id", ondelete="CASCADE"), index=True
    )
    inventory_id: Mapped[int] = mapped_column(
        ForeignKey("food_inventory.id", ondelete="CASCADE"), index=True
    )

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)
