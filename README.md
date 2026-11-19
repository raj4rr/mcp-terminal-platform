# MCP Terminal Platform

A local-first, safe developer control plane powered by the **Model Context Protocol (MCP)**:
- **React + TypeScript GUI** for inspecting tools, workspace files, and approving diagnostics.
- **Spring Boot Gateway** acting as an MCP client and REST intermediary.
- **Java MCP Server** running as a local child process over STDIO.
- **Ollama AI Assistant** (advisory only; runs lightweight `qwen2.5:0.5b` with zero autonomous tool execution).
- **Two-Phase Command Approval** with a strict, fixed server-side allowlist executed via `ProcessBuilder` (no shell).
- **Workspace Confinement** ensuring file operations never escape the designated root directory.

---

## Architecture

```
+-----------------------------------------------------------+
|              React + TypeScript Frontend (Vite)           |
|                 http://localhost:5173                     |
+-----------------------------+-----------------------------+
                              | HTTP / Proxy
+-----------------------------v-----------------------------+
|               Spring Boot Gateway (Port 8080)             |
|  - MCP Client                                             |
|  - REST Endpoints (/api/tools, /api/commands, /api/chat)  |
|  - Command Approval State Machine                         |
+---------------+-----------------------------+-------------+
                | STDIO                       | HTTP
+---------------v-------------+ +-------------v-------------+
|    Java MCP Server Process  | |       Ollama Service      |
|  - list_files, read_file    | |   http://localhost:11434  |
|  - system_info, run_command | |   (Model: qwen2.5:0.5b)   |
|  - Path Traversal Guards    | |   Advisory Assistant      |
|  - ProcessBuilder Allowlist | +---------------------------+
+-----------------------------+
```

---

## Requirements

- **Docker Option**: Docker 24+ & Docker Compose (nothing else required).
- **Local Option**: JDK 21+, Maven 3.9+, Node.js 20+, Ollama.

---

## Quick Start

### Option A: Docker (Recommended)

Spins up the Gateway, Frontend, and Ollama with `qwen2.5:0.5b` automatically pulled and ready:

```bash
./start.sh --docker
```
*Or directly via Docker Compose:*
```bash
docker-compose up --build
```

