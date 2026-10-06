"""食材配图：按食材名匹配一张**白底单品图**。

## 为什么单独做一套图，不复用菜谱图

菜谱图是「一盘做好的菜」，食材图是「一样生的食材」，两者用途完全不同：
菜谱图放在菜谱卡片上让人有食欲，食材图放在冰箱列表里帮人**快速认东西**。
把成品菜图塞进冰箱列表会出现「冰箱里有一盘红烧肉」这种荒谬的观感。

## 为什么是白底

冰箱列表一屏十几个条目，每条一张小图。彩色背景的小图混在一起会互相打架，
而且不同的底色会让整列看着参差不齐。统一白底 + 主体居中，
缩到 44dp 时依然能一眼分辨，整列也是齐的。

## 匹配不上怎么办

逐级退化，最后返回 None —— 由客户端显示「食材名首字 + 哈希色块」的占位。
**不要给一个通用的「未知食材」图**：一列里出现五张一样的通用图，
用户会以为列表渲染坏了，比没有图更糟。
"""
from __future__ import annotations

import logging

from app.core.config import settings

logger = logging.getLogger(__name__)

IMAGE_SUBDIR = "ingredients"
IMAGE_SUFFIX = ".jpg"

# ---------------------------------------------------------------- 匹配规则
# 顺序有意义：**从上往下**匹配，命中即停。
#
# ⚠️ 单字关键词必须排在多字关键词**之后**，而且要注意会不会被别的规则抢走。
# 踩过的坑（和菜谱图那边是同一类问题）：
#   「鸡蛋」含「鸡」，如果鸡肉规则在前面，鸡蛋会被配上鸡肉图。
#   所以蛋类规则必须排在所有肉类之前。
#
# 加新规则前先自问一遍：这条的关键词会不会被上面某条先命中？
# ============================================================
#  【挡刀区】—— 优先级最高，排在词库匹配之前
#
#  专门拦在含「单字关键词」的规则前面，防止配错图。
#  例：「牛油果」含「牛」，不挡的话会被牛肉规则命中 ——
#  冰箱列表里就会给牛油果配一张牛排照片。
#
#  指向**还没有配图的 key 是故意的**：resolve_ingredient_image
#  只返回磁盘上真实存在的图，所以这些会退化成万能图或占位样式。
#  「没有图」比「配错图」好得多。以后补了图直接生效，代码不用改。
#
#  加新规则前先自问：这条的关键词会不会被上面某条先命中？
#  回归测试见 tests/test_ingredient_rules.py
# ============================================================
GUARD_RULES: tuple[tuple[tuple[str, ...], str], ...] = (
    # —— 加工过的调味料：不能命中「原料」的图 ——
    #
    # 这一类不是「单字抢关键词」，而是**原料规则太宽**：
    # 「番茄酱」含「番茄」、「花生油」含「花生」，都会命中原料的规则，
    # 于是给番茄酱配一张新鲜番茄的图、给花生油配一堆花生米。
    # 而且分类也跟着错（番茄酱会被归进「蔬菜」），所以必须在这里拦掉。
    #
    # ⚠️ 这里只留**词库覆盖不到**的复合词。
    # 「生抽」「老抽」「蚝油」「料酒」这些词库里都有更精确的 key
    #（light-soy-sauce / oyster-sauce / cooking-wine…），拦在这里反而变差。
    (("番茄酱", "蕃茄酱", "番茄沙司"), "seasoning"),
    (("花生油", "菜籽油", "橄榄油", "玉米油", "葵花籽油", "芝麻油", "香油",
      "食用油", "色拉油", "调和油"), "seasoning"),
    # 「醋」本身词库有（vinegar），但「苹果醋」没有 ——
    # 不拦的话它会被「苹果」抢走，配一张苹果的图。
    (("苹果醋", "果醋"), "seasoning"),
    (("沙拉酱", "千岛酱", "烧烤酱"), "seasoning"),

    # 名字里带动物、但其实不是那种肉的东西
    (("鸡腿菇", "鸡枞", "鸡油菌", "牛肝菌"), "mushroom"),   # 是菌菇不是鸡/牛
    (("羊奶", "马奶", "骆驼奶", "水牛奶"), "milk"),        # 是奶不是羊肉
    # 这两个没有对应的图，故意指向不存在的 key：
    # 这样 resolve_ingredient_image 会退到「蔬菜」的万能图，
    # 而不是错误地配一张肉类图。以后补了图直接生效。
    (("鸡毛菜", "牛蒡"), "leafy-green"),

    (("牛油果", "鳄梨"), "avocado"),            # 否则被 beef 的单字「牛」抢走
    (("花生", "花生米", "花生仁"), "peanut"),    # 否则被 rice 的单字「米」抢走
    (("南瓜子", "西瓜子", "瓜子", "松子"), "seeds"),  # 否则被 pumpkin / watermelon 抢走
    (("鸡精", "鸡粉", "鸡汤块"), "seasoning"),   # 否则被 chicken 的单字「鸡」抢走
    (("葡萄柚", "西柚"), "pomelo"),             # 否则被 grape 的「葡萄」抢走。
    # ⚠️ 这里指向「柚子」（pomelo）而不是单独的 grapefruit ——
    # 清单里只有「柚子」的图，两者外观很接近，用它的图比退到万能图好。
    (("青柠", "莱姆"), "lime"),                # 与 lemon 都含「柠」，必须先命中
    (("猕猴桃", "奇异果"), "kiwi"),             # 与「桃」类规则冲突，必须排在前面
    (("核桃",), "walnut"),                     # 同上
    # ⚠️ 这里**故意不拦「淀粉」** ——
    # 词库里有玉米淀粉/土豆淀粉/红薯淀粉/木薯淀粉/澄粉/藕粉这些具体条目，
    # 拦成笼统的 seasoning 反而把它们的专属图顶掉了。
    # 笼统的「淀粉」交给 EXTRA_ALIASES 处理（指向 cornstarch）。
)


