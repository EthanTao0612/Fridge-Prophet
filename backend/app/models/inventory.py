"""冰箱库存。策划书第八节 + 第十五节的字段设计。"""
from datetime import date, datetime, timezone

from sqlalchemy import Date, DateTime, Float, ForeignKey, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from app.db.session import Base

STORAGE_LOCATIONS = ("冷藏", "冷冻", "常温")
FRESHNESS_LEVELS = ("新鲜", "正常", "尽快食用", "已过期")


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


class FoodInventory(Base):
    __tablename__ = "food_inventory"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)

    food_name: Mapped[str] = mapped_column(String(64), index=True)
    category: Mapped[str] = mapped_column(String(32), default="其他")
    quantity: Mapped[float] = mapped_column(Float, default=0)
    unit: Mapped[str] = mapped_column(String(16), default="个")

    purchase_date: Mapped[date | None] = mapped_column(Date, nullable=True)
    expiry_date: Mapped[date | None] = mapped_column(Date, nullable=True, index=True)
    storage_location: Mapped[str] = mapped_column(String(16), default="冷藏")
    freshness: Mapped[str] = mapped_column(String(16), default="正常")

    confidence: Mapped[float | None] = mapped_column(Float, nullable=True)
    source: Mapped[str] = mapped_column(String(16), default="manual")  # manual / ai_scan
    note: Mapped[str | None] = mapped_column(String(255), nullable=True)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, onupdate=_utcnow
    )

    def refresh_freshness(self, today: date | None = None) -> bool:
        """按剩余天数推导新鲜度。过期前 2 天内标为「尽快食用」。

        **返回「有没有真的变化」**，调用方据此决定要不要 commit。

        为什么要这个返回值：生产库在 Supabase（孟买），一次往返 169ms，
        而 commit 又要一次往返。以前每个**读**接口都无条件 commit，
        等于每次列表查询白多花 169ms —— 用户感觉就是「点一下卡半秒」。
        绝大多数请求里新鲜度根本没变（今天没跨天），根本不需要写库。
        """
        today = today or date.today()
        before = self.freshness
        if self.expiry_date is None:
            self.freshness = "正常"
        else:
            days_left = (self.expiry_date - today).days
            if days_left < 0:
                self.freshness = "已过期"
            elif days_left <= 2:
                self.freshness = "尽快食用"
            elif days_left <= 5:
                self.freshness = "正常"
            else:
                self.freshness = "新鲜"
        return self.freshness != before
        return self.freshness
