# T19 manual check on a Russian video (2026-11-26)

Collected by the orchestrator with the owner. The Developer copies this into `docs/host-setup.md` in place of the T19-MANUAL-CHECK-PLACEHOLDER block.

- Video: "Нет работы в IT? Как пережить этот период и не сдаться", 10m 5s, https://www.youtube.com/watch?v=KkYrgszPpbQ (sent by the owner from Telegram).
- Date: 2026-11-26. Bot image built from `main` at 1561b56 (T19 code 2a55577), `podman compose up -d --build`.
- Services: whisper.cpp `whisper-server` 1.9.6 (Homebrew, ggml 1.0.0, Metal), `-m ggml-large-v3-turbo.bin -l auto -t 8 -mc 0`; Ollama 0.40.16, `qwen3:32b`, `OLLAMA_NUM_CTX` 8192 (default).
- Contract check before the run: a Russian phrase spoken by the macOS voice Milena, sent with `language=auto`, `response_format=verbose_json`, `translate=false`, `detect_language=false`, came back with `"language": "russian"` and the Russian text.
- Detected language: Russian. The transcript and the summary are in Russian. (The bot does not log the detected code.)
- Transcript excerpt: "Представьте, что вы 10-15 лет работаете в IT, развиваетесь, строите карьеру, а потом в один прекрасный день вас сокращают. Первый раз. Вы начинаете искать работу." 10,633 characters, all letters Cyrillic.
- Summary (8 bullets, under 1,500 characters), first two: "После травмы и личных проблем автор потерял мотивацию и энергетику." / "Он заметил, как одна сфера жизни влияет на другие, создавая замкнутый круг."
- Bot texts (owner's screenshot of the chat): all English: "Done.", "Duration: 10m 5s", the buttons changed to "Audio sent" and "Transcript sent", the audio caption "Original audio, not compressed.". The audio plays in Telegram's player as "Нет работы в IT? Как пережить этот…", 10:05.
- File names: the bot sends "Нет работы в IT_ Как пережить этот период и не сдаться.txt" and ".m4a" (Cyrillic kept, `?` replaced by `_`; the multipart file name is checked by LanguageIT). Telegram Desktop for macOS shows the downloaded document in the chat as "Нет_работы_в_IT_Как_переж…тот_период_и_не_сдаться.txt" and saves both files with underscores instead of spaces, as it already did for English files before T19. Where the underscores come from (the Telegram server or the desktop client) was not verified; the single `_` between "IT" and "Как" (the bot sends "IT_ Как") suggests the client's own name cleaning. Cyrillic is kept either way. The audio arrived as M4A, 9.8 MB, not compressed; the transcript 18.8 KB.
- Token estimate: the full transcript sent to Ollama gave `prompt_eval_count` 3185 for 10,633 characters, 3.34 characters per token. The bot's estimate (2 characters per token) gives 5317, so it is safe (higher than the real count) and the characters-per-token ratio for Russian stays at 2; it must never be set above the lowest ratio measured. The cost is chunks about 1.7 times smaller than necessary.
- Result: passed.
