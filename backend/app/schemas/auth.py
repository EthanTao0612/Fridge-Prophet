"""注册 / 登录 / 令牌。"""
from pydantic import BaseModel, ConfigDict, EmailStr, Field, field_validator


class SendCodeRequest(BaseModel):
    """请求发验证码。

    这个接口是**公开的**（注册前还没有账号），所以字段越少越好 ——
    不要在这里接受「用途」之类的参数，否则会变成通用发信机。
    用途由后端按接口固定。
    """

    email: EmailStr


class SendCodeResponse(BaseModel):
    """发码结果。

    ⚠️ 不管邮箱是否已注册，**响应都长这样**（同样的状态码、同样的字段）。
    如果「已注册」返回 409、「未注册」返回 200，攻击者就能拿这个接口
    批量探测「某个邮箱在不在这个平台上」—— 这叫账号枚举。
    真要区分的话，信息应该发到**邮箱里**，而不是回给请求方。
    """

    sent: bool
    # 距离下次可以发码还有多少秒（客户端用它做倒计时）
    cooldown_seconds: int = 0
    # 给用户看的提示。**不包含**「还剩几次」这类内部信息。
    message: str = ""


class RegisterRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=6, max_length=64)
    nickname: str = Field(default="", max_length=64)
    # 邮箱验证码。**必填** —— 没有它，编造的邮箱也能注册成功。
    code: str = Field(min_length=4, max_length=8)

    @field_validator("code")
    @classmethod
    def _strip_code(cls, v: str) -> str:
        # 用户从邮件里复制粘贴很容易带上首尾空格
        return (v or "").strip()


class LoginRequest(BaseModel):
    email: EmailStr
    password: str


class TokenResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    access_token: str
    token_type: str = "bearer"
    expires_in: int
    user: "UserBrief"


class UserBrief(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    email: str
    nickname: str
    onboarded: bool = False


TokenResponse.model_rebuild()
