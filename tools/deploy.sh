#!/usr/bin/env bash
# ============================================================
#  冰箱先知 · 一键部署（在腾讯云服务器上跑）
# ============================================================
#
#  用法（在服务器上，root 或 sudo）：
#
#      bash deploy.sh 你的域名
#      bash deploy.sh 1.2.3.4.nip.io          # 没域名时用 nip.io
#
#  想换通知邮箱（certbot 要用，证书过期前会提醒）：
#
#      CERTBOT_EMAIL=you@example.com bash deploy.sh 你的域名
#
# ------------------------------------------------------------
#  ## 为什么要有这个脚本
#
#  docs/03 里那套手工步骤有 6 大步、十几条命令，中间要改 3 个配置文件。
#  手工做一遍 1-2 小时，而且**每一处都能写错**（缩进、路径、端口、
#  server_name），报错还都很晦涩（nginx 的报错尤其）。
#
#  这个脚本把能自动化的全部自动化，并且**可重入**：
#  中途失败（比如要先去 Supabase 控制台复制连接串），修好后
#  **再跑一次**就行 —— 已经做过的步骤会跳过，不会把服务器搞乱。
#
#  ## 它做了什么（对应 docs/03 的章节）
#
#    1. 装依赖包                        （三、2）
#    2. 建专用用户 fridge，拉代码        （三、1 / 三、3）
#    3. 建虚拟环境、装 requirements      （四）
#    4. 生成 / 校验 .env                 （四）
#    5. 跑一遍测试（SQLite，不碰生产库）  （四）
#    6. systemd 守护，开机自启           （五）
#    7. Nginx 反代                       （六）
#    8. certbot 上 HTTPS                 （七）
#    9. Supabase 保活 cron               （九）
#   10. 自检：本机 /health + 公网 /health
#
#  ## ⚠️ 两个设计上的取舍
#
#  ① **不用 root 跑服务**。systemd 里 User=fridge。
#     用 root 跑的话，服务一旦被攻破就等于整台机器失守。
#
#  ② **uvicorn 只监听 127.0.0.1**，外部流量一律走 Nginx。
#     监听 0.0.0.0 的话 8000 端口会直接暴露在公网上，
#     绕过 Nginx 也就绕过了 HTTPS 和后面可能要加的限流。
#
# ============================================================

set -euo pipefail

# ---------------- 常量 ----------------
APP_USER="fridge"
APP_DIR="/home/${APP_USER}/app"
SERVICE_NAME="fridge"
NGINX_SITE="fridge"
REPO_URL="https://github.com/EthanTao0612/Fridge-Prophet.git"
CERTBOT_EMAIL="${CERTBOT_EMAIL:-}"

# ---------------- 输出 ----------------
# 用颜色区分「信息 / 成功 / 警告 / 失败」，日志长了也能一眼扫到重点
if [ -t 1 ]; then
    C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'
    C_BLUE=$'\033[34m'; C_GREEN=$'\033[32m'
    C_YELLOW=$'\033[33m'; C_RED=$'\033[31m'
else
    C_RESET=""; C_BOLD=""; C_BLUE=""; C_GREEN=""; C_YELLOW=""; C_RED=""
fi

STEP_NO=0
step() {
    STEP_NO=$((STEP_NO + 1))
    echo
    echo "${C_BOLD}${C_BLUE}==> [${STEP_NO}] $*${C_RESET}"
}
ok()   { echo "    ${C_GREEN}✓${C_RESET} $*"; }
info() { echo "    · $*"; }
warn() { echo "    ${C_YELLOW}⚠${C_RESET}  $*"; }
die()  { echo; echo "${C_RED}${C_BOLD}[失败] $*${C_RESET}"; echo; exit 1; }

# 出错时把「在哪一步挂的」打出来，而不是只丢一个行号
trap 'echo; echo "${C_RED}脚本在第 ${STEP_NO} 步中断。修好上面的问题后，重新运行本脚本即可继续。${C_RESET}"; echo' ERR

# ============================================================
#  纯逻辑函数
#
#  这几个是「不会报错、但会静默产生错结果」的地方，
#  所以单独抽出来，配 `bash deploy.sh --selftest` 可以本地跑一遍。
#
#  为什么值得这么麻烦：连接串写错的表现是「有时候连得上、有时候连不上」
#  （psycopg 默认 prefer，SSL 失败会退回明文，而 Supabase 拒绝明文），
#  排查起来极其痛苦。这里钉住它。
# ============================================================

# 把用户从 Supabase 控制台复制的连接串，修成后端能用的形式。
# 干两件事：
#   ① 驱动前缀 postgresql:// → postgresql+psycopg://
#      （SQLAlchemy 2.0 要显式指定驱动，不写就找不到 psycopg）
#   ② 补上 ?sslmode=require
#      （Supabase 强制 SSL；psycopg 默认 prefer 会退回明文，然后被拒）
normalize_db_url() {
    local url="$1"
    [ -z "$url" ] && { echo ""; return 0; }

    # ⚠️ 只处理 PostgreSQL。别的串（比如有人手抖填了 sqlite:///x.db）
    # **原样返回** —— 给它拼个 ?sslmode=require 是纯错误，
    # 而且 sqlite 不认这个参数，启动时才报错，更难查。
    case "$url" in
        postgresql+psycopg://*|postgresql://*) : ;;
        *) echo "$url"; return 0 ;;
    esac

    case "$url" in
        postgresql+psycopg://*) : ;;
        postgresql://*) url="postgresql+psycopg://${url#postgresql://}" ;;
    esac

    # 注意两种写法：已经有 query 用 & 拼，没有才用 ?
    case "$url" in
        *sslmode=*) : ;;
        *\?*) url="${url}&sslmode=require" ;;
        *)    url="${url}?sslmode=require" ;;
    esac

    echo "$url"
}

