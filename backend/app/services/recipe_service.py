"""AI 2：菜谱生成。

关键设计（对应策划书第五节）：
    规则系统负责约束，AI 负责理解和生成。

也就是说——模型只负责「想菜名、写步骤、估营养」，
**食材是否在库存里、缺多少，全部由后端用真实库存重算**，
不采信模型自报的 available 字段。这样才不会出现「AI 说你有豆腐其实没有」。
"""
from __future__ import annotations

import logging

from sqlalchemy import or_
from sqlalchemy.orm import Session

from app.core.config import settings
from app.models.inventory import FoodInventory
from app.models.recipe import Recipe
from app.models.user import HealthPreference, UserPreference
from app.schemas.recipe import (
    CookPlan,
    CookPlanItem,
    MissingIngredient,
    NutritionEstimate,
    RecipeIngredientOut,
    RecipeOut,
)
from app.services.ai_client import AIUnavailable, ai_client
from app.services.family_service import visible_user_ids
from app.services.food_image_service import resolve_image_url

logger = logging.getLogger(__name__)

# ============================================================
#  菜谱的可见范围 —— ⚠️ 只在这里定义一次
# ============================================================
#
# 菜谱表里有两类数据，**读和写的范围不一样**：
#
#   user_id 为 NULL   →  系统内置菜谱（菜品库那 192 道，所有用户可见）
#   user_id 有值      →  用户自己（或家人）生成的菜谱
#
# 为什么必须拆成两个函数：
#
#   如果读和写共用一套范围，用户就能**删掉系统内置菜谱** ——
#   而那条记录是所有用户共用的，删一次全都没了。
#   同理，AI 生成时按菜名查重如果查到了内置菜谱，会把内置那条
#   改写成用户自己的（`source` 从 builtin 变成 ai），也是全局污染。
#
# 和 `family_service.visible_user_ids()` 的分工：
#   那个函数回答「我能看到哪些**人**的数据」，
#   这两个函数回答「在**菜谱**这张表上，我能看到 / 能改哪些行」。
#   后者建立在前者之上，所以改家庭规则时不用动这里。


def readable_recipe_clause(db: Session, user_id: int):
    """**读**菜谱时的可见范围：系统内置 + 自己/家人。

    列表、详情、做菜预览、扣减库存、采购清单、用户行为上报都用它。
    漏用的后果是「推荐列表里有这道菜，点进去 404」。
    """
    return or_(
        Recipe.user_id.is_(None),
        Recipe.user_id.in_(visible_user_ids(db, user_id)),
    )


def editable_recipe_clause(db: Session, user_id: int):
    """**写**菜谱时的可见范围：自己/家人，**不含系统内置**。

    按菜名查重、删除、改名都用它。
    """
    return Recipe.user_id.in_(visible_user_ids(db, user_id))


def is_builtin(row) -> bool:
    """这条菜谱是不是系统内置的（决定界面上要不要显示「删除」）。"""
    return getattr(row, "user_id", None) is None


