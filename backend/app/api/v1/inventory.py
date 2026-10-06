"""冰箱库存：增删改查、临期查询、识别结果确认入库。"""
from datetime import date, timedelta

from fastapi import APIRouter, HTTPException, Query, status

from app.api.deps import CurrentUser, DbSession
from app.models.food_category import FoodCategoryItem
from app.models.inventory import FoodInventory
from app.schemas.inventory import (
    ExpiringItem,
    InventoryCreate,
    InventoryOut,
    InventoryUpdate,
    ScanConfirmRequest,
)
from app.services.family_service import can_write, visible_user_ids
from app.services.ingredient_category import resolve_category
from app.services.ingredient_image_service import resolve_ingredient_image

router = APIRouter(prefix="/inventory", tags=["冰箱库存"])


def _visible(db, user_id: int) -> list[int]:
    """这个用户能看到哪些人的库存。

    冰箱是**共享实体**：家人各自用自己账号加的东西，都算「冰箱里的」。
    所以读操作一律走这里，写操作仍然记在添加者名下。
    """
    return visible_user_ids(db, user_id)


def _guard_write(db, user_id: int) -> None:
    """只读成员不能改冰箱。"""
    if not can_write(db, user_id):
        raise HTTPException(
            status_code=403,
            detail="你在家庭里的身份是「只读」，不能修改冰箱。需要的话让家庭主调整你的身份。",
        )

DEFAULT_SHELF_LIFE: dict[str, int] = {
    "鸡蛋": 30, "牛奶": 7, "豆腐": 3, "西红柿": 7, "青椒": 7, "西兰花": 5,
    "鸡胸肉": 30, "牛肉": 30, "猪肉": 30, "叶菜": 3, "蘑菇": 5,
}


def _shelf_life_for(name: str, explicit: int | None) -> int:
    if explicit:
        return explicit
    for key, days in DEFAULT_SHELF_LIFE.items():
        if key in name or name in key:
            return days
    return 7


def _resolve_dates(
    *,
    food_name: str,
    purchase_date: date | None,
    expiry_date: date | None,
    shelf_life_days: int | None,
    today: date,
) -> tuple[date, date]:
    """决定入库时用哪个购买日期和过期日期。

    优先级（越靠前越优先）：

    - 购买日期：用户显式指定 > 今天
    - 过期日期：用户显式指定 > 购买日期 + 用户给的保质期天数
      > 购买日期 + 按食材名查到的默认保质期

    用户手里的牛奶可能已经买了 3 天，所以「用户说的」永远压过「AI 猜的」。
    """
    purchase = purchase_date or today
    if expiry_date is not None:
        return purchase, expiry_date
    days = _shelf_life_for(food_name, shelf_life_days)
    return purchase, purchase + timedelta(days=days)


def _merge_expiry(old: date | None, new: date) -> date:
    """同名食材累加时如何合并过期日期：取**更早**的那个。

    库存表按「名称 + 储存位置」聚合成一行，两批不同保质期的同种食材
    本来就没法分开记。取更早的意味着 App 会**更早**提醒你，
    而漏提醒（把快过期的显示成新鲜）比多提醒严重得多。
    """
    return new if old is None else min(old, new)


def _to_out(item: FoodInventory, today: date) -> InventoryOut:
    """ORM 行 → 返回体。**所有返回食材的地方都必须走这里**。

    配图、剩余天数和**分类**都是**算出来的**，不在表里。
    放在这里是为了只有一处实现：如果哪个接口自己 `model_validate(row)`，
    那条路径上的食材就会没有图、没有剩余天数、分类还是原始值 ——
    而且这种缺失是静默的（界面显示占位色块，不报错）。

    ⚠️ `category` 这里**覆盖**了表里存的值，用的是 `resolve_category()`
    归一化之后的结果。为什么不让表里的值直接用：那是 AI 扫描时写进来的，
    说法不统一（「海鲜」「鱼类」「水产」都是同一类），而且手动添加的食材
    根本没有。归一化后客户端才能按固定分组渲染。
    """
    days_left = (item.expiry_date - today).days if item.expiry_date else None
    out = InventoryOut.model_validate(item)
    out.days_left = days_left
    out.category = resolve_category(item.food_name, item.category)
    # 把归一化后的分类一起传进去：没有具体图时用它选万能图
    out.image_url = resolve_ingredient_image(item.food_name, item.category)
    return out


def _visible_or_404(db: DbSession, user_id: int, item_id: int) -> FoodInventory:
    """按 id 取一件食材，取不到（或不在可见范围）就 404。

    范围是**家庭可见**而不是「只有自己加的」：冰箱是共享实体，
    家人买回来的东西也要能改能删 —— 就像家里那台真冰箱，
    谁都能把喝完的牛奶那行划掉。

    只读成员不能改，那条由调用方的 `_guard_write` 把关。
    """
    item = (
        db.query(FoodInventory)
        .filter(
            FoodInventory.id == item_id,
            FoodInventory.user_id.in_(visible_user_ids(db, user_id)),
        )
        .one_or_none()
    )
    if item is None:
        raise HTTPException(status_code=404, detail="库存中找不到该食材")
    return item