# uvicorn worker 数：按核数，但封顶 4。
# 再多人也没用（瓶颈在 AI 调用和数据库往返），只是白吃内存。
pick_workers() {
    local cpu="${1:-2}"
    [ "$cpu" -lt 1 ] 2>/dev/null && cpu=1
    # ⚠️ **封顶 2，不是 4。**
    #
    # 2026-10-07 实测：Supabase 的连接池
    #（`pooler.supabase.com:5432`）**只允许 10 条并发连接**，
    # 第 11 条直接报 `FATAL: (EMAXCONNSESSION) max clients reached`。
    #
    # 每个 worker 占 `pool_size + max_overflow = 2 + 2 = 4` 条
    #（见 backend/app/db/session.py），所以：
    #     1 个 worker → 4 条
    #     2 个 worker → 8 条  ← 封顶，留 2 条余量
    #     4 个 worker → 16 条 ← 直接爆
    #
    # 而且这个额度**整个项目共享** —— 本机开发的后端也占着，
    # 所以部署前记得把本机的后端停掉。
    #
    # 想多开 worker 的话，必须同时把 session.py 里的 pool_size 调小，
    # 让 `(pool_size + max_overflow) × worker ≤ 10` 成立。
    if [ "$cpu" -gt 2 ]; then echo 2; else echo "$cpu"; fi
}

# 自测：`bash deploy.sh --selftest`
# 不碰系统、不需要 root，本地就能跑。
if [ "${1:-}" = "--selftest" ]; then
    PASS=0; FAIL=0
    t() {  # t <说明> <实际> <期望>
        if [ "$2" = "$3" ]; then
            PASS=$((PASS+1)); echo "  [OK]   $1"
        else
            FAIL=$((FAIL+1)); echo "  [FAIL] $1"
            echo "         期望：$3"
            echo "         实际：$2"
        fi
    }
    echo "=== normalize_db_url ==="
    t "原样补 sslmode（无 query）" \
      "$(normalize_db_url 'postgresql://u:p@h:5432/postgres')" \
      "postgresql+psycopg://u:p@h:5432/postgres?sslmode=require"
    t "已有 query 时用 & 拼" \
      "$(normalize_db_url 'postgresql://u:p@h:5432/postgres?foo=1')" \
      "postgresql+psycopg://u:p@h:5432/postgres?foo=1&sslmode=require"
    t "已有 sslmode 不重复加" \
      "$(normalize_db_url 'postgresql://u:p@h:5432/postgres?sslmode=require')" \
      "postgresql+psycopg://u:p@h:5432/postgres?sslmode=require"
    t "已经是 +psycopg 前缀不重复加" \
      "$(normalize_db_url 'postgresql+psycopg://u:p@h:5432/postgres')" \
      "postgresql+psycopg://u:p@h:5432/postgres?sslmode=require"
    t "真实 Supabase 串（带 pooler 和参数）" \
      "$(normalize_db_url 'postgresql://postgres.abc:pw@aws-0-ap-south-1.pooler.supabase.com:5432/postgres?sslmode=require')" \
      "postgresql+psycopg://postgres.abc:pw@aws-0-ap-south-1.pooler.supabase.com:5432/postgres?sslmode=require"
    t "空串返回空串" "$(normalize_db_url '')" ""
    t "非 postgres 串原样返回（不瞎拼 sslmode）" \
      "$(normalize_db_url 'sqlite:///x.db')" "sqlite:///x.db"

    echo
    echo "=== pick_workers ==="
    t "1 核 → 1"   "$(pick_workers 1)"  "1"
    t "2 核 → 2"   "$(pick_workers 2)"  "2"
    t "4 核 → 2（封顶，受数据库连接数限制）" "$(pick_workers 4)" "2"
    t "8 核 → 2（封顶）" "$(pick_workers 8)" "2"
    t "异常值 0 → 1"     "$(pick_workers 0)" "1"

    # ⚠️ 连接预算必须 ≤ 10（Supabase 连接池的硬上限，2026-10-07 实测）
    echo "=== 连接预算（pool_size 2 + overflow 2 = 4/worker）==="
    for _cpu in 1 2 4 8 16; do
        _w=$(pick_workers "$_cpu")
        _budget=$((_w * 4))
        t "${_cpu} 核 → ${_w} worker × 4 = ${_budget} 条连接（≤10）" \
          "$([ "$_budget" -le 10 ] && echo ok || echo 超了)" "ok"
    done

    echo
    if [ "$FAIL" -eq 0 ]; then
        echo "全部通过（$PASS 项）"
    else
        echo "通过 $PASS 项，失败 $FAIL 项"
    fi
    [ "$FAIL" -eq 0 ] && exit 0 || exit 1
fi

# ---------------- 参数 ----------------
DOMAIN="${1:-}"
if [ "$DOMAIN" = "--help" ] || [ "$DOMAIN" = "-h" ]; then
    # 打印文件开头那段注释（到第一个 set -euo pipefail 之前）
    sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
    exit 0
fi
if [ -z "$DOMAIN" ]; then
    echo "用法： bash deploy.sh 你的域名"
    echo
    echo "  有域名：  bash deploy.sh api.example.com"
    echo "  没域名：  bash deploy.sh 1.2.3.4.nip.io   （换成你的服务器 IP）"
    echo
    echo "  没域名也能跑 —— 用 nip.io 这个泛解析服务，"
    echo "  \`1.2.3.4.nip.io\` 会自动解析回 1.2.3.4，certbot 也认。"
    echo
    echo "  想先看它到底会做什么：  bash deploy.sh --help"
    echo "  想只跑纯逻辑自测：      bash deploy.sh --selftest"
    exit 1
fi