RECIPE_SYSTEM_PROMPT = """你是一位擅长中国家常菜的营养厨师，服务于「冰箱先知」这个 App。
用户会给你他的冰箱库存、饮食偏好和健康约束，你需要推荐真正能做的菜。

严格规则：
1. 只输出 JSON，不要输出任何解释、Markdown 代码块或额外说明。
2. 【最重要】绝对不能编造库存。你只能在用户给出的库存清单范围内声称「有」某种食材。
   如果一道菜需要库存里没有的东西，把它放进 missing_ingredients，不要放进 ingredients 的 available 项。
3. 优先消耗用户标记为「即将过期」的食材，尽量让每道菜都用到至少一种。
4. 严格避开用户明确过敏的食材——这是硬性红线，一道菜里出现即视为错误。
5. 避开用户明确不喜欢的食材。
6. 遵守用户的烹饪时间上限，time_minutes 不能超过用户给的上限。
7. 符合用户的饮食目标（减脂→少油少糖、增肌/高蛋白→多蛋白、素食→不含任何肉类海鲜）。
8. 每道菜的步骤要具体可操作，写清楚火候和顺序，3 到 8 步。
9. 营养数据是估算值，按每份计算，宁可给区间中值也不要空着。
10. 菜名用中文，贴近真实家常菜名（如「番茄炒蛋」「青椒鸡胸肉」），不要起浮夸的名字。
11. 所有菜品之间要有明显差异，不要推荐三道本质相同的菜。

输出 JSON 结构（必须严格遵守）：
{
  "recipes": [
    {
      "name": "番茄鸡蛋豆腐",
      "description": "一句话介绍，20 字以内",
      "time_minutes": 15,
      "difficulty": "easy",
      "ingredients": [
        {"name": "西红柿", "quantity": 2, "unit": "个"},
        {"name": "鸡蛋", "quantity": 2, "unit": "个"},
        {"name": "豆腐", "quantity": 300, "unit": "g"}
      ],
      "steps": ["西红柿切块", "鸡蛋打散加少许盐", "热锅少油炒蛋盛出", "下西红柿炒出汁", "加豆腐和炒蛋翻匀调味"],
      "nutrition": {
        "calories_kcal": 320,
        "protein_g": 24,
        "carbs_g": 12,
        "fat_g": 18,
        "fiber_g": 3,
        "sodium_mg": 620
      },
      "tags": ["高蛋白", "15分钟"]
    }
  ]
}

difficulty 只能是 easy、medium、hard 之一。
ingredients 里只列这道菜需要的全部食材，不需要你判断哪些有库存——那部分由系统计算。"""


def _fmt_inventory(items: list[FoodInventory]) -> str:
    if not items:
        return "（冰箱是空的）"
    lines = []
    for it in items:
        days = ""
        if it.expiry_date:
            days = f"，剩余 {(it.expiry_date - __import__('datetime').date.today()).days} 天"
        lines.append(
            f"- {it.food_name}：{it.quantity:g}{it.unit}"
            f"（{it.storage_location}，新鲜度：{it.freshness}{days}）"
        )
    return "\n".join(lines)


def _fmt_preference(pref: UserPreference | None, health: HealthPreference | None) -> str:
    if pref is None:
        return "用户未填写偏好，按普通家常菜处理。"

    parts = [
        f"- 菜系偏好：{pref.cuisine}",
        f"- 口味：{pref.taste}",
        f"- 单次烹饪时间上限：{pref.cook_time_max} 分钟",
        f"- 饮食目标：{pref.diet_goal}",
        f"- 不吃的食材（必须避开）：{'、'.join(pref.disliked_foods) or '无'}",
        f"- 过敏食材（绝对禁止出现）：{'、'.join(pref.allergies) or '无'}",
    ]
    if health:
        flags = []
        if health.vegetarian:
            flags.append("素食（不含任何肉类、禽类、海鲜）")
        if health.low_carb:
            flags.append("控制碳水")
        if health.low_sodium:
            flags.append("控制钠盐")
        if health.low_fat:
            flags.append("控制脂肪")
        if health.high_protein:
            flags.append("高蛋白")
        if health.high_fiber:
            flags.append("增加膳食纤维")
        if flags:
            parts.append(f"- 健康管理要求：{'、'.join(flags)}")
        if health.height_cm and health.weight_kg:
            parts.append(f"- 身高体重：{health.height_cm:g}cm / {health.weight_kg:g}kg")
        if health.activity_level:
            parts.append(f"- 运动频率：{health.activity_level}")
    return "\n".join(parts)


