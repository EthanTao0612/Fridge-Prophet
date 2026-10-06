"""全局配置。所有可调参数集中在这里，通过环境变量或 .env 覆盖。"""
from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

BASE_DIR = Path(__file__).resolve().parents[2]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=BASE_DIR / ".env", env_file_encoding="utf-8", extra="ignore"
    )

    # ---- 应用 ----
    APP_NAME: str = "冰箱先知 Fridge Prophet API"
    APP_VERSION: str = "0.1.0"
    DEBUG: bool = True
    API_PREFIX: str = "/api/v1"

    # ---- 数据库 ----
    # 开发：SQLite（默认）
    # 上线：Supabase 托管 PostgreSQL
    #   直连（适合常驻服务器）：
    #     postgresql+psycopg://postgres.<ref>:密码@aws-0-ap-southeast-1.pooler.supabase.com:5432/postgres
    #   连接池（适合 Serverless）：
    #     postgresql+psycopg://postgres.<ref>:密码@aws-0-ap-southeast-1.pooler.supabase.com:6543/postgres
    # 注意：SQLite 和 Postgres 之间切换只需改这一行，模型层无需改动
    DATABASE_URL: str = f"sqlite:///{BASE_DIR / 'fridge_prophet.db'}"

    # ---- 鉴权 ----
    SECRET_KEY: str = "dev-only-change-me-in-production"
    ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 60 * 24 * 14  # 14 天

    # ---- AI（阿里云百炼 / DashScope 的 OpenAI 兼容接口）----
    # 留空则自动进入 MOCK 模式，用内置假数据跑通全流程
    DASHSCOPE_API_KEY: str = ""
    AI_BASE_URL: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    VISION_MODEL: str = "qwen-vl-max"
    TEXT_MODEL: str = "qwen-plus"
    AI_TIMEOUT_SECONDS: float = 90.0

    # —— AI 每日配额（给百炼额度上一道保险，见 services/quota_service.py）——
    #
    # 后端接口原本没有限流，而注册是开放的 —— 任何人发现域名后注册个账号
    # 就能反复调 /vision/scan 和 /recipes/generate，每次都在花项目自己的钱。
    #
    # 演示前如果怕被限住，把 PER_USER 调大（改 .env 即可，不用改代码）。
    # GLOBAL 是**账单的硬上限**：就算有人批量注册账号，全站每天也就这么多。
    DAILY_AI_LIMIT_PER_USER: int = 30
    DAILY_AI_LIMIT_GLOBAL: int = 500

    # ---- 上传 ----
    MAX_UPLOAD_MB: int = 10
    UPLOAD_DIR: Path = BASE_DIR / "uploads"

    # ---- 内置静态资源（菜谱配图等）----
    # 和 UPLOAD_DIR 的区别：uploads 是**用户产生的**（会被 .gitignore 忽略），
    # static 是**项目自带的**，要跟着 Git 走、部署时一起传上去。
    STATIC_DIR: Path = BASE_DIR / "static"

    # ---- Supabase Storage（可选）----
    # 配好之后冰箱照片会存到 Supabase，而不是服务器本地磁盘。
    # 好处：服务器重装/换机器照片不丢，且自带 CDN 加速。
    # SUPABASE_URL 形如 https://abcdefgh.supabase.co
    # SUPABASE_SERVICE_KEY 是 service_role key（Settings → API），只能放后端，绝不能进 App
    SUPABASE_URL: str = ""
    SUPABASE_SERVICE_KEY: str = ""
    SUPABASE_BUCKET: str = "fridge-photos"

    @property
    def supabase_storage_enabled(self) -> bool:
        return bool(self.SUPABASE_URL.strip() and self.SUPABASE_SERVICE_KEY.strip())

    # ---- 邮箱验证码（注册用）----
    #
    # 不配也能跑：验证码会打到服务端日志里（开发模式）。
    # 但**生产环境必须配**，否则任何人都能用编造的邮箱注册。
    #
    # QQ 邮箱为例：
    #   SMTP_HOST=smtp.qq.com
    #   SMTP_PORT=465
    #   SMTP_USER=你的QQ号@qq.com
    #   SMTP_PASSWORD=授权码（不是登录密码！在 QQ 邮箱设置→账户 里开 SMTP 服务时生成）
    SMTP_HOST: str = ""
    SMTP_PORT: int = 465
    SMTP_USER: str = ""
    SMTP_PASSWORD: str = ""
    SMTP_FROM_NAME: str = "冰箱先知"
    # 465 端口用 SSL，587 端口用 STARTTLS
    SMTP_USE_SSL: bool = True

    @property
    def smtp_enabled(self) -> bool:
        return bool(self.SMTP_HOST.strip() and self.SMTP_USER.strip() and self.SMTP_PASSWORD.strip())

    @property
    def ai_enabled(self) -> bool:
        return bool(self.DASHSCOPE_API_KEY.strip())

    @property
    def max_upload_bytes(self) -> int:
        return self.MAX_UPLOAD_MB * 1024 * 1024


@lru_cache
def get_settings() -> Settings:
    s = Settings()
    s.UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
    # static 目录必须**存在**才能挂载（StaticFiles 在目录缺失时直接抛错），
    # 所以启动时顺手建出来，哪怕里面暂时没有图片。
    s.STATIC_DIR.mkdir(parents=True, exist_ok=True)
    return s


settings = get_settings()
