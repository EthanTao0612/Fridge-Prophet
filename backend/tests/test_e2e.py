"""端到端冒烟测试：把策划书里的完整闭环跑一遍。

    python tests/test_e2e.py

使用临时 SQLite 库，不会污染开发数据。不需要 AI 密钥——
MOCK 模式会返回内置食材和菜谱，链路照样完整。
"""
from __future__ import annotations

import base64
import io
import os
import sys
import tempfile
from datetime import date, timedelta
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_DIR))

# 必须在导入 app 之前设置，config 是模块级单例
_DB_FILE = Path(tempfile.gettempdir()) / "fridge_prophet_test.db"
_DB_FILE.unlink(missing_ok=True)
os.environ["DATABASE_URL"] = f"sqlite:///{_DB_FILE.as_posix()}"
os.environ["DASHSCOPE_API_KEY"] = ""

from fastapi.testclient import TestClient  # noqa: E402

from app.main import app  # noqa: E402

PASSED: list[str] = []
FAILED: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    if condition:
        PASSED.append(name)
        print(f"  [OK]   {name}")
    else:
        FAILED.append(f"{name} :: {detail}")
        print(f"  [FAIL] {name}  {detail}")


def main() -> int:
    # 1x1 透明 PNG，用来冒充冰箱照片
    fake_png = base64.b64decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8"
        "z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    )

    with TestClient(app) as client:
        print("\n=== 0. 系统接口 ===")
        r = client.get("/health")
        check("健康检查", r.status_code == 200 and r.json()["status"] == "healthy", r.text[:200])
        r = client.get("/")
        check("服务信息", r.status_code == 200, r.text[:200])

        print("\n=== 1. 注册与登录 ===")
        r = client.post(
            "/api/v1/auth/register",
            json={"email": "demo@fridge.com", "password": "demo123456", "nickname": "小明"},
        )
        check("注册成功", r.status_code == 201, r.text[:300])
        if r.status_code != 201:
            return report()
        token = r.json()["access_token"]
        check("注册直接返回令牌", bool(token))
        check("新用户 onboarded 为 false", r.json()["user"]["onboarded"] is False)

        r = client.post(
            "/api/v1/auth/login",
            json={"email": "demo@fridge.com", "password": "demo123456"},
        )
        check("登录成功", r.status_code == 200, r.text[:200])

        r = client.post(
            "/api/v1/auth/login",
            json={"email": "demo@fridge.com", "password": "wrong-password"},
        )
        check("错误密码被拒绝", r.status_code == 401, r.text[:200])

        r = client.get("/api/v1/auth/me")
        check("无令牌访问被拒绝", r.status_code == 401, r.text[:200])

        headers = {"Authorization": f"Bearer {token}"}

        r = client.get("/api/v1/auth/me", headers=headers)
        check("携带令牌可访问", r.status_code == 200, r.text[:200])

        print("\n=== 2. 用户画像 ===")
        r = client.get("/api/v1/users/profile", headers=headers)
        check("获取画像", r.status_code == 200, r.text[:300])
        check("画像含三段结构",
              all(k in r.json() for k in ("user", "preference", "health", "family_members")))

        r = client.put(
            "/api/v1/users/preference",
            headers=headers,
            json={
                "cuisine": "家常菜",
                "taste": "微辣",
                "cook_time_max": 30,
                "diet_goal": "减脂",
                "disliked_foods": ["香菜", "芹菜"],
                "allergies": ["海鲜"],
            },
        )
        check("保存饮食偏好", r.status_code == 200, r.text[:300])
        check("onboarded 置为 true", r.json().get("onboarded") is True)

        r = client.put(
            "/api/v1/users/health",
            headers=headers,
            json={"high_protein": True, "low_fat": True, "height_cm": 178, "weight_kg": 74,
                  "age": 22, "activity_level": "每周3-4次"},
        )
        check("保存健康设置", r.status_code == 200, r.text[:300])

        r = client.post(
            "/api/v1/users/family",
            headers=headers,
            json={"name": "爷爷", "relation": "祖父", "diet_goal": "低糖", "taste": "清淡",
                  "disliked_foods": ["辣椒"], "allergies": []},
        )
        check("添加家庭成员", r.status_code == 201, r.text[:300])
        member_id = r.json()["id"] if r.status_code == 201 else 0

        r = client.get("/api/v1/users/family", headers=headers)
        check("家庭成员列表", r.status_code == 200 and len(r.json()) == 1, r.text[:200])

        print("\n=== 3. AI 冰箱扫描 ===")
        r = client.get("/api/v1/vision/status", headers=headers)
        check("AI 状态接口", r.status_code == 200 and "ai_enabled" in r.json(), r.text[:200])

        r = client.post(
            "/api/v1/vision/scan",
            headers=headers,
            files={"file": ("fridge.png", io.BytesIO(fake_png), "image/png")},
        )
        check("上传照片识别", r.status_code == 200, r.text[:400])
        scan = r.json() if r.status_code == 200 else {}
        check("返回 foods 列表", len(scan.get("foods", [])) > 0, str(scan)[:300])
        check("每条结果带置信度",
              all("confidence" in f for f in scan.get("foods", [])))

        r = client.post(
            "/api/v1/vision/scan",
            headers=headers,
            files={"file": ("bad.txt", io.BytesIO(b"not an image"), "text/plain")},
        )
        check("非图片格式被拒绝", r.status_code == 415, r.text[:200])

        print("\n=== 4. 确认入库 ===")
        foods = [
            {"name": "鸡蛋", "quantity": 6, "unit": "个", "confidence": 0.96,
             "category": "蛋类", "storage_location": "冷藏", "shelf_life_days": 30},
            {"name": "西红柿", "quantity": 3, "unit": "个", "confidence": 0.91,
             "category": "蔬菜", "storage_location": "冷藏", "shelf_life_days": 7},
            {"name": "豆腐", "quantity": 1, "unit": "盒", "confidence": 0.88,
             "category": "豆制品", "storage_location": "冷藏", "shelf_life_days": 2},
            {"name": "鸡胸肉", "quantity": 450, "unit": "g", "confidence": 0.84,
             "category": "肉类", "storage_location": "冷冻", "shelf_life_days": 30},
        ]
        r = client.post("/api/v1/inventory/confirm", headers=headers, json={"foods": foods})
        check("确认写入库存", r.status_code == 201 and len(r.json()) == 4, r.text[:400])
        check("自动算出保质期",
              all(item.get("expiry_date") for item in r.json()) if r.status_code == 201 else False)
        check("自动标记新鲜度",
              all(item.get("freshness") for item in r.json()) if r.status_code == 201 else False)

        r = client.post("/api/v1/inventory/confirm", headers=headers,
                        json={"foods": [foods[0]]})
        check("同名食材再次扫描会累加",
              r.status_code == 201 and r.json()[0]["quantity"] == 12,
              r.text[:300])

        print("\n=== 5. 库存查询 ===")
        r = client.get("/api/v1/inventory", headers=headers)
        check("库存列表", r.status_code == 200 and len(r.json()) == 4, r.text[:300])
        check("带 days_left 字段", all("days_left" in i for i in r.json()))
        check("库存列表带 image_url 字段",
              all("image_url" in i for i in r.json()), "字段缺失，客户端拿不到食材配图")
        check("食材 image_url 是相对路径或 None",
              all(i["image_url"] is None or i["image_url"].startswith("/static/") for i in r.json()),
              str([i.get("image_url") for i in r.json()]))

        from app.services.ingredient_image_service import (  # noqa: PLC0415
            INGREDIENT_RULES,
            resolve_ingredient_key,
        )
        egg_idx = next(i for i, (_kw, key) in enumerate(INGREDIENT_RULES) if key == "egg")
        chicken_idx = next(i for i, (_kw, key) in enumerate(INGREDIENT_RULES) if key == "chicken")
        check("食材图规则：蛋类排在鸡肉前面",
              egg_idx < chicken_idx,
              "否则『鸡蛋』会先命中单字『鸡』，被配成鸡肉图")
        check("食材图规则：鸡蛋理论命中 egg",
              resolve_ingredient_key("鸡蛋") == "egg",
              f"实际 {resolve_ingredient_key('鸡蛋')}")
        # 574 张图导入后，词库能给出更精确的 key（鸡胸肉 -> chicken-breast），
        # 比早期的通用 key（chicken）更好。
        check("食材图规则：鸡胸肉理论命中 chicken-breast",
              resolve_ingredient_key("鸡胸肉") == "chicken-breast",
              f"实际 {resolve_ingredient_key('鸡胸肉')}")
        # 「肥羊肉」这类词库里没有的写法，要靠子串匹配兜住
        check("食材图规则：肥羊肉能认成羊肉",
              resolve_ingredient_key("肥羊肉") == "lamb",
              f"实际 {resolve_ingredient_key('肥羊肉')}")

        r = client.get("/api/v1/inventory?storage_location=冷冻", headers=headers)
        check("按位置筛选", r.status_code == 200 and len(r.json()) == 1, r.text[:200])

        r = client.get("/api/v1/inventory/expiring?within_days=3", headers=headers)
        check("临期查询", r.status_code == 200, r.text[:300])
        expiring_names = [i["food_name"] for i in r.json()] if r.status_code == 200 else []
        check("豆腐被判为临期", "豆腐" in expiring_names, str(expiring_names))

        r = client.get("/api/v1/inventory/stats", headers=headers)
        check("库存概览", r.status_code == 200 and r.json()["total_kinds"] == 4, r.text[:300])

        print("\n=== 5b. 购买日期与保质期手选 ===")
        today = date.today()
        d = lambda n: str(today + timedelta(days=n))  # noqa: E731

        r = client.post("/api/v1/inventory", headers=headers, json={
            "food_name": "测试酸奶", "quantity": 2, "unit": "盒", "category": "乳制品",
            "purchase_date": d(-3), "expiry_date": d(4),
        })
        check("手动添加时指定购买与过期日期", r.status_code == 201, r.text[:300])
        added = r.json() if r.status_code == 201 else {}
        check("购买日期按用户指定的存下来", added.get("purchase_date") == d(-3),
              str(added.get("purchase_date")))
        check("过期日期按用户指定的存下来", added.get("expiry_date") == d(4),
              str(added.get("expiry_date")))
        check("days_left 按用户指定的日期算", added.get("days_left") == 4,
              str(added.get("days_left")))

        r = client.post("/api/v1/inventory", headers=headers, json={
            "food_name": "测试面包", "quantity": 1, "unit": "袋", "category": "主食",
            "purchase_date": d(-2), "shelf_life_days": 5,
        })
        check("只给保质期天数也能添加", r.status_code == 201, r.text[:300])
        added2 = r.json() if r.status_code == 201 else {}
        check("过期日期 = 购买日期 + 保质期天数", added2.get("expiry_date") == d(3),
              str(added2.get("expiry_date")))

        r = client.post("/api/v1/inventory", headers=headers, json={
            "food_name": "测试错误日期", "quantity": 1, "unit": "个",
            "purchase_date": d(0), "expiry_date": d(-1),
        })
        check("过期日期早于购买日期被拒绝", r.status_code == 422, r.text[:200])

        r = client.post("/api/v1/inventory/confirm", headers=headers, json={"foods": [{
            "name": "测试火腿", "quantity": 1, "unit": "包", "confidence": 0.9,
            "category": "肉类", "storage_location": "冷藏", "shelf_life_days": 30,
            "purchase_date": d(-5), "expiry_date": d(2),
        }]})
        check("扫描确认时可携带用户指定的日期", r.status_code == 201, r.text[:300])
        conf = r.json()[0] if r.status_code == 201 else {}
        check("用户指定的日期优先于 AI 的 shelf_life_days",
              conf.get("expiry_date") == d(2), str(conf.get("expiry_date")))

        r = client.post("/api/v1/inventory/confirm", headers=headers, json={"foods": [{
            "name": "测试火腿", "quantity": 1, "unit": "包", "confidence": 0.9,
            "category": "肉类", "storage_location": "冷藏", "expiry_date": d(10),
        }]})
        merged = r.json()[0] if r.status_code == 201 else {}
        check("同名食材数量累加", merged.get("quantity") == 2, str(merged.get("quantity")))
        check("同名食材累加后取更早的过期日期（宁可早提醒）",
              merged.get("expiry_date") == d(2), str(merged.get("expiry_date")))

        # 清掉本节新增的数据，避免影响后面的采购计算
        for _name in ("测试酸奶", "测试面包", "测试火腿"):
            _r = client.get(f"/api/v1/inventory?keyword={_name}", headers=headers)
            for _it in (_r.json() if _r.status_code == 200 else []):
                client.delete(f"/api/v1/inventory/{_it['id']}", headers=headers)
        r = client.get("/api/v1/inventory", headers=headers)
        check("本节数据已清理，库存回到 4 项",
              r.status_code == 200 and len(r.json()) == 4, r.text[:200])

        print("\n=== 6. AI 菜谱生成 ===")
        r = client.post("/api/v1/recipes/generate", headers=headers,
                        json={"count": 3, "prioritize_expiring": True, "save": True})
        check("生成菜谱", r.status_code == 200, r.text[:500])
        gen = r.json() if r.status_code == 200 else {}
        recipes = gen.get("recipes", [])
        check("返回 3 道菜", len(recipes) == 3, f"实际 {len(recipes)} 道")
        if recipes:
            first = recipes[0]
            check("含严格 JSON 字段",
                  all(k in first for k in ("name", "time_minutes", "difficulty",
                                           "ingredients", "missing_ingredients",
                                           "steps", "nutrition")))
            check("食材带 available 标记",
                  all("available" in i for i in first["ingredients"]))
            check("营养数据带免责声明",
                  "disclaimer" in first["nutrition"] and "估算" in first["nutrition"]["disclaimer"])
            check("遵守时间上限",
                  all(r["time_minutes"] <= 30 for r in recipes),
                  str([r["time_minutes"] for r in recipes]))
            check("过敏原海鲜未出现",
                  not any("虾" in i["name"] or "蟹" in i["name"] or "鱼" in i["name"]
                          for r in recipes for i in r["ingredients"]))
            check("不吃的香菜未出现",
                  not any("香菜" in i["name"] for r in recipes for i in r["ingredients"]))
            check("缺料被正确识别",
                  any(r["missing_ingredients"] for r in recipes),
                  "所有菜都显示不缺料，可能是库存重算有问题")

        print("\n=== 6b. 菜谱配图 ===")
        # 配图是「有图用图、没图降级」的设计，所以这里分两层测：
        #   ① 接口契约：字段必须在、类型必须对（不依赖图片库是否为空）
        #   ② 匹配规则：纯函数，确定性，跟磁盘上有没有图无关
        from app.services.food_image_service import (  # noqa: PLC0415
            DEFAULT_KEY,
            available_images,
            candidate_keys,
            resolve_image_key,
            resolve_image_url,
            rule_key,
        )

        check("生成结果带 image_url 字段",
              all("image_url" in r for r in recipes), "字段缺失，客户端拿不到配图")
        check("image_url 是相对路径或 None",
              all(r["image_url"] is None or r["image_url"].startswith("/static/")
                  for r in recipes),
              str([r.get("image_url") for r in recipes]))

        r = client.get("/api/v1/recipes", headers=headers)
        listed = r.json() if r.status_code == 200 else []
        check("菜谱列表也带 image_url",
              r.status_code == 200 and all("image_url" in x for x in listed),
              r.text[:200])

        # 注意：recipe_id 要到下一节才定义，这里不能借用
        img_recipe_id = listed[0]["id"] if listed else 0
        r = client.get(f"/api/v1/recipes/{img_recipe_id}", headers=headers)
        check("菜谱详情也带 image_url",
              r.status_code == 200 and "image_url" in r.json(), r.text[:200])

        # 匹配规则：确定性 —— 同一个菜名问两次必须给同一个 key
        # 用 rule_key（纯规则、不查磁盘），这样图片库是空的时候也能验证规则表
        k1 = rule_key("番茄炒蛋", ["西红柿", "鸡蛋"])
        k2 = rule_key("番茄炒蛋", ["西红柿", "鸡蛋"])
        check("同一道菜两次匹配结果一致", k1 == k2, f"{k1} != {k2}")
        check("常见菜能命中具体规则", k1 == "tomato-egg", f"实际 {k1}")

        # 具体菜名优先于笼统菜名：番茄鸡蛋豆腐不该被番茄炒蛋的规则抢走
        check("更具体的菜名优先匹配",
              rule_key("番茄鸡蛋豆腐", ["西红柿", "鸡蛋", "豆腐"]) == "tomato-egg-tofu",
              f"实际 {rule_key('番茄鸡蛋豆腐', ['西红柿', '鸡蛋', '豆腐'])}，"
              "规则表顺序有问题（具体菜名要排在笼统菜名前面）")

        # 菜名没线索时，应该退回看食材
        check("菜名无线索时用食材兜底",
              rule_key("妈妈的拿手菜", ["西兰花", "鸡胸肉"]) is not None,
              "食材里有西兰花/鸡胸肉却没匹配到任何规则")

        # 回归：食材规则里的单字关键词会互相抢。
        # 鸡肉规则含单字「鸡」，而「鸡蛋」也含「鸡」，所以蛋类规则必须排在它前面。
        check("「鸡蛋」归到蛋类而不是被鸡肉规则抢走",
              rule_key("妈妈的拿手菜", ["鸡蛋", "青椒"]) == "egg",
              f"实际 {rule_key('妈妈的拿手菜', ['鸡蛋', '青椒'])}，"
              "蛋类规则要排在鸡肉规则前面（否则「鸡」会先吃掉「鸡蛋」）")
        check("「鸡肉」仍归到鸡肉类（修蛋类顺序时没误伤）",
              rule_key("妈妈的拿手菜", ["鸡胸肉"]) == "chicken",
              f"实际 {rule_key('妈妈的拿手菜', ['鸡胸肉'])}")
        check("「青椒炒鸡蛋」命中青椒炒蛋专属图",
              rule_key("青椒炒鸡蛋", ["青椒", "鸡蛋"]) == "green-pepper-egg",
              f"实际 {rule_key('青椒炒鸡蛋', ['青椒', '鸡蛋'])}")

        # 完全陌生的菜（一个关键词都命中不了）必须能退到 default，候选链末尾也一定是 default。
        # 注意菜名里不能带「炒饭」「鸡」这类会被规则命中的词，否则测的就不是「陌生菜」了。
        weird_name = "宇宙无敌霹雳XYZ"
        weird = candidate_keys(weird_name, ["不存在的食材"])
        check("陌生菜名的候选链以 default 收尾",
              weird and weird[-1] == DEFAULT_KEY, str(weird))
        # default.jpg 没生成时返回 None，生成了就返回 default —— 两者都是正常降级
        check("陌生菜名能安全降级（不抛异常）",
              resolve_image_key(weird_name, ["不存在的食材"]) in (DEFAULT_KEY, None),
              str(resolve_image_key(weird_name, ["不存在的食材"])))

        # 降级链的核心保证：命中了一个「图片还没生成」的 key 时，必须继续往下退到
        # 真实存在的图，**绝不能返回一个会 404 的地址**。
        # 这条在「有图」「没图」两种状态下都必须成立，所以断言的是
        # 「返回的 key 对应的文件真实存在」，而不是某个具体的 key。
        from app.core.config import settings as _settings  # noqa: PLC0415

        for probe_name in ("糖醋里脊", "蒜蓉西兰花", "红烧狮子头"):
            key = resolve_image_key(probe_name, [])
            if key is None:
                continue  # 图片库为空，退到 None 也是合法的
            check(f"降级返回的图真实存在（{probe_name}）",
                  (_settings.STATIC_DIR / "recipes" / f"{key}.jpg").is_file(),
                  f"返回 {key}，但该文件不存在 → 客户端会拿到 404")

        # 候选链必须是「具体 → 笼统 → default」的顺序，否则文件缺失时退不对
        chain = candidate_keys("番茄炒蛋", ["西红柿", "鸡蛋"])
        check("候选链顺序为 具体→笼统→兜底",
              chain[-1] == DEFAULT_KEY and len(chain) >= 2, str(chain))

        imgs = available_images()
        if imgs:
            probe = resolve_image_url("番茄炒蛋", ["西红柿", "鸡蛋"])
            r = client.get(probe) if probe else None
            check("配图可通过 /static 访问",
                  r is not None and r.status_code == 200,
                  f"{probe} → {r.status_code if r else 'N/A'}")
        else:
            # 图片库为空是合法状态（代码先铺好、图片后补），接口必须照常工作
            r = client.get("/api/v1/recipes", headers=headers)
            check("图片库为空时接口仍正常", r.status_code == 200, r.text[:200])
            check("图片库为空时降级为 None",
                  all(x["image_url"] is None for x in r.json()),
                  "没有图片却返回了 URL，客户端会显示裂图")

        print("\n=== 6c. 菜谱去重（回归） ===")
        # 回归：修复前 /generate 是无条件 INSERT。用户反复点「生成新菜谱」，
        # 同名菜就会在库里攒成十几条（实测「番茄鸡蛋豆腐」重复 11 次），
        # 菜谱页看起来就是同一道菜刷屏。这里连打 3 次验证不再发生。
        import sqlite3  # noqa: PLC0415

        before = client.get("/api/v1/recipes", headers=headers).json()
        before_names = [x["name"] for x in before]

        for _ in range(3):
            r = client.post("/api/v1/recipes/generate", headers=headers,
                            json={"count": 3, "prioritize_expiring": True, "save": True})
            check("反复生成仍然 200", r.status_code == 200, r.text[:300])

        after = client.get("/api/v1/recipes", headers=headers).json()
        after_names = [x["name"] for x in after]

        check("列表里没有同名菜谱",
              len(after_names) == len(set(after_names)),
              f"重名的有：{sorted({n for n in after_names if after_names.count(n) > 1})}")

        # 测试环境强制 MOCK，每次返回的都是同样三道菜，
        # 所以「总数不增长」可以严格断言（AI 模式下菜名会变，不能这么测）
        check("MOCK 下反复生成不新增行",
              len(after) == len(before),
              f"{len(before)} → {len(after)} 条，说明又在重复入库")

        # 直接查库，确认物理行数也没涨 —— 列表层去重只是兜底，
        # 库里如果还在堆重复行，用户删一条就会冒出另一条
        conn = sqlite3.connect(_DB_FILE)
        dup_rows = conn.execute(
            "SELECT name, COUNT(*) c FROM recipes GROUP BY name HAVING c > 1"
        ).fetchall()
        conn.close()
        check("数据库里没有同名菜谱行", not dup_rows, str(dup_rows))

        # 单次返回内部也不能重名（模型偶尔把同一道菜给两遍）
        r = client.post("/api/v1/recipes/generate", headers=headers,
                        json={"count": 3, "save": False})
        gen_names = [x["name"] for x in r.json().get("recipes", [])]
        check("单次生成的返回里没有重名",
              len(gen_names) == len(set(gen_names)), str(gen_names))

        # 反向验证：去重不能变成「这道菜以后永远不再生成」。
        # 删掉一道后重新生成，它必须能回来。
        victim = after[0]
        client.delete(f"/api/v1/recipes/{victim['id']}", headers=headers)
        client.post("/api/v1/recipes/generate", headers=headers,
                    json={"count": 3, "prioritize_expiring": True, "save": True})
        regen_names = [
            x["name"] for x in client.get("/api/v1/recipes", headers=headers).json()
        ]
        check("删掉的菜重新生成能回来",
              victim["name"] in regen_names,
              f"删了「{victim['name']}」，重新生成后列表是 {regen_names}")

        print("\n=== 6d. 做菜扣库存 ===")
        # 造一份确定的库存，这样扣减结果能精确断言。
        # 选「番茄鸡蛋豆腐」当样本，它正好覆盖三种情况：
        #   鸡蛋   → 单位一致，能自动算
        #   豆腐   → 库存记「盒」、菜谱要「g」，单位对不上
        #   小葱   → 冰箱里压根没有
        client.delete("/api/v1/inventory", headers=headers)
        for food, qty, unit in (("鸡蛋", 6, "个"), ("西红柿", 3, "个"), ("豆腐", 1, "盒")):
            client.post("/api/v1/inventory", headers=headers,
                        json={"food_name": food, "quantity": qty, "unit": unit})

        def stock_of(food: str):
            rows = client.get("/api/v1/inventory", headers=headers).json()
            hit = next((x for x in rows if x["food_name"] == food), None)
            return None if hit is None else hit["quantity"]

        pool = client.get("/api/v1/recipes", headers=headers).json()
        target = next((x for x in pool if x["name"] == "番茄鸡蛋豆腐"), None)
        check("找到样本菜谱「番茄鸡蛋豆腐」", target is not None,
              str([x["name"] for x in pool]))
        cook_id = target["id"] if target else pool[0]["id"]

        # ---- 预览：只算不扣 ----
        r = client.get(f"/api/v1/recipes/{cook_id}/cook-plan", headers=headers)
        check("扣减预览 200", r.status_code == 200, r.text[:300])
        plan = r.json() if r.status_code == 200 else {}
        items = {i["name"]: i for i in plan.get("items", [])}

        check("鸡蛋匹配到了库存行",
              items.get("鸡蛋", {}).get("stock_item_id") is not None,
              str(items.get("鸡蛋")))
        check("单位一致 → 建议扣 2 个",
              items.get("鸡蛋", {}).get("suggested_deduct") == 2
              and items.get("鸡蛋", {}).get("unit_matched") is True,
              str(items.get("鸡蛋")))
        check("单位对不上（盒 vs g）→ 不猜，建议量为空",
              items.get("豆腐", {}).get("unit_matched") is False
              and items.get("豆腐", {}).get("suggested_deduct") is None,
              str(items.get("豆腐")))
        check("冰箱里没有的小葱归到 missing，不参与扣减",
              "小葱" not in items
              and any(m["name"] == "小葱" for m in plan.get("missing", [])),
              str(plan.get("missing")))
        check("预览不修改库存", stock_of("鸡蛋") == 6,
              f"预览后鸡蛋 = {stock_of('鸡蛋')}")

        # ---- 执行：按系统估算扣 ----
        r = client.post(f"/api/v1/recipes/{cook_id}/cook", headers=headers, json={})
        check("执行扣减 200", r.status_code == 200, r.text[:300])
        result = r.json() if r.status_code == 200 else {}
        check("鸡蛋 6 → 4", stock_of("鸡蛋") == 4, f"实际 {stock_of('鸡蛋')}")
        check("单位对不上的豆腐原封不动", stock_of("豆腐") == 1,
              f"实际 {stock_of('豆腐')}")
        check("没扣的记进 skipped", "豆腐" in result.get("skipped", []),
              str(result.get("skipped")))
        check("扣减明细里有鸡蛋 2 个",
              any(d["name"] == "鸡蛋" and d["quantity"] == 2 for d in result.get("deducted", [])),
              str(result.get("deducted")))

        # ---- 执行：用户自己填的量优先 ----
        tofu_id = items.get("豆腐", {}).get("stock_item_id")
        r = client.post(f"/api/v1/recipes/{cook_id}/cook", headers=headers,
                        json={"deductions": [{"item_id": tofu_id, "quantity": 1}]})
        check("用户手填的量能覆盖「单位对不上就不扣」",
              r.status_code == 200 and stock_of("豆腐") is None,
              f"豆腐 = {stock_of('豆腐')}（应为 None，即用完被移除）")
        check("用完的食材被移除而不是留一条 0",
              all(x["food_name"] != "豆腐"
                  for x in client.get("/api/v1/inventory", headers=headers).json()))

        # ---- 空 deductions：只记行为，不动库存 ----
        eggs_before = stock_of("鸡蛋")
        r = client.post(f"/api/v1/recipes/{cook_id}/cook", headers=headers,
                        json={"deductions": []})
        check("deductions 传空数组 = 只记行为不改库存",
              r.status_code == 200 and stock_of("鸡蛋") == eggs_before,
              f"{eggs_before} → {stock_of('鸡蛋')}")
        check("空扣减时 note 说明是「按你的选择」，而不是甩锅给缺料",
              "按你的选择" in r.json().get("note", ""), str(r.json().get("note")))
        check("用户主动不改库存时 skipped 应为空（不然像出错了）",
              r.json().get("skipped") == [], str(r.json().get("skipped")))

        # ---- 做菜会喂给口味画像 ----
        r = client.get("/api/v1/recipes/insights/preference", headers=headers)
        check("做过的菜进了口味画像",
              r.status_code == 200 and "番茄鸡蛋豆腐" in r.json().get("frequently_cooked", []),
              str(r.json().get("frequently_cooked")))

        # ---- 边界 ----
        r = client.get("/api/v1/recipes/999999/cook-plan", headers=headers)
        check("不存在的菜谱 → 404", r.status_code == 404, r.text[:200])
        r = client.post("/api/v1/recipes/999999/cook", headers=headers, json={})
        check("给不存在的菜谱扣库存 → 404", r.status_code == 404, r.text[:200])

        print("\n=== 7. 菜谱详情与行为反馈 ===")
        r = client.get("/api/v1/recipes", headers=headers)
        check("菜谱列表", r.status_code == 200 and len(r.json()) == 3, r.text[:300])
        recipe_id = r.json()[0]["id"] if r.status_code == 200 and r.json() else 0

        # 列表必须用当前库存重算缺料，否则「食材齐全」筛选和「缺 N 样」标记全是假的
        listed_recipes = r.json() if r.status_code == 200 else []
        check("列表里带 ready 标记",
              all("ready" in x for x in listed_recipes), "字段缺失")
        check("列表里缺料不是恒为空",
              any(x["missing_ingredients"] for x in listed_recipes),
              "每道菜都显示不缺料，说明列表没有用库存重算")
        check("ready 与缺料状态自洽",
              all(x["ready"] == (bool(x["ingredients"]) and not x["missing_ingredients"])
                  for x in listed_recipes),
              str([(x["name"], x["ready"], len(x["missing_ingredients"])) for x in listed_recipes]))

        r = client.get(f"/api/v1/recipes/{recipe_id}", headers=headers)
        check("菜谱详情", r.status_code == 200, r.text[:300])
        check("详情用当前库存重算缺料",
              "missing_ingredients" in r.json() if r.status_code == 200 else False)
        check("详情带 ready 标记", r.json().get("ready") is not None if r.status_code == 200 else False)

        # 买齐东西后 ready 必须翻转 —— 这是「做完的菜自动弱化置底」的数据基础
        detail = r.json() if r.status_code == 200 else {}
        missing_names = [m["name"] for m in detail.get("missing_ingredients", [])]
        if missing_names:
            client.post("/api/v1/inventory", headers=headers, json={
                "food_name": missing_names[0],
                "quantity": 9999,
                "unit": next(m["unit"] for m in detail["missing_ingredients"]
                             if m["name"] == missing_names[0]),
            })
            after = client.get(f"/api/v1/recipes/{recipe_id}", headers=headers).json()
            check("补齐缺料后该食材转为已备齐",
                  all(i["available"] for i in after["ingredients"] if i["name"] == missing_names[0]),
                  str([(i["name"], i["available"]) for i in after["ingredients"]]))
            # 清理，避免影响后面的采购测试
            inv = client.get("/api/v1/inventory", headers=headers).json()
            for item in inv:
                if item["food_name"] == missing_names[0]:
                    client.delete(f"/api/v1/inventory/{item['id']}", headers=headers)
        else:
            check("补齐缺料后该食材转为已备齐", True, "本用例无缺料，跳过")

        r = client.post("/api/v1/recipes/feedback", headers=headers,
                        json={"recipe_id": recipe_id, "action": "cook"})
        check("上报做过", r.status_code == 200, r.text[:200])

        r = client.post("/api/v1/recipes/feedback", headers=headers,
                        json={"recipe_id": recipe_id, "action": "rate", "rating": 5})
        check("上报评分", r.status_code == 200, r.text[:200])

        r = client.get("/api/v1/recipes/insights/preference", headers=headers)
        check("行为偏好推导", r.status_code == 200, r.text[:300])
        check("能看出常做的菜",
              len(r.json().get("frequently_cooked", [])) > 0 if r.status_code == 200 else False,
              r.text[:200])

        print("\n=== 8. 智能采购 ===")
        r = client.post("/api/v1/shopping/build", headers=headers,
                        json={"recipe_ids": [recipe_id], "title": "今日采购"})
        check("生成采购清单", r.status_code == 201, r.text[:400])
        if r.status_code == 201:
            lst = r.json()
            check("清单含采购项", len(lst["items"]) > 0, str(lst)[:300])
            check("有预计花费字段", "estimated_total" in lst)
            check("自动合并重复食材",
                  len({i["food_name"] for i in lst["items"]}) == len(lst["items"]),
                  "存在重复食材未被合并")
            list_id = lst["id"]
            item_id = lst["items"][0]["id"]
            buy_name = lst["items"][0]["food_name"]
            buy_qty = lst["items"][0]["quantity"]
        else:
            list_id = item_id = 0
            buy_name, buy_qty = "", 0

        if item_id:
            r = client.patch(f"/api/v1/shopping/items/{item_id}", headers=headers,
                             json={"quantity": 99, "checked": True})
            check("修改采购项并勾选",
                  r.status_code == 200 and r.json()["quantity"] == 99, r.text[:300])

            r = client.post(f"/api/v1/shopping/{list_id}/apply", headers=headers,
                            json={"only_checked": True})
            check("买完写回库存", r.status_code == 200, r.text[:400])
            check("标记已入库",
                  r.json()["items"][0]["applied_to_inventory"] is True if r.status_code == 200 else False)

            r = client.get(f"/api/v1/inventory?keyword={buy_name}", headers=headers)
            check(f"库存中已出现 {buy_name}",
                  r.status_code == 200 and len(r.json()) > 0, r.text[:300])

        print("\n=== 8b. 画像扩充 ===")
        r = client.get("/api/v1/users/options", headers=headers)
        check("过敏原/忌口选项接口", r.status_code == 200, r.text[:200])
        opts = r.json() if r.status_code == 200 else {}
        check("返回过敏原分组", len(opts.get("allergens", [])) >= 3)
        check("返回忌口分组", len(opts.get("disliked_foods", [])) >= 3)
        check("带免责声明", bool(opts.get("disclaimer")))
        allergen_count = sum(len(g["items"]) for g in opts.get("allergens", []))
        check(f"过敏原条目够用（实际 {allergen_count} 条）", allergen_count >= 20)

        r = client.patch("/api/v1/users/profile", headers=headers,
                         json={"bio": "爱吃辣，不吃香菜"})
        check("保存个性简介",
              r.status_code == 200 and r.json().get("bio") == "爱吃辣，不吃香菜", r.text[:200])

        r = client.patch("/api/v1/users/profile", headers=headers, json={"bio": ""})
        check("简介可清空", r.status_code == 200 and r.json().get("bio") is None, r.text[:200])

        r = client.patch("/api/v1/users/profile", headers=headers, json={"bio": "x" * 201})
        check("简介超长被拒绝", r.status_code == 422, r.text[:150])

        r = client.post("/api/v1/users/avatar", headers=headers,
                        files={"file": ("avatar.png", io.BytesIO(fake_png), "image/png")})
        check("上传头像", r.status_code == 200 and r.json().get("avatar_url"), r.text[:300])

        r = client.post("/api/v1/users/avatar", headers=headers,
                        files={"file": ("a.txt", io.BytesIO(b"not an image"), "text/plain")})
        check("非图片头像被拒绝", r.status_code == 415, r.text[:200])

        r = client.put("/api/v1/users/health", headers=headers, json={
            "low_sugar": True, "low_purine": True, "no_raw_food": True,
            "high_calcium": True, "high_iron": True,
        })
        check("健康偏好扩充字段可保存", r.status_code == 200, r.text[:300])
        h = r.json() if r.status_code == 200 else {}
        check("扩充字段读回一致",
              all(h.get(k) is True for k in ("low_sugar", "low_purine", "no_raw_food",
                                             "high_calcium", "high_iron")), str(h))

        print("\n=== 8c. 食品安全贴士 ===")
        # 注意：这个接口刻意不要求登录，所以不带 headers 也应该通
        r = client.get("/api/v1/tips")
        check("贴士列表无需登录即可访问", r.status_code == 200, r.text[:200])
        tips = r.json() if r.status_code == 200 else {}
        check("贴士数量够用", tips.get("total", 0) >= 15, str(tips.get("total")))
        check("列表不含详情正文（省流量）",
              all("detail" not in t for t in tips.get("items", [])))
        check("每条都带结论标签",
              all(t.get("verdict") for t in tips.get("items", [])))

        r = client.get("/api/v1/tips?verdict=谣言")
        check("按结论筛选", r.status_code == 200 and r.json()["count"] >= 5,
              str(r.json().get("count") if r.status_code == 200 else r.text[:150]))

        r = client.get("/api/v1/tips?category=储存安全")
        check("按分类筛选", r.status_code == 200 and r.json()["count"] >= 3)

        r = client.get("/api/v1/tips/crab-with-tomato")
        check("贴士详情", r.status_code == 200, r.text[:200])
        tip = r.json() if r.status_code == 200 else {}
        check("详情含原因与原理正文", len(tip.get("detail", "")) > 100)
        check("详情含依据来源", bool(tip.get("source")))

        r = client.get("/api/v1/tips/not-exist-tip")
        check("不存在的贴士返回 404", r.status_code == 404)

        r = client.get("/api/v1/tips/random?count=2")
        check("随机取贴士", r.status_code == 200 and r.json()["count"] == 2)

        print("\n=== 9. 数据隔离 ===")
        r = client.post("/api/v1/auth/register",
                        json={"email": "other@fridge.com", "password": "other123456"})
        other_headers = {"Authorization": f"Bearer {r.json()['access_token']}"}
        r = client.get("/api/v1/inventory", headers=other_headers)
        check("新用户看不到别人的库存", r.status_code == 200 and len(r.json()) == 0, r.text[:200])
        r = client.get(f"/api/v1/recipes/{recipe_id}", headers=other_headers)
        check("新用户访问不到别人的菜谱", r.status_code == 404, r.text[:200])

        me_id = client.get("/api/v1/users/profile", headers=headers).json()["user"]["id"]
        other_id = client.get(
            "/api/v1/users/profile", headers=other_headers
        ).json()["user"]["id"]

        print("\n=== 10. 广场：发布动态 ===")
        r = client.post("/api/v1/social/posts", headers=headers,
                        json={"content": "今天做了番茄炒蛋，很成功", "tags": ["家常菜"]})
        check("发纯文字动态", r.status_code == 201, r.text[:200])
        post_id = r.json()["id"] if r.status_code == 201 else 0
        check("动态带作者信息", r.status_code == 201 and r.json()["author"]["id"] == me_id)
        check("新动态计数为 0", r.status_code == 201 and r.json()["like_count"] == 0)
        check("自己的动态标记 is_mine", r.status_code == 201 and r.json()["is_mine"] is True)

        r = client.post("/api/v1/social/posts", headers=headers, json={"content": "   "})
        check("空内容且无图被拒绝", r.status_code == 400, r.text[:200])

        r = client.post("/api/v1/social/posts", headers=headers,
                        json={"content": "分享一道菜", "recipe_id": recipe_id})
        check("分享自己的菜谱", r.status_code == 201, r.text[:200])
        recipe_post_id = r.json()["id"] if r.status_code == 201 else 0
        check("菜名从库里带出而非采信客户端",
              r.status_code == 201 and bool(r.json()["recipe_name"]), r.text[:200])
        check("做法也一并快照", r.status_code == 201 and len(r.json()["steps"]) > 0)

        r = client.post("/api/v1/social/posts", headers=other_headers,
                        json={"content": "偷别人的菜", "recipe_id": recipe_id})
        check("不能引用别人的菜谱", r.status_code == 404, r.text[:200])

        r = client.post("/api/v1/social/posts", headers=headers,
                        json={"content": "纯图片动态", "image_url": "/uploads/posts/1/x.jpg"})
        check("允许纯图片动态", r.status_code == 201, r.text[:200])
        image_post_id = r.json()["id"] if r.status_code == 201 else 0

        print("\n=== 11. 广场：三种排序 ===")
        for sort_key, label in (("latest", "最新"), ("hot", "热门"), ("composite", "综合")):
            r = client.get(f"/api/v1/social/feed?sort={sort_key}", headers=headers)
            ok = r.status_code == 200
            check(f"{label}排序可访问", ok, r.text[:200])
            if ok:
                check(f"{label}排序返回正确标签", r.json()["sort_label"] == label)
        r = client.get("/api/v1/social/feed", headers=headers)
        check("默认排序为综合",
              r.status_code == 200 and r.json()["sort"] == "composite", r.text[:200])
        check("返回排序选项供客户端渲染",
              r.status_code == 200 and len(r.json()["sort_options"]) == 3)
        r = client.get("/api/v1/social/feed?sort=乱写的", headers=headers)
        check("非法排序返回 400", r.status_code == 400, r.text[:200])
        r = client.get("/api/v1/social/feed?limit=2", headers=headers)
        check("分页 limit 生效",
              r.status_code == 200 and len(r.json()["items"]) <= 2, r.text[:200])

        print("\n=== 12. 广场：点赞 ===")
        r = client.post(f"/api/v1/social/posts/{post_id}/like", headers=headers)
        check("点赞成功", r.status_code == 200 and r.json()["liked"] is True, r.text[:200])
        check("点赞数变 1", r.status_code == 200 and r.json()["like_count"] == 1, r.text[:200])

        r = client.post(f"/api/v1/social/posts/{post_id}/like", headers=headers)
        check("重复点赞不重复计数",
              r.status_code == 200 and r.json()["like_count"] == 1, r.text[:200])

        r = client.post(f"/api/v1/social/posts/{post_id}/like", headers=other_headers)
        check("别人也能点赞", r.status_code == 200 and r.json()["like_count"] == 2, r.text[:200])

        r = client.get(f"/api/v1/social/posts/{post_id}", headers=headers)
        check("我赞过则 liked_by_me 为真",
              r.status_code == 200 and r.json()["liked_by_me"] is True, r.text[:200])

        r = client.delete(f"/api/v1/social/posts/{post_id}/like", headers=headers)
        check("取消点赞", r.status_code == 200 and r.json()["like_count"] == 1, r.text[:200])
        r = client.delete(f"/api/v1/social/posts/{post_id}/like", headers=headers)
        check("重复取消不会把计数减成负数",
              r.status_code == 200 and r.json()["like_count"] == 1, r.text[:200])

        r = client.post("/api/v1/social/posts/999999/like", headers=headers)
        check("给不存在的动态点赞返回 404", r.status_code == 404, r.text[:200])

        print("\n=== 13. 广场：评论 ===")
        r = client.post(f"/api/v1/social/posts/{post_id}/comments", headers=other_headers,
                        json={"content": "看起来很好吃"})
        check("发表评论", r.status_code == 201, r.text[:200])
        comment_id = r.json()["id"] if r.status_code == 201 else 0
        check("评论带作者", r.status_code == 201 and r.json()["author"]["id"] == other_id)

        r = client.post(f"/api/v1/social/posts/{post_id}/comments", headers=headers,
                        json={"content": "   "})
        check("空评论被拒绝", r.status_code == 400, r.text[:200])

        r = client.get(f"/api/v1/social/posts/{post_id}/comments", headers=headers)
        check("评论列表有 1 条", r.status_code == 200 and r.json()["total"] == 1, r.text[:200])

        r = client.get(f"/api/v1/social/posts/{post_id}", headers=headers)
        check("评论数同步到动态",
              r.status_code == 200 and r.json()["comment_count"] == 1, r.text[:200])

        print("\n=== 14. 广场：分享 ===")
        r = client.post(f"/api/v1/social/posts/{post_id}/share", headers=headers)
        check("分享计数 +1", r.status_code == 200 and r.json()["share_count"] == 1, r.text[:200])
        check("返回分享文案供系统面板使用",
              r.status_code == 200 and len(r.json()["share_text"]) > 0, r.text[:200])

        print("\n=== 15. 广场：关注 ===")
        r = client.post(f"/api/v1/social/users/{other_id}/follow", headers=headers)
        check("关注成功", r.status_code == 200 and r.json()["following"] is True, r.text[:200])
        check("粉丝数变 1", r.status_code == 200 and r.json()["follower_count"] == 1, r.text[:200])

        r = client.post(f"/api/v1/social/users/{other_id}/follow", headers=headers)
        check("重复关注不重复计数",
              r.status_code == 200 and r.json()["follower_count"] == 1, r.text[:200])

        r = client.post(f"/api/v1/social/users/{me_id}/follow", headers=headers)
        check("不能关注自己", r.status_code == 400, r.text[:200])

        r = client.get("/api/v1/social/me/following-ids", headers=headers)
        check("我关注的人列表含对方",
              r.status_code == 200 and other_id in r.json(), r.text[:200])

        r = client.get(f"/api/v1/social/users/{other_id}/followers", headers=headers)
        check("粉丝列表含我",
              r.status_code == 200 and any(x["user"]["id"] == me_id for x in r.json()),
              r.text[:200])

        r = client.get(f"/api/v1/social/users/{me_id}/following", headers=headers)
        check("关注列表含对方",
              r.status_code == 200 and any(x["user"]["id"] == other_id for x in r.json()),
              r.text[:200])

        r = client.get("/api/v1/social/feed?only_following=true", headers=headers)
        check("关注流可访问", r.status_code == 200, r.text[:200])
        if r.status_code == 200:
            author_ids = {p["author"]["id"] for p in r.json()["items"]}
            check("关注流不含未关注的人", author_ids <= {me_id, other_id}, str(author_ids))

        print("\n=== 16. 广场：公开性开关 ===")
        r = client.get(f"/api/v1/social/users/{me_id}", headers=other_headers)
        check("别人能看我的主页", r.status_code == 200, r.text[:200])
        check("默认不公开饮食偏好",
              r.status_code == 200 and r.json()["preference"] is None, r.text[:200])
        check("默认不公开健康偏好",
              r.status_code == 200 and r.json()["health"] is None, r.text[:200])
        check("默认不公开家庭成员",
              r.status_code == 200 and r.json()["family_members"] is None, r.text[:200])
        check("公开状态字段一并返回",
              r.status_code == 200 and r.json()["visibility"]["health"] is False, r.text[:200])

        r = client.put("/api/v1/users/privacy", headers=headers,
                       json={"share_preference": True})
        check("保存公开性设置", r.status_code == 200 and r.json()["share_preference"] is True,
              r.text[:200])

        r = client.get(f"/api/v1/social/users/{me_id}", headers=other_headers)
        check("开了开关后别人能看到饮食偏好",
              r.status_code == 200 and r.json()["preference"] is not None, r.text[:200])
        check("未开的健康偏好仍然看不到",
              r.status_code == 200 and r.json()["health"] is None, r.text[:200])

        r = client.put("/api/v1/users/privacy", headers=headers,
                       json={"share_preference": True, "share_health": True})
        r = client.get(f"/api/v1/social/users/{me_id}", headers=other_headers)
        check("开健康开关后能看到目标列表",
              r.status_code == 200 and r.json()["health"] is not None, r.text[:200])
        check("身体数据仍被单独挡住",
              r.status_code == 200 and r.json()["health"]["body"] is None, r.text[:200])
        check("健康信息带免责声明",
              r.status_code == 200 and len(r.json()["health"]["disclaimer"]) > 0, r.text[:200])

        r = client.put("/api/v1/users/privacy", headers=headers,
                       json={"share_preference": True, "share_health": True,
                             "share_body": True})
        r = client.get(f"/api/v1/social/users/{me_id}", headers=other_headers)
        check("开身体数据开关后能看到",
              r.status_code == 200 and r.json()["health"]["body"] is not None, r.text[:200])

        r = client.get(f"/api/v1/social/users/{me_id}", headers=headers)
        check("本人看自己不受开关限制",
              r.status_code == 200 and r.json()["is_me"] is True, r.text[:200])

        r = client.get("/api/v1/users/profile", headers=headers)
        check("画像接口带出公开性设置",
              r.status_code == 200 and r.json()["privacy"] is not None, r.text[:200])

        print("\n=== 17. 广场：权限边界 ===")
        r = client.delete(f"/api/v1/social/posts/{post_id}", headers=other_headers)
        check("删不了别人的动态", r.status_code == 404, r.text[:200])

        r = client.delete(f"/api/v1/social/comments/{comment_id}", headers=headers)
        check("动态作者能删别人的评论", r.status_code == 204, r.text[:200])

        r = client.delete(f"/api/v1/social/posts/{recipe_post_id}", headers=headers)
        check("删自己的动态", r.status_code == 204, r.text[:200])
        r = client.get(f"/api/v1/social/posts/{recipe_post_id}", headers=headers)
        check("删掉后查不到", r.status_code == 404, r.text[:200])

        r = client.delete(f"/api/v1/social/users/{other_id}/follow", headers=headers)
        check("取关", r.status_code == 200 and r.json()["following"] is False, r.text[:200])
        check("取关后粉丝数归零",
              r.status_code == 200 and r.json()["follower_count"] == 0, r.text[:200])

        r = client.delete(f"/api/v1/social/posts/{post_id}", headers=headers)
        check("清理主测试动态", r.status_code == 204, r.text[:200])
        if image_post_id:
            client.delete(f"/api/v1/social/posts/{image_post_id}", headers=headers)

        print("\n=== 17b. 家庭组（账号关联与数据共享）===")
        # 用两个独立账号验证共享。共享是这次改动里最容易出错的地方 ——
        # 每个查询都要走 family_service.visible_user_ids()，漏一处就会
        # 出现「冰箱里看得到、菜谱里看不到」这种自相矛盾。
        r = client.post("/api/v1/auth/register", json={
            "email": "fam_owner@example.com", "password": "fam123456", "nickname": "家长"})
        check("家庭主账号注册", r.status_code == 201, r.text[:200])
        owner_h = {"Authorization": f"Bearer {r.json()['access_token']}"}

        r = client.post("/api/v1/auth/register", json={
            "email": "fam_member@example.com", "password": "fam123456", "nickname": "家人"})
        check("成员账号注册", r.status_code == 201, r.text[:200])
        member_h = {"Authorization": f"Bearer {r.json()['access_token']}"}

        # 没加入家庭时必须是 null，不是空对象 —— 前端要分得开「没家庭」和「空家庭」
        r = client.get("/api/v1/family", headers=owner_h)
        # 后端永远返回对象，用 joined 当开关。
        # 不用裸 null 是因为 Retrofit + kotlinx-serialization 吃不下
        # （序列化器是非空的），客户端会抛 JsonDecodingException。
        check("没加入家庭时 joined=false",
              r.status_code == 200 and r.json().get("joined") is False, r.text[:200])

        r = client.post("/api/v1/family", headers=owner_h, json={"name": "测试之家"})
        check("创建家庭", r.status_code == 201, r.text[:300])
        fam = r.json() if r.status_code == 201 else {}
        code = fam.get("invite_code")
        check("家庭主拿到 6 位邀请码",
              bool(code) and len(code) == 6, str(code))
        check("自己是家庭主", fam.get("my_role") == "owner", str(fam.get("my_role")))
        check("家里目前只有自己", fam.get("member_count") == 1, str(fam.get("member_count")))

        r = client.post("/api/v1/family", headers=owner_h, json={})
        check("已经在家庭里就不能再建一个", r.status_code == 400, r.text[:200])

        r = client.post("/api/v1/family/join", headers=member_h, json={"code": "ZZZZZZ"})
        check("无效邀请码被拒绝", r.status_code == 404, r.text[:200])

        # 邀请码多半是当面念或截图发的，大小写和空格不该成为障碍
        r = client.post("/api/v1/family/join", headers=member_h,
                        json={"code": f"  {code.lower()}  "})
        check("邀请码容忍大小写和前后空格", r.status_code == 200, r.text[:300])

        r = client.get("/api/v1/family", headers=owner_h)
        check("成员加入后人数变 2", r.json().get("member_count") == 2, r.text[:200])

        # ---------- 数据共享（本次改动的核心）----------
        client.post("/api/v1/inventory", headers=owner_h,
                    json={"food_name": "共享牛奶", "quantity": 2, "unit": "盒"})
        names = [x["food_name"]
                 for x in (client.get("/api/v1/inventory", headers=member_h).json() or [])]
        check("成员能看到家庭主加的食材", "共享牛奶" in names, str(names))

        client.post("/api/v1/inventory", headers=member_h,
                    json={"food_name": "成员买的鸡蛋", "quantity": 6, "unit": "个"})
        names = [x["food_name"]
                 for x in (client.get("/api/v1/inventory", headers=owner_h).json() or [])]
        check("家庭主能看到成员加的食材", "成员买的鸡蛋" in names, str(names))

        # 菜谱同样共享
        client.post("/api/v1/recipes/generate", headers=owner_h,
                    json={"count": 3, "save": True})
        owner_recipes = client.get("/api/v1/recipes", headers=owner_h).json() or []
        member_recipes = client.get("/api/v1/recipes", headers=member_h).json() or []
        check("成员能看到家庭主的菜谱",
              bool(owner_recipes)
              and {x["name"] for x in owner_recipes} == {x["name"] for x in member_recipes},
              f"主 {len(owner_recipes)} 道 / 成员 {len(member_recipes)} 道")
        if owner_recipes:
            rid = owner_recipes[0]["id"]
            r = client.get(f"/api/v1/recipes/{rid}", headers=member_h)
            check("成员能打开家庭主的菜谱详情", r.status_code == 200, r.text[:200])

        # 个人数据**不**共享 —— 这是当初定下的边界。
        # 健康偏好没有单独的 GET，它跟着 /users/profile 一起返回。
        client.put("/api/v1/users/health", headers=owner_h, json={"low_sodium": True})
        owner_health = (client.get("/api/v1/users/profile", headers=owner_h).json()
                        or {}).get("health", {})
        member_health = (client.get("/api/v1/users/profile", headers=member_h).json()
                         or {}).get("health", {})
        check("家庭主确实设上了（否则这条测不出东西）",
              owner_health.get("low_sodium") is True, str(owner_health))
        check("健康偏好不共享（成员看不到家庭主设的）",
              member_health.get("low_sodium") is not True, str(member_health))

        # ---------- 只读成员 ----------
        r = client.post("/api/v1/family/invite-code", headers=owner_h,
                        json={"role": "viewer"})
        check("换邀请码并指定「只读」身份", r.status_code == 200, r.text[:300])
        viewer_code = r.json().get("invite_code")
        check("换码后旧码失效",
              viewer_code != code, f"新 {viewer_code} / 旧 {code}")

        r = client.post("/api/v1/family/leave", headers=member_h)
        check("成员退出", r.status_code == 200 and r.json().get("dissolved") is False,
              r.text[:200])

        r = client.post("/api/v1/family/join", headers=member_h,
                        json={"code": viewer_code})
        check("用只读码重新加入", r.status_code == 200, r.text[:300])
        check("身份确实是只读",
              r.json().get("my_role") == "viewer", str(r.json().get("my_role")))
        check("只读成员看不到邀请码",
              r.json().get("invite_code") is None, str(r.json().get("invite_code")))

        r = client.post("/api/v1/inventory", headers=member_h,
                        json={"food_name": "只读不该能加", "quantity": 1, "unit": "个"})
        check("只读成员不能加食材", r.status_code == 403, r.text[:200])

        r = client.get("/api/v1/inventory", headers=member_h)
        check("但只读成员能看", r.status_code == 200, r.text[:200])

        r = client.post("/api/v1/recipes/generate", headers=member_h,
                        json={"count": 3, "save": True})
        check("只读成员不能落库菜谱", r.status_code == 403, r.text[:200])

        # ---------- 家庭主管理成员 ----------
        fam_member_id = next(
            (m["user_id"] for m in
             (client.get("/api/v1/family", headers=owner_h).json() or {}).get("members", [])
             if not m["is_me"]),
            None,
        )
        check("能拿到成员 id", fam_member_id is not None, str(fam_member_id))

        r = client.patch(f"/api/v1/family/members/{fam_member_id}", headers=owner_h,
                         json={"role": "member"})
        check("家庭主能把只读改回成员",
              r.status_code == 200 and r.json().get("my_role") == "owner", r.text[:200])

        r = client.patch(f"/api/v1/family/members/{fam_member_id}", headers=member_h,
                         json={"role": "viewer"})
        check("成员不能改别人身份", r.status_code == 403, r.text[:200])

        r = client.patch(f"/api/v1/family/members/{fam_member_id}", headers=owner_h,
                         json={"role": "owner"})
        check("不能把别人设成家庭主（本版本不支持转让）",
              r.status_code == 422, r.text[:200])

        r = client.delete(f"/api/v1/family/members/{fam_member_id}", headers=owner_h)
        check("家庭主能移出成员",
              r.status_code == 200 and r.json().get("member_count") == 1, r.text[:200])

        # 被移出后看不到了 —— 但对方自己的数据还在
        names = [x["food_name"]
                 for x in (client.get("/api/v1/inventory", headers=member_h).json() or [])]
        check("被移出后看不到家庭主的食材", "共享牛奶" not in names, str(names))
        check("被移出后自己的食材还在", "成员买的鸡蛋" in names, str(names))

        # ---------- 家庭主退出 = 解散 ----------
        r = client.post("/api/v1/family/leave", headers=owner_h)
        check("家庭主退出会解散家庭",
              r.status_code == 200 and r.json().get("dissolved") is True, r.text[:200])
        r = client.get("/api/v1/family", headers=owner_h)
        check("解散后 joined 变回 false", r.json().get("joined") is False, r.text[:200])

        print("\n=== 17c. 自定义折叠箱 ===")
        # 先备几样食材，不依赖前面几节留下的状态
        box_items = []
        for name, qty, unit in (("猪肉", 2, "块"), ("牛肉", 1, "块"), ("金针菇", 2, "把")):
            r = client.post(
                "/api/v1/inventory",
                json={"food_name": name, "quantity": qty, "unit": unit},
                headers=headers,
            )
            check(f"备食材 {name}", r.status_code == 201, r.text[:200])
            box_items.append(r.json()["id"])

        r = client.get("/api/v1/food-categories", headers=headers)
        check("初始没有折叠箱", r.status_code == 200 and r.json() == [], r.text[:200])

        r = client.post(
            "/api/v1/food-categories",
            json={"name": "火锅材料", "inventory_ids": box_items[:2]},
            headers=headers,
        )
        check("建折叠箱并直接放两样进去", r.status_code == 201, r.text[:200])
        box = r.json()
        check("箱子里的食材 id 正确", sorted(box.get("inventory_ids", [])) == sorted(box_items[:2]),
              str(box)[:200])

        # 名字两边的空格要去掉，否则会出现「看起来是空的」箱子
        r = client.post("/api/v1/food-categories",
                        json={"name": "  本周要吃完  "}, headers=headers)
        check("名字首尾空格被去掉", r.status_code == 201 and r.json()["name"] == "本周要吃完",
              r.text[:200])
        box2_id = r.json()["id"]

        r = client.post("/api/v1/food-categories", json={"name": "   "}, headers=headers)
        check("全是空格的名字被拒", r.status_code == 422, r.text[:200])

        r = client.post("/api/v1/food-categories", json={"name": "火锅材料"}, headers=headers)
        check("重名被拒", r.status_code == 400, r.text[:200])

        # 加入食材
        r = client.post(f"/api/v1/food-categories/{box['id']}/items",
                        json={"inventory_ids": [box_items[2]]}, headers=headers)
        check("加入第三样食材", r.status_code == 200 and len(r.json()["inventory_ids"]) == 3,
              r.text[:200])

        # 重复加入不能变成两条
        r = client.post(f"/api/v1/food-categories/{box['id']}/items",
                        json={"inventory_ids": box_items}, headers=headers)
        check("重复加入不会产生重复项",
              r.status_code == 200 and len(r.json()["inventory_ids"]) == 3, r.text[:200])

        # 移出：只解除归组，食材本身必须留在冰箱里
        r = client.delete(f"/api/v1/food-categories/{box['id']}/items/{box_items[0]}",
                          headers=headers)
        check("移出一样食材", r.status_code == 200 and len(r.json()["inventory_ids"]) == 2,
              r.text[:200])
        r = client.get("/api/v1/inventory", headers=headers)
        still_there = any(it["id"] == box_items[0] for it in r.json())
        check("移出折叠箱后食材仍在冰箱里", still_there, "食材被误删了！")

        # 改名
        r = client.patch(f"/api/v1/food-categories/{box2_id}",
                         json={"name": "这周要吃完"}, headers=headers)
        check("改名成功", r.status_code == 200 and r.json()["name"] == "这周要吃完", r.text[:200])
        r = client.patch(f"/api/v1/food-categories/{box2_id}",
                         json={"name": "火锅材料"}, headers=headers)
        check("改成别的箱子已有的名字被拒", r.status_code == 400, r.text[:200])

        # 一件食材可以同时属于多个箱子（用户明确要的行为）
        r = client.post(f"/api/v1/food-categories/{box2_id}/items",
                        json={"inventory_ids": [box_items[1]]}, headers=headers)
        check("同一件食材能进第二个箱子", r.status_code == 200, r.text[:200])
        r = client.get("/api/v1/food-categories", headers=headers)
        boxes = {b["id"]: b for b in r.json()}
        check("两个箱子都包含牛肉",
              box_items[1] in boxes[box["id"]]["inventory_ids"]
              and box_items[1] in boxes[box2_id]["inventory_ids"], str(boxes)[:200])

        # 删箱子不能删食材
        r = client.delete(f"/api/v1/food-categories/{box2_id}", headers=headers)
        check("删除折叠箱", r.status_code == 204, r.text[:200])
        r = client.get("/api/v1/inventory", headers=headers)
        check("删箱子后食材都还在",
              all(any(it["id"] == i for it in r.json()) for i in box_items), "食材被误删了！")
        r = client.get("/api/v1/food-categories", headers=headers)
        check("删掉的箱子不在列表里", all(b["id"] != box2_id for b in r.json()))

        # 删食材时关联要一起清掉。
        # SQLite 默认不开外键约束，ON DELETE CASCADE 不生效，
        # 所以代码里是手动清的 —— 这里就是钉这个行为。
        r = client.delete(f"/api/v1/inventory/{box_items[1]}", headers=headers)
        check("删除食材", r.status_code == 204, r.text[:200])
        r = client.get("/api/v1/food-categories", headers=headers)
        remaining = {b["id"]: b for b in r.json()}
        check("删食材后折叠箱里不再残留它的 id",
              box_items[1] not in remaining[box["id"]]["inventory_ids"],
              str(remaining)[:200])

        # 不存在 / 无权限的箱子要 404。
        # 注意用 PATCH 而不是 GET —— 没有「查单个箱子」这个接口
        #（列表接口已经返回了全部信息），GET /{id} 会落到 405。
        r = client.patch("/api/v1/food-categories/999999",
                         json={"name": "不存在的箱子"}, headers=headers)
        check("不存在的箱子 404", r.status_code == 404, r.text[:200])
        r = client.delete("/api/v1/food-categories/999999", headers=headers)
        check("删不存在的箱子 404", r.status_code == 404, r.text[:200])

        # 清理这一节建的箱子，别影响后面的用例
        client.delete(f"/api/v1/food-categories/{box['id']}", headers=headers)

        print("\n=== 18. 清理 ===")
        r = client.delete("/api/v1/inventory", headers=headers)
        check("清空冰箱", r.status_code == 204, r.text[:200])
        r = client.get("/api/v1/inventory", headers=headers)
        check("清空后为空", r.status_code == 200 and len(r.json()) == 0)
        # 注意：这里用的是第 2 节那个「忌口档案」的 id，
        # 不是 17b 节的家庭组成员 id。两者名字像但完全是两回事。
        if member_id:
            r = client.delete(f"/api/v1/users/family/{member_id}", headers=headers)
            check("删除家庭成员", r.status_code == 204, r.text[:200])

    return report()


def report() -> int:
    print("\n" + "=" * 56)
    print(f"通过 {len(PASSED)} 项，失败 {len(FAILED)} 项")
    if FAILED:
        print("\n失败明细：")
        for f in FAILED:
            print(f"  - {f}")
    print("=" * 56)

    # Windows 上 SQLite 连接没释放就删不掉文件，先 dispose 再删
    try:
        from app.db.session import engine

        engine.dispose()
    except Exception:  # noqa: BLE001
        pass
    try:
        _DB_FILE.unlink(missing_ok=True)
    except OSError:
        print(f"（临时库未能删除，可手动清理: {_DB_FILE}）")

    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
