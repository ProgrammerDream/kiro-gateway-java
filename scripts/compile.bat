@echo off
chcp 65001 >nul
echo ========================================
echo  Kiro Gateway Java - Compile
echo ========================================

cd /d "%~dp0.."

set MVN_CMD="C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1.1\plugins\maven\lib\maven3\bin\mvn.cmd"
set MVN_SETTINGS="C:\develop\important\settings_client.xml"

echo [1/1] Maven package...
call %MVN_CMD% -s %MVN_SETTINGS% clean package -DskipTests -q
if %errorlevel% neq 0 (
    echo Maven build failed!
    pause
    exit /b 1
)

echo ========================================
echo  Build success!
echo ========================================