def _build_user_prompt(
    inventory: list[FoodInventory],
    pref: UserPreference | None,
    health: HealthPreference | None,
    count: int,
    max_time: int,
    expiring: list[str],
    extra_notes: str | None,
    exclude: list[str] | None = None,
    focus_dish: str | None = None,
) -> str:
    expiring_line = (
        f"\n⚠️ 即将过期，请优先消耗：{'、'.join(expiring)}\n" if expiring else ""
    )
    notes = f"\n用户补充说明：{extra_notes}\n" if extra_notes else ""

    # 已经推荐过的菜名要**明确列出来**让模型避开。
    #
    # 不告诉它的话，模型每次都把最适合这份库存的那几道再给一遍 ——
    # 实测：同一个冰箱连点三次「生成新菜谱」，拿到的都是
    # 「番茄炒蛋 + 青椒鸡胸肉 + 第三道略变」，前两道一模一样。
    # 而落库是按菜名 upsert 的（见 api/v1/recipes.py），
    # 重复的名字只会覆盖旧行，**列表永远长不大** ——
    # 用户感受就是「能生成的菜非常有限」。
    exclude_line = ""
    if exclude:
        exclude_line = (
            f"\n【已经推荐过，请勿重复】{'、'.join(exclude)}\n"
            f"上面这些菜用户已经看过了。请推荐**完全不同**的菜，"
            f"哪怕换个主料、换个做法都行，就是不要重复上面的菜名。\n"
        )

    # 「照着某道菜生成详情」用 —— 用户点了菜品库里的一道菜，
    # 我们要的是**这一道**的步骤和营养，不是让它自由发挥。
    focus_line = ""
    if focus_dish:
        focus_line = (
            f"\n【必须生成这一道菜】{focus_dish}\n"
            f"菜名必须**原样**使用「{focus_dish}」，不要改名、不要换成别的菜。\n"
            f"只生成这 1 道，不要多给。\n"
        )

    if focus_dish:
        return f"""请为用户生成「{focus_dish}」这一道菜的详细做法。

【冰箱现有库存】（这是唯一真实存在的食材，不得声称拥有其他食材）
{_fmt_inventory(inventory)}
{expiring_line}
【用户画像与约束】
{_fmt_preference(pref, health)}
{focus_line}
【额外要求】
- 菜名必须是「{focus_dish}」，一个字都不要改
- 做法要符合这道菜的传统做法，不要自创
- 需要什么食材就如实列出（哪怕冰箱里没有）——
  缺料由后端按真实库存算，你只管把菜谱写对
- 只返回 JSON，里面**只包含 1 道菜**{notes}"""

    return f"""请为用户推荐 {count} 道菜。

【冰箱现有库存】（这是唯一真实存在的食材，不得声称拥有其他食材）
{_fmt_inventory(inventory)}
{expiring_line}
【用户画像与约束】
{_fmt_preference(pref, health)}
{exclude_line}
【额外要求】
- 每道菜烹饪时间不超过 {max_time} 分钟
- 尽量只用库存里已有的食材，把缺的东西明确列出来
- 只返回 JSON{notes}"""


def _normalize_unit(u: str) -> str:
    return (u or "").strip().lower()


# `recipe_ingredients.unit` 是 VARCHAR(16)，而模型偶尔会在单位字段里塞注释。
# 离线生成 192 道菜时实测出现过的脏值：
#   「汤匙（即oyster-sauce）」  17 个字 —— 直接超长
#   「个（可选）」「茶匙（可选）」 把 optional 混进了单位
#   「/2勺」「/4 茶匙」          分数写残了，前面的 1 丢了
#
# ⚠️ 这个坑**在 SQLite 上测不出来**（SQLite 不校验 VARCHAR 长度），
# 只有在生产库（Postgres）上才会炸：
#   StringDataRightTruncation → 整个请求 500。
# 而且它出现在**写库**路径上，等于「AI 哪次心情不好，用户就存不了菜谱」。
#
# 所以清洗放在这里，**写库的两个入口共用**：
#   ① `_persist`（/generate 和 /materialize 的兜底路径）
#   ② `tools/seed-dish-recipes.py`（离线灌菜品库）
UNIT_MAX_LEN = 16
UNIT_FALLBACK = "份"


