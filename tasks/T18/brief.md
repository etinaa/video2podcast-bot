# T18 brief

Owner's request: "Начать T18". The draft came from the owner during T9: keep a job in a dead-letter queue when Whisper or Ollama is down and finish it automatically after the service is back.

Q: Task type? A: Product (Recommended) (full team).
Q: How long to wait for the service before giving up? A: 12 attempts, about 24 hours (Recommended); configurable.
Q: Where does a retry start? A: From scratch (Recommended): only the URL and the owner data are stored, working files are deleted at once.
Q: Where does the queue live? A: A file in the data directory (Recommended): `deferred-jobs.json` next to `allowed-users.json`.
Defaults confirmed with the whole task ("yes"): user message texts, a non-outage failure on a retry closes the entry, an entry of a user who lost access is dropped silently.

Agreed task: see T18 in `tasks/todo.md`.
