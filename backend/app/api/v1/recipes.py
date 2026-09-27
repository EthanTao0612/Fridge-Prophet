"""AI 2 接口：菜谱推荐、详情、用户行为反馈。"""
from datetime import date, datetime, timezone

from fastapi import APIRouter, HTTPException, Query, status
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.api.deps import CurrentUser, DbSession, get_or_create_preference
from app.models.inventory import FoodInventory
from app.models.recipe import MealHistory, Recipe, RecipeFeedback, RecipeIngredient
from app.models.user import HealthPreference
from app.schemas.recipe import (
    CookDeducted,
    CookPlan,
    CookRequest,
    CookResult,
    MealActionRequest,
    NutritionEstimate,
    RecipeGenerateRequest,
    RecipeGenerateResponse,
    RecipeIngredientOut,
    RecipeOut,
)
from app.services.family_service import can_write, visible_user_ids
from app.services.food_image_service import resolve_image_url
from app.services.recipe_service import (
    build_cook_plan,
    generate_recipes,
    recompute_availability,
)

router = APIRouter(prefix="/recipes", tags=["AI 菜谱"])


def _load_inventory(db: Session, user_id: int) -> list[FoodInventory]:
    """取「这个用户能看到的库存」—— 也就是全家的冰箱。

    生成菜谱、算缺料、做菜扣减都基于它。如果这里只看自己那份，
    就会出现「冰箱页显示有鸡蛋、菜谱却说缺鸡蛋」。
    """
    inventory = (
        db.query(FoodInventory)
        .filter(FoodInventory.user_id.in_(visible_user_ids(db, user_id)))
        .all()
    )
    today = date.today()
    for it in inventory:
        it.refresh_freshness(today)
    db.commit()
    return inventory


def _load_context(db: Session, user_id: int):
    inventory = _load_inventory(db, user_id)
    today = date.today()

    expiring = [
        it.food_name
        for it in inventory
        if it.expiry_date and (it.expiry_date - today).days <= 3
    ]
    pref = get_or_create_preference(db, user_id)
    health = (
        db.query(HealthPreference).filter(HealthPreference.user_id == user_id).one_or_none()
    )
    return inventory, expiring, pref, health


def _with_availability(out: RecipeOut, inventory: list[FoodInventory]) -> RecipeOut:
    """按**当前**库存重算这道菜缺什么，并就地更新 available / ready。

    菜谱详情和菜谱列表都要用，所以抽出来只写一遍 ——
    判断「食材有没有」的规则只能有一处实现（见 MEMORY.md 铁律 7）。
    """
    out.missing_ingredients = recompute_availability(out.ingredients, inventory)
    out.ready = bool(out.ingredients) and not out.missing_ingredients
    return out


def _persist(db: Session, user_id: int, recipe: RecipeOut) -> Recipe:
    row = Recipe(
        user_id=user_id,
        name=recipe.name,
        description=recipe.description,
        time_minutes=recipe.time_minutes,
        difficulty=recipe.difficulty,
        steps=recipe.steps,
        nutrition=recipe.nutrition.model_dump(),
        tags=recipe.tags,
        source="ai",
        image_url=recipe.image_url,
    )
    row.ingredients = [
        RecipeIngredient(
            name=i.name, quantity=i.quantity, unit=i.unit, optional=i.optional
        )
        for i in recipe.ingredients
    ]
    db.add(row)
    db.flush()
    return row


def _sync_row(row: Recipe, recipe: RecipeOut) -> None:
    """把新生成的内容覆盖进已有行（复用同一行，不新建）。"""
    row.description = recipe.description
    row.time_minutes = recipe.time_minutes
    row.difficulty = recipe.difficulty
    row.steps = recipe.steps
    row.nutrition = recipe.nutrition.model_dump()
    row.tags = recipe.tags
    row.image_url = recipe.image_url
    # 整个列表替换；relationship 带 delete-orphan，旧食材行会被自动清掉
    row.ingredients = [
        RecipeIngredient(name=i.name, quantity=i.quantity, unit=i.unit, optional=i.optional)
        for i in recipe.ingredients
    ]


