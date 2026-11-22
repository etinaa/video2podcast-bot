# TD8 developer evidence

- `DeliveryCallbackHandler.EXPIRED` = `Files already downloaded or expired, please resend the link.` (only the constant changed).
- Unit tests updated to the new text: `DeliveryCallbackHandlerTest` (2), `AudioButtonTest`, `TranscriptButtonTest`.
- `SPEC.md` updated (line 28 retention paragraph, line 203 acceptance item).
- `docs/decisions.md:54` still says buttons answer "expired" (generic wording, not the quoted text). My permissions block `docs/`; left for the architect/orchestrator if wanted.
- `./mvnw spotless:check`: BUILD SUCCESS.
- `./mvnw verify -Dno-it`: BUILD SUCCESS (tests, PMD, SpotBugs, JaCoCo gate).
- src/it and src/features not touched; they still name the old text (Tester/Analyst update).
