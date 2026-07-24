$ErrorActionPreference = "Stop"
$jar = Join-Path $PSScriptRoot "kscriptx.jar"
if (-not (Test-Path $jar)) {
    Write-Error "kscriptx.jar not found. Build with: .\gradlew.bat :cli:build"
}

$cp = $jar
$lib = Join-Path $PSScriptRoot "lib"
if (Test-Path $lib) {
    $cp = "$jar;$lib\*"
}

# Forward pipeline input to Java (PowerShell does not do this automatically for child processes).
if ($MyInvocation.ExpectingInput) {
    $input | & java -cp $cp io.kscriptx.MainKt @args
} else {
    & java -cp $cp io.kscriptx.MainKt @args
}
exit $LASTEXITCODE
