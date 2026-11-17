@T9
Feature: Transcribe ordered WAV chunks into one plain-text transcript
  These component contract examples describe HTTP calls, callback events and files.
  T9 has no new functional IT tests; exclude @T9 from Cucumber until later wiring.
  In JSON examples escapes represent the characters decoded from the response.
  Transcript doc strings omit the separately asserted single final LF newline.

  Background:
    Given a fresh transcription job directory with unchanged "source.m4a" and "audio.wav"
    And no "transcript.txt" exists in the job directory
    And the Whisper base URL points to a controlled transcription server

  @R1 @R2
  Scenario: Send WAV bytes with the explicit English JSON contract
    Given the job has the ordered WAV chunks "chunk-000.wav"
    And Whisper returns the T3 fixture "whisper-inference-ok.json" with status 200
    When transcription runs for the job
    Then the server receives 1 POST request to "/inference"
    And the request is multipart with "file" containing the bytes of "chunk-000.wav"
    And the request has multipart fields "response_format" equal to "json" and "language" equal to "en"
    And "transcript.txt" contains exactly this UTF-8 text followed by one LF newline:
      """
      Hello, this is a short test of the video to podcast but the meeting is on Tuesday at 10 o'clock, and we will discuss the budget.
      """

  @R1 @R2
  Scenario: Merge all chunks in the supplied playback order
    Given the job has the ordered WAV chunks "chunk-000.wav, chunk-001.wav, chunk-002.wav"
    And Whisper returns these JSON responses in order:
      | response                  |
      | {"text":"First part."}    |
      | {"text":"Second part."}   |
      | {"text":"Final part."}    |
    When transcription runs for the job
    Then the server receives chunks in order "chunk-000.wav, chunk-001.wav, chunk-002.wav"
    And at most 1 chunk request is in flight at a time
    And "transcript.txt" contains exactly this UTF-8 text followed by one LF newline:
      """
      First part. Second part. Final part.
      """

  @R1 @R2
  Scenario: Include every chunk of a video at the three-hour limit
    Given the job has 18 ordered WAV chunks for a 10800-second video accepted by media preparation
    And Whisper returns text "part-00" through "part-17" for the corresponding chunks
    When transcription runs for the job
    Then all 18 chunks are sent once in playback order
    And "transcript.txt" contains "part-00" through "part-17" in order separated by single spaces and followed by one LF newline

  @R1 @R2
  Scenario: Include the short final chunk
    Given the job has ordered WAV chunks of approximately 600, 600 and 50 seconds
    And Whisper returns text "First", "Second" and "Remainder" for those chunks
    When transcription runs for the job
    Then "transcript.txt" contains exactly this UTF-8 text followed by one LF newline:
      """
      First Second Remainder
      """

  @R2
  Scenario: Collapse segment whitespace and preserve Unicode and punctuation
    Given the job has the ordered WAV chunks "chunk-000.wav, chunk-001.wav"
    And Whisper returns these JSON responses in order:
      | response                                          |
      | {"text":"  Café\n\tpart.  "}                       |
      | {"text":" Second\r\npart — it's ready! ","id":7}  |
    When transcription runs for the job
    Then "transcript.txt" contains exactly this UTF-8 text followed by one LF newline:
      """
      Café part. Second part — it's ready!
      """

  @R2 @R8
  Scenario: Do not publish or clean up while a later answer is pending
    Given the job has the ordered WAV chunks "chunk-000.wav, chunk-001.wav"
    And Whisper returns JSON {"text":"First"} for "chunk-000.wav"
    And the answer for "chunk-001.wav" is held within its answer timeout
    When transcription has sent "chunk-001.wav" and is waiting for its answer
    Then no "transcript.txt" exists in the job directory
    And "source.m4a", "audio.wav" and every chunk remain unchanged

  @R3 @R6
  Scenario: Skip empty speech without retry or extra separators
    Given the job has the ordered WAV chunks "chunk-000.wav, chunk-001.wav, chunk-002.wav, chunk-003.wav"
    And Whisper returns these JSON responses in order:
      | response                 |
      | {"text":""}              |
      | {"text":" First "}       |
      | {"text":" \t\r\n "}      |
      | {"text":" Last "}        |
    When transcription runs for the job
    Then each chunk is sent exactly once
    And the progress callback events are "1/4, 2/4, 3/4, 4/4" in that order
    And "transcript.txt" contains exactly this UTF-8 text followed by one LF newline:
      """
      First Last
      """

  @R3 @R7 @R8
  Scenario Outline: Entirely empty speech fails without deleting audio
    Given the job has the ordered WAV chunks "chunk-000.wav, chunk-001.wav"
    And Whisper returns <response> with status 200 for both chunks
    When transcription runs for the job
    Then transcription fails with kind "NO_SPEECH"
    And the safe failure message is "No speech was found in this video."
    And each chunk is sent exactly once
    And no "transcript.txt" exists in the job directory
    And "source.m4a", "audio.wav" and every chunk remain unchanged

    Examples:
      | response              |
      | {"text":""}           |
      | {"text":" \t\r\n "}   |

  @R3 @R4
  Scenario Outline: Broken successful responses are retried rather than called silence
    Given the job has the ordered WAV chunks "chunk-000.wav"
    And Whisper returns status 200 with body <body> on the first attempt
    And Whisper returns JSON {"text":"Recovered"} on the retry
    When transcription runs for the job
    Then "chunk-000.wav" is sent exactly twice
    And "transcript.txt" contains exactly this UTF-8 text followed by one LF newline:
      """
      Recovered
      """

    Examples:
      | body                |
      | {broken             |
      | {}                  |
      | {"text":null}       |
      | {"text":42}         |
      | {"text":true}       |
      | {"text":[]}         |
      | {"text":{}}         |
      | []                  |
      | an empty body       |

  @R4
  Scenario Outline: Recover a failed chunk without resending earlier work
    Given the job has the ordered WAV chunks "chunk-000.wav, chunk-001.wav, chunk-002.wav"
    And Whisper returns JSON {"text":"First"} for "chunk-000.wav"
    And the first attempt for "chunk-001.wav" fails with <failure>
    And Whisper returns JSON {"text":"Second"} on its retry
    And Whisper returns JSON {"text":"Last"} for "chunk-002.wav"
    When transcription runs for the job
    Then the chunk attempt order is "chunk-000.wav, chunk-001.wav, chunk-001.wav, chunk-002.wav"
    And the retry is made without a scheduled delay
    And every attempt uses the same multipart English JSON contract and corresponding chunk bytes
    And "transcript.txt" contains exactly this UTF-8 text followed by one LF newline:
      """
      First Second Last
      """

    Examples:
      | failure                                                  |
      | a connection refusal                                     |
      | status 400 with T3 fixture whisper-inference-bad-request.txt |
      | status 302 without a usable transcription response        |
      | status 500 with body {"text":"not speech"}                |
      | status 200 with malformed JSON                            |
      | an answer timeout                                        |

  @R3 @R4
  Scenario: A valid empty answer on retry continues to the next chunk
    Given the job has the ordered WAV chunks "chunk-000.wav, chunk-001.wav"
    And the first attempt for "chunk-000.wav" fails with status 400
    And Whisper returns JSON {"text":" "} on its retry
    And Whisper returns JSON {"text":"Speech"} for "chunk-001.wav"
    When transcription runs for the job
    Then the chunk attempt order is "chunk-000.wav, chunk-000.wav, chunk-001.wav"
    And "transcript.txt" contains exactly this UTF-8 text followed by one LF newline:
      """
      Speech
      """

  @R4 @R7 @R8
  Scenario Outline: The retry failure determines the final kind and stops later chunks
    Given the job has the ordered WAV chunks "chunk-000.wav, chunk-001.wav, chunk-002.wav"
    And Whisper returns JSON {"text":"Earlier success"} for "chunk-000.wav"
    And the first attempt for "chunk-001.wav" fails with <first_failure>
    And its retry fails with <retry_failure>
    When transcription runs for the job
    Then transcription fails with kind "<kind>"
    And the safe failure message is "<message>"
    And the chunk attempt order is "chunk-000.wav, chunk-001.wav, chunk-001.wav"
    And "chunk-002.wav" is never sent
    And no "transcript.txt" exists in the job directory
    And "source.m4a", "audio.wav" and every chunk remain unchanged

    Examples:
      | first_failure      | retry_failure           | kind                | message                                        |
      | connection refusal | connection refusal      | SERVICE_UNREACHABLE | The transcription server is unreachable.       |
      | status 400         | connection refusal      | SERVICE_UNREACHABLE | The transcription server is unreachable.       |
      | connection refusal | status 400              | FAILED              | Transcription failed. Please try again later.  |
      | status 400         | status 400              | FAILED              | Transcription failed. Please try again later.  |
      | status 503         | status 503              | FAILED              | Transcription failed. Please try again later.  |
      | malformed JSON     | missing text field      | FAILED              | Transcription failed. Please try again later.  |
      | answer timeout     | answer timeout          | FAILED              | Transcription failed. Please try again later.  |
      | status 204 no body | status 204 no body       | FAILED              | Transcription failed. Please try again later.  |

  @R5
  Scenario Outline: Bind the per-attempt answer timeout
    Given the Whisper environment configuration is <configuration>
    When transcription request settings are created
    Then the answer timeout for each attempt is <answer_wait>
    And the connection timeout is 5 seconds

    Examples:
      | configuration           | answer_wait |
      | WHISPER_TIMEOUT unset   | 10 minutes  |
      | WHISPER_TIMEOUT=PT2S    | 2 seconds   |

  @R4 @R5 @R7
  Scenario: Enforce the configured answer timeout on a connected server
    Given the job has the ordered WAV chunks "chunk-000.wav"
    And WHISPER_TIMEOUT is "PT0.1S"
    And Whisper accepts each connection but delays each answer for 2 seconds
    When transcription runs for the job
    Then each attempt ends on the configured answer timeout before its delayed answer
    And "chunk-000.wav" is attempted exactly twice
    And transcription fails with kind "FAILED"
    And the safe failure message is "Transcription failed. Please try again later."

  @R4 @R5 @R7
  Scenario: Bound a connection that cannot be established
    Given the job has the ordered WAV chunks "chunk-000.wav"
    And the controlled transport cannot establish a connection before the 5-second connection deadline on either attempt
    When transcription runs for the job
    Then each connection attempt ends at its connection deadline
    And exactly 2 connection attempts are made
    And transcription fails with kind "SERVICE_UNREACHABLE"
    And the safe failure message is "The transcription server is unreachable."

  @R4 @R5
  Scenario: Each attempt receives a fresh answer wait
    Given the job has the ordered WAV chunks "chunk-000.wav, chunk-001.wav"
    And WHISPER_TIMEOUT is "PT1S"
    And the first attempt for "chunk-000.wav" exceeds the answer timeout
    And its retry returns JSON {"text":"First"} after 700 milliseconds
    And "chunk-001.wav" returns JSON {"text":"Last"} after 700 milliseconds
    When transcription runs for the job
    Then transcription succeeds despite total elapsed time exceeding 1 second
    And the chunk attempt order is "chunk-000.wav, chunk-000.wav, chunk-001.wav"

  @R6
  Scenario: Report each chunk before its first request but not again on retry
    Given the job has the ordered WAV chunks "chunk-000.wav, chunk-001.wav, chunk-002.wav"
    And "chunk-001.wav" fails once and then succeeds on retry
    And all other chunk answers contain nonempty text
    When transcription runs for the job
    Then the progress callback events are "1/3, 2/3, 3/3" in that order
    And each progress event occurs before that chunk's first request
    And no progress event occurs for a retry

  @R4 @R6
  Scenario: Stop progress reporting at the terminally failing chunk
    Given the job has the ordered WAV chunks "chunk-000.wav, chunk-001.wav, chunk-002.wav"
    And "chunk-000.wav" succeeds with nonempty text
    And both attempts for "chunk-001.wav" fail with status 500
    When transcription runs for the job
    Then the progress callback events are "1/3, 2/3" in that order
    And no progress event occurs for chunk 3

  @R7
  Scenario: Raw server diagnostics do not enter the safe failure message
    Given the job has the ordered WAV chunks "chunk-000.wav"
    And both attempts return status 500 with body "Internal error /private/jobs/secret.wav https://example.com/private-video"
    When transcription runs for the job
    Then transcription fails with kind "FAILED"
    And the safe failure message is "Transcription failed. Please try again later."

  @R2 @R8
  Scenario: Remove temporary audio after persisting a complete transcript
    Given the job has the ordered WAV chunks "chunk-000.wav, chunk-001.wav"
    And the job directory also contains unchanged "job.json"
    And Whisper returns nonempty text for both chunks
    When transcription runs for the job
    Then "transcript.txt" is readable and complete before any temporary audio deletion starts
    And "audio.wav" does not exist in the job directory
    And the "chunks" directory does not exist in the job directory
    And "source.m4a" and "job.json" remain unchanged

  @R8
  Scenario: A failed transcript write does not trigger temporary audio deletion
    Given the job has the ordered WAV chunks "chunk-000.wav"
    And Whisper returns JSON {"text":"Complete speech"}
    And writing the transcript is forced to fail before any transcript bytes are written
    When transcription runs for the job
    Then transcription does not report success
    And no "transcript.txt" exists in the job directory
    And "source.m4a", "audio.wav" and every chunk remain unchanged

  @R2
  Scenario: Collapse nonbreaking whitespace between words
    Given the job has the ordered WAV chunks "chunk-000.wav"
    And Whisper returns JSON {"text":"\u00a0First\u00a0\u00a0part.\u00a0"}
    When transcription runs for the job
    Then "transcript.txt" contains exactly this UTF-8 text followed by one LF newline:
      """
      First part.
      """

  @R7 @R8
  Scenario: A failed atomic replacement leaves no partial final transcript
    Given the job has the ordered WAV chunks "chunk-000.wav"
    And Whisper returns JSON {"text":"Complete speech"}
    And atomic transcript persistence is forced to fail after temporary output is written but before replacement
    When transcription runs for the job
    Then transcription fails with kind "FAILED"
    And the safe failure message is "Transcription failed. Please try again later."
    And no "transcript.txt" exists in the job directory
    And "source.m4a", "audio.wav" and every chunk remain unchanged

  @R8
  Scenario Outline: Cleanup errors after persistence preserve success and permit remaining deletions
    Given the job has the ordered WAV chunks "chunk-000.wav, chunk-001.wav"
    And Whisper returns nonempty text for both chunks
    And deletion of "<path>" is forced to fail
    And all other possible temporary file deletions can succeed
    When transcription runs for the job
    Then transcription succeeds with a complete readable "transcript.txt"
    And a WARN log identifies the failed deletion by file name without file content
    And every remaining temporary deletion is attempted
    And successfully deleted files are not restored
    And "source.m4a" remains unchanged

    Examples:
      | path                  |
      | audio.wav             |
      | chunks/chunk-000.wav  |
      | chunks                |

  @R7 @R9
  Scenario Outline: Reject invalid job inputs before making HTTP requests
    Given the transcription input has <invalid_input>
    When transcription runs with that input
    Then transcription fails with kind "FAILED"
    And the safe failure message is "Transcription failed. Please try again later."
    And no transcription request is sent
    And no transcript is written or temporary audio deleted

    Examples:
      | invalid_input                    |
      | an empty chunk list              |
      | a job directory that is missing  |
      | a job directory that is a file   |

  @R9
  Scenario Outline: Null arguments are caller programming errors
    Given the transcription argument "<argument>" is null
    When transcription runs with that input
    Then the caller receives a NullPointerException

    Examples:
      | argument |
      | jobDir   |
      | chunks   |
      | progress |

  @R4 @R7 @R9
  Scenario Outline: An unavailable chunk fails locally without misclassifying the server
    Given the job has the ordered WAV chunks "chunk-000.wav, chunk-001.wav"
    And "chunk-000.wav" is <condition>
    When transcription runs for the job
    Then reading "chunk-000.wav" is attempted twice without a scheduled delay
    And transcription fails with kind "FAILED"
    And the safe failure message is "Transcription failed. Please try again later."
    And "chunk-001.wav" is never sent
    And no transcript is written or temporary audio deleted

    Examples:
      | condition  |
      | missing    |
      | unreadable |

  @R1 @R9
  Scenario: Supplied chunk order wins over filename order
    Given the job has the ordered WAV chunks "chunk-001.wav, chunk-000.wav"
    And Whisper returns text "First supplied" and "Second supplied" for those chunks
    When transcription runs for the job
    Then the server receives chunks in order "chunk-001.wav, chunk-000.wav"
    And "transcript.txt" contains exactly this UTF-8 text followed by one LF newline:
      """
      First supplied Second supplied
      """

  @R1 @R9
  Scenario: Accept a supplied chunk outside the usual chunks directory
    Given the supplied chunk list contains one readable WAV in a separate temporary directory
    And Whisper returns JSON {"text":"Speech"}
    When transcription runs for the job
    Then the server receives the bytes of the supplied WAV
    And transcription succeeds with a complete readable "transcript.txt"

  @R2 @R9
  Scenario: Replace an existing transcript on success without resuming it
    Given the job has the ordered WAV chunks "chunk-000.wav"
    And "transcript.txt" already contains "Old text" followed by one LF newline
    And Whisper returns JSON {"text":"New text"}
    When transcription runs for the job
    Then "chunk-000.wav" is sent exactly once
    And "transcript.txt" contains exactly this UTF-8 text followed by one LF newline:
      """
      New text
      """

  @R8 @R9
  Scenario Outline: A failed run preserves an existing transcript
    Given the job has the ordered WAV chunks "chunk-000.wav"
    And "transcript.txt" already contains "Old text" followed by one LF newline
    And the run will end with <failure>
    When transcription runs for the job
    Then transcription does not report success
    And the bytes of "transcript.txt" are unchanged
    And "source.m4a", "audio.wav" and every chunk remain unchanged

    Examples:
      | failure                                           |
      | two failed chunk attempts                          |
      | NO_SPEECH                                          |
      | an atomic write failure before replacement          |

  @R6 @R9
  Scenario: A failed progress callback stops before sending its chunk
    Given the job has the ordered WAV chunks "chunk-000.wav, chunk-001.wav"
    And Whisper returns JSON {"text":"First"} for "chunk-000.wav"
    And the callback throws an IllegalStateException "callback failed" for progress "2/2"
    When transcription runs for the job
    Then the original callback exception reaches the caller unchanged
    And "chunk-001.wav" is never sent
    And no "transcript.txt" exists in the job directory
    And "source.m4a", "audio.wav" and every chunk remain unchanged