# ============================================================
#  【关键词兜底区】—— 优先级最低，只在词库完全匹配不上时才用
#
#  词库（ingredient_lexicon.py，574 条）覆盖了绝大多数常见食材，
#  这一区是给**词库里没有的写法**兜底的，比如用户随手输的别名、
#  或者以后新出现的食材名。
#
#  ⚠️ 顺序仍然是语义：从上往下匹配、命中即停。
#  单字关键词（牛 / 米 / 鱼 / 鸡 / 粉 / 蛋 / 葱）会抢走别的食材，
#  加规则前先自问：这条会不会被上面某条先命中？
# ============================================================
KEYWORD_RULES: tuple[tuple[tuple[str, ...], str], ...] = (
    # —— 蛋奶：必须排在肉类之前（「鸡蛋」含「鸡」）——
    (("鸡蛋", "蛋", "蛋液"), "egg"),
    (("牛奶", "纯奶", "鲜奶"), "milk"),
    (("酸奶", "优酪乳"), "yogurt"),
    (("奶酪", "芝士", "黄油", "奶油"), "cheese"),

    # —— 肉类 ——
    (("鸡胸", "鸡腿", "鸡翅", "鸡肉", "整鸡", "鸡"), "chicken"),
    (("五花", "里脊", "排骨", "猪肉", "肉末", "肉丝", "肉片", "猪"), "pork"),
    (("牛腩", "牛肉", "牛排", "牛"), "beef"),
    (("火腿", "培根", "香肠", "腊肉", "午餐肉"), "ham"),
    (("羊肉", "羊排", "羊"), "lamb"),

    # —— 水产 ——
    # ⚠️ 海鲜必须排在 fish 前面：「鱿鱼」「章鱼」「墨鱼」都含「鱼」，
    # 排在后面的话会被 fish 规则整条抢走，海鲜规则里的「鱿鱼」等于白写 ——
    # 实测「鱿鱼」曾经配到一张鱼的照片。
    (("虾", "基围虾", "虾仁"), "shrimp"),
    (("蟹", "蛤", "贝", "鱿鱼", "章鱼", "墨鱼", "海鲜"), "seafood"),
    (("鱼", "鲈鱼", "鲫鱼", "草鱼", "三文鱼", "带鱼"), "fish"),

    # —— 蔬菜 ——
    # 「西红柿 / 番茄」放在黄瓜前面：两者常一起出现（凉拌），但西红柿的识别度更高
    (("西红柿", "番茄"), "tomato"),
    (("黄瓜", "青瓜"), "cucumber"),
    (("土豆", "马铃薯", "洋芋"), "potato"),
    (("胡萝卜",), "carrot"),
    (("白萝卜", "萝卜"), "radish"),
    (("白菜", "娃娃菜", "大白菜"), "cabbage"),
    (("菠菜",), "spinach"),
    (("生菜", "莴苣"), "lettuce"),
    (("西兰花", "花菜", "菜花"), "broccoli"),
    (("青椒", "尖椒", "彩椒", "柿子椒"), "green-pepper"),
    (("洋葱",), "onion"),
    (("茄子",), "eggplant"),
    # 补上「菇」和「银耳」：平菇、茶树菇、银耳以前一条都匹配不上。
    # 加单字「菇」是安全的 —— 其他规则里没有「菇」，不存在互相抢。
    (("蘑菇", "香菇", "金针菇", "杏鲍菇", "口蘑", "平菇", "茶树菇",
      "木耳", "银耳", "菌", "菇"), "mushroom"),
    (("玉米",), "corn"),
    (("南瓜",), "pumpkin"),
    (("豆角", "四季豆", "豇豆", "豌豆", "青豆"), "green-beans"),
    (("芹菜",), "celery"),

    # —— 豆制品 ——
    (("豆腐", "豆干", "腐竹", "千张", "豆皮"), "tofu"),

    # —— 主食 ——
    (("米饭", "大米", "米", "饭"), "rice"),
    (("面条", "挂面", "拉面", "意面", "面粉", "粉"), "noodles"),
    (("面包", "吐司"), "bread"),

    # —— 水果 ——
    (("苹果",), "apple"),
    (("香蕉",), "banana"),
    (("橙子", "橘子", "柑橘", "柚子"), "orange"),
    (("葡萄", "提子"), "grape"),
    (("草莓",), "strawberry"),
    (("西瓜",), "watermelon"),

    # —— 其他 ——
    # 补上「胡椒」「花椒」「八角」「孜然」：以前「黑胡椒」一条都匹配不上，
    # 只能显示占位色块，而 seasoning 那张图完全够用。
    # 放在最后是安全的：青椒/尖椒/彩椒那些规则在上面，不会被这条抢走。
    (("姜", "蒜", "葱", "香菜", "调味", "胡椒", "花椒", "八角", "孜然", "桂皮"), "seasoning"),
)

