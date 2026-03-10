param(
    [Parameter(Mandatory = $true)]
    [string]$ObjPath,

    [Parameter(Mandatory = $true)]
    [string]$DebugOut,

    [ValidateSet("new", "reuse")]
    [string]$WindowMode = "new"
)

$debugDir = Split-Path -Parent $DebugOut
if (-not [string]::IsNullOrWhiteSpace($debugDir) -and -not (Test-Path $debugDir)) {
    New-Item -ItemType Directory -Path $debugDir | Out-Null
}

$debugText = & java -cp "bin;lib/mj-runtime.jar" "rs.etf.pp1.mj.runtime.Run" "-debug" $ObjPath 2>&1 | Out-String
$debugExit = $LASTEXITCODE

$debugText | Out-File -FilePath $DebugOut -Encoding utf8
$debugAbs = (Resolve-Path $DebugOut).Path

$codeArgs = if ($WindowMode -eq "reuse") { @("--reuse-window", $debugAbs) } else { @("--new-window", $debugAbs) }
$codeCmd = Get-Command code -ErrorAction SilentlyContinue
if ($codeCmd) {
    & $codeCmd.Source @codeArgs
} else {
    $codeExe = Join-Path $env:LOCALAPPDATA "Programs\Microsoft VS Code\Code.exe"
    if (Test-Path $codeExe) {
        Start-Process -FilePath $codeExe -ArgumentList $codeArgs
    } else {
        Write-Output ("Debug output saved to " + $debugAbs)
    }
}

if ($debugExit -ne 0) {
    Write-Output ("Runtime debug exited with code " + $debugExit + " (output was saved).")
}

exit 0
