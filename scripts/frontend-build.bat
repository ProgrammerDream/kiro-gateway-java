@echo off
chcp 65001 >nul
echo ========================================
echo  Frontend Build - Kiro Gateway Admin
echo ========================================

cd /d "%~dp0..\frontend"

echo [1/2] npm install...
call npm install
if %errorlevel% neq 0 (
    echo npm install failed!
    pause
    exit /b 1
)

echo [2/2] npm run build...
call npm run build
if %errorlevel% neq 0 (
    echo build failed!
    pause
    exit /b 1
)

echo ========================================
echo  Done! output: src\main\resources\static
echo ========================================
pause