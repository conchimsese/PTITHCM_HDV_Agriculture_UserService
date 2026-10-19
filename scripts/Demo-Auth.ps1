param([switch]$IncludeAdmins)

# Local live demo. Creates one Farmer in the configured local DB; does not delete users.
# Admin verification is opt-in and uses only OTP received by local Mailpit.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
$directory = Join-Path (Get-Location) '.tools/demo-auth'
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(10)
$results = [Collections.Generic.List[object]]::new()
function Request([string]$Method, [string]$Path, $Body = $null, [string]$Access = '') {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), 'http://localhost:8081' + $Path)
    try {
        if ($null -ne $Body) {
            $request.Content = [System.Net.Http.StringContent]::new(($Body | ConvertTo-Json -Depth 8 -Compress), [Text.Encoding]::UTF8, 'application/json')
        }
        if ($Access) { $request.Headers.Add('Authorization', 'Bearer ' + $Access) }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        try {
            $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            return @{ Status = [int]$response.StatusCode; Body = $(if ($content) { $content | ConvertFrom-Json } else { $null }) }
        } finally { $response.Dispose() }
    } finally { $request.Dispose() }
}
function Step([string]$Name, $Response, [int]$Expected) {
    $ok = $Response.Status -eq $Expected
    $results.Add([pscustomobject]@{ Step = $Name; HTTP = $Response.Status; Result = $(if ($ok) { 'PASS' } else { 'FAIL' }) })
    if (!$ok) { throw ($Name + ': expected HTTP ' + $Expected + ', got ' + $Response.Status + '. No credential/token details printed.') }
}
function Otp([string]$Email) {
    $messages = Invoke-RestMethod -Uri 'http://localhost:8025/api/v1/messages?limit=100' -TimeoutSec 5
    $message = $messages.messages | Where-Object { @($_.To | Where-Object { $_.Address -eq $Email }).Count -gt 0 } | Select-Object -First 1
    if (!$message) { throw 'No matching OTP in local Mailpit. No external mailbox is accessed.' }
    $detail = Invoke-RestMethod -Uri ('http://localhost:8025/api/v1/message/' + $message.ID) -TimeoutSec 5
    $match = [regex]::Match($detail.Text, 'verification code is ([0-9]{6})')
    if (!$match.Success) { throw 'Mailpit message did not contain a verification code' }
    return $match.Groups[1].Value
}
try {
    Step 'Service health' (Request 'GET' '/actuator/health') 200
    if ($IncludeAdmins) {
        $settings = @{}
        Get-Content .env | ForEach-Object { if ($_ -match '^([^#=]+)=(.*)$') { $settings[$matches[1]] = $matches[2] } }
        foreach ($n in 1, 2) {
            $username = $settings['ADMIN_' + $n + '_USERNAME']
            $email = $settings['ADMIN_' + $n + '_EMAIL']
            $password = $settings['ADMIN_' + $n + '_PASSWORD']
            if (!$username -or !$email -or !$password) { throw 'Configure Admin identity/password in .env first' }
            $before = Request 'POST' '/api/v1/auth/login' @{ username = $username; password = $password }
            if ($before.Status -eq 403 -and $before.Body.code -eq 'ACCOUNT_NOT_ACTIVE') {
                Step ('Admin ' + $n + ': login before OTP rejected') $before 403
                Step ('Admin ' + $n + ': verify OTP from Mailpit') (Request 'POST' '/api/v1/auth/verify-email' @{ email = $email; otp = (Otp $email) }) 200
                $session = Request 'POST' '/api/v1/auth/login' @{ username = $username; password = $password }
            } else { $session = $before }
            Step ('Admin ' + $n + ': login') $session 200
            if ($session.Body.role -ne 'ADMIN') { throw 'Seeded identity did not return ADMIN role' }
            Step ('Admin ' + $n + ': logout') (Request 'POST' '/api/v1/auth/logout' @{ refreshToken = $session.Body.refreshToken } $session.Body.accessToken) 204
        }
    }
    $suffix = [Guid]::NewGuid().ToString('N').Substring(0, 12)
    $name = 'demo_farmer_' + $suffix
    $email = 'demo.farmer.' + $suffix + '@example.test'
    $bytes = New-Object byte[] 16
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $password = 'Demo9!' + [BitConverter]::ToString($bytes).Replace('-', '')
    $registered = Request 'POST' '/api/v1/auth/register' @{
        username = $name; email = $email; password = $password; role = 'FARMER'
        profile = @{ fullName = 'Farmer local demo' }
    }
    Step 'Farmer: register UNVERIFIED' $registered 201
    if ($registered.Body.status -ne 'UNVERIFIED' -or $registered.Body.verificationEmailStatus -ne 'SENT') {
        throw 'Registration did not return UNVERIFIED / email SENT'
    }
    # Private local credential file only. Access/refresh tokens and OTP are never persisted here.
    @(('DEMO_USERNAME=' + $name), ('DEMO_EMAIL=' + $email), ('DEMO_PASSWORD=' + $password)) |
        Set-Content -LiteralPath (Join-Path $directory 'account.env') -Encoding UTF8
    Step 'Farmer: login before OTP rejected' (Request 'POST' '/api/v1/auth/login' @{ username = $name; password = $password }) 403
    Step 'Farmer: resend cooldown enforced' (Request 'POST' '/api/v1/auth/resend-verification' @{ email = $email }) 429
    Step 'Farmer: verify OTP from Mailpit' (Request 'POST' '/api/v1/auth/verify-email' @{ email = $email; otp = (Otp $email) }) 200
    $login = Request 'POST' '/api/v1/auth/login' @{ username = $name; password = $password }
    Step 'Farmer: login returns TokenPair' $login 200
    if ($login.Body.role -ne 'FARMER' -or $login.Body.expiresIn -ne 900) { throw 'Unexpected TokenPair claims' }
    $rotated = Request 'POST' '/api/v1/auth/refresh' @{ refreshToken = $login.Body.refreshToken }
    Step 'Farmer: refresh rotates token' $rotated 200
    Step 'Farmer: old refresh rejected' (Request 'POST' '/api/v1/auth/refresh' @{ refreshToken = $login.Body.refreshToken }) 401
    Step 'Farmer: logout' (Request 'POST' '/api/v1/auth/logout' @{ refreshToken = $rotated.Body.refreshToken } $rotated.Body.accessToken) 204
    Step 'Farmer: refresh after logout rejected' (Request 'POST' '/api/v1/auth/refresh' @{ refreshToken = $rotated.Body.refreshToken }) 401
    Step 'Public register ADMIN rejected' (Request 'POST' '/api/v1/auth/register' @{
        username = 'demo_admin_' + $suffix; email = 'demo.admin.' + $suffix + '@example.test'
        password = $password; role = 'ADMIN'; profile = @{ fullName = 'Invalid public Admin' }
    }) 400
    $results | Format-Table -AutoSize
    $report = @('# Local authentication demo', '', 'Service: http://localhost:8081', 'Mailpit: http://localhost:8025',
        ('Farmer demo username: ' + $name), '', '| Step | HTTP | Result |', '| --- | --- | --- |')
    foreach ($result in $results) { $report += '| ' + $result.Step + ' | ' + $result.HTTP + ' | ' + $result.Result + ' |' }
    $report += @('', 'No passwords, OTPs, access tokens or refresh tokens are included in this report.',
        'Demo account password is in ignored local account.env next to this report; Admin passwords are in .env.')
    $report | Set-Content -LiteralPath (Join-Path $directory 'latest.md') -Encoding UTF8
    Write-Output ('DEMO PASS: ' + $results.Count + ' steps. Report: .tools/demo-auth/latest.md')
} finally { $client.Dispose() }
