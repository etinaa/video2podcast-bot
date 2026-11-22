@T12
Feature: Download a retained transcript once from its summary button
  Requirement tags refer to tasks/T12/requirements.md.
  Fixtures write job files on disk; actions enter through Telegram updates.

  Background:
    Given an allowed Telegram user with id 101
    And the application clock is "2026-11-19T12:30:00Z"
    And a published job "11111111-1111-4111-8111-111111111111" exists on disk for user 101 in private chat 101 with summary message id 701
    And the stored job title is "Budget meeting"
    And the job's AUDIO and TRANSCRIPT entries are "AVAILABLE" with expiresAt "2026-11-19T13:00:00Z" and null sentAt
    And the job directory contains "source.m4a" and UTF-8 "transcript.txt" with content "The budget meeting is on Tuesday.\n"
    And the Telegram mock accepts document uploads and keyboard edits

  @R2 @R3 @R4
  Scenario: Send the transcript and retain the audio download button
    When the user taps "transcript:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the Telegram mock receives in order an answerCallbackQuery with "Sending transcript…", a reply-markup edit with "Sending transcript…", one sendDocument and a reply-markup edit with "Transcript sent"
    And the sendDocument multipart request targets chat 101 with filename "Budget meeting.txt", the unchanged transcript bytes and no caption
    And both keyboard edits target chat 101 and message 701 with audio first and transcript second
    And both keyboard edits keep "Download audio" with callback data "audio:11111111-1111-4111-8111-111111111111"
    And both keyboard edits keep transcript callback data "transcript:11111111-1111-4111-8111-111111111111"
    And the Telegram mock receives no editMessageText or sendAudio request
    And the Whisper and Ollama mocks receive no requests
    And job.json records TRANSCRIPT state "SENT" and sentAt no earlier than the successful upload response
    And the AUDIO entry and both expiresAt values are unchanged
    And the job directory still contains "source.m4a", "transcript.txt" and "job.json"

  @R3 @R4
  Scenario: Persist sending progress while Telegram holds the upload response
    Given the Telegram mock holds the document upload response
    When the user taps "transcript:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the Telegram mock receives one sendDocument request
    And before the upload response is released job.json records TRANSCRIPT state "SENDING" and null sentAt
    And before the upload response is released the summary keyboard shows "Sending transcript…" and "Download audio"
    When the Telegram mock releases the successful upload response
    Then job.json records TRANSCRIPT state "SENT" and nonnull sentAt

  @R3
  Scenario: A second tap after successful delivery does not upload again
    Given the user has successfully downloaded this job's transcript through a Telegram callback
    When the user taps "transcript:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the second tap produces only answerCallbackQuery with "Already sent."
    And the Telegram mock has received exactly one sendDocument request in total
    And the second tap leaves job.json unchanged

  @R3
  Scenario: A concurrent tap cannot duplicate an upload in progress
    Given the Telegram mock holds the document upload response
    And the user has tapped "transcript:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    And the Telegram mock has received the document upload and holds its response
    When a concurrent Telegram update taps "transcript:11111111-1111-4111-8111-111111111111" as user 101 on private message 701 in chat 101
    Then the concurrent tap produces only answerCallbackQuery with "Already being sent."
    And the Telegram mock has received exactly one sendDocument request in total
    When the Telegram mock releases the successful upload response
    Then job.json records TRANSCRIPT state "SENT" and nonnull sentAt
    And the Telegram mock has received exactly one sendDocument request in total

  @R1
  Scenario Outline: Another allowed user cannot take the owner's transcript
    Given Telegram user <userId> is allowed and <role>
    When Telegram user <userId> taps "transcript:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the tap produces only answerCallbackQuery with "This button is not for you."
    And the job files are unchanged
    Examples:
      | userId | role                           |
      | 202    | is not the bot administrator   |
      | 999    | is the bot administrator       |

  @R1
  Scenario: Revoked access prevents delivery of the user's own job
    Given user 101 has been removed from the allowlist and is not the bot administrator
    When the user taps "transcript:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the tap produces only answerCallbackQuery with "No access."
    And the job files are unchanged

  @R1
  Scenario Outline: Ignore delivery callbacks outside private chats
    When user 101 sends a Telegram callback with data "transcript:11111111-1111-4111-8111-111111111111" from a <chatType> chat
    Then the bot sends nothing
    And the job files are unchanged
    Examples:
      | chatType   |
      | group      |
      | supergroup |
      | channel    |

  @R1 @R2 @R4
  Scenario: Callback envelope cannot redirect the stored delivery destination
    When the user taps "transcript:11111111-1111-4111-8111-111111111111" on private message 702 in chat 303
    Then the Telegram mock receives one sendDocument targeting stored chat 101
    And the reply-markup edits target stored chat 101 and message 701
    And the Telegram mock receives no document upload or message edit targeting chat 303

  @R5
  Scenario Outline: Enforce the stored expiry at the tap boundary
    Given the TRANSCRIPT expiresAt in job.json is "<expiresAt>"
    When the user taps "transcript:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the tap produces <outcome>
    Examples:
      | expiresAt                      | outcome                                                               |
      | 2026-11-19T12:30:00.000000001Z   | one successful sendDocument and persisted TRANSCRIPT state SENT         |
      | 2026-11-19T12:30:00Z             | only answerCallbackQuery with text Files already downloaded or expired, please resend the link.    |
      | 2026-11-19T12:29:59Z             | only answerCallbackQuery with text Files already downloaded or expired, please resend the link.    |

  @R5
  Scenario Outline: Missing or unusable retained content reports expiry
    Given <missingContent>
    When the user taps "transcript:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the tap produces only answerCallbackQuery with "Files already downloaded or expired, please resend the link."
    And the tap leaves all remaining job files unchanged
    Examples:
      | missingContent                                      |
      | the job directory is absent                          |
      | job.json is absent                                   |
      | job.json contains invalid JSON                       |
      | job.json has no TRANSCRIPT entry                     |
      | the TRANSCRIPT state in job.json is EXPIRED          |

  @R5
  Scenario Outline: Reject invalid callback job ids without accessing other files
    Given a sentinel file outside the job directory contains "keep me"
    When the user taps "<callbackData>" on private message 701 in chat 101
    Then the tap produces only answerCallbackQuery with "Files already downloaded or expired, please resend the link."
    And the job files and sentinel file are unchanged
    Examples:
      | callbackData                                                |
      | transcript:                                                 |
      | transcript:../sentinel                                       |
      | transcript:/tmp/sentinel                                    |
      | transcript:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa         |
      | transcript:22222222-2222-4222-8222-222222222222               |

  @R6
  Scenario: A rejected upload allows an explicit manual retry
    Given the Telegram mock rejects the first sendDocument with HTTP 400 and a Bot API error response
    When the user taps "transcript:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the Telegram mock receives one sendDocument attempt without an automatic retry
    And job.json records TRANSCRIPT state "AVAILABLE" and null sentAt
    And the summary keyboard is restored to "Download transcript" with "Download audio" unchanged
    And the Telegram mock receives a sendMessage to chat 101 with "Could not send the transcript. Tap the button to try again."
    And the job directory still contains "source.m4a", "transcript.txt" and "job.json"
    When the user taps "transcript:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101 again
    Then the Telegram mock has received exactly two sendDocument attempts in total with one successful response
    And job.json records TRANSCRIPT state "SENT" and nonnull sentAt

  @R6
  Scenario: A dropped upload response does not trigger an automatic retry
    Given the Telegram mock accepts the upload bytes but closes the connection without a response on every upload attempt
    When the user taps "transcript:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the Telegram mock receives one sendDocument attempt without an automatic retry
    And job.json records TRANSCRIPT state "AVAILABLE" and null sentAt
    And the Telegram mock receives a sendMessage to chat 101 with "Could not send the transcript. Tap the button to try again."
    And no Telegram message contains the transport diagnostic or a filesystem path

  @R7
  Scenario: A rejected callback answer does not prevent the upload
    Given the Telegram mock rejects answerCallbackQuery with HTTP 400 and a Bot API error response
    When the user taps "transcript:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the Telegram mock receives one answerCallbackQuery attempt without retry
    And the Telegram mock receives one successful sendDocument
    And job.json records TRANSCRIPT state "SENT" and nonnull sentAt

  @R7
  Scenario Outline: Keyboard failure cannot cause duplicate document delivery
    Given the Telegram mock rejects the <edit> reply-markup edit with HTTP 400 and a Bot API error response
    When the user taps "transcript:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the Telegram mock receives one successful sendDocument
    And job.json records TRANSCRIPT state "SENT" and nonnull sentAt
    When the user taps "transcript:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101 again
    Then the second tap produces only answerCallbackQuery with "Already sent."
    And the Telegram mock has received exactly one sendDocument request in total
    Examples:
      | edit     |
      | progress |
      | final    |

  @R2
  Scenario Outline: Derive a safe document filename from the original stored title
    Given the stored job title is "<title>"
    When the user taps "transcript:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the sendDocument multipart filename is "<filename>"
    And the stored title and transcript bytes are unchanged
    Examples:
      | title               | filename             |
      | Budget meeting      | Budget meeting.txt   |
      |   Budget meeting    | Budget meeting.txt   |
      | Q4/Plan: v1?         | Q4_Plan_ v1_.txt      |
      | R&D <notes>          | R&D _notes_.txt       |
      |                     | transcript.txt       |

  @R2
  Scenario: Bound a long upload filename independently of the stored title
    Given the stored job title contains 120 copies of "A"
    When the user taps "transcript:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the sendDocument multipart filename is 100 copies of "A" followed by ".txt"
    And job.json retains the original 120-character title

  @R4
  Scenario Outline: Preserve the stored audio state when updating transcript buttons
    Given the AUDIO state in job.json is "<state>"
    When the user taps "transcript:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then both reply-markup edits retain the audio button label "<label>" and its original callback data
    And the AUDIO entry is unchanged
    Examples:
      | state   | label            |
      | SENDING | Preparing audio… |
      | SENT    | Audio sent       |
      | EXPIRED | Audio expired    |

  @R4
  Scenario: A missing summary message id does not prevent transcript delivery
    Given summaryMessageId in job.json is null
    When the user taps "transcript:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the Telegram mock receives one successful sendDocument
    And the Telegram mock receives no reply-markup edit
    And job.json records TRANSCRIPT state "SENT" and nonnull sentAt

  @R8 @R3
  Scenario Outline: Honor retained state after application startup
    Given the TRANSCRIPT state in job.json is "<state>" before application startup
    And the application starts with the existing job directory
    When the user taps "transcript:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the tap produces <outcome>
    And the Whisper and Ollama mocks receive no requests
    Examples:
      | state     | outcome                                                               |
      | AVAILABLE | one successful sendDocument and persisted TRANSCRIPT state SENT         |
      | SENT      | only answerCallbackQuery with text Already sent.                        |
      | SENDING   | one successful sendDocument and persisted TRANSCRIPT state SENT         |

  @R8 @R5 @R3
  Scenario Outline: Elapsed expiry does not reset an existing terminal or in-progress state
    Given the TRANSCRIPT state in job.json is "<state>"
    And the TRANSCRIPT expiresAt in job.json is "2026-11-19T12:00:00Z"
    When the user taps "transcript:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the tap produces only answerCallbackQuery with "<notice>"
    And the job files are unchanged
    Examples:
      | state   | notice              |
      | SENT    | Already sent.       |
      | SENDING | Already being sent. |

  @R5
  Scenario: A missing transcript file expires its available entry
    Given transcript.txt is absent
    When the user taps "transcript:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the tap produces only answerCallbackQuery with "Files already downloaded or expired, please resend the link."
    And job.json records TRANSCRIPT state "EXPIRED" and null sentAt
    And the AUDIO entry and remaining source file are unchanged

  @R8
  Scenario: Use persisted retention rather than creation time or the default hour
    Given job.json records createdAt "2026-11-19T10:00:00Z"
    And the TRANSCRIPT expiresAt in job.json is "2026-11-19T14:00:00Z"
    When the user taps "transcript:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the Telegram mock receives one successful sendDocument
    And the TRANSCRIPT expiresAt in job.json remains "2026-11-19T14:00:00Z"