# ============================================================
step "环境检查"
# ============================================================

[ "$(id -u)" -eq 0 ] || die "请用 root 运行（或 sudo bash deploy.sh 域名）"

if [ -f /etc/os-release ]; then
    . /etc/os-release
    info "系统：${PRETTY_NAME:-未知}"
    case "${ID:-}" in
        ubuntu|debian) : ;;
        *) warn "这套脚本只在 Ubuntu / Debian 上验证过，当前是 ${ID:-未知}，继续跑但可能有问题" ;;
    esac
else
    warn "读不到 /etc/os-release，无法确认系统版本"
fi

# 内存：uvicorn 开 2 个 worker + Nginx，512M 的机器会很吃力
MEM_MB=$(awk '/MemTotal/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo 0)
if [ "$MEM_MB" -gt 0 ] && [ "$MEM_MB" -lt 900 ]; then
    warn "内存只有 ${MEM_MB}MB，2 个 worker 可能吃紧。"
    warn "如果之后 journalctl 里看到被 OOM 杀掉，把 systemd 里的 --workers 改成 1。"
fi

# 域名解析：certbot 要求域名必须指向本机，否则签发一定失败。
# 提前检查能省掉「装完一堆东西最后一步才失败」的挫败感。
if command -v getent >/dev/null 2>&1; then
    RESOLVED=$(getent hosts "$DOMAIN" | awk '{print $1}' | head -1 || true)
    PUBLIC_IP=$(curl -s --max-time 8 https://api.ipify.org || true)
    if [ -n "$RESOLVED" ]; then
        info "域名 ${DOMAIN} 解析到 ${RESOLVED}"
        if [ -n "$PUBLIC_IP" ] && [ "$RESOLVED" != "$PUBLIC_IP" ]; then
            warn "本机公网 IP 是 ${PUBLIC_IP}，和解析结果不一致。"
            warn "certbot 那一步会失败。先去域名服务商把 A 记录改成 ${PUBLIC_IP}。"
        fi
    else
        warn "域名 ${DOMAIN} 现在解析不出 IP。"
        warn "如果用的是自己的域名，先去服务商加一条 A 记录指向这台服务器。"
        warn "（用 nip.io 的话不用担心，它会自动解析。）"
    fi
fi

# ============================================================
step "安装系统依赖"
# ============================================================

export DEBIAN_FRONTEND=noninteractive

# apt 在有些云镜像上会因为缓存过期直接报错，先 update 一次
apt-get update -qq
# sudo 也一起装上：脚本后面到处用 `sudo -u fridge` 切换身份，
# 极简 Debian 镜像上可能没有它，那时候会报「command not found」而不是
# 「权限不足」，很容易误判成权限问题
apt-get install -y -qq \
    python3 python3-venv python3-pip \
    nginx git curl ca-certificates sudo \
    certbot python3-certbot-nginx

ok "python3 / nginx / git / certbot 都装好了"

# 时区改成国内，否则 journalctl 里的时间看不懂
timedatectl set-timezone Asia/Shanghai 2>/dev/null && ok "时区已设为 Asia/Shanghai" \
    || warn "时区设置失败（不影响运行）"

PY_VER=$(python3 -c 'import sys; print("%d.%d" % sys.version_info[:2])')
info "Python 版本：${PY_VER}"
python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' \
    || die "Python 需要 3.10 以上，当前是 ${PY_VER}。Ubuntu 22.04 自带 3.10，20.04 只有 3.8，需要换系统或自己编译。"

# ============================================================
step "建专用用户 + 拉代码"
# ============================================================

if id -u "$APP_USER" >/dev/null 2>&1; then
    ok "用户 ${APP_USER} 已存在"
else
    adduser --disabled-password --gecos "" "$APP_USER"
    usermod -aG sudo "$APP_USER"
    ok "已创建用户 ${APP_USER}（不能登录，只用来跑服务）"
fi

# 把当前登录用户的公钥给 fridge，方便以后 su - fridge 调试
if [ -n "${SUDO_USER:-}" ] && [ -f "/home/${SUDO_USER}/.ssh/authorized_keys" ]; then
    mkdir -p "/home/${APP_USER}/.ssh"
    cp "/home/${SUDO_USER}/.ssh/authorized_keys" "/home/${APP_USER}/.ssh/authorized_keys"
    chown -R "${APP_USER}:${APP_USER}" "/home/${APP_USER}/.ssh"
    chmod 700 "/home/${APP_USER}/.ssh"
    chmod 600 "/home/${APP_USER}/.ssh/authorized_keys"
    ok "已复制 SSH 公钥，之后可以直接 ssh ${APP_USER}@本机 调试"
fi

if [ -d "${APP_DIR}/.git" ]; then
    info "代码已存在，拉取最新"
    sudo -u "$APP_USER" git -C "$APP_DIR" fetch --quiet origin
    sudo -u "$APP_USER" git -C "$APP_DIR" reset --hard --quiet origin/main
    ok "已更新到 origin/main（$(sudo -u "$APP_USER" git -C "$APP_DIR" rev-parse --short HEAD)）"
elif [ -d "$APP_DIR" ]; then
    ok "代码目录已存在（不是 git 仓库，按上传方式部署的，跳过拉取）"
else
    info "从 ${REPO_URL} 克隆…"
    if sudo -u "$APP_USER" git clone --quiet "$REPO_URL" "$APP_DIR" 2>/dev/null; then
        ok "克隆完成"
    else
        echo
        die "克隆失败。如果仓库是私有的，在**你自己的电脑上**执行：

    scp -r \"G:/workbuddy/任务路径/Fridge-Prophet\" ${APP_USER}@<服务器IP>:~/app

  （注意目标路径写成 ~/app，不要写成 ~/app/Fridge-Prophet）
  传完再重新运行本脚本。"
    fi
fi

[ -f "${APP_DIR}/backend/requirements.txt" ] \
    || die "找不到 ${APP_DIR}/backend/requirements.txt —— 代码目录结构不对，检查一下上传的路径"

# ============================================================
step "建虚拟环境 + 装依赖"
# ============================================================

VENV="${APP_DIR}/backend/.venv"
if [ -x "${VENV}/bin/python" ]; then
    ok "虚拟环境已存在，跳过创建"
else
    sudo -u "$APP_USER" python3 -m venv "$VENV"
    ok "虚拟环境已创建"
fi

sudo -u "$APP_USER" "${VENV}/bin/python" -m pip install --quiet --upgrade pip
info "安装 requirements.txt（第一次要几分钟）…"
sudo -u "$APP_USER" "${VENV}/bin/python" -m pip install --quiet -r "${APP_DIR}/backend/requirements.txt"
ok "依赖装好了"

# ============================================================
step "配置 .env"
# ============================================================

ENV_FILE="${APP_DIR}/backend/.env"

if [ -f "$ENV_FILE" ]; then
    ok ".env 已存在，保留不动（要改就 nano ${ENV_FILE} 再重跑本脚本）"
    if ! grep -q "sslmode=require" "$ENV_FILE"; then
        warn ".env 里的 DATABASE_URL 没有 ?sslmode=require —— Supabase 会时通时不通，建议补上"
    fi
else
    info "生成新的 .env（密钥是现生成的，不会沿用开发环境那份）"
    NEW_SECRET=$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))')

    echo
    echo "    ── 接下来几项可以直接按回车跳过 ──"
    echo "    跳过的项：图片存本地磁盘（能跑，但重启后上传的图片会丢）"
    echo "    必填的项：DATABASE_URL（不填就用 SQLite，重启数据丢）"
    echo

    read -r -p "    Supabase 连接串 DATABASE_URL: " IN_DB || IN_DB=""
    read -r -p "    百炼 AI 密钥 DASHSCOPE_API_KEY: " IN_AI || IN_AI=""
    read -r -p "    Supabase 项目地址 SUPABASE_URL: " IN_SUPA_URL || IN_SUPA_URL=""
    read -r -p "    Supabase service key: " IN_SUPA_KEY || IN_SUPA_KEY=""

    # ⚠️ SMTP 一定要问 —— 2026-10-07 首次部署漏了这三项，
    # 结果服务器上「邮箱验证码: ⚠️ 未配置 SMTP」，
    # **注册流程直接不可用**（验证码只打到服务器日志里，
    # 用户根本收不到，任何人都能用编造的邮箱注册）。
    # 这个坑只在真实用户注册时才会暴露，演示当天才发现就晚了。
    echo
    echo "    ── 邮箱验证码（注册要用）──"
    echo "    不填的话：验证码只打到服务器日志里，**用户注册不了**。"
    echo
    read -r -p "    SMTP 服务器 SMTP_HOST（如 smtp.163.com）: " IN_SMTP_HOST || IN_SMTP_HOST=""
    read -r -p "    SMTP 端口 SMTP_PORT（163 用 465）: " IN_SMTP_PORT || IN_SMTP_PORT=""
    read -r -p "    SMTP 账号 SMTP_USER: " IN_SMTP_USER || IN_SMTP_USER=""
    read -r -p "    SMTP 密码/授权码 SMTP_PASSWORD: " IN_SMTP_PASS || IN_SMTP_PASS=""
    [ -n "$IN_SMTP_HOST" ] && [ -z "$IN_SMTP_PORT" ] && IN_SMTP_PORT=465

    # 提醒两个最容易写错的地方（逻辑在 normalize_db_url 里，有自测）
    if [ -n "$IN_DB" ]; then
        FIXED_DB=$(normalize_db_url "$IN_DB")
        if [ "$FIXED_DB" != "$IN_DB" ]; then
            ok "已把连接串规范化（补驱动前缀 / sslmode）"
        fi
        IN_DB="$FIXED_DB"
        case "$IN_DB" in
            postgresql+psycopg://*) : ;;
            *) warn "连接串不像 Supabase 的 PostgreSQL 地址，确认一下" ;;
        esac
    else
        warn "没填 DATABASE_URL —— 会用 SQLite，重启后数据丢失，演示前记得补上"
    fi

    cat > "$ENV_FILE" <<ENVEOF
