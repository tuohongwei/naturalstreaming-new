param([string]$SshKey="$PSScriptRoot/../../keys/AiDevKey.pem")
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$python='C:/Users/Administrator/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
& "$PSScriptRoot/build.ps1"
& $python "$PSScriptRoot/test_upload_paths.py"
if($LASTEXITCODE -ne 0){throw 'Uploader boundary tests failed'}
$env:NS_CREDENTIALS_FILE=Join-Path $root '.local/owner-access.json'
& $python "$PSScriptRoot/test_e2e.py"
if($LASTEXITCODE -ne 0){throw 'Public backend regression failed'}
& $python "$PSScriptRoot/test_race.py" --key $SshKey
if($LASTEXITCODE -ne 0){throw 'MySQL race regression failed'}
$env:NS_CREDENTIALS_FILE=Join-Path $root '.local/frontend-access.json'
& $python "$PSScriptRoot/frontend_smoke.py"
if($LASTEXITCODE -ne 0){throw 'Frontend downloader smoke failed'}
Write-Output 'All backend and integration checks passed; Android device playback remains separate acceptance.'
