"""广场（社区）：动态、点赞、评论、关注、他人主页。

## 鉴权策略

整个模块**全部需要登录**，和贴士（`/tips`）不一样。
理由：广场的每一条返回体都要带 `liked_by_me` / `is_mine` / `followed_by_me`
这类「相对于当前请求者」的字段。没有身份就无从计算，返回一个阉割版
反而会让客户端多写一套分支。

## ⚠️ 本模块**不能**加 `from __future__ import annotations`

这个文件里有 204 的 DELETE 路由，而加上那个 future 导入会让它们全部启动失败：

    加上之后，`def f(...) -> None` 的注解变成**字符串** `'None'`。
    FastAPI 求值 `'None'` 拿到的是 `NoneType` 这个**类对象**（真值），
    而不是 `None`（假值），于是它认为「204 路由声明了响应体」并断言失败：

        AssertionError: Status code 204 must not have a response body

    不加 future 导入时，注解就是字面量 `None`，假值，一切正常。

其他模块（app/api/v1/ 下除 tips.py 外）都没有这个导入，保持了一致。
`app/services/` 下有几个模块加了，它们不定义路由，没有这个问题。
"""
import uuid
from pathlib import Path

from fastapi import APIRouter, File, HTTPException, Query, UploadFile, status
from sqlalchemy import func

from app.api.deps import CurrentUser, DbSession, get_or_create_privacy
from app.data.health_goals import HEALTH_DISCLAIMER, HEALTH_GOAL_LABELS
from app.models.recipe import MealHistory, Recipe
from app.models.social import Follow, Post, PostComment, PostLike
from app.models.user import FamilyMember, HealthPreference, User
from app.schemas.social import (
    SORT_LABELS,
    CommentCreate,
    CommentListOut,
    CommentOut,
    FeedOut,
    FollowResultOut,
    FollowUserOut,
    ImageUploadOut,
    LikeResultOut,
    PostCreate,
    PostOut,
    PublicProfileOut,
    ShareResultOut,
    UserBrief,
)
from app.services import social_service as svc
from app.services.recipe_service import readable_recipe_clause
from app.services.storage_service import upload_image

router = APIRouter(prefix="/social", tags=["广场"])

POST_IMAGE_MIME = {"image/jpeg", "image/jpg", "image/png", "image/webp"}
POST_IMAGE_MAX_BYTES = 8 * 1024 * 1024
POST_IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp"}


def _get_post_or_404(db, post_id: int) -> Post:
    post = db.get(Post, post_id)
    if post is None:
        raise HTTPException(status_code=404, detail="动态不存在或已被删除")
    return post


def _brief(user) -> UserBrief:
    return UserBrief(
        id=user.id,
        nickname=user.nickname or f"用户{user.id}",
        avatar_url=user.avatar_url,
        bio=user.bio,
    )


# ---------------------------------------------------------------- 动态


@router.post("/posts", response_model=PostOut, status_code=status.HTTP_201_CREATED,
             summary="发布动态")
def create_post(payload: PostCreate, user: CurrentUser, db: DbSession) -> PostOut:
    """发布一条动态。

    **允许「纯文字」和「纯图片」**，但不允许两者都空 —— 一条什么都没有的动态
    在列表里就是一个空白卡片，看起来像加载失败。

    关联菜谱时，菜名和做法会**复制一份**存进动态（见 models/social.py 的说明），
    所以这里要校验 `recipe_id` 确实属于当前用户：不能引用别人的菜谱，
    否则等于把别人菜谱库里的内容发到了广场。
    """
    content = (payload.content or "").strip()
    if not content and not payload.image_url:
        raise HTTPException(status_code=400, detail="说点什么，或者配张图")

    recipe_name = payload.recipe_name
    steps = list(payload.steps or [])

    if payload.recipe_id is not None:
        # 可见范围用 `readable_recipe_clause`（含系统内置的菜品库）。
        #
        # ⚠️ 以前这里写的是 `Recipe.user_id == user.id`（只能分享自己的）。
        # 菜品库那 192 道变成内置菜谱之后，那个条件会**误伤**：
        # 发帖界面能选到这些菜，选完提交却报 404「找不到这道菜」——
        # 用户会觉得「你明明让我选的」。
        #
        # 而且「我照着菜品库做了清炒西兰花，发个帖」本来就该允许。
        recipe = (
            db.query(Recipe)
            .filter(Recipe.id == payload.recipe_id, readable_recipe_clause(db, user.id))
            .one_or_none()
        )
        if recipe is None:
            raise HTTPException(status_code=404, detail="找不到这道菜，刷新一下再试试")
        # 以库里的数据为准，不采信客户端传来的菜名和步骤
        recipe_name = recipe.name
        steps = [str(s) for s in (recipe.steps or [])]

    post = Post(
        user_id=user.id,
        content=content,
        image_url=payload.image_url,
        recipe_id=payload.recipe_id,
        recipe_name=recipe_name,
        steps=steps,
        tags=[t.strip() for t in (payload.tags or []) if t.strip()][:8],
    )
    db.add(post)
    db.commit()
    db.refresh(post)
    return svc.to_out(post, user.id, set())