@router.get("", response_model=list[InventoryOut], summary="全部食材（可按位置/分类过滤）")
def list_inventory(
    user: CurrentUser,
    db: DbSession,
    storage_location: str | None = Query(default=None, description="冷藏/冷冻/常温"),
    category: str | None = None,
    keyword: str | None = None,
) -> list[InventoryOut]:
    q = db.query(FoodInventory).filter(FoodInventory.user_id.in_(_visible(db, user.id)))
    if storage_location:
        q = q.filter(FoodInventory.storage_location == storage_location)
    if category:
        q = q.filter(FoodInventory.category == category)
    if keyword:
        q = q.filter(FoodInventory.food_name.contains(keyword))

    items = q.order_by(FoodInventory.expiry_date.asc().nullslast()).all()
    today = date.today()
    for it in items:
        it.refresh_freshness(today)
    db.commit()
    return [_to_out(it, today) for it in items]


@router.get("/expiring", response_model=list[ExpiringItem], summary="即将过期（默认 3 天内）")
def list_expiring(
    user: CurrentUser,
    db: DbSession,
    within_days: int = Query(default=3, ge=0, le=30),
) -> list[ExpiringItem]:
    today = date.today()
    deadline = today + timedelta(days=within_days)
    items = (
        db.query(FoodInventory)
        .filter(
            FoodInventory.user_id.in_(_visible(db, user.id)),
            FoodInventory.expiry_date.isnot(None),
            FoodInventory.expiry_date <= deadline,
        )
        .order_by(FoodInventory.expiry_date.asc())
        .all()
    )
    out = []
    for it in items:
        it.refresh_freshness(today)
        out.append(
            ExpiringItem(
                id=it.id,
                food_name=it.food_name,
                quantity=it.quantity,
                unit=it.unit,
                expiry_date=it.expiry_date,
                days_left=(it.expiry_date - today).days if it.expiry_date else None,
                freshness=it.freshness,
            )
        )
    db.commit()
    return out


@router.get("/stats", summary="库存概览（首页顶部数字）")
def inventory_stats(user: CurrentUser, db: DbSession) -> dict:
    today = date.today()
    items = db.query(FoodInventory).filter(FoodInventory.user_id.in_(_visible(db, user.id))).all()
    for it in items:
        it.refresh_freshness(today)
    db.commit()

    by_location: dict[str, int] = {}
    for it in items:
        by_location[it.storage_location] = by_location.get(it.storage_location, 0) + 1

    return {
        "total_kinds": len(items),
        "by_location": by_location,
        "expiring_soon": sum(
            1 for it in items if it.expiry_date and (it.expiry_date - today).days <= 3
        ),
        "expired": sum(
            1 for it in items if it.expiry_date and (it.expiry_date - today).days < 0
        ),
        "freshness_breakdown": {
            level: sum(1 for it in items if it.freshness == level)
            for level in ("新鲜", "正常", "尽快食用", "已过期")
        },
    }


@router.get("/categories", summary="食材分类列表（含显示顺序）")
def list_categories(user: CurrentUser, db: DbSession) -> dict:
    """返回全部分类和它们的显示顺序。

    ## 为什么客户端要单独问一次

    分类顺序（蔬菜在前、「其他」垫底）只在
    `ingredient_category.CATEGORIES` 里定义了一次。
    如果客户端自己写一份，两边迟早不一致 ——
    改了后端忘了改客户端，用户看到的分组顺序就会很怪。

    这和「跨用户可见范围只能有一处定义」是同一个原则
    （见 MEMORY.md 铁律 9）。

    顺便返回每个分类下**当前有多少样食材**，客户端可以先画分组骨架
    再填内容，避免分组标题闪一下才出现。
    """
    from app.services.ingredient_category import CATEGORIES

    counts: dict[str, int] = {c: 0 for c in CATEGORIES}
    rows = db.query(FoodInventory).filter(
        FoodInventory.user_id.in_(_visible(db, user.id))
    ).all()
    for item in rows:
        c = resolve_category(item.food_name, item.category)
        counts[c] = counts.get(c, 0) + 1

    return {
        "categories": [
            {"name": c, "count": counts.get(c, 0)} for c in CATEGORIES
        ],
    }


@router.post("", response_model=InventoryOut, status_code=status.HTTP_201_CREATED,
             summary="手动添加食材")
