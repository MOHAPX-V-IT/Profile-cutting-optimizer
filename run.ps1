[CmdletBinding()]
param(
    [switch]$Rebuild,
    [switch]$Background,
    [switch]$Demo,
    [ValidateRange(1024,65535)]
    [int]$Port = 8091
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$optToolchain = Join-Path $env:LOCALAPPDATA 'HodToolchain'
$optJdk = Get-ChildItem -LiteralPath $optToolchain -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($optJdk) {
    $env:JAVA_HOME = $optJdk.FullName
    $optJava = Join-Path $optJdk.FullName 'bin/java.exe'
} else {
    $optJava = (Get-Command java.exe -ErrorAction Stop).Source
}

$optJar = Join-Path $PSScriptRoot 'target/optimizator.jar'
if ($Rebuild -or -not (Test-Path -LiteralPath $optJar)) {
    $optBundledMaven = Get-ChildItem -LiteralPath $optToolchain -Directory -Filter 'apache-maven-*' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $optMaven = if ($optBundledMaven) { Join-Path $optBundledMaven.FullName 'bin/mvn.cmd' }
        else { (Get-Command mvn.cmd -ErrorAction Stop).Source }
    & $optMaven -q package
    if ($LASTEXITCODE -ne 0) { throw 'Maven package failed.' }
}

$optAddress = "http://127.0.0.1:$Port"
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    throw "Port $Port is already in use. Check $optAddress/health before starting another process."
}
$optJvmOptions = @("--server.port=$Port")
if ($Demo) {
    $optJvmOptions += '--spring.datasource.url=jdbc:h2:file:./data/workshop-demo;DB_CLOSE_ON_EXIT=FALSE'
    $optJvmOptions += '--cut.demo=true'
}
if (-not $Background) {
    Write-Host $optAddress
    & $optJava -jar $optJar @optJvmOptions
    exit $LASTEXITCODE
}

$optRun = Join-Path $PSScriptRoot '.run'
New-Item -ItemType Directory -Path $optRun -Force | Out-Null
$optStamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$optRuntime = Join-Path $optRun "app-$optStamp.jar"
$optOutput = Join-Path $optRun "app-$optStamp.log"
$optErrors = Join-Path $optRun "app-$optStamp.err.log"
Copy-Item -LiteralPath $optJar -Destination $optRuntime
$optStart = @{
    FilePath = $optJava
    ArgumentList = ('-jar "{0}" {1}' -f $optRuntime, ($optJvmOptions -join ' '))
    WorkingDirectory = $PSScriptRoot
    WindowStyle = 'Hidden'
    RedirectStandardOutput = $optOutput
    RedirectStandardError = $optErrors
    PassThru = $true
}
$optProcess = Start-Process @optStart
$optPidName = if ($Port -eq 8091) { 'runtime.pid' } else { "runtime-$Port.pid" }
for ($optAttempt = 0; $optAttempt -lt 40; $optAttempt++) {
    if ($optProcess.HasExited) { throw "Application exited. See $optOutput and $optErrors." }
    try {
        $optHealth = Invoke-RestMethod -Uri "$optAddress/health" -TimeoutSec 1 -ErrorAction Stop
        if ($optHealth.status -eq 'ok') {
            Set-Content -LiteralPath (Join-Path $optRun $optPidName) -Value $optProcess.Id -Encoding ascii
            Write-Host "$optAddress (PID $($optProcess.Id))"
            return
        }
    } catch {
        Start-Sleep -Milliseconds 500
    }
}
throw "The application did not become ready. See $optOutput and $optErrors."
