param([switch]$Docs)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$compiler = $null
$candidates = @()
if ($env:JAVA_HOME) { $candidates += Join-Path $env:JAVA_HOME 'bin\javac.exe' }
if (Get-Command javac -ErrorAction SilentlyContinue) { $candidates += (Get-Command javac).Source }
$candidates += @('C:\Program Files\JetBrains\PyCharm Community Edition 2024.1\jbr\bin\javac.exe',
                 'C:\Program Files\JetBrains\PyCharm 2023.3.4\jbr\bin\javac.exe')
foreach ($candidate in $candidates) {
    if (-not (Test-Path -LiteralPath $candidate)) { continue }
    try {
        $versionText = (& $candidate -version 2>&1 | Out-String)
        if ($LASTEXITCODE -eq 0) { $compiler = $candidate; break }
    } catch { continue }
}
if (-not $compiler) { throw 'JDK 8+ not found. Set JAVA_HOME to the JDK directory.' }
$jdkBin = Split-Path -Parent $compiler
New-Item -ItemType Directory -Force -Path 'build\classes','build\test-classes' | Out-Null
$compat = @('--release','8')
if ($versionText -match 'javac 1\.8') { $compat = @('-source','8','-target','8') }
$sources = @(Get-ChildItem -LiteralPath 'src\main\java' -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
& $compiler @compat -encoding UTF-8 -Xlint:all -d 'build\classes' @sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed.' }
$tests = @()
if (Test-Path -LiteralPath 'src\test\java') {
    $tests = @(Get-ChildItem -LiteralPath 'src\test\java' -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
}
if ($tests.Count -gt 0) {
    & $compiler @compat -encoding UTF-8 -Xlint:all -d 'build\test-classes' @sources @tests
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
}
$jarTool = Join-Path $jdkBin 'jar.exe'
if (Test-Path -LiteralPath $jarTool) {
    & $jarTool cfe 'build\tennis-minidb.jar' tennisdb.Main -C 'build\classes' .
    if ($LASTEXITCODE -ne 0) { throw 'JAR creation failed.' }
} else {
    # Some IDE runtimes include javac but omit jar. A JAR is a ZIP with a manifest.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    Add-Type -AssemblyName System.IO.Compression
    $classRoot = (Resolve-Path -LiteralPath 'build\classes').Path
    New-Item -ItemType Directory -Force -Path 'build\classes\META-INF' | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $classRoot 'META-INF\MANIFEST.MF'),
        "Manifest-Version: 1.0`r`nMain-Class: tennisdb.Main`r`n`r`n", [System.Text.Encoding]::ASCII)
    $jarPath = Join-Path $PSScriptRoot 'build\tennis-minidb.jar'
    if (Test-Path -LiteralPath $jarPath) { Remove-Item -LiteralPath $jarPath }
    $archive = [System.IO.Compression.ZipFile]::Open($jarPath, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($file in (Get-ChildItem -LiteralPath $classRoot -Recurse -File)) {
            $entryName = $file.FullName.Substring($classRoot.Length + 1).Replace('\','/')
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, $entryName) | Out-Null
        }
    } finally { $archive.Dispose() }
}
if ($Docs) {
    & (Join-Path $jdkBin 'javadoc.exe') -quiet -encoding UTF-8 -charset UTF-8 -docencoding UTF-8 -Xdoclint:none -d 'build\javadoc' @sources
    if ($LASTEXITCODE -ne 0) { throw 'Javadoc failed.' }
}
Write-Host 'Build OK: build\tennis-minidb.jar'