# 由 tools/deploy.sh 生成于 $(date '+%Y-%m-%d %H:%M:%S')
# 改完这个文件后：sudo systemctl restart ${SERVICE_NAME}

DEBUG=false

# 令牌密钥：本机现生成的，和开发环境那份不同
SECRET_KEY=${NEW_SECRET}

DATABASE_URL=${IN_DB}
DASHSCOPE_API_KEY=${IN_AI}
SUPABASE_URL=${IN_SUPA_URL}
SUPABASE_SERVICE_KEY=${IN_SUPA_KEY}

# —— AI 每日配额（给百炼额度兜底）——
#
# 后端接口没有限流，而注册是开放的 —— 任何人发现这个域名后注册个账号，
# 就能反复调 /vision/scan 和 /recipes/generate，每次都在花你的钱。
# 所以有两道闸（见 app/services/quota_service.py）：
#   PER_USER 防单个账号霸占；GLOBAL 是**账单的硬上限**。
#
# 演示前怕被限住的话，把 PER_USER 调大（不用改代码）。
DAILY_AI_LIMIT_PER_USER=30
DAILY_AI_LIMIT_GLOBAL=500

# 邮箱验证码（注册要用）。不填则验证码只打到日志里，**用户注册不了**。
SMTP_HOST=${IN_SMTP_HOST}
SMTP_PORT=${IN_SMTP_PORT}
SMTP_USER=${IN_SMTP_USER}
SMTP_PASSWORD=${IN_SMTP_PASS}
ENVEOF

    chown "${APP_USER}:${APP_USER}" "$ENV_FILE"
    chmod 600 "$ENV_FILE"     # 里面有密钥，只给属主读
    ok ".env 已生成（权限 600，只有 ${APP_USER} 能读）"
