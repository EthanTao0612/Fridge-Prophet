"""自定义折叠箱的请求/响应模型。"""
from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.models.food_category import NAME_MAX_LEN


def _clean_name(v: str) -> str:
    """去掉首尾空白，并拒绝全是空白/换行的名字。

    `min_length=1` 拦不住 `"   "` —— 它长度是 3。不去掉的话，
    用户能手建一个「看起来是空的」折叠箱，界面上只剩一个箭头，很迷惑。
    """
    text = (v or "").strip()
    if not text:
        raise ValueError("折叠箱名字不能为空")
    return text


class FoodCategoryCreate(BaseModel):
    name: str = Field(min_length=1, max_length=NAME_MAX_LEN)
    # 建箱子时可以直接把已有食材放进去（界面上是「勾选几样」）
    inventory_ids: list[int] = Field(default_factory=list, max_length=200)

    @field_validator("name")
    @classmethod
    def _v_name(cls, v: str) -> str:
        return _clean_name(v)


class FoodCategoryUpdate(BaseModel):
    """改名 / 调整顺序。两个字段都可选，只传要改的。"""

    name: str | None = Field(default=None, min_length=1, max_length=NAME_MAX_LEN)
    sort_order: int | None = None

    @field_validator("name")
    @classmethod
    def _v_name(cls, v: str | None) -> str | None:
        return None if v is None else _clean_name(v)


class FoodCategoryItemsIn(BaseModel):
    """批量加入 / 剔除。

    用批量而不是单个：界面上用户是一次勾好几样再点确认的，
    逐个请求既慢又容易半途失败（加进去一半）。
    """

    inventory_ids: list[int] = Field(min_length=1, max_length=200)


class FoodCategoryOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    name: str
    sort_order: int
    created_at: datetime

    # 箱子里的食材 id。客户端拿它和冰箱列表做交集，
    # 比再查一次接口简单，也不会出现两份数据不同步。
    inventory_ids: list[int] = Field(default_factory=list)

    @property
    def item_count(self) -> int:
        return len(self.inventory_ids)
