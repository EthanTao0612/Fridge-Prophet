"""ORM 模型。对应策划书第十五节的数据库设计。"""
from app.models.food_category import FoodCategory, FoodCategoryItem
from app.models.inventory import FoodInventory
from app.models.recipe import MealHistory, Recipe, RecipeFeedback, RecipeIngredient
from app.models.shopping import ShoppingItem, ShoppingList
from app.models.social import Follow, Post, PostComment, PostLike
from app.models.verification import EmailVerification
from app.models.user import (
    Family,
    FamilyMember,
    FamilyMembership,
    HealthPreference,
    PrivacySetting,
    User,
    UserPreference,
)

__all__ = [
    "EmailVerification",
    "User",
    "UserPreference",
    "HealthPreference",
    "PrivacySetting",
    "FamilyMember",
    "Family",
    "FamilyMembership",
    "FoodInventory",
    "FoodCategory",
    "FoodCategoryItem",
    "Recipe",
    "RecipeIngredient",
    "MealHistory",
    "RecipeFeedback",
    "ShoppingList",
    "ShoppingItem",
    "Post",
    "PostLike",
    "PostComment",
    "Follow",
]
