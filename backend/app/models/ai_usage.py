"""AI 调用配额。用来给百炼额度上一道保险。

## 为什么需要这张表

后端的接口**原本没有任何限流**，而注册是开放的 —— 理论上任何人发现域名后，
注册个账号就能反复调 `/vision/scan` 和 `/recipes/generate`，
**每一次都是在花项目自己的百炼额度**（`qwen-vl-max` 识别一张图约 2 分钱）。

对一个学生参赛项目来说，「被人刷掉几十块」不是理论风险，是真会发生的。

## 两层配额

| 层 | 作用 |
|---|---|
| **按用户**（`user_id` 有值） | 防止单个账号霸占额度。默认 30 次/天 |
| **全站**（`user_id = -1`） | **给账单兜底**：就算有人批量注册账号，全站每天最多也就这么多 |

全站那条是最关键的一道 —— 只有按用户限流的话，
攻击者注册 100 个账号就能拿到 100 倍的额度。

## ⚠️ 为什么 `user_id = -1` 而不是 NULL

「全站合计」本来用 NULL 更自然，但 `(user_id, day)` 的唯一约束在
**PostgreSQL 里对 NULL 是失效的**（NULL 互不相等），可以插出无数行。
用 -1 这个哨兵值就没有这个问题。

## ⚠️ 为什么不加外键

`user_id = -1` 不对应任何真实用户，加 `ForeignKey("users.id")` 会直接
违反约束。所以这里**故意不加外键**。
代价是删用户时配额行会留下 —— 它们很小（一行几十字节）且只按天增长，
可以忽略；真要清理就跑一条按 `day` 删旧行的 SQL。
"""
from __future__ import annotations

from datetime import date, datetime, timezone

from sqlalchemy import Date, DateTime, Integer, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.db.session import Base

# 「全站合计」那一行用的哨兵 user_id
GLOBAL_USER_ID = -1


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


class AiUsage(Base):
    """某一天、某个用户（或全站）用掉了多少次 AI 调用。"""

    __tablename__ = "ai_usage"

    # 复合主键：一天一个用户最多一行
    day: Mapped[date] = mapped_column(Date, primary_key=True)
    user_id: Mapped[int] = mapped_column(Integer, primary_key=True)
    count: Mapped[int] = mapped_column(Integer, default=0)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, onupdate=_utcnow
    )

    __table_args__ = (
        UniqueConstraint("day", "user_id", name="uq_ai_usage_day_user"),
    )

    def __repr__(self) -> str:  # pragma: no cover - 调试用
        who = "全站" if self.user_id == GLOBAL_USER_ID else f"user={self.user_id}"
        return f"<AiUsage {self.day} {who} {self.count}>"
