param([string]$Image = 'user-service:phase7-local')

# Windows PowerShell 5.1 / PowerShell 7. Creates only randomly named Docker fixtures.
# Does not load the workspace .env, touch normal Compose volumes, or contact real SMTP.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
Add-Type -AssemblyName System.Drawing
$workspace = Split-Path -Parent $PSScriptRoot
$taskId = [Guid]::NewGuid().ToString('N')
$prefix = 'user-phase7-' + $taskId
$work = Join-Path $workspace ('.tools/deployment-' + $taskId)
New-Item -ItemType Directory -Path $work -Force | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
$network = $prefix + '-net'
$db = $prefix + '-db'
$restoredDb = $prefix + '-restored-db'
$mail = $prefix + '-mail'
$app = $prefix + '-app'
$restoredApp = $prefix + '-restored-app'
$containers = @($app, $restoredApp, $db, $restoredDb, $mail)
$volumes = @(($prefix + '-db-data'), ($prefix + '-media'), ($prefix + '-restored-db-data'), ($prefix + '-restored-media'))
$steps = [Collections.Generic.List[string]]::new()
$client = [Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(12)

function Docker {
    $dockerArguments = @($args)
    $output = & docker.exe @dockerArguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ('Docker command failed: ' + $dockerArguments[0] + '; fixture: ' + $prefix) }
    return ($output -join "`n")
}
function Secret {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [BitConverter]::ToString($bytes).Replace('-', '')
}
function Check([bool]$Condition, [string]$Name) {
    if (!$Condition) { throw ('Deployment check failed: ' + $Name) }
    $steps.Add($Name)
    Write-Output ('PASS: ' + $Name)
}
function Wait-Until([scriptblock]$Condition, [string]$Name) {
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        try { if (& $Condition) { return } } catch { }
        Start-Sleep -Seconds 1
    }
    throw ('Timed out: ' + $Name + '; inspect private logs in ' + $work)
}
function Base-Url([string]$Container, [string]$Port) {
    $mapping = Docker @('port', $Container, $Port)
    return 'http://' + $mapping.Trim()
}
function Request([string]$Method, [string]$Path, $Body = $null, [string]$Token = '', [string]$Key = '') {
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), $script:base + $Path)
    try {
        if ($Token) { $request.Headers.Add('Authorization', 'Bearer ' + $Token) }
        if ($Key) { $request.Headers.Add('X-Service-Key', $Key) }
        if ($null -ne $Body) {
            $request.Content = [Net.Http.StringContent]::new(($Body | ConvertTo-Json -Depth 10 -Compress), [Text.Encoding]::UTF8, 'application/json')
        }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        try {
            $raw = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            $parsed = if ($raw) { $raw | ConvertFrom-Json } else { $null }
            return @{ Status = [int]$response.StatusCode; Body = $parsed; Cache = [string]$response.Headers.CacheControl }
        } finally { $response.Dispose() }
    } finally { $request.Dispose() }
}
function Otp([string]$Email) {
    $messages = Invoke-RestMethod -Uri ($script:mailUrl + '/api/v1/messages?limit=100') -TimeoutSec 5
    $message = $messages.messages | Where-Object { @($_.To | Where-Object { $_.Address -eq $Email }).Count -gt 0 } | Select-Object -First 1
    if (!$message) { throw 'Expected fixture OTP in isolated Mailpit' }
    $detail = Invoke-RestMethod -Uri ($script:mailUrl + '/api/v1/message/' + $message.ID) -TimeoutSec 5
    $match = [regex]::Match($detail.Text, 'verification code is ([0-9]{6})')
    if (!$match.Success) { throw 'Fixture OTP missing' }
    return $match.Groups[1].Value
}
function Ready {
    $deadline = [DateTime]::UtcNow.AddSeconds(180)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ((Docker @('inspect', '--format', '{{.State.Running}}', $script:currentApp)).Trim() -ne 'true') {
            throw ('Application exited before ready; inspect private fixture logs: ' + $work)
        }
        try { if ((Request 'GET' '/actuator/health/readiness').Status -eq 200) { return } } catch { }
        Start-Sleep -Seconds 1
    }
    throw ('Application did not become ready; inspect private fixture logs: ' + $work)
}
function Start-App([string]$Name, [string]$Database, [string]$Media, [bool]$EmailDelivery = $true) {
    $envPath = Join-Path $work ($Name + '.env')
    $settingsForApp = $script:appSettings
    if (!$EmailDelivery) { $settingsForApp = $settingsForApp.Replace('OUTBOX_EMAIL_ENABLED=true', 'OUTBOX_EMAIL_ENABLED=false') }
    [IO.File]::WriteAllText($envPath, $settingsForApp + "`nDB_URL=jdbc:postgresql://${Database}:5432/user_db`n", $utf8)
    Docker @('run', '-d', '--name', $Name, '--network', $network, '--env-file', $envPath,
        '--mount', ('type=bind,source=' + (Join-Path $work 'secrets/local-auth') + ',target=/run/user-secrets,readonly'),
        '--mount', ('type=volume,source=' + $Media + ',target=/var/lib/user-media'),
        '-p', '127.0.0.1::8081', $Image) | Out-Null
    $script:base = Base-Url $Name '8081/tcp'
    $script:currentApp = $Name
    Ready
}
function Download([string]$Path, [string]$Token) {
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Get, $script:base + $Path)
    try {
        $request.Headers.Add('Authorization', 'Bearer ' + $Token)
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        try {
            if ([int]$response.StatusCode -ne 200) { throw 'Avatar download failed' }
            return [Convert]::ToBase64String($response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult())
        } finally { $response.Dispose() }
    } finally { $request.Dispose() }
}

