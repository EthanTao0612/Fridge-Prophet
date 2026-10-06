"""菜谱配图：用项目内置的本地图片库，按菜名/食材匹配。

为什么不用 AI 现画图片
--------------------
1. 慢。用户点「生成菜谱」后要立刻看到结果，而画一张图要几十秒。
2. 贵。每次生成都要花钱，且同一道菜每次画出来都不一样，看着廉价、不可信。
3. 脆。断网、MOCK 模式下就彻底没图了 —— 而 MOCK 模式是比赛现场的保命机制。

所以改成：项目自带一套菜品图，放在 ``backend/static/recipes/``，
按「菜名关键词 → 图片」匹配。匹配不上就逐级退化，
最后落到 ``default.jpg`` —— 宁可给一张通用图，也不要空白卡片。

匹配是**确定性**的：同一道菜永远得到同一张图，不会这次是这个下次是那个。

加新图的方法
-----------
把图片命名为 ``{key}.jpg`` 丢进 ``backend/static/recipes/``，
然后在下面两张表里加一条规则即可，不用改任何其它代码。
"""
from __future__ import annotations

import logging

from app.core.config import settings

logger = logging.getLogger(__name__)

# 图片放在 static 下这个子目录里
IMAGE_SUBDIR = "recipes"
IMAGE_SUFFIX = ".jpg"
# 匹配不上时用这张兜底
DEFAULT_KEY = "default"

# ---------------------------------------------------------------- 第一层：具体菜名
# 顺序有意义：**从上往下**匹配，命中即停。
# 所以更具体的菜名必须排在更笼统的前面
# （否则「番茄鸡蛋豆腐」会先被「番茄鸡蛋」截走）。
DISH_RULES: tuple[tuple[tuple[str, ...], str], ...] = (
    # —— MOCK 模式内置的三道菜，必须有专属图 ——
    (("番茄鸡蛋豆腐", "西红柿鸡蛋豆腐", "番茄豆腐"), "tomato-egg-tofu"),
    (("鸡胸", "鸡肉沙拉", "香煎鸡"), "chicken-breast-broccoli"),
    (("青椒炒蛋", "青椒炒鸡蛋", "青椒鸡蛋", "青椒蛋", "尖椒炒蛋",
      "尖椒炒鸡蛋"), "green-pepper-egg"),

    # —— 高频家常菜 ——
    (("番茄炒蛋", "西红柿炒蛋", "番茄炒鸡蛋", "西红柿炒鸡蛋", "番茄鸡蛋",
      "西红柿鸡蛋"), "tomato-egg"),
    (("麻婆豆腐", "家常豆腐", "烧豆腐"), "mapo-tofu"),
    (("红烧肉", "梅菜扣肉", "回锅肉"), "braised-pork"),
    (("宫保鸡丁", "辣子鸡", "黄焖鸡"), "kung-pao-chicken"),
    (("蛋炒饭", "炒饭", "盖浇饭", "拌饭"), "egg-fried-rice"),
    (("蒸鱼", "清蒸", "鱼片", "红烧鱼", "煎鱼"), "steamed-fish"),
    (("糖醋", "咕咾", "锅包肉"), "sweet-sour-pork"),
    (("土豆丝", "炒土豆", "醋溜土豆"), "potato-shreds"),
    (("拍黄瓜", "凉拌黄瓜", "拌黄瓜", "凉拌菜", "凉拌"), "cucumber-salad"),
    (("蒸蛋", "水蒸蛋", "鸡蛋羹", "蛋羹"), "steamed-egg"),
    (("饺子", "馄饨", "云吞", "包子"), "dumplings"),
    (("茄子", "地三鲜"), "braised-eggplant"),
    (("西兰花", "花菜", "菜花"), "broccoli"),
)

# ---------------------------------------------------------------- 第二层：主食材
# 菜名匹配不上时，用「这道菜用到的主要食材」来选图。
# 同样是从上往下，命中即停。
#
# ⚠️ 顺序在这里同样是语义的一部分，而且比第一层更隐蔽。
# 踩过的坑：鸡肉规则里有单字关键词「鸡」，而「鸡蛋」里也含「鸡」，
# 所以「青椒炒鸡蛋」原本会被鸡肉规则截走，配上一张煎鸡腿的图。
# 解法是把**蛋类规则排在鸡肉前面** —— 「鸡蛋」先被蛋类命中，
# 而「鸡肉/鸡胸/鸡腿」不含「蛋」，照样落到鸡肉规则。
# 加新规则时务必想一遍：它会不会被上面某条的单字关键词抢先命中。
INGREDIENT_RULES: tuple[tuple[tuple[str, ...], str], ...] = (
    # 蛋类必须排在鸡肉前面（见上方说明）
    (("鸡蛋", "鸭蛋", "蛋"), "egg"),
    (("鸡胸", "鸡腿", "鸡翅", "鸡肉", "鸡柳", "鸡"), "chicken"),
    (("牛腩", "牛肉", "牛排", "牛"), "beef"),
    (("五花", "里脊", "排骨", "肉末", "猪肉", "肉丝", "肉片", "火腿", "腊肉", "培根"), "pork"),
    (("虾", "蟹", "贝", "蛤", "鱿鱼", "海鲜"), "shrimp"),
    (("鲈鱼", "鲫鱼", "草鱼", "鱼"), "fish"),
    (("豆腐", "豆干", "腐竹"), "tofu"),
    (("西兰花", "芦笋", "秋葵"), "broccoli"),
    (("土豆", "红薯", "山药", "芋头"), "potato"),
    (("茄子",), "eggplant"),
    (("黄瓜", "番茄", "西红柿", "生菜", "沙拉"), "cucumber-salad"),
    (("蘑菇", "香菇", "金针菇", "杏鲍菇", "木耳", "菌", "笋"), "mushroom"),
    (("白菜", "卷心菜", "包菜", "菠菜", "青菜", "油菜", "油麦菜", "空心菜",
      "芹菜", "韭菜", "豆芽", "蔬菜", "时蔬"), "greens"),
    (("米饭", "米", "饭", "粥", "燕麦"), "rice"),
    (("面条", "挂面", "拉面", "粉", "米线", "意面"), "noodles"),
    (("汤", "羹", "煲"), "soup"),
)


