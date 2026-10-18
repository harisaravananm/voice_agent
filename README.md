# On-Device Voice Agent (English + Hinglish)

Android voice assistant where **speech-to-text and text-to-speech run fully on the phone**. Only the LLM runs in the cloud (Gemini API).

## Architecture

```
Mic → Whisper STT (on-device) → Gemini LLM (cloud, streamed) → Piper TTS (on-device) → Speaker
```

| Part | Tech | Where |
|---|---|---|
| STT | Whisper-base (int8) via sherpa-onnx | On-device |
| TTS | Piper VITS: `en_US-amy-low`, `hi_IN-pratham-medium` via sherpa-onnx | On-device |
| LLM | Gemini 2.5 Flash (streaming) | Cloud |

## How it keeps latency low
- The LLM response is streamed, then split into sentences/clauses and spoken immediately while the rest is still being generated.
- Hinglish replies are generated in Devanagari so the Hindi voice pronounces them naturally. The voice is picked per sentence by script.
- The app shows STT time, LLM first-token time, and time to first audio.

## Languages
- **English:** toggle off.
- **Hinglish:** toggle on (Whisper decodes in Hindi mode). Replies come back in Hindi/Devanagari with English loanwords.

## Setup
Large files are not in this repo. Download them yourself:

1. Open the project in Android Studio.
2. Download the sherpa-onnx Android AAR from https://github.com/k2-fsa/sherpa-onnx/releases and put it in `app/libs/`. Match the file name in `app/build.gradle.kts`.
3. Download these from the same releases page (tags `asr-models` and `tts-models`), extract, and put the folders in `app/src/main/assets/`:
   - `sherpa-onnx-whisper-base`
   - `vits-piper-en_US-amy-low`
   - `vits-piper-hi_IN-pratham-medium`
4. Get a free key at https://aistudio.google.com/apikey and set `GEMINI_KEY` in `MainActivity.kt`.
5. Connect an Android phone (USB debugging on) and press Run. Wait for the status to say **Ready**, then tap the mic, speak, and tap again.

## Example sentences tested
- "What is the weather forecast for Bangalore tomorrow?"
- "Can you set a reminder for my meeting at 5 PM?"
- "Mujhe kal subah 7 baje ka alarm set karna hai."
- "Aaj ka schedule kya hai, aur meri next meeting kab hai?"


## Possible improvements
- Whisper-small for better accuracy, voice activity detection instead of tap-to-talk, tool calling for real alarms and reminders.

## Author
Hari Saravanan M
