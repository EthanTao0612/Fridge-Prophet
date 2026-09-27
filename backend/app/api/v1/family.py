"""家庭组：邀请码加入、成员管理、身份调整。

需求（小组第 1 条）：把「家人」从一份忌口档案升级成真正关联的账号 ——
能邀请别人一起管理冰箱，可以选择对方以什么身份加入，
同一个家庭的人共享冰箱数据和菜谱。

⚠️ **数据共享本身不在这里**。它靠 `family_service.visible_user_ids()`
在各个业务查询里生效（库存 / 菜谱 / 采购）。这个文件只管
「谁在家庭里、什么身份」，两件事分开，改一边不会误伤另一边。
"""
from fastapi import APIRouter, HTTPException, status
from sqlalchemy.orm import Session

from app.api.deps import CurrentUser, DbSession
from app.models.user import Family, FamilyMembership, User
from app.schemas.user import (
    FamilyAccountMember,
    FamilyCreateIn,
    FamilyInviteCodeIn,
    FamilyJoinIn,
    FamilyLeaveOut,
    FamilyOut,
    FamilyRoleUpdateIn,
)
from app.services import family_service as fam

router = APIRouter(prefix="/family", tags=["家庭组"])


def _out(db: Session, family: Family, my_role: str, me_id: int) -> FamilyOut:
    """把家庭渲染成前端要的样子。"""
    rows = (
        db.query(FamilyMembership, User)
        .join(User, User.id == FamilyMembership.user_id)
        .filter(FamilyMembership.family_id == family.id)
        # 家庭主排最前，其余按加入时间 —— 列表顺序稳定，用户才好认人
        .order_by(FamilyMembership.joined_at.asc())
        .all()
    )
    members = [
        FamilyAccountMember(
            user_id=u.id,
            nickname=u.nickname or u.email.split("@")[0],
            email=u.email,
            avatar_url=u.avatar_url,
            role=m.role,
            joined_at=m.joined_at,
            is_me=(u.id == me_id),
        )
        for m, u in rows
    ]
    members.sort(key=lambda x: (x.role != "owner", x.joined_at))

    return FamilyOut(
        id=family.id,
        name=family.name,
        my_role=my_role,
        members=members,
        member_count=len(members),
        # 只读成员看不到邀请码：能看到就能拉人进来，那就不叫只读了
        invite_code=family.invite_code if my_role in ("owner", "member") else None,
    )


def _require_owner(db: Session, user_id: int) -> Family:
    """只有家庭主能做的操作，统一走这里把关。"""
    family, role = fam.my_family(db, user_id)
    if family is None:
        raise HTTPException(status_code=404, detail="你还没有加入任何家庭")
    if role != "owner":
        raise HTTPException(status_code=403, detail="只有家庭主能做这个操作")
    return family


def _check_assignable(role: str) -> str:
    if role not in fam.ASSIGNABLE_ROLES:
        labels = "、".join(fam.ROLE_LABELS[r] for r in fam.ASSIGNABLE_ROLES)
        raise HTTPException(status_code=422, detail=f"身份只能是：{labels}")
    return role


@router.get("", response_model=FamilyOut | None,
            summary="我的家庭（没加入则返回 null）")
def get_my_family(user: CurrentUser, db: DbSession) -> FamilyOut | None:
    """没加入家庭时返回 **null**，不是空对象 ——
    「没有家庭」和「有个空家庭」是两种状态，前端要分得开。"""
    family, role = fam.my_family(db, user.id)
    if family is None:
        return None
    return _out(db, family, role or "member", user.id)


@router.post("", response_model=FamilyOut, status_code=status.HTTP_201_CREATED,
             summary="创建家庭（自己成为家庭主）")
def create_family(payload: FamilyCreateIn, user: CurrentUser, db: DbSession) -> FamilyOut:
    existing, _ = fam.my_family(db, user.id)
    if existing is not None:
        raise HTTPException(status_code=400, detail="你已经在一个家庭里了，要先退出才能建新的")

    family = Family(
        name=(payload.name or "").strip() or "我的家",
        invite_code=fam.new_invite_code(db),
        invite_role="member",
    )
    db.add(family)
    db.flush()
    db.add(FamilyMembership(family_id=family.id, user_id=user.id, role="owner"))
    db.commit()
    db.refresh(family)
    return _out(db, family, "owner", user.id)


