param(
    [string]$Docker = 'docker',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
if (-not (Get-Command $Docker -ErrorAction SilentlyContinue)) {
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/resources/bin/docker.exe'),
        (Join-Path $env:ProgramFiles 'Docker/Docker/resources/bin/docker.exe')
    )
    $Docker = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (-not $Docker) { throw 'Docker CLI was not found. Install Docker Desktop and start the Linux engine.' }
}
$Docker = (Get-Command $Docker -ErrorAction Stop).Source
$originalPath = $env:PATH
# A shell opened before Desktop was installed may have a stale PATH. Docker's credential
# helper must be discoverable without changing credential settings or machine-wide PATH.
$env:PATH = (Split-Path -Parent $Docker) + [IO.Path]::PathSeparator + $env:PATH
$projectRoot = Split-Path -Parent $PSScriptRoot
$reportDirectory = Join-Path $projectRoot 'build/reports/guarddog'
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
$runId = 'guarddog-verify-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$networkName = $runId + '-network'
$databaseName = $runId + '-postgres'
$runtimeName = $runId + '-api'
$testName = $runId + '-tests'
$networkCreated = $false
$databaseCreated = $false
$runtimeStarted = $false
$testStarted = $false
$phase = 'engine'
$summary = [ordered]@{
    startedAt = [DateTimeOffset]::Now.ToString('o')
    status = 'RUNNING'
    guarddogVersion = '3.2.0'
    semgrepVersion = '1.180.0'
    runtimeDockerfile = 'Dockerfile'
    realRegistryPackages = @('npm:is-number@7.0.0', 'pypi:six@1.17.0')
    scope = 'Default production image; both CLIs; API startup; local PR fixtures; real PostgreSQL; no AI or GitHub writes'
}
$summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $reportDirectory 'verification-summary.json') -Encoding UTF8

function Invoke-VerificationDocker {
    param([string[]]$DockerArguments, [string]$LogName, [string]$ErrorLogName)
    if ($ErrorLogName) {
        # Keep scanner diagnostics out of JSON output, just as the API client does.
        & $Docker @DockerArguments 2> (Join-Path $reportDirectory $ErrorLogName) |
            Tee-Object -FilePath (Join-Path $reportDirectory $LogName)
    } elseif ($LogName) {
        & $Docker @DockerArguments 2>&1 | Tee-Object -FilePath (Join-Path $reportDirectory $LogName)
    } else {
        & $Docker @DockerArguments
    }
    if ($LASTEXITCODE -ne 0) {
        throw ('Docker verification command failed (exit ' + $LASTEXITCODE + '): ' + ($DockerArguments -join ' '))
    }
}

