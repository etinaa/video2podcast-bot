VERDICT: APPROVED

## Findings
- [Suggestion] tasks/T8/api.md:73, tasks/T8/flows.md:18, tasks/T8/requirements.md:67 - these T8 design documents still name the old selector `bestaudio[ext=m4a]/bestaudio` - they are the record of a finished task, so leaving them is fine, but a reader may take them for current. Optional: one line saying TD5 extended the selector.
- [Suggestion] src/main/java/com/video2podcast/media/YtDlpClient.java:90 - `/best` can download the whole video when a source has no separate audio track, so the download is larger and slower than an audio-only one - this is the owner's decision and the timeout still limits it, so nothing to change; the cost is worth a line in the TD5 notes.

## Requirements
- R1 (selector is `bestaudio[ext=m4a]/bestaudio/best`; SPEC.md says the same): met - YtDlpClient.java:90 changed, and SPEC.md:156 and the T8 feature line 162 already carry the new value.
- R2 (a combined mp4 downloads as one `source.*` file and the pipeline continues; separate-track behaviour unchanged): met - the `sourceFiles` lookup uses the glob `source.*`, so any extension is found. `bestaudio[ext=m4a]` stays first. evidence.md shows exit 0 and `source.mp4` with the new selector, and exit 1 with the old one.
- R3 (a unit test asserts the selector in the call arguments): met - YtDlpClientTest.java:296 asserts the full argument list with the new selector.
- Scope: the patch has two one-line changes and nothing else. The T8, T9 and TD2 feature files and the other working-tree changes are TD4 and were not reviewed.