# 兼容旧名字：`tools/import-images.py` 和测试还在用 INGREDIENT_RULES 做覆盖检查。
# 实际匹配时**不用**这个合并表 —— 它丢了两区的优先级差异，
# 必须按 GUARD_RULES → 词库 → 关键词 的顺序逐级降级。
INGREDIENT_RULES: tuple[tuple[tuple[str, ...], str], ...] = GUARD_RULES + KEYWORD_RULES


# —— 手写别名：词库里没有、但用户确实会这么说的写法 ——
#
# 词库来自生成图片时的清单，覆盖的是「一种食材一个标准名」。
# 但用户输入是随意的，这里补的是**标准名之外的高频说法**。
#
# 维护原则：**只补词库里确实没有的**。
# 词库已经覆盖的（青椒、芝士、酱油、生抽…）不要写进来 ——
# 词库精确匹配在别名之前生效，写了也不会被执行，只会让人误以为它在起作用。
# 测试里有断言逐条验证别名真的生效（不生效会 FAIL）。
EXTRA_ALIASES: dict[str, str] = {
    # 水果
    "凤梨": "pineapple",        # 词库只有「菠萝」。不补的话「凤梨酥」会被「梨」抢走
    "银杏": "ginkgo-nut",       # 词库只有「白果」。不补的话「银杏」会被「杏」抢走
    "车厘子": "cherry",
    # 肉类
    "肥牛": "beef-hotpot-roll",  # 词库只有「肥牛卷」
    "肉": "pork",
    # 蔬菜
    "土豆": "potato",
    "洋芋": "potato",
    "西红柿": "tomato",
    "包菜": "cabbage",
    "圆白菜": "cabbage",
    "香菜": "cilantro",
    # 主食
    "大米": "rice",
    "米饭": "rice",
    "方便面": "instant-noodles",
    # 调味
    "白糖": "white-sugar",
    "红糖": "brown-sugar",
    # 蛋奶
    "芝士": "cheddar-cheese",
    # —— 写菜品清单时扫出来的词库缺口（2026-10-06）——
    #
    # 这些都是**家庭常做菜里的常客**，词库里却没有标准名。
    # 前四个是同一类问题：清单用的是学名/全名，日常说的是俗称。
    "西兰花": "broccoli",        # 清单里叫「羽衣甘蓝」，但日常都说西兰花
    "西蓝花": "broccoli",
    "花椰菜": "broccoli",
    "菜花": "broccoli",
    "卷心菜": "cabbage",         # 清单里叫「大白菜/小白菜」，卷心菜是另一个常见叫法
    "紫甘蓝": "cabbage",
    "甘蓝": "cabbage",
    "香椿": "香椿芽",            # 反向包含：词库名更长，子串匹配捞不到
    "豆苗": "豌豆",
    # 部位肉 → 整块肉（做法上没区别，用同一张图）
    "梅花肉": "pork",
    "前腿肉": "pork",
    "后腿肉": "pork",
    "鸭掌": "鸭肉",
    "鸭肠": "鸭肉",
    # 其他
    "可乐": "soft-drink",
    "雪碧": "soft-drink",
    "酸笋": "pickled-cabbage",   # ⚠️ 右边必须是**图片 key**，不是中文名
    "椰蓉": "coconut",
    # 笼统的「淀粉」词库里没有（只有玉米淀粉/木薯淀粉这些具体的），
    # 但厨房里说的「淀粉」通常就是玉米淀粉，用它的图。
    "淀粉": "cornstarch",
    "生粉": "cornstarch",
}


