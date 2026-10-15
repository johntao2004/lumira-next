$ErrorActionPreference = 'Stop'

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'This offline generator requires Windows with the configured System.Speech voices installed.'
}

$sampleRate = 22050
$sampleBytes = 2
$speechRate = -2
$voiceByLanguage = @{
    zh = 'Microsoft Yaoyao'
    en = 'Microsoft Zira Desktop'
}
$digitsByLanguage = @{
    zh = @([string][char]0x96F6, [string][char]0x4E00, [string][char]0x4E8C, [string][char]0x4E09, [string][char]0x56DB, [string][char]0x4E94, [string][char]0x516D, [string][char]0x4E03, [string][char]0x516B, [string][char]0x4E5D)
    en = @('zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine')
}
$targetRms = [Math]::Pow(10, -20.0 / 20.0) * 32767.0
$peakLimit = [Math]::Pow(10, -1.5 / 20.0) * 32767.0
$repoRoot = Split-Path -Parent $PSScriptRoot
$outputRoot = Join-Path $repoRoot 'backend\src\main\resources\captcha\audio'
$stageRoot = Join-Path ([IO.Path]::GetTempPath()) ('lumira-captcha-audio-' + [Guid]::NewGuid().ToString('N'))

Add-Type -AssemblyName System.Speech
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo($sampleRate, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
$rawPath = Join-Path $stageRoot 'raw.wav'
$metrics = New-Object System.Collections.Generic.List[object]

function Get-PcmSamples([string] $path) {
    $bytes = [IO.File]::ReadAllBytes($path)
    if ($bytes.Length -lt 44 -or [Text.Encoding]::ASCII.GetString($bytes, 0, 4) -ne 'RIFF' -or [Text.Encoding]::ASCII.GetString($bytes, 8, 4) -ne 'WAVE') {
        throw "Synthesized file is not a RIFF/WAVE file: $path"
    }
    $offset = 12
    $formatFound = $false
    $data = $null
    while ($offset + 8 -le $bytes.Length) {
        $chunkId = [Text.Encoding]::ASCII.GetString($bytes, $offset, 4)
        $chunkSize = [BitConverter]::ToUInt32($bytes, $offset + 4)
        $chunkStart = $offset + 8
        if ([long]$chunkStart + [long]$chunkSize -gt $bytes.Length) {
            throw "Invalid WAV chunk bounds in $path"
        }
        if ($chunkId -eq 'fmt ') {
            if ($chunkSize -lt 16) { throw "Invalid WAV fmt chunk in $path" }
            $formatTag = [BitConverter]::ToUInt16($bytes, $chunkStart)
            $channels = [BitConverter]::ToUInt16($bytes, $chunkStart + 2)
            $rate = [BitConverter]::ToUInt32($bytes, $chunkStart + 4)
            $bits = [BitConverter]::ToUInt16($bytes, $chunkStart + 14)
            if ($formatTag -ne 1 -or $channels -ne 1 -or $rate -ne $sampleRate -or $bits -ne 16) {
                throw "Expected mono 22050 Hz signed 16-bit PCM; got tag=$formatTag channels=$channels rate=$rate bits=$bits in $path"
            }
            $formatFound = $true
        }
        elseif ($chunkId -eq 'data') {
            $data = New-Object byte[] ([int]$chunkSize)
            [Array]::Copy($bytes, $chunkStart, $data, 0, [int]$chunkSize)
        }
        $offset = $chunkStart + [int]$chunkSize + ([int]$chunkSize % 2)
    }
    if (-not $formatFound -or $null -eq $data -or ($data.Length % 2) -ne 0) {
        throw "Missing fmt/data or unaligned PCM data in $path"
    }
    $samples = New-Object System.Int16[] ($data.Length / 2)
    [Buffer]::BlockCopy($data, 0, $samples, 0, $data.Length)
    return ,$samples
}

function Write-CanonicalWave([string] $path, [System.Int16[]] $samples) {
    $dataBytes = $samples.Length * $sampleBytes
    $stream = [IO.File]::Open($path, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $writer = New-Object System.IO.BinaryWriter($stream)
        $writer.Write([Text.Encoding]::ASCII.GetBytes('RIFF'))
        $writer.Write([int](36 + $dataBytes))
        $writer.Write([Text.Encoding]::ASCII.GetBytes('WAVE'))
        $writer.Write([Text.Encoding]::ASCII.GetBytes('fmt '))
        $writer.Write([int]16)
        $writer.Write([System.Int16]1)
        $writer.Write([System.Int16]1)
        $writer.Write([int]$sampleRate)
        $writer.Write([int]($sampleRate * $sampleBytes))
        $writer.Write([System.Int16]$sampleBytes)
        $writer.Write([System.Int16]16)
        $writer.Write([Text.Encoding]::ASCII.GetBytes('data'))
        $writer.Write([int]$dataBytes)
        foreach ($sample in $samples) { $writer.Write([System.Int16]$sample) }
        $writer.Flush()
        $writer.Dispose()
    }
    finally { $stream.Dispose() }
}

function Normalize-Clip([System.Int16[]] $source) {
    $peak = 0.0
    foreach ($sample in $source) { $peak = [Math]::Max($peak, [Math]::Abs([double]$sample)) }
    if ($peak -lt 100) { throw 'Synthesized digit was silent or nearly silent.' }
    $trimThreshold = [Math]::Max(90.0, $peak * 0.006)
    $first = 0
    while ($first -lt $source.Length -and [Math]::Abs([double]$source[$first]) -lt $trimThreshold) { $first++ }
    $last = $source.Length - 1
    while ($last -gt $first -and [Math]::Abs([double]$source[$last]) -lt $trimThreshold) { $last-- }
    $edgeKeep = [int]($sampleRate * 0.025)
    $first = [Math]::Max(0, $first - $edgeKeep)
    $last = [Math]::Min($source.Length - 1, $last + $edgeKeep)
    $length = $last - $first + 1
    if ($length -lt [int]($sampleRate * 0.12)) { throw "Trimmed speech unexpectedly short ($length samples)." }
    $active = New-Object System.Int16[] $length
    [Array]::Copy($source, $first, $active, 0, $length)
    $sumSquares = 0.0
    $peak = 0.0
    foreach ($sample in $active) {
        $value = [double]$sample
        $sumSquares += $value * $value
        $peak = [Math]::Max($peak, [Math]::Abs($value))
    }
    $rms = [Math]::Sqrt($sumSquares / $active.Length)
    if ($rms -lt 1.0 -or $peak -lt 1.0) { throw 'Speech clip has invalid RMS/peak.' }
    $gain = [Math]::Min($targetRms / $rms, $peakLimit / $peak)
    $fadeLength = [Math]::Max(1, [int]($sampleRate * 0.006))
    for ($i = 0; $i -lt $active.Length; $i++) {
        $fade = 1.0
        if ($i -lt $fadeLength) { $fade = [Math]::Min($fade, $i / [double]$fadeLength) }
        $fromEnd = $active.Length - 1 - $i
        if ($fromEnd -lt $fadeLength) { $fade = [Math]::Min($fade, $fromEnd / [double]$fadeLength) }
        $scaled = [Math]::Round([double]$active[$i] * $gain * $fade)
        $active[$i] = [System.Int16][Math]::Max(-32768, [Math]::Min(32767, $scaled))
    }
    return ,$active
}

function Assert-CanonicalWave([string] $path) {
    $bytes = [IO.File]::ReadAllBytes($path)
    if ($bytes.Length -lt 44 -or [Text.Encoding]::ASCII.GetString($bytes, 0, 4) -ne 'RIFF' -or [Text.Encoding]::ASCII.GetString($bytes, 8, 4) -ne 'WAVE' -or [Text.Encoding]::ASCII.GetString($bytes, 12, 4) -ne 'fmt ' -or [BitConverter]::ToUInt32($bytes, 16) -ne 16 -or [BitConverter]::ToUInt16($bytes, 20) -ne 1 -or [BitConverter]::ToUInt16($bytes, 22) -ne 1 -or [BitConverter]::ToUInt32($bytes, 24) -ne $sampleRate -or [BitConverter]::ToUInt16($bytes, 34) -ne 16 -or [Text.Encoding]::ASCII.GetString($bytes, 36, 4) -ne 'data' -or [BitConverter]::ToUInt32($bytes, 40) -ne ($bytes.Length - 44)) {
        throw "Generated file is not canonical mono 22050 Hz PCM16 WAV: $path"
    }
}

function Remove-SafeStageDirectory([string] $path) {
    $separators = [char[]]@([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    $stagePath = [IO.Path]::GetFullPath($path)
    $volumeRoot = [IO.Path]::GetPathRoot($tempRoot)
    if ($tempRoot.Length -gt $volumeRoot.Length) { $tempRoot = $tempRoot.TrimEnd($separators) }
    $stageVolumeRoot = [IO.Path]::GetPathRoot($stagePath)
    if ($stagePath.Length -gt $stageVolumeRoot.Length) { $stagePath = $stagePath.TrimEnd($separators) }
    $stageParent = [IO.Path]::GetDirectoryName($stagePath)
    $stageLeaf = [IO.Path]::GetFileName($stagePath)
    if ([string]::Equals($stagePath, $tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
        -not [string]::Equals($stageParent, $tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
        $stageLeaf -notmatch '^lumira-captcha-audio-[0-9A-Fa-f]{32}$') {
        throw "Refusing to remove staging directory outside the expected temporary path: $stagePath"
    }
    if (Test-Path -LiteralPath $stagePath) {
        Remove-Item -LiteralPath $stagePath -Recurse -Force
    }
}

try {
    $null = New-Item -ItemType Directory -Force -Path $stageRoot
    $installedVoices = @($synth.GetInstalledVoices() | Where-Object { $_.Enabled } | ForEach-Object { $_.VoiceInfo.Name })
    foreach ($language in @('zh', 'en')) {
        $requiredVoice = $voiceByLanguage[$language]
        if ($installedVoices -notcontains $requiredVoice) {
            throw "Required installed Windows voice not found: $requiredVoice. Install that exact voice; no substitute will be selected."
        }
        $synth.SelectVoice($requiredVoice)
        $synth.Rate = $speechRate
        $stageLanguage = Join-Path $stageRoot $language
        $null = New-Item -ItemType Directory -Force -Path $stageLanguage
        for ($digit = 0; $digit -le 9; $digit++) {
            $synth.SetOutputToWaveFile($rawPath, $format)
            $synth.Speak($digitsByLanguage[$language][$digit])
            $synth.SetOutputToNull()
            $clip = Normalize-Clip (Get-PcmSamples $rawPath)
            $stagedFile = Join-Path $stageLanguage "$digit.wav"
            Write-CanonicalWave $stagedFile $clip
            Assert-CanonicalWave $stagedFile
            $sumSquares = 0.0
            $peak = 0.0
            foreach ($sample in $clip) {
                $value = [double]$sample
                $sumSquares += $value * $value
                $peak = [Math]::Max($peak, [Math]::Abs($value))
            }
            $metrics.Add([pscustomobject]@{
                Language = $language
                Digit = $digit
                Voice = $requiredVoice
                Rate = $speechRate
                DurationMs = [Math]::Round(1000.0 * $clip.Length / $sampleRate, 1)
                RmsDbfs = [Math]::Round(20.0 * [Math]::Log10([Math]::Sqrt($sumSquares / $clip.Length) / 32767.0), 1)
                PeakDbfs = [Math]::Round(20.0 * [Math]::Log10($peak / 32767.0), 1)
            })
        }
        $synth.SetOutputToNull()
    }
    if ($metrics.Count -ne 20) { throw "Expected 20 generated clips; got $($metrics.Count)." }
    foreach ($language in @('zh', 'en')) {
        foreach ($digit in 0..9) {
            $stagedFile = Join-Path (Join-Path $stageRoot $language) "$digit.wav"
            if (-not (Test-Path -LiteralPath $stagedFile -PathType Leaf)) { throw "Missing staged clip: $stagedFile" }
            $targetDir = Join-Path $outputRoot $language
            $null = New-Item -ItemType Directory -Force -Path $targetDir
            Copy-Item -LiteralPath $stagedFile -Destination (Join-Path $targetDir "$digit.wav") -Force
        }
    }
    $metrics | Group-Object Language | ForEach-Object {
        $items = @($_.Group)
        [pscustomobject]@{
            Language = $_.Name
            Voice = $items[0].Voice
            Rate = $items[0].Rate
            Clips = $items.Count
            DurationMsMedian = ($items.DurationMs | Sort-Object | Select-Object -Index 4)
            RmsDbfsMin = ($items.RmsDbfs | Measure-Object -Minimum).Minimum
            RmsDbfsMax = ($items.RmsDbfs | Measure-Object -Maximum).Maximum
            PeakDbfsMin = ($items.PeakDbfs | Measure-Object -Minimum).Minimum
            PeakDbfsMax = ($items.PeakDbfs | Measure-Object -Maximum).Maximum
        }
    } | Format-Table -AutoSize
}
finally {
    try { $synth.Dispose() }
    finally { Remove-SafeStageDirectory -path $stageRoot }
}