- **Frontend Dashboard**: [http://localhost:5173](http://localhost:5173)
- **Gateway Health**: [http://localhost:8080/api/health](http://localhost:8080/api/health)
- **Ollama Service**: [http://localhost:11434](http://localhost:11434)
- **Shared Workspace**: Mounted at `./workspace`

---

### Option B: Local Native (`start.sh`)

Automatically detects Java and Node environments, compiles backend JARs if missing, and starts both services:

```bash
./start.sh
```

#### Script Options

```bash
./start.sh --docker   # Run full stack in Docker (gateway, frontend, ollama)
./start.sh --build    # Force rebuilding backend JARs and reinstalling node modules
./start.sh --help     # Show help and environment variable overrides
```

---

### Option C: Manual Multi-Terminal Run

**Terminal 1 (Local Ollama):**
```bash
ollama serve
ollama pull qwen2.5:0.5b
```

**Terminal 2 (Build & Start Gateway):**
```bash
mvn clean package -DskipTests
mkdir -p "$HOME/mcp-workspace"
export MCP_WORKSPACE="$HOME/mcp-workspace"
export MCP_SERVER_JAR="$(pwd)/terminal-mcp-server/target/terminal-mcp-server-1.0.0.jar"
java -jar mcp-gateway/target/mcp-gateway-1.0.0.jar
```

**Terminal 3 (Frontend):**
```bash
cd frontend
npm install
npm run dev
```

Open [http://localhost:5173](http://localhost:5173).

---

## MCP Tools & Diagnostic Commands

### Available MCP Tools

| Tool | Parameters | Description |
| :--- | :--- | :--- |
| `list_files` | `path` (relative) | Lists files and directories inside the workspace. |
| `read_file` | `path` (relative) | Reads a UTF-8 text file (enforces a 256 KB maximum). |
| `system_info` | *none* | Returns OS name/version, Java runtime, and workspace root. |
| `run_command` | `command` | Executes an exact allowlisted diagnostic command. Requires explicit preview & approval flow. |

### Command Allowlist

Commands are dispatched directly to `java.lang.ProcessBuilder` using fixed argument lists—**no shell interpreter is ever spawned**, preventing injection attacks, pipes, and redirection:

- `pwd`
- `date`
- `whoami`
- `uname` (executed as `uname -a`)
- `java -version`
- `git status` (executed as `git -C <workspace> status --short --branch`)
- `git log -1 --oneline` (executed as `git -C <workspace> log -1 --oneline`)
- `ls -la` (executed as `ls -la <workspace>`)

Arbitrary inputs or commands like `rm -rf /`, `cat /etc/passwd`, or shell chains (`&&`, `|`) are strictly rejected.

---

## Ollama AI Assistant (Light Qwen)

The dashboard includes a local AI advisory chat powered by **`qwen2.5:0.5b`** (~398 MB download, fast inference with low memory usage).

- **Strict Advisory Guardrails**: The assistant answers questions about commands and tools, but has **no execution capabilities**.
- **No Autonomous Execution**: AI responses cannot bypass GUI approval or run shell commands.

### Suggested Queries to Try

- *"What diagnostic commands are available on this platform and what does `uname` show?"*
- *"How do I check the current Git branch and uncommitted changes?"*
- *"Explain what `git log -1 --oneline` outputs."*
- *"Why does `read_file` limit files to 256 KB and confine paths to the workspace?"*
- *"How does the Model Context Protocol (MCP) STDIO transport isolate the server?"*

---

## Configuration Reference

Configure services using environment variables:

| Variable | Default | Description |
| :--- | :--- | :--- |
| `SERVER_PORT` | `8080` | HTTP port for the Spring Boot Gateway |
| `SERVER_ADDRESS` | `127.0.0.1` (`0.0.0.0` in Docker) | Network bind address |
| `FRONTEND_PORT` | `5173` | Port for Vite / Nginx frontend |
| `MCP_WORKSPACE` | `$HOME/mcp-workspace` (`./workspace` in Docker) | Directory for workspace-confined operations |
| `MCP_SERVER_JAR` | `terminal-mcp-server/target/terminal-mcp-server-1.0.0.jar` | Absolute path to the MCP server JAR |
| `OLLAMA_BASE_URL` | `http://localhost:11434` (`http://ollama:11434` in Docker) | Base URL for Ollama API |
| `OLLAMA_MODEL` | `qwen2.5:0.5b` | Ollama model name |

---

## Security Model

1. **Zero Shell Execution**: Commands are never evaluated inside a shell (`/bin/sh` or `/bin/bash`). Arguments are passed directly to OS `execve` via `ProcessBuilder`.
2. **Explicit User Approval**: The UI implements a two-step flow (`Request Approval` $\to$ `Approve & Run`). UUID tokens expire upon execution.
3. **Workspace Path Sanitization**: All file paths are resolved with `.toRealPath()` to block directory traversal (`../`) and prevent symlinks from escaping the workspace root.
4. **CORS Isolation**: Gateway CORS is restricted to loopback origin patterns (`http://localhost:*` and `http://127.0.0.1:*`).
5. **Read-Only LLM**: The Ollama assistant has no access to invoke tools or execute terminal operations.

---

## Smoke Test Checklist

1. Start the stack via `./start.sh` or `./start.sh --docker`.
2. Open [http://localhost:5173](http://localhost:5173) and verify that the status pill indicates **MCP Connected**.
3. Under **Workspace files**, click **List** to view entries in your workspace.
4. Click **System information** to inspect your host OS and Java version.
5. Under **Command approval**, choose `date`, click **Request approval**, and click **Approve & run**. Confirm the output appears in **Activity & terminal output**.
6. In **Ollama assistant**, type *"How do I check my git status?"* and verify the local model provides guidance.
7. Confirm that arbitrary inputs or destructive commands are rejected.