def clean_unit(raw: str) -> str:
    """把模型给的单位洗成能安全入库的形式。

    规则（按顺序）：
      1. 去掉首尾空白
      2. 去掉括号注释：`汤匙（即oyster-sauce）` → `汤匙`、`个（可选）` → `个`
      3. 去掉残缺分数的前导斜杠：`/2勺` → `勺`
      4. 还是空的、或者仍然超过 16 个字 → 退成 `份`

    第 4 步是**兜底而不是截断**：超过 16 个字的东西肯定不是一个单位，
    截断只会得到「汤匙（即oyster-sa」这种更莫名其妙的值。
    """
    import re

    text = (raw or "").strip()
    if not text:
        return UNIT_FALLBACK

    # 中英文括号都去掉。用 `.*` 贪婪匹配到最后一个右括号，
    # 这样「块（约350g）」这种只去一次就干净了。
    text = re.sub(r"[（(].*[)）]", "", text).strip()
    # 残缺分数：AI 写「1/2勺」时前面的 1 有时会丢，留下「/2勺」
    text = re.sub(r"^/\s*\d+\s*", "", text).strip()

    if not text or len(text) > UNIT_MAX_LEN:
        return UNIT_FALLBACK
    return text


def _index_stock(inventory: list[FoodInventory]) -> dict[str, FoodInventory]:
    """把库存按食材名索引，同名的取第一条。

    ⚠️ `recompute_availability` 和 `build_cook_plan` **必须共用这一个索引**。
    以前「有没有这种食材」有两处实现，结果单位对不上时两边结论相反
    （菜谱页显示「豆腐 ✓ 有」、采购页却让你去买豆腐），见 MEMORY.md 铁律 6。
    做菜扣库存是第三处用到它的地方，更要共用 ——
    否则会出现「菜谱说食材齐全，一点『做这道菜』却告诉你冰箱里找不到」。
    """
    stock: dict[str, FoodInventory] = {}
    for it in inventory:
        stock.setdefault(it.food_name.strip(), it)
    return stock


def recompute_availability(
    ingredients: list[RecipeIngredientOut], inventory: list[FoodInventory]
) -> list[MissingIngredient]:
    """用真实库存重算「缺什么」，并**就地**修正每个食材的 available 标记。

    ⚠️ 这是全项目判断「食材有没有」的**唯一实现**。
    菜谱生成、菜谱详情、菜谱列表都调它，不允许各处自己写一遍。

    为什么必须唯一：以前菜谱详情和采购服务各写了一份判断，
    结果单位对不上时（库存记「1 盒豆腐」，菜谱要「300 g」）两边结论相反，
    菜谱页显示「豆腐 ✓ 有」、采购页却让你去买豆腐。见 MEMORY.md 铁律 7。

    规则（三条都要和 shopping_service 保持一致）：
        1. 库存里完全没有这种食材 → 缺，按菜谱要的量全额计入
        2. 单位能对上 → 比数量，不够就缺差额
        3. 单位对不上且无法换算 → **按「大概率有」处理，不列入采购**。
           让用户去买冰箱里已经有的东西，比漏买一样更糟。
    """
    stock = _index_stock(inventory)

    missing: list[MissingIngredient] = []

    for ing in ingredients:
        owned = stock.get(ing.name.strip())
        if owned is None:
            ing.available = False
            missing.append(
                MissingIngredient(name=ing.name, quantity=ing.quantity, unit=ing.unit)
            )
            continue

        if _normalize_unit(owned.unit) == _normalize_unit(ing.unit):
            have = float(owned.quantity or 0)
            need = float(ing.quantity or 0)
            ing.available = have >= need > 0 or (need == 0 and have > 0)
            if not ing.available:
                missing.append(
                    MissingIngredient(
                        name=ing.name, quantity=round(need - have, 2), unit=ing.unit
                    )
                )
        else:
            ing.available = True

    return missing