try {
    # A half-started Desktop may leave the API pipe open without answering. Bound the preflight.
    $engineStart = @{
        FilePath = $Docker
        ArgumentList = @('info', '--format', '{{.OSType}}')
        WindowStyle = 'Hidden'
        RedirectStandardOutput = (Join-Path $reportDirectory 'docker-engine.log')
        RedirectStandardError = (Join-Path $reportDirectory 'docker-engine-error.log')
        PassThru = $true
    }
    $engineCheck = Start-Process @engineStart
    if (-not $engineCheck.WaitForExit(30000)) {
        $engineCheck.Kill()
        throw 'Docker engine did not respond within 30 seconds. Start Docker Desktop and check WSL2.'
    }
    $engineType = ([string](Get-Content -LiteralPath (Join-Path $reportDirectory 'docker-engine.log') -Raw)).Trim()
    if ($engineCheck.ExitCode -ne 0 -or $engineType -ne 'linux') {
        throw 'A running Linux Docker engine is required. Start Docker Desktop and check WSL2.'
    }
    $phase = 'build'
    if (-not $SkipBuild) {
        Invoke-VerificationDocker -DockerArguments @('build', '-f', (Join-Path $projectRoot 'Dockerfile'),
            '-t', 'pr-guard-api:guarddog', $projectRoot) -LogName 'api-image-build.log'
        Invoke-VerificationDocker -DockerArguments @('build', '-f', (Join-Path $projectRoot 'Dockerfile.guarddog'),
            '--target', 'verification', '-t', 'pr-guard-api:guarddog-verification', $projectRoot) -LogName 'verification-image-build.log'
    }

    $phase = 'runtime-config'
    $configJson = & $Docker image inspect --format '{{json .Config}}' 'pr-guard-api:guarddog'
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the production API image' }
    $configJson | Set-Content -LiteralPath (Join-Path $reportDirectory 'runtime-image-config.json') -Encoding UTF8
    $config = $configJson | ConvertFrom-Json
    if ($config.User -ne 'app') { throw 'The production API image must run as the app user' }
    foreach ($setting in @('GUARDDOG_ENABLED=true', 'GUARDDOG_EXECUTABLE=/opt/guarddog/bin/guarddog',
            'GUARDDOG_TOP_PACKAGES_CACHE_LOCATION=/var/cache/prguard/guarddog', 'SEMGREP_BIN=/opt/semgrep/bin/semgrep')) {
        if ($config.Env -notcontains $setting) { throw ('Production image setting is missing: ' + $setting) }
    }
    $override = & $Docker run --rm --entrypoint printenv -e GUARDDOG_ENABLED=false 'pr-guard-api:guarddog' GUARDDOG_ENABLED
    if ($LASTEXITCODE -ne 0 -or $override -ne 'false') { throw 'Explicit GuardDog opt-out must override the image default' }
    $summary.defaultGuardDogEnabled = $true

    $phase = 'runtime-cli'
    # These commands run as the final API image's non-root app user, with v3 protection enabled.
    Invoke-VerificationDocker -DockerArguments @('run', '--rm', '--init', '--entrypoint', '/opt/guarddog/bin/guarddog',
        'pr-guard-api:guarddog', '--version') -LogName 'guarddog-version.log'
    Invoke-VerificationDocker -DockerArguments @('run', '--rm', '--init', '--entrypoint', '/opt/semgrep/bin/semgrep',
        'pr-guard-api:guarddog', '--version') -LogName 'semgrep-version.log'
    $scanJson = Invoke-VerificationDocker -DockerArguments @('run', '--rm', '--init', '--entrypoint', '/usr/bin/timeout',
        'pr-guard-api:guarddog', '180', '/opt/guarddog/bin/guarddog', 'npm', 'scan', 'is-number',
        '--version', '7.0.0', '--output-format', 'json') -LogName 'runtime-npm-scan.log' -ErrorLogName 'runtime-npm-scan.stderr.log'
    $scan = ($scanJson -join [Environment]::NewLine) | ConvertFrom-Json
    if ($scan.package -ne 'is-number' -or $scan.package_version -ne '7.0.0' -or $null -eq $scan.errors -or
            @($scan.errors.PSObject.Properties).Count -ne 0 -or $null -eq $scan.risks) {
        throw 'The production image did not complete the requested npm scan without errors'
    }
    Write-Output 'Production image: both scanners available, npm scan completed, explicit opt-out supported.'

    $phase = 'database'
    Invoke-VerificationDocker -DockerArguments @('network', 'create', $networkName)
    $networkCreated = $true
    Invoke-VerificationDocker -DockerArguments @('run', '-d', '--name', $databaseName, '--network', $networkName,
        '--network-alias', 'postgres', '-e', 'POSTGRES_DB=guarddog_verify', '-e', 'POSTGRES_USER=prguard',
        '-e', 'POSTGRES_PASSWORD=prguard', 'postgres:16-alpine')
    $databaseCreated = $true
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        & $Docker exec $databaseName pg_isready -U prguard -d guarddog_verify *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Disposable PostgreSQL did not become ready in time' }

    $phase = 'runtime-api'
    $runtimeStarted = $true
    Invoke-VerificationDocker -DockerArguments @('run', '-d', '--init', '--name', $runtimeName, '--network', $networkName,
        '-e', 'PGHOST=postgres', '-e', 'PGDATABASE=guarddog_verify', '-e', 'PGUSER=prguard', '-e', 'PGPASSWORD=prguard',
        '-e', 'OPENAI_API_KEY=', '-e', 'GITHUB_TOKEN=', '-e', 'API_TOKEN=', '-e', 'GITHUB_COMMENT_ENABLED=false',
        '-e', 'EVIDENCE_ENABLED=false', '-e', 'SONAR_ENABLED=false', '-e', 'POLL_INTERVAL=PT24H', 'pr-guard-api:guarddog')
    $healthProbe = 'import urllib.request; print(urllib.request.urlopen(''http://127.0.0.1:8080/actuator/health'', timeout=3).read().decode())'
    $healthy = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        $health = & $Docker exec $runtimeName python3 -c $healthProbe 2> $null
        if ($LASTEXITCODE -eq 0) {
            $health | Set-Content -LiteralPath (Join-Path $reportDirectory 'runtime-api-health.json') -Encoding UTF8
            $healthy = ($health | ConvertFrom-Json).status -eq 'UP'
            if ($healthy) { break }
        }
        Start-Sleep -Seconds 1
    }
    & $Docker logs $runtimeName 2>&1 | Set-Content -LiteralPath (Join-Path $reportDirectory 'runtime-api.log') -Encoding UTF8
    if (-not $healthy) { throw 'The default production API image did not become healthy' }
    $summary.runtimeApiHealthy = $true
    Write-Output 'Production API: started with a disposable PostgreSQL and health is UP.'

    $phase = 'integration-tests'
    $testStarted = $true
    Invoke-VerificationDocker -DockerArguments @('run', '--rm', '--init', '--name', $testName, '--network', $networkName,
        '-e', 'GUARDDOG_TEST_JDBC_URL=jdbc:postgresql://postgres:5432/guarddog_verify',
        '-e', 'GUARDDOG_TEST_DB_USER=prguard', '-e', 'GUARDDOG_TEST_DB_PASSWORD=prguard',
        '--mount', ('type=bind,source=' + $reportDirectory + ',target=/workspace/build/reports/guarddog'),
        'pr-guard-api:guarddog-verification') -LogName 'integration-tests.log'
    $summary.status = 'PASSED'
} catch {
    $summary.status = if ($phase -eq 'engine') { 'BLOCKED' } else { 'FAILED' }
    $summary.phase = $phase
    $summary.error = $_.Exception.Message
    throw
} finally {
    # Remove only resources with this invocation's unique names. No shared DB or images are deleted.
    if ($testStarted) { & $Docker rm -f $testName *> $null }
    if ($runtimeStarted) { & $Docker rm -f $runtimeName *> $null }
    if ($databaseCreated) { & $Docker rm -f -v $databaseName *> $null }
    if ($networkCreated) { & $Docker network rm $networkName *> $null }
    $summary.finishedAt = [DateTimeOffset]::Now.ToString('o')
    $summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $reportDirectory 'verification-summary.json') -Encoding UTF8
    $env:PATH = $originalPath
}