fi

# ⚠️ 部署完立刻检查 SMTP —— 这是**唯一一个「配错了也照样启动、
# 只有真实用户注册时才会暴露」**的配置项。
# 不检查的话，演示当天有人来注册才发现收不到验证码。
if grep -q '^SMTP_HOST=$' "$ENV_FILE" 2>/dev/null; then
    warn "SMTP_HOST 是空的 —— **注册流程不可用**（验证码只打到服务器日志里）"
    warn "补上之后重启服务："
    warn "  sudo nano ${ENV_FILE}    # 填 SMTP_HOST/PORT/USER/PASSWORD"
    warn "  sudo systemctl restart ${SERVICE_NAME}"
else
    ok "SMTP 已配置（注册验证码能发出去）"
fi

# ============================================================
step "自检：数据库连接预算"
# ============================================================

# worker 数按 CPU 核数来（逻辑在 pick_workers 里，有自测）。
# ⚠️ 在这里就算出来 —— 下面的连接预算自检和 systemd 都要用。
CPU_N=$(nproc 2>/dev/null || echo 2)
WORKERS=$(pick_workers "$CPU_N")

# ⚠️ 为什么单独查这一项。
#
# 2026-10-07 实测：Supabase 的连接池（`pooler.supabase.com:5432`）
# **只允许 10 条并发连接**，第 11 条报 `(EMAXCONNSESSION) max clients reached`。
# 而当时每个 worker 就占了 10 条 —— 这个错**只有在演示当天有人并发访问时
# 才会冒出来**，而且报错是「连不上数据库」，很难联想到是连接数不够。
#
# 所以在这里**主动试一次**：按当前配置开满连接，
# 开不满就在部署阶段就报出来，而不是等到演示。
# ⚠️ 这里的 `4` 是 `pool_size + max_overflow`，来自
# `backend/app/db/session.py`（2 + 2）。**改那边就要改这里** ——
# 两处不一致的话，这个自检会给你一个假的安心。
CONN_BUDGET=$((WORKERS * 4))
info "按 ${WORKERS} 个 worker × 4 条 = ${CONN_BUDGET} 条连接试连…"
if sudo -u "$APP_USER" bash -c "cd '${APP_DIR}/backend' && '${VENV}/bin/python' - '${CONN_BUDGET}'" <<'PYEOF' > /tmp/fridge_conn.log 2>&1
import sys
from sqlalchemy import create_engine, text
from app.core.config import settings

want = int(sys.argv[1])
held = []
try:
    for i in range(1, want + 1):
        e = create_engine(settings.DATABASE_URL, pool_size=1, max_overflow=0)
        c = e.connect()
        c.execute(text("SELECT 1"))
        held.append((e, c))
    print(f"OK 开满 {want} 条连接")
except Exception as ex:
    print(f"FAIL 只开到 {len(held)} 条就失败了：{type(ex).__name__}: {ex}")
    sys.exit(1)
finally:
    for e, c in held:
        try:
            c.close()
        except Exception:
            pass
        try:
            e.dispose()
        except Exception:
            pass
PYEOF
then
    ok "连接预算够用（$(grep -o '开满 [0-9]* 条连接' /tmp/fridge_conn.log | tail -1)）"
else
    warn "连不上 ${CONN_BUDGET} 条 —— 部署上去之后并发一高就会报「连不上数据库」"
    cat /tmp/fridge_conn.log | sed 's/^/    /' || true
    warn "常见的两个原因："
    warn "  1. 本机开发的后端还开着，占着同一份连接额度 —— 先停掉它"
    warn "  2. Supabase 的连接上限比预期低 —— 把 backend/app/db/session.py 的"
    warn "     pool_size / max_overflow 调小，或把 worker 数降到 1"
    die "先解决连接数问题再部署"
fi

# ============================================================
step "跑一遍测试（用 SQLite，不碰生产库）"
# ============================================================

# ⚠️ 这一步是故意的：先证明「代码在这台机器上能跑通」，
#    再去连生产库。否则一旦连不上 Supabase，你分不清是
#    代码问题还是网络问题。
#
# test_e2e.py 自己在导入 app 之前就把 DATABASE_URL 指向临时 SQLite 了，
# 所以它**不会**往 Supabase 写任何东西。
info "运行 backend/tests/test_e2e.py …"
if sudo -u "$APP_USER" bash -c "cd '${APP_DIR}/backend' && '${VENV}/bin/python' tests/test_e2e.py" \
        > /tmp/fridge_test.log 2>&1; then
    ok "测试通过：$(grep -o '通过 [0-9]* 项' /tmp/fridge_test.log | tail -1)"
else
    warn "测试没过，完整日志在 /tmp/fridge_test.log"
    tail -20 /tmp/fridge_test.log || true
    die "先解决测试失败再部署 —— 在这台机器上都跑不通，部署上去也一样。"
fi

# ============================================================
step "systemd 守护（开机自启 + 崩溃自动重启）"
# ============================================================

# worker 数在「自检：数据库连接预算」那一步已经算好了（$WORKERS）。
# 这里不重算 —— 重算一次就有两处能改，迟早会不一致。

cat > "/etc/systemd/system/${SERVICE_NAME}.service" <<UNITEOF
[Unit]
Description=Fridge Prophet Backend
After=network.target

