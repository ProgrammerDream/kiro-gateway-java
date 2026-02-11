@echo off
chcp 65001 >nul
echo ========================================
echo  Frontend Dev Server - Kiro Gateway Admin
echo  URL: http://localhost:3000
echo  API Proxy: http://localhost:8080
echo ========================================

cd /d "%~dp0..\frontend"

if not exist node_modules (
    echo First run, installing dependencies...
    call npm install
    if %errorlevel% neq 0 (
        echo npm install failed!
        pause
        exit /b 1
    )
)

echo Starting dev server...
call npm run dev
pause