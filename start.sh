#!/usr/bin/env bash
set -euo pipefail

# MCP Terminal Platform Launch Script
# Resolves root directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Color definitions
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m' # No Color

info() { echo -e "${BLUE}[INFO]${NC} $*"; }
success() { echo -e "${GREEN}[OK]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*" >&2; }

usage() {
    cat <<EOF
Usage: ./start.sh [options]

Options:
  -d, --docker      Run the full platform (gateway, frontend, ollama) using Docker
  -b, --build       Force rebuild of Java JARs and reinstall npm packages (local mode)
  -h, --help        Show this help message

Environment Variables:
  SERVER_PORT       Gateway HTTP port (default: 8080)
  FRONTEND_PORT     Frontend Vite port (default: 5173)
  MCP_WORKSPACE     Workspace path for MCP server (default: \$HOME/mcp-workspace)
  MCP_SERVER_JAR    Path to terminal-mcp-server JAR
  OLLAMA_BASE_URL   Ollama API URL (default: http://localhost:11434)
  OLLAMA_MODEL      Ollama model name (default: qwen2.5:0.5b)
EOF
    exit 0
}

USE_DOCKER=false
FORCE_BUILD=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        -d|--docker)
            USE_DOCKER=true
            shift
            ;;
        -b|--build)
            FORCE_BUILD=true
            shift
            ;;
        -h|--help)
            usage
            ;;
        *)
            error "Unknown argument: $1"
            usage
            ;;
    esac
done

if [ "$USE_DOCKER" = true ]; then
    echo -e "${BOLD}========================================${NC}"
    echo -e "${BOLD}   MCP Terminal Platform (Docker Mode)  ${NC}"
    echo -e "${BOLD}========================================${NC}"
    DOCKER_CMD=""
    if docker compose version >/dev/null 2>&1; then
        DOCKER_CMD="docker compose"
    elif command -v docker-compose >/dev/null 2>&1; then
        DOCKER_CMD="docker-compose"
    else
        error "Neither 'docker compose' nor 'docker-compose' was found."
        exit 1
    fi
    info "Launching containers via $DOCKER_CMD..."
    mkdir -p "$SCRIPT_DIR/workspace"
    exec $DOCKER_CMD up --build
fi

echo -e "${BOLD}========================================${NC}"
echo -e "${BOLD}       MCP Terminal Platform            ${NC}"
echo -e "${BOLD}========================================${NC}"

# 1. Detect & configure Java environment
if ! command -v java >/dev/null 2>&1 || ! java -version >/dev/null 2>&1; then
    if [ -x "/opt/homebrew/opt/openjdk/bin/java" ]; then
        export PATH="/opt/homebrew/opt/openjdk/bin:$PATH"
        export JAVA_HOME="/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"
    elif [ -x "/usr/local/opt/openjdk/bin/java" ]; then
        export PATH="/usr/local/opt/openjdk/bin:$PATH"
        export JAVA_HOME="/usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home"
    elif [ -x "/usr/libexec/java_home" ]; then
        JH="$(/usr/libexec/java_home 2>/dev/null || true)"
        if [ -n "$JH" ] && [ -d "$JH" ]; then
            export JAVA_HOME="$JH"
            export PATH="$JAVA_HOME/bin:$PATH"
        fi
    fi
fi

if ! command -v java >/dev/null 2>&1; then
    error "Java (JDK 21+) is not found in PATH. Please install Java 21 or set JAVA_HOME."
    exit 1
fi

JAVA_VER_LINE=$(java -version 2>&1 | head -n 1)
info "Using Java: $JAVA_VER_LINE"

# 2. Check Node & npm
if ! command -v node >/dev/null 2>&1; then
    error "Node.js (v20+) is not found. Please install Node.js."
    exit 1
fi

if ! command -v npm >/dev/null 2>&1; then
    error "npm is not found. Please install npm."
    exit 1
fi
info "Using Node: $(node -v) | npm: $(npm -v)"

