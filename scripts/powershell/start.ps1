[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Write-Host "========================================"
Write-Host "  Kiro Gateway Java - Start"
Write-Host "  Port: 8080"
Write-Host "========================================"

Set-Location "$PSScriptRoot\..\.."

$MvnCmd = "C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1.1\plugins\maven\lib\maven3\bin\mvn.cmd"
$MvnSettings = "C:\develop\important\settings_client.xml"

Write-Host "[1/2] Maven build..."
& $MvnCmd -s $MvnSettings clean package -DskipTests -q
if ($LASTEXITCODE -ne 0) {
    Write-Host "Maven build failed!" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host "[2/2] Starting application..."
Write-Host "========================================"

$jar = Get-ChildItem "target\kiro-gateway-java-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jar) {
    java -jar $jar.FullName
} else {
    Write-Host "JAR not found!" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

Read-Host "Press Enter to exit"
