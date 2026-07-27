@echo off
setlocal enabledelayedexpansion

REM ============================================
REM Proc Dependency Extractor - One-Click Start
REM Uses bundled Maven 3.8.1 (compatible with Spring Boot 3.2.x)
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
echo [1/3] Checking Java...
java -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java not found. Please install JDK 17 and set JAVA_HOME.
    goto :end
)
echo [OK] Java ready.
echo.

REM Check Maven
echo [2/3] Checking Maven...
if not exist "%MVN_CMD%" (
    echo [ERROR] Maven not found at: %MVN_CMD%
    echo         Please check MAVEN_HOME in this script.
    goto :end
)
echo [OK] Maven ready.
echo Current Maven version:
call "%MVN_CMD%" -version | findstr /i "Apache Maven"
echo.

REM Check MySQL config
echo [3/3] Reminder:
echo   - Please run sql\init.sql to create database proc_extractor first
echo   - Please confirm DB connection in application.yml
echo.
echo --------------------------------------------
echo  Application is starting, press Ctrl+C to stop
echo --------------------------------------------
echo.

call "%MVN_CMD%" spring-boot:run

:end
echo.
echo ============================================
echo  Script finished. Press any key to close.
echo ============================================
pause >nul
exit /b 0
