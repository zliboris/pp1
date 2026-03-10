param(
    [Parameter(Mandatory = $true)]
    [string]$ObjPath,

    [Parameter(Mandatory = $true)]
    [string]$DisasmOut,

    [ValidateSet("new", "reuse")]
    [string]$WindowMode = "new"
)

$disasmDir = Split-Path -Parent $DisasmOut
if (-not [string]::IsNullOrWhiteSpace($disasmDir) -and -not (Test-Path $disasmDir)) {
    New-Item -ItemType Directory -Path $disasmDir | Out-Null
}

$disasmText = & java -cp "lib/mj-runtime.jar" "rs.etf.pp1.mj.runtime.disasm" $ObjPath 2>&1 | Out-String
$disasmExit = $LASTEXITCODE

$disasmText | Out-File -FilePath $DisasmOut -Encoding utf8
$disasmAbs = (Resolve-Path $DisasmOut).Path

$codeArgs = if ($WindowMode -eq "reuse") { @("--reuse-window", $disasmAbs) } else { @("--new-window", $disasmAbs) }
$codeCmd = Get-Command code -ErrorAction SilentlyContinue
if ($codeCmd) {
    & $codeCmd.Source @codeArgs
} else {
    $codeExe = Join-Path $env:LOCALAPPDATA "Programs\Microsoft VS Code\Code.exe"
    if (Test-Path $codeExe) {
        Start-Process -FilePath $codeExe -ArgumentList $codeArgs
    } else {
        Write-Output ("Disasm saved to " + $disasmAbs)
    }
}

if ($disasmExit -ne 0) {
    Write-Output ("disasm exited with code " + $disasmExit + " (output was saved).")
}

exit 0