[Service]
Type=simple
User=${APP_USER}
Group=${APP_USER}
WorkingDirectory=${APP_DIR}/backend
Environment="PATH=${VENV}/bin"
Environment="PYTHONUNBUFFERED=1"
ExecStart=${VENV}/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000 --workers ${WORKERS}
Restart=always
RestartSec=5

# 日志走 journald，用 journalctl -u ${SERVICE_NAME} -f 看
StandardOutput=journal
StandardError=journal

# 基础加固：服务只能写自己的目录和 /tmp
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full
ReadWritePaths=${APP_DIR}

[Install]
WantedBy=multi-user.target
UNITEOF

systemctl daemon-reload
systemctl enable --quiet "$SERVICE_NAME"
systemctl restart "$SERVICE_NAME"
sleep 3

if systemctl is-active --quiet "$SERVICE_NAME"; then
    ok "服务已启动（${WORKERS} 个 worker，只监听 127.0.0.1:8000）"
else
    echo
    journalctl -u "$SERVICE_NAME" -n 30 --no-pager || true
    die "服务启动失败，日志在上面"
fi

# ============================================================
step "灌菜品库的做法（192 道菜）"
# ============================================================

# 菜品库那 192 道菜的步骤、营养、配料，是**离线生成一次**存在
# `backend/data/dish-recipes.json` 里的（跟着 Git 走）。
# 这一步把它们写进数据库，作为「系统内置菜谱」（user_id 为 NULL）。
#
# 为什么必须做：不做的话，App 里点开推荐菜会去**现调 AI 生成做法**，
# 第一次点要等十几秒，还每次都花 AI 额度。
# 灌完之后点开是查库返回，毫秒级。
#
# 幂等：按菜名 upsert，只动 user_id IS NULL 的行，不会碰用户自己的菜谱。
# 所以重复跑是安全的（改完 JSON 重跑就会更新）。
if [ -f "${APP_DIR}/backend/data/dish-recipes.json" ]; then
    info "写入内置菜谱（第一次要一两分钟，192 道菜 + 配料）…"
    if sudo -u "$APP_USER" bash -c \
            "cd '${APP_DIR}/backend' && '${VENV}/bin/python' ../tools/seed-dish-recipes.py" \
            > /tmp/fridge_seed.log 2>&1; then
        # 把脚本最后那几行结果打出来 —— 里面有「库里现在有 N 道」和抽查详情
        tail -8 /tmp/fridge_seed.log | sed 's/^/    /'
    else
        warn "灌菜品库失败，完整日志：/tmp/fridge_seed.log"
        tail -15 /tmp/fridge_seed.log | sed 's/^/    /' || true
        warn "不影响服务运行，但 App 里点开推荐菜会退回「现调 AI 生成」"
        warn "修好之后单独执行："
        warn "  cd ${APP_DIR}/backend && ${VENV}/bin/python ../tools/seed-dish-recipes.py"
    fi
else
    warn "找不到 backend/data/dish-recipes.json，跳过"
    warn "生成它的命令（本地跑，要 30-40 分钟）："
    warn "  cd backend && .venv/Scripts/python.exe ../tools/generate-dish-recipes.py"
fi

# ============================================================
step "Nginx 反向代理"
# ============================================================

cat > "/etc/nginx/sites-available/${NGINX_SITE}" <<NGINXEOF
server {
    listen 80;
    listen [::]:80;
    server_name ${DOMAIN};

    # 拍冰箱的照片可能有几 MB，默认 1M 会直接 413
    client_max_body_size 20M;

    # 图片是静态文件，让 Nginx 直接发，别绕到 Python 去
    location /static/ {
        alias ${APP_DIR}/backend/static/;
        expires 7d;
        access_log off;
    }

    location / {
        proxy_pass http://127.0.0.1:8000;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;

        # AI 识别一张图 + 生成菜谱要几十秒，超时给够，
        # 否则 Nginx 会在 60 秒时先断，客户端看到 504
        proxy_read_timeout 180s;
        proxy_send_timeout 180s;
        proxy_connect_timeout 10s;
    }
}
NGINXEOF

ln -sf "/etc/nginx/sites-available/${NGINX_SITE}" "/etc/nginx/sites-enabled/${NGINX_SITE}"
rm -f /etc/nginx/sites-enabled/default

# ⚠️⚠️ 让 Nginx 能读到 static/ 里的图 —— 不加这一条，App 里**一张图都不显示**。
#
# 2026-10-07 首次部署踩到：`adduser` 建的 /home/fridge 权限是 `drwxr-x---`，
# 而 Nginx 的 worker 以 **www-data** 身份跑 —— **穿不过这个目录**，
# 于是 /static/ 下所有文件一律 **403 Forbidden**。
#
# 最坑的是：`/health` 完全正常、接口全通，只有图片挂 ——
# 排查时很容易怀疑到图片服务或路径上，很难想到是**目录权限**。
# 证据在 Nginx 错误日志里：
#   open() ".../static/ingredients/tomato.jpg" failed (13: Permission denied)
#
# 组的权限本来就是 r-x（`drwxr-x---`），所以只要把 www-data 加进 fridge 组。
# 比 `chmod o+x` 干净 —— 不用放开「其他用户可穿越」，
# 而且 .env 是 600，加了组照样读不到。
usermod -aG "${APP_USER}" www-data

# ⚠️ 顺序：**先测配置，再重启**。
# 反过来的话，配置有语法错误会把 Nginx 先弄挂，站点直接不可用。
nginx -t >/dev/null 2>&1 || { nginx -t; die "Nginx 配置有语法错误，见上面输出"; }
# restart 而不是 reload —— www-data 的**附加组**要重新拉进程才生效
systemctl restart nginx
ok "Nginx 已配置并重启（server_name=${DOMAIN}）"
ok "已把 www-data 加进 ${APP_USER} 组（否则 /static/ 里的图会 403）"

