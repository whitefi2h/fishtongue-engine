param(
  [Parameter(Mandatory = $true)]
  [string]$JdkHome
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$runtimeLock = Get-Content -Raw -LiteralPath (Join-Path $root "RUNTIME_LOCK.json") | ConvertFrom-Json
$version = Get-Content -Raw -LiteralPath (Join-Path $root "ENGINE_VERSION.json") | ConvertFrom-Json
$gradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { "E:\.gradle-fishtongue-engine" }
$env:GRADLE_USER_HOME = $gradleHome

& (Join-Path $root "gradlew.bat") test desktop-api:buildFatJar cyclonedxBom --console=plain
if ($LASTEXITCODE -ne 0) { throw "Engine verification failed." }

$jar = Join-Path $root "desktop-api\build\libs\desktop-api-all.jar"
$jlink = Join-Path $JdkHome "bin\jlink.exe"
if (-not (Test-Path -LiteralPath $jar)) { throw "Fat JAR was not produced." }
if (-not (Test-Path -LiteralPath $jlink)) { throw "jlink.exe not found in $JdkHome." }

$dist = Join-Path $root "dist"
$stage = Join-Path $dist "fishtongue-engine-windows-x64"
$resolvedStage = [System.IO.Path]::GetFullPath($stage)
if (-not $resolvedStage.StartsWith([System.IO.Path]::GetFullPath($dist), [System.StringComparison]::OrdinalIgnoreCase)) {
  throw "Refusing to replace a release stage outside dist."
}
if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage | Out-Null

& $jlink `
  --module-path (Join-Path $JdkHome "jmods") `
  --add-modules ($runtimeLock.modules -join ",") `
  --output (Join-Path $stage "runtime") `
  --strip-debug `
  --no-header-files `
  --no-man-pages `
  --compress=2
if ($LASTEXITCODE -ne 0) { throw "jlink failed." }

Copy-Item -LiteralPath $jar -Destination (Join-Path $stage "fishtongue-engine.jar")
Copy-Item -LiteralPath (Join-Path $root "LICENSE") -Destination (Join-Path $stage "LICENSE")
Copy-Item -LiteralPath (Join-Path $root "FISHTONGUE_BUILD.md") -Destination $stage
Copy-Item -LiteralPath (Join-Path $root "ENGINE_VERSION.json") -Destination $stage
Copy-Item -LiteralPath (Join-Path $root "RUNTIME_LOCK.json") -Destination $stage
Copy-Item -LiteralPath (Join-Path $root "build\reports\bom.json") -Destination (Join-Path $stage "sbom.cyclonedx.json")
Copy-Item -LiteralPath (Join-Path $root "build\reports\bom.xml") -Destination (Join-Path $stage "sbom.cyclonedx.xml")

$jarHash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
$manifest = [ordered]@{
  engineVersion = $version.engineVersion
  protocolVersion = $version.protocolVersion
  upstreamCommit = $version.upstreamCommit
  jarSha256 = $jarHash
  runtimeVendor = $runtimeLock.vendor
  runtimeVersion = $runtimeLock.version
  runtimeModules = $runtimeLock.modules
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $stage "release-manifest.json") -Encoding utf8

$archive = Join-Path $dist "fishtongue-engine-windows-x64.zip"
if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force }
Compress-Archive -Path (Join-Path $stage "*") -DestinationPath $archive -CompressionLevel Optimal
$archiveHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
"$archiveHash  fishtongue-engine-windows-x64.zip" |
  Set-Content -LiteralPath "$archive.sha256" -Encoding ascii

Write-Host "Created $archive"
Write-Host "SHA-256 $archiveHash"