def create_item(payload: InventoryCreate, user: CurrentUser, db: DbSession) -> InventoryOut:
    _guard_write(db, user.id)
    today = date.today()
    data = payload.model_dump()
    # shelf_life_days 只是「用来推算过期日期」的输入提示，不是数据库字段
    shelf_life_days = data.pop("shelf_life_days", None)

    purchase, expiry = _resolve_dates(
        food_name=data["food_name"],
        purchase_date=data.get("purchase_date"),
        expiry_date=data.get("expiry_date"),
        shelf_life_days=shelf_life_days,
        today=today,
    )
    data["purchase_date"] = purchase
    data["expiry_date"] = expiry

    item = FoodInventory(user_id=user.id, **data)
    item.refresh_freshness(today)
    db.add(item)
    db.commit()
    db.refresh(item)
    return _to_out(item, today)


@router.patch("/{item_id}", response_model=InventoryOut, summary="修改食材")
def update_item(
    item_id: int, payload: InventoryUpdate, user: CurrentUser, db: DbSession
) -> InventoryOut:
    _guard_write(db, user.id)
    item = _visible_or_404(db, user.id, item_id)
    for field, value in payload.model_dump(exclude_unset=True).items():
        setattr(item, field, value)
    if payload.freshness is None:
        item.refresh_freshness()
    db.commit()
    db.refresh(item)
    return _to_out(item, date.today())


@router.delete("/{item_id}", status_code=status.HTTP_204_NO_CONTENT, summary="删除食材")
def delete_item(item_id: int, user: CurrentUser, db: DbSession) -> None:
    _guard_write(db, user.id)
    item = _visible_or_404(db, user.id, item_id)

    # 先把折叠箱里的关联清掉，再删食材。
    #
    # ⚠️ 不能指望数据库的 ON DELETE CASCADE：开发用的 SQLite 默认
    # 不开启外键约束，CASCADE 在那里**根本不生效**，删完会留下孤儿关联行 ——
    # 折叠箱里显示「3 件」但只列得出 2 件，而且不报错。
    db.execute(
        FoodCategoryItem.__table__.delete().where(FoodCategoryItem.inventory_id == item.id)
    )
    db.delete(item)
    db.commit()


@router.post("/confirm", response_model=list[InventoryOut], status_code=status.HTTP_201_CREATED,
             summary="确认 AI 识别结果并写入冰箱（同名食材自动累加）")
def confirm_scan(
    payload: ScanConfirmRequest, user: CurrentUser, db: DbSession
) -> list[InventoryOut]:
    """策划书强调的「用户确认后才写入库存」这一步就在这里落地。"""
    _guard_write(db, user.id)
    if not payload.foods:
        raise HTTPException(status_code=400, detail="没有可加入的食材")

    today = date.today()
    saved: list[FoodInventory] = []

    for food in payload.foods:
        location = food.storage_location or payload.default_storage_location
        purchase, expiry = _resolve_dates(
            food_name=food.name,
            purchase_date=food.purchase_date,
            expiry_date=food.expiry_date,
            shelf_life_days=food.shelf_life_days,
            today=today,
        )
        # 同名合并要跨家庭找，不是只找自己加的那条 ——
        # 共享冰箱是同一个物理空间：妈妈已经放过鸡蛋，我再扫一次鸡蛋，
        # 应该是「那盒鸡蛋多了一板」，而不是冰箱里冒出两行鸡蛋。
        existing = (
            db.query(FoodInventory)
            .filter(
                FoodInventory.user_id.in_(_visible(db, user.id)),
                FoodInventory.food_name == food.name,
                FoodInventory.storage_location == location,
            )
            .first()
        )

        if existing:
            existing.quantity += food.quantity
            existing.confidence = food.confidence
            existing.source = "ai_scan"
            # 两批同种食材保质期不同，取更早的那个（见 _merge_expiry 注释）
            existing.expiry_date = _merge_expiry(existing.expiry_date, expiry)
            existing.refresh_freshness(today)
            saved.append(existing)
        else:
            item = FoodInventory(
                user_id=user.id,
                food_name=food.name,
                category=food.category,
                quantity=food.quantity,
                unit=food.unit,
                storage_location=location,
                purchase_date=purchase,
                expiry_date=expiry,
                confidence=food.confidence,
                source="ai_scan",
            )
            item.refresh_freshness(today)
            db.add(item)
            saved.append(item)

    db.commit()
    for it in saved:
        db.refresh(it)
    return [_to_out(it, today) for it in saved]


@router.delete("", status_code=status.HTTP_204_NO_CONTENT, summary="清空冰箱")
def clear_inventory(user: CurrentUser, db: DbSession) -> None:
    """清空**冰箱里看得见的全部**，包括家人加的。

    刻意不是「只清自己的」：按钮写着「清空冰箱」，如果点完还剩家人的食材，
    用户会以为功能坏了。范围要和用户在列表里看到的完全一致 ——
    看得见什么，清掉的就是什么。

    只读成员不能清。
    """
    _guard_write(db, user.id)
    db.query(FoodInventory).filter(
        FoodInventory.user_id.in_(_visible(db, user.id))
    ).delete(synchronize_session=False)
    db.commit()
