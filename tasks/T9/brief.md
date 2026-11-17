# T9 brief

## Owner's original words
"Продолжи работу с T9" (T9 already existed in `tasks/todo.md` as `[ ]`).

## Questions and answers
1. Does T9 include deleting the temporary WAV after transcription (SPEC: "deleted after transcription")? **Yes:** after `transcript.txt` is written, `audio.wav` and `chunks/` are deleted; `source.*` is kept; on failure nothing is deleted.
2. What if Whisper finds no speech? **Agreed:** an empty chunk is skipped; an entirely empty transcript fails with a new kind `NO_SPEECH` ("No speech was found in this video."), no empty file written.
3. Retry and unreachable server? **Agreed:** a connection error counts as a failed chunk, retried once immediately; a second connection failure gives `SERVICE_UNREACHABLE` ("The transcription server is unreachable."), another second failure gives `FAILED`; later chunks are not sent.
4. Timeouts? **Agreed:** `app.whisper.timeout` (env `WHISPER_TIMEOUT`, default `PT10M`) per chunk answer, fixed 5 s connection wait; a timeout is a failed chunk (retry once, then `FAILED`).
5. Transcript format? **Agreed:** chunks joined by one space, one trailing newline, UTF-8, no header or timestamps.

## Out of scope: the owner's DLQ request (a separate new task)
The owner asked for a dead-letter queue (or an equivalent table or query) for jobs that fail because a service is unavailable, a user message such as "Service temporarily unavailable, we will send the result after recovery", and a check of the queue every 2 hours with a retry of the entries. **Decision: not in T9.** It becomes a new task after T11 (it needs the pipeline, T11, and cleanup/recovery, T14; it also meets the 1-hour retention and the "no database" rule in `SPEC.md`). T9 only provides the typed failure `SERVICE_UNREACHABLE` for it. The storage will be discussed in that task; if everything is in files, the DLQ gets its own separate file. The task still has to be added to `tasks/todo.md` through its own intake.

## Agreed task
See T9 in `tasks/todo.md` (status `[~]`). No functional `*IT` tests: T9 has no user-visible behaviour yet (T11 wires it).
