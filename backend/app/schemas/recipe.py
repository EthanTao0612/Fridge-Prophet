"""菜谱生成与输出模型。对应策划书第十六节的严格 JSON 结构。"""
from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

Difficulty = Literal["easy", "medium", "hard"]


class RecipeIngredientOut(BaseModel):
    name: str
    quantity: float = 0
    unit: str = "g"
    available: bool = False
    optional: bool = False


class MissingIngredient(BaseModel):
    name: str
    quantity: float = 0
    unit: str = "个"
    estimated_price: float | None = None


class NutritionEstimate(BaseModel):
    """营养数据均为估算值，不构成医疗或营养学建议。"""

    calories_kcal: float | None = None
    protein_g: float | None = None
    carbs_g: float | None = None
    fat_g: float | None = None
    fiber_g: float | None = None
    sodium_mg: float | None = None
    disclaimer: str = "营养数据为估算值，仅供参考，不能替代医生或注册营养师的建议。"


class RecipeOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int | None = None
    name: str
    description: str = ""
    time_minutes: int = 30
    difficulty: Difficulty = "easy"
    ingredients: list[RecipeIngredientOut] = Field(default_factory=list)
    missing_ingredients: list[MissingIngredient] = Field(default_factory=list)
    steps: list[str] = Field(default_factory=list)
    nutrition: NutritionEstimate = Field(default_factory=NutritionEstimate)
    tags: list[str] = Field(default_factory=list)
    # 这道菜用掉了多少即将过期食材
    uses_expiring: list[str] = Field(default_factory=list)
    # 「食材已备齐」——配料表非空且一样都不缺。
    # 必须由后端算，不能让客户端拿 missing_ingredients 空不空去猜：
    # 配料表为空的坏数据也会是「空」，那样会把一道没配料的菜显示成「已备齐」。
    #
    # ⚠️ 这个字段**只用于筛选和打标**（如「现在能做」分类、卡片上的绿色徽标），
    # 不要拿它去弱化或置底整张卡片。曾经这么做过，用户明确否掉了：
    # 要弱化的是**采购清单里已勾选的条目**，不是菜谱。
    ready: bool = False
    # 菜品配图，**相对路径**（如 /static/recipes/tomato-egg.jpg）。
    # 客户端自己拼域名；没有配图时为 None，此时客户端显示占位样式即可。
    image_url: str | None = None
    created_at: datetime | None = None


class RecipeGenerateRequest(BaseModel):
    """生成菜谱的入参。库存由后端读取，客户端不用传。"""

    count: int = Field(default=3, ge=1, le=5)
    max_time_minutes: int | None = Field(default=None, ge=5, le=240)
    prioritize_expiring: bool = True
    extra_notes: str | None = Field(default=None, max_length=200)
    save: bool = True


class RecipeGenerateResponse(BaseModel):
    recipes: list[RecipeOut]
    model: str = "mock"
    used_ingredients: list[str] = Field(default_factory=list)
    expiring_used: list[str] = Field(default_factory=list)


class RecipeMaterializeRequest(BaseModel):
    """「照着菜品库里的某道菜生成详细做法」的入参。

    只传菜名。**必须是菜品库里真实存在的菜** —— 后端会校验，
    不让客户端随便传个名字让 AI 现编（那等于绕过了菜品库）。
    """

    name: str = Field(min_length=1, max_length=64)


class MealActionRequest(BaseModel):
    recipe_id: int
    action: Literal["view", "favorite", "cook", "skip", "rate"]
    rating: int | None = Field(default=None, ge=1, le=5)


# ---------------------------------------------------------------
#  做菜扣库存
# ---------------------------------------------------------------


class CookPlanItem(BaseModel):
    """扣减预览里的一项。

    前端拿它渲染「这道菜会用掉什么」的清单，每项的数量用户可以改。
    """

    name: str
    need_quantity: float = 0
    need_unit: str = "g"

    # 冰箱里对应的那件食材。没有 = 本来就缺料，扣不了
    stock_item_id: int | None = None
    stock_quantity: float | None = None
    stock_unit: str | None = None

    # 系统建议扣多少。**单位对不上时为 None** —— 不猜，让用户自己填。
    # 这和 recompute_availability / compute_missing 是同一条规则：
    # 单位无法换算时按「大概率有」处理，宁可让用户多填一次，也不瞎扣。
    suggested_deduct: float | None = None
    unit_matched: bool = False

    # 按建议量扣完就没了 —— 前端据此提示「鸡蛋会用完」
    will_empty: bool = False
    optional: bool = False


class CookPlan(BaseModel):
    recipe_id: int
    recipe_name: str
    items: list[CookPlanItem] = Field(default_factory=list)
    # 冰箱里压根没有的（本来就缺的料），这些不参与扣减
    missing: list[MissingIngredient] = Field(default_factory=list)


class CookDeduction(BaseModel):
    """用户确认后提交的一条扣减。"""

    item_id: int
    quantity: float = Field(default=0, ge=0)


class CookRequest(BaseModel):
    """做菜扣库存。

    `deductions` 留空 = 按系统估算扣；
    传了 = 按用户改过的量扣（用户可自由改数量）。
    """

    deductions: list[CookDeduction] | None = None
    # 是否顺带记一笔「做过这道菜」，喂给口味画像
    record_history: bool = True


class CookDeducted(BaseModel):
    name: str
    quantity: float
    unit: str
    remaining: float
    # 扣完为 0，已从冰箱移除
    emptied: bool = False


class CookResult(BaseModel):
    recipe_id: int
    recipe_name: str
    deducted: list[CookDeducted] = Field(default_factory=list)
    # 缺料、或单位对不上又没手填量的，没扣
    skipped: list[str] = Field(default_factory=list)
    note: str = ""
