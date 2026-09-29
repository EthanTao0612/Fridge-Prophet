"""通义千问连通性自检。

填好 .env 里的 DASHSCOPE_API_KEY 之后运行：

    python scripts/check_ai.py

会分别测试「文本模型」和「视觉模型」是否真的能调通，
并给出报错的具体排查方向。避免到了演示现场才发现密钥不通。
"""
from __future__ import annotations

import base64
import sys
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_DIR))

from app.core.config import settings  # noqa: E402

OK = "  [OK]  "
BAD = "  [FAIL]"
WARN = "  [WARN]"

# 视觉模型那一项改用 static/ingredients/broccoli.jpg 了 ——
# 这里原来是个 1x1 的透明 PNG，模型会直接拒收（要求宽高大于 10），
# 导致那一项永远失败、误报成「视觉模型坏了」。


def diagnose(exc: Exception) -> None:
    """把常见报错翻译成可操作的建议。"""
    msg = str(exc)
    low = msg.lower()
    print(f"         原始报错: {msg[:300]}\n")

    if "invalid_api_key" in low or "401" in msg or "authentication" in low:
        print("         → API Key 不对。去 https://bailian.console.aliyun.com/model/settings/api-key")
        print("           重新创建一个，注意不要有多余空格或换行")
    elif "model not exist" in low or "model_not_found" in low or "404" in msg:
        print("         → 模型名不对，或该模型没在你账号下开通")
        print("           去模型广场确认 qwen-vl-max / qwen-plus 是否可用：")
        print("           https://bailian.console.aliyun.com/model/market")
    elif "access denied" in low or "accessdenied" in low:
        print("         → 密钥权限不足。如果你用的是子业务空间的 Key，")
        print("           子空间默认无法调用主账号的模型，需要主账号管理员授权，")
        print("           或者干脆用主账号创建一个新 Key")
    elif "quota" in low or "arrearage" in low or "欠费" in msg:
        print("         → 免费额度用完了，或者账号欠费。去费用中心确认：")
        print("           https://usercenter2.aliyun.com/home")
    elif "timeout" in low or "timed out" in low or "connect" in low:
        print("         → 网络不通。检查是否连了需要代理的网络，")
        print("           或者换一个 Base URL（见下方说明）")
    elif "400" in msg or "invalid_parameter" in low:
        print("         → 参数问题。如果是视觉模型报错，可能是该模型不支持")
        print("           response_format=json_object，代码里已做降级重试，")
        print("           若仍失败请把完整报错发给我")
    else:
        print("         → 把完整报错发给我，我来定位")


