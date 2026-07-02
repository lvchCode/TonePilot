#!/usr/bin/env bash
set -euo pipefail

# 本地完整依赖启动脚本：先启动 Docker Compose 基础设施，再让后端连接 MySQL、Redis、MinIO、Milvus。

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${ROOT_DIR}/logs"
BACKEND_LOG="${LOG_DIR}/backend-compose.log"

cd "${ROOT_DIR}"
if [ -f "${ROOT_DIR}/.env.local" ]; then
  echo "加载本地配置：${ROOT_DIR}/.env.local"
  set -a
  # shellcheck disable=SC1091
  source "${ROOT_DIR}/.env.local"
  set +a
fi
mkdir -p "${LOG_DIR}"

echo "启动 Docker Compose 依赖：MySQL、Redis、MinIO、Milvus"
docker compose up -d mysql redis minio etcd milvus

echo "等待 MySQL 就绪"
for attempt in {1..40}; do
  if docker compose exec -T mysql mysqladmin ping -h localhost -utonepilot -ptonepilot --silent >/dev/null 2>&1; then
    break
  fi
  if [ "${attempt}" -eq 40 ]; then
    echo "MySQL 在等待时间内没有就绪，请执行 docker compose logs mysql 查看原因。"
    exit 1
  fi
  sleep 2
done

if command -v ss >/dev/null 2>&1 && ss -ltnp | grep -q ":8080 "; then
  backend_pid="$(ss -ltnp | awk '/:8080 / {print $NF}' | sed -n 's/.*pid=\([0-9]*\).*/\1/p' | head -1)"
  if [ -n "${backend_pid}" ]; then
    echo "停止旧后端进程：${backend_pid}"
    kill "${backend_pid}" || true
    sleep 2
  fi
fi

echo "启动后端，连接 Docker Compose 的 MySQL、Redis、MinIO、Milvus"
cd "${ROOT_DIR}/tonepilot-admin/backend"

export TONEPILOT_DB_URL="jdbc:mysql://localhost:3306/tonepilot?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false"
export TONEPILOT_DB_USERNAME="tonepilot"
export TONEPILOT_DB_PASSWORD="tonepilot"
export TONEPILOT_DB_DRIVER="com.mysql.cj.jdbc.Driver"
export TONEPILOT_SQL_INIT_MODE="always"
export SPRING_SQL_INIT_SCHEMA_LOCATIONS="classpath:schema-mysql.sql"
export SPRING_SQL_INIT_CONTINUE_ON_ERROR="true"

export REDIS_HOST="localhost"
export REDIS_PORT="6379"

export TONEPILOT_STORAGE_TYPE="minio"
export MINIO_ENDPOINT="http://localhost:9000"
export MINIO_ACCESS_KEY="tonepilot"
export MINIO_SECRET_KEY="tonepilot123"
export MINIO_BUCKET="tonepilot"

export TONEPILOT_VECTOR_STORE="milvus"
export MILVUS_URI="http://localhost:19530"
export MILVUS_COLLECTION="tonepilot_knowledge"

echo "安装管理端后端多模块依赖"
mvn -q -DskipTests install

: > "${BACKEND_LOG}"
setsid nohup mvn -f starter/pom.xml spring-boot:run > "${BACKEND_LOG}" 2>&1 < /dev/null &
echo "$!" > "${LOG_DIR}/backend-compose.pid"
echo "后端启动中，日志：${BACKEND_LOG}"
echo "后端进程 PID 文件：${LOG_DIR}/backend-compose.pid"
echo "查看状态：curl http://localhost:8080/api/admin/styles"
