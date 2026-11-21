@T14
Feature: Reclaim retained job files and recover downloads after restart
  Requirement tags refer to tasks/T14/requirements.md.
  Scheduled actions wait for the real application schedule; they never call cleanup code.
  Startup fixtures are written before application startup, not while a live pipeline owns them.

  Background:
    Given an allowed Telegram user with id 101
    And the application clock is "2026-11-21T12:00:00Z"
    And a published job "11111111-1111-4111-8111-111111111111" exists on disk for user 101 in private chat 101 with summary message id 701
    And the stored job title is "Budget meeting"
    And the job's AUDIO and TRANSCRIPT entries are "AVAILABLE" with expiresAt "2026-11-21T13:00:00Z" and null sentAt
    And the job directory contains "source.m4a" and UTF-8 "transcript.txt" with content "The budget meeting is on Tuesday.\n"
    And the Telegram mock accepts audio uploads, document uploads and keyboard edits

  @R1 @R2 @R3 @R7
  Scenario: An idle bot removes expired files on its minute schedule
    Given the job directory also contains "out.m4a" and "chunks/chunk-000.wav"
    And the application clock is "2026-11-21T13:00:00Z"
    When the next scheduled minute elapses without any Telegram update
    Then the job directory no longer exists
    And the Telegram mock receives a reply-markup edit targeting chat 101 and message 701 with "Audio expired" and "Transcript expired"
    And the edit keeps audio first and transcript second with the original callback data
    And the Telegram mock receives no sendAudio, sendDocument, sendMessage, editMessageText or deleteMessage
    And the Whisper and Ollama mocks receive no requests

  @R2
  Scenario: One instant before expiry neither retained file is removed
    Given the application clock is "2026-11-21T12:59:59.999999999Z"
    When the next scheduled minute elapses without any Telegram update
    Then the job directory still contains "source.m4a", "transcript.txt" and "job.json"
    And the job files are unchanged
    And the Telegram mock receives no editMessageReplyMarkup

  @R2 @R7
  Scenario Outline: Expire one file while its sibling has a later deadline
    Given the <expiredKind> expiresAt in job.json is "2026-11-21T12:30:00Z"
    And the application clock is "2026-11-21T12:30:00Z"
    When the next scheduled minute elapses without any Telegram update
    Then "<expiredPath>" does not exist in the job directory
    And job.json records <expiredKind> state "EXPIRED" and null sentAt
    And the <keptKind> entry and its retained file bytes are unchanged
    And the job directory still contains "job.json" and "<keptPath>"
    And the Telegram mock receives a reply-markup edit targeting chat 101 and message 701 with "<audioLabel>" and "<transcriptLabel>"
    Examples:
      | expiredKind | expiredPath    | keptKind   | keptPath       | audioLabel    | transcriptLabel     |
      | AUDIO       | source.m4a     | TRANSCRIPT | transcript.txt | Audio expired | Download transcript |
      | TRANSCRIPT  | transcript.txt | AUDIO      | source.m4a     | Download audio| Transcript expired  |

  @R2 @R3
  Scenario: A long-running pipeline does not consume post-publication retention
    Given the job createdAt in job.json is "2026-11-21T08:00:00Z"
    And the application clock is "2026-11-21T12:30:00Z"
    When the next scheduled minute elapses without any Telegram update
    Then the job files are unchanged
    And the Telegram mock receives no editMessageReplyMarkup

  @R2 @R5
  Scenario: A configured shorter retention is not renewed at restart
    Given the job's AUDIO and TRANSCRIPT entries are "AVAILABLE" with expiresAt "2026-11-21T12:15:00Z" and null sentAt
    And the application clock is "2026-11-21T12:10:00Z"
    When the application restarts using the same work directory and access store
    Then both expiresAt values in job.json remain "2026-11-21T12:15:00Z"
    When the application clock is "2026-11-21T12:15:00Z" and the next scheduled minute elapses
    Then the job directory no longer exists

  @R2 @R3 @R7
  Scenario Outline: A sent file keeps its label when the remaining download expires
    Given job.json records <sentKind> state "SENT" and sentAt "2026-11-21T12:10:00Z"
    And the application clock is "2026-11-21T13:00:01Z"
    When the next scheduled minute elapses without any Telegram update
    Then the job directory no longer exists
    And the Telegram mock receives a reply-markup edit targeting chat 101 and message 701 with "<audioLabel>" and "<transcriptLabel>"
    And the Telegram mock receives no deleteMessage, sendAudio or sendDocument
    Examples:
      | sentKind   | audioLabel    | transcriptLabel    |
      | AUDIO      | Audio sent    | Transcript expired |
      | TRANSCRIPT | Audio expired | Transcript sent    |

  @R3
  Scenario Outline: Partial delivery preserves the unexpired download
    Given job.json records <sentKind> state "SENT" and sentAt "2026-11-21T12:10:00Z"
    And the application clock is "2026-11-21T12:30:00Z"
    When the next scheduled minute elapses without any Telegram update
    Then the job directory still contains "job.json" and "<keptPath>"
    And the <keptKind> entry and its retained file bytes are unchanged
    And job.json records <sentKind> state "SENT" and sentAt "2026-11-21T12:10:00Z"
    Examples:
      | sentKind   | keptKind   | keptPath       |
      | AUDIO      | TRANSCRIPT | transcript.txt |
      | TRANSCRIPT | AUDIO      | source.m4a     |

  @R3 @R7
  Scenario: Both successful downloads reclaim the directory before retention elapses
    Given the user has successfully downloaded this job's transcript through a Telegram callback
    And the user has successfully downloaded this job's audio through a Telegram callback
    When the next scheduled minute elapses without any Telegram update
    Then the job directory no longer exists
    And the final summary keyboard shows "Audio sent" and "Transcript sent"
    And no summary keyboard edit labels either file as expired
    And the Telegram mock has received exactly one sendAudio request and one sendDocument request in total
    And the Telegram mock receives no deleteMessage

  @R3
  Scenario Outline: Already-terminal files do not retain a directory
    Given job.json records AUDIO state "<audioState>" and TRANSCRIPT state "<transcriptState>"
    And files marked EXPIRED are already absent from the job directory
    When the next scheduled minute elapses without any Telegram update
    Then the job directory no longer exists
    Examples:
      | audioState | transcriptState |
      | SENT       | EXPIRED         |
      | EXPIRED    | SENT            |
      | EXPIRED    | EXPIRED         |

  @R3
  Scenario: Runtime cleanup leaves a live download without job.json alone
    Given another allowed user's video link is waiting for a held media-download response
    And that live job's directory contains a partial source file but no job.json
    And the application clock advances by two hours
    When the next scheduled minute elapses without any Telegram update
    Then the live job's partial source bytes remain on disk
    And its directory still exists
    And no Telegram failure message is sent for that live job

  @R3
  Scenario: Runtime cleanup leaves a live transcription alone after an hour
    Given another allowed user's earlier video link is waiting for a held Whisper HTTP response
    And the application clock advances by two hours
    When the next scheduled minute elapses without any Telegram update
    Then that live job's source and transcription input files remain on disk
    And no Telegram failure message is sent for that live job
    When the Whisper mock releases the successful response
    Then that earlier job publishes its summary with both download buttons

  @R3
  Scenario: A failed pipeline directory is removed without waiting for a minute
    Given the media-download fault fixture exits with code 1 for "https://videos.example/broken"
    When the user sends a private message with text "https://videos.example/broken"
    Then the Telegram mock receives a sendMessage with "Could not process this video. Please try again later."
    And the failed job's directory no longer exists before another scheduled minute elapses

  @R4
  Scenario Outline: A held upload protects its expired sibling and the entire directory
    Given the Telegram mock holds the <upload> upload response
    And the user has tapped "<kind>:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    And the Telegram mock has received the <upload> upload and holds its response
    And the application clock is "2026-11-21T13:00:01Z"
    When the next scheduled minute elapses without any Telegram update
    Then the job directory still contains "source.m4a", "transcript.txt" and "job.json"
    And job.json records <fileKind> state "SENDING" and null sentAt
    And the <sibling> entry remains AVAILABLE with expiresAt "2026-11-21T13:00:00Z"
    And no summary keyboard edit labels either file as expired
    When the Telegram mock releases the successful upload response
    Then job.json records <fileKind> state "SENT" and nonnull sentAt
    When the next scheduled minute elapses without any Telegram update
    Then the job directory no longer exists
    And the final summary keyboard shows "<audioLabel>" and "<transcriptLabel>"
    Examples:
      | upload   | kind       | fileKind   | sibling    | audioLabel    | transcriptLabel    |
      | document | transcript | TRANSCRIPT | AUDIO      | Audio expired | Transcript sent    |
      | audio    | audio      | AUDIO      | TRANSCRIPT | Audio sent    | Transcript expired |

  @R4
  Scenario: Audio claimed before expiry survives a minute tick while queued
    Given another allowed user's earlier video link is waiting for a held Whisper HTTP response
    And the user has tapped "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    And job.json records AUDIO state "SENDING" and null sentAt
    And the application clock is "2026-11-21T13:00:01Z"
    When the next scheduled minute elapses without any Telegram update
    Then the job directory still contains "source.m4a", "transcript.txt" and "job.json"
    And the TRANSCRIPT entry remains AVAILABLE with expiresAt "2026-11-21T13:00:00Z"
    When the Whisper mock releases the successful response and the earlier job publishes its summary
    Then the Telegram mock receives one successful sendAudio for chat 101
    And job.json records AUDIO state "SENT" and nonnull sentAt

  @R4
  Scenario: Failed send released after expiry is cleaned on the following pass
    Given the Telegram mock holds the document upload response and will reject it with HTTP 400
    And the user has tapped "transcript:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    And the Telegram mock has received the document upload and holds its response
    And the application clock is "2026-11-21T13:00:01Z"
    When the next scheduled minute elapses without any Telegram update
    Then the job directory still contains "source.m4a", "transcript.txt" and "job.json"
    When the Telegram mock releases the rejected upload response
    Then job.json records TRANSCRIPT state "AVAILABLE" and null sentAt
    When the next scheduled minute elapses without any Telegram update
    Then the job directory no longer exists
    And the Telegram mock has received exactly one sendDocument request in total

  @R1 @R5 @R7
  Scenario Outline: Startup restores a retained published download without processing the link again
    Given the <fileKind> state in job.json is "<state>"
    When the application restarts using the same work directory and access store
    Then job.json records <fileKind> state "AVAILABLE" and null sentAt
    And both expiresAt values remain "2026-11-21T13:00:00Z"
    And the retained source and transcript bytes are unchanged
    And the summary keyboard offers "Download audio" and "Download transcript" after any recovery edit
    And no automatic upload or media process occurs
    And the Whisper and Ollama mocks receive no requests
    When the user taps "<kind>:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the Telegram mock receives one successful <upload> targeting stored chat 101
    And job.json records <fileKind> state "SENT" and nonnull sentAt
    Examples:
      | fileKind   | kind       | state     | upload       |
      | AUDIO      | audio      | AVAILABLE | sendAudio    |
      | TRANSCRIPT | transcript | AVAILABLE | sendDocument |
      | AUDIO      | audio      | SENDING   | sendAudio    |
      | TRANSCRIPT | transcript | SENDING   | sendDocument |

  @R5
  Scenario: Startup resets both interrupted sends without renewing either deadline
    Given the job's AUDIO and TRANSCRIPT entries are "SENDING" with expiresAt "2026-11-21T13:00:00Z" and null sentAt
    When the application restarts using the same work directory and access store
    Then the job's AUDIO and TRANSCRIPT entries are "AVAILABLE" with expiresAt "2026-11-21T13:00:00Z" and null sentAt
    And no automatic upload or media process occurs

  @R5
  Scenario: Recovery leaves an already-sent sibling sent
    Given job.json records AUDIO state "SENT" and sentAt "2026-11-21T11:50:00Z"
    And the TRANSCRIPT state in job.json is "SENDING"
    When the application restarts using the same work directory and access store
    Then job.json records AUDIO state "SENT" and sentAt "2026-11-21T11:50:00Z"
    And job.json records TRANSCRIPT state "AVAILABLE" and null sentAt
    And the Telegram mock receives a reply-markup edit targeting chat 101 and message 701 with "Audio sent" and "Download transcript"
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the tap produces only answerCallbackQuery with "Already sent."
    And the Telegram mock receives no sendAudio request

  @R1 @R5 @R7
  Scenario Outline: Startup cleanup removes expired interrupted sends after resetting them
    Given the <fileKind> state in job.json is "SENDING"
    And the application clock is "2026-11-21T13:00:00Z"
    When the application restarts using the same work directory and access store
    Then the job directory no longer exists before the first scheduled minute elapses
    And the Telegram mock receives a reply-markup edit targeting chat 101 and message 701 with "Audio expired" and "Transcript expired"
    And no automatic upload or media process occurs
    Examples:
      | fileKind   |
      | AUDIO      |
      | TRANSCRIPT |

  @R1 @R6
  Scenario Outline: Startup removes unusable job directories without reading Telegram targets from them
    Given <invalidRecord>
    When the application restarts using the same work directory and access store
    Then the job directory no longer exists before the first scheduled minute elapses
    And the Telegram mock receives no upload or reply-markup edit for that job
    Examples:
      | invalidRecord                                               |
      | job.json is absent                                          |
      | job.json contains invalid JSON                              |
      | job.json contains null                                      |
      | job.json contains a different jobId from its directory name  |
      | job.json contains a retained filename with parent traversal  |

  @R1 @R6
  Scenario Outline: Startup with no retained work is harmless
    Given <workRoot>
    When the application restarts using the same work directory and access store
    Then no job files are created by startup cleanup
    And the Telegram mock receives no upload or expiry keyboard edit
    Examples:
      | workRoot                    |
      | the work directory is empty |
      | the work directory is absent|

  @R1 @R6
  Scenario: Repeated startup cleanup preserves the separate access store and external files
    Given job.json contains invalid JSON
    And a sentinel file outside the job directory contains "keep me"
    And the allowed-users.json file is stored outside the work directory
    When the application restarts twice using the same work directory and access store
    Then the job directory no longer exists
    And the sentinel file and allowed-users.json bytes are unchanged
    When the user sends a private message with text "/start"
    Then the Telegram mock receives the allowed-user help text

  @R2 @R7
  Scenario Outline: A callback after removal reports expiry for either original button
    Given the application clock is "2026-11-21T13:00:00Z"
    And a scheduled minute has elapsed and the job directory no longer exists
    When the user taps "<kind>:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the tap produces only answerCallbackQuery with "Expired, please resend the link."
    And the Telegram mock receives no sendAudio or sendDocument
    Examples:
      | kind       |
      | audio      |
      | transcript |

  @R7
  Scenario: Missing summary message id does not prevent removal
    Given summaryMessageId in job.json is null
    And the job's AUDIO and TRANSCRIPT entries are "EXPIRED" with expiresAt "2026-11-21T11:00:00Z" and null sentAt
    When the next scheduled minute elapses without any Telegram update
    Then the job directory no longer exists
    And the Telegram mock receives no editMessageReplyMarkup

  @R7 @R8
  Scenario: A rejected expiry edit cannot preserve expired downloads or stop other cleanup
    Given another published expired job "22222222-2222-4222-8222-222222222222" exists on disk for user 101 with summary message id 702
    And the Telegram mock rejects expiry edits for message 701 with HTTP 400 and a Bot API error response
    And the application clock is "2026-11-21T13:00:00Z"
    When the next scheduled minute elapses without any Telegram update
    Then both expired job directories no longer exist
    And the Telegram mock receives a reply-markup edit for message 702 with "Audio expired" and "Transcript expired"
    And no Telegram message contains the transport diagnostic or a filesystem path
    When the user taps "transcript:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the tap produces only answerCallbackQuery with "Expired, please resend the link."

  @R1 @R2 @R3
  Scenario: Runtime cleanup preserves an unfinished publication with null deadlines
    Given both expiresAt values in job.json are null
    And summaryMessageId in job.json is null
    And the job createdAt in job.json is "2026-11-21T08:00:00Z"
    When the next scheduled minute elapses without any Telegram update
    Then the job files are unchanged
    And the Telegram mock receives no editMessageReplyMarkup

  @R1 @R2 @R5
  Scenario Outline: Startup discards interrupted publication without a fresh retention window
    Given both expiresAt values in job.json are null
    And the job's AUDIO and TRANSCRIPT states are "<state>" with null sentAt
    When the application restarts using the same work directory and access store
    Then the job directory no longer exists before the first scheduled minute elapses
    And the Telegram mock receives a reply-markup edit targeting chat 101 and message 701 with "Audio expired" and "Transcript expired"
    And no automatic upload or media process occurs
    Examples:
      | state     |
      | AVAILABLE |
      | SENDING   |

  @R2 @R5 @R7
  Scenario: Startup expires a null-deadline file while preserving an explicitly unexpired sibling
    Given the AUDIO expiresAt in job.json is null
    When the application restarts using the same work directory and access store
    Then "source.m4a" does not exist in the job directory
    And job.json records AUDIO state "EXPIRED" and null sentAt
    And the TRANSCRIPT entry and transcript bytes are unchanged
    And the Telegram mock receives a reply-markup edit targeting chat 101 and message 701 with "Audio expired" and "Download transcript"

  @R6
  Scenario: Startup leaves directories outside the job-id naming contract alone
    Given a directory named "not.a.job" under the work directory contains "keep.txt" with content "keep me"
    When the application restarts using the same work directory and access store
    Then "not.a.job/keep.txt" under the work directory still contains "keep me"
