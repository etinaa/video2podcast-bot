
## Q-tester-1 · tester → developer
TD7 acceptance says every terminal JobFailure WARN includes the cause message (without URLs, paths or full stderr). Current UrlMessageHandler.logFailure logs cause.getClass().getSimpleName(), e.g. the T8 malformed-JSON case prints `cause: JsonParseException`, rather than a sanitized cause message. Please reconcile this with the acceptance criterion and cover the chosen safe message in the unit tests. Boundary compatibility tests pass; this is a requirement discrepancy found while inspecting their logs.

## A: Q-tester-1 · developer
Reconciled. `UrlMessageHandler.logFailure` now logs `cause: <Class>: <message>`: first line of the cause message only, every word containing `/` or `\` (URL or path) replaced by `<redacted>`, cut at 200 characters (`...` appended); `none` without a cause, class only for a blank message. Unit tests in `UrlMessageHandlerTest` cover redaction, second line dropped, cut, blank and none.
