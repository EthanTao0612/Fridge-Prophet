"""HTTP 冒烟测试：对着一个真实运行中的后端，把完整闭环跑一遍。

    # 测本地
    .venv/Scripts/python.exe scripts/smoke_http.py

    # 测部署后的服务器（部署完必跑一次）
    .venv/Scripts/python.exe scripts/smoke_http.py --base-url https://api.your-domain.com

和 tests/test_e2e.py 的区别：
    test_e2e.py      用 TestClient，直接调 ASGI 应用，不起网络栈
    smoke_http.py    走真实 HTTP，验证网络、反向代理、HTTPS、上传、CORS

所以两个都要跑。部署后只有这个脚本能发现 Nginx 配错、HTTPS 证书没生效这类问题。

不需要 AI 密钥 —— 后端会自动降级 MOCK 模式，链路照样完整。

⚠️ **需要能读到数据库**：注册接口要求邮箱验证码，而验证码只会发给真实邮箱，
   外部客户端拿不到。本脚本的做法是**直接把码写进库**（见 prepare_register_code）。
   所以要在**后端那台机器上**跑，或者本机能读到 backend/.env。
"""
from __future__ import annotations

import argparse
import io
import json
import struct
import sys
import urllib.error
import urllib.request
import random
import uuid
import zlib
from pathlib import Path
from typing import Any

DEFAULT_BASE_URL = "http://127.0.0.1:8000"

PASSED = 0
FAILED: list[str] = []


def check(label: str, ok: bool, detail: str = "") -> bool:
    global PASSED
    if ok:
        PASSED += 1
        print(f"  [OK]   {label}")
    else:
        FAILED.append(label)
        print(f"  [FAIL] {label}" + (f"  —— {detail}" if detail else ""))
    return ok


def make_test_png() -> bytes:
    """手搓一个合法 PNG，避免为了测试去依赖 Pillow。

    内容是 32x32 的纯色图 —— 后端只校验格式和大小，不关心画面内容。
    """
    width = height = 32
    raw = b"".join(b"\x00" + b"\x88\xc0\x60" * width for _ in range(height))

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