def _persist_or_update(db: Session, user_id: int, recipe: RecipeOut) -> Recipe:
    """按**菜名**落库：已有同名菜谱就更新，没有才新建。

    为什么必须查重：用户每点一次「生成新菜谱」都会打一次 /generate，
    而 MOCK 模式（没配 API Key 时）和低温度下的模型都很容易反复给同样的几道菜。
    以前这里无条件 INSERT，结果「番茄鸡蛋豆腐」在库里攒了 11 条，
    菜谱页看着就是同一道菜刷屏。

    去重键用 name 而不是「食材组合」：同一道菜重做时用量会变，
    但用户心里的「番茄鸡蛋豆腐」始终是一道菜。
    """
    # 查重范围是**全家可见的菜谱**，不是只有自己生成的。
    # 否则妈妈生成过「番茄鸡蛋豆腐」、我再生成一次，列表里就会出现两张一样的卡片 ——
    # 那正是之前修过的「同一道菜重复出现」，只不过换成了跨账号的版本。
    existing = (
        db.query(Recipe)
        .filter(
            Recipe.user_id.in_(visible_user_ids(db, user_id)),
            Recipe.name == recipe.name,
        )
        .order_by(Recipe.id.desc())
        .first()
    )
    if existing is None:
        return _persist(db, user_id, recipe)

    _sync_row(existing, recipe)
    # created_at 兼作「最近一次生成时间」：刷新它，刚生成的菜才会回到列表最前
    existing.created_at = datetime.now(timezone.utc)
    db.flush()
    return existing


def _ingredients_of(row: Recipe) -> list[RecipeIngredientOut]:
    """从菜谱行取出配料表。详情、列表、做菜预览都用它，别各写一份。"""
    return [
        RecipeIngredientOut(
            name=i.name, quantity=i.quantity, unit=i.unit, optional=i.optional
        )
        for i in row.ingredients
    ]


def _load_visible_recipe(db: Session, user_id: int, recipe_id: int) -> Recipe:
    """取一道**自己或家人**的菜谱，取不到就 404。

    菜谱在家庭里是共享的：妈妈生成的菜，我也要能打开、能照着做。
    """
    row = (
        db.query(Recipe)
        .filter(
            Recipe.id == recipe_id,
            Recipe.user_id.in_(visible_user_ids(db, user_id)),
        )
        .one_or_none()
    )
    if row is None:
        raise HTTPException(status_code=404, detail="菜谱不存在")
    return row


def _guard_write(db: Session, user_id: int) -> None:
    """只读成员不能动菜谱。"""
    if not can_write(db, user_id):
        raise HTTPException(
            status_code=403,
            detail="你在家庭里的身份是「只读」，不能修改菜谱。需要的话让家庭主调整你的身份。",
        )


def _row_to_out(row: Recipe) -> RecipeOut:
    try:
        nutrition = NutritionEstimate(**(row.nutrition or {}))
    except Exception:  # noqa: BLE001
        nutrition = NutritionEstimate()

    ingredient_outs = _ingredients_of(row)

    # 库里没存配图时现场算一个。
    # 这样有两个好处：① 老菜谱不用跑数据迁移就能补上配图；
    # ② 以后往图片库里加图，所有老菜谱的配图会一起变好。
    image_url = row.image_url or resolve_image_url(
        row.name, [i.name for i in ingredient_outs]
    )

    return RecipeOut(
        id=row.id,
        name=row.name,
        description=row.description,
        time_minutes=row.time_minutes,
        difficulty=row.difficulty,  # type: ignore[arg-type]
        ingredients=ingredient_outs,
        missing_ingredients=[],
        steps=row.steps or [],
        nutrition=nutrition,
        tags=row.tags or [],
        image_url=image_url,
        created_at=row.created_at,
    )


@router.post("/generate", response_model=RecipeGenerateResponse,
             summary="根据库存 + 用户画像生成菜谱（核心接口）")
def generate(payload: RecipeGenerateRequest, user: CurrentUser, db: DbSession):
    inventory, expiring, pref, health = _load_context(db, user.id)

    recipes, model_name = generate_recipes(
        inventory=inventory,
        pref=pref,
        health=health,
        count=payload.count,
        max_time=payload.max_time_minutes,
        expiring=expiring if payload.prioritize_expiring else [],
        extra_notes=payload.extra_notes,
    )

    if payload.save:
        # 落库 = 写共享数据，只读成员不行。
        # 放在 if 里面而不是函数开头：save=False 的纯预览不该被拦。
        _guard_write(db, user.id)
        for r in recipes:
            # 走 upsert：同名菜谱只保留一行，反复生成不会把列表撑爆
            row = _persist_or_update(db, user.id, r)
            r.id = row.id
        db.commit()

    return RecipeGenerateResponse(
        recipes=recipes,
        model=model_name,
        used_ingredients=[i.food_name for i in inventory],
        expiring_used=sorted({n for r in recipes for n in r.uses_expiring}),
    )