@router.get("/feed", response_model=FeedOut, summary="广场信息流（热门 / 最新 / 综合）")
def feed(
    user: CurrentUser,
    db: DbSession,
    sort: str = Query(default="composite", description="latest / hot / composite"),
    limit: int = Query(default=20, ge=1, le=50),
    offset: int = Query(default=0, ge=0),
    only_following: bool = Query(default=False, description="只看我关注的人"),
) -> FeedOut:
    if sort not in SORT_LABELS:
        raise HTTPException(status_code=400, detail=f"不支持的排序：{sort}")

    followed = svc.fetch_followed_ids(db, user.id)
    pool = svc.load_pool(db, only_following_of=user.id if only_following else None)
    ranked = svc.rank(pool, sort, svc.now(), followed)

    page = ranked[offset : offset + limit]
    liked = svc.fetch_liked_ids(db, user.id, [p.id for p in page])

    return FeedOut(
        items=[svc.to_out(p, user.id, liked) for p in page],
        total=len(ranked),
        offset=offset,
        limit=limit,
        has_more=offset + limit < len(ranked),
        sort=sort,
        sort_label=SORT_LABELS[sort],
        sort_options=svc.sort_options(),
    )


@router.get("/posts/{post_id}", response_model=PostOut, summary="动态详情")
def get_post(post_id: int, user: CurrentUser, db: DbSession) -> PostOut:
    post = _get_post_or_404(db, post_id)
    liked = svc.fetch_liked_ids(db, user.id, [post.id])
    return svc.to_out(post, user.id, liked)


@router.delete("/posts/{post_id}", status_code=status.HTTP_204_NO_CONTENT,
               summary="删除自己的动态")
def delete_post(post_id: int, user: CurrentUser, db: DbSession) -> None:
    """只能删自己的。删别人的返回 404 而不是 403 —— 403 等于确认「这条存在但不归你」，
    对一个只想删帖的人来说，两者没有区别，但前者少泄露一点信息。"""
    post = (
        db.query(Post).filter(Post.id == post_id, Post.user_id == user.id).one_or_none()
    )
    if post is None:
        raise HTTPException(status_code=404, detail="动态不存在或不是你的")

    # 显式删子行，不依赖数据库的 ON DELETE CASCADE：
    # SQLite 默认不开外键约束，靠级联会留下孤儿点赞记录，
    # 之后同一个人再点赞同一条帖子就会撞唯一约束。
    db.query(PostLike).filter(PostLike.post_id == post_id).delete(synchronize_session=False)
    db.query(PostComment).filter(PostComment.post_id == post_id).delete(synchronize_session=False)
    db.delete(post)
    db.commit()


# ---------------------------------------------------------------- 点赞


@router.post("/posts/{post_id}/like", response_model=LikeResultOut, summary="点赞")
def like_post(post_id: int, user: CurrentUser, db: DbSession) -> LikeResultOut:
    post = _get_post_or_404(db, post_id)
    exists = (
        db.query(PostLike.id)
        .filter(PostLike.post_id == post_id, PostLike.user_id == user.id)
        .first()
    )
    if exists is None:
        db.add(PostLike(post_id=post_id, user_id=user.id))
        post.like_count = post.like_count + 1
        db.commit()
        db.refresh(post)
    return LikeResultOut(post_id=post_id, liked=True, like_count=post.like_count)


