"""AI 4 接口：智能采购清单。"""
from datetime import date, timedelta

from fastapi import APIRouter, HTTPException, Query, status

from app.api.deps import CurrentUser, DbSession
from app.models.inventory import FoodInventory
from app.models.recipe import Recipe
from app.models.shopping import ShoppingItem, ShoppingList
from app.schemas.recipe import RecipeOut
from app.schemas.shopping import (
    ShoppingApplyRequest,
    ShoppingBuildRequest,
    ShoppingItemOut,
    ShoppingItemUpdate,
    ShoppingListOut,
)
from app.services.family_service import can_write, visible_user_ids
from app.services.recipe_service import readable_recipe_clause
from app.services.shopping_service import compute_missing, merge_into

router = APIRouter(prefix="/shopping", tags=["智能采购"])

DEFAULT_SHELF_LIFE_DAYS = 5


def _visible(db, user_id: int) -> list[int]:
    """能看到的采购清单范围 = 全家。买菜的可能不是我。"""
    return visible_user_ids(db, user_id)


def _guard_write(db, user_id: int) -> None:
    if not can_write(db, user_id):
        raise HTTPException(
            status_code=403,
            detail="你在家庭里的身份是「只读」，不能修改采购清单。需要的话让家庭主调整你的身份。",
        )


def _row_to_out(row: ShoppingList) -> ShoppingListOut:
    items = [
        ShoppingItemOut(
            id=i.id,
            food_name=i.food_name,
            quantity=i.quantity,
            unit=i.unit,
            category=i.category,
            estimated_price=i.estimated_price,
            checked=i.checked,
            applied_to_inventory=i.applied_to_inventory,
        )
        for i in row.items
    ]
    return ShoppingListOut(
        id=row.id,
        title=row.title,
        status=row.status,
        source_recipes=row.source_recipes,
        planned_date=row.planned_date,
        created_at=row.created_at,
        items=items,
        estimated_total=round(sum(i.estimated_price or 0 for i in items), 2),
    )


def _recipe_to_out(row: Recipe) -> RecipeOut:
    return RecipeOut(
        id=row.id,
        name=row.name,
        description=row.description,
        time_minutes=row.time_minutes,
        difficulty=row.difficulty,  # type: ignore[arg-type]
        ingredients=[
            {"name": i.name, "quantity": i.quantity, "unit": i.unit, "optional": i.optional}
            for i in row.ingredients
        ],
        steps=row.steps or [],
        nutrition=row.nutrition or {},
        tags=row.tags or [],
    )


@router.post("/build", response_model=ShoppingListOut, status_code=status.HTTP_201_CREATED,
             summary="根据菜谱自动计算缺少食材并生成采购清单")
def build_shopping_list(
    payload: ShoppingBuildRequest, user: CurrentUser, db: DbSession
) -> ShoppingListOut:
    _guard_write(db, user.id)
    # 库存仍然按「家人可见」算（和菜谱范围是两回事）：
    # 用自己那份会把家人已经买的东西又列一遍
    visible = _visible(db, user.id)

    # 菜谱范围用 `readable_recipe_clause`（含系统内置）：
    # 用户从菜品库点开一道菜、再点「把缺的加进采购清单」——
    # 如果这里只认「自己的菜谱」，那一步会报 400「还没有可用的菜谱」，
    # 而屏幕上明明摆着一份完整做法，看起来就像坏了。
    scope = readable_recipe_clause(db, user.id)

    if payload.recipe_ids:
        rows = (
            db.query(Recipe)
            .filter(Recipe.id.in_(payload.recipe_ids), scope)
            .all()
        )
    else:
        # 不指定就用最近生成的 days 道菜，对应策划书第十节的「批量采购」。
        # 内置菜谱用的是固定旧时间戳（见 seed-dish-recipes.py），
        # 所以这里挑出来的仍然是用户自己最近生成的菜。
        rows = (
            db.query(Recipe)
            .filter(scope)
            .order_by(Recipe.created_at.desc())
            .limit(payload.days * 3)
            .all()
        )

    if not rows:
        raise HTTPException(status_code=400, detail="还没有可用的菜谱，请先生成菜谱")

    recipes = [_recipe_to_out(r) for r in rows]
    # 库存也要算全家的 —— 用自己那份会把家人已经买的东西又列一遍
    inventory = (
        db.query(FoodInventory).filter(FoodInventory.user_id.in_(visible)).all()
    )
    missing = merge_into(compute_missing(recipes, inventory))

    if not missing:
        raise HTTPException(status_code=200, detail="现有库存已经够做这些菜了，无需采购")

    title = payload.title or (
        f"今日采购（{len(recipes)} 道菜）" if payload.days <= 1 else f"未来 {payload.days} 天采购"
    )
    row = ShoppingList(
        user_id=user.id,
        title=title,
        source_recipes="、".join(r.name for r in recipes)[:500],
        planned_date=payload.planned_date or date.today(),
    )
    row.items = [
        ShoppingItem(
            food_name=m.food_name,
            quantity=m.quantity,
            unit=m.unit,
            estimated_price=m.estimated_price,
        )
        for m in missing
    ]
    db.add(row)
    db.commit()
    db.refresh(row)
    return _row_to_out(row)