def _recompute_availability(
    ingredients: list[dict], inventory: list[FoodInventory]
) -> tuple[list[RecipeIngredientOut], list[MissingIngredient]]:
    """把 AI 返回的原始食材列表规范化，再交给 recompute_availability 判缺料。"""
    normalized: list[RecipeIngredientOut] = []

    for raw in ingredients:
        if not isinstance(raw, dict):
            continue
        name = str(raw.get("name", "")).strip()
        if not name:
            continue
        try:
            need = float(raw.get("quantity") or 0)
        except (TypeError, ValueError):
            need = 0.0
        unit = str(raw.get("unit") or "g").strip()
        optional = bool(raw.get("optional", False))

        normalized.append(
            RecipeIngredientOut(
                name=name, quantity=need, unit=unit, available=False, optional=optional
            )
        )

    missing = recompute_availability(normalized, inventory)
    return normalized, missing


def _mock_recipes(
    inventory: list[FoodInventory],
    count: int,
    expiring: list[str],
    focus_dish: str | None = None,
) -> list[dict]:
    """无密钥时的兜底菜谱，保证演示链路完整。

    ⚠️ `focus_dish` 必须支持 —— 「照着菜品库里的某道菜生成详情」
    在 MOCK 模式下也要能用。不支持的话，没配密钥时点推荐卡片
    会得到一个空的 502，而调用方（`materialize`）还会因为
    「模型返回的菜名对不上」把它过滤掉，报错信息完全指不到真正原因。
    """
    # 指定了菜名 → 就返回这一道（步骤是占位，但菜名必须准确）
    if focus_dish:
        return [
            {
                "name": focus_dish,
                "description": f"{focus_dish}（MOCK 模式生成的占位做法）",
                "time_minutes": 20,
                "difficulty": "easy",
                "ingredients": [
                    {"name": i.food_name, "quantity": 1, "unit": i.unit}
                    for i in inventory[:4]
                ],
                "steps": [
                    f"准备{focus_dish}需要的食材，洗净切好",
                    "热锅下油，按顺序下锅翻炒",
                    "调味后出锅装盘",
                ],
                "nutrition": {"calories_kcal": 300, "protein_g": 15, "carbs_g": 30,
                              "fat_g": 12},
            }
        ]

    names = [i.food_name for i in inventory] or ["鸡蛋", "西红柿"]
    pool = [
        {
            "name": "番茄鸡蛋豆腐",
            "description": "家常快手，酸甜下饭",
            "time_minutes": 15,
            "difficulty": "easy",
            "ingredients": [
                {"name": "西红柿", "quantity": 2, "unit": "个"},
                {"name": "鸡蛋", "quantity": 2, "unit": "个"},
                {"name": "豆腐", "quantity": 300, "unit": "g"},
                {"name": "小葱", "quantity": 10, "unit": "g"},
            ],
            "steps": ["西红柿切块，豆腐切厚片", "鸡蛋打散加少许盐",
                      "热锅少油，炒蛋至凝固盛出", "下西红柿炒出汁水",
                      "加豆腐与炒蛋，翻匀后调味出锅"],
            "nutrition": {"calories_kcal": 320, "protein_g": 24, "carbs_g": 12,
                          "fat_g": 18, "fiber_g": 3, "sodium_mg": 620},
            "tags": ["家常菜", "15分钟"],
        },
        {
            "name": "香煎鸡胸配西兰花",
            "description": "高蛋白低油，健身友好",
            "time_minutes": 25,
            "difficulty": "easy",
            "ingredients": [
                {"name": "鸡胸肉", "quantity": 200, "unit": "g"},
                {"name": "西兰花", "quantity": 200, "unit": "g"},
                {"name": "黑胡椒", "quantity": 2, "unit": "g"},
            ],
            "steps": ["鸡胸肉拍松，用盐和黑胡椒腌 10 分钟", "西兰花切小朵焯水",
                      "平底锅刷薄油，中火煎鸡胸每面 4 分钟", "静置 3 分钟后切片装盘"],
            "nutrition": {"calories_kcal": 280, "protein_g": 42, "carbs_g": 10,
                          "fat_g": 8, "fiber_g": 4, "sodium_mg": 380},
            "tags": ["高蛋白", "低油"],
        },
        {
            "name": "青椒炒鸡蛋",
            "description": "三分钟出锅的下饭菜",
            "time_minutes": 10,
            "difficulty": "easy",
            "ingredients": [
                {"name": "青椒", "quantity": 2, "unit": "个"},
                {"name": "鸡蛋", "quantity": 3, "unit": "个"},
            ],
            "steps": ["青椒去籽切丝", "鸡蛋打散", "热锅下蛋液炒散盛出",
                      "下青椒丝大火翻炒 1 分钟", "回锅鸡蛋，加盐炒匀"],
            "nutrition": {"calories_kcal": 240, "protein_g": 16, "carbs_g": 8,
                          "fat_g": 16, "fiber_g": 2, "sodium_mg": 540},
            "tags": ["快手菜", "10分钟"],
        },
    ]
    out = pool[: max(1, min(count, len(pool)))]
    if expiring:
        for r in out:
            r.setdefault("tags", []).append("消耗临期")
    logger.info("MOCK 菜谱已生成，库存食材参考: %s", names)
    return out


