[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Write-Host "========================================"
Write-Host "  Kiro Gateway Java - Compile"
Write-Host "========================================"

Set-Location "$PSScriptRoot\..\.."

$MvnCmd = "C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1.1\plugins\maven\lib\maven3\bin\mvn.cmd"
$MvnSettings = "C:\develop\important\settings_client.xml"

Write-Host "[1/1] Maven package..."
& $MvnCmd -s $MvnSettings clean package -DskipTests -q
if ($LASTEXITCODE -ne 0) {
    Write-Host "Maven build failed!" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host "========================================"
Write-Host "  Build success!" -ForegroundColor Green
Write-Host "========================================"
