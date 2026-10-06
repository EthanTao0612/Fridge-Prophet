"""家庭组：账号之间的关联，以及「谁的数据算我的」。

这个模块存在的唯一理由，是把**数据可见范围**这件事收敛到一处。

以前每个查询都是 `filter(xxx.user_id == user.id)`。加入家庭之后，
「我的冰箱」变成了「我和家人的冰箱」，如果让各处自己改成
`user_id.in_(...)`，那家庭功能一旦调整（比如加个「只看自己的」开关），
就得把每个查询再翻一遍 —— 而且总会漏掉一两个，
表现就是「冰箱页看得到家人的食材，菜谱页却看不到」这种自相矛盾。

所以：**所有跨用户的查询都必须走 `visible_user_ids()`**。
"""
from __future__ import annotations

import secrets

from sqlalchemy.orm import Session

from app.models.user import FAMILY_ROLES, Family, FamilyMembership

# 邀请码字符表。故意去掉 I / O / 0 / 1 ——
# 这东西多半是当面念给对方、或者截图发过去的，
# 「是数字 0 还是字母 O」这种问题不该由用户来猜。
CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
CODE_LENGTH = 6

# 家庭人数上限。不是为了省资源，是为了让「一桌饭」这个前提还成立 ——
# 二十个人的口味冲突，推荐算法给不出有意义的结果。
MAX_FAMILY_SIZE = 8

# 能分配给别人的身份。**故意不含 owner**：
# 转让所有权是另一件事（要处理原主人降级、避免出现两个主人或没主人），
# 这次不做。想换家庭主就先解散重建，简单且不会留下半吊子状态。
ASSIGNABLE_ROLES = ("member", "viewer")

ROLE_LABELS = {
    "owner": "家庭主",
    "member": "成员",
    "viewer": "只读",
}


def my_family(db: Session, user_id: int) -> tuple[Family | None, str | None]:
    """返回 (我的家庭, 我的身份)。没加入任何家庭时是 (None, None)。

    一个人最多在一个家庭里（`FamilyMembership.user_id` 上有唯一约束），
    所以这里不需要处理「多个家庭选哪个」。
    """
    membership = (
        db.query(FamilyMembership)
        .filter(FamilyMembership.user_id == user_id)
        .one_or_none()
    )
    if membership is None:
        return None, None
    family = db.query(Family).filter(Family.id == membership.family_id).one_or_none()
    if family is None:
        # 理论上外键保证不会发生；真发生了就当没家庭，别让整个接口 500
        return None, None
    return family, membership.role


def visible_user_ids(db: Session, user_id: int) -> list[int]:
    """**这个用户能看到哪些人的数据。**

    ⚠️ 全项目唯一实现。冰箱 / 菜谱 / 采购清单的查询都要用它。

    没加入家庭时返回 `[user_id]`，所以调用方不用写
    「先判断有没有家庭」那一套 —— 少一个分支就少一处能写错的地方。

    ## 为什么在 Session 上缓存

    生产库在 Supabase（孟买），**一次往返实测 169ms**。
    而这个函数在**同一个请求里会被调好几次** ——
    比如菜谱详情：算可见范围一次、取库存又一次，就是白白多花 169ms。

    缓存挂在 `db.info` 上（SQLAlchemy 给每个 Session 预留的用户字典），
    所以**生命周期正好是一个请求** —— 请求结束 Session 关掉，缓存自然没了，
    不存在「家庭关系改了还读到旧值」的问题。

    加家庭 / 退家庭之后如果同一个请求里还要重新读，那属于另一件事，
    现在没有这种流程。
    """
    cache: dict = db.info.setdefault("_visible_user_ids_cache", {})
    if user_id in cache:
        return cache[user_id]

    membership = (
        db.query(FamilyMembership)
        .filter(FamilyMembership.user_id == user_id)
        .one_or_none()
    )
    if membership is None:
        result = [user_id]
    else:
        rows = (
            db.query(FamilyMembership.user_id)
            .filter(FamilyMembership.family_id == membership.family_id)
            .all()
        )
        # 把自己兜进去：万一 membership 表有脏数据，至少不会看不到自己的东西
        result = sorted({r[0] for r in rows} | {user_id})

    cache[user_id] = result
    return result


def can_write(db: Session, user_id: int) -> bool:
    """能不能改共享数据。

    只读成员（viewer）看得到但不能动 —— 这是邀请时选的那个身份。
    没加入家庭的人当然可以改自己的东西。
    """
    _, role = my_family(db, user_id)
    return role != "viewer"


def family_member_ids(db: Session, family_id: int) -> list[int]:
    rows = (
        db.query(FamilyMembership.user_id)
        .filter(FamilyMembership.family_id == family_id)
        .all()
    )
    return sorted({r[0] for r in rows})


def new_invite_code(db: Session) -> str:
    """生成一个没被占用的邀请码。"""
    for _ in range(20):
        code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(CODE_LENGTH))
        taken = db.query(Family.id).filter(Family.invite_code == code).first()
        if taken is None:
            return code
    # 6 位 32 进制有十亿种组合，连撞 20 次基本只可能是随机源坏了
    raise RuntimeError("邀请码生成失败，请重试")


def normalize_code(raw: str) -> str:
    """用户输入的邀请码统一处理：去空格、转大写。

    抄邀请码时很容易带上空格，或者小写输入 —— 这些都不该导致「邀请码无效」。
    """
    return "".join((raw or "").split()).upper()


def is_valid_role(role: str) -> bool:
    return role in FAMILY_ROLES