try {
    Docker @('image', 'inspect', $Image, '--format', '{{.Id}}') | Set-Content -LiteralPath (Join-Path $work 'image-id.txt')
    # Existing key generator is safe here: its cwd and .env are this new private fixture directory.
    Copy-Item -LiteralPath (Join-Path $workspace '.env.example') -Destination (Join-Path $work '.env')
    Push-Location -LiteralPath $work
    try {
        & java (Join-Path $workspace 'scripts/GenerateLocalAuth.java') | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Fixture key generation failed' }
    } finally { Pop-Location }
    $generated = @{}
    Get-Content -LiteralPath (Join-Path $work '.env') | ForEach-Object {
        if ($_ -match '^([^#=]+)=(.*)$') { $generated[$matches[1]] = $matches[2] }
    }
    $dbPassword = Secret
    $password = 'Fixture9!' + (Secret).Substring(0, 24)
    $gatewayKey = Secret
    $paymentKey = Secret
    $dbEnv = Join-Path $work 'db.env'
    [IO.File]::WriteAllText($dbEnv, "POSTGRES_USER=user_service`nPOSTGRES_DB=user_db`nPOSTGRES_PASSWORD=$dbPassword`n", $utf8)
    $script:appSettings = @"
DB_USERNAME=user_service
DB_PASSWORD=$dbPassword
OTP_HMAC_SECRET=$($generated['OTP_HMAC_SECRET'])
JWT_PRIVATE_KEY_PATH=/run/user-secrets/private.pem
JWT_PUBLIC_KEY_PATH=/run/user-secrets/public.pem
JWT_KEY_ID=$($generated['JWT_KEY_ID'])
MAIL_HOST=$mail
MAIL_PORT=1025
MAIL_FROM=phase7@example.test
MAIL_SMTP_AUTH=false
MAIL_STARTTLS=false
SUPPORT_EMAIL=support@example.test
INTERNAL_GATEWAY_KEY=$gatewayKey
INTERNAL_PAYMENT_KEY=$paymentKey
ADMIN_SEED_ENABLED=true
ADMIN_1_USERNAME=p7_admin_one
ADMIN_1_EMAIL=admin.one@example.test
ADMIN_1_PASSWORD=${password}A
ADMIN_2_USERNAME=p7_admin_two
ADMIN_2_EMAIL=admin.two@example.test
ADMIN_2_PASSWORD=${password}B
OUTBOX_KAFKA_ENABLED=false
OUTBOX_EMAIL_ENABLED=true
PROFILE_BOOTSTRAP_ENABLED=false
REPUTATION_CONSUMER_ENABLED=false
"@
    Docker @('network', 'create', $network) | Out-Null
    foreach ($volume in $volumes) { Docker @('volume', 'create', $volume) | Out-Null }
    Docker @('run', '-d', '--name', $db, '--network', $network, '--env-file', $dbEnv,
        '--mount', ('type=volume,source=' + $volumes[0] + ',target=/var/lib/postgresql/data'), 'postgres:17-alpine') | Out-Null
    Docker @('run', '-d', '--name', $mail, '--network', $network, '-p', '127.0.0.1::8025', 'axllent/mailpit:v1.27.10') | Out-Null
    Wait-Until { (Docker @('exec', $db, 'pg_isready', '-U', 'user_service', '-d', 'user_db')) -match 'accepting connections' } 'PostgreSQL'
    $script:mailUrl = Base-Url $mail '8025/tcp'
    Start-App $app $db $volumes[1]
    Check ((Docker @('exec', $app, 'id', '-u')).Trim() -ne '0') 'image runs as non-root'
    Check ((Request 'GET' '/actuator/health/liveness').Status -eq 200) 'image liveness/readiness UP'
    $migrationCount = Docker @('exec', $db, 'psql', '-U', 'user_service', '-d', 'user_db', '-Atc', 'SELECT count(*) FROM flyway_schema_history WHERE success')
    Check ($migrationCount.Trim() -eq '4') 'Flyway V1-V4 applied on fresh database'
    $admins = @()
    foreach ($n in @('one', 'two')) {
        $email = 'admin.' + $n + '@example.test'
        Check ((Request 'POST' '/api/v1/auth/verify-email' @{ email = $email; otp = (Otp $email) }).Status -eq 200) ('seed Admin ' + $n + ' verified via isolated SMTP')
        $adminPassword = $password + $(if ($n -eq 'one') { 'A' } else { 'B' })
        $login = Request 'POST' '/api/v1/auth/login' @{ username = ('p7_admin_' + $n); password = $adminPassword }
        Check ($login.Status -eq 200 -and $login.Body.role -eq 'ADMIN') ('seed Admin ' + $n + ' login')
        $admins += $login.Body
    }
    $registered = Request 'POST' '/api/v1/auth/register' @{
        username = 'p7_farmer'; email = 'farmer@example.test'; password = $password; role = 'FARMER'; profile = @{ fullName = 'Deployment Farmer' }
    }
    Check ($registered.Status -eq 201 -and $registered.Body.verificationEmailStatus -eq 'SENT') 'register sends OTP through container SMTP'
    Check ((Request 'POST' '/api/v1/auth/verify-email' @{ email = 'farmer@example.test'; otp = (Otp 'farmer@example.test') }).Status -eq 200) 'Farmer verifies email'
    $session = (Request 'POST' '/api/v1/auth/login' @{ username = 'p7_farmer'; password = $password }).Body
    $token = $session.accessToken
    Check (![string]::IsNullOrEmpty($token)) 'Farmer login with image RSA key pair'
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Post, $script:base + '/api/v1/users/me/avatar')
    try {
        $request.Headers.Add('Authorization', 'Bearer ' + $token)
        $request.Content = [Net.Http.MultipartFormDataContent]::new()
        $bitmap = [Drawing.Bitmap]::new(2, 2)
        $stream = [IO.MemoryStream]::new()
        try { $bitmap.Save($stream, [Drawing.Imaging.ImageFormat]::Png); $bytes = $stream.ToArray() }
        finally { $bitmap.Dispose(); $stream.Dispose() }
        $part = [Net.Http.ByteArrayContent]::new($bytes)
        $part.Headers.ContentType = [Net.Http.Headers.MediaTypeHeaderValue]::new('image/png')
        $request.Content.Add($part, 'file', 'fixture.png')
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        try {
            Check ([int]$response.StatusCode -eq 201) 'image accepts real multipart PNG avatar'
            $avatar = ($response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json).avatarUrl
        } finally { $response.Dispose() }
    } finally { $request.Dispose() }
    $originalBytes = Download $avatar $token
    $internal = '/internal/v1/accounts/' + $session.accountId + '/access-state'
    Check ((Request 'GET' $internal).Status -eq 401) 'internal access-state requires service key'
    Check ((Request 'GET' $internal $null '' $paymentKey).Body.accessAllowed -eq $true) 'independent Payment key reads access-state'
    Check ((Request 'GET' '/api/v1/users/me' $null '' $gatewayKey).Status -eq 401) 'service key cannot replace user bearer token'
    $statusPath = '/api/v1/admin/accounts/' + $session.accountId + '/status'
    Check ((Request 'PATCH' $statusPath @{ status = 'LOCKED'; reason = 'DEPLOYMENT_CHECK' } $admins[0].accessToken).Status -eq 200) 'Admin locks Farmer in image'
    Check ((Request 'GET' '/api/v1/users/me' $null $token).Status -eq 403) 'old JWT blocked after committed Admin lock'
    Check ((Request 'POST' '/api/v1/auth/refresh' @{ refreshToken = $session.refreshToken }).Status -eq 403) 'locked account cannot refresh'
    $state = Request 'GET' $internal $null '' $gatewayKey
    Check ($state.Status -eq 200 -and !$state.Body.accessAllowed -and $state.Cache -eq 'no-store') 'Gateway key sees committed lock with no-store'
    Check ((Request 'PATCH' $statusPath @{ status = 'ACTIVE'; reason = 'DEPLOYMENT_REVIEW' } $admins[1].accessToken).Status -eq 200) 'second verified Admin unlocks Farmer'
    Check ((Request 'POST' '/api/v1/auth/refresh' @{ refreshToken = $session.refreshToken }).Status -eq 401) 'unlock does not restore revoked refresh token'
    Docker @('restart', '--time', '10', $app) | Out-Null
    # Docker may allocate a different host port when restarting a :0 publication.
    $script:base = Base-Url $app '8081/tcp'
    Ready
    Check ((Download $avatar $token) -ceq $originalBytes) 'avatar bytes persist across image container restart'
    Check ((Request 'GET' '/api/v1/users/me' $null $token).Body.profile.avatarUrl -eq $avatar) 'profile avatar metadata persists across restart'
    Docker @('stop', '--time', '10', $db) | Out-Null
    Check ((Request 'GET' $internal $null '' $gatewayKey).Status -eq 503) 'database outage fails access-state closed'
    Check ((Request 'GET' '/actuator/health/readiness').Status -eq 503) 'database outage makes image readiness DOWN'
    Docker @('start', $db) | Out-Null
    Ready

    # All writers in this fixture are this single app; stop it before taking the paired snapshot.
    Docker @('stop', '--time', '10', $app) | Out-Null
    Docker @('exec', $db, 'pg_dump', '-U', 'user_service', '-d', 'user_db', '-Fc', '-f', '/tmp/user.dump') | Out-Null
    Docker @('cp', ($db + ':/tmp/user.dump'), (Join-Path $work 'user.dump')) | Out-Null
    Docker @('run', '--rm', '--network', 'none', '--mount', ('type=volume,source=' + $volumes[1] + ',target=/source,readonly'),
        '--mount', ('type=bind,source=' + $work + ',target=/backup'), 'postgres:17-alpine', 'tar', '-C', '/source', '-cf', '/backup/media.tar', '.') | Out-Null
    Check ((Get-Item -LiteralPath (Join-Path $work 'user.dump')).Length -gt 0 -and (Get-Item -LiteralPath (Join-Path $work 'media.tar')).Length -gt 0) 'paired PostgreSQL/media backup after quiescing writers'
    Docker @('run', '-d', '--name', $restoredDb, '--network', $network, '--env-file', $dbEnv,
        '--mount', ('type=volume,source=' + $volumes[2] + ',target=/var/lib/postgresql/data'), 'postgres:17-alpine') | Out-Null
    Wait-Until { (Docker @('exec', $restoredDb, 'pg_isready', '-U', 'user_service', '-d', 'user_db')) -match 'accepting connections' } 'restore PostgreSQL'
    Docker @('cp', (Join-Path $work 'user.dump'), ($restoredDb + ':/tmp/user.dump')) | Out-Null
    Docker @('exec', $restoredDb, 'pg_restore', '-U', 'user_service', '-d', 'user_db', '--no-owner', '--exit-on-error', '/tmp/user.dump') | Out-Null
    Docker @('run', '--rm', '--network', 'none', '--mount', ('type=volume,source=' + $volumes[3] + ',target=/restore'),
        '--mount', ('type=bind,source=' + $work + ',target=/backup,readonly'), 'postgres:17-alpine', 'tar', '-C', '/restore', '-xf', '/backup/media.tar') | Out-Null
    Start-App $restoredApp $restoredDb $volumes[3] $false
    Check ((Download $avatar $token) -ceq $originalBytes) 'restored DB/media preserve avatar identity and exact bytes'
    Check ((Request 'GET' '/api/v1/users/me' $null $token).Body.profile.avatarUrl -eq $avatar) 'restored profile references restored media'
    $activeAdmins = Docker @('exec', $restoredDb, 'psql', '-U', 'user_service', '-d', 'user_db', '-Atc', "SELECT count(*) FROM accounts WHERE role='ADMIN' AND status='ACTIVE' AND email_verified_at IS NOT NULL")
    Check ($activeAdmins.Trim() -eq '2') 'restore preserves two verified Admins without duplicate seeding'
    Check ((Request 'POST' '/api/v1/auth/refresh' @{ refreshToken = $session.refreshToken }).Status -eq 401) 'restore preserves revoked refresh token state'
    Check ((Request 'GET' $internal $null '' $gatewayKey).Body.accessAllowed -eq $true) 'restored access-state uses preserved service key'
    $report = @('# Isolated deployment smoke', '', ('UTC: ' + [DateTime]::UtcNow.ToString('o')), ('Image: ' + $Image),
        ('Image ID: ' + (Get-Content -LiteralPath (Join-Path $work 'image-id.txt'))), '', '| Check | Result |', '| --- | --- |')
    foreach ($step in $steps) { $report += '| ' + $step + ' | PASS |' }
    $report += @('', 'Only synthetic fixture identities and isolated Mailpit were used. This does not verify real Gateway, Payment, Trading, Notification, external SMTP or production deployment.',
        'Reputation listener remained disabled. Private fixture env, keys, dump, media archive and logs are in this ignored directory; never commit them.')
    [IO.File]::WriteAllText((Join-Path $work 'report.md'), ($report -join "`n") + "`n", $utf8)
    Write-Output ('DEPLOYMENT PASS: ' + $steps.Count + ' checks; private report: ' + (Join-Path $work 'report.md'))
} finally {
    $client.Dispose()
    $ErrorActionPreference = 'Continue'
    # Names are generated here, not caller-supplied; remove only this exact fixture's resources.
    foreach ($name in $containers) {
        if (!$name.StartsWith($prefix + '-')) { throw 'Unsafe fixture cleanup target' }
        & docker.exe logs $name *> (Join-Path $work ($name + '.log'))
        & docker.exe rm -f $name *> $null
    }
    foreach ($volume in $volumes) {
        if (!$volume.StartsWith($prefix + '-')) { throw 'Unsafe fixture volume cleanup target' }
        & docker.exe volume rm $volume *> $null
    }
    & docker.exe network rm $network *> $null
}