def _normalize(text: str) -> str:
    """统一别名，减少规则表里的重复项。"""
    return (text or "").strip()


def _match(rules, haystack: str) -> str | None:
    for keywords, key in rules:
        for kw in keywords:
            if kw in haystack:
                return key
    return None


def _exists(key: str) -> bool:
    return (settings.STATIC_DIR / IMAGE_SUBDIR / f"{key}{IMAGE_SUFFIX}").is_file()


def _url(key: str) -> str:
    return f"/static/{IMAGE_SUBDIR}/{key}{IMAGE_SUFFIX}"


def resolve_image_key(
    name: str, ingredient_names: list[str] | None = None
) -> str | None:
    """算出这道菜该用哪张图。返回 key，找不到返回 None。

    只会返回**磁盘上真实存在**的图；候选图不存在就继续往下退，
    一路退到 default 都没有，才返回 None（此时客户端显示占位样式）。
    """
    for key in candidate_keys(name, ingredient_names):
        if _exists(key):
            return key

    logger.debug("菜谱配图缺失: name=%s（static/recipes/ 下没有可用图片）", name)
    return None


def rule_key(name: str, ingredient_names: list[str] | None = None) -> str | None:
    """**纯规则**匹配，不查磁盘。返回首选 key；一条规则都没命中时返回 None。

    和 resolve_image_key 的区别：这个函数回答「按规则该配哪张图」，
    那个函数回答「实际能配到哪张图」。分开是为了让规则逻辑可以脱离
    图片文件独立测试 —— 图片还没生成时也能验证规则表顺序是否正确。
    """
    candidates = candidate_keys(name, ingredient_names)
    # candidate_keys 末尾一定会补一个 default，所以只剩它一个 = 没有规则命中
    if len(candidates) == 1 and candidates[0] == DEFAULT_KEY:
        return None
    return candidates[0]


def candidate_keys(
    name: str, ingredient_names: list[str] | None = None
) -> list[str]:
    """按优先级列出候选图片 key（不去磁盘检查）。

    顺序即优先级：
        1. **菜品库精确匹配**（192 道家常菜，每道一张专属图）
        2. 旧 DISH_RULES（MOCK 内置菜 + 早期的高频菜）
        3. 菜名 / 食材命中的主食材
        4. default 兜底
    已经去重，且保持顺序。
    """
    dish = _normalize(name)
    ingredients = " ".join(_normalize(i) for i in (ingredient_names or []))

    out: list[str] = []

    # 第 1 层：菜品库精确匹配
    #
    # 192 道家常菜每道都有自己的图，比「按关键词猜一个主食材」准得多。
    # 放在最前面：比如「西红柿炒鸡蛋」应该用它的专属图，
    # 而不是被后面的「番茄」规则抢去用一张生番茄的照片。
    from app.services.dish_library import DISH_NAMES

    key = DISH_NAMES.get(name) or DISH_NAMES.get(name.strip())
    if key:
        out.append(key)

    # 第 2 层：旧规则表 → 具体菜品
    key = _match(DISH_RULES, dish)
    if key:
        out.append(key)

    # 第 3 层：菜名 + 食材 → 主食材
    # 先只看菜名（菜名里的关键词比配料表更贴近成品的样子），
    # 菜名里没有线索时再看食材清单。
    key = _match(INGREDIENT_RULES, dish) or _match(INGREDIENT_RULES, ingredients)
    if key:
        out.append(key)

    # 第 4 层：兜底
    out.append(DEFAULT_KEY)

    # 去重但保序（同一张图可能在两层都被命中）
    seen: set[str] = set()
    unique: list[str] = []
    for key in out:
        if key not in seen:
            seen.add(key)
            unique.append(key)
    return unique


def resolve_image_url(
    name: str, ingredient_names: list[str] | None = None
) -> str | None:
    """返回可访问的相对 URL（如 ``/static/recipes/tomato-egg.jpg``），没有图返回 None。

    注意返回的是**相对路径**。客户端（Android / 浏览器）需要自己拼上服务器域名，
    这样同一份数据在本地、局域网、公网都能用，不用改数据。
    """
    key = resolve_image_key(name, ingredient_names)
    return _url(key) if key else None


def available_images() -> list[str]:
    """已就位的图片 key 列表，供自检脚本 / 部署检查使用。"""
    directory = settings.STATIC_DIR / IMAGE_SUBDIR
    if not directory.is_dir():
        return []
    return sorted(p.stem for p in directory.glob(f"*{IMAGE_SUFFIX}"))


def expected_keys() -> list[str]:
    """规则表里会用到、但可能还没生成图片的 key 全集。"""
    keys = {key for _, key in DISH_RULES}
    keys |= {key for _, key in INGREDIENT_RULES}
    keys.add(DEFAULT_KEY)
    return sorted(keys)
