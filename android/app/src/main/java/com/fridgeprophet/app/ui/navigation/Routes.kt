package com.fridgeprophet.app.ui.navigation

/** 全部路由。用字符串而不是类型安全路由，兼容性更好、出错信息也更直观。 */
object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val ONBOARDING = "onboarding"
    const val MAIN = "main"
    const val SCAN = "scan"

    const val RECIPE_DETAIL = "recipe/{recipeId}"
    fun recipeDetail(id: Int) = "recipe/$id"

    // 家庭组：邀请家人一起管理冰箱
    const val FAMILY = "family"

    // 贴士 id 是字符串（如 crab-with-tomato），不是数字，所以路由参数类型不同
    const val TIPS = "tips"
    const val TIP_DETAIL = "tip/{tipId}"
    fun tipDetail(id: String) = "tip/$id"

    // ---------- 广场 ----------
    const val PLAZA_COMPOSE = "plaza/compose"
    const val PLAZA_POST = "plaza/post/{postId}"
    fun plazaPost(id: Int) = "plaza/post/$id"
    const val PLAZA_USER = "plaza/user/{userId}"
    fun plazaUser(id: Int) = "plaza/user/$id"
}

/**
 * 底部导航的 Tab。
 *
 * 广场排在「首页」后面而不是最后：它承载的是**内容消费**，
 * 和首页、菜谱属于同一类（看东西），而「我的」是设置类。
 * 把设置类塞在中间会打断浏览动线。
 */
enum class MainTab(
    val label: String,
    val route: String,
) {
    HOME("首页", "tab_home"),
    PLAZA("广场", "tab_plaza"),
    FRIDGE("冰箱", "tab_fridge"),
    RECIPES("菜谱", "tab_recipes"),
    SHOPPING("采购", "tab_shopping"),
    PROFILE("我的", "tab_profile"),
}
