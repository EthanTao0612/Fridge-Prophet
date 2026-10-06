"""AI 调用配额 —— 给百炼额度上一道保险。

## 背景

后端的接口**原本没有任何限流**，而注册是开放的。
理论上任何人发现域名后注册个账号，就能反复调 `/vision/scan` 和
`/recipes/generate`，**每一次都在花项目自己的百炼额度**
（`qwen-vl-max` 识别一张图约 2 分钱）。

对学生参赛项目来说，「被人刷掉几十块」不是理论风险。

## 两层，缺一不可

| 层 | 作用 | 存哪 |
|---|---|---|
| **按用户** | 防单个账号霸占额度。默认 30 次/天 | 数据库（要准） |
| **全站** | **给账单兜底**。默认 500 次/天 | 数据库（要跨进程准） |

只有按用户限流是不够的 —— 攻击者注册 100 个账号就拿到 100 倍额度。
全站那条才是真正的「最多花多少钱」。

## ⚠️ 为什么配额要落库，而不是放内存

- **多个 uvicorn worker**：放内存的话每个进程各算各的，
  实际额度 = 配置值 × worker 数，等于没限住
- **重启会清零**：内存计数一重启就没了

代价是每次 AI 调用多几次数据库往返（约 700ms）。
**这个代价可以忽略**：AI 调用本身要 2.6~20 秒，
700ms 连零头都算不上。而反过来，配额不准就等于没有配额。

## ⚠️ 已知的并发缺口

「先读再写」不是原子的：两个并发请求可能同时读到 `count = 29`，
于是都放行，实际变成 31 次。**这个偏差是故意的** ——
要修就得加行锁（`SELECT ... FOR UPDATE`）或者做原子 upsert，
那会让每次 AI 调用再多一次往返和一把锁。

对一个「防滥用」而不是「精确计费」的场景，
**多放行一两次完全可接受**，不值得为它引入锁。
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date

from sqlalchemy.orm import Session

from app.core.config import settings
from app.models.ai_usage import GLOBAL_USER_ID, AiUsage

# 每个用户每天能调多少次 AI（拍照识别 + 生成菜谱 合计）
# 可以在 .env 里改：DAILY_AI_LIMIT_PER_USER=100
# 演示前如果怕被限住，把它调大。
DEFAULT_PER_USER = 30

# 全站每天上限 —— **这条才是账单的硬上限**
DEFAULT_GLOBAL = 500


@dataclass(frozen=True)
class QuotaResult:
    """一次配额检查的结果。"""

    allowed: bool
    used: int          # 本次计入之后，该用户的用量
    limit: int
    scope: str         # "user"（个人额度用完）或 "global"（全站额度用完）

    @property
    def remaining(self) -> int:
        return max(0, self.limit - self.used)

    def message(self) -> str:
        if self.allowed:
            return ""
        if self.scope == "global":
            # 给用户看的文案不要暴露「全站」这种内部概念，
            # 但也不能骗人。说「今天的额度用完了」既准确又不吓人。
            return "今天的 AI 额度已经用完了，明天再试。你可以先用「推荐」标签 —— 那里不调 AI。"
        return (
            f"你今天已经用了 {self.used} 次 AI（上限 {self.limit} 次）。"
            "明天恢复。想现在就看菜谱的话，点「推荐」标签 —— 那里是查库，不花额度。"
        )


def _per_user_limit() -> int:
    return int(getattr(settings, "DAILY_AI_LIMIT_PER_USER", DEFAULT_PER_USER)
               or DEFAULT_PER_USER)


def _global_limit() -> int:
    return int(getattr(settings, "DAILY_AI_LIMIT_GLOBAL", DEFAULT_GLOBAL)
               or DEFAULT_GLOBAL)


def _read_counts(db: Session, day: date, user_id: int) -> tuple[int, int]:
    """一次查询把「用户用量」和「全站用量」都读出来。

    分两次查就是两次数据库往返（生产库在孟买，169ms/次），
    而这个函数每次 AI 调用都要跑。
    """
    rows = (
        db.query(AiUsage)
        .filter(AiUsage.day == day, AiUsage.user_id.in_([user_id, GLOBAL_USER_ID]))
        .all()
    )
    by_uid = {r.user_id: r.count for r in rows}
    return by_uid.get(user_id, 0), by_uid.get(GLOBAL_USER_ID, 0)


def _bump(db: Session, day: date, user_id: int, cost: int) -> int:
    """给某一行 +cost，返回加完之后的计数。行不存在就建。"""
    row = (
        db.query(AiUsage)
        .filter(AiUsage.day == day, AiUsage.user_id == user_id)
        .one_or_none()
    )
    if row is None:
        row = AiUsage(day=day, user_id=user_id, count=0)
        db.add(row)
    row.count += cost
    return row.count


def consume_ai_quota(db: Session, user_id: int, *, cost: int = 1) -> QuotaResult:
    """**要调 AI 之前**调这个。放行就顺手把配额扣掉。

    返回 `QuotaResult`，`allowed=False` 时调用方应该返回 429。

    ⚠️ 调用方**必须在真正调 AI 之前**调用它 —— 事后再扣等于没限住
    （攻击者只要并发发请求，全都会先通过）。
    """
    today = date.today()
    user_limit = _per_user_limit()
    global_limit = _global_limit()

    used_user, used_global = _read_counts(db, today, user_id)

    # 两道闸，先检查再写。检查都不通过时**一行都不写** ——
    # 免得被刷的账号把用户表的配额行撑爆。
    if used_user + cost > user_limit:
        return QuotaResult(False, used_user, user_limit, "user")
    if used_global + cost > global_limit:
        return QuotaResult(False, used_user, user_limit, "global")

    new_user = _bump(db, today, user_id, cost)
    _bump(db, today, GLOBAL_USER_ID, cost)
    db.commit()

    return QuotaResult(True, new_user, user_limit, "user")


def quota_status(db: Session, user_id: int) -> QuotaResult:
    """**只看不扣**。给界面显示「今天还剩几次」用。"""
    today = date.today()
    limit = _per_user_limit()
    used, _ = _read_counts(db, today, user_id)
    return QuotaResult(used < limit, used, limit, "user")
