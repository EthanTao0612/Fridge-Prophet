"""食材分类：把任意食材名归到固定的几个分类里。

冰箱页要按分类分组显示，而分类有三个来源，优先级从高到低：

1. **图片 key 的分类** —— 最准，而且保证「分类」和「配图」是一致的
   （不会出现食材在「水果」组里、配图却是蔬菜这种自相矛盾）。
2. **按食材名推断** —— 用于图库里没有的食材。比如用户导入了「杨桃」，
   规则表里没有这个 key，但名字里的「桃」能推断出它是水果。
   **这一步不依赖 AI**，所以手动添加的食材、AI 不可用时的食材都能分组。
3. **AI 返回的分类**（归一化后）—— 前面都认不出来时兜底。
   百炼可能返回「海鲜」「鱼类」「水产」等不同说法，统一映射到「水产」。

都没有就归「其他」。

## 为什么单独一个文件

分类是**跨模块共用**的：配图服务要用它选万能图，库存接口要用它分组，
客户端要用它排序。散在各处迟早会出现「冰箱页算出来是水果、
配图服务算出来是蔬菜」这种自相矛盾 —— 和之前 `_index_stock()`
踩过的坑是同一类（见 MEMORY.md 铁律 6）。
"""
from __future__ import annotations

# —— 默认分类 ——
#
# 顺序就是冰箱页的显示顺序：常吃的、量大的放前面，「其他」垫底。
# 客户端按这个顺序渲染分组。
CATEGORIES: tuple[str, ...] = (
    "蔬菜",
    "水果",
    "肉类",
    "水产",
    "蛋奶",
    "豆制品",
    "主食",
    "调味",
    "其他",
)

CATEGORY_ORDER: dict[str, int] = {name: i for i, name in enumerate(CATEGORIES)}

# —— 图片 key → 分类 ——
#
# 必须覆盖 ingredient_image_service.INGREDIENT_RULES 里出现的**每一个 key**，
# 否则那个食材会因为找不到分类而掉进「其他」。
# 有测试兜着：tests/test_ingredient_category.py 会比对两个表。
KEY_CATEGORY: dict[str, str] = {
    # 蔬菜
    "tomato": "蔬菜",
    "cucumber": "蔬菜",
    "potato": "蔬菜",
    "carrot": "蔬菜",
    "radish": "蔬菜",
    "cabbage": "蔬菜",
    "spinach": "蔬菜",
    "lettuce": "蔬菜",
    "broccoli": "蔬菜",
    "green-pepper": "蔬菜",
    "onion": "蔬菜",
    "eggplant": "蔬菜",
    "corn": "蔬菜",
    "pumpkin": "蔬菜",
    "green-beans": "蔬菜",
    "celery": "蔬菜",
    "asparagus": "蔬菜",
    "leafy-green": "蔬菜",   # 鸡毛菜、牛蒡这类「有分类但还没图」的，先占个位
    "lotus-root": "蔬菜",
    "chinese-yam": "蔬菜",
    "taro": "蔬菜",
    "bean-sprout": "蔬菜",
    "okra": "蔬菜",
    "chives": "蔬菜",
    "cilantro": "蔬菜",
    # 水果
    "apple": "水果",
    "banana": "水果",
    "orange": "水果",
    "grape": "水果",
    "strawberry": "水果",
    "watermelon": "水果",
    "lemon": "水果",
    "lime": "水果",
    "kiwi": "水果",
    "grapefruit": "水果",
    "pomegranate": "水果",
    "blueberry": "水果",
    "raspberry": "水果",
    "pineapple": "水果",
    "mango": "水果",
    "peach": "水果",
    "pear": "水果",
    "cherry": "水果",
    "avocado": "水果",
    "dragon-fruit": "水果",
    "plum": "水果",
    "persimmon": "水果",
    "lychee": "水果",
    "papaya": "水果",
    "cantaloupe": "水果",
    # 肉类
    "chicken": "肉类",
    "pork": "肉类",
    "beef": "肉类",
    "ham": "肉类",
    "lamb": "肉类",
    "duck": "肉类",
    "chicken-wing": "肉类",
    "pork-ribs": "肉类",
    "pork-belly": "肉类",
    "bacon": "肉类",
    "sausage": "肉类",
    "beef-brisket": "肉类",
    # 水产
    "shrimp": "水产",
    "fish": "水产",
    "seafood": "水产",
    "crab": "水产",
    "clam": "水产",
    "squid": "水产",
    "hairtail": "水产",
    "bass": "水产",
    "oyster": "水产",
    # 蛋奶
    "egg": "蛋奶",
    "milk": "蛋奶",
    "yogurt": "蛋奶",
    "cheese": "蛋奶",
    "duck-egg": "蛋奶",
    "quail-egg": "蛋奶",
    "butter": "蛋奶",
    "cream": "蛋奶",
    # 豆制品
    "tofu": "豆制品",
    # 主食
    "rice": "主食",
    "noodles": "主食",
    "bread": "主食",
    "dumpling": "主食",
    "bun": "主食",
    "steamed-bun": "主食",
    "rice-cake": "主食",
    "vermicelli": "主食",
    # 调味
    "seasoning": "调味",
    "starch": "调味",
    "soy-sauce": "调味",
    "vinegar": "调味",
    "chili": "调味",
    "sichuan-pepper": "调味",
    "cumin": "调味",
    "curry": "调味",
    "ketchup": "调味",
    "oyster-sauce": "调味",
    # 菌菇归蔬菜（不单独开一类，否则只有一两样时分组很空）
    "mushroom": "蔬菜",
    "enoki": "蔬菜",
    "oyster-mushroom": "蔬菜",
    "wood-ear": "蔬菜",
    # 坚果单独归「其他」—— 数量少，不值得单开一组
    "peanut": "其他",
    "seeds": "其他",
    "walnut": "其他",
    "almond": "其他",
    "cashew": "其他",
    "pistachio": "其他",
    "sunflower-seed": "其他",
}