@router.get("", response_model=list[RecipeOut], summary="我生成过的菜谱")
def list_recipes(
    user: CurrentUser,
    db: DbSession,
    limit: int = Query(default=20, ge=1, le=100),
) -> list[RecipeOut]:
    """菜谱列表。

    注意：这里**也要**用当前库存重算缺料，不能直接返回空列表。
    以前这里偷懒返回 missing_ingredients=[]，导致两个问题：
      1. 列表里每道菜都显示「食材齐全」，用户买完菜回来看不出区别；
      2. 客户端按「缺料空不空」做的筛选形同虚设。
    顺带把 ready（食材已备齐）算出来，客户端据此把做完的菜弱化置底。
    """
    # 按菜名只取最新的一条。修复前反复点「生成新菜谱」攒下的重复行
    # 可能还留在库里（见 _persist_or_update 的说明），这里兜一层，
    # 保证界面上永远不会出现重复卡片。
    # 范围是全家的菜谱；按菜名分组去重也是跨账号的 ——
    # 妈妈和我各生成过一次同一道菜，列表里也只该出现一张卡片。
    visible = visible_user_ids(db, user.id)
    latest_ids = (
        db.query(func.max(Recipe.id))
        .filter(Recipe.user_id.in_(visible))
        .group_by(Recipe.name)
        .scalar_subquery()
    )
    rows = (
        db.query(Recipe)
        .filter(Recipe.user_id.in_(visible), Recipe.id.in_(latest_ids))
        .order_by(Recipe.created_at.desc())
        .limit(limit)
        .all()
    )
    if not rows:
        return []

    inventory = _load_inventory(db, user.id)
    return [_with_availability(_row_to_out(r), inventory) for r in rows]


@router.get("/{recipe_id}", response_model=RecipeOut, summary="菜谱详情")
def get_recipe(recipe_id: int, user: CurrentUser, db: DbSession) -> RecipeOut:
    row = _load_visible_recipe(db, user.id, recipe_id)

    # 详情页要用当前库存重算缺料，避免用户已经买回来了还显示缺
    inventory = _load_inventory(db, user.id)
    out = _with_availability(_row_to_out(row), inventory)

    db.add(MealHistory(user_id=user.id, recipe_id=row.id, action="view"))
    db.commit()
    return out


@router.get("/{recipe_id}/cook-plan", response_model=CookPlan,
            summary="做这道菜会扣掉冰箱里什么（做菜前的预览）")
def get_cook_plan(recipe_id: int, user: CurrentUser, db: DbSession) -> CookPlan:
    """做菜前的预览。

    **为什么扣库存要先预览**：扣减不可逆 —— 扣完那行食材就没了。
    不能点一下按钮就闷头扣，得先让用户看见「会用掉什么、各多少」，
    能改数字，再确认。取消就什么都不动。
    """
    row = _load_visible_recipe(db, user.id, recipe_id)
    inventory = _load_inventory(db, user.id)
    return build_cook_plan(
        recipe_id=row.id,
        recipe_name=row.name,
        ingredients=_ingredients_of(row),
        inventory=inventory,
    )


@router.post("/{recipe_id}/cook", response_model=CookResult,
             summary="做这道菜：按实际用量扣减冰箱库存")
def cook_recipe(
    recipe_id: int,
    payload: CookRequest,
    user: CurrentUser,
    db: DbSession,
) -> CookResult:
    """扣库存 + 记一笔「做过」。

    `deductions` 留空 = 按系统估算扣；传了 = 按用户改过的量扣。
    传空列表 `[]` 是有意义的 —— 表示「我做了这道菜，但不想改库存」，
    这时候只记行为、不动库存。
    """
    row = _load_visible_recipe(db, user.id, recipe_id)
    inventory = _load_inventory(db, user.id)
    plan = build_cook_plan(
        recipe_id=row.id,
        recipe_name=row.name,
        ingredients=_ingredients_of(row),
        inventory=inventory,
    )

    # 要扣哪些、各扣多少
    if payload.deductions is None:
        # 按系统估算：单位对得上的才扣（suggested_deduct 为 None 的一律跳过）
        wanted: dict[int, float] = {
            it.stock_item_id: it.suggested_deduct
            for it in plan.items
            if it.stock_item_id is not None and it.suggested_deduct
        }
    else:
        wanted = {d.item_id: d.quantity for d in payload.deductions}

    # 只认自己冰箱里的东西 —— 传别人的 item_id 在这里自然落空
    owned_by_id = {it.id: it for it in inventory}

    deducted: list[CookDeducted] = []
    for item_id, want in wanted.items():
        item = owned_by_id.get(item_id)
        if item is None:
            continue
        have = float(item.quantity or 0)
        if want <= 0 or have <= 0:
            continue

        take = round(min(float(want), have), 2)
        left = round(have - take, 2)
        emptied = left <= 0

        if emptied:
            # 用完了就把这行删掉。留一条数量为 0 的记录只会在冰箱页当噪声，
            # 「库存」表达的就是「现在还有什么」。
            db.delete(item)
        else:
            item.quantity = left

        deducted.append(
            CookDeducted(
                name=item.food_name,
                quantity=take,
                unit=item.unit,
                remaining=0 if emptied else left,
                emptied=emptied,
            )
        )

    # 用户明确传空数组 = 「我做了这道菜，但别动库存」。
    # 这和「想扣却扣不了（缺料 / 单位对不上）」是两回事，后面的文案要分开写。
    user_declined = payload.deductions is not None and not payload.deductions

    # 没扣成的：缺料的、单位对不上又没手填的、用户填 0 的。
    # 用户主动放弃时不算 skipped —— 否则前端会弹一句「有 4 样没扣」
    # 去解释一件用户自己决定的事，看着像出错了。
    if user_declined:
        skipped = []
    else:
        taken_names = {d.name for d in deducted}
        skipped = [it.name for it in plan.items if it.name not in taken_names]

    if payload.record_history:
        # 记一笔「做过」，喂给口味画像（策划书第七节的「AI 会学习」）
        db.add(
            MealHistory(
                user_id=user.id,
                recipe_id=row.id,
                action="cook",
                cooked_at=datetime.now(timezone.utc),
            )
        )

    db.commit()

    if deducted:
        note = f"已从冰箱扣掉 {len(deducted)} 样食材。"
        if skipped:
            note += f"另有 {len(skipped)} 样没动（缺料或单位对不上）。"
    elif user_declined:
        note = "已记下「做过这道菜」，按你的选择没有改动库存。"
    elif skipped:
        note = "这次没有改动库存 —— 需要的食材冰箱里都没有，或者单位对不上。"
    else:
        note = "已记下「做过这道菜」，库存未改动。"

    return CookResult(
        recipe_id=row.id,
        recipe_name=row.name,
        deducted=deducted,
        skipped=skipped,
        note=note,
    )