class Client:
    """极简 HTTP 客户端。用标准库，免得为了跑冒烟测试再装 requests。"""

    def __init__(self, base_url: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.token: str | None = None

    def request(
        self,
        method: str,
        path: str,
        *,
        json_body: Any = None,
        multipart: tuple[str, str, bytes] | None = None,
        timeout: int = 60,
    ) -> tuple[int, Any]:
        url = f"{self.base_url}{path}"
        headers: dict[str, str] = {"Accept": "application/json"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"

        body: bytes | None = None
        if multipart is not None:
            field, filename, content = multipart
            boundary = "----smoke" + uuid.uuid4().hex
            buf = io.BytesIO()
            buf.write(f"--{boundary}\r\n".encode())
            buf.write(
                f'Content-Disposition: form-data; name="{field}"; '
                f'filename="{filename}"\r\n'.encode()
            )
            buf.write(b"Content-Type: image/png\r\n\r\n")
            buf.write(content)
            buf.write(f"\r\n--{boundary}--\r\n".encode())
            body = buf.getvalue()
            headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
        elif json_body is not None:
            body = json.dumps(json_body).encode()
            headers["Content-Type"] = "application/json"

        req = urllib.request.Request(url, data=body, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                raw = resp.read()
                return resp.status, _maybe_json(raw)
        except urllib.error.HTTPError as e:
            return e.code, _maybe_json(e.read())
        except urllib.error.URLError as e:
            return 0, {"error": str(e.reason)}

    def get(self, path: str, **kw: Any) -> tuple[int, Any]:
        return self.request("GET", path, **kw)

    def post(self, path: str, **kw: Any) -> tuple[int, Any]:
        return self.request("POST", path, **kw)

    def put(self, path: str, **kw: Any) -> tuple[int, Any]:
        return self.request("PUT", path, **kw)

    def patch(self, path: str, **kw: Any) -> tuple[int, Any]:
        return self.request("PATCH", path, **kw)


def _maybe_json(raw: bytes) -> Any:
    if not raw:
        return None
    try:
        return json.loads(raw)
    except (json.JSONDecodeError, UnicodeDecodeError):
        return raw.decode("utf-8", errors="replace")[:300]


def section(n: str, title: str) -> None:
    print(f"\n=== {n}. {title} ===")


def prepare_register_code(email: str) -> str | None:
    """给注册测试准备一个验证码 —— **直接写进数据库**。

    ## 为什么不走 /auth/send-code

    注册接口要求邮箱验证码，而验证码**只会发给真实邮箱**。
    一个跑在外面的 HTTP 客户端拿不到它：

      - 库里存的是 `sha256("邮箱:码")`，**不是明文**
      - 配了 SMTP 之后码**不再打日志**（只有没配 SMTP 时才打）
      - 用 `@example.com` 这种假邮箱的话，真 SMTP 会退信

    所以这里**直接把码写进库**（后端校验的就是这张表）：
    既不用发邮件，也仍然把注册接口本身完整测到了。

    ⚠️ 只在测试脚本里这么做。应用当然不能这样。

    需要能读到数据库 —— 在**后端那台机器上**跑本脚本，
    或者本机能读到 `backend/.env`。连不上就返回 None，调用方会明确报错。
    """
    try:
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        from datetime import datetime, timedelta, timezone

        from app.db.session import SessionLocal
        from app.models.verification import EmailVerification
        from app.services.email_service import _hash
    except Exception:
        return None

    code = f"{random.randint(0, 999999):06d}"
    try:
        db = SessionLocal()
        try:
            db.add(
                EmailVerification(
                    email=email.strip().lower(),
                    code_hash=_hash(code, email),
                    purpose="register",
                    expires_at=datetime.now(timezone.utc) + timedelta(minutes=5),
                )
            )
            db.commit()
        finally:
            db.close()
    except Exception:
        return None
    return code


def main() -> int:
    parser = argparse.ArgumentParser(description="后端 HTTP 冒烟测试")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help="后端地址")
    args = parser.parse_args()

    base = args.base_url.rstrip("/")
    print(f"目标：{base}")
    c = Client(base)

    # ---------- 0. 可达性 ----------
    section("0", "服务可达性")
    status, data = c.get("/health")
    if not check("健康检查返回 200", status == 200, f"HTTP {status} {data}"):
        print("\n服务不可达，后续步骤无法进行。")
        print("请先确认后端已启动：bash tools/start-backend.sh")
        return 1
    check("状态为 healthy", isinstance(data, dict) and data.get("status") == "healthy", str(data))
    ai_enabled = data.get("ai_enabled") if isinstance(data, dict) else None
    print(f"         AI 状态：{'已接入真实模型' if ai_enabled else 'MOCK 模式（未配置密钥）'}")

    # ---------- 1. 注册 ----------
    section("1", "注册账号")
    email = f"smoke_{uuid.uuid4().hex[:12]}@example.com"
    code = prepare_register_code(email)
    if code is None:
        check(
            "能准备注册验证码（需要数据库访问）",
            False,
            "连不上数据库。请在**后端那台机器上**跑本脚本，或确保能读到 backend/.env",
        )
        return 1
    status, data = c.post(
        "/api/v1/auth/register",
        json_body={
            "email": email,
            "code": code,
            "password": "SmokeTest123",
            "nickname": "冒烟测试",
        },
    )
    if not check("注册成功", status in (200, 201), f"HTTP {status} {data}"):
        return 1
    token = data.get("access_token") if isinstance(data, dict) else None
    check("返回了登录令牌", bool(token), str(data))
    if not token:
        return 1
    c.token = token

    status, me = c.get("/api/v1/auth/me")
    check("能用令牌取到自己的信息", status == 200 and me.get("email") == email, str(me))

    # ---------- 2. 问卷 ----------
    section("2", "填写饮食偏好问卷")
    status, data = c.put(
        "/api/v1/users/preference",
        json_body={
            "cuisine": "川菜",
            "taste": "微辣",
            "cook_time_max": 45,
            "diet_goal": "减脂",
            "disliked_foods": ["香菜"],
            "allergies": ["花生"],
        },
    )
    check("偏好保存成功", status in (200, 201), f"HTTP {status} {data}")

    status, data = c.put(
        "/api/v1/users/health",
        json_body={
            "low_carb": True,
            "low_sodium": False,
            "low_fat": True,
            "high_protein": True,
            "high_fiber": False,
            "vegetarian": False,
            "height_cm": 175,
            "weight_kg": 70,
            "age": 28,
            "activity_level": "中度",
        },
    )
    check("健康设置保存成功", status in (200, 201), f"HTTP {status} {data}")

    status, profile = c.get("/api/v1/users/profile")
    check("画像能读回", status == 200, f"HTTP {status} {profile}")

    # ---------- 3. 拍照识别 ----------
    section("3", "扫描冰箱照片")
    status, scan = c.post(
        "/api/v1/vision/scan",
        multipart=("file", "fridge.png", make_test_png()),
    )
    if not check("照片上传并识别成功", status in (200, 201), f"HTTP {status} {scan}"):
        return 1
    # ⚠️ 这里**不能断言「识别出了食材」**。
    #
    # 本脚本上传的是一张 32×32 的纯色 PNG（make_test_png），
    # 真实模型看一眼就会说「没有识别到食材」—— 这是**正确行为**，
    # 不是 bug。之前断言「识别出了食材」只在 MOCK 模式下才成立
    #（MOCK 会返回假食材），接了真模型之后必然失败。
    #
    # 所以这里只断言**扫描接口本身工作正常**：
    # 返回 scan_id、标记为待确认、上传的照片能取回来。
    # 后面下游流程（菜谱 / 采购）用一份固定的候选食材继续测。
    scan_id = scan.get("scan_id") if isinstance(scan, dict) else None
    check("扫描接口返回了 scan_id", bool(scan_id), str(scan)[:200])
    check("识别结果标记为待确认", scan.get("needs_review") is True, str(scan)[:200])
    check("模型名有返回（确认真调了 AI）", bool(scan.get("model")), str(scan)[:200])

    # 上传的照片必须真的能取回来 —— 这条顺带验证了图片存储
    #（Supabase Storage 或本地磁盘 + 反向代理）配置正确。
    photo_url = scan.get("image_url") if isinstance(scan, dict) else None
    if photo_url:
        try:
            req = urllib.request.Request(photo_url, method="HEAD")
            with urllib.request.urlopen(req, timeout=20) as r:
                check("上传的照片能公网取回", r.status == 200, f"HTTP {r.status}")
        except Exception as ex:
            check("上传的照片能公网取回", False, f"{type(ex).__name__}: {ex}")

    foods = scan.get("foods") if isinstance(scan, dict) else None
    if foods:
        print(f"         真实识别到 {len(foods)} 种食材：")
        for f in foods[:6]:
            print(f"           - {f.get('name')} {f.get('quantity')}{f.get('unit')}"
                  f"  置信度 {f.get('confidence')}")
    else:
        # 纯色假图 + 真模型 = 识别不到东西，走这条兜底。
        # 用一份常见的候选食材，让后面的菜谱 / 采购流程也能测到。
        print("         （纯色测试图，真模型识别不到食材 —— 符合预期，用固定候选继续）")
        foods = [
            {"name": "鸡蛋", "quantity": 6, "unit": "个", "confidence": 1.0},
            {"name": "西红柿", "quantity": 3, "unit": "个", "confidence": 1.0},
            {"name": "牛奶", "quantity": 1, "unit": "盒", "confidence": 1.0},
        ]

    # ---------- 4. 确认入库 ----------
    section("4", "用户确认识别结果（关键：AI 结果不直接入库）")
    status, before = c.get("/api/v1/inventory")
    before_count = len(before) if isinstance(before, list) else -1

    confirm_foods = [
        {
            "name": f.get("name"),
            "quantity": f.get("quantity") or 1,
            "unit": f.get("unit") or "份",
            "confidence": f.get("confidence") or 1.0,
            "category": f.get("category") or "其他",
            "storage_location": f.get("storage_location") or "冷藏",
            "shelf_life_days": f.get("shelf_life_days"),
        }
        for f in foods
    ]
    status, data = c.post(
        "/api/v1/inventory/confirm",
        json_body={"foods": confirm_foods, "default_storage_location": "冷藏"},
    )
    check("确认入库成功", status in (200, 201), f"HTTP {status} {data}")

    status, after = c.get("/api/v1/inventory")
    after_count = len(after) if isinstance(after, list) else -1
    check(
        "库存数量增加了",
        after_count > before_count,
        f"入库前 {before_count} 项，入库后 {after_count} 项",
    )
    print(f"         库存：{before_count} 项 → {after_count} 项")

    status, stats = c.get("/api/v1/inventory/stats")
    check("库存概览可读", status == 200, f"HTTP {status} {stats}")

    status, expiring = c.get("/api/v1/inventory/expiring")
    check("临期查询可读", status == 200, f"HTTP {status} {expiring}")

    # ---------- 5. 生成菜谱 ----------
    section("5", "根据库存生成菜谱")
    status, gen = c.post(
        "/api/v1/recipes/generate",
        json_body={"count": 3, "prioritize_expiring": True, "save": True},
    )
    if not check("菜谱生成成功", status in (200, 201), f"HTTP {status} {gen}"):
        return 1
    recipes = gen.get("recipes") if isinstance(gen, dict) else None
    check("生成了菜谱", bool(recipes), str(gen)[:200])
    if not recipes:
        return 1
    print(f"         生成 {len(recipes)} 道：")
    for r in recipes[:4]:
        print(f"           - {r.get('name')}  约 {r.get('time_minutes')} 分钟")

    first = recipes[0]
    recipe_id = first.get("id")
    check("菜谱有 id", recipe_id is not None, str(first)[:200])

    # ---------- 6. 菜谱详情 ----------
    section("6", "查看菜谱详情")
    status, detail = c.get(f"/api/v1/recipes/{recipe_id}")
    check("详情可读", status == 200, f"HTTP {status} {detail}")
    if status == 200 and isinstance(detail, dict):
        check("含烹饪步骤", bool(detail.get("steps")), str(detail.get("steps"))[:120])
        ings = detail.get("ingredients") or []
        check("含食材清单", bool(ings), str(ings)[:120])
        if ings:
            check(
                "食材标了「有没有」",
                all("available" in i for i in ings),
                "缺 available 字段",
            )
        check("含营养估算", bool(detail.get("nutrition")), str(detail.get("nutrition"))[:120])

    status, data = c.post(
        "/api/v1/recipes/feedback",
        json_body={"recipe_id": recipe_id, "action": "favorite"},
    )
    check("上报收藏成功", status in (200, 201), f"HTTP {status} {data}")

    # ---------- 7. 采购清单 ----------
    section("7", "从菜谱生成采购清单（关键：差集由后端算，不用 AI）")
    status, build = c.post(
        "/api/v1/shopping/build",
        json_body={"recipe_ids": [recipe_id], "title": "冒烟测试清单"},
    )
    if not check("清单生成成功", status in (200, 201), f"HTTP {status} {build}"):
        return 1

    list_id = build.get("id") if isinstance(build, dict) else None
    items = build.get("items") if isinstance(build, dict) else None
    check("清单有 id", list_id is not None, str(build)[:200])
    check("清单含采购项", bool(items), str(build)[:200])
    check("有预计花费字段", build.get("estimated_total") is not None, str(build)[:200])

    if items:
        print(f"         需采购 {len(items)} 项，预计 {build.get('estimated_total')} 元：")
        for it in items[:6]:
            print(f"           - {it.get('food_name')} {it.get('quantity')}{it.get('unit')}")

        # 关键校验：清单里不该出现冰箱里已经有的食材
        # 注意字段名是 food_name 不是 name —— 库存和采购项都用这个（App 的 DTO 里
        # 也是 @SerialName("food_name")）。只有 AI 识别结果用 name。
        inv_names = {
            (i.get("food_name") or "").strip()
            for i in (after if isinstance(after, list) else [])
        }
        bought = [it for it in items if it.get("checked")]
        to_buy = [it for it in items if not it.get("checked")]
        check(
            "已勾选项不计入待购",
            len(bought) + len(to_buy) == len(items),
            f"勾选 {len(bought)} + 待购 {len(to_buy)} != {len(items)}",
        )
        print(f"         库存已有 {len(inv_names)} 种食材，清单只列缺的")

        # 核心校验：清单里的食材必须都在菜谱的「缺料」列表里。
        # 这条守的是架构铁律「采购差集由后端算，不用 AI」——
        # 如果清单里冒出了菜谱根本不需要的东西，说明差集算错了。
        raw_missing = (detail.get("missing_ingredients") or []) if isinstance(detail, dict) else []
        missing = {
            ((m.get("name") if isinstance(m, dict) else m) or "").strip() for m in raw_missing
        }
        list_names = {(it.get("food_name") or "").strip() for it in items}
        if missing:
            extra = list_names - missing
            check(
                "清单只列菜谱缺的食材",
                not extra,
                f"多列了：{extra}",
            )
            print(f"         菜谱缺 {len(missing)} 种，清单列 {len(list_names)} 种")

        # ---------- 8. 勾选 + 写回 ----------
        section("8", "勾选买到的，写回冰箱")
        target = items[0]
        status, data = c.patch(
            f"/api/v1/shopping/items/{target.get('id')}",
            json_body={"checked": True},
        )
        check("勾选成功", status == 200, f"HTTP {status} {data}")

        status, data = c.patch(
            f"/api/v1/shopping/items/{target.get('id')}",
            json_body={"quantity": 2},
        )
        check("改数量成功", status == 200, f"HTTP {status} {data}")

        # apply 的请求体是必填的（字段本身都有默认值，但不能整个 body 都不给）
        status, applied = c.post(
            f"/api/v1/shopping/{list_id}/apply",
            json_body={"only_checked": True, "shelf_life_days": 5},
        )
        check("写回库存成功", status in (200, 201), f"HTTP {status} {applied}")

        status, final_inv = c.get("/api/v1/inventory")
        final_names = {
            (i.get("food_name") or "").strip()
            for i in (final_inv if isinstance(final_inv, list) else [])
        }
        check(
            "买到的食材出现在库存里",
            target.get("food_name") in final_names,
            f"库存里没有 {target.get('food_name')}",
        )
        print(f"         库存：{len(inv_names)} 种 → {len(final_names)} 种")

    # ---------- 8b. 做菜扣库存 ----------
    section("8b", "做菜扣库存（先预览、再确认；可改量、可取消）")

    def inv_now() -> dict[str, float]:
        _, rows = c.get("/api/v1/inventory")
        return {x["food_name"]: x["quantity"] for x in (rows or [])}

    stock_before = inv_now()
    check("能读到扣减前的库存", bool(stock_before), str(stock_before)[:200])

    status, plan = c.get(f"/api/v1/recipes/{recipe_id}/cook-plan")
    if check("扣减预览可读", status == 200, f"HTTP {status} {plan}"):
        items = plan.get("items") or []
        check("预览列出了会用到的食材",
              bool(items) or bool(plan.get("missing")), str(plan)[:200])
        # 这条是核心契约：单位一致才给建议量，不一致必须留空让用户填
        check("单位一致才有建议扣减量，不一致的留空",
              all((i["suggested_deduct"] is not None) == bool(i["unit_matched"])
                  for i in items),
              str([(i["name"], i["unit_matched"], i["suggested_deduct"]) for i in items]))
        check("预览不写库（只是看一眼）", inv_now() == stock_before,
              f"{stock_before} → {inv_now()}")

    # 「取消」= 压根不调接口，什么都不会发生，这条不需要测。
    # 这里测的是另一条路：明确传空列表 = 只记「做过」，一样都不动。
    status, res = c.post(f"/api/v1/recipes/{recipe_id}/cook",
                         json_body={"deductions": []})
    if check("只记录不改库存 成功", status == 200, f"HTTP {status} {res}"):
        check("说明是「按你的选择」而不是甩锅给缺料",
              "按你的选择" in (res.get("note") or ""), str(res.get("note")))
        check("没有误报 skipped", not res.get("skipped"), str(res.get("skipped")))
    check("库存确实一点没动", inv_now() == stock_before,
          f"{stock_before} → {inv_now()}")

    status, res = c.post(f"/api/v1/recipes/{recipe_id}/cook", json_body={})
    if check("按系统估算扣减 成功", status == 200, f"HTTP {status} {res}"):
        deducted = res.get("deducted") or []
        check("返回了扣减明细", bool(deducted), str(res)[:200])
        now = inv_now()
        for d in deducted:
            if d["emptied"]:
                check(f"{d['name']} 用完了被移出冰箱",
                      d["name"] not in now, str(now))
            else:
                check(f"{d['name']} 数量按明细减少",
                      now.get(d["name"]) == d["remaining"],
                      f"期望剩 {d['remaining']}，实际 {now.get(d['name'])}")
        if res.get("skipped"):
            check("跳过的项没有被误扣",
                  all(inv_now().get(n) == stock_before.get(n) for n in res["skipped"]
                      if n in stock_before),
                  f"skipped={res['skipped']}")

    status, _ = c.get("/api/v1/recipes/999999/cook-plan")
    check("不存在的菜谱 → 404", status == 404, f"HTTP {status}")

    # ---------- 8c. 家庭组 ----------
    section("8c", "家庭组（邀请码加入 + 数据共享）")

    # 另起一个客户端当「家人」，免得把主测试账号的 token 弄乱
    mate = Client(base)
    mate_email = f"smokefam_{uuid.uuid4().hex[:10]}@example.com"
    status, mate_data = mate.post("/api/v1/auth/register", json_body={
        "email": mate_email,
        "code": prepare_register_code(mate_email) or "",
        "password": "SmokeTest123", "nickname": "家人"})
    if check("家人账号注册成功", status in (200, 201), f"HTTP {status} {mate_data}"):
        mate.token = mate_data.get("access_token")

        # 关键契约：没加入家庭时返回的仍是**对象**，靠 joined=false 表达。
        # 不能返回裸 null —— Retrofit + kotlinx-serialization 吃不下，
        # 客户端会抛 JsonDecodingException，用户看到「出错了」而不是加入入口。
        status, none_fam = c.get("/api/v1/family")
        check("没加入家庭时 joined=false",
              status == 200 and isinstance(none_fam, dict)
              and none_fam.get("joined") is False,
              f"HTTP {status} {none_fam}")

        status, fam = c.post("/api/v1/family", json_body={"name": "冒烟之家"})
        if check("创建家庭成功", status in (200, 201), f"HTTP {status} {fam}"):
            code = fam.get("invite_code")
            check("拿到 6 位邀请码", bool(code) and len(code) == 6, str(code))

            status, joined = mate.post("/api/v1/family/join", json_body={"code": code})
            check("家人用邀请码加入成功", status == 200, f"HTTP {status} {joined}")
            check("家人身份是成员",
                  isinstance(joined, dict) and joined.get("my_role") == "member",
                  str(joined))

            # 数据共享 —— 这是这个功能的全部意义所在
            c.post("/api/v1/inventory", json_body={
                "food_name": "共享验证牛奶", "quantity": 1, "unit": "盒"})
            status, mate_inv = mate.get("/api/v1/inventory")
            names = {x.get("food_name") for x in (mate_inv or [])}
            check("家人能看到我加的食材", "共享验证牛奶" in names, str(names)[:200])

            # 收尾：退出 + 解散，别在库里留垃圾
            mate.post("/api/v1/family/leave")
            status, left = c.post("/api/v1/family/leave")
            check("家庭主退出 = 解散",
                  status == 200 and isinstance(left, dict) and left.get("dissolved") is True,
                  str(left))
            status, gone = c.get("/api/v1/family")
            check("解散后 joined 变回 false",
                  status == 200 and isinstance(gone, dict) and gone.get("joined") is False,
                  str(gone))

    # ---------- 9. 数据隔离 ----------
    section("9", "数据隔离（别人的数据你看不到）")
    other = Client(base)
    other_email = f"other_{uuid.uuid4().hex[:12]}@example.com"
    status, data = other.post(
        "/api/v1/auth/register",
        json_body={
            "email": other_email,
            "code": prepare_register_code(other_email) or "",
            "password": "SmokeTest123",
            "nickname": "路人",
        },
    )
    if check("另一个账号注册成功", status in (200, 201), f"HTTP {status} {data}"):
        other.token = data.get("access_token")
        status, other_inv = other.get("/api/v1/inventory")
        check(
            "新账号看不到别人的库存",
            status == 200 and isinstance(other_inv, list) and len(other_inv) == 0,
            f"HTTP {status} 看到 {other_inv}",
        )
        status, other_recipes = other.get("/api/v1/recipes")
        check(
            "新账号看不到别人的菜谱",
            status == 200 and isinstance(other_recipes, list) and len(other_recipes) == 0,
            f"HTTP {status} 看到 {other_recipes}",
        )

    # ---------- 汇总 ----------
    print("\n" + "=" * 56)
    if FAILED:
        print(f"通过 {PASSED} 项，失败 {len(FAILED)} 项")
        for f in FAILED:
            print(f"  失败：{f}")
        print("=" * 56)
        return 1
    print(f"全部通过（{PASSED} 项）")
    print("=" * 56)
    return 0


if __name__ == "__main__":
    sys.exit(main())
