"""FastAPI 依赖：当前用户、数据库会话、分页。"""
from typing import Annotated

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session

from app.core.security import decode_access_token
from app.db.session import get_db
from app.models.user import PrivacySetting, User, UserPreference

bearer_scheme = HTTPBearer(auto_error=False)

DbSession = Annotated[Session, Depends(get_db)]


def get_current_user(
    db: DbSession,
    creds: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer_scheme)] = None,
) -> User:
    unauthorized = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="未登录或登录已过期",
        headers={"WWW-Authenticate": "Bearer"},
    )
    if creds is None or not creds.credentials:
        raise unauthorized

    payload = decode_access_token(creds.credentials)
    if not payload or not payload.get("sub"):
        raise unauthorized

    try:
        user_id = int(payload["sub"])
    except (TypeError, ValueError):
        raise unauthorized from None

    user = db.get(User, user_id)
    if user is None or not user.is_active:
        raise unauthorized
    return user


CurrentUser = Annotated[User, Depends(get_current_user)]


def guard_ai_quota(db: Session, user_id: int, *, cost: int = 1) -> None:
    """**调 AI 之前的守门人**。额度用完就抛 429。

    ## 为什么所有调 AI 的接口都要经过这里

    AI 是**唯一会花真钱**的东西（百炼按调用计费）。
    漏掉一处就等于留了一个免费刷额度的口子 ——
    而「漏掉一处」很难靠 review 发现，因为接口是散在各文件里的。

    所以统一成这一个函数，加新接口时**先想一下它调不调 AI**，
    调就加上这一行。

    ## 为什么放在 deps.py

    它和 `CurrentUser` / `DbSession` 一样，是「接口的横切关注点」，
    而且需要 `db` 和 `user_id` 这两样东西 —— 放在这里调用方最省事。

    ## 返回 429 而不是 403

    429 Too Many Requests 是标准语义，客户端能据此区分
    「没权限」和「用超了」。客户端也可以据此显示一个专门的提示。
    """
    from app.services.quota_service import consume_ai_quota

    result = consume_ai_quota(db, user_id, cost=cost)
    if not result.allowed:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=result.message(),
            # 客户端可以用它做倒计时（这里就是「明天再来」）
            headers={"Retry-After": "86400"},
        )


def ai_quota_left(db: Session, user_id: int) -> int:
    """今天还剩几次 AI 调用。**只看不扣**，给界面显示用。"""
    from app.services.quota_service import quota_status

    return quota_status(db, user_id).remaining


def get_or_create_preference(db: Session, user_id: int) -> UserPreference:
    pref = db.query(UserPreference).filter(UserPreference.user_id == user_id).one_or_none()
    if pref is None:
        pref = UserPreference(user_id=user_id)
        db.add(pref)
        db.commit()
        db.refresh(pref)
    return pref


def get_or_create_privacy(db: Session, user_id: int) -> PrivacySetting:
    """取公开性开关，没有就按**全关**建一个。

    做成「用时创建」而不是注册时创建：老账号（这个功能上线之前注册的）
    数据库里没有对应行，如果只依赖注册流程写入，老用户一进广场就会 500。
    全关是安全默认值 —— 见 models/user.py 里 PrivacySetting 的说明。
    """
    row = db.query(PrivacySetting).filter(PrivacySetting.user_id == user_id).one_or_none()
    if row is None:
        row = PrivacySetting(user_id=user_id)
        db.add(row)
        db.commit()
        db.refresh(row)
    return row
