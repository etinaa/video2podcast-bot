
## Q-analyst-1 · analyst → architect
Please define the delivery failure contract for requirements/scenarios: explicit Telegram sendDocument rejection vs ambiguous transport failure; SENDING rollback/retry policy; answerCallbackQuery and keyboard edit failures; successful upload followed by SENT persistence failure. SPEC requires one-send on duplicate/concurrent taps and safe errors, while FileState allows SENDING → AVAILABLE on failed send. No automatic retry policy is specified. Also confirm how to edit only the keyboard while preserving summary HTML (job.json does not contain summary bullets), whether callback chat/message must match stored chatId/summaryMessageId, and how to derive a safe <title>.txt filename for separators/control characters/very long titles. I will draft settled requirements now and keep these contracts explicitly pending.

## A: Q-architect-1 · architect (answer to Q-analyst-1)
All of it is in `tasks/T12/api.md` and `flows.md` (F3). Summary:
1. **Send failure.** The gateway reports every failure of `sendDocument` as one `TelegramGatewayException`, so a rejection and an ambiguous transport failure are not told apart. Both (and a missing `transcript.txt`, or any RuntimeException after the claim) do `release` (SENDING → AVAILABLE), best-effort `editButtons` back to "Download transcript", and a message "Could not send the transcript. Tap the button to try again." No automatic retry; the user taps again. Accepted risk: an ambiguous failure after Telegram stored the file can lead to a second delivery on a manual retry.
2. **answerCallback failure.** It is best effort: logged, and the send goes on (the first answer is "Sending transcript…"). A failure of any `editButtons` is logged and ignored; `job.json` is the truth.
3. **Upload ok, SENT persistence fails.** Logged; the file stays SENDING, so further taps get "Already being sent."; T14's startup recovery resets it. No message to the user (the file was delivered).
4. **Keyboard only.** New `TelegramGateway.editButtons` (`editMessageReplyMarkup`); the summary text is never touched.
5. **Binding.** Ownership is by `callback.from.id == job.ownerUserId` only. The callback's own chat/message id are not compared; the file goes to `job.chatId` and the buttons are edited on `job.summaryMessageId` (skipped if null). The private-chat and allowed-user checks of `BotUpdateHandler` stay first.
6. **Filename.** `FileNames.transcriptName(title)`: `\ / : * ? " < > |` and control characters → `_`, stripped, cut to 100 UTF-16 units without splitting a surrogate pair, empty → `transcript`, plus `.txt`.

## A: Q-architect-1 · analyst
Read and incorporating the contracts. One conflict needs correction: SPEC.md success criterion 6 says a tap "after the working files are gone" gives an expired/resend notice. Your F3 treats missing transcript.txt as a retryable send failure. Please align the missing-file case with SPEC (Expired, please resend the link.; no upload), or escalate the product change to owner. I will specify SPEC's missing-file behavior. I will explicitly bound the duplicate guarantee to successful sends/concurrent claims and document ambiguous transport/manual-retry limits, rather than promise network-wide exactly-once delivery.

## A: Q-architect-1 (correction) · architect
Agreed, SPEC criterion 6 wins. A missing `transcript.txt` on an `AVAILABLE` file is checked inside `claim`, before the transition: outcome `EXPIRED` ("Expired, please resend the link."), the file is moved AVAILABLE → EXPIRED, nothing is uploaded, no retry. Only `sendDocument` errors and other exceptions after the claim are release-and-retry. `api.md` and `flows.md` updated. Your bound on the duplicate guarantee is correct.

## A: Q-architect-1 (handoff) · analyst
Correction incorporated: missing AVAILABLE transcript is marked EXPIRED before upload, expired notice only; other refusals preserve state. Analyst requirements/research and src/features/T12-transcript-button.feature are ready for review. 23 scenarios/outlines compile into 46 concrete scenarios. R7 maps low-level persistence/release failures and exact Unicode truncation to Developer unit tests, not internal calls from Gherkin. R3 documents ambiguous-response/manual-retry limits; no claim of transport-wide exactly-once delivery. No owner question remains open.

## Architect check · architect
Checked `requirements.md` R1–R9 and all 23 outlines/scenarios in `src/features/T12-transcript-button.feature` against `flows.md` F1–F5 and `api.md`. Every scenario is reachable through the contracts; texts, call order (answerCallback, editButtons SENDING, sendDocument, editButtons SENT), labels, claim check order, expiry boundary (`!now.isBefore(expiresAt)`) and missing-file EXPIRED match. No design change needed.

## Q-tester-1 · tester → architect
Step 1 review: api.md declares DeliveryCallbackHandler and its constructor package-private in com.video2podcast.bot, but components.md requires com.video2podcast.config.PipelineConfig to construct a bean and TelegramConfig to inject it into BotUpdateHandler. Java package access prevents that wiring from compiling. Please specify an accessible construction/injection boundary (for example a public handler and constructor, consistent with existing UrlMessageHandler), and update the contract/components accordingly. All 23 scenario/outlines otherwise have external Telegram/disk test boundaries; no test code has been written.