# 3. Configure paths & variables
export SERVER_PORT="${SERVER_PORT:-8080}"
export FRONTEND_PORT="${FRONTEND_PORT:-5173}"
export MCP_WORKSPACE="${MCP_WORKSPACE:-$HOME/mcp-workspace}"
export MCP_SERVER_JAR="${MCP_SERVER_JAR:-$SCRIPT_DIR/terminal-mcp-server/target/terminal-mcp-server-1.0.0.jar}"
export OLLAMA_BASE_URL="${OLLAMA_BASE_URL:-http://localhost:11434}"
export OLLAMA_MODEL="${OLLAMA_MODEL:-qwen2.5:0.5b}"

GATEWAY_JAR="$SCRIPT_DIR/mcp-gateway/target/mcp-gateway-1.0.0.jar"
GATEWAY_LOG="$SCRIPT_DIR/.gateway.log"

# Prepare workspace directory
mkdir -p "$MCP_WORKSPACE"
info "Workspace directory: $MCP_WORKSPACE"

# 4. Check & build backend JARs if necessary
NEED_BACKEND_BUILD=false
if [ "$FORCE_BUILD" = true ] || [ ! -f "$MCP_SERVER_JAR" ] || [ ! -f "$GATEWAY_JAR" ]; then
    NEED_BACKEND_BUILD=true
elif [ -n "$(find "$SCRIPT_DIR/terminal-mcp-server/src" "$SCRIPT_DIR/terminal-mcp-server/pom.xml" -newer "$MCP_SERVER_JAR" 2>/dev/null || true)" ] || \
     [ -n "$(find "$SCRIPT_DIR/mcp-gateway/src" "$SCRIPT_DIR/mcp-gateway/pom.xml" "$SCRIPT_DIR/pom.xml" -newer "$GATEWAY_JAR" 2>/dev/null || true)" ]; then
    info "Source or POM changes detected. Rebuilding backend..."
    NEED_BACKEND_BUILD=true
fi

if [ "$NEED_BACKEND_BUILD" = true ]; then
    if ! command -v mvn >/dev/null 2>&1; then
        error "Maven is required to build the JARs but 'mvn' was not found in PATH."
        exit 1
    fi
    info "Building backend JARs with Maven..."
    mvn clean package -DskipTests
    success "Backend JARs built successfully."
fi

if [ ! -f "$MCP_SERVER_JAR" ]; then
    error "MCP Server JAR not found at: $MCP_SERVER_JAR"
    exit 1
fi

if [ ! -f "$GATEWAY_JAR" ]; then
    error "Gateway JAR not found at: $GATEWAY_JAR"
    exit 1
fi

# 5. Check & install frontend dependencies if necessary
if [ "$FORCE_BUILD" = true ] || [ ! -d "$SCRIPT_DIR/frontend/node_modules" ] || [ "$SCRIPT_DIR/frontend/package.json" -newer "$SCRIPT_DIR/frontend/node_modules" 2>/dev/null ]; then
    info "Installing frontend dependencies..."
    (cd "$SCRIPT_DIR/frontend" && npm install)
    success "Frontend dependencies installed."
fi

# 6. Check Ollama (advisory)
if curl -s -m 2 "$OLLAMA_BASE_URL/api/tags" >/dev/null 2>&1; then
    success "Ollama detected at $OLLAMA_BASE_URL."
    # Check if the configured model is installed
    if ! curl -s "$OLLAMA_BASE_URL/api/tags" | grep -q "\"name\":\"$OLLAMA_MODEL" 2>/dev/null && \
       ! curl -s "$OLLAMA_BASE_URL/api/tags" | grep -q "\"model\":\"$OLLAMA_MODEL" 2>/dev/null; then
        warn "Model '$OLLAMA_MODEL' is not yet pulled in Ollama."
        if command -v ollama >/dev/null 2>&1; then
            info "Auto-pulling lightweight model '$OLLAMA_MODEL' (~398 MB)..."
            ollama pull "$OLLAMA_MODEL" || warn "Auto-pull failed. Please run: ollama pull $OLLAMA_MODEL"
        else
            warn "Please run: ollama pull $OLLAMA_MODEL"
        fi
    else
        success "Model '$OLLAMA_MODEL' is ready in Ollama."
    fi
