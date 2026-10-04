"""v1 路由汇总。"""
from fastapi import APIRouter

from app.api.v1 import (
    auth,
    family,
    food_category,
    inventory,
    recipes,
    shopping,
    social,
    tips,
    users,
    vision,
)

api_router = APIRouter()
api_router.include_router(auth.router)
api_router.include_router(users.router)
api_router.include_router(family.router)
api_router.include_router(inventory.router)
api_router.include_router(food_category.router)
api_router.include_router(vision.router)
api_router.include_router(recipes.router)
api_router.include_router(shopping.router)
api_router.include_router(tips.router)
api_router.include_router(social.router)