# 本机自检：不经过域名，直接打 Nginx
#
# ⚠️ 必须带 `Host: ${DOMAIN}` 头。
# 不带的话 curl 发的是 `Host: 127.0.0.1`，而 certbot 之后会把 80 端口的
# server 块改成「只对 ${DOMAIN} 跳转，其他 Host 一律 404」——
# 于是这一步会报一个**看起来像应用挂了、其实是假警报**的 404。
if curl -fsS --max-time 10 -H "Host: ${DOMAIN}" "http://127.0.0.1/health" >/dev/null 2>&1; then
    ok "本机 /health 通了（Host: ${DOMAIN}）"
else
    warn "本机 /health 没通，检查一下：journalctl -u ${SERVICE_NAME} -n 30"
fi

# ============================================================
step "上 HTTPS（certbot）"
# ============================================================

if [ -z "$CERTBOT_EMAIL" ]; then
    read -r -p "    certbot 通知邮箱（证书快过期时提醒，可留空）: " CERTBOT_EMAIL || CERTBOT_EMAIL=""
fi
[ -z "$CERTBOT_EMAIL" ] && CERTBOT_EMAIL="admin@${DOMAIN}"

# 已经签过就不重复签（Let's Encrypt 有频率限制，反复签会被限流）
if [ -d "/etc/letsencrypt/live/${DOMAIN}" ]; then
    ok "证书已存在，跳过签发"
else
    info "向 Let's Encrypt 申请证书（${DOMAIN}）…"
    if certbot --nginx -d "$DOMAIN" \
            --non-interactive --agree-tos --redirect \
            --email "$CERTBOT_EMAIL" >/tmp/fridge_certbot.log 2>&1; then
        ok "HTTPS 已配好，HTTP 会自动 301 到 HTTPS"
    else
        warn "证书签发失败。常见原因："
        warn "  1. 域名的 A 记录还没指向这台服务器（最常见）"
        warn "  2. 防火墙没开 80 / 443 端口"
        warn "  3. 同一域名短时间申请太多次，被 Let's Encrypt 限流（等 1 小时）"
        warn "完整日志：/tmp/fridge_certbot.log"
        warn "HTTP 仍然可用，修好后单独执行：certbot --nginx -d ${DOMAIN}"
    fi
fi

# ============================================================
step "Supabase 保活定时任务"
# ============================================================

# ⚠️ Supabase 免费版**连续 7 天没有请求就自动暂停**。
#    演示前一天被暂停 = 灾难。所以挂个每天访问一次的定时任务。
SUPA_URL=$(grep -E '^SUPABASE_URL=' "$ENV_FILE" | head -1 | cut -d= -f2- || true)
if [ -n "$SUPA_URL" ]; then
    SUPA_URL="${SUPA_URL%/}"     # 去掉结尾斜杠，避免拼出 //rest/v1/
    CRON_LINE="17 3 * * * curl -s -o /dev/null --max-time 20 '${SUPA_URL}/rest/v1/'"

    # 先删掉旧的同功能条目，避免重复添加
    crontab -l 2>/dev/null | grep -v "supabase.co/rest/v1" > /tmp/fridge_cron || true
    echo "$CRON_LINE" >> /tmp/fridge_cron
    crontab /tmp/fridge_cron
    rm -f /tmp/fridge_cron
    ok "已加定时任务（每天 03:17 访问一次 Supabase）"

    # 立刻手动跑一次，顺便验证 URL 是对的
    CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 20 "${SUPA_URL}/rest/v1/" || echo "000")
    info "刚才手动访问了一次，HTTP ${CODE}（401 也算正常 —— 说明域名通了）"
else
    warn "没配 SUPABASE_URL，跳过保活任务"
    warn "⚠️ 如果你用了 Supabase，**务必**补上，否则闲置 7 天项目会被暂停"
fi

# ============================================================
step "自检"
# ============================================================

echo
echo "    ${C_BOLD}服务状态${C_RESET}"
systemctl is-active --quiet "$SERVICE_NAME" && ok "systemd: 运行中" || warn "systemd: 未运行"
systemctl is-active --quiet nginx && ok "nginx: 运行中" || warn "nginx: 未运行"

echo
echo "    ${C_BOLD}数据库连的是哪个${C_RESET}"
# 这一行能看出连的是 Supabase 还是 SQLite —— 部署时最容易搞错的地方
DB_LINE=$(journalctl -u "$SERVICE_NAME" -n 60 --no-pager 2>/dev/null | grep -o '数据库已就绪: .*' | tail -1 || true)
if [ -n "$DB_LINE" ]; then
    ok "$DB_LINE"
    case "$DB_LINE" in
        *supabase*) : ;;
        *sqlite*)   warn "连的是 SQLite！重启会丢数据。检查 .env 里的 DATABASE_URL" ;;
    esac
else
    warn "日志里没看到「数据库已就绪」，用 journalctl -u ${SERVICE_NAME} -n 50 看一下"
fi

echo
echo "    ${C_BOLD}图片存储${C_RESET}"
IMG_LINE=$(journalctl -u "$SERVICE_NAME" -n 60 --no-pager 2>/dev/null | grep -o '图片存储: .*' | tail -1 || true)
if [ -n "$IMG_LINE" ]; then
    ok "$IMG_LINE"
    case "$IMG_LINE" in
        *本地磁盘*) warn "图片存本地 —— 重建服务器会丢。检查 SUPABASE_URL / SUPABASE_SERVICE_KEY 和 bucket" ;;
    esac
fi

echo
echo "    ${C_BOLD}接口连通性${C_RESET}"

