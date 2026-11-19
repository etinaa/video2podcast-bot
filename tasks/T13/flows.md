# T13 flows

## Main: unchanged / converted / compressed

```mermaid
sequenceDiagram
  actor U as Owner
  participant H as DeliveryCallbackHandler
  participant D as DeliveryService
  participant Q as JobQueue
  participant P as AudioPreparer
  participant G as TelegramGateway
  U->>H: callback audio:jobId
  H->>D: claim(jobId, userId, AUDIO)
  D-->>H: GRANTED (AVAILABLE→SENDING, source path)
  H->>G: answerCallback "Preparing audio…"
  H->>G: editButtons (audio SENDING)
  H->>Q: submit(task)
  Q->>P: prepare(source, durationSeconds)
  alt ≤50 MiB and mp3/m4a
    P-->>Q: UNCHANGED, same path
  else ≤50 MiB, other format
    P-->>Q: CONVERTED 128 kbps (out.m4a)
  else >50 MiB (or converted output too big)
    P-->>Q: COMPRESSED n kbps (out.m4a)
  end
  Q->>G: sendAudio(file, name, title, performer, caption)
  Q->>D: complete(jobId, AUDIO) (SENDING→SENT)
  Q->>G: editButtons (audio SENT)
  Q->>Q: delete out.m4a
```

## Refused tap (expired, not owner, already sent, in progress)

```mermaid
sequenceDiagram
  actor U
  participant H as DeliveryCallbackHandler
  participant D as DeliveryService
  participant G as TelegramGateway
  U->>H: callback audio:jobId
  H->>D: claim
  D-->>H: EXPIRED | NOT_OWNER | ALREADY_SENT | IN_PROGRESS
  H->>G: answerCallback(refusal text)
  Note over H,G: no editButtons, no queue, no sendAudio
```

## Failure: ffmpeg fails, output too big after the retry, Telegram refuses

```mermaid
sequenceDiagram
  participant Q as JobQueue task
  participant P as AudioPreparer
  participant D as DeliveryService
  participant G as TelegramGateway
  Q->>P: prepare
  P--xQ: JobFailure FAILED/TIMEOUT (or sendAudio throws)
  Q->>D: release(AUDIO) (SENDING→AVAILABLE)
  Q->>G: editButtons (audio AVAILABLE)
  Q->>G: sendMessage "Could not send the audio. Tap the button to try again."
  Note over Q: delete out.m4a; job and transcript untouched; the user may tap again
```

## Concurrent taps: two taps, one send

```mermaid
sequenceDiagram
  actor U
  participant H
  participant D as DeliveryService
  U->>H: tap 1
  U->>H: tap 2
  H->>D: claim (tap 1) → GRANTED
  H->>D: claim (tap 2) → IN_PROGRESS (or ALREADY_SENT)
  H-->>U: tap 2 answered "Already being sent." / "Already sent."
```

## Expiry while queued

```mermaid
sequenceDiagram
  participant H
  participant Q as JobQueue
  participant D as DeliveryService
  H->>D: claim at 12:59 (expiresAt 13:00) → GRANTED
  H->>Q: submit (waits behind a summary job)
  Note over Q: runs at 13:05: the claim already holds the file, the send completes
  Q->>D: complete → SENT
  Note over D: a repeat tap on this claimed file gets Already being sent. / Already sent. (state precedes expiry); only a new AVAILABLE claim after 13:00 gets EXPIRED
```
