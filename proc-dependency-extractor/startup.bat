@echo off
setlocal enabledelayedexpansion

REM ============================================
REM Proc Dependency Extractor - One-Click Start
REM 启动后端(8080) + 前端 dev server(5173, 带热更新)
REM 访问入口: http://localhost:5173
REM ============================================

cd /d "%~dp0"

REM Use Maven 3.8.1 from local installation (system Maven 3.0.5 is too old)
set "MAVEN_HOME=D:\anzhuangbao\idea\maven\apache-maven-3.8.1"
set "PATH=%MAVEN_HOME%\bin;%PATH%"
set "MVN_CMD=%MAVEN_HOME%\bin\mvn.cmd"

echo ============================================
echo  Proc Dependency Extractor Startup
echo ============================================
echo.

REM Check Java
echo [1/5] Checking Java...
java -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java not found. Please install JDK 17 and set JAVA_HOME.
    goto :end
)
echo [OK] Java ready.
echo.

REM Check Maven
echo [2/5] Checking Maven...
if not exist "%MVN_CMD%" (
    echo [ERROR] Maven not found at: %MVN_CMD%
    echo         Please check MAVEN_HOME in this script.
    goto :end
)
echo [OK] Maven ready.
echo Current Maven version:
call "%MVN_CMD%" -version | findstr /i "Apache Maven"
echo.

REM Check Frontend
echo [3/5] Checking Frontend...
if not exist "frontend\node_modules" (
    echo [WARN] node_modules not found, running npm install...
    cd frontend
    call npm install
    cd /d "%~dp0"
)
echo [OK] Frontend ready.
echo.

REM Check MySQL config
echo [4/5] Reminder:
echo   - Please run sql\init.sql to create database proc_extractor first
echo   - Please confirm DB connection in application.yml
echo.

REM Check Neo4j connection
echo [5/5] Checking Neo4j connection...
powershell -Command "try { $null = Invoke-WebRequest -Uri http://localhost:7474 -TimeoutSec 3 -UseBasicParsing; Write-Host '[OK] Neo4j is running' } catch { Write-Host '[WARN] Neo4j not started, impact analysis will be unavailable'; exit 1 }"
if errorlevel 1 (
    echo [HINT] Please ensure Neo4j is running on localhost:7687
    choice /c yn /m "Continue startup (MySQL-only mode)"
    if errorlevel 2 goto :end
)
echo.

REM ============================================
REM 清理残留进程：上次运行未正确关闭时，java(8080)/node(5173) 会残留并占用端口
REM 导致本次启动失败（Port already in use）。只清理本项目的 java/node 监听进程。
REM ============================================
echo [Cleanup] Checking stale processes on ports 8080/5173...
powershell -NoProfile -Command "foreach ($port in 8080,5173) { $c = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue; if ($c) { $p = Get-Process -Id $c[0].OwningProcess -ErrorAction SilentlyContinue; if ($p -and ($p.ProcessName -in 'java','node')) { Write-Host ('[CLEANUP] Killing stale ' + $p.ProcessName + ' PID=' + $p.Id + ' on port ' + $port); Stop-Process -Id $p.Id -Force; Start-Sleep -Milliseconds 500 } } }"
echo [OK] Ports ready.
echo.

REM ============================================
REM 启动前端 dev server (5173, 后台)
REM ============================================
echo Starting frontend dev server on port 5173...
start "PDE-Frontend" cmd /c "cd /d %~dp0frontend && npm run dev"

REM ============================================
REM 启动后端 (8080, 后台窗口 PDE-Backend)
REM ============================================
echo Starting backend on port 8080 (background window)...
start "PDE-Backend" cmd /c "cd /d %~dp0 && call %MVN_CMD% spring-boot:run"

REM ============================================
REM 等待后端真正就绪 (轮询 /api/impact/schemas, 最多 120s)
REM 就绪前不打开浏览器, 避免 Vite 代理 500 (时序问题根治)
REM ============================================
echo Waiting for backend to be ready (up to 120s)...
powershell -NoProfile -Command "$ok = $false; for ($i = 0; $i -lt 60; $i++) { try { $null = Invoke-WebRequest -Uri 'http://localhost:8080/api/impact/schemas' -TimeoutSec 2 -UseBasicParsing; $ok = $true; break } catch { Start-Sleep -Seconds 2 } }; if ($ok) { Write-Host '[OK] Backend is ready.' } else { Write-Host '[ERROR] Backend not ready after 120s.'; exit 1 }"
if errorlevel 1 (
    echo [ERROR] Backend failed to start. Check the "PDE-Backend" window for details.
    goto :end
)

echo.
echo  ============================================
echo   All services are UP. Opening browser...
echo.
echo   Access URL: http://localhost:5173
echo   Frontend window: PDE-Frontend  (hot-reload)
echo   Backend  window: PDE-Backend
echo.
echo   Press any key HERE to STOP all services.
echo  ============================================
echo.

start "" http://localhost:5173

REM 主窗口作为控制台: 按任意键停止全部服务 (杀 8080/5173 的 java/node)
pause >nul
echo Stopping services...
powershell -NoProfile -Command "foreach ($port in 8080,5173) { $c = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue; if ($c) { $p = Get-Process -Id $c[0].OwningProcess -ErrorAction SilentlyContinue; if ($p -and ($p.ProcessName -in 'java','node')) { Write-Host ('[STOP] Killing ' + $p.ProcessName + ' PID=' + $p.Id + ' on port ' + $port); Stop-Process -Id $p.Id -Force } } }"
echo [OK] Services stopped.

:end
echo.
echo ============================================
echo  Script finished. Press any key to close.
echo ============================================
pause >nul
exit /b 0