@router.get("", response_model=list[ShoppingListOut], summary="我的采购清单")
def list_shopping(
    user: CurrentUser,
    db: DbSession,
    status_filter: str | None = Query(default=None, alias="status"),
) -> list[ShoppingListOut]:
    q = db.query(ShoppingList).filter(ShoppingList.user_id.in_(_visible(db, user.id)))
    if status_filter:
        q = q.filter(ShoppingList.status == status_filter)
    rows = q.order_by(ShoppingList.created_at.desc()).all()
    return [_row_to_out(r) for r in rows]


@router.patch("/items/{item_id}", response_model=ShoppingItemOut, summary="修改采购项数量/勾选")
def update_item(
    item_id: int, payload: ShoppingItemUpdate, user: CurrentUser, db: DbSession
) -> ShoppingItemOut:
    _guard_write(db, user.id)
    item = (
        db.query(ShoppingItem)
        .join(ShoppingList, ShoppingList.id == ShoppingItem.shopping_list_id)
        .filter(
            ShoppingItem.id == item_id,
            ShoppingList.user_id.in_(_visible(db, user.id)),
        )
        .one_or_none()
    )
    if item is None:
        raise HTTPException(status_code=404, detail="采购项不存在")

    for field, value in payload.model_dump(exclude_unset=True).items():
        setattr(item, field, value)
    db.commit()
    db.refresh(item)
    return ShoppingItemOut.model_validate(item)


@router.delete("/items/{item_id}", status_code=status.HTTP_204_NO_CONTENT, summary="删除采购项")
def delete_item(item_id: int, user: CurrentUser, db: DbSession) -> None:
    _guard_write(db, user.id)
    item = (
        db.query(ShoppingItem)
        .join(ShoppingList, ShoppingList.id == ShoppingItem.shopping_list_id)
        .filter(
            ShoppingItem.id == item_id,
            ShoppingList.user_id.in_(_visible(db, user.id)),
        )
        .one_or_none()
    )
    if item is None:
        raise HTTPException(status_code=404, detail="采购项不存在")
    db.delete(item)
    db.commit()


@router.post("/{list_id}/apply", response_model=ShoppingListOut,
             summary="买完了一键写回冰箱库存")
def apply_to_inventory(
    list_id: int, payload: ShoppingApplyRequest, user: CurrentUser, db: DbSession
) -> ShoppingListOut:
    _guard_write(db, user.id)
    row = (
        db.query(ShoppingList)
        .filter(
            ShoppingList.id == list_id,
            ShoppingList.user_id.in_(_visible(db, user.id)),
        )
        .one_or_none()
    )
    if row is None:
        raise HTTPException(status_code=404, detail="采购清单不存在")

    today = date.today()
    targets = [i for i in row.items if i.checked or not payload.only_checked]
    if not targets:
        raise HTTPException(status_code=400, detail="请先勾选已买到的食材")

    for item in targets:
        if item.applied_to_inventory:
            continue
        # 同名合并跨家庭找 —— 共享冰箱是同一个物理空间，
        # 家人已经买过牛奶，我再买一盒应该是「多了一盒」，
        # 而不是冰箱里出现两行牛奶。
        existing = (
            db.query(FoodInventory)
            .filter(
                FoodInventory.user_id.in_(_visible(db, user.id)),
                FoodInventory.food_name == item.food_name,
                FoodInventory.storage_location == "冷藏",
            )
            .first()
        )
        if existing:
            existing.quantity += item.quantity
            existing.purchase_date = today
            existing.expiry_date = today + timedelta(days=DEFAULT_SHELF_LIFE_DAYS)
            existing.refresh_freshness(today)
        else:
            fresh = FoodInventory(
                user_id=user.id,
                food_name=item.food_name,
                quantity=item.quantity,
                unit=item.unit,
                storage_location="冷藏",
                purchase_date=today,
                expiry_date=today + timedelta(days=DEFAULT_SHELF_LIFE_DAYS),
                source="shopping",
            )
            fresh.refresh_freshness(today)
            db.add(fresh)
        item.applied_to_inventory = True
        item.checked = True

    if all(i.checked for i in row.items):
        row.status = "done"

    db.commit()
    db.refresh(row)
    return _row_to_out(row)


@router.delete("/{list_id}", status_code=status.HTTP_204_NO_CONTENT, summary="删除采购清单")
def delete_list(list_id: int, user: CurrentUser, db: DbSession) -> None:
    _guard_write(db, user.id)
    row = (
        db.query(ShoppingList)
        .filter(
            ShoppingList.id == list_id,
            ShoppingList.user_id.in_(_visible(db, user.id)),
        )
        .one_or_none()
    )
    if row is None:
        raise HTTPException(status_code=404, detail="采购清单不存在")
    db.delete(row)
    db.commit()
