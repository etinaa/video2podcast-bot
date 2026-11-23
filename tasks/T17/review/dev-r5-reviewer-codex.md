VERDICT: APPROVED

## Findings
- No Critical or Important findings.
- [Suggestion] docs/host-setup.md:62 - Clarify the final evidence-attribution sentence: only upload completion comes from the Telegram message time; the download estimate comes from extrapolating the earlier 16-second/152 MB download, as the preceding sentence already explains. This is a wording ambiguity, not a missing estimate qualification.

## Requirements
Requirement numbering retains my round-4 documentation scope. T17 is technical; no separate requirements or Architect documents exist.

- R1: met - The existing `-mc 0` guidance remains; forced English and non-English input being outside SPEC scope are correctly documented. The language paragraph no longer treats word count as proof of completeness.
- R2: met - Compression now correctly says about 3 minutes; download is explicitly an estimate, upload completion is attributed to Telegram, and memory values distinguish the two runs. The near-three-hour sampled peaks are Java 673 MiB, ffmpeg 68 MiB and whisper-server 325 MiB. Earlier English-run Java peaks at 186 MiB.
- R3: met - The retry sentence now agrees with the new row: the 37-to-29 kbps retry ran on the 2 h 58 min video. Its retained mono AAC file is 40,222,038 bytes, 10,700.010522 seconds, and 30,072 bit/s (30.1 kbps rounded). First-attempt size is explicitly unrecorded; receipt without a size error is attributed to the owner. The earlier 51,933,907-byte file remains between the two candidate Telegram limits.
- R4: met - Tuning/model settings remain recorded, and TOO_LONG at 13:24:26.288 agrees with the log and metadata-before-download validation. This approval covers the documentation patch, not final owner acceptance of T17 or certification of all 15 SPEC success criteria; the Russian run does not establish full English transcript coverage.

## Review evidence
- Reviewed the fixed patch and the entire touched document. Reconstructed the post-image from base blob `02fe32d`; its hash is `9da096e5a857a3e805927c80914f68cbd513c101`, matching the patch and inspected document.
- Rechecked both round-4 reports as explicitly requested. Compression timing, evidence qualification, Java peak/units and earlier-run labeling, repetition count, transcript-completeness caveat, retry contradiction, first-output-size qualification and owner attribution are addressed.
- Read `/tmp/v2p/mem2.log`, `/tmp/v2p/mem.sh` and `/tmp/v2p/bot.log`; checked both `manual_tests/*3h*` artifacts with file statistics, text counting and ffprobe. RSS is KiB divided by 1024 in the sampling script, hence MiB. Peaks above are scoped to the Russian run, not the entire log.
- Compression is sampled from 13:44:47 through 13:47:34 and absent at 13:47:39: approximately 2 min 50 s. CPU/RSS changes around 13:45:58–13:46:03 support the second encoding attempt; AudioPreparer computes 37 kbps for 10,700 s and retries at 29 kbps. The log does not contain encoder command arguments or first-output size, so those rates are corroborated through the code, not directly printed in the samples.
- Whisper activity spans approximately 13:29:23–13:35:52 (the documented inner timestamps 13:29:38–13:35:47 are present); approximately 6 minutes of transcription and 9 minutes until compression are consistent with sampling precision. Download duration and Telegram upload completion cannot be independently established from these process samples or bot.log; the document identifies their separate basis.
- Transcript has exactly 30,855 whitespace-delimited words, seven consecutive instances of the quoted full sentence and fourteen occurrences of “new house”. English language request, 600-second chunking, bitrate retry logic/tests and duration rejection order were checked against source/configuration. The retained earlier English audio also matches 51,933,907 bytes and approximately 44.2 kbps.
- Reviewed correctness, readability, architecture, security and performance within this docs-only scope. No new runtime behavior or source/test change. `git diff --check -- docs/host-setup.md` passed. No Maven command or source mutation was run. Historical measurements outside this patch are not newly certified by these later-run artifacts.