@router.post("/join", response_model=FamilyOut, summary="用邀请码加入家庭")
def join_family(payload: FamilyJoinIn, user: CurrentUser, db: DbSession) -> FamilyOut:
    existing, _ = fam.my_family(db, user.id)
    if existing is not None:
        raise HTTPException(
            status_code=400, detail="你已经在一个家庭里了，要先退出才能加入别的家庭"
        )

    code = fam.normalize_code(payload.code)
    family = db.query(Family).filter(Family.invite_code == code).one_or_none()
    if family is None:
        raise HTTPException(status_code=404, detail="邀请码无效，让家人重新发一次给你")

    if len(fam.family_member_ids(db, family.id)) >= fam.MAX_FAMILY_SIZE:
        raise HTTPException(
            status_code=400, detail=f"这个家庭已经满员了（最多 {fam.MAX_FAMILY_SIZE} 人）"
        )

    # 身份由**邀请码**决定，不是由加入者自己选 ——
    # 否则谁都能拿个码把自己加成能改数据的成员，
    # 那家庭主在生成邀请码时选的那个身份就没意义了。
    role = family.invite_role if family.invite_role in fam.ASSIGNABLE_ROLES else "member"
    db.add(FamilyMembership(family_id=family.id, user_id=user.id, role=role))
    db.commit()
    return _out(db, family, role, user.id)


@router.post("/invite-code", response_model=FamilyOut, summary="换一个邀请码（家庭主）")
def regenerate_invite_code(
    payload: FamilyInviteCodeIn, user: CurrentUser, db: DbSession
) -> FamilyOut:
    """换码 + 顺便定「拿这个码加入的人算什么身份」。

    旧码立刻失效 —— 这正是这个接口的用途：码不小心发错群了，换一个就止血。
    """
    family = _require_owner(db, user.id)
    family.invite_code = fam.new_invite_code(db)
    family.invite_role = _check_assignable(payload.role)
    db.commit()
    db.refresh(family)
    return _out(db, family, "owner", user.id)


@router.patch("/members/{member_id}", response_model=FamilyOut,
              summary="调整成员身份（家庭主）")
def update_member_role(
    member_id: int, payload: FamilyRoleUpdateIn, user: CurrentUser, db: DbSession
) -> FamilyOut:
    family = _require_owner(db, user.id)

    if member_id == user.id:
        raise HTTPException(
            status_code=400,
            detail="不能改自己的身份。想换家庭主就先解散家庭再重建 —— 这个版本不支持转让。",
        )

    target = (
        db.query(FamilyMembership)
        .filter(
            FamilyMembership.family_id == family.id,
            FamilyMembership.user_id == member_id,
        )
        .one_or_none()
    )
    if target is None:
        raise HTTPException(status_code=404, detail="这个人不在你的家庭里")

    target.role = _check_assignable(payload.role)
    db.commit()
    return _out(db, family, "owner", user.id)


@router.delete("/members/{member_id}", response_model=FamilyOut,
               summary="把成员移出家庭（家庭主）")
def remove_member(member_id: int, user: CurrentUser, db: DbSession) -> FamilyOut:
    family = _require_owner(db, user.id)

    if member_id == user.id:
        raise HTTPException(status_code=400, detail="要退出家庭请用「退出家庭」，别用移除")

    target = (
        db.query(FamilyMembership)
        .filter(
            FamilyMembership.family_id == family.id,
            FamilyMembership.user_id == member_id,
        )
        .one_or_none()
    )
    if target is None:
        raise HTTPException(status_code=404, detail="这个人不在你的家庭里")

    # 只解除关联。对方自己加的食材和菜谱都留着 ——
    # 被移出家庭不等于东西被没收，那太吓人了。
    db.delete(target)
    db.commit()
    return _out(db, family, "owner", user.id)


@router.post("/leave", response_model=FamilyLeaveOut, summary="退出家庭")
def leave_family(user: CurrentUser, db: DbSession) -> FamilyLeaveOut:
    """成员退出 = 解除关联；家庭主退出 = **解散整个家庭**。

    家庭主不能「一走了之」——那会留下一个没有管理员的家庭，
    剩下的人既改不了设置也没法邀请人。所以要么解散，要么先转交（本版本不支持）。
    解散时其他人的账号和数据都还在，只是不再共享。
    """
    family, role = fam.my_family(db, user.id)
    if family is None:
        raise HTTPException(status_code=404, detail="你还没有加入任何家庭")

    if role == "owner":
        db.query(FamilyMembership).filter(
            FamilyMembership.family_id == family.id
        ).delete(synchronize_session=False)
        db.delete(family)
        db.commit()
        return FamilyLeaveOut(
            dissolved=True,
            message="家庭已解散。其他成员各自的数据都还在，只是不再共享了。",
        )

    db.query(FamilyMembership).filter(
        FamilyMembership.family_id == family.id,
        FamilyMembership.user_id == user.id,
    ).delete(synchronize_session=False)
    db.commit()
    return FamilyLeaveOut(dissolved=False, message="已退出家庭，你自己的数据不受影响。")
