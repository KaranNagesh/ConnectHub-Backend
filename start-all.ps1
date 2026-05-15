# ConnectHub Microservices Startup Script
# ----------------------------------------

Write-Host ">>> Starting ConnectHub Infrastructure..." -ForegroundColor Cyan

# 1. Start Docker Infrastructure
docker-compose up -d
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Failed to start Docker. Please make sure Docker Desktop is running." -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ">>> Waiting for Kafka and Redis to be ready..." -ForegroundColor Yellow
Start-Sleep -Seconds 10

# 2. Start Service Registry (Eureka)
Write-Host ">>> Starting Service Registry (8761)..." -ForegroundColor Cyan
Start-Job -Name "service-registry" -ScriptBlock { 
    Set-Location $using:PWD
    mvn spring-boot:run -pl service-registry 
}

Write-Host ">>> Waiting for Eureka..." -ForegroundColor Yellow
Start-Sleep -Seconds 20

# 3. Start API Gateway (8080)
Write-Host ">>> Starting API Gateway (8080)..." -ForegroundColor Cyan
Start-Job -Name "api-gateway" -ScriptBlock { 
    Set-Location $using:PWD
    mvn spring-boot:run -pl api-gateway 
}

# 4. Start Admin Server (8089)
Write-Host ">>> Starting Admin Server (8089)..." -ForegroundColor Cyan
Start-Job -Name "admin-server" -ScriptBlock { 
    Set-Location $using:PWD
    mvn spring-boot:run -pl admin-server 
}

Write-Host ">>> Waiting for Gateway and Admin..." -ForegroundColor Yellow
Start-Sleep -Seconds 15

# 5. Start Core Business Services
Write-Host ">>> Starting Business Microservices..." -ForegroundColor Cyan
$services = @("auth-service", "room-service", "message-service", "notification-service", "websocket-service", "presence-service", "payment-service", "media-service")

foreach ($service in $services) {
    Write-Host "   -> Starting $service..." -ForegroundColor Cyan
    Start-Job -Name $service -ScriptBlock { 
        Set-Location $using:PWD
        mvn spring-boot:run -pl $using:service 
    }
    Start-Sleep -Seconds 5
}

Write-Host "`n[OK] All services have been signaled to start!" -ForegroundColor Green
Write-Host "Check http://localhost:8761 for registration status." -ForegroundColor Gray
Write-Host "Check http://localhost:8089 for service health dashboard." -ForegroundColor Gray
Write-Host "`nUse 'Get-Job' to see running processes and 'Stop-Job *' to stop all." -ForegroundColor Yellow