def generate_recipes(
    *,
    inventory: list[FoodInventory],
    pref: UserPreference | None,
    health: HealthPreference | None,
    count: int = 3,
    max_time: int | None = None,
    expiring: list[str] | None = None,
    extra_notes: str | None = None,
    exclude: list[str] | None = None,
    focus_dish: str | None = None,
) -> tuple[list[RecipeOut], str]:
    """返回 (菜谱列表, 使用的模型名)。

    `exclude` 是**用户已经有的菜名**，会写进提示词让模型避开 ——
    不传的话模型每次都推荐同样的几道，而落库又是按菜名去重的，
    结果就是「点多少次列表都不变」。
    """
    expiring = expiring or []
    effective_max_time = max_time or (pref.cook_time_max if pref else 30)

    if not inventory:
        # 冰箱为空时不调用模型，直接返回空列表，前端引导用户先扫描
        return [], "none"

    if ai_client.enabled:
        try:
            data = ai_client.chat_json(
                system=RECIPE_SYSTEM_PROMPT,
                user_text=_build_user_prompt(
                    inventory, pref, health, count, effective_max_time,
                    expiring, extra_notes, exclude, focus_dish,
                ),
                model=settings.TEXT_MODEL,
                temperature=0.7,
            )
            raw_recipes = data.get("recipes") or []
            model_name = settings.TEXT_MODEL
        except AIUnavailable as exc:
            logger.error("菜谱生成失败，降级为 MOCK: %s", exc)
            raw_recipes = _mock_recipes(inventory, count, expiring, focus_dish)
            model_name = "mock"
    else:
        raw_recipes = _mock_recipes(inventory, count, expiring, focus_dish)
        model_name = "mock"

    results: list[RecipeOut] = []
    seen_names: set[str] = set()
    for raw in raw_recipes:
        if not isinstance(raw, dict):
            continue
        name = str(raw.get("name") or "").strip()
        if not name:
            continue
        # 同一次返回里也要去重：模型偶尔会把同一道菜换个说法再给一遍，
        # 不去掉的话刚生成就会出现两张一模一样的卡片。
        if name in seen_names:
            logger.info("本次生成出现重名菜谱，已跳过：%s", name)
            continue
        seen_names.add(name)

        # 「照着某道菜生成」时只保留菜名对得上的那道。
        # 提示词里已经明确说了「只生成这 1 道」，但模型偶尔还是会顺手多给 ——
        # 多出来的那道会变成一个用户没点过的卡片，属于噪音。
        if focus_dish and name != focus_dish:
            logger.info("focus_dish=%s 但模型返回了「%s」，已跳过", focus_dish, name)
            continue

        ingredients, missing = _recompute_availability(
            raw.get("ingredients") or [], inventory
        )
        nutrition_raw = raw.get("nutrition") or {}
        if not isinstance(nutrition_raw, dict):
            nutrition_raw = {}
        nutrition_raw.pop("disclaimer", None)

        used_expiring = [
            ing.name for ing in ingredients if ing.name in expiring and ing.available
        ]

        try:
            nutrition = NutritionEstimate(**nutrition_raw)
        except Exception:  # noqa: BLE001
            nutrition = NutritionEstimate()

        difficulty = str(raw.get("difficulty") or "easy").lower()
        if difficulty not in ("easy", "medium", "hard"):
            difficulty = "easy"

        try:
            time_minutes = int(raw.get("time_minutes") or 30)
        except (TypeError, ValueError):
            time_minutes = 30

        steps = [str(s).strip() for s in (raw.get("steps") or []) if str(s).strip()]
        tags = [str(t) for t in (raw.get("tags") or [])]

        results.append(
            RecipeOut(
                name=name,
                description=str(raw.get("description") or "")[:200],
                time_minutes=max(1, time_minutes),
                difficulty=difficulty,  # type: ignore[arg-type]
                ingredients=ingredients,
                missing_ingredients=missing,
                steps=steps,
                nutrition=nutrition,
                tags=tags,
                uses_expiring=used_expiring,
                # 配料表非空且一样不缺 = 现在就能做
                ready=bool(ingredients) and not missing,
                # 配图由本地图片库决定，不看模型脸色 —— 模型只会给菜名，
                # 图片匹配是纯规则，确定性、不花钱、断网也有。
                image_url=resolve_image_url(name, [i.name for i in ingredients]),
            )
        )

    return results[:count], model_name


