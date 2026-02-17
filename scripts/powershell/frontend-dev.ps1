[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Write-Host "========================================"
Write-Host "  Frontend Dev Server - Kiro Gateway Admin"
Write-Host "  URL: http://localhost:3000"
Write-Host "  API Proxy: http://localhost:8080"
Write-Host "========================================"

Set-Location "$PSScriptRoot\..\..\frontend"

if (-not (Test-Path "node_modules")) {
    Write-Host "First run, installing dependencies..."
    npm install
    if ($LASTEXITCODE -ne 0) {
        Write-Host "npm install failed!" -ForegroundColor Red
        Read-Host "Press Enter to exit"
        exit 1
    }
}

Write-Host "Starting dev server..."
npm run dev
Read-Host "Press Enter to exit"