# —— 分类 → 万能图 key ——
#
# 「万能图」是每个分类各一张的代表性图片，用于**图库里没有的食材**。
# 比如用户导入了「杨桃」，规则表匹配不上具体图，但它属于水果，
# 就显示「水果」这张万能图 —— 比空白占位好得多，也不会配错图。
#
# 这些 key 都以 `_` 开头，和真实食材区分开，也便于在目录里一眼认出来。
# 图片由 Ethan 提供，放到 backend/static/ingredients/ 下。
CATEGORY_IMAGE_KEY: dict[str, str] = {
    "蔬菜": "_universal-vegetable",
    "水果": "_universal-fruit",
    "肉类": "_universal-meat",
    "水产": "_universal-seafood",
    "蛋奶": "_universal-dairy-egg",
    "豆制品": "_universal-soy",
    "主食": "_universal-staple",
    "调味": "_universal-seasoning",
    "其他": "_universal-other",
}

# —— AI 返回值的归一化 ——
#
# 百炼返回的分类说法不统一，同一个意思可能有好几种写法。
# 这里把它们都映射到上面的 9 个分类。
CATEGORY_ALIASES: dict[str, str] = {
    # 水产
    "海鲜": "水产", "鱼类": "水产", "鱼": "水产", "虾": "水产", "蟹": "水产",
    "贝类": "水产", "水产品": "水产", "海产": "水产",
    # 肉类
    "肉": "肉类", "红肉": "肉类", "白肉": "肉类", "禽肉": "肉类", "畜肉": "肉类",
    "猪牛羊肉": "肉类",
    # 蛋奶
    "蛋类": "蛋奶", "蛋": "蛋奶", "鸡蛋": "蛋奶",
    "乳制品": "蛋奶", "奶制品": "蛋奶", "奶": "蛋奶", "乳品": "蛋奶",
    # 蔬菜
    "青菜": "蔬菜", "叶菜": "蔬菜", "蔬菜类": "蔬菜", "菌菇": "蔬菜", "菇类": "蔬菜",
    # 水果
    "水果类": "水果", "鲜果": "水果",
    # 主食
    "主食类": "主食", "谷类": "主食", "谷物": "主食", "米面": "主食", "淀粉类主食": "主食",
    # 豆制品
    "豆类": "豆制品", "豆制品类": "豆制品",
    # 调味
    "调料": "调味", "调味料": "调味", "调味品": "调味", "佐料": "调味",
    # 其他
    "其它": "其他", "未知": "其他", "干货": "其他", "零食": "其他",
}