@router.delete("/{recipe_id}", status_code=status.HTTP_204_NO_CONTENT, summary="删除菜谱")
def delete_recipe(recipe_id: int, user: CurrentUser, db: DbSession) -> None:
    """删一道菜谱。**家人生成的也能删** —— 菜谱列表是全家共享的，
    看得见却删不掉会让人以为按钮坏了。只读成员除外。"""
    _guard_write(db, user.id)
    row = _load_visible_recipe(db, user.id, recipe_id)
    db.delete(row)
    db.commit()


@router.post("/feedback", summary="上报用户行为（收藏/做过/跳过/评分），用于修正画像")
def submit_feedback(payload: MealActionRequest, user: CurrentUser, db: DbSession) -> dict:
    # 家人生成的菜也要能反馈 —— 收藏 / 做过 / 跳过记的是**我自己的**口味，
    # 属于个人数据，所以只读成员也能用（不拦 _guard_write）。
    row = _load_visible_recipe(db, user.id, payload.recipe_id)

    db.add(
        MealHistory(
            user_id=user.id,
            recipe_id=row.id,
            action=payload.action,
            rating=payload.rating,
            cooked_at=datetime.now(timezone.utc) if payload.action == "cook" else None,
        )
    )

    # 连续跳过 3 次 → 记入负反馈，后续生成时主动避开（策划书第七节）
    if payload.action == "skip":
        skips = (
            db.query(MealHistory)
            .filter(
                MealHistory.user_id == user.id,
                MealHistory.recipe_id == row.id,
                MealHistory.action == "skip",
            )
            .count()
        )
        if skips >= 2:  # 本次还没提交，加上这次是 3 次
            fb = (
                db.query(RecipeFeedback)
                .filter(
                    RecipeFeedback.user_id == user.id,
                    RecipeFeedback.recipe_name == row.name,
                )
                .one_or_none()
            )
            if fb is None:
                db.add(RecipeFeedback(user_id=user.id, recipe_name=row.name, disliked=True))

    db.commit()
    return {"ok": True, "action": payload.action}


@router.get("/insights/preference", summary="从历史行为推导出的口味偏好（答辩演示用）")
def preference_insights(user: CurrentUser, db: DbSession) -> dict:
    rows = (
        db.query(MealHistory, Recipe.name)
        .join(Recipe, Recipe.id == MealHistory.recipe_id)
        .filter(MealHistory.user_id == user.id)
        .all()
    )
    counter: dict[str, dict[str, int]] = {}
    for hist, name in rows:
        entry = counter.setdefault(name, {"view": 0, "favorite": 0, "cook": 0, "skip": 0})
        entry[hist.action] = entry.get(hist.action, 0) + 1

    liked = sorted(
        (n for n, c in counter.items() if c.get("cook", 0) + c.get("favorite", 0) > 0),
        key=lambda n: -(counter[n].get("cook", 0) + counter[n].get("favorite", 0)),
    )[:5]
    skipped = sorted(
        (n for n, c in counter.items() if c.get("skip", 0) >= 2),
        key=lambda n: -counter[n]["skip"],
    )[:5]

    return {
        "total_interactions": len(rows),
        "frequently_cooked": liked,
        "frequently_skipped": skipped,
        "note": "系统根据你的点击、收藏、烹饪与跳过行为逐步调整推荐，无需重复填写偏好。",
    }
