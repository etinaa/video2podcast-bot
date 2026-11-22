@T13
Feature: Prepare retained audio on demand and deliver it once
  Requirement tags refer to tasks/T13/requirements.md.
  Success media fixtures and conversions use real ffmpeg.
  Byte boundaries, metadata and captions follow tasks/T13/api.md and answered Q-analyst-1/2.

  Background:
    Given an allowed Telegram user with id 101
    And the application clock is "2026-11-19T12:30:00Z"
    And a published job "11111111-1111-4111-8111-111111111111" exists on disk for user 101 in private chat 101 with summary message id 701
    And the stored job title is "Budget meeting"
    And the stored job duration is 60 seconds
    And the job's AUDIO and TRANSCRIPT entries are "AVAILABLE" with expiresAt "2026-11-19T13:00:00Z" and null sentAt
    And the job directory contains a real ffmpeg-generated 60 second AAC audio-only "source.m4a" below 1000000 bytes
    And the job directory contains UTF-8 "transcript.txt" with content "The budget meeting is on Tuesday.\n"
    And the Telegram mock accepts audio uploads, document uploads, messages and keyboard edits

  @R2 @R5 @R6 @R7
  Scenario Outline: Upload a small player-compatible source without changing its bytes
    Given the retained audio source is a real ffmpeg-generated 60 second "<source>" below 1000000 bytes
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the Telegram mock receives in order an answerCallbackQuery with "Preparing audio…", a reply-markup edit with "Preparing audio…", one sendAudio and a reply-markup edit with "Audio sent"
    And the sendAudio multipart request targets chat 101 with title "Budget meeting" and the unchanged retained source bytes
    And the uploaded audio filename ends with "<extension>"
    And the sendAudio multipart performer is "Video2Podcast"
    And the sendAudio multipart caption is exactly "Original audio, not compressed."
    And job.json records AUDIO state "SENT" and sentAt "2026-11-19T12:30:00Z"
    And the TRANSCRIPT entry and transcript bytes are unchanged
    And both keyboard edits target chat 101 and message 701 with audio first and transcript second
    And both keyboard edits keep "Download transcript" with callback data "transcript:11111111-1111-4111-8111-111111111111"
    And both keyboard edits keep audio callback data "audio:11111111-1111-4111-8111-111111111111"
    And the Telegram mock receives no editMessageText, sendDocument, sendVoice or sendVideo
    And the Whisper and Ollama mocks receive no requests
    And the source bytes and both stored expiry timestamps are unchanged
    Examples:
      | source     | extension |
      | source.m4a | .m4a      |
      | source.mp3 | .mp3      |

  @R3 @R6
  Scenario Outline: Convert a small unsupported container into audio-only M4A
    Given the retained audio source is a real ffmpeg-generated 60 second "<source>" below 1000000 bytes with <streams>
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the Telegram mock receives one successful sendAudio
    And the uploaded bytes form a nonempty M4A with AAC audio and no video stream
    And the converted file on disk contains approximately 60 seconds of audio encoded at a requested 128 kbps
    And the sendAudio multipart caption is exactly "Converted to M4A, 128 kbps."
    And the sendAudio multipart performer is "Video2Podcast"
    And "out.m4a" does not exist in the job directory after delivery
    And the source bytes are unchanged
    And job.json records AUDIO state "SENT" and nonnull sentAt
    Examples:
      | source      | streams                 |
      | source.webm | an Opus audio stream    |
      | source.mp4  | video and AAC audio     |

  @R4 @R6
  Scenario: Compress a real oversized source and verify the uploaded file
    Given the retained audio source is a real decodable 60 second stereo WAV larger than 52428800 bytes
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the Telegram mock receives one successful sendAudio
    And the uploaded bytes form a nonempty M4A with mono AAC audio and no video stream
    And the uploaded audio is smaller than 52428800 bytes
    And the sendAudio multipart caption is exactly "Compressed to mono AAC, 128 kbps, to fit Telegram's 50 MB limit."
    And the sendAudio multipart performer is "Video2Podcast"
    And "out.m4a" does not exist in the job directory after delivery
    And the source bytes are unchanged
    And job.json records AUDIO state "SENT" and nonnull sentAt

  @R4 @R6
  Scenario: Retry a compressed output once at a lower bitrate
    Given the retained audio source is larger than 52428800 bytes
    And the ffmpeg fault fixture writes an oversized M4A for the first encode and a nonempty below-limit M4A for the second encode
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the one sendAudio upload contains the second encoded output fixture bytes
    And the ffmpeg fault journal records exactly two mono AAC encodes at 128 and 102 kbps in order
    And the sendAudio multipart caption is exactly "Compressed to mono AAC, 102 kbps, to fit Telegram's 50 MB limit."
    And "out.m4a" does not exist in the job directory after delivery
    And job.json records AUDIO state "SENT" and nonnull sentAt

  @R4 @R8
  Scenario: Stop after the second compressed output is still oversized
    Given the retained audio source is larger than 52428800 bytes
    And the ffmpeg fault fixture writes an oversized M4A for every encode
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the ffmpeg fault journal records exactly two encodes
    And the Telegram mock receives no sendAudio
    And job.json records AUDIO state "AVAILABLE" and null sentAt
    And the summary keyboard is restored to "Download audio" with "Download transcript" unchanged
    And the Telegram mock receives a sendMessage to chat 101 with "Could not send the audio. Tap the button to try again."
    And "out.m4a" does not exist in the job directory after delivery
    And the source bytes and transcript bytes are unchanged

  @R4 @R8
  Scenario Outline: Failed encoding never uploads a partial or absent output
    Given the retained audio source is a real ffmpeg-generated 60 second "source.webm" below 1000000 bytes with an Opus audio stream
    And the ffmpeg fault fixture <failure>
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the Telegram mock receives no sendAudio
    And job.json records AUDIO state "AVAILABLE" and null sentAt
    And the summary keyboard is restored to "Download audio" with "Download transcript" unchanged
    And the Telegram mock receives a sendMessage to chat 101 with "Could not send the audio. Tap the button to try again."
    And "out.m4a" does not exist in the job directory after delivery
    And no Telegram message contains process stderr or a filesystem path
    Examples:
      | failure                                     |
      | exits with code 1 and writes a partial file  |
      | reports a process timeout                   |
      | exits with code 0 but writes no output      |
      | exits with code 0 but writes an empty output |

  @R5
  Scenario: Audio waits behind an earlier link job in the shared queue
    Given another allowed user's earlier video link is waiting for a held Whisper HTTP response
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the tap is answered with "Preparing audio…" while the Whisper response remains held
    And the summary keyboard shows "Preparing audio…" and "Download transcript"
    And job.json records AUDIO state "SENDING" and null sentAt
    And no delivery audio output or sendAudio upload occurs while the Whisper response remains held
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101 again
    Then the second tap produces only answerCallbackQuery with "Already being sent."
    When the Whisper mock releases the successful response and the earlier job publishes its summary
    Then the Telegram mock receives one successful sendAudio for chat 101
    And job.json records AUDIO state "SENT" and nonnull sentAt

  @R5
  Scenario: A second tap after successful audio delivery does not upload again
    Given the user has successfully downloaded this job's audio through a Telegram callback
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the second tap produces only answerCallbackQuery with "Already sent."
    And the Telegram mock has received exactly one sendAudio request in total
    And the second tap leaves job.json unchanged

  @R5
  Scenario: A concurrent tap cannot duplicate an audio upload in progress
    Given the Telegram mock holds the audio upload response
    And the user has tapped "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    And the Telegram mock has received the audio upload and holds its response
    When a concurrent Telegram update taps "audio:11111111-1111-4111-8111-111111111111" as user 101 on private message 701 in chat 101
    Then the concurrent tap produces only answerCallbackQuery with "Already being sent."
    And the Telegram mock has received exactly one sendAudio request in total
    When the Telegram mock releases the successful upload response
    Then job.json records AUDIO state "SENT" and nonnull sentAt
    And the Telegram mock has received exactly one sendAudio request in total

  @R1
  Scenario Outline: Another allowed user cannot take the owner's audio
    Given Telegram user <userId> is allowed and <role>
    When Telegram user <userId> taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the tap produces only answerCallbackQuery with "This button is not for you."
    And the job files are unchanged
    Examples:
      | userId | role                         |
      | 202    | is not the bot administrator |
      | 999    | is the bot administrator     |

  @R1
  Scenario: Revoked access prevents delivery of the user's own audio
    Given user 101 has been removed from the allowlist and is not the bot administrator
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the tap produces only answerCallbackQuery with "No access."
    And the job files are unchanged

  @R1
  Scenario Outline: Ignore audio callbacks outside private chats
    When user 101 sends a Telegram callback with data "audio:11111111-1111-4111-8111-111111111111" from a <chatType> chat
    Then the bot sends nothing
    And the job files are unchanged
    Examples:
      | chatType   |
      | group      |
      | supergroup |
      | channel    |

  @R1 @R6 @R7
  Scenario: Callback envelope cannot redirect audio delivery
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 702 in chat 303
    Then the Telegram mock receives one sendAudio targeting stored chat 101
    And the reply-markup edits target stored chat 101 and message 701
    And the Telegram mock receives no audio upload or message edit targeting chat 303

  @R8
  Scenario Outline: Enforce stored audio expiry at the tap boundary
    Given the AUDIO expiresAt in job.json is "<expiresAt>"
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the tap produces <outcome>
    Examples:
      | expiresAt                    | outcome                                                             |
      | 2026-11-19T12:30:00.000000001Z | one successful sendAudio and persisted AUDIO state SENT              |
      | 2026-11-19T12:30:00Z           | only answerCallbackQuery with text Files already downloaded or expired, please resend the link.   |
      | 2026-11-19T12:29:59Z           | only answerCallbackQuery with text Files already downloaded or expired, please resend the link.   |

  @R8
  Scenario Outline: Missing or unusable retained audio reports expiry
    Given <missingContent>
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the tap produces only answerCallbackQuery with "Files already downloaded or expired, please resend the link."
    And the tap leaves all remaining job files unchanged
    Examples:
      | missingContent                            |
      | the job directory is absent               |
      | job.json is absent                        |
      | job.json contains invalid JSON            |
      | job.json has no AUDIO entry               |
      | the AUDIO state in job.json is EXPIRED     |

  @R8
  Scenario: An absent source marks only the audio as expired
    Given the retained audio source file is absent
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the tap produces only answerCallbackQuery with "Files already downloaded or expired, please resend the link."
    And job.json records AUDIO state "EXPIRED" and null sentAt
    And the TRANSCRIPT entry and transcript bytes are unchanged

  @R1 @R8
  Scenario Outline: Invalid audio callback ids cannot access other files
    Given a sentinel file outside the job directory contains "keep me"
    When the user taps "<callbackData>" on private message 701 in chat 101
    Then the tap produces only answerCallbackQuery with "Files already downloaded or expired, please resend the link."
    And the job files and sentinel file are unchanged
    Examples:
      | callbackData                                           |
      | audio:                                                 |
      | audio:../sentinel                                      |
      | audio:/tmp/sentinel                                    |
      | audio:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa         |
      | audio:22222222-2222-4222-8222-222222222222               |

  @R5 @R8
  Scenario Outline: Persisted delivery states take precedence over elapsed expiry
    Given the AUDIO state in job.json is "<state>"
    And the AUDIO expiresAt in job.json is "2026-11-19T12:00:00Z"
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the tap produces only answerCallbackQuery with "<notice>"
    And the job files are unchanged
    Examples:
      | state   | notice              |
      | SENT    | Already sent.       |
      | SENDING | Already being sent. |

  @R8
  Scenario: A rejected audio upload allows an explicit manual retry
    Given the Telegram mock rejects the first sendAudio with HTTP 400 and a Bot API error response
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the Telegram mock receives one sendAudio attempt without an automatic retry
    And job.json records AUDIO state "AVAILABLE" and null sentAt
    And the summary keyboard is restored to "Download audio" with "Download transcript" unchanged
    And the Telegram mock receives a sendMessage to chat 101 with "Could not send the audio. Tap the button to try again."
    And "out.m4a" does not exist in the job directory after delivery
    And the source bytes and transcript bytes are unchanged
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101 again
    Then the Telegram mock has received exactly two sendAudio attempts in total with one successful response
    And job.json records AUDIO state "SENT" and nonnull sentAt

  @R8
  Scenario: A dropped audio upload response does not trigger automatic retry
    Given the Telegram mock accepts the audio upload bytes but closes the connection without a response on every audio upload attempt
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the Telegram mock receives one sendAudio attempt without an automatic retry
    And job.json records AUDIO state "AVAILABLE" and null sentAt
    And no Telegram message contains the transport diagnostic or a filesystem path

  @R8
  Scenario: A rejected callback answer does not prevent audio delivery
    Given the Telegram mock rejects answerCallbackQuery with HTTP 400 and a Bot API error response
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the Telegram mock receives one answerCallbackQuery attempt without retry
    And the Telegram mock receives one successful sendAudio
    And job.json records AUDIO state "SENT" and nonnull sentAt

  @R8
  Scenario Outline: Keyboard failure cannot duplicate successful audio delivery
    Given the Telegram mock rejects the <edit> reply-markup edit with HTTP 400 and a Bot API error response
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the Telegram mock receives one successful sendAudio
    And job.json records AUDIO state "SENT" and nonnull sentAt
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101 again
    Then the second tap produces only answerCallbackQuery with "Already sent."
    And the Telegram mock has received exactly one sendAudio request in total
    Examples:
      | edit     |
      | progress |
      | final    |

  @R7
  Scenario Outline: Preserve the transcript state while updating audio buttons
    Given the TRANSCRIPT state in job.json is "<state>"
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then both reply-markup edits retain the transcript button label "<label>" and its original callback data
    And the TRANSCRIPT entry is unchanged
    Examples:
      | state     | label               |
      | AVAILABLE | Download transcript |
      | SENDING   | Sending transcript… |
      | SENT      | Transcript sent     |
      | EXPIRED   | Transcript expired  |

  @R7
  Scenario: Transcript can be delivered while audio waits behind an earlier job
    Given another allowed user's earlier video link is waiting for a held Whisper HTTP response
    And the user has tapped "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    And job.json records AUDIO state "SENDING" and null sentAt
    When the user taps "transcript:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the Telegram mock receives one successful sendDocument while the Whisper response remains held
    And job.json records TRANSCRIPT state "SENT" and nonnull sentAt
    When the Whisper mock releases the successful response and the earlier job publishes its summary
    Then the Telegram mock receives one successful sendAudio for chat 101
    And the final summary keyboard shows "Audio sent" and "Transcript sent"
    And the source file, transcript.txt and job.json remain on disk

  @R7
  Scenario: Transcript remains deliverable after the audio was sent
    Given the user has successfully downloaded this job's audio through a Telegram callback
    When the user taps "transcript:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the Telegram mock receives one successful sendDocument
    And the final summary keyboard shows "Audio sent" and "Transcript sent"
    And the source file, transcript.txt and job.json remain on disk

  @R1 @R7
  Scenario: Missing summary message id does not prevent audio delivery
    Given summaryMessageId in job.json is null
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the Telegram mock receives one successful sendAudio
    And the Telegram mock receives no editMessageReplyMarkup
    And job.json records AUDIO state "SENT" and nonnull sentAt

  @R6
  Scenario: Video title remains literal audio metadata
    Given the stored job title is "R&D <notes>"
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the sendAudio multipart title is exactly "R&D <notes>"
    And the sendAudio multipart caption is exactly "Original audio, not compressed."
    And the sendAudio multipart performer is "Video2Podcast"
    And job.json retains the original title

  @R2
  Scenario Outline: Inclusive source size boundary preserves MP3 and M4A bytes
    Given the retained audio source is a valid "<source>" with padding to exactly <size> bytes
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the sendAudio multipart request contains exactly the unchanged retained source bytes
    And the sendAudio multipart caption is exactly "Original audio, not compressed."
    And job.json records AUDIO state "SENT" and nonnull sentAt
    Examples:
      | source     | size     |
      | source.mp3 | 52428799 |
      | source.mp3 | 52428800 |
      | source.m4a | 52428800 |
      | source.M4A | 52428800 |

  @R2 @R4
  Scenario: A source one byte above the boundary is compressed
    Given the retained audio source is a valid 60 second "source.m4a" with padding to exactly 52428801 bytes
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the uploaded bytes form a nonempty M4A with mono AAC audio and no video stream
    And the uploaded audio is smaller than 52428800 bytes
    And the sendAudio multipart caption is exactly "Compressed to mono AAC, 128 kbps, to fit Telegram's 50 MB limit."
    And the source bytes are unchanged

  @R3 @R4
  Scenario Outline: An oversized format conversion falls through to mono compression
    Given the retained audio source is a real ffmpeg-generated 60 second "source.webm" below 1000000 bytes with an Opus audio stream
    And the ffmpeg fault fixture writes a <size> byte output for the non-mono 128 kbps conversion and a valid below-limit M4A for mono compression
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the ffmpeg fault journal records a non-mono 128 kbps conversion followed by one mono 128 kbps encode from the original source
    And the one sendAudio upload contains the mono compression output fixture bytes
    And the sendAudio multipart caption is exactly "Compressed to mono AAC, 128 kbps, to fit Telegram's 50 MB limit."
    And "out.m4a" does not exist in the job directory after delivery
    Examples:
      | size     |
      | 52428800 |
      | 52428801 |

  @R4
  Scenario: A compressed output equal to the limit requires the lower bitrate retry
    Given the retained audio source is larger than 52428800 bytes
    And the ffmpeg fault fixture writes exactly 52428800 bytes for the first mono encode and a valid below-limit M4A for the second
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the ffmpeg fault journal records exactly two mono AAC encodes at 128 and 102 kbps in order
    And the one sendAudio upload contains the second encoded output fixture bytes
    And the sendAudio multipart caption is exactly "Compressed to mono AAC, 102 kbps, to fit Telegram's 50 MB limit."

  @R5 @R8
  Scenario: An eligible audio claim completes after expiry passes in the queue
    Given another allowed user's earlier video link is waiting for a held Whisper HTTP response
    And the AUDIO expiresAt in job.json is "2026-11-19T12:31:00Z"
    And the user has tapped "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    And job.json records AUDIO state "SENDING" and null sentAt
    And the application clock is "2026-11-19T12:32:00Z"
    When the Whisper mock releases the successful response and the earlier job publishes its summary
    Then the Telegram mock receives one successful sendAudio for chat 101
    And job.json records AUDIO state "SENT" and sentAt "2026-11-19T12:32:00Z"
    And the AUDIO expiresAt in job.json is still "2026-11-19T12:31:00Z"

  @R4
  Scenario Outline: Nonpositive stored durations use one second for bitrate calculation
    Given the retained audio source is a real decodable 60 second stereo WAV larger than 52428800 bytes
    And the stored job duration is <duration> seconds
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the Telegram mock receives one successful sendAudio
    And the sendAudio multipart caption is exactly "Compressed to mono AAC, 128 kbps, to fit Telegram's 50 MB limit."
    Examples:
      | duration |
      | 0        |
      | -1       |

  @R6
  Scenario Outline: Audio upload filename uses the stored title safely
    Given the stored job title is "<title>"
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the sendAudio multipart filename is "<filename>"
    And the stored title and source bytes are unchanged
    Examples:
      | title          | filename          |
      | Budget meeting | Budget meeting.m4a |
      | Q4/Plan: v1?    | Q4_Plan_ v1_.m4a   |
      |                | audio.m4a         |

  @R6
  Scenario: Bound audio metadata independently of the persisted title
    Given the stored job title contains 300 copies of "A"
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the sendAudio multipart title is exactly 256 copies of "A"
    And the sendAudio multipart filename is 100 copies of "A" followed by ".m4a"
    And job.json retains the original 300-character title

  @R8
  Scenario: An explicit new tap can succeed after preparation failure
    Given the retained audio source is a real ffmpeg-generated 60 second "source.webm" below 1000000 bytes with an Opus audio stream
    And the ffmpeg fault fixture exits with code 1 for the first conversion only
    And the user has tapped "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    And job.json records AUDIO state "AVAILABLE" and null sentAt
    And the ffmpeg fault fixture now allows real ffmpeg to run
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101 again
    Then the Telegram mock receives one successful sendAudio
    And job.json records AUDIO state "SENT" and nonnull sentAt
    And "out.m4a" does not exist in the job directory after delivery

  @R7 @R8
  Scenario: A converted temporary output is removed after upload rejection
    Given the retained audio source is a real ffmpeg-generated 60 second "source.webm" below 1000000 bytes with an Opus audio stream
    And the Telegram mock rejects the first sendAudio with HTTP 400 and a Bot API error response
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the Telegram mock receives one sendAudio attempt without an automatic retry
    And job.json records AUDIO state "AVAILABLE" and null sentAt
    And "out.m4a" does not exist in the job directory after delivery
    And the source bytes and transcript bytes are unchanged

  @T17 @R2
  Scenario Outline: A player-incompatible codec inside an M4A or MP3 name is converted
    Given the retained audio source is a real ffmpeg-generated 60 second "<source>" below 1000000 bytes with <streams>
    When the user taps "audio:11111111-1111-4111-8111-111111111111" on private message 701 in chat 101
    Then the Telegram mock receives one successful sendAudio
    And the uploaded bytes form a nonempty M4A with AAC audio and no video stream
    And the uploaded audio has 2 channels at most
    And the sendAudio multipart caption is exactly "Converted to M4A, 128 kbps."
    And "out.m4a" does not exist in the job directory after delivery
    And the source bytes are unchanged
    And job.json records AUDIO state "SENT" and nonnull sentAt
    Examples:
      | source     | streams                                     |
      | source.m4a | an E-AC-3 5.1 audio stream in an MP4 container |
      | source.m4a | an Opus audio stream in an MP4 container       |
      | source.mp3 | an AAC audio stream in an ADTS container       |