# —— 关键词推断 ——
#
# 用于**图库里没有的食材**。比如「杨桃」不在规则表里，但名字里的「桃」
# 能推断出它是水果。
#
# ⚠️ 顺序仍然是语义：从上往下匹配、命中即停。
# 和图片规则表一样，具体要排在笼统前面 —— 这里最典型的陷阱是「瓜」：
# 西瓜/哈密瓜是水果，黄瓜/冬瓜/南瓜是蔬菜。
# 所以**水果那一条必须排在蔬菜前面**，并且把西瓜、哈密瓜明确写进水果。
CATEGORY_KEYWORDS: tuple[tuple[tuple[str, ...], str], ...] = (
    # —— 例外区：会被单字关键词误伤的食材，必须排在最前面 ——
    #
    # 和图片规则表的「挡刀区」是同一个道理：下面有「油」「豆」「米」这些
    # 单字关键词，它们会抢走本不该属于自己的食材。实测踩到两个：
    #   「豆浆」被蔬菜的「豆」抢走、「油条」被调味的「油」抢走。
    (("油条", "油饼", "油面筋", "油茶", "油墩子"), "主食"),
    (("油菜", "油麦菜"), "蔬菜"),
    (("豆浆", "豆奶", "豆腐脑", "豆花", "腐乳", "豆豉", "纳豆"), "豆制品"),
    # 各种奶：被肉类的「牛」「羊」抢走。「牛奶」因为有图片规则兜着没事，
    # 但「羊奶」「马奶」这些没有图片规则，只能靠这里拦。
    (("羊奶", "马奶", "骆驼奶", "水牛奶", "奶粉", "鲜奶", "纯奶", "酸奶"), "蛋奶"),
    # 名字带动物但其实是素菜
    (("鸡腿菇", "鸡枞", "鸡毛菜", "牛蒡", "牛肝菌"), "蔬菜"),

    # 水果（必须排在蔬菜之前，「瓜」「桃」会互相抢）
    (("西瓜", "哈密瓜", "甜瓜", "香瓜", "木瓜", "佛手瓜"), "水果"),
    (("苹果", "梨", "桃", "猕猴桃", "奇异果", "李", "杏", "枣", "柿",
      "樱桃", "车厘子", "莓", "葡萄", "提子", "橙", "柑", "橘", "柚",
      "柠檬", "柠", "蕉", "菠萝", "凤梨", "芒果", "荔枝", "龙眼", "桂圆",
      "石榴", "火龙果", "牛油果", "鳄梨", "杨梅", "枇杷", "无花果", "椰子",
      "百香果", "榴莲", "山竹", "莲雾", "杨桃", "果"), "水果"),
    # 水产
    (("鱼", "虾", "蟹", "蛤", "贝", "鱿鱼", "章鱼", "墨鱼", "海参",
      "鲍鱼", "生蚝", "牡蛎", "扇贝", "海带", "紫菜", "海苔", "裙带菜"), "水产"),
    # 肉类
    (("猪", "牛", "羊", "鸡", "鸭", "鹅", "肉", "排骨", "里脊", "五花",
      "培根", "火腿", "香肠", "腊肠", "午餐肉", "鸡翅", "鸡腿", "牛腩"), "肉类"),
    # 蛋奶
    (("蛋", "奶", "乳", "芝士", "奶酪", "黄油", "奶油"), "蛋奶"),
    # 豆制品
    (("豆腐", "豆干", "豆皮", "腐竹", "千张", "油豆腐", "豆泡"), "豆制品"),
    # 主食
    (("米", "面", "饭", "馒头", "包子", "饺", "馄饨", "年糕", "粉丝",
      "粉条", "面包", "吐司", "粥", "麦片", "燕麦"), "主食"),
    # 调味
    (("酱", "醋", "盐", "糖", "油", "胡椒", "花椒", "八角", "孜然", "咖喱",
      "味精", "鸡精", "蚝油", "料酒", "淀粉", "生粉", "调味"), "调味"),
    # 蔬菜（放最后，「瓜」在这里才安全 —— 水果的瓜已经在上面被截走了）
    (("菜", "瓜", "椒", "茄", "豆", "葱", "蒜", "姜", "萝卜", "薯", "笋",
      "藕", "菇", "耳", "苗", "芽", "芹", "莴", "菠", "荠", "茭", "莲"), "蔬菜"),
)


def normalize_category(raw: str | None) -> str | None:
    """把 AI 返回的分类归一化成 9 个分类之一；认不出来返回 None。

    注意返回 None 表示「这个值没用」，而不是「归到其他」——
    调用方需要区分「AI 说了但我不认识」和「AI 什么都没说」。
    """
    text = (raw or "").strip()
    if not text:
        return None
    if text in CATEGORY_ORDER:
        return text
    return CATEGORY_ALIASES.get(text)


def guess_category_by_name(name: str) -> str | None:
    """只按食材名推断分类，认不出来返回 None。**不依赖 AI、不查磁盘。**"""
    text = (name or "").strip()
    if not text:
        return None
    for keywords, category in CATEGORY_KEYWORDS:
        if any(kw in text for kw in keywords):
            return category
    return None


def resolve_category(name: str, stored: str | None = None) -> str:
    """算出这个食材该归到哪个分类。**永远不会返回 None**，兜底是「其他」。

    优先级：图片 key 的分类 > 名字推断 > AI 存的分类 > 其他。

    为什么图片 key 排第一：它保证「分类」和「配图」一致。
    如果让 AI 的分类优先，就可能出现食材被分到「水果」组、
    配图却是蔬菜规则命中的那张图 —— 用户看到会觉得很怪。

    为什么名字推断排在 AI 前面：AI 的分类只在扫描入库时才有，
    手动添加的食材、以及 AI 不可用（MOCK 模式）时都是空的。
    名字推断这两种情况都能用，结果也更稳定（同一个名字永远同一类）。
    """
    from app.services.ingredient_image_service import resolve_ingredient_key

    key = resolve_ingredient_key(name)
    if key and key in KEY_CATEGORY:
        return KEY_CATEGORY[key]

    guessed = guess_category_by_name(name)
    if guessed:
        return guessed

    normalized = normalize_category(stored)
    if normalized:
        return normalized

    return "其他"


def universal_image_key(category: str) -> str | None:
    """这个分类的万能图 key；分类不认识返回 None。"""
    return CATEGORY_IMAGE_KEY.get(category)


def sort_key(category: str) -> int:
    """分类的显示顺序。认不出来的排到最后。"""
    return CATEGORY_ORDER.get(category, len(CATEGORIES))
