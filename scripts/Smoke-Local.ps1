# Runs against an isolated temporary Compose project, never the normal user_db volume.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$workspace = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $workspace
$jar = Join-Path $workspace 'target/user-service-0.1.0-SNAPSHOT.jar'
if (!(Test-Path -LiteralPath $jar)) { throw 'Build the JAR with mvnw.cmd verify first' }
$probe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 8081)
try { $probe.Start() } finally { $probe.Stop() }
$portProbe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$portProbe.Start()
$dbPort = $portProbe.LocalEndpoint.Port
$portProbe.Stop()
$taskId = [Guid]::NewGuid().ToString('N')
$project = 'user-foundation-smoke-' + $taskId
$taskDirectory = Join-Path $workspace ('.tools/smoke-' + $taskId)
New-Item -ItemType Directory -Path $taskDirectory -Force | Out-Null
function New-RandomHex {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [BitConverter]::ToString($bytes).Replace('-', '')
}
$dbPassword = New-RandomHex
$callerKey = New-RandomHex
$configPath = Join-Path $taskDirectory '.env'
@"
DB_URL=jdbc:postgresql://localhost:$dbPort/user_db
DB_PORT=$dbPort
DB_USERNAME=user_service
DB_PASSWORD=$dbPassword
INTERNAL_GATEWAY_KEY=$callerKey
SERVER_PORT=8081
"@ | Set-Content -LiteralPath $configPath -Encoding ASCII
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(5)
$app = $null
function Compose {
    & docker compose --env-file $configPath -p $project @args
    if ($LASTEXITCODE -ne 0) { throw 'Compose command failed' }
}
function Send-Api([string]$Path, [bool]$WithKey = $true) {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Get, 'http://localhost:8081' + $Path)
    try {
        if ($WithKey) { $request.Headers.Add('X-Service-Key', $callerKey) }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        try {
            return @{
                Status = [int]$response.StatusCode
                Body = ($response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json)
                Cache = $response.Headers.CacheControl.ToString()
            }
        } finally { $response.Dispose() }
    } finally { $request.Dispose() }
}
function Check([bool]$Condition, [string]$Message) {
    if (!$Condition) { throw $Message }
}
try {
    Compose up -d --wait user-db
    $java = (Get-Command java).Source
    $app = Start-Process -FilePath $java -ArgumentList @('-jar', $jar, '--spring.profiles.active=local',
        "--spring.config.import=file:${configPath}[.properties]", '--debug=false') -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $taskDirectory 'app.log') `
        -RedirectStandardError (Join-Path $taskDirectory 'app-error.log')
    $ready = $false
    for ($attempt = 0; $attempt -lt 45; $attempt++) {
        if ($app.HasExited) { throw 'Application exited; inspect smoke app logs in .tools' }
        try {
            $health = Send-Api '/actuator/health' $false
            if ($health.Status -eq 200 -and $health.Body.status -eq 'UP') { $ready = $true; break }
        } catch { }
        Start-Sleep -Seconds 1
    }
    Check $ready 'Local application did not become healthy on port 8081'
    $id = [Guid]::NewGuid().ToString()
    $path = '/internal/v1/accounts/' + $id + '/access-state'
    $unauthorized = Send-Api $path $false
    Check ($unauthorized.Status -eq 401 -and $unauthorized.Body.code -eq 'UNAUTHORIZED') 'Expected service-key 401'
    $absent = Send-Api $path
    Check ($absent.Status -eq 404 -and $absent.Body.code -eq 'ACCOUNT_NOT_FOUND') 'Expected missing account 404'
    Compose exec -T user-db psql -U user_service -d user_db -v ON_ERROR_STOP=1 -c "INSERT INTO accounts(account_id,username,email,password_hash,role,status,email_verified_at) VALUES ('$id','smoke_admin','smoke@example.test','test-only-hash','ADMIN','ACTIVE',now())"
    $active = Send-Api $path
    Check ($active.Status -eq 200 -and $active.Body.accessAllowed -eq $true -and $active.Cache -eq 'no-store') 'Expected ACTIVE no-store response'
    Compose exec -T user-db psql -U user_service -d user_db -v ON_ERROR_STOP=1 -c "UPDATE accounts SET status='LOCKED',admin_lock=true WHERE account_id='$id'"
    $locked = Send-Api $path
    Check ($locked.Status -eq 200 -and $locked.Body.accessAllowed -eq $false) 'Committed lock must take effect immediately'
    Compose stop user-db
    $unavailable = Send-Api $path
    Check ($unavailable.Status -eq 503 -and $unavailable.Body.code -eq 'ACCESS_STATE_UNAVAILABLE') 'Expected real DB outage 503'
    $readiness = Send-Api '/actuator/health/readiness' $false
    Check ($readiness.Status -eq 503 -and $readiness.Body.status -eq 'DOWN') 'DB outage must make readiness DOWN'
    Write-Output 'SMOKE PASS: local profile, port 8081, health, 401, 404, ACTIVE, committed LOCKED, no-store, real DB outage 503, readiness DOWN'
} finally {
    if ($app -and !$app.HasExited) { Stop-Process -Id $app.Id -Force }
    $client.Dispose()
    # Only this randomly named test project and its volume are removed.
    & docker compose --env-file $configPath -p $project down -v
}
