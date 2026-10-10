# CAPTCHA audio clips

These are pre-generated, one-digit speech clips. The application reads the packaged WAV files at runtime; it does not need a system voice, TTS engine, or network access.

- `zh/0.wav`–`zh/9.wav`: eSpeak NG 1.52.0, Mandarin `cmn` voice, spoken as `零` through `九`.
- `en/0.wav`–`en/9.wav`: eSpeak NG 1.52.0, US English `en-us` voice, spoken as `zero` through `nine`.
- Encoding: RIFF/WAVE, mono, 22050 Hz, signed 16-bit PCM. Each file contains only the standard `fmt ` and `data` chunks.

Source project: [eSpeak NG](https://github.com/espeak-ng/espeak-ng). eSpeak NG is licensed GPL-3.0-or-later. Its GPL states that program output is covered only when the output content itself constitutes a covered work. These clips contain only isolated spoken digit names; the TTS program and voice data are not included in the application. No Apple system voice was used.