def _build_index() -> tuple[tuple[str, str], ...]:
    """把词库和别名合成一张**按名字长度倒序**的表。

    倒序是为了让子串匹配**第一次命中就是最长的那个** ——
    这是「新鲜杏鲍菇」能正确匹配到「杏鲍菇」而不是「杏」的原因。
    """
    from app.services.ingredient_lexicon import INGREDIENT_ALIASES

    merged = {**INGREDIENT_ALIASES, **EXTRA_ALIASES}
    return tuple(sorted(merged.items(), key=lambda kv: -len(kv[0])))


# 模块加载时算一次。574 条排完序之后，每次匹配都是短路的。
_MATCH_INDEX: tuple[tuple[str, str], ...] = _build_index()



def _exists(key: str) -> bool:
    return (settings.STATIC_DIR / IMAGE_SUBDIR / f"{key}{IMAGE_SUFFIX}").is_file()


def _url(key: str) -> str:
    return f"/static/{IMAGE_SUBDIR}/{key}{IMAGE_SUFFIX}"


def resolve_ingredient_key(name: str) -> str | None:
    """算出这个食材该用哪张图（只算 key，**不查磁盘**）。

    五级降级，从最精确到最宽松：

    | 级 | 依据 | 例子 |
    |---|---|---|
    | ① 挡刀区 | 手写规则，防加工品误配 | 番茄酱 → seasoning（不是番茄） |
    | ② 词库精确 | 574 条标准中文名 | 五花肉 → pork-belly |
    | ③ 别名 | 手写的高频俗称 | 车厘子 → cherry |
    | ④ **词库子串** | 名字被包含，**最长优先** | **肥羊肉 → 羊肉** |
    | ⑤ 关键词兜底 | 手写规则，覆盖词库没有的写法 | 某种新食材 |
    | ⑥ 无 | 交给调用方退到万能图 | — |

    第 ④ 级是「用户输入肥羊肉、库里只有羊肉」这类问题的解法。
    按名字长度倒序扫，**第一次命中就是最长的那个**，所以
    「新鲜杏鲍菇」会命中「杏鲍菇」而不是「杏」。

    拆出这个函数是为了能单独测规则顺序（不依赖磁盘上有没有图）。
    回归测试见 tests/test_ingredient_rules.py。
    """
    text = (name or "").strip()
    if not text:
        return None

    # ① 挡刀区：加工品、易误配的，优先级最高
    for keywords, key in GUARD_RULES:
        if any(kw in text for kw in keywords):
            return key

    # ② 词库精确匹配（③ 别名已合进 _MATCH_INDEX，见下）
    from app.services.ingredient_lexicon import INGREDIENT_ALIASES

    exact = INGREDIENT_ALIASES.get(text) or EXTRA_ALIASES.get(text)
    if exact:
        return exact

    # ④ 子串匹配：名字被包含，最长优先
    #
    # 跳过 1 字条目在这里是**故意的**：单字太容易误伤 ——
    # 「梨」会命中「凤梨酥」、「杏」会命中「银杏」。
    # 单字只在精确匹配（②）里生效，那时是用户明确输入了这个字。
    if len(text) >= 2:
        for alias, key in _MATCH_INDEX:
            if len(alias) >= 2 and alias in text:
                return key

    # ⑤ 关键词兜底
    for keywords, key in KEYWORD_RULES:
        if any(kw in text for kw in keywords):
            return key

    return None


