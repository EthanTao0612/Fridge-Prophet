"""邮箱验证码。

## 为什么单独一张表而不是放缓存

理论上验证码放 Redis / 内存字典更快。但这个项目**没有 Redis**
（免费版 Supabase 也不带），而且验证码的数据量极小（一天最多几十条）。

放数据库的好处：
- 不需要新组件
- 服务重启后验证码**还在**（放内存的话重启就全失效，用户白等 60 秒）
- 天然有审计记录（谁在什么时候请求过验证码）

## 安全考虑

- **存哈希不存明文**：库被看到也拿不到可用的验证码
- **不存「用户是否已验证」这种状态**：验证通过后验证码就作废了，
  注册接口自己判断邮箱是否已被占用。少一个状态就少一处不一致。
"""
from __future__ import annotations

from datetime import datetime, timezone

from sqlalchemy import DateTime, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from app.db.session import Base

# 验证码位数。6 位是行业惯例 —— 4 位太容易被猜到，8 位用户嫌长。
CODE_LENGTH = 6

# 用途。现在只有注册，以后加「改密码」「换邮箱」时在这里加。
PURPOSES = ("register", "reset", "bind")


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


class EmailVerification(Base):
    """一次验证码请求的记录。"""

    __tablename__ = "email_verifications"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    # 小写存储 —— 邮箱大小写不敏感，不统一的话 a@x.com 和 A@X.com
    # 会被当成两个不同的目标，限流就绕过去了。
    email: Mapped[str] = mapped_column(String(255), index=True)

    # 验证码的 sha256（加了邮箱当盐）。**不存明文。**
    code_hash: Mapped[str] = mapped_column(String(64))

    purpose: Mapped[str] = mapped_column(String(16), default="register")

    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)
    # 非空表示已用过（或已被新码作废）。一个码只能用一次。
    used_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    # 已经试了几次。超过上限就作废，防暴力猜。
    attempts: Mapped[int] = mapped_column(Integer, default=0)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, index=True
    )
