"""注册 / 登录 / 当前用户。"""
import logging

from fastapi import APIRouter, HTTPException, status
from sqlalchemy.exc import IntegrityError

from app.api.deps import CurrentUser, DbSession, get_or_create_preference
from app.core.config import settings
from app.core.security import create_access_token, hash_password, verify_password
from app.models.user import HealthPreference, User
from app.schemas.auth import (
    LoginRequest,
    RegisterRequest,
    SendCodeRequest,
    SendCodeResponse,
    TokenResponse,
    UserBrief,
)
from app.schemas.user import UserOut
from app.services.email_service import issue_code, send_code_email, verify_code

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/auth", tags=["鉴权"])

# 客户端拿到这个值后开始倒计时。和 email_service.SEND_COOLDOWN 保持一致 ——
# 这里返回给客户端是为了让按钮的倒计时和服务器一致，而不是客户端自己猜。
COOLDOWN_HINT = 60


def _token_for(user: User, db: DbSession) -> TokenResponse:
    pref = get_or_create_preference(db, user.id)
    token = create_access_token(user.id)
    return TokenResponse(
        access_token=token,
        expires_in=settings.ACCESS_TOKEN_EXPIRE_MINUTES * 60,
        user=UserBrief(
            id=user.id, email=user.email, nickname=user.nickname, onboarded=pref.onboarded
        ),
    )


@router.post("/send-code", response_model=SendCodeResponse, summary="发送邮箱验证码")
def send_code(payload: SendCodeRequest, db: DbSession) -> SendCodeResponse:
    """给邮箱发注册验证码。

    ## 这个接口是公开的

    注册前还没有账号，所以不能要求登录。代价是任何人都能调 ——
    很容易被当成免费发信机去轰炸别人的邮箱。防滥用靠三道闸，
    都在 `email_service.issue_code()` 里：

    - 同邮箱 60 秒冷却
    - 同邮箱每天 10 次上限
    - 验证码 5 分钟过期

    ## ⚠️ 不做账号枚举

    **邮箱已注册时也返回成功**（只是不发码），响应和正常情况完全一样。
    否则攻击者能拿这个接口批量探测「某个邮箱在不在这个平台上」。

    真要告诉用户「你已注册」，那句话应该发到**邮箱里**，不是回给请求方。
    """
    email = payload.email.strip().lower()

    # 已注册的邮箱：不发码，但**响应要和正常情况一致**（见上面的说明）
    if db.query(User).filter(User.email == email).one_or_none() is not None:
        logger.info("已注册邮箱请求验证码，静默跳过：%s", email)
        return SendCodeResponse(sent=True, cooldown_seconds=COOLDOWN_HINT,
                                message="如果这个邮箱可用，验证码已发送")

    code, message = issue_code(db, email, "register")
    if code is None:
        # 被限流了。这里**如实**返回 429 —— 限流信息不涉及账号是否存在，
        # 不构成枚举风险，而且用户需要知道自己被限流了。
        raise HTTPException(status_code=429, detail=message)

    delivered, note = send_code_email(email, code, "register")
    return SendCodeResponse(
        sent=True,
        cooldown_seconds=COOLDOWN_HINT,
        message="验证码已发送，请查收邮件" if delivered else note,
    )


@router.post("/register", response_model=TokenResponse, status_code=status.HTTP_201_CREATED,
             summary="注册并直接返回登录令牌")
def register(payload: RegisterRequest, db: DbSession) -> TokenResponse:
    """注册。

    ⚠️ **必须带邮箱验证码**。以前只校验邮箱格式（`EmailStr`），
    结果是 `a@b.com`、`test@nowhere-xyz-12345.com` 这种编造的地址
    都能注册成功 —— 邮箱那一栏形同虚设。
    """
    email = payload.email.strip().lower()

    # 先查重再验码：已经注册的邮箱没必要消耗一次验证码尝试次数。
    # （顺序反过来会让「已注册」这个错误晚一步暴露，而且白费用户一次机会）
    if db.query(User).filter(User.email == email).one_or_none() is not None:
        raise HTTPException(status_code=409, detail="该邮箱已注册，直接登录即可")

    ok, reason = verify_code(db, email, payload.code, "register")
    if not ok:
        # 400 而不是 401 —— 401 会触发客户端的「登录失效」处理，把用户踢回登录页
        raise HTTPException(status_code=400, detail=reason)

    user = User(
        email=email,
        nickname=payload.nickname or email.split("@")[0],
        hashed_password=hash_password(payload.password),
    )
    db.add(user)
    try:
        db.flush()
    except IntegrityError:
        db.rollback()
        raise HTTPException(status_code=409, detail="该邮箱已注册，直接登录即可") from None

    # 顺手建好画像记录，避免后续到处判空
    db.add(HealthPreference(user_id=user.id))
    db.commit()
    db.refresh(user)
    return _token_for(user, db)


@router.post("/login", response_model=TokenResponse, summary="登录")
def login(payload: LoginRequest, db: DbSession) -> TokenResponse:
    user = db.query(User).filter(User.email == payload.email).one_or_none()
    if user is None or not verify_password(payload.password, user.hashed_password):
        raise HTTPException(status_code=401, detail="邮箱或密码不正确")
    if not user.is_active:
        raise HTTPException(status_code=403, detail="账号已被停用")
    return _token_for(user, db)


@router.get("/me", response_model=UserOut, summary="获取当前登录用户")
def me(user: CurrentUser) -> User:
    return user