@router.delete("/posts/{post_id}/like", response_model=LikeResultOut, summary="取消点赞")
def unlike_post(post_id: int, user: CurrentUser, db: DbSession) -> LikeResultOut:
    post = _get_post_or_404(db, post_id)
    removed = (
        db.query(PostLike)
        .filter(PostLike.post_id == post_id, PostLike.user_id == user.id)
        .delete(synchronize_session=False)
    )
    if removed:
        # 用 max(0, ...) 兜底：计数是冗余列，万一历史上算错过，
        # 取消一次赞也不该让界面显示出负数。
        post.like_count = max(0, post.like_count - removed)
        db.commit()
        db.refresh(post)
    return LikeResultOut(post_id=post_id, liked=False, like_count=post.like_count)


@router.post("/posts/{post_id}/share", response_model=ShareResultOut, summary="记一次分享")
def share_post(post_id: int, user: CurrentUser, db: DbSession) -> ShareResultOut:
    """客户端点了「分享」并调起系统分享面板后调用，只累加计数。

    服务端**不主动外发**任何内容：分享到哪个 App 由系统面板决定，
    后端无从知晓也不该假装知晓。
    """
    post = _get_post_or_404(db, post_id)
    post.share_count = post.share_count + 1
    db.commit()
    db.refresh(post)

    author = post.author.nickname or f"用户{post.author.id}"
    head = post.content.strip().splitlines()[0] if post.content.strip() else ""
    text = f"{author} 在冰箱先知分享了"
    text += f"「{post.recipe_name}」" if post.recipe_name else "一条动态"
    if head:
        text += f"：{head[:60]}"
    return ShareResultOut(post_id=post_id, share_count=post.share_count, share_text=text)


# ---------------------------------------------------------------- 评论


@router.get("/posts/{post_id}/comments", response_model=CommentListOut, summary="评论列表")
def list_comments(post_id: int, user: CurrentUser, db: DbSession) -> CommentListOut:
    _get_post_or_404(db, post_id)
    rows = (
        db.query(PostComment)
        .filter(PostComment.post_id == post_id)
        .order_by(PostComment.created_at.asc(), PostComment.id.asc())
        .all()
    )
    return CommentListOut(
        items=[
            CommentOut(
                id=c.id, author=_brief(c.author), content=c.content,
                created_at=c.created_at, is_mine=c.user_id == user.id,
            )
            for c in rows
        ],
        total=len(rows),
    )


@router.post("/posts/{post_id}/comments", response_model=CommentOut,
             status_code=status.HTTP_201_CREATED, summary="发表评论")
def add_comment(
    post_id: int, payload: CommentCreate, user: CurrentUser, db: DbSession
) -> CommentOut:
    post = _get_post_or_404(db, post_id)
    content = payload.content.strip()
    if not content:
        raise HTTPException(status_code=400, detail="评论不能为空")

    comment = PostComment(post_id=post_id, user_id=user.id, content=content)
    db.add(comment)
    post.comment_count = post.comment_count + 1
    db.commit()
    db.refresh(comment)
    return CommentOut(
        id=comment.id, author=_brief(user), content=comment.content,
        created_at=comment.created_at, is_mine=True,
    )


@router.delete("/comments/{comment_id}", status_code=status.HTTP_204_NO_CONTENT,
               summary="删除评论（本人或动态作者）")
def delete_comment(comment_id: int, user: CurrentUser, db: DbSession) -> None:
    """动态作者也能删自己帖子下的评论 —— 这是社区产品的基本要求，
    否则别人在你帖子下刷广告你只能干看着。"""
    comment = db.get(PostComment, comment_id)
    if comment is None:
        raise HTTPException(status_code=404, detail="评论不存在")

    post = db.get(Post, comment.post_id)
    is_comment_owner = comment.user_id == user.id
    is_post_owner = post is not None and post.user_id == user.id
    if not (is_comment_owner or is_post_owner):
        raise HTTPException(status_code=404, detail="评论不存在")

    db.delete(comment)
    if post is not None:
        post.comment_count = max(0, post.comment_count - 1)
    db.commit()


# ---------------------------------------------------------------- 配图


