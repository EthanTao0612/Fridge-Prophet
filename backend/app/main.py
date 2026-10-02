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
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles

from app.api.v1.router import api_router
from app.core.config import settings
from app.db.session import init_db

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("fridge_prophet")


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    logger.info("数据库已就绪: %s", settings.DATABASE_URL.split("@")[-1])
    logger.info(
        "AI 状态: %s",
        f"已接入 {settings.VISION_MODEL} / {settings.TEXT_MODEL}"
        if settings.ai_enabled
        else "MOCK 模式（未配置 DASHSCOPE_API_KEY）",
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
