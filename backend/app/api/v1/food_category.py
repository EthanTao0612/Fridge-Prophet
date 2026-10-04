"""用户自定义折叠箱的接口。

路径是 `/food-categories` 而不是 `/categories`：项目里还有菜谱分类、
家庭角色这些「分类」概念，名字短了容易和它们混。
"""
from __future__ import annotations

from fastapi import APIRouter, HTTPException, status
from sqlalchemy import select

from app.api.deps import CurrentUser, DbSession
from app.models.food_category import (
    MAX_CATEGORIES_PER_USER,
    FoodCategory,
    FoodCategoryItem,
)
from app.models.inventory import FoodInventory
from app.schemas.food_category import (
    FoodCategoryCreate,
    FoodCategoryItemsIn,
    FoodCategoryOut,
    FoodCategoryUpdate,
)
from app.services.family_service import can_write, visible_user_ids

router = APIRouter(prefix="/food-categories", tags=["折叠箱"])


def _guard_write(db: DbSession, user_id: int) -> None:
    """只读成员不能改折叠箱。

    和冰箱是同一套权限：折叠箱是冰箱的另一种看法，
    能看不能改，不该因为换了个入口就绕过去。
    """
    if not can_write(db, user_id):
        raise HTTPException(
            status_code=403,
            detail="你在家庭里的身份是「只读」，不能修改折叠箱。需要的话让家庭主调整你的身份。",
        )


def _visible_category(db: DbSession, user_id: int, category_id: int) -> FoodCategory:
    """按 id 取一个折叠箱，取不到（或不在可见范围）就 404。

    可见范围是**家庭**而不是「只有自己建的」—— 和冰箱一致。
    """
    row = db.get(FoodCategory, category_id)
    if row is None or row.user_id not in visible_user_ids(db, user_id):
        raise HTTPException(status_code=404, detail="折叠箱不存在")
    return row


def _member_ids(db: DbSession, category_ids: list[int]) -> dict[int, list[int]]:
    """一次查出这些箱子里各有哪些食材 id。

    不用「逐个箱子查一次」是因为列表接口要返回所有箱子，
    箱子的数量虽然不多（上限 12），但没必要 N+1 次查询。
    """
    if not category_ids:
        return {}
    rows = db.execute(
        select(FoodCategoryItem.category_id, FoodCategoryItem.inventory_id)
        .where(FoodCategoryItem.category_id.in_(category_ids))
        .order_by(FoodCategoryItem.created_at)
    ).all()

    result: dict[int, list[int]] = {cid: [] for cid in category_ids}
    for category_id, inventory_id in rows:
        result[category_id].append(inventory_id)
    return result


def _to_out(row: FoodCategory, inventory_ids: list[int]) -> FoodCategoryOut:
    out = FoodCategoryOut.model_validate(row)
    out.inventory_ids = inventory_ids
    return out


def _check_inventory_visible(db: DbSession, user_id: int, inventory_ids: list[int]) -> list[int]:
    """过滤出真正可见的食材 id。

    为什么不直接报错：客户端可能拿着一个刚被家人删掉的食材 id 来加箱子，
    这时候整批失败太粗暴 —— 把还能加的加进去，静默跳过失效的更合理。
    """
    if not inventory_ids:
        return []
    rows = db.execute(
        select(FoodInventory.id).where(
            FoodInventory.id.in_(inventory_ids),
            FoodInventory.user_id.in_(visible_user_ids(db, user_id)),
        )
    ).scalars().all()
    return list(rows)


@router.get("", response_model=list[FoodCategoryOut], summary="列出所有折叠箱")
def list_categories(user: CurrentUser, db: DbSession) -> list[FoodCategoryOut]:
    rows = db.execute(
        select(FoodCategory)
        .where(FoodCategory.user_id.in_(visible_user_ids(db, user.id)))
        # 先按用户指定的顺序，再按创建时间 —— 用户没拖过顺序时就是「先建的在前」
        .order_by(FoodCategory.sort_order, FoodCategory.id)
    ).scalars().all()

    members = _member_ids(db, [r.id for r in rows])
    return [_to_out(r, members.get(r.id, [])) for r in rows]


@router.post("", response_model=FoodCategoryOut, status_code=status.HTTP_201_CREATED,
             summary="新建折叠箱")
def create_category(
    payload: FoodCategoryCreate, user: CurrentUser, db: DbSession
) -> FoodCategoryOut:
    _guard_write(db, user.id)

    existing = db.execute(
        select(FoodCategory).where(
            FoodCategory.user_id.in_(visible_user_ids(db, user.id))
        )
    ).scalars().all()

    if len(existing) >= MAX_CATEGORIES_PER_USER:
        raise HTTPException(
            status_code=400,
            detail=f"最多建 {MAX_CATEGORIES_PER_USER} 个折叠箱，先删掉不用的再建。",
        )

    # 同名不让建：界面上会出现两个一模一样的箱子，用户分不清点哪个
    if any(r.name == payload.name for r in existing):
        raise HTTPException(status_code=400, detail=f"已经有一个叫「{payload.name}」的折叠箱了")

    row = FoodCategory(user_id=user.id, name=payload.name)
    db.add(row)
    db.flush()  # 拿到 row.id

    valid_ids = _check_inventory_visible(db, user.id, payload.inventory_ids)
    for inventory_id in valid_ids:
        db.add(FoodCategoryItem(category_id=row.id, inventory_id=inventory_id))

    db.commit()
    db.refresh(row)
    return _to_out(row, valid_ids)


