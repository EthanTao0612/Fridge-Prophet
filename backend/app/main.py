"""冰箱先知 Fridge Prophet —— 后端服务入口。

启动：
    uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
文档：
    http://127.0.0.1:8000/docs
"""
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles

from app.api.v1.router import api_router
from app.core.config import settings
from app.db.session import init_db
from app.services.storage_service import ensure_bucket

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("fridge_prophet")


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    logger.info("数据库已就绪: %s", settings.DATABASE_URL.split("@")[-1])

    # 图片存储：配了 Supabase 就用它，否则落本地磁盘。
    #
    # ⚠️ 这里必须**主动检查一次 bucket**。
    # `ensure_bucket()` 原来只在 scripts/check_supabase.py 里被调用，
    # 而那个脚本在部署文档 docs/03 里根本没提 —— 忘了跑的话，
    # 第一次上传会因为 bucket 不存在而失败，然后**静默退回本地磁盘**
    #（只留一行 warning）。表面上图片照样能显示，
    # 但「照片不怕服务器重装」这个好处已经没了，
    # 而且要到服务器真出事那天才会发现。
    #
    # 这个函数本身很稳：没配 Supabase 直接返回 False，
    # 网络不通也只记日志、不抛异常，不会拖垮启动。
    if settings.supabase_storage_enabled:
        bucket_ok = ensure_bucket()
        logger.info(
            "图片存储: %s",
            f"Supabase Storage（bucket={settings.SUPABASE_BUCKET}）"
            if bucket_ok
            else "⚠️ 本地磁盘 —— Supabase bucket 不可用，已退回",
        )
    else:
        logger.info("图片存储: 本地磁盘（未配置 SUPABASE_URL / SUPABASE_SERVICE_KEY）")

    logger.info(
        "AI 状态: %s",
        f"已接入 {settings.VISION_MODEL} / {settings.TEXT_MODEL}"
        if settings.ai_enabled
        else "MOCK 模式（未配置 DASHSCOPE_API_KEY）",
    )

    # 没配 SMTP 时注册验证码会打到日志里 —— 开发能跑通，
    # 但**生产环境这样等于没有邮箱验证**，必须显眼地提醒。
    if settings.smtp_enabled:
        logger.info("邮箱验证码: 已配置（%s）", settings.SMTP_HOST)
    else:
        logger.warning(
            "邮箱验证码: ⚠️ 未配置 SMTP —— 验证码只会打到日志里，"
            "任何人都能用编造的邮箱注册。生产环境请配 SMTP_HOST/USER/PASSWORD。"
        )

    yield
    logger.info("服务已停止")


app = FastAPI(
    title=settings.APP_NAME,
    version=settings.APP_VERSION,
    description=(
        "「冰箱先知」后端 API。拍摄冰箱照片 → AI 识别食材 → 建立库存 → "
        "结合用户画像生成菜谱 → 计算缺料 → 生成采购清单。"
    ),
    lifespan=lifespan,
    # 接口文档只在开发期开。
    #
    # `/docs` 会把全部接口结构摊开，而且能直接在页面上调接口 ——
    # 生产环境暴露它等于给陌生人一份完整的「怎么打我的 API」说明书。
    #
    # ⚠️ 这里以前是硬编码的 `/docs` 和 `/redoc`，而 `settings.DEBUG`
    # 虽然定义了却**全项目没有一处用到**（死配置）。
    # 结果就是 docs/03 里「上线后加 DEBUG=false 关掉接口文档」这条指引
    # 加了也没用 —— 文档和代码对不上，是最难发现的一类问题。
    #
    # `openapi_url` 也要一起关：只关 `/docs` 的话，
    # `/openapi.json` 还是能把完整结构吐出来。
    docs_url="/docs" if settings.DEBUG else None,
    redoc_url="/redoc" if settings.DEBUG else None,
    openapi_url="/openapi.json" if settings.DEBUG else None,
)

# 开发期全开；上线时把 ALLOW_ORIGINS 收紧到你的域名
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

# JSON 响应压缩。
#
# 接口返回的都是 JSON（中文居多），压缩率通常在 70-85%。
# 实测「菜谱列表」200 条约 482KB，压缩后约 70KB ——
# 手机走流量时这个差别是实打实的。
#
# ⚠️ 两个参数别乱改：
#   `minimum_size=1000` 是**故意的** —— 小响应（比如 /health）压缩后
#   反而更大（要加 gzip 头），所以只压 1KB 以上的。
#   `compresslevel=6` 是速度和压缩率的平衡点，9 会更小但更吃 CPU，
#   而我们的瓶颈在数据库往返（169ms），不在 CPU。
#
# 注意：客户端要**主动发 `Accept-Encoding: gzip`** 才会生效。
# OkHttp 默认就会发，所以不用改客户端。
app.add_middleware(GZipMiddleware, minimum_size=1000, compresslevel=6)


@app.exception_handler(Exception)
async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    logger.exception("未捕获异常: %s %s", request.method, request.url.path)
    return JSONResponse(status_code=500, content={"detail": "服务器内部错误，请稍后重试"})


app.include_router(api_router, prefix=settings.API_PREFIX)
app.mount("/uploads", StaticFiles(directory=settings.UPLOAD_DIR), name="uploads")
# 项目自带的静态资源（菜谱配图等），跟着 Git 走
app.mount("/static", StaticFiles(directory=settings.STATIC_DIR), name="static")


@app.get("/", tags=["系统"], summary="服务信息")
def root() -> dict:
    return {
        "app": settings.APP_NAME,
        "version": settings.APP_VERSION,
        "status": "ok",
        "docs": "/docs",
        "api_prefix": settings.API_PREFIX,
        "ai_enabled": settings.ai_enabled,
    }


@app.get("/health", tags=["系统"], summary="健康检查（部署与监控用）")
def health() -> dict:
    return {"status": "healthy", "ai_enabled": settings.ai_enabled}
