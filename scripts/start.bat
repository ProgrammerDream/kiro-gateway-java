@echo off
chcp 65001 >nul
echo ========================================
echo  Kiro Gateway Java - Start
echo  Port: 8080
echo ========================================

cd /d "%~dp0.."

set MVN_CMD="C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1.1\plugins\maven\lib\maven3\bin\mvn.cmd"
set MVN_SETTINGS="C:\develop\important\settings_client.xml"

echo [1/2] Maven build...
call %MVN_CMD% -s %MVN_SETTINGS% clean package -DskipTests -q
if %errorlevel% neq 0 (
    echo Maven build failed!
    pause
    exit /b 1
)

echo [2/2] Starting application...
echo ========================================

for %%f in (target\kiro-gateway-java-*.jar) do (
    java -jar "%%f"
    goto :end
)

echo JAR not found!
pause
exit /b 1

:end
pause