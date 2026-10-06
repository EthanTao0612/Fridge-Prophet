"""邮箱验证码。

## 为什么需要

`EmailStr` 只校验**格式**，不验证邮箱是否真实存在 ——
实测 `a@b.com`、`test@nowhere-xyz-12345.com` 这种编造的地址都能注册成功。
这个文件补上「证明这个邮箱真是你的」这一步。

## 为什么不用第三方服务

验证码走 **SMTP**（Python 标准库 `smtplib`），不引入新依赖。
免费邮箱（QQ / 163 / Gmail）开个授权码就能用，比赛够用。

短信验证码要买套餐，成本高得多，这里不做。

## 防滥用

验证码接口是**公开的**（注册前还没有账号），很容易被当成免费发信机。
四道闸：

| 闸 | 值 | 防什么 |
|---|---|---|
| 同邮箱冷却 | 60 秒 | 连点「获取验证码」 |
| 同邮箱每日上限 | 10 次 | 把别人的邮箱当轰炸目标 |
| 验证码有效期 | 5 分钟 | 泄露后长期可用 |
| 最多尝试次数 | 5 次 | 暴力猜 6 位码 |

另外**同一个邮箱发新码会作废旧的**，避免同时存在多个有效码。
"""
from __future__ import annotations

import hashlib
import logging
import random
import smtplib
import ssl
from datetime import datetime, timedelta, timezone
from email.header import Header
from email.mime.text import MIMEText
from email.utils import formataddr

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.core.config import settings
from app.models.verification import CODE_LENGTH, EmailVerification

logger = logging.getLogger(__name__)

# 验证码有效期
CODE_TTL = timedelta(minutes=5)
# 同一邮箱两次发码的最小间隔
SEND_COOLDOWN = timedelta(seconds=60)
# 同一邮箱每天最多发几次
DAILY_LIMIT = 10
# 一个验证码最多能被试几次
MAX_ATTEMPTS = 5


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


def _hash(code: str, email: str) -> str:
    """验证码存哈希，不存明文。

    加了邮箱当盐 —— 不加的话，6 位数字只有 100 万种可能，
    拿到库的人一张彩虹表就全破了。加了盐至少每个邮箱要单独算。

    ⚠️ 这里**故意不用 bcrypt**（虽然注册密码用的是它）：
    bcrypt 单次约 100ms，每次校验都要跑一遍，而 6 位码本身就防不住
    离线暴力破解 —— 真正的防线是「5 次尝试上限 + 5 分钟过期」。
    用 sha256 是为了让校验快，不是为了抗破解。
    """
    return hashlib.sha256(f"{email.lower()}:{code}".encode()).hexdigest()


def generate_code() -> str:
    """生成 6 位数字验证码。用 secrets 而不是 random —— 后者可预测。"""
    import secrets

    return "".join(secrets.choice("0123456789") for _ in range(CODE_LENGTH))


def issue_code(db: Session, email: str, purpose: str = "register") -> tuple[str | None, str]:
    """生成并存下验证码。返回 (验证码, 提示语)；被限流时验证码为 None。

    提示语是**给用户看的**，所以不包含「还剩几次」这类内部信息。
    """
    email = email.strip().lower()
    now = _utcnow()

    # 闸 ①：冷却
    last = db.execute(
        select(EmailVerification)
        .where(EmailVerification.email == email, EmailVerification.purpose == purpose)
        .order_by(EmailVerification.created_at.desc())
        .limit(1)
    ).scalar_one_or_none()
    if last is not None:
        # SQLite 存的是 naive datetime，比较前统一成 aware
        created = last.created_at
        if created.tzinfo is None:
            created = created.replace(tzinfo=timezone.utc)
        waited = now - created
        if waited < SEND_COOLDOWN:
            left = int((SEND_COOLDOWN - waited).total_seconds()) + 1
            return None, f"发送太频繁了，请 {left} 秒后再试"

    # 闸 ②：每日上限
    since = now - timedelta(days=1)
    today_count = db.execute(
        select(func.count())
        .select_from(EmailVerification)
        .where(
            EmailVerification.email == email,
            EmailVerification.created_at >= since,
        )
    ).scalar_one()
    if today_count >= DAILY_LIMIT:
        return None, "这个邮箱今天获取验证码的次数太多了，请明天再试"

    # 同一个邮箱发新码 → 作废旧的，避免同时存在多个有效码
    for old in db.execute(
        select(EmailVerification).where(
            EmailVerification.email == email,
            EmailVerification.purpose == purpose,
            EmailVerification.used_at.is_(None),
        )
    ).scalars():
        old.used_at = now

    code = generate_code()
    db.add(
        EmailVerification(
            email=email,
            code_hash=_hash(code, email),
            purpose=purpose,
            expires_at=now + CODE_TTL,
        )
    )
    db.commit()
    return code, "验证码已发送"