def build_cook_plan(
    *,
    recipe_id: int,
    recipe_name: str,
    ingredients: list[RecipeIngredientOut],
    inventory: list[FoodInventory],
) -> CookPlan:
    """做菜前算一遍「这道菜会用掉冰箱里哪些东西、各扣多少」。

    **为什么要有这个预览**：扣库存是不可逆的（扣完那行就没了），
    不能点一下就闷头扣。用户得先看见清单、能改数字、再确认。

    **为什么单位对不上时不猜**：库存记「1 盒豆腐」、菜谱要「300 g」，
    两者没法换算。这时候给个「大概扣 300」是错的（可能把整盒扣没），
    给 0 也是错的（用户以为扣了）。所以 `suggested_deduct=None`，
    前端渲染成空输入框让用户自己填 —— 和 `recompute_availability`
    对单位不一致时按「大概率有」处理是同一个态度：不装懂。
    """
    stock = _index_stock(inventory)
    items: list[CookPlanItem] = []
    missing: list[MissingIngredient] = []

    for ing in ingredients:
        owned = stock.get(ing.name.strip())

        if owned is None:
            # 冰箱里没有 —— 和缺料判断保持一致，不参与扣减
            missing.append(
                MissingIngredient(name=ing.name, quantity=ing.quantity, unit=ing.unit)
            )
            continue

        have = float(owned.quantity or 0)
        need = float(ing.quantity or 0)
        matched = _normalize_unit(owned.unit) == _normalize_unit(ing.unit)

        suggested: float | None = None
        will_empty = False
        if matched:
            # 最多扣到 0，不能扣成负数
            suggested = round(min(need, have), 2)
            will_empty = suggested >= have

        items.append(
            CookPlanItem(
                name=ing.name,
                need_quantity=need,
                need_unit=ing.unit,
                stock_item_id=owned.id,
                stock_quantity=have,
                stock_unit=owned.unit,
                suggested_deduct=suggested,
                unit_matched=matched,
                will_empty=will_empty,
                optional=ing.optional,
            )
        )

    return CookPlan(
        recipe_id=recipe_id,
        recipe_name=recipe_name,
        items=items,
        missing=missing,
    )


# ============================================================
#  内置菜品库推荐
# ============================================================