@router.post("/image", response_model=ImageUploadOut, summary="上传动态配图")
async def upload_post_image(
    user: CurrentUser,
    file: UploadFile = File(..., description="JPG / PNG / WEBP，不超过 8MB"),
) -> ImageUploadOut:
    mime = (file.content_type or "").lower()
    if mime not in POST_IMAGE_MIME:
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail=f"不支持的图片格式：{mime or '未知'}，请上传 JPG / PNG / WEBP",
        )

    raw = await file.read()
    if not raw:
        raise HTTPException(status_code=400, detail="上传的文件是空的")
    if len(raw) > POST_IMAGE_MAX_BYTES:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail="图片不能超过 8MB，请在客户端压缩后再上传",
        )

    suffix = Path(file.filename or "").suffix.lower()
    if suffix not in POST_IMAGE_SUFFIXES:
        suffix = ".jpg"
    path = f"posts/{user.id}/{uuid.uuid4().hex[:16]}{suffix}"

    url = upload_image(raw, path, mime)
    if url is None:
        raise HTTPException(status_code=500, detail="图片保存失败，请稍后重试")
    return ImageUploadOut(url=url)


# ---------------------------------------------------------------- 关注


@router.get("/users/search", response_model=list[FollowUserOut], summary="按昵称找人")
def search(
    user: CurrentUser,
    db: DbSession,
    q: str = Query(min_length=1, max_length=32),
    limit: int = Query(default=20, ge=1, le=50),
) -> list[FollowUserOut]:
    followed = svc.fetch_followed_ids(db, user.id)
    return [
        FollowUserOut(
            user=_brief(u),
            followed_by_me=u.id in followed,
            post_count=svc.post_count(db, u.id),
        )
        for u in svc.search_users(db, q, limit)
        if u.id != user.id  # 搜到自己没意义，关注按钮点了也不会有效果
    ]


@router.get("/me/following-ids", response_model=list[int], summary="我关注的人的 id")
def my_following_ids(user: CurrentUser, db: DbSession) -> list[int]:
    """客户端进广场时拉一次，用来把已关注的人的按钮渲染成「已关注」。"""
    return sorted(svc.fetch_followed_ids(db, user.id))


@router.get("/users/{user_id}", response_model=PublicProfileOut, summary="看别人的主页")
def public_profile(user_id: int, user: CurrentUser, db: DbSession) -> PublicProfileOut:
    """按对方的公开开关裁剪内容。

    ⚠️ 这里是**唯一的**裁剪点。所有「别人的主页」都走这个接口，
    不要另开一个接口再写一遍判断 —— 那样迟早有一边忘了加开关，
    把健康信息漏出去。
    """
    target = db.get(User, user_id)
    if target is None or not target.is_active:
        raise HTTPException(status_code=404, detail="用户不存在")

    privacy = get_or_create_privacy(db, target.id)
    is_me = target.id == user.id
    # 本人看自己：不受开关限制，否则用户改完设置自己都看不到自己填了什么
    show = lambda flag: True if is_me else bool(flag)  # noqa: E731

    preference = health = None
    family: list[dict] | None = None

    if show(privacy.share_preference):
        pref = target.preference
        if pref is not None:
            preference = {
                "cuisine": pref.cuisine,
                "taste": pref.taste,
                "cook_time_max": pref.cook_time_max,
                "diet_goal": pref.diet_goal,
                "disliked_foods": list(pref.disliked_foods or []),
                "allergies": list(pref.allergies or []),
            }

    if show(privacy.share_health):
        row = (
            db.query(HealthPreference)
            .filter(HealthPreference.user_id == target.id)
            .one_or_none()
        )
        if row is not None:
            health = {
                "goals": [
                    {"key": key, "label": label}
                    for key, label in HEALTH_GOAL_LABELS.items()
                    if getattr(row, key, False)
                ],
                # 身体数据比「少油少盐」敏感得多，所以单独一个开关管
                "body": (
                    {
                        "height_cm": row.height_cm,
                        "weight_kg": row.weight_kg,
                        "age": row.age,
                        "activity_level": row.activity_level,
                    }
                    if show(privacy.share_body)
                    else None
                ),
                # 展示别人的健康取向时必须带上这句：用户可能把截图转出去，
                # 接收方不该以为这是医学结论。
                "disclaimer": HEALTH_DISCLAIMER,
            }

    if show(privacy.share_family):
        members = db.query(FamilyMember).filter(FamilyMember.user_id == target.id).all()
        family = [
            {"name": m.name, "relation": m.relation, "diet_goal": m.diet_goal,
             "taste": m.taste}
            for m in members
        ]

    cooked = None
    if show(privacy.share_stats):
        cooked = (
            db.query(func.count(MealHistory.id))
            .filter(MealHistory.user_id == target.id, MealHistory.action == "cook")
            .scalar()
            or 0
        )

    return PublicProfileOut(
        user=_brief(target),
        post_count=svc.post_count(db, target.id),
        follower_count=svc.follower_count(db, target.id),
        following_count=svc.following_count(db, target.id),
        followed_by_me=False if is_me else svc.is_following(db, user.id, target.id),
        is_me=is_me,
        preference=preference,
        health=health,
        family_members=family,
        cooked_count=cooked,
        visibility={
            "preference": show(privacy.share_preference),
            "health": show(privacy.share_health),
            "body": show(privacy.share_body),
            "family": show(privacy.share_family),
            "stats": show(privacy.share_stats),
        },
    )


