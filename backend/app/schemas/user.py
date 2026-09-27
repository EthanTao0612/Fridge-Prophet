"""用户画像相关模型。"""
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field

from app.schemas.social import PrivacySettingOut


class UserPreferenceIn(BaseModel):
    cuisine: str = "家常菜"
    taste: str = "正常"
    cook_time_max: int = Field(default=30, ge=5, le=240)
    diet_goal: str = "正常饮食"
    disliked_foods: list[str] = Field(default_factory=list)
    allergies: list[str] = Field(default_factory=list)


class UserPreferenceOut(UserPreferenceIn):
    model_config = ConfigDict(from_attributes=True)

    onboarded: bool = False
    updated_at: datetime | None = None


class HealthPreferenceIn(BaseModel):
    low_carb: bool = False
    low_sodium: bool = False
    low_fat: bool = False
    high_protein: bool = False
    high_fiber: bool = False
    vegetarian: bool = False
    low_sugar: bool = False
    high_calcium: bool = False
    high_iron: bool = False
    low_purine: bool = False
    no_raw_food: bool = False
    height_cm: float | None = Field(default=None, ge=80, le=250)
    weight_kg: float | None = Field(default=None, ge=20, le=300)
    age: int | None = Field(default=None, ge=1, le=120)
    activity_level: str | None = None


class HealthPreferenceOut(HealthPreferenceIn):
    model_config = ConfigDict(from_attributes=True)

    updated_at: datetime | None = None


class FamilyMemberIn(BaseModel):
    name: str = Field(min_length=1, max_length=64)
    relation: str = ""
    diet_goal: str = "正常饮食"
    taste: str = "正常"
    disliked_foods: list[str] = Field(default_factory=list)
    allergies: list[str] = Field(default_factory=list)
    note: str | None = None


class FamilyMemberOut(FamilyMemberIn):
    model_config = ConfigDict(from_attributes=True)

    id: int


class UserOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    email: str
    nickname: str
    avatar_url: str | None = None
    bio: str | None = None
    created_at: datetime


class ProfileUpdate(BaseModel):
    nickname: str | None = Field(default=None, max_length=64)
    avatar_url: str | None = Field(default=None, max_length=512)
    bio: str | None = Field(
        default=None, max_length=200, description="个性简介，最长 200 字；传空字符串可清空"
    )


class ProfileOut(BaseModel):
    user: UserOut
    preference: UserPreferenceOut
    health: HealthPreferenceOut
    family_members: list[FamilyMemberOut] = Field(default_factory=list)
    # 公开性开关。跟着 /users/profile 一起返回，省掉个人页的一次额外请求 ——
    # 个人页要渲染那些开关，多一次往返就多一次加载态闪烁。
    privacy: PrivacySettingOut | None = None


# ---------------------------------------------------------------
#  家庭组（账号关联）
#
#  注意和上面 FamilyMemberIn/Out 的区别：那是「忌口档案」，
#  这是「真实账号」。两者并存，见 models/user.py 的说明。
# ---------------------------------------------------------------


class FamilyAccountMember(BaseModel):
    """家庭里的一位成员 —— 一个真实账号。"""

    user_id: int
    nickname: str
    email: str
    avatar_url: str | None = None
    role: str = "member"
    joined_at: datetime
    # 前端据此把「我」标出来，并在自己那行隐藏「移出」按钮
    is_me: bool = False


class FamilyOut(BaseModel):
    """我的家庭。

    ⚠️ **不要改回「没家庭时返回裸 null」**。

    一开始就是那么写的（`response_model=FamilyOut | None`），看着很干净，
    但客户端根本吃不下：Retrofit + kotlinx-serialization 拿到的序列化器是
    **非空**的（Kotlin 的可空标记在 Java 的 `Type` 里丢了），
    给它喂一个字面量 `null` 会直接抛 `JsonDecodingException`。
    结果是「没加入家庭的用户打开家庭页 → 看到『出错了』」，
    而不是本该显示的创建/加入入口。这个 bug 只在真机上才暴露，
    靠 `FamilyContractTest` 用 MockWebServer 才抓出来。

    现在改成永远返回对象，用 `joined` 当显式开关：
    「有没有家庭」是一个**字段**，不是一个可能不存在的响应体。
    """

    # 没加入任何家庭时是 false，此时其余字段都是空值。
    joined: bool = True

    id: int
    name: str
    my_role: str
    members: list[FamilyAccountMember] = Field(default_factory=list)
    member_count: int = 0
    # 邀请码。只读成员看不到 —— 能看就能拉人进来，那就不叫只读了。
    invite_code: str | None = None


class FamilyCreateIn(BaseModel):
    name: str = Field(default="我的家", max_length=64)


class FamilyJoinIn(BaseModel):
    code: str = Field(min_length=1, max_length=16, description="家人给你的邀请码")


class FamilyRoleUpdateIn(BaseModel):
    role: str = Field(description="owner / member / viewer")


class FamilyInviteCodeIn(BaseModel):
    """换邀请码时顺便定「拿这个码加入的人算什么身份」。

    需求里的「可以选择被邀请人以什么身份加入」就落在这里 ——
    家庭主发码之前先想好给对方多大权限，而不是等人进来了再补。
    """

    role: str = Field(default="member", description="owner / member / viewer")


class FamilyLeaveOut(BaseModel):
    """退出/解散的结果说明。

    家庭主退出 = 解散家庭（其他成员会失去共享权限，但各自的数据都还在），
    这件事必须让用户看见，不能默默发生。
    """

    dissolved: bool = False
    message: str = ""
