# CAPTCHA audio clips

The application reads these pre-generated, one-digit WAV files at runtime. Runtime playback does not require a speech engine, voice pack, or network connection.

- `zh/0.wav`–`zh/9.wav`: Microsoft Windows `System.Speech` / SAPI, voice `Microsoft Yaoyao`, `zh-CN`, speaking `零` through `九`.
- `en/0.wav`–`en/9.wav`: Microsoft Windows `System.Speech` / SAPI, voice `Microsoft Zira Desktop`, `en-US`, speaking `zero` through `nine`.
- Both voices were synthesized offline at speech rate `-2` by [`SpeechSynthesizer.SetOutputToWaveFile`](https://learn.microsoft.com/en-us/dotnet/api/system.speech.synthesis.speechsynthesizer.setoutputtowavefile).
- The generator trims quiet margins while retaining a 25 ms boundary, normalizes each clip toward -20 dBFS RMS with a -1.5 dBFS peak ceiling, and applies a 6 ms edge fade. The stored output is RIFF/WAVE, mono, 22050 Hz, signed 16-bit PCM, with only the standard `fmt ` and `data` chunks.

To regenerate on Windows from the repository root, install the exact voices above and run `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/generate-captcha-audio.ps1`. The script fails if a required voice is missing; it does not download or substitute voices. Source WAVs are synthesized locally and the script emits only the packaged digit clips.