def _stock_keys(inventory: list[FoodInventory]) -> set[str]:
    """把库存映射成**图片 key 集合**。

    ## 为什么要有这一层

    `_index_stock()` 是按**中文食材名**索引的（AI 生成的菜谱里存的是名字）。
    而内置菜品库的「必需食材」用的是**图片 key**（`tomato` 而不是「番茄」），
    两边对不上。

    这里在 `_index_stock()` 的基础上转一层，**保证「冰箱里有什么」仍然只有一处定义** ——
    直接自己遍历一遍 inventory 判断，就又变成两处了（铁律 6 踩过的坑）。

    认不出 key 的食材直接跳过：它不会匹配上任何菜品，留着也没用。
    """
    from app.services.ingredient_image_service import resolve_ingredient_key

    keys: set[str] = set()
    for name in _index_stock(inventory):
        key = resolve_ingredient_key(name)
        if key:
            keys.add(key)
    return keys


def recommend_dishes(
    inventory: list[FoodInventory],
    limit: int = 30,
    category: str | None = None,
) -> list[dict]:
    """按冰箱里现有的食材，从内置菜品库里挑出能做的菜。

    返回的每一项：

        {
          "name": "西红柿炒鸡蛋",
          "category": "家常热菜",
          "image_url": "/static/recipes/scrambled-eggs-with-tomatoes.jpg",
          "ready": True,               # 必需食材齐了
          "matched": ["番茄", "鸡蛋"],  # 冰箱里已有（**中文名**，给用户看）
          "missing": [],                # 还缺什么
        }

    ## 排序规则

    1. **能做的在前**（缺 0 样）
    2. 缺得少的在前
    3. 同档按菜名稳定排序（否则每次请求顺序会变，列表看起来在跳）

    ## 为什么用「必需食材」而不是「全部食材」

    `dish_library.BY_REQUIRED` 只算必需食材。如果连可选食材（蒜、葱、酱油）
    也算进去，用户有蒜就会看到几十道「差一点就能做」的菜，全是噪音。
    """
    from app.services.dish_library import DISH_CATEGORIES, DISH_INGREDIENTS, DISH_NAMES
    # ⚠️ 这里必须用 **food_image_service** 的 _exists / _url ——
    # 它们查的是 `static/recipes/`。
    # ingredient_image_service 里**也有**一对同名的 _exists / _url，
    # 但查的是 `static/ingredients/`。拿错的话每道菜都会显示「没有配图」，
    # 而且不报错 —— 只是图片静默消失了。
    from app.services.food_image_service import _exists as image_exists
    from app.services.food_image_service import _url as image_url
    from app.services.ingredient_lexicon import INGREDIENT_ALIASES

    stock = _stock_keys(inventory)

    # key -> 中文名（显示用）。同一个 key 可能对应多个中文名，取最短的那个
    #（「番茄」比「西红柿」短，界面上更好看）
    key_to_name: dict[str, str] = {}
    for name, key in INGREDIENT_ALIASES.items():
        cur = key_to_name.get(key)
        if cur is None or len(name) < len(cur):
            key_to_name[key] = name

    out: list[dict] = []
    for dish in DISH_NAMES:
        if category and DISH_CATEGORIES.get(dish) != category:
            continue

        required, _optional = DISH_INGREDIENTS[dish]
        missing = [k for k in required if k not in stock]
        # 一道菜最多接受缺 3 样 —— 缺太多的推了也没意义，用户不会为一道菜买五样东西
        if len(missing) > 3:
            continue

        img_key = DISH_NAMES[dish]
        out.append({
            "name": dish,
            "category": DISH_CATEGORIES.get(dish, ""),
            "image_url": image_url(img_key) if image_exists(img_key) else None,
            "ready": not missing,
            "matched": [key_to_name.get(k, k) for k in required if k in stock],
            "missing": [key_to_name.get(k, k) for k in missing],
        })

    # 能做的在前 → 缺得少的在前 → 菜名稳定排序
    out.sort(key=lambda d: (len(d["missing"]), d["name"]))
    return out[:limit]
