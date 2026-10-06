"""AI 1 接口：上传冰箱照片 → 返回结构化食材列表（不直接入库）。"""
import logging
import uuid
from pathlib import Path

from fastapi import APIRouter, File, HTTPException, UploadFile, status

from app.api.deps import CurrentUser, DbSession, ai_quota_left, guard_ai_quota
from app.core.config import settings
from app.schemas.inventory import ScanResult
from app.services.storage_service import upload_image
from app.services.vision_service import recognize_foods

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/vision", tags=["AI 识别"])

ALLOWED_MIME = {"image/jpeg", "image/jpg", "image/png", "image/webp", "image/heic"}


@router.post("/scan", response_model=ScanResult, summary="扫描冰箱照片，识别食材")
async def scan_fridge(
    user: CurrentUser,
    db: DbSession,
    file: UploadFile = File(..., description="冰箱或食材照片"),
) -> ScanResult:
    mime = (file.content_type or "image/jpeg").lower()
    if mime not in ALLOWED_MIME:
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail=f"不支持的图片格式：{mime}，请上传 JPG / PNG / WEBP",
        )

    raw = await file.read()
    if not raw:
        raise HTTPException(status_code=400, detail="上传的文件是空的")
    if len(raw) > settings.max_upload_bytes:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=f"图片不能超过 {settings.MAX_UPLOAD_MB}MB，请在客户端压缩后再上传",
        )

    # ⚠️ 配额检查放在**参数校验之后、真正调 AI 之前**：
    #   - 放在最前面的话，格式不对的图片也会白扣一次配额
    #   - 放在 AI 调用之后的话等于没限（并发请求全都先通过再扣）
    guard_ai_quota(db, user.id)

    scan_id = uuid.uuid4().hex[:16]

    # 落盘留档，便于后续用「用户修正结果」积累训练数据（策划书第二十七节）
    # 优先存 Supabase Storage，未配置则存本地磁盘
    suffix = Path(file.filename or "").suffix or ".jpg"
    image_url = upload_image(raw, f"{user.id}/{scan_id}{suffix}", mime)

    result = recognize_foods(raw, mime, scan_id)
    result.image_url = image_url
    return result


@router.get("/status", summary="查询 AI 与存储配置 + 今日剩余额度")
def ai_status(user: CurrentUser, db: DbSession) -> dict:
    """客户端启动时调一次，用来决定要不要提示「今天额度快用完了」。

    `ai_quota_left` 是**今天还剩几次 AI 调用**（拍照识别 + 生成菜谱合计）。
    带上它是有必要的：没有这个数字，用户被 429 拦下来时会一头雾水 ——
    「昨天还好好的，怎么突然不行了」。
    """
    left = ai_quota_left(db, user.id)
    return {
        "ai_enabled": settings.ai_enabled,
        "vision_model": settings.VISION_MODEL if settings.ai_enabled else "mock",
        "text_model": settings.TEXT_MODEL if settings.ai_enabled else "mock",
        "storage": "supabase" if settings.supabase_storage_enabled else "local",
        "ai_quota_left": left,
        "ai_quota_low": left <= 3,
        "note": (
            "已接入通义千问"
            if settings.ai_enabled
            else "未配置 DASHSCOPE_API_KEY，当前返回演示数据"
        ),
    }