# ① 先打**本机 443**（带正确的 Host，绕过外部防火墙）。
#
# 这一步是「应用 + Nginx + 证书」三件事是否都好的**唯一可靠判据** ——
# 它不走外网，所以防火墙开没开都不影响它。
LOCAL_HTTPS=$(curl -sk -o /dev/null -w "%{http_code}" --max-time 10 \
    -H "Host: ${DOMAIN}" https://127.0.0.1/health || echo "000")
if [ "$LOCAL_HTTPS" = "200" ]; then
    ok "本机 https://127.0.0.1/health（带 Host 头）→ 200，应用和证书都正常"
else
    warn "本机 443 → ${LOCAL_HTTPS}（不是 200）—— 问题在应用或 Nginx 本身："
    warn "  sudo journalctl -u ${SERVICE_NAME} -n 30"
    warn "  sudo nginx -t"
fi

# ⚠️ 静态图单独查 —— 它**不走 Python**（Nginx 直接发文件），
# 所以「接口全通」不代表图能显示。
#
# 2026-10-07 首次部署就是这个挂了：/home/fridge 是 drwxr-x---，
# Nginx(www-data) 穿不过去 → /static/ 全 403 → **App 里一张图都不显示**，
# 而 /health 和所有接口都正常，很容易查错方向。
STATIC_CODE=$(curl -sk -o /dev/null -w "%{http_code}" --max-time 10 \
    -H "Host: ${DOMAIN}" "https://127.0.0.1/static/ingredients/tomato.jpg" || echo "000")
if [ "$STATIC_CODE" = "200" ]; then
    ok "静态图 /static/ → 200（Nginx 读得到文件）"
else
    warn "静态图 /static/ → ${STATIC_CODE} —— **App 里会一张图都不显示**"
    warn "最常见的原因：Nginx(www-data) 读不到 ${APP_DIR}/backend/static/"
    warn "  sudo usermod -aG ${APP_USER} www-data && sudo systemctl restart nginx"
    warn "  （看 Nginx 日志确认：sudo tail -5 /var/log/nginx/error.log）"
fi

# ② 再打公网。
#
# ⚠️ 这一步失败**基本只有一个原因：防火墙没放行**。# 之前这里报「证书可能还没签成功」，把排查方向带偏了 ——
# 证书早就签好了，是 443 没开。
PUBLIC_HTTPS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 15 "https://${DOMAIN}/health" || echo "000")
if [ "$PUBLIC_HTTPS" = "200" ]; then
    ok "公网 https://${DOMAIN}/health → 200"
else
    PUBLIC_HTTP=$(curl -s -o /dev/null -w "%{http_code}" --max-time 15 "http://${DOMAIN}/health" || echo "000")
    if [ "$LOCAL_HTTPS" = "200" ]; then
        # 本机通、公网不通 → 几乎一定是防火墙
        warn "公网 https://${DOMAIN}/health → ${PUBLIC_HTTPS}（本机是通的）"
        warn "所以问题在**外部网络到这台机器的路上**，去控制台放行端口："
        warn "  腾讯云轻量应用服务器 → 选中实例 → 防火墙 → 添加规则"
        warn "    443/TCP  ← 不加这条，App 正式包连不上"
        warn "    80/TCP   ← 不加这条，certbot 续期会失败"
        [ "$PUBLIC_HTTP" = "301" ] && warn "  （80 现在是通的，只差 443）"
    else
        warn "公网 https://${DOMAIN}/health → ${PUBLIC_HTTPS}"
        warn "本机 443 也不通，先按上面的提示查应用；另外确认："
        warn "  1. 控制台防火墙放行了 80 和 443"
        warn "  2. 域名的 A 记录指向这台服务器"
    fi
fi

# ============================================================
echo
echo "${C_BOLD}${C_GREEN}============================================================${C_RESET}"
echo "${C_BOLD}${C_GREEN} 部署完成${C_RESET}"
echo "${C_BOLD}${C_GREEN}============================================================${C_RESET}"
echo
echo "  接口地址   https://${DOMAIN}/"
echo "  接口文档   https://${DOMAIN}/docs"
echo "  健康检查   https://${DOMAIN}/health"
echo
echo "  ${C_BOLD}下一步：打 App 的正式包${C_RESET}"
echo
echo "    在**你自己的电脑上**执行："
echo
echo "      cd \"G:/workbuddy/任务路径/Fridge-Prophet/android\""
echo "      export JAVA_HOME=\"G:/Android/jdk-17.0.20.1+1\""
echo "      \"G:/Android/gradle-8.14.5/bin/gradle.bat\" clean assembleRelease \\"
echo "        -PAPI_BASE_URL=https://${DOMAIN}/ --console=plain"
echo
echo "    ⚠️ release 包做了 R8 混淆，装上真机后**必须完整走一遍九步链路**，"
echo "       不能只看能不能打开 —— 混淆可能让某些接口静默失败。"
echo
echo "  ${C_BOLD}常用命令${C_RESET}"
echo
echo "    sudo systemctl restart ${SERVICE_NAME}      # 改完 .env 重启服务"
echo "    sudo journalctl -u ${SERVICE_NAME} -f       # 实时看日志"
echo "    sudo nano ${ENV_FILE}    # 改配置"
echo "    sudo certbot renew --dry-run               # 测试证书自动续期"
echo
echo "  ${C_BOLD}演示前检查（docs/03 末尾那份清单）${C_RESET}"
echo
echo "    [ ] 手动跑过一次保活：curl -s -o /dev/null -w '%{http_code}\\n' ${SUPA_URL:-<你的supabase>}/rest/v1/"
echo "    [ ] SECRET_KEY 是现生成的，不是开发环境那份（本脚本生成 .env 时已保证）"
echo "    [ ] 服务器上**没有** .env / keystore.properties / *.jks 被提交进 git"
echo "    [ ] 演示前一天，在手机浏览器打开 https://${DOMAIN}/health 确认没被暂停"
echo
