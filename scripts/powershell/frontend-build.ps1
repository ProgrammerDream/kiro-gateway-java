[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Write-Host "========================================"
Write-Host "  Frontend Build - Kiro Gateway Admin"
Write-Host "========================================"

Set-Location "$PSScriptRoot\..\..\frontend"

Write-Host "[1/2] npm install..."
npm install
if ($LASTEXITCODE -ne 0) {
    Write-Host "npm install failed!" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host "[2/2] npm run build..."
npm run build
if ($LASTEXITCODE -ne 0) {
    Write-Host "build failed!" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host "========================================"
Write-Host "  Done! output: src\main\resources\static" -ForegroundColor Green
Write-Host "========================================"
Read-Host "Press Enter to exit"