## A: Q-tester-1 · architect
Correct, my mistake. `DeliveryCallbackHandler` and its constructor are now `public` (as `UrlMessageHandler`); only `handle(CallbackQuery)` stays package-private because only `BotUpdateHandler` calls it. `FileNames` stays package-private (used only inside `bot/`). `api.md` and `components.md` updated; nothing else changes.

## Tester design review · tester
Verified A: Q-tester-1 against updated api.md/components.md: public handler and constructor permit config wiring; package-private handle remains callable by BotUpdateHandler. No open tester questions remain. Reviewed all 23 scenario/outlines against requirements R1–R9, contracts and flows: Telegram updates plus HTTP mocks and direct job-directory fixtures cover delivery bytes/filename/destination, state and keyboard progress, duplicate/concurrent taps, ownership/access, expiry/missing content, manual retry, notification failures and persisted startup state. A scenario-owned application clock and held HTTP response support deterministic expiry/progress observations; storage failure injection and exact surrogate truncation remain Developer unit-test obligations per R9. Transport-attempt assertions must retain the specified one-attempt expectation and report any client-level retries. Design approval only: no tests written or Maven execution in Step 1.

## Q-tester-2 · tester → developer
First focused TranscriptButtonIT compile stopped in src/test/java/com/video2podcast/bot/UrlMessageHandlerTest.java:46: ThreadSafeGateway does not implement new TelegramGateway.editButtons(long,int,List<InlineButton>). Log target/t12-tester-red.log. This appears to be an intermediate implementation state; please include this existing recording gateway in the interface update. Tester is continuing T12 steps.

## A: Q-tester-2 · developer
Done: `UrlMessageHandlerTest.ThreadSafeGateway` and `RecordingGateway` now implement `editButtons`. `./mvnw test -Dno-it` compiles and passes (unit tests only). Note: `./mvnw spotless:check` currently reports a format violation in your `src/it/.../TranscriptSetupSteps.java`; I did not touch `src/it`.

## Tester progress · tester
Q-tester-2 is resolved. Focused TranscriptButtonIT now passes all 3 full-context tests (happy path/duplicate, held concurrent tap, owner/expiry) in target/t12-tester-focused.log. Fixture corrections: follow Telegram client's attach:// multipart part reference; treat absent JSON sentAt as null (existing serializer omits null values). All T12 step definitions are written; Cucumber validation is underway. Full verify remains reserved for orchestrator.

## Q-tester-3 · tester → developer
T12 selected-feature run exercises all 46 scenarios. The dropped-upload-response scenario makes TWO actual sendDocument HTTP requests for one tap (same multipart bytes), failing the required one-attempt/no-automatic-retry assertion. The mock returns WireMock Fault.EMPTY_RESPONSE on every upload, so it closes the connection after receiving the body, before an HTTP response. Log target/t12-tester-selected.log; scenario "A dropped upload response does not trigger an automatic retry". Existing OkHttp transport appears to retry transparently. Please disable automatic upload retry through the real Telegram client configuration or otherwise satisfy the approved R6 HTTP boundary; tester will retain the one-attempt assertion. Four other failures are fixture-only absent/null serialization comparisons, being corrected in tester files.

## Q-tester-3 evidence correction · tester
The selected XML gives **3** sendDocument attempts (not 2) for the dropped response. The underlying finding is unchanged: more than one HTTP upload for one callback. The full multipart body was received on every recorded attempt.

## Tester focused rerun · tester
After writing fixture null sentAt fields in the same omitted form as the production serializer, all fixture-comparison failures are resolved. Latest selected feature run: 46 tests, 45 pass, only Q-tester-3 fails (3 sendDocument attempts after dropped response). The broader Cucumber run completed with 244 scenarios, 5 failures all in T12 (the same 4 now-fixed fixture comparisons plus Q-tester-3), 5 existing manual-media skips; no non-T12 failure. Scoped Spotless apply/check pass. Waiting to verify the Developer's transport fix.

## A: Q-tester-3 · developer
Fixed in `config/TelegramConfig.telegramClient`: the client now uses `new OkHttpClient.Builder().retryOnConnectionFailure(false)` (everything else as the library default). A dropped connection now gives one HTTP attempt, `TelegramGatewayException`, and the normal release-and-retry-by-tap path. Unit test `TelegramConfigTest.theClientDoesNotResendARequestWhoseConnectionDropped` fails (2 requests) with the retry on and passes with it off. Please rerun the dropped-response scenario.

## A: Q-tester-3 verification · tester
Verified the Developer's retryOnConnectionFailure(false) fix through the real Telegram HTTP client: the selected T12 feature now passes **46/46**, zero failures/errors/skips. The dropped-response scenario records exactly one upload attempt, AVAILABLE/null sentAt rollback and the safe manual-retry message. Log: target/t12-tester-selected.log (BUILD SUCCESS, 2026-11-19 17:17:58 +02:00). No T12 assertion was weakened.

## Tester Step 2 handoff · tester
Complete. T12 selected Cucumber feature: 46/46 pass. Final TranscriptButtonIT: 3/3 pass. Scoped Spotless apply/check and git diff --check pass. No open question; Q-tester-2 and Q-tester-3 verified resolved. Detailed files, commands and limits: tasks/T12/comms/tester-step2.md. Full verify/mutation/review remain with orchestrator; no clean/full verify run by Tester.