def main() -> int:
    problems = 0

    print("\n=== 配置检查 ===")
    print(f"  Base URL : {settings.AI_BASE_URL}")
    print(f"  文本模型 : {settings.TEXT_MODEL}")
    print(f"  视觉模型 : {settings.VISION_MODEL}")

    if not settings.ai_enabled:
        print(f"\n{WARN} 未配置 DASHSCOPE_API_KEY，AI 处于 MOCK 模式")
        print("         这不影响开发和测试，但演示时用的是假数据。")
        print("         配置步骤见 docs/01-账号注册.md")
        return 0

    key = settings.DASHSCOPE_API_KEY
    print(f"  API Key  : {key[:6]}...{key[-4:]}（长度 {len(key)}）")
    if len(key) < 20:
        print(f"{WARN} Key 长度异常，可能复制不完整")
    if key != key.strip():
        print(f"{WARN} Key 首尾有空白字符，可能导致鉴权失败")

    from openai import OpenAI

    client = OpenAI(
        api_key=key, base_url=settings.AI_BASE_URL, timeout=settings.AI_TIMEOUT_SECONDS
    )

    print("\n=== 1. 文本模型 ===")
    try:
        resp = client.chat.completions.create(
            model=settings.TEXT_MODEL,
            messages=[{"role": "user", "content": "只回复两个字：正常"}],
            max_tokens=16,
        )
        text = (resp.choices[0].message.content or "").strip()
        print(f"{OK} 调用成功，返回: {text}")
        print(f"         模型: {resp.model}")
    except Exception as exc:  # noqa: BLE001
        print(f"{BAD} 调用失败")
        diagnose(exc)
        problems += 1

    print("\n=== 2. 视觉模型 ===")
    # 用项目里**真实的食材照片**，而不是一个 1x1 的占位图。
    #
    # ⚠️ 这里原来用的是 1x1 的透明 PNG，模型直接拒收
    #（要求宽高都大于 10），所以这一项**永远不可能通过** ——
    # 会把人误导成「视觉模型有问题」，其实 key 和模型名都没问题。
    # 换成真图之后，这一项不但验「通不通」，还顺带验「认不认得出来」。
    sample = BACKEND_DIR / "static" / "ingredients" / "broccoli.jpg"
    if not sample.exists():
        print(f"{WARN} 找不到示例图 {sample.name}，跳过这一项")
        print("         （这张图应该随仓库提交，缺了说明仓库不完整）")
    else:
        try:
            b64 = base64.b64encode(sample.read_bytes()).decode("ascii")
            resp = client.chat.completions.create(
                model=settings.VISION_MODEL,
                messages=[
                    {
                        "role": "user",
                        "content": [
                            {
                                "type": "text",
                                "text": "这张图里是什么食材？只回答食材名字，不要别的字。",
                            },
                            {
                                "type": "image_url",
                                "image_url": {"url": f"data:image/jpeg;base64,{b64}"},
                            },
                        ],
                    }
                ],
                max_tokens=32,
            )
            text = (resp.choices[0].message.content or "").strip()
            print(f"{OK} 调用成功，返回: {text[:120]}")
            print(f"         模型: {resp.model}")
            print(f"         测试图: {sample.name}（正确答案：西兰花）")

            if any(k in text for k in ("西兰花", "花椰", "broccoli", "西蓝花")):
                print(f"{OK} 认出来了 ✓ 视觉链路可用")
            else:
                print(f"{WARN} 接口通，但没认出这是西兰花 —— 看图能力偏弱。")
                print("           先别急着换模型：拍照时离近一点、光线亮一点，")
                print("           通常就能认出来。真不行再换更强的视觉模型。")
        except Exception as exc:  # noqa: BLE001
            print(f"{BAD} 调用失败")
            diagnose(exc)
            problems += 1

    print("\n=== 3. JSON 结构化输出 ===")
    try:
        resp = client.chat.completions.create(
            model=settings.TEXT_MODEL,
            messages=[
                {"role": "system", "content": "只输出 JSON，不要任何其他文字。"},
                {
                    "role": "user",
                    "content": '返回 {"foods":[{"name":"鸡蛋","quantity":6,"unit":"个","confidence":0.95}]}',
                },
            ],
            response_format={"type": "json_object"},
            max_tokens=128,
        )
        from app.services.ai_client import _extract_json

        data = _extract_json(resp.choices[0].message.content or "")
        print(f"{OK} 结构化输出正常，解析到 {len(data.get('foods', []))} 条食材")
    except Exception as exc:  # noqa: BLE001
        print(f"{WARN} JSON 模式异常（代码里有降级重试，不致命）: {str(exc)[:200]}")

    print("\n" + "=" * 56)
    if problems == 0:
        print("AI 配置全部正常，可以开始真实识别了")
    else:
        print(f"发现 {problems} 个问题，见上方排查建议")
        print("\n如果提示网络不通，试试把 .env 里的 AI_BASE_URL 换成带业务空间 ID 的地址：")
        print("  AI_BASE_URL=https://{你的业务空间ID}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1")
        print("  业务空间 ID 在这里查：https://bailian.console.aliyun.com/cn-beijing/settings/workspace")
    print("=" * 56 + "\n")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