def resolve_ingredient_image(name: str, stored_category: str | None = None) -> str | None:
    """算出这个食材该用哪张图。返回**相对 URL**，找不到返回 None。

    降级顺序（逐级往下退，**只返回磁盘上真实存在的图**）：

    1. **具体图** —— 规则表命中且有对应文件，比如「西红柿」→ tomato.jpg
    2. **分类万能图** —— 图库里没有这个食材，就用它所属分类的代表图。
       比如用户导入了「杨桃」，规则表匹配不上，但它属于水果，
       就显示 `_universal-fruit.jpg`。**这是 2026-10-03 新增的一级。**
    3. **None** —— 连万能图都没有，客户端显示「首字 + 色块」占位。

    为什么不直接返回算出来的 key：那会让客户端拿到 404，比显示占位图糟得多。
    所以每一步都要 `_exists()` 确认文件真的在。
    """
    key = resolve_ingredient_key(name)
    if key and _exists(key):
        return _url(key)

    # 具体图没有（或规则没命中）→ 退到分类万能图
    from app.services.ingredient_category import resolve_category, universal_image_key

    category = resolve_category(name, stored_category)
    fallback_key = universal_image_key(category)
    if fallback_key and _exists(fallback_key):
        logger.debug("食材 %s 没有具体图，用「%s」万能图", name, category)
        return _url(fallback_key)

    if key:
        logger.debug("食材图 %s 尚未生成，且无「%s」万能图，走占位", key, category)
    return None


def available_keys() -> list[str]:
    """已经生成好的图有哪些。供 `scripts/check_images.py` 之类的工具核对。"""
    folder = settings.STATIC_DIR / IMAGE_SUBDIR
    if not folder.is_dir():
        return []
    return sorted(p.stem for p in folder.glob(f"*{IMAGE_SUFFIX}"))
