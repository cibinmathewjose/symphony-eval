# CLI paths resolve from the caller's directory; automatic suite discovery is relative to the launcher.
$ErrorActionPreference = 'Stop'
try {
    $evalFolder = Join-Path $PSScriptRoot 'eval'
    if ($args.Count -gt 0 -and $args[0] -ceq '--eval-dir') {
        if ($args.Count -lt 2 -or [string]::IsNullOrWhiteSpace($args[1])) {
            throw 'Usage: run-eval.cmd [--eval-dir <folder>] [--config <file>] <workspace> <command> [arguments]'
        }
        $evalFolder = $args[1]
        if (-not (Test-Path -LiteralPath $evalFolder -PathType Container)) {
            throw "Evaluation folder does not exist or is not a directory: $evalFolder"
        }
        $args = @($args | Select-Object -Skip 2)
    }

    $jar = Join-Path $PSScriptRoot 'symphony-eval-1.1.1-SNAPSHOT-cli.jar'
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
        throw 'Place the Symphony Eval CLI JAR beside the launcher.'
    }

    $dotenv = Join-Path $PSScriptRoot '.env'
    $hasDotenv = Test-Path -LiteralPath $dotenv -PathType Leaf
    $settings = @{}
    if ($hasDotenv) {
        foreach ($line in Get-Content -LiteralPath $dotenv -Encoding UTF8) {
            if ($line -match '^\s*(?:export\s+)?(AZURE_OPENAI_ENDPOINT|AZURE_OPENAI_API_KEY|SYMPHONY_EVAL_ADAPTER|SYMPHONY_EVAL_ENDPOINT|SYMPHONY_EVAL_JUDGE_MODEL|SYMPHONY_EVAL_HEADER_KEY|SYMPHONY_EVAL_HEADER_VALUE|SYMPHONY_EVAL_REQUEST_BODY)\s*=\s*(.*?)\s*$') {
                $value = $Matches[2]
                if ($value.Length -ge 2 -and ($value[0] -eq '"' -or $value[0] -eq "'") -and $value[0] -eq $value[-1]) {
                    $value = $value.Substring(1, $value.Length - 2)
                }
                $settings[$Matches[1]] = $value
            }
        }

        if ([string]::IsNullOrWhiteSpace($env:SYMPHONY_EVAL_HEADER_KEY) -and
            [string]::IsNullOrWhiteSpace($env:SYMPHONY_EVAL_HEADER_VALUE)) {
            $env:SYMPHONY_EVAL_HEADER_KEY = $settings['SYMPHONY_EVAL_HEADER_KEY']
            $env:SYMPHONY_EVAL_HEADER_VALUE = $settings['SYMPHONY_EVAL_HEADER_VALUE']
        }

        if ([string]::IsNullOrWhiteSpace($env:SYMPHONY_EVAL_JUDGE_MODEL) -and
            -not [string]::IsNullOrWhiteSpace($settings['SYMPHONY_EVAL_JUDGE_MODEL'])) {
            $env:SYMPHONY_EVAL_JUDGE_MODEL = $settings['SYMPHONY_EVAL_JUDGE_MODEL'].Trim()
        }

        if ([string]::IsNullOrWhiteSpace($env:SYMPHONY_EVAL_REQUEST_BODY) -and
            -not [string]::IsNullOrWhiteSpace($settings['SYMPHONY_EVAL_REQUEST_BODY'])) {
            $env:SYMPHONY_EVAL_REQUEST_BODY = $settings['SYMPHONY_EVAL_REQUEST_BODY']
        }

        # Keep the Azure key paired with the endpoint it belongs to. Explicit judge settings win as a pair.
        if ([string]::IsNullOrWhiteSpace($env:SYMPHONY_EVAL_JUDGE_URL) -and
            [string]::IsNullOrWhiteSpace($env:SYMPHONY_EVAL_JUDGE_TOKEN) -and
            ($settings.ContainsKey('AZURE_OPENAI_ENDPOINT') -or $settings.ContainsKey('AZURE_OPENAI_API_KEY'))) {
            $endpoint = $null
            if (-not [Uri]::TryCreate($settings['AZURE_OPENAI_ENDPOINT'], [UriKind]::Absolute, [ref]$endpoint) -or
                $endpoint.Scheme -ne 'https' -or $endpoint.AbsolutePath -ne '/' -or $endpoint.Query -or
                $endpoint.Fragment -or $endpoint.UserInfo -or
                ($endpoint.Host -notlike '*.openai.azure.com' -and $endpoint.Host -notlike '*.services.ai.azure.com')) {
                throw 'Expected an Azure OpenAI HTTPS resource root in the launcher dotenv file.'
            }
            if ([string]::IsNullOrWhiteSpace($settings['AZURE_OPENAI_API_KEY'])) {
                throw 'AZURE_OPENAI_API_KEY is missing from the launcher dotenv file.'
            }
            $env:SYMPHONY_EVAL_JUDGE_URL = [Uri]::new($endpoint, '/openai/v1/chat/completions').AbsoluteUri
            $env:SYMPHONY_EVAL_JUDGE_TOKEN = $settings['AZURE_OPENAI_API_KEY']
        }
    }

    $usingDefaults = $args.Count -eq 0
    if ($usingDefaults) {
        $adapter = $settings['SYMPHONY_EVAL_ADAPTER']
        $target = $settings['SYMPHONY_EVAL_ENDPOINT']
        if ([string]::IsNullOrWhiteSpace($adapter) -or $adapter -ceq 'demo-stream') { $adapter = 'sse' }
        if ([string]::IsNullOrWhiteSpace($target)) {
            if ($adapter -cne 'sse') {
                throw 'Set SYMPHONY_EVAL_ENDPOINT in the launcher dotenv file for this adapter.'
            }
            $target = 'http://localhost:8085/directchat/stream'
        }
        if ($adapter -cnotin @('sse', 'json')) {
            throw 'SYMPHONY_EVAL_ADAPTER must be sse or json.'
        }
        $workspace = Join-Path $PSScriptRoot '.eval-workspace'
        $reportDir = Join-Path $workspace 'reports'
        $stamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff')
        $args = @($workspace, 'run-all', $adapter, $target, (Join-Path $reportDir "evaluation-$stamp.json"))
    }

    $offset = 0
    $configArgs = @()
    if ($args.Count -ge 2 -and $args[0] -ceq '--config') {
        $offset = 2
        $configArgs = @($args[0], $args[1])
    }
    if ($args.Count -ge ($offset + 2) -and
        $args[$offset + 1] -cin @('run', 'run-all', 'list')) {
        $workspace = $args[$offset]
        $suites = @()
        if (Test-Path -LiteralPath $evalFolder -PathType Container) {
            $suites = @(Get-ChildItem -LiteralPath $evalFolder -File |
                Where-Object { $_.Extension -in @('.yaml', '.yml') } | Sort-Object Name)
        }
        if ($usingDefaults -and $suites.Count -eq 0) {
            throw "No YAML suites found in $evalFolder. Add a .yaml or .yml suite and try again."
        }
        if ($usingDefaults) {
            Write-Host "Discovered $($suites.Count) YAML suite file(s) in: $evalFolder"
            if ($hasDotenv) { Write-Host "Loaded launcher settings from: $dotenv" }
            if ([string]::IsNullOrWhiteSpace($settings['SYMPHONY_EVAL_ENDPOINT'])) {
                Write-Host "Using the default Symphony Demo endpoint: $target"
            }
        }
        if ($suites.Count -gt 0) {
            $ids = @{}
            foreach ($suite in $suites) {
                $id = $suite.BaseName
                if ($id -cnotmatch '\A[a-z0-9][a-z0-9_-]{0,63}\z') {
                    throw "Invalid suite filename: $($suite.Name). Use a lowercase suite ID as the filename stem."
                }
                if ($ids.ContainsKey($id)) {
                    throw "Duplicate suite ID in eval folder: $id"
                }
                $ids[$id] = $true
            }
            $registered = @(& java -jar $jar @configArgs $workspace list)
            if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
            foreach ($suite in $suites) {
                $action = if ($suite.BaseName -cin $registered) { 'update' } else { 'add' }
                & java -jar $jar @configArgs $workspace $action $suite.BaseName $suite.FullName | Out-Null
                if ($LASTEXITCODE -ne 0) {
                    [Console]::Error.WriteLine("Could not register suite file: $($suite.Name)")
                    exit $LASTEXITCODE
                }
            }
        }
    }

    if ($usingDefaults) {
        New-Item -ItemType Directory -Path $reportDir -Force | Out-Null
    }
    & java -jar $jar @args
    exit $LASTEXITCODE
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 2
}