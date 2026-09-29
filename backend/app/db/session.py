"""数据库引擎与会话。SQLite / PostgreSQL 通过 DATABASE_URL 切换。"""
from collections.abc import Generator

from sqlalchemy import create_engine
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

from app.core.config import settings

_is_sqlite = settings.DATABASE_URL.startswith("sqlite")

# 开发（SQLite）和生产（Supabase Postgres）的连接池要求不一样。
#
# 非 SQLite 要额外注意三件事：
#
# 1. `pool_pre_ping`：取连接前先探活。Supabase 的连接池会主动回收空闲连接，
#    不探活就会拿到已经断掉的连接，报一堆「连接被对方关闭」的怪错。
#
# 2. `pool_recycle=1800`：半小时换一批连接。和 pre_ping 是双保险 ——
#    pre_ping 管「别用死连接」，recycle 管「别把死连接一直留在池子里」。
#
# 3. `pool_size` / `max_overflow`：把总连接数压到可控范围。
#    SQLAlchemy 默认是 5 + 10 = 15，而 systemd 里跑 2 个 worker，
#    最坏情况就是 30 条 —— Supabase 免费版扛得住但没余量，
#    演示当天要是有人并发访问就容易撞上「too many clients」。
#    改成 5 + 5 = 10，两个 worker 一共 20 条，留出余量。
_engine_kwargs: dict = {"echo": False}
if _is_sqlite:
    _engine_kwargs["connect_args"] = {"check_same_thread": False}
else:
    _engine_kwargs.update(
        pool_pre_ping=True,
        pool_recycle=1800,
        pool_size=5,
        max_overflow=5,
    )

engine = create_engine(settings.DATABASE_URL, **_engine_kwargs)

SessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False)


class Base(DeclarativeBase):
    pass


def get_db() -> Generator[Session, None, None]:
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def init_db() -> None:
    """建表 + 补齐后来新增的列。

    `create_all` 只建**不存在的表**，不会给已存在的表加列，
    所以后面还要跑一次 ensure_columns，否则老库上会报 no such column。
    见 app/db/migrate.py 的说明。
    """
    from app import models  # noqa: F401  触发模型注册
    from app.db.migrate import ensure_columns

    Base.metadata.create_all(bind=engine)
    ensure_columns(engine)
