param (
    [Parameter(Mandatory = $false)]
    [string]$sonarToken = "",

    [Parameter(Mandatory = $false)]
    [int]$coverageThreshold = 80
)

$ErrorActionPreference = "Stop"

$services = @(
    "service-registry",
    "api-gateway",
    "auth-service",
    "room-service",
    "message-service",
    "media-service",
    "presence-service",
    "notification-service",
    "websocket-service",
    "payment-service",
    "admin-server"
)

function Invoke-Maven {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [Parameter(Mandatory = $true)]
        [string]$StepName
    )

    & mvn @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$StepName failed with exit code $LASTEXITCODE"
    }
}

function Get-JacocoLineCoverage {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ReportPath
    )

    if (-not (Test-Path $ReportPath)) {
        throw "JaCoCo report not found: $ReportPath"
    }

    [xml]$report = Get-Content $ReportPath
    $lineCounter = $report.report.counter | Where-Object { $_.type -eq "LINE" }
    if ($null -eq $lineCounter) {
        return 100.0
    }

    $covered = [int]$lineCounter.covered
    $missed = [int]$lineCounter.missed
    $total = $covered + $missed
    if ($total -eq 0) {
        return 100.0
    }

    return [math]::Round(($covered / $total) * 100, 2)
}

function Assert-Coverage {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [Parameter(Mandatory = $true)]
        [string]$ReportPath
    )

    $coverage = Get-JacocoLineCoverage $ReportPath
    if ($coverage -lt $coverageThreshold) {
        throw "$Name line coverage is $coverage%, below the required $coverageThreshold%."
    }

    [pscustomobject]@{
        Project = $Name
        Coverage = "$coverage%"
    }
}

Write-Host "Starting SonarQube infrastructure..." -ForegroundColor Cyan
docker-compose up -d sonarqube sonar-db
if ($LASTEXITCODE -ne 0) {
    throw "Failed to start SonarQube containers. Make sure Docker Desktop is running."
}

Write-Host "Waiting for SonarQube to become healthy..." -ForegroundColor Yellow
$maxRetries = 40
$isUp = $false

for ($attempt = 1; $attempt -le $maxRetries; $attempt++) {
    try {
        $response = Invoke-WebRequest -Uri "http://localhost:9000/api/system/status" -ErrorAction Stop
        $status = ($response.Content | ConvertFrom-Json).status
        if ($status -eq "UP") {
            $isUp = $true
            break
        }
        Write-Host "SonarQube status is $status ($attempt/$maxRetries)" -ForegroundColor Gray
    } catch {
        Write-Host "SonarQube is not ready yet ($attempt/$maxRetries)" -ForegroundColor Gray
    }

    Start-Sleep -Seconds 10
}

if (-not $isUp) {
    throw "SonarQube did not become ready in time."
}

Write-Host "SonarQube is ready." -ForegroundColor Green
Write-Host "Running Maven verification with $coverageThreshold% per-service coverage gates..." -ForegroundColor Cyan
Invoke-Maven `
    -Arguments @("clean", "verify", "-Djacoco.minimum.coverage=$($coverageThreshold / 100)") `
    -StepName "Maven verification"

Write-Host "Checking generated JaCoCo coverage reports..." -ForegroundColor Cyan
$coverageResults = @()
foreach ($service in $services) {
    $coverageResults += Assert-Coverage `
        -Name $service `
        -ReportPath (Join-Path $service "target/site/jacoco/jacoco.xml")
}
$coverageResults += Assert-Coverage `
    -Name "overall" `
    -ReportPath "code-coverage-report/target/site/jacoco-aggregate/jacoco.xml"

$coverageResults | Format-Table -AutoSize

$tokenArg = @()
if (-not [string]::IsNullOrWhiteSpace($sonarToken)) {
    $tokenArg += "-Dsonar.token=$sonarToken"
}

Write-Host "Publishing overall ConnectHub analysis..." -ForegroundColor Cyan
Invoke-Maven `
    -Arguments (@(
        "sonar:sonar",
        "-DskipTests",
        "-Dsonar.projectKey=connecthub-overall",
        "-Dsonar.projectName=ConnectHub Overall",
        "-Dsonar.coverage.jacoco.xmlReportPaths=code-coverage-report/target/site/jacoco-aggregate/jacoco.xml"
    ) + $tokenArg) `
    -StepName "Overall SonarQube analysis"

Write-Host "Publishing one SonarQube project per service..." -ForegroundColor Cyan
foreach ($service in $services) {
    if (-not (Test-Path $service)) {
        throw "Directory $service not found."
    }

    Write-Host "Analyzing $service..." -ForegroundColor Cyan
    $serviceSonarArgs = @(
        "sonar:sonar",
        "-DskipTests",
        "-Dsonar.projectKey=connecthub-$service",
        "-Dsonar.projectName=ConnectHub $service",
        "-Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml"
    )

    if ($service -eq "admin-server") {
        $serviceSonarArgs += "-Dsonar.coverage.exclusions=**/*Application.java"
    }

    Push-Location $service
    try {
        Invoke-Maven `
            -Arguments ($serviceSonarArgs + $tokenArg) `
            -StepName "$service SonarQube analysis"
    } finally {
        Pop-Location
    }
}

Write-Host "All SonarQube analyses completed. Open http://localhost:9000 to view the projects." -ForegroundColor Green