@router.post("/users/{user_id}/follow", response_model=FollowResultOut, summary="关注")
def follow(user_id: int, user: CurrentUser, db: DbSession) -> FollowResultOut:
    if user_id == user.id:
        raise HTTPException(status_code=400, detail="不能关注自己")
    target = db.get(User, user_id)
    if target is None or not target.is_active:
        raise HTTPException(status_code=404, detail="用户不存在")

    exists = (
        db.query(Follow.id)
        .filter(Follow.follower_id == user.id, Follow.followee_id == user_id)
        .first()
    )
    if exists is None:
        db.add(Follow(follower_id=user.id, followee_id=user_id))
        db.commit()
    return FollowResultOut(
        user_id=user_id, following=True, follower_count=svc.follower_count(db, user_id)
    )


@router.delete("/users/{user_id}/follow", response_model=FollowResultOut, summary="取消关注")
def unfollow(user_id: int, user: CurrentUser, db: DbSession) -> FollowResultOut:
    db.query(Follow).filter(
        Follow.follower_id == user.id, Follow.followee_id == user_id
    ).delete(synchronize_session=False)
    db.commit()
    return FollowResultOut(
        user_id=user_id, following=False, follower_count=svc.follower_count(db, user_id)
    )


def _follow_list(db, rows: list[Follow], viewer_id: int, key: str) -> list[FollowUserOut]:
    """把 Follow 行列表转成带用户信息的列表。

    `key` 决定取关系里的哪一端：粉丝列表传 `follower_id`（谁关注了我），
    关注列表传 `followee_id`（我关注了谁）。
    """
    followed = svc.fetch_followed_ids(db, viewer_id)
    out: list[FollowUserOut] = []
    for row in rows:
        uid: int = getattr(row, key)
        target = db.get(User, uid)
        if target is None or not target.is_active:
            continue  # 账号已注销，从列表里静默跳过而不是显示一个空行
        out.append(
            FollowUserOut(
                user=_brief(target),
                followed_by_me=uid in followed,
                followed_at=row.created_at,
                post_count=svc.post_count(db, uid),
            )
        )
    return out


@router.get("/users/{user_id}/followers", response_model=list[FollowUserOut],
            summary="粉丝列表")
def followers(user_id: int, user: CurrentUser, db: DbSession,
              limit: int = Query(default=50, ge=1, le=100)) -> list[FollowUserOut]:
    rows = (
        db.query(Follow)
        .filter(Follow.followee_id == user_id)
        .order_by(Follow.created_at.desc())
        .limit(limit)
        .all()
    )
    return _follow_list(db, rows, user.id, "follower_id")


@router.get("/users/{user_id}/following", response_model=list[FollowUserOut],
            summary="关注列表")
def following(user_id: int, user: CurrentUser, db: DbSession,
              limit: int = Query(default=50, ge=1, le=100)) -> list[FollowUserOut]:
    rows = (
        db.query(Follow)
        .filter(Follow.follower_id == user_id)
        .order_by(Follow.created_at.desc())
        .limit(limit)
        .all()
    )
    return _follow_list(db, rows, user.id, "followee_id")
