#!/usr/bin/env bash
# =====================================================================
# proc-dependency-extractor 内网启动脚本（Linux）
# 用法：
#   1) cp .env.example .env          # 填入内网真实连接与路径
#   2) source .env                   # 加载环境变量（或由 systemd 注入）
#   3) ./start.sh [start|stop|status|restart]
# 若用 systemd（推荐），直接执行：systemctl start pde，无需用本脚本。
# =====================================================================
set -euo pipefail

APP_NAME="proc-dependency-extractor"
# 修改为内网实际 jar 路径与 .env 路径
JAR="/data/pde/${APP_NAME}.jar"
ENV_FILE="/data/pde/.env"
PID_FILE="/data/pde/${APP_NAME}.pid"
JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx512m}"

# 加载 .env（仅当存在且变量未预置时；已 export 的优先）
if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

need_java_env() {
  if ! command -v java >/dev/null 2>&1; then
    echo "[ERROR] 环境缺少 java，请先安装 JDK 17 并 export JAVA_HOME/PATH" >&2
    exit 1
  fi
}

start() {
  need_java_env
  if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "${APP_NAME} 已在运行 (pid $(cat "$PID_FILE"))"; return 0
  fi
  echo "启动 ${APP_NAME} ..."
  nohup java $JAVA_OPTS -jar "$JAR" >> "/data/pde/${APP_NAME}.log" 2>&1 &
  echo $! > "$PID_FILE"
  echo "pid=$(cat "$PID_FILE")，日志: /data/pde/${APP_NAME}.log"
}

stop() {
  if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if kill -0 "$PID" 2>/dev/null; then
      echo "停止 ${APP_NAME} (pid $PID)"
      kill "$PID"
      for _ in $(seq 1 30); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done
      kill -9 "$PID" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
  else
    echo "未发现 pid 文件，尝试 pkill"
    pkill -f "$JAR" 2>/dev/null || echo "无进程"
  fi
}

status() {
  if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "运行中 (pid $(cat "$PID_FILE"))"
  elif pgrep -f "$JAR" >/dev/null 2>&1; then
    echo "运行中 (pid $(pgrep -f "$JAR"))"
  else
    echo "未运行"; return 3
  fi
}

case "${1:-start}" in
  start) start ;;
  stop)  stop ;;
  restart) stop; sleep 1; start ;;
  status) status ;;
  *) echo "用法: $0 {start|stop|restart|status}"; exit 1 ;;
esac