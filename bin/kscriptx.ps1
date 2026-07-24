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

# Short-lived CLI JVM tuning (warm cache hits).
$ksxOpts = @("-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC")
if ($env:KSCRIPTX_JAVA_OPTS) {
    $ksxOpts += $env:KSCRIPTX_JAVA_OPTS.Split(" ", [System.StringSplitOptions]::RemoveEmptyEntries)
}

# Forward pipeline input to Java (PowerShell does not do this automatically for child processes).
if ($MyInvocation.ExpectingInput) {
    $input | & java @ksxOpts -cp $cp io.kscriptx.MainKt @args
} else {
    & java @ksxOpts -cp $cp io.kscriptx.MainKt @args
}
exit $LASTEXITCODE
