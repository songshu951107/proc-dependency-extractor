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

REM 等待前端就绪
echo Waiting for frontend to be ready...
timeout /t 5 /nobreak >nul

echo.
echo --------------------------------------------
echo  Backend is starting, press Ctrl+C to stop
echo --------------------------------------------
echo.
echo  ============================================
echo   Access URL: http://localhost:5173
echo   (Frontend with hot-reload, proxies /api to 8080)
echo  ============================================
echo.

REM ============================================
REM 启动后端 (前台, spring-boot:run)
REM ============================================
call "%MVN_CMD%" spring-boot:run

:end
echo.
echo ============================================
echo  Script finished. Press any key to close.
echo ============================================
pause >nul
exit /b 0