def verify_code(db: Session, email: str, code: str, purpose: str = "register") -> tuple[bool, str]:
    """校验验证码。成功时把它标记为已用（一个码只能用一次）。"""
    email = email.strip().lower()
    now = _utcnow()

    row = db.execute(
        select(EmailVerification)
        .where(
            EmailVerification.email == email,
            EmailVerification.purpose == purpose,
            EmailVerification.used_at.is_(None),
        )
        .order_by(EmailVerification.created_at.desc())
        .limit(1)
    ).scalar_one_or_none()

    if row is None:
        return False, "请先获取验证码"

    expires = row.expires_at
    if expires.tzinfo is None:
        expires = expires.replace(tzinfo=timezone.utc)
    if now > expires:
        return False, "验证码已过期，请重新获取"

    if row.attempts >= MAX_ATTEMPTS:
        # 用掉它，逼用户重新发 —— 否则会一直被同一个码卡住
        row.used_at = now
        db.commit()
        return False, "验证码错误次数过多，请重新获取"

    row.attempts += 1
    if _hash(code, email) != row.code_hash:
        db.commit()
        return False, "验证码不正确"

    row.used_at = now
    db.commit()
    return True, "验证通过"


def send_code_email(email: str, code: str, purpose: str = "register") -> tuple[bool, str]:
    """把验证码发出去。返回 (是否真的发出去了, 说明)。

    ⚠️ **没配 SMTP 时不报错**，而是把验证码打到日志里。
    这样本地开发和自动化测试都能跑通，不用为了测注册去配邮箱。
    但生产环境必须配上 —— 启动时会打一条显眼的警告。
    """
    if not settings.smtp_enabled:
        logger.warning(
            "[开发模式] 未配置 SMTP，验证码直接打到日志：%s -> %s（%s）",
            email, code, purpose,
        )
        return False, "邮件服务未配置，验证码已输出到服务端日志（开发模式）"

    subject = "冰箱先知 · 注册验证码" if purpose == "register" else "冰箱先知 · 验证码"
    body = (
        f"你的验证码是：{code}\n\n"
        f"有效期 5 分钟，请尽快输入。\n"
        f"如果不是你本人操作，忽略这封邮件即可。\n\n"
        f"—— 冰箱先知"
    )

    msg = MIMEText(body, "plain", "utf-8")
    msg["Subject"] = Header(subject, "utf-8")
    msg["From"] = formataddr((str(Header(settings.SMTP_FROM_NAME, "utf-8")), settings.SMTP_USER))
    msg["To"] = email

    try:
        if settings.SMTP_USE_SSL:
            ctx = ssl.create_default_context()
            with smtplib.SMTP_SSL(settings.SMTP_HOST, settings.SMTP_PORT,
                                  context=ctx, timeout=15) as s:
                s.login(settings.SMTP_USER, settings.SMTP_PASSWORD)
                s.sendmail(settings.SMTP_USER, [email], msg.as_string())
        else:
            with smtplib.SMTP(settings.SMTP_HOST, settings.SMTP_PORT, timeout=15) as s:
                s.starttls(context=ssl.create_default_context())
                s.login(settings.SMTP_USER, settings.SMTP_PASSWORD)
                s.sendmail(settings.SMTP_USER, [email], msg.as_string())
    except Exception as exc:  # noqa: BLE001
        # 邮件发失败**不该**让注册流程崩掉 —— 记日志，把原因如实告诉用户
        logger.error("发验证码邮件失败（%s）: %s", email, exc)
        return False, "验证码发送失败，请稍后重试或联系管理员"

    return True, "验证码已发送"