else
    warn "Ollama is not running at $OLLAMA_BASE_URL."
    warn "The platform and terminal MCP tools work without it. To enable AI chat:"
    warn "  ollama serve"
    warn "  ollama pull $OLLAMA_MODEL"
fi

# 7. Check if ports are already in use
check_port() {
    local port="$1"
    if command -v lsof >/dev/null 2>&1; then
        lsof -i ":$port" -sTCP:LISTEN -t 2>/dev/null || true
    fi
}

EXISTING_GW_PID=$(check_port "$SERVER_PORT")
if [ -n "$EXISTING_GW_PID" ]; then
    error "Port $SERVER_PORT is already in use by PID $EXISTING_GW_PID."
    error "Please stop that process before starting the gateway."
    exit 1
fi

EXISTING_FE_PID=$(check_port "$FRONTEND_PORT")
if [ -n "$EXISTING_FE_PID" ]; then
    warn "Port $FRONTEND_PORT is already in use by PID $EXISTING_FE_PID (Vite might pick an alternative port)."
fi

# 8. Setup cleanup handlers
GATEWAY_PID=""
FRONTEND_PID=""

cleanup() {
    echo ""
    info "Shutting down MCP Terminal Platform..."
    if [ -n "$FRONTEND_PID" ] && kill -0 "$FRONTEND_PID" 2>/dev/null; then
        kill "$FRONTEND_PID" 2>/dev/null || true
    fi
    if [ -n "$GATEWAY_PID" ] && kill -0 "$GATEWAY_PID" 2>/dev/null; then
        kill "$GATEWAY_PID" 2>/dev/null || true
    fi
    wait 2>/dev/null || true
    success "All processes stopped cleanly."
}

trap cleanup INT TERM EXIT

# 9. Start Gateway
info "Starting MCP Gateway on http://127.0.0.1:$SERVER_PORT..."
java -jar "$GATEWAY_JAR" > "$GATEWAY_LOG" 2>&1 &
GATEWAY_PID=$!

# Wait for gateway health endpoint
info "Waiting for gateway to initialize..."
HEALTH_URL="http://127.0.0.1:$SERVER_PORT/api/health"
MAX_ATTEMPTS=40
ATTEMPT=0
GATEWAY_READY=false

while [ $ATTEMPT -lt $MAX_ATTEMPTS ]; do
    if ! kill -0 "$GATEWAY_PID" 2>/dev/null; then
        error "Gateway process exited prematurely. Last log lines:"
        tail -n 25 "$GATEWAY_LOG" >&2 || true
        exit 1
    fi

    if curl -s "$HEALTH_URL" | grep -q '"status":"ok"' 2>/dev/null; then
        GATEWAY_READY=true
        break
    fi

    sleep 0.5
    ATTEMPT=$((ATTEMPT + 1))
done

if [ "$GATEWAY_READY" = false ]; then
    error "Gateway failed to start within $((MAX_ATTEMPTS / 2)) seconds. Log output:"
    tail -n 25 "$GATEWAY_LOG" >&2 || true
    exit 1
fi

success "MCP Gateway is live and MCP server connected."

# 10. Start Frontend
info "Starting Frontend GUI on http://localhost:$FRONTEND_PORT..."
cd "$SCRIPT_DIR/frontend"
npm run dev &
FRONTEND_PID=$!

echo ""
echo -e "${GREEN}${BOLD}================================================================${NC}"
echo -e "${GREEN}${BOLD}  MCP Terminal Platform is running!                             ${NC}"
echo -e "${BOLD}  - Frontend GUI:     ${BLUE}http://localhost:${FRONTEND_PORT}${NC}"
echo -e "${BOLD}  - Gateway API:      ${BLUE}http://127.0.0.1:${SERVER_PORT}${NC}"
echo -e "${BOLD}  - MCP Workspace:    ${NC}${MCP_WORKSPACE}"
echo -e "${BOLD}  - Gateway Log:      ${NC}${GATEWAY_LOG}"
echo -e "${GREEN}${BOLD}================================================================${NC}"
echo -e "Press ${BOLD}Ctrl+C${NC} to stop all services."
echo ""

# Wait on background processes
wait "$FRONTEND_PID" "$GATEWAY_PID" 2>/dev/null || true