@router.patch("/{category_id}", response_model=FoodCategoryOut, summary="改名 / 调整顺序")
def update_category(
    category_id: int, payload: FoodCategoryUpdate, user: CurrentUser, db: DbSession
) -> FoodCategoryOut:
    _guard_write(db, user.id)
    row = _visible_category(db, user.id, category_id)

    if payload.name is not None and payload.name != row.name:
        clash = db.execute(
            select(FoodCategory).where(
                FoodCategory.user_id.in_(visible_user_ids(db, user.id)),
                FoodCategory.name == payload.name,
                FoodCategory.id != row.id,
            )
        ).scalars().first()
        if clash:
            raise HTTPException(
                status_code=400, detail=f"已经有一个叫「{payload.name}」的折叠箱了"
            )
        row.name = payload.name

    if payload.sort_order is not None:
        row.sort_order = payload.sort_order

    db.commit()
    db.refresh(row)
    members = _member_ids(db, [row.id])
    return _to_out(row, members.get(row.id, []))


# ⚠️ 这个 `response_model=None` 不能省，而且**不是**可有可无的声明。
#
# 本文件顶部有 `from __future__ import annotations`，它会把返回标注
# `-> None` 变成**字符串** "None"。FastAPI 解析后拿到的是 `NoneType`
# 这个**类型对象**，而 `bool(NoneType)` 是 True —— 于是它认为这个 204
# 接口要返回响应体，启动时直接抛：
#     AssertionError: Status code 204 must not have a response body
#
# 而 `inventory.py` 里**一模一样**的写法却一直没事，只因为那个文件
# 没有 future import（`-> None` 就是 None 对象，判为假）。
# 这种「看起来一样、实际不一样」的坑极难查 —— 报错在启动时，
# 指向的是 decorator 那一行，而不是真正的原因。
#
# 顺带记一下：`response_class=Response` **解决不了**这个问题，
# 因为断言检查的是 `response_model`，不是 `response_class`。
@router.delete("/{category_id}", status_code=status.HTTP_204_NO_CONTENT,
               response_model=None, summary="删除折叠箱")
def delete_category(category_id: int, user: CurrentUser, db: DbSession) -> None:
    """删箱子**不会删食材**，只是解除归组。

    这一点很重要：用户删的是「分组」这个视角，不是里面的东西。
    如果顺手把食材也删了，那是个灾难性的意外。
    """
    _guard_write(db, user.id)
    row = _visible_category(db, user.id, category_id)

    # 手动清关联，不依赖数据库的 ON DELETE CASCADE ——
    # SQLite 默认不开外键约束，CASCADE 在那里不生效（见 models/food_category.py）
    db.execute(
        FoodCategoryItem.__table__.delete().where(FoodCategoryItem.category_id == row.id)
    )
    db.delete(row)
    db.commit()


@router.post("/{category_id}/items", response_model=FoodCategoryOut,
             summary="把食材加入折叠箱（批量）")
def add_items(
    category_id: int, payload: FoodCategoryItemsIn, user: CurrentUser, db: DbSession
) -> FoodCategoryOut:
    _guard_write(db, user.id)
    row = _visible_category(db, user.id, category_id)

    valid_ids = _check_inventory_visible(db, user.id, payload.inventory_ids)

    # 已经在箱子里的不再插 —— 有 UniqueConstraint 兜底，
    # 但先查一次能避免「重复加入」直接抛数据库异常变成 500
    already = set(_member_ids(db, [row.id]).get(row.id, []))
    for inventory_id in valid_ids:
        if inventory_id not in already:
            db.add(FoodCategoryItem(category_id=row.id, inventory_id=inventory_id))

    db.commit()
    db.refresh(row)
    members = _member_ids(db, [row.id])
    return _to_out(row, members.get(row.id, []))


@router.delete("/{category_id}/items/{inventory_id}", response_model=FoodCategoryOut,
               summary="把食材移出折叠箱")
def remove_item(
    category_id: int, inventory_id: int, user: CurrentUser, db: DbSession
) -> FoodCategoryOut:
    """只解除归组，**食材本身留在冰箱里**。"""
    _guard_write(db, user.id)
    row = _visible_category(db, user.id, category_id)

    db.execute(
        FoodCategoryItem.__table__.delete().where(
            FoodCategoryItem.category_id == row.id,
            FoodCategoryItem.inventory_id == inventory_id,
        )
    )
    db.commit()
    db.refresh(row)
    members = _member_ids(db, [row.id])
    return _to_out(row, members.get(row.id, []))
