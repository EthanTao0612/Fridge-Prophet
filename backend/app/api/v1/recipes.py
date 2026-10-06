"""AI 2 接口：菜谱推荐、详情、用户行为反馈。"""
from datetime import date, datetime, timezone

from fastapi import APIRouter, HTTPException, Query, status
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.api.deps import CurrentUser, DbSession, guard_ai_quota, get_or_create_preference
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
    RecipeMaterializeRequest,
)
from app.services.family_service import can_write, visible_user_ids
from app.services.food_image_service import resolve_image_url
from app.services.recipe_service import (
    build_cook_plan,
    clean_unit,
    editable_recipe_clause,
    generate_recipes,
    is_builtin,
    readable_recipe_clause,
    recompute_availability,
    recommend_dishes,
)

router = APIRouter(prefix="/recipes", tags=["AI 菜谱"])


def _load_inventory(db: Session, user_id: int) -> list[FoodInventory]:
    """取「这个用户能看到的库存」—— 也就是全家的冰箱。

    生成菜谱、算缺料、做菜扣减都基于它。如果这里只看自己那份，
    就会出现「冰箱页显示有鸡蛋、菜谱却说缺鸡蛋」。

    ## ⚠️⚠️ 这个函数有个会拖垮列表接口的副作用，改之前先读完

    `db.commit()` 会让 session 里**所有已加载的对象过期**，
    之后每访问它们的一个字段都会触发一次 SELECT。

    生产库在 Supabase（孟买），**单次往返实测 169ms**。
    而菜谱列表要一次读 200 道菜 —— 如果菜谱是在这个函数**之前**查出来的，
    200 道菜 × 169ms = **74 秒**（实测就是这个数）。

    所以两件事必须一起做：
      ① 没变化就**不 commit**（绝大多数请求都属此类，见下）
      ② 真 commit 了，**用一次查询**把这批食材读回来 ——
         而不是让调用方一个个懒加载
      ③ 调用方要**先调这个函数、再查菜谱**（见 list_recipes）
    """
    def _query() -> list[FoodInventory]:
        return (
            db.query(FoodInventory)
            .filter(FoodInventory.user_id.in_(visible_user_ids(db, user_id)))
            .all()
        )

    inventory = _query()
    today = date.today()

    # `refresh_freshness` 返回「有没有真的变化」。用普通循环而不是 any() ——
    # `any()` 会短路，后面的食材就不刷了。
    changed = False
    for it in inventory:
        if it.refresh_freshness(today):
            changed = True

    if not changed:
        # 这是**绝大多数请求**的路径：新鲜度没变，不 commit，对象不过期，
        # 调用方拿到的是一个干净、不需要再查库的列表
        return inventory

    db.commit()
    # 提交之后这批对象已经过期了，一次查询读回来（不要逐个 refresh，
    # 那是 N 次往返）
    return _query()


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
    # ⚠️ unit 必须过 clean_unit：模型偶尔在单位里塞注释
    #（实测「汤匙（即oyster-sauce）」17 个字），而 unit 列是 VARCHAR(16)。
    # SQLite 不校验长度所以本地测不出来，生产库会 StringDataRightTruncation → 500。
    row.ingredients = [
        RecipeIngredient(
            name=i.name,
            quantity=i.quantity,
            unit=clean_unit(i.unit),
            optional=i.optional,
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
    # 同上：unit 要清洗（这里是「更新已有行」那条路，也会写库）
    row.ingredients = [
        RecipeIngredient(
            name=i.name,
            quantity=i.quantity,
            unit=clean_unit(i.unit),
            optional=i.optional,
        )
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
    # 查重范围是**全家可见的、用户自己的**菜谱，**不含系统内置**。
    #
    # 为什么不含内置：内置菜谱（user_id 为 NULL）是所有用户共用的。
    # 如果查重时命中它，`_sync_row` 会把那条**全局记录**改写成
    # 「这个用户刚生成的样子」（source 从 builtin 变成 ai，食材用量也跟着变），
    # 等于一个用户的操作污染了所有人的菜品库。
    #
    # 所以：AI 生成出和内置菜谱同名的菜时，**新建一条用户自己的**。
    # 界面上会看到两道同名的菜 —— 那是可接受的（一道是标准做法，
    # 一道是他自己生成的版本），而且 `/generate` 的提示词里已经把
    # 内置菜名列为「避开」，正常不会撞。
    existing = (
        db.query(Recipe)
        .filter(
            editable_recipe_clause(db, user_id),
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


def _load_readable_recipe(db: Session, user_id: int, recipe_id: int) -> Recipe:
    """取一道**能看的**菜谱：系统内置 + 自己/家人。取不到就 404。

    家庭共享：妈妈生成的菜，我也要能打开、能照着做。
    系统内置：菜品库那 192 道，所有用户都能打开。
    """
    row = (
        db.query(Recipe)
        .filter(Recipe.id == recipe_id, readable_recipe_clause(db, user_id))
        .one_or_none()
    )
    if row is None:
        raise HTTPException(status_code=404, detail="菜谱不存在")
    return row


def _load_editable_recipe(db: Session, user_id: int, recipe_id: int) -> Recipe:
    """取一道**能改的**菜谱：自己/家人。系统内置的给 403，不是 404。

    ⚠️ 这里必须给 403 而不是 404。
    内置菜谱用户**看得见**（详情页能打开），
    所以对「删除」说「不存在」是自相矛盾的，用户会以为界面出错了。
    说清楚「这是系统内置的，不能删」才是对的。
    """
    row = (
        db.query(Recipe)
        .filter(Recipe.id == recipe_id, editable_recipe_clause(db, user_id))
        .one_or_none()
    )
    if row is None:
        # 先确认它是不是「存在但不可改」（内置），能区分就说得更明白
        exists = (
            db.query(Recipe.id)
            .filter(Recipe.id == recipe_id, readable_recipe_clause(db, user_id))
            .first()
        )
        if exists:
            raise HTTPException(
                status_code=403,
                detail="这是系统内置的菜谱，不能删除。你可以把它收藏起来，或者自己照着做。",
            )
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
        is_builtin=is_builtin(row),
        created_at=row.created_at,
    )


@router.post("/generate", response_model=RecipeGenerateResponse,
             summary="根据库存 + 用户画像生成菜谱（核心接口）")
def generate(payload: RecipeGenerateRequest, user: CurrentUser, db: DbSession):
    # ⚠️ 配额检查必须在最前面 —— 这个接口每调一次就是一次真实的百炼计费。
    #
    # 一次调用生成 count 道菜，但**只扣 1 次配额**：
    # 花的是「一次 AI 调用」的钱，按次数扣才和账单对得上。
    guard_ai_quota(db, user.id)

    inventory, expiring, pref, health = _load_context(db, user.id)

    # 把「用户已经有的菜名」带进提示词，让模型避开重复。
    #
    # 为什么必须做：模型每次都会把最适合这份库存的那几道再推荐一遍，
    # 而落库是按菜名 upsert 的 —— 重复的名字只覆盖旧行、不新增。
    # 实测同一个冰箱连点三次「生成新菜谱」，前两道菜一模一样
    #（番茄炒蛋 + 青椒鸡胸肉），列表卡在 3 道不动，
    # 用户感受就是「能生成的菜非常有限」。
    #
    # ⚠️ 范围要**同时**包含「系统内置」和「自己最近生成的」，两段分开取：
    #
    #   - 内置那 192 道必须全带上。用户点开就能看到它们，
    #     如果 AI 又编一道「清炒西兰花」，列表里会出现两道同名菜 ——
    #     一道是内置标准做法、一道是 AI 现编的，很难分辨。
    #   - 自己最近 30 道。菜谱攒多了以后把上百个名字全塞进提示词会拖慢生成，
    #     而「别重复最近这些」已经足够解决问题。
    #
    # 不能简单地「取最近 30 个 readable」：那样内置菜谱一多，
    # 用户自己的菜名就会被挤出前 30，反而失去去重作用。
    builtin_names = [
        name
        for (name,) in db.query(Recipe.name).filter(Recipe.user_id.is_(None)).all()
    ]
    own_names = [
        name
        for (name,) in (
            db.query(Recipe.name)
            .filter(editable_recipe_clause(db, user.id))
            .order_by(Recipe.created_at.desc())
            .limit(30)
            .all()
        )
    ]
    exclude = own_names + builtin_names

    recipes, model_name = generate_recipes(
        inventory=inventory,
        pref=pref,
        health=health,
        count=payload.count,
        max_time=payload.max_time_minutes,
        expiring=expiring if payload.prioritize_expiring else [],
        extra_notes=payload.extra_notes,
        exclude=exclude,
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
    limit: int = Query(default=20, ge=1, le=200),
) -> list[RecipeOut]:
    """菜谱列表：**自己/家人生成的**。

    ## 为什么不把菜品库那 192 道也放进来（试过，不成立）

    一条完整菜谱（配料 + 步骤 + 营养）约 2.4KB，192 道就是 **482KB**。
    生产库在 Supabase（孟买），把这些拉回来实测 **4 秒** ——
    瓶颈是数据量而不是查询条数（整个接口只有 5 条 SQL）。
    菜谱页是主 tab，进去卡 4 秒不可接受。

    语义上也没必要：**菜品库的浏览入口是「推荐」标签** ——
    它本来就是「按你冰箱里现有的食材挑出能做的菜」，
    比在「全部」里平铺 192 道更贴合用户的问题。
    点推荐卡 → 秒开完整做法（见 materialize），那条链路才是重点。

    真要浏览整个菜品库，得单独做一个标签页，并且
    ① 列表不返回 steps / ingredients（省掉 75% 体积）
    ② 分页。那是另一件事。

    注意：这里**也要**用当前库存重算缺料，不能直接返回空列表。
    以前这里偷懒返回 missing_ingredients=[]，导致两个问题：
      1. 列表里每道菜都显示「食材齐全」，用户买完菜回来看不出区别；
      2. 客户端按「缺料空不空」做的筛选形同虚设。
    顺带把 ready（食材已备齐）算出来，客户端据此把做完的菜弱化置底。
    """
    # ⚠️ **顺序很重要：先取库存，再查菜谱。**
    #
    # `_load_inventory` 有可能 commit（刷新鲜度），而 commit 会让
    # session 里**已经加载**的对象过期。菜谱要是先查出来，
    # 后面每访问一道菜的字段都会各触发一次 SELECT ——
    # 200 道菜 × 169ms（Supabase 孟买往返）实测 **74 秒**。
    #
    # 反过来先取库存就没这个问题：commit 发生在菜谱被加载之前。
    inventory = _load_inventory(db, user.id)

    # 按菜名只取最新的一条。修复前反复点「生成新菜谱」攒下的重复行
    # 可能还留在库里（见 _persist_or_update 的说明），这里兜一层，
    # 保证界面上永远不会出现重复卡片。
    # 范围是全家的菜谱；按菜名分组去重也是跨账号的 ——
    # 妈妈和我各生成过一次同一道菜，列表里也只该出现一张卡片。
    scope = editable_recipe_clause(db, user.id)
    latest_ids = (
        db.query(func.max(Recipe.id))
        .filter(scope)
        .group_by(Recipe.name)
        .scalar_subquery()
    )
    rows = (
        db.query(Recipe)
        .filter(scope, Recipe.id.in_(latest_ids))
        .order_by(Recipe.created_at.desc())
        .limit(limit)
        .all()
    )
    if not rows:
        return []

    return [_with_availability(_row_to_out(r), inventory) for r in rows]


# ⚠️ 这个路由**必须**放在 `/{recipe_id}` 之前。
# 否则 FastAPI 会先把 "recommend" 当成 recipe_id 去匹配 `/{recipe_id}`，
# 结果是 422（int 解析失败）而不是走到这里。
@router.get("/recommend", summary="按冰箱里现有的食材推荐能做的菜")
def recommend(
    user: CurrentUser,
    db: DbSession,
    limit: int = Query(default=30, ge=1, le=100),
    category: str | None = Query(default=None, description="只看某个分类，如「家常热菜」"),
) -> dict:
    """从 192 道内置家常菜里，挑出用户现在能做 / 差一点就能做的。

    ## 和 `/generate` 的区别

    - `/generate` 是**AI 现编**：慢（约 20 秒）、花钱、但能按画像定制，
      也能编出库里没有的菜
    - `/recommend` 是**查库**：快（毫秒级）、免费、菜品有专属配图，
      但只能推荐库里那 192 道

    两者互补：用户想「看看现在能做什么」用这个（即时反馈），
    想要「按我的口味来点新花样」用 AI 生成。

    ## 为什么不用 AI 算「能不能做」

    AI 判断「冰箱里有没有五花肉」是不可靠的（它会凭常识猜），
    而且每次都要花钱。库里每道菜的必需食材是**人工标注**的，
    用集合运算判断又准又快。
    """
    inventory = _load_inventory(db, user.id)
    dishes = recommend_dishes(inventory, limit=limit, category=category)
    return {
        "total": len(dishes),
        "ready_count": sum(1 for d in dishes if d["ready"]),
        "dishes": dishes,
    }


@router.post("/materialize", response_model=RecipeOut,
             summary="照着菜品库里的某道菜生成详细做法")
def materialize(
    payload: RecipeMaterializeRequest, user: CurrentUser, db: DbSession
) -> RecipeOut:
    """取菜品库里某道菜的完整做法（含步骤和营养）。

    ## 现在**不再调 AI**

    菜品库那 192 道菜的步骤、营养、配料，已经**离线生成一次**
    并作为系统内置菜谱（`user_id` 为 NULL）写进数据库了 ——
    见 `tools/generate-dish-recipes.py` + `tools/seed-dish-recipes.py`。

    所以这个接口现在的实际行为是：**按菜名去库里查一条内置菜谱，直接返回**。
    点开就是秒开，也不消耗任何 AI 额度。

    接口本身保留下来，是因为客户端还按「POST 一个菜名、拿回一条完整菜谱」
    这个契约调用它，而且下面那段 AI 兜底还需要留着。

    ## 为什么还留着 AI 兜底

    万一某道菜在离线生成时失败了（模型抽风、网络断），
    种子数据里就没有它。这时候**不能直接报错** ——
    用户点了一道推荐菜却打不开，比多花一次 AI 调用糟得多。
    所以查不到就现生成一条（并且只落给这个用户自己）。

    实测离线生成 192 道后，这条兜底路径正常情况下不会走到。

    ## 幂等

    同一个用户反复点同一道菜，查到的都是同一条内置记录，不会重复花钱，
    也不会在列表里攒出多行。
    """
    from app.services.dish_library import DISH_NAMES

    name = payload.name.strip()
    # 必须是菜品库里真实存在的菜。
    # 不校验的话客户端能传任意名字让 AI 现编 —— 那等于绕过了菜品库，
    # 也绕过了「这 192 道菜是人工核对过的」这个前提。
    if name not in DISH_NAMES:
        raise HTTPException(status_code=404, detail=f"菜品库里没有「{name}」")

    # ① 库里已有（内置的，或用户自己生成过的）→ 直接返回，不调 AI
    #
    # ⚠️ **优先取内置的那份**（`user_id IS NULL` 排前面）。
    #
    # 为什么不是优先用户自己的：Ethan 明确要求「不要每次点开都 AI 现场生成的，
    # 要本来就现成的」。用户库里可能留着一份**更早用 AI 生成的同名菜**
    #（那正是以前 materialize 现场生成留下的），内容不如离线整理过的好。
    # 优先内置才能保证「点任何一道推荐菜，拿到的都是菜品库那份」。
    #
    # 用户自己那份不会消失 —— 它还在「全部」列表里，是他自己的记录。
    existing = (
        db.query(Recipe)
        .filter(
            readable_recipe_clause(db, user.id),
            Recipe.name == name,
        )
        .order_by(Recipe.user_id.is_(None).desc(), Recipe.id.desc())
        .first()
    )
    if existing is not None:
        inventory = _load_inventory(db, user.id)
        return _with_availability(_row_to_out(existing), inventory)

    # ② 库里没有（离线生成时漏掉的）→ 现生成一条，只落给这个用户
    #
    # ⚠️ 配额检查放在这里而不是函数开头：
    # 正常情况下 ① 就返回了（查库、不花钱），**根本走不到这里**。
    # 放在开头会让每次点推荐菜都白扣一次配额 —— 而那本该是免费的。
    guard_ai_quota(db, user.id)
    _guard_write(db, user.id)
    inventory, expiring, pref, health = _load_context(db, user.id)

    recipes, _model = generate_recipes(
        inventory=inventory,
        pref=pref,
        health=health,
        count=1,
        max_time=None,
        expiring=expiring,
        focus_dish=name,
    )
    if not recipes:
        raise HTTPException(status_code=502, detail="生成失败，请稍后重试")

    row = _persist_or_update(db, user.id, recipes[0])
    db.commit()
    db.refresh(row)

    inventory = _load_inventory(db, user.id)
    return _with_availability(_row_to_out(row), inventory)


@router.get("/{recipe_id}", response_model=RecipeOut, summary="菜谱详情")
def get_recipe(recipe_id: int, user: CurrentUser, db: DbSession) -> RecipeOut:
    row = _load_readable_recipe(db, user.id, recipe_id)

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
    row = _load_readable_recipe(db, user.id, recipe_id)
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
    row = _load_readable_recipe(db, user.id, recipe_id)
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
    看得见却删不掉会让人以为按钮坏了。只读成员除外。

    ⚠️ **系统内置的不能删**（`_load_editable_recipe` 会给 403）。
    内置菜谱是所有用户共用的一条记录，删一次全都没了。
    客户端那边靠 `RecipeOut.is_builtin` 把这个按钮藏起来。
    """
    _guard_write(db, user.id)
    row = _load_editable_recipe(db, user.id, recipe_id)
    db.delete(row)
    db.commit()


@router.post("/feedback", summary="上报用户行为（收藏/做过/跳过/评分），用于修正画像")
def submit_feedback(payload: MealActionRequest, user: CurrentUser, db: DbSession) -> dict:
    # 家人生成的菜也要能反馈 —— 收藏 / 做过 / 跳过记的是**我自己的**口味，
    # 属于个人数据，所以只读成员也能用（不拦 _guard_write）。
    row = _load_readable_recipe(db, user.id, payload.recipe_id)

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
