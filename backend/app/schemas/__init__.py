"""Pydantic 请求/响应模型。"""
from app.schemas.auth import (
    LoginRequest,
    RegisterRequest,
    SendCodeRequest,
    SendCodeResponse,
    TokenResponse,
)
from app.schemas.inventory import (
    InventoryCreate,
    InventoryOut,
    InventoryUpdate,
    RecognizedFood,
    ScanConfirmRequest,
    ScanResult,
)
from app.schemas.recipe import (
    RecipeGenerateRequest,
    RecipeGenerateResponse,
    RecipeOut,
)
from app.schemas.shopping import ShoppingItemOut, ShoppingItemUpdate, ShoppingListOut
from app.schemas.user import (
    FamilyMemberIn,
    FamilyMemberOut,
    HealthPreferenceIn,
    HealthPreferenceOut,
    ProfileOut,
    ProfileUpdate,
    UserOut,
    UserPreferenceIn,
    UserPreferenceOut,
)

__all__ = [
    "RegisterRequest",
    "LoginRequest",
    "TokenResponse",
    "UserOut",
    "ProfileOut",
    "ProfileUpdate",
    "UserPreferenceIn",
    "UserPreferenceOut",
    "HealthPreferenceIn",
    "HealthPreferenceOut",
    "FamilyMemberIn",
    "FamilyMemberOut",
    "InventoryCreate",
    "InventoryUpdate",
    "InventoryOut",
    "RecognizedFood",
    "ScanResult",
    "ScanConfirmRequest",
    "RecipeGenerateRequest",
    "RecipeGenerateResponse",
    "RecipeOut",
    "ShoppingListOut",
    "ShoppingItemOut",
    "ShoppingItemUpdate",
]
