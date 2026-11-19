# T12 flows

Thread: `BotUpdateHandler` runs on the Telegram consumer thread. Two taps may run at once only if the consumer is multi-threaded; `JobStore` makes this safe either way (F4).

## F1: success

```mermaid
sequenceDiagram
  actor U as Owner
  participant BH as BotUpdateHandler
  participant DH as DeliveryCallbackHandler
  participant DS as DeliveryService
  participant ST as JobStore
  participant TG as TelegramGateway
  U->>BH: tap "Download transcript" (transcript:J)
  BH->>BH: private chat, user allowed
  BH->>DH: handle(callback)
  DH->>DS: claim(J, userId, TRANSCRIPT)
  DS->>ST: load(J), transition(J, TRANSCRIPT, AVAILABLE, SENDING)
  ST-->>DS: APPLIED
  DS-->>DH: GRANTED(job, transcript.txt)
  DH->>TG: answerCallback(id, "Sending transcript…")
  DH->>TG: editButtons(chat, summaryMsg, delivery(J, audioState, SENDING))
  DH->>TG: sendDocument(chat, transcript.txt, "<title>.txt", null)
  DH->>DS: complete(J, TRANSCRIPT)
  DS->>ST: transition(SENDING, SENT)
  DH->>TG: editButtons(chat, summaryMsg, delivery(J, audioState, SENT))
```

The audio button is rendered from the audio file's current state (read from the record returned by `claim`/`complete`), so it stays "Download audio" while that file is `AVAILABLE`.

## F2: refusals (no state change, no file sent)

```mermaid
sequenceDiagram
  actor U as User
  participant DH as DeliveryCallbackHandler
  participant DS as DeliveryService
  participant TG as TelegramGateway
  U->>DH: transcript:J
  DH->>DS: claim(J, userId, TRANSCRIPT)
  alt job missing, bad id, file EXPIRED, expiresAt passed, or transcript.txt gone
    DS-->>DH: EXPIRED
    DH->>TG: answerCallback("Expired, please resend the link.")
  else userId is not job.ownerUserId
    DS-->>DH: NOT_OWNER
    DH->>TG: answerCallback("This button is not for you.")
  else file already SENT (second tap)
    DS-->>DH: ALREADY_SENT
    DH->>TG: answerCallback("Already sent.")
  else file SENDING (tap at the same moment)
    DS-->>DH: IN_PROGRESS
    DH->>TG: answerCallback("Already being sent.")
  end
```

Check order inside `claim`: job missing → `EXPIRED`; owner mismatch → `NOT_OWNER`; file `SENT` → `ALREADY_SENT`; file `EXPIRED` → `EXPIRED`; file `SENDING` → `IN_PROGRESS`; file `AVAILABLE` and `expiresAt` not after now → `EXPIRED`; file `AVAILABLE` but `transcript.txt` not on disk → `EXPIRED` (file marked `EXPIRED`, nothing uploaded); else try the transition.

## F3: failure while sending

```mermaid
sequenceDiagram
  participant DH as DeliveryCallbackHandler
  participant DS as DeliveryService
  participant TG as TelegramGateway
  DH->>TG: answerCallback("Sending transcript…")
  DH->>TG: editButtons(... SENDING)
  DH->>TG: sendDocument(...)
  TG--xDH: TelegramGatewayException
  DH->>DS: release(J, TRANSCRIPT)
  Note over DS: SENDING to AVAILABLE
  DH->>TG: editButtons(... AVAILABLE) (best effort)
  DH->>TG: sendMessage(chat, "Could not send the transcript. Tap the button to try again.") (best effort)
```

The same path runs when any `RuntimeException` is thrown after the claim (`IOException` is never thrown, `sendDocument` reads the file inside the gateway call). A failure of an `editButtons` call (for example "message is not modified" or a deleted message) is logged and ignored: the state in `job.json` is the truth and the document is still sent. If `complete` fails after a successful send, the error is logged; the file stays `SENDING` (T14 recovery will reset it).

## F4: two taps at the same moment

```mermaid
sequenceDiagram
  actor U as Owner
  participant A as Tap A
  participant B as Tap B
  participant ST as JobStore (job lock)
  U->>A: transcript:J
  U->>B: transcript:J
  A->>ST: transition(AVAILABLE, SENDING)
  B->>ST: transition(AVAILABLE, SENDING)
  ST-->>A: APPLIED
  ST-->>B: STATE_MISMATCH
  Note over B: reload: SENDING gives IN_PROGRESS, SENT gives ALREADY_SENT
  A->>A: send once
```

## F5: unknown or unusable job

A tap on `transcript:` with an empty or invalid id, or a job whose `job.json` is missing or corrupt, gives `EXPIRED` (F2). A `JobStoreException` (an I/O error) is caught by the handler: `answerCallback("Something went wrong, please try again.")`, no state change.

## Not in this task

Access: a callback from a user who is not allowed is answered "No access." by `BotUpdateHandler` before `DeliveryCallbackHandler` runs (T4 behaviour, unchanged). Taps on `audio:` are ignored until T13.
