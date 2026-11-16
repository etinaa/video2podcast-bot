@T8
Feature: Validate and prepare one video's audio for transcription
  Media preparation exposes tool invocations, files and safe failures to its caller.
  Telegram integration and acceptance-test wiring are delivered in later tasks.
  Scenarios tagged manual_media require real tools and are checked manually in T8.
  Scenarios tagged stubbed_media prove wrapper contracts with fixtures, not audio properties.

  @R1 @R2
  Scenario Outline: Accept a public HTTP URL unchanged
    When media preparation validates the URL "<url>"
    Then the accepted URL is "<url>"
    And no DNS lookup is performed

    Examples:
      | url                                          |
      | https://www.youtube.com/watch?v=abc&list=xyz   |
      | http://example.com/video                      |
      | HTTPS://example.com/video                     |
      | https://8.8.8.8/video                          |
      | https://[2606:4700:4700::1111]/video            |
      | https://unresolvable.example/video            |

  @R1
  Scenario Outline: Reject an invalid URL before starting a tool
    Given the URL input is <input>
    When media preparation validates that input
    Then preparation fails with kind "UNSUPPORTED"
    And no external process is started

    Examples:
      | input                                      |
      | null                                       |
      | an empty string                            |
      | three spaces                               |
      | "/relative/video"                          |
      | "https:///video"                           |
      | "file:///tmp/video"                        |
      | "ftp://example.com/video"                  |
      | "https://example.com/a b"                  |
      | an HTTPS URL containing a tab              |
      | an HTTPS URL containing CR and LF          |
      | an HTTPS URL containing U+0000             |
      | "https://user:password@example.com/video"  |
      | "https://user:@example.com/video"          |
      | "https://user@example.com/video"           |

  @R1
  Scenario Outline: Enforce the inclusive URL length limit
    Given an otherwise valid HTTPS URL of <length> characters
    When media preparation validates that input
    Then the validation outcome is "<outcome>"
    And no external process is started

    Examples:
      | length | outcome     |
      | 2048   | accepted    |
      | 2049   | UNSUPPORTED |

  @R2
  Scenario Outline: Reject a local or restricted literal host without DNS
    When media preparation validates the URL "http://<host>/video"
    Then preparation fails with kind "UNSUPPORTED"
    And no DNS lookup is performed
    And no external process is started

    Examples:
      | host                  |
      | localhost             |
      | LOCALHOST.            |
      | a.localhost           |
      | 127.0.0.1             |
      | 127.0.0.2             |
      | 10.1.2.3              |
      | 172.16.0.1            |
      | 172.31.255.254        |
      | 192.168.1.1           |
      | 169.254.1.1           |
      | [::1]                 |
      | [fc00::1]             |
      | [fd12::1]             |
      | [fe80::1]             |
      | [::ffff:127.0.0.1]    |
      | [::ffff:192.168.1.1]  |
      | 0.0.0.0               |
      | [::]                  |
      | 100.64.0.1            |
      | 127.1                 |
      | 2130706433            |

  @R2
  Scenario Outline: Do not extend private IPv4 ranges to public neighbours
    When media preparation validates the URL "http://<host>/video"
    Then the accepted URL is "http://<host>/video"
    And no DNS lookup is performed

    Examples:
      | host         |
      | 172.15.1.1   |
      | 172.32.1.1   |
      | 192.169.1.1  |

  @R1 @R3
  Scenario Outline: Validate again at each yt-dlp entry point
    When media preparation attempts "<operation>" for "http://localhost/video"
    Then preparation fails with kind "UNSUPPORTED"
    And no external process is started

    Examples:
      | operation |
      | metadata  |
      | download  |

  @R3
  Scenario Outline: Keep URL punctuation as a single data argument
    Given the accepted URL "https://example.com/video?q=a;b&value=$(id)"
    When media preparation attempts "<operation>" for that URL
    Then yt-dlp receives the complete URL as one final argument immediately after "--"
    And the executable is invoked directly without a shell

    Examples:
      | operation |
      | metadata  |
      | download  |

  @R3
  Scenario: Preserve a job directory containing spaces as one output argument
    Given the job directory "/tmp/T8 job"
    When media preparation downloads audio for "https://example.com/video"
    Then yt-dlp receives "/tmp/T8 job/source.%(ext)s" as one output-template argument

  @R3
  Scenario: Capture both streams and a nonzero exit without deadlock
    Given a controlled local process writes 1048576 bytes to each output stream and exits with code 7
    When that process is executed with a deadline of 10 seconds
    Then the execution result has exit code 7
    And stdout contains the process's stdout output
    And stderr contains the process's stderr output

  @R3
  Scenario: Continue draining output after capture limits are reached
    Given a controlled local process writes 9437184 bytes to each output stream and exits with code 0
    When that process is executed with a deadline of 10 seconds
    Then the execution result has exit code 0
    And the captured size of each stream is at most 8388608 bytes

  @R3 @R10
  Scenario: Report a missing executable safely
    Given the requested executable does not exist
    When that process is executed with a deadline of 10 seconds
    Then preparation fails with kind "FAILED"
    And the failure message contains no raw diagnostics

  @R3
  Scenario: Stop execution when its waiting thread is interrupted
    Given a controlled local process and its child remain running
    When the waiting execution thread is interrupted
    Then the process and its child stop
    And preparation fails with kind "FAILED"
    And the waiting thread's interrupt flag is set

  @R4
  Scenario Outline: Apply default stage deadlines
    Given no process-deadline overrides
    When media preparation attempts the "<stage>" stage
    Then the tool invocation has a deadline of "<deadline>"

    Examples:
      | stage       | deadline |
      | metadata    | PT1M     |
      | download    | PT30M    |
      | WAV         | PT30M    |
      | split       | PT30M    |

  @R4
  Scenario Outline: Apply configured stage deadlines
    Given metadata deadline "PT7S", download deadline "PT11S" and ffmpeg deadline "PT13S"
    When media preparation attempts the "<stage>" stage
    Then the tool invocation has a deadline of "<deadline>"

    Examples:
      | stage       | deadline |
      | metadata    | PT7S     |
      | download    | PT11S    |
      | WAV         | PT13S    |
      | split       | PT13S    |

  @R4 @R10
  Scenario: Kill the process tree on deadline expiry
    Given a controlled local process and its child remain running
    When that process is executed with a deadline of 1 second
    Then the process and its child stop
    And preparation fails with kind "TIMEOUT"
    And the failure message contains no raw diagnostics

  @R4
  Scenario Outline: Reject a nonpositive execution deadline
    When a controlled local process is requested with deadline "<deadline>"
    Then execution rejects the deadline before starting the process

    Examples:
      | deadline |
      | PT0S     |
      | PT-1S    |

  @R4
  Scenario Outline: Preserve a timeout through every media stage
    Given the external process for "<stage>" times out
    When media preparation attempts the "<stage>" stage
    Then preparation fails with kind "TIMEOUT"

    Examples:
      | stage    |
      | metadata |
      | download |
      | WAV      |
      | split    |

  @R5
  Scenario: Read a title and duration without downloading audio
    Given yt-dlp metadata exits with code 0 and JSON:
      """
      {"title":"Example talk","duration":1250}
      """
    When media preparation fetches metadata for "https://example.com/video"
    Then the metadata title is "Example talk"
    And the metadata duration is 1250 seconds
    And yt-dlp is instructed not to download media
    And no audio download is started

  @R5
  Scenario Outline: Enforce the configured duration inclusively before download
    Given the maximum video duration is "<limit>"
    And yt-dlp metadata has title "Example talk" and duration <seconds> seconds
    When media preparation checks metadata before download
    Then the metadata outcome is "<outcome>"
    And no audio download is started

    Examples:
      | limit | seconds | outcome  |
      | PT3H  | 10800   | accepted |
      | PT3H  | 10800.1 | TOO_LONG |
      | PT3H  | 10801   | TOO_LONG |
      | PT2H  | 7200    | accepted |
      | PT2H  | 7201    | TOO_LONG |

  @R5 @R10
  Scenario: Explain the configured duration limit
    Given the maximum video duration is "PT2H"
    And yt-dlp metadata has title "Example talk" and duration 7201 seconds
    When media preparation checks metadata before download
    Then preparation fails with kind "TOO_LONG"
    And the failure message explains the 2 hour limit

  @R5
  Scenario Outline: Reject live or unusable metadata before downloading
    Given yt-dlp metadata exits with code 0 and stdout <metadata>
    When media preparation checks metadata before download
    Then preparation fails with kind "<kind>"
    And no audio download is started

    Examples:
      | metadata                                                 | kind        |
      | {"title":"Live"}                                         | UNSUPPORTED |
      | {"title":"Live","duration":null}                         | UNSUPPORTED |
      | {"title":"Live","duration":300,"is_live":true}            | UNSUPPORTED |
      | {"title":"Talk","duration":"unknown"}                    | UNSUPPORTED |
      | {"title":"Talk","duration":0}                            | UNSUPPORTED |
      | {"title":"Talk","duration":-1}                           | UNSUPPORTED |
      | not-json                                                 | FAILED      |
      | []                                                       | FAILED      |
      | {"duration":300}                                         | FAILED      |
      | {"title":" ","duration":300}                              | FAILED      |

  @R6
  Scenario Outline: Retain source audio with the requested format preference
    Given accepted metadata for one 300 second video
    And yt-dlp download writes "<file>" in the job directory and exits with code 0
    When media preparation downloads that video's audio
    Then yt-dlp receives format selector "bestaudio[ext=m4a]/bestaudio"
    And the returned source path is "<file>" in the job directory
    And no Telegram audio re-encoding is started

    Examples:
      | file        |
      | source.m4a  |
      | source.webm |

  @R6
  Scenario: Process one video when its URL also includes playlist context
    Given a URL referring to a video and a playlist
    When media preparation attempts metadata and audio download for that URL
    Then both yt-dlp invocations receive "--no-playlist"
    And only the selected video's source audio is downloaded

  @R5 @R6
  Scenario: Do not expand a playlist-only URL into audio downloads
    Given yt-dlp metadata represents a playlist rather than one video
    When media preparation checks metadata before download
    Then metadata is rejected
    And no audio download is started

  @R6 @R10
  Scenario Outline: Reject a successful download without a completed source file
    Given yt-dlp download exits with code 0
    And the job directory contains <files>
    When media preparation collects the downloaded source
    Then preparation fails with kind "FAILED"

    Examples:
      | files                         |
      | no files                      |
      | only "source.m4a.part"         |
      | only "source.m4a.ytdl"         |

  @R7 @manual_media
  Scenario: Produce a Whisper-compatible WAV while retaining the source
    Given source audio "source.webm" in the job directory
    When media preparation converts the source to WAV
    Then "audio.wav" contains 16000 Hz mono PCM signed 16-bit little-endian audio
    And the source file hash is unchanged

  @R8 @manual_media
  Scenario: Split audio into ordered chunks including the final remainder
    Given a 1250 second 16000 Hz mono WAV
    When media preparation splits the WAV
    Then ffmpeg is requested to segment audio every 600 seconds
    And chunks "chunk-000.wav", "chunk-001.wav" and "chunk-002.wav" are returned in playback order
    And those chunks contain approximately 600, 600 and 50 seconds of 16000 Hz mono PCM audio
    And reconstructing their audio reproduces the input audio without dropped or duplicated intervals

  @R8 @manual_media
  Scenario: Keep a short source as one chunk
    Given a 59 second 16000 Hz mono WAV
    When media preparation splits the WAV
    Then one chunk containing the full audio is returned

  @R8
  Scenario: Return numbered chunks in order regardless of filesystem enumeration
    Given the split invocation produces "chunk-002.wav", "chunk-000.wav" and "chunk-001.wav"
    When media preparation collects the WAV chunks
    Then chunks "chunk-000.wav", "chunk-001.wav" and "chunk-002.wav" are returned in playback order

  @R8
  Scenario: Exclude stale chunks when splitting again
    Given a previous attempt left "chunk-009.wav" in the chunks directory
    And the new split invocation produces only "chunk-000.wav"
    When media preparation splits the WAV again
    Then only the new "chunk-000.wav" is returned

  @R8 @R10
  Scenario: Reject a split that produced no chunks
    Given ffmpeg split exits with code 0 but produces no WAV chunks
    When media preparation collects the WAV chunks
    Then preparation fails with kind "FAILED"

  @R7 @R8 @R10
  Scenario Outline: Report ffmpeg failure without yt-dlp classification
    Given ffmpeg for "<stage>" exits with code 1 and stderr "private /tmp/secret"
    When media preparation attempts the "<stage>" stage
    Then preparation fails with kind "FAILED"
    And the failure message contains no raw diagnostics

    Examples:
      | stage |
      | WAV   |
      | split |

  @R9 @R10
  Scenario Outline: Classify nonzero yt-dlp stderr case-insensitively
    Given yt-dlp exits with code 1 and stderr "<stderr>"
    When media preparation handles the yt-dlp failure
    Then preparation fails with kind "<kind>"
    And the failure message contains no raw diagnostics

    Examples:
      | stderr                          | kind        |
      | PRIVATE content                 | PRIVATE     |
      | Please SIGN IN                  | PRIVATE     |
      | MEMBERS-ONLY video               | PRIVATE     |
      | AGE restriction                 | PRIVATE     |
      | Unsupported URL                 | UNSUPPORTED |
      | PRIVATE: Unsupported URL         | PRIVATE     |
      | Network connection failed        | FAILED      |
      |                                 | FAILED      |

  @R9
  Scenario Outline: Use the same error classification at both yt-dlp stages
    Given yt-dlp for "<stage>" exits with code 1 and stderr "Please sign in"
    When media preparation attempts the "<stage>" stage
    Then preparation fails with kind "PRIVATE"

    Examples:
      | stage    |
      | metadata |
      | download |

  @R9
  Scenario: Do not classify warning text after successful metadata extraction
    Given yt-dlp metadata exits with code 0 and JSON:
      """
      {"title":"Example talk","duration":300}
      """
    And yt-dlp stderr is "private warning text"
    When media preparation fetches metadata for "https://example.com/video"
    Then the metadata title is "Example talk"

  @R10
  Scenario Outline: Keep diagnostic content out of safe failure messages
    Given a "<kind>" failure with diagnostics containing a URL, local path and stack trace
    When the failure is exposed to the media caller
    Then its kind is "<kind>"
    And its message is a clear English explanation
    And its message contains none of those diagnostics

    Examples:
      | kind        |
      | PRIVATE     |
      | UNSUPPORTED |
      | TOO_LONG    |
      | FAILED      |
      | TIMEOUT     |

  @R3
  Scenario: Do not interpret shell punctuation in an argument
    Given a controlled local executable records its arguments
    When it is invoked with the single argument "https://example.com/video?a=1&b=$VALUE;echo"
    Then it records that entire value as one argument
    And no shell expansion or additional command is executed

  @R3
  Scenario: Continue draining both streams after reaching capture limits
    Given a controlled local process writes more than the configured capture limit to both output streams
    When that process is executed with a deadline of 10 seconds
    Then the execution completes without an output-pipe deadlock
    And the captured stdout and stderr each respect the capture limit

  @R7
  Scenario: Reject conversion success without the expected WAV
    Given ffmpeg conversion exits with code 0 but writes no "audio.wav"
    When media preparation collects the converted WAV
    Then preparation fails with kind "FAILED"

  @R8 @manual_media
  Scenario Outline: Retain short recordings without empty trailing chunks
    Given a <seconds> second 16000 Hz mono WAV
    When media preparation splits that WAV
    Then one nonempty WAV chunk covers the recording
    And no empty trailing chunk is returned

    Examples:
      | seconds |
      | 45      |
      | 600     |

  @R8
  Scenario: Reject split success without chunks
    Given ffmpeg splitting exits with code 0 but writes no chunk files
    When media preparation collects the WAV chunks
    Then preparation fails with kind "FAILED"


  @R7 @stubbed_media
  Scenario: Request the WAV format without claiming fixture audio was converted
    Given a fake process runner creates an output fixture at "audio.wav" and reports exit code 0
    When media preparation converts source audio "source.webm" to WAV
    Then ffmpeg receives separate source and output path arguments
    And ffmpeg receives "-vn", "-ar 16000", "-ac 1" and "-c:a pcm_s16le" as options and values
    And the returned WAV path is the output fixture path

  @R8 @stubbed_media
  Scenario: Request ten-minute segments and return ordered fixture paths
    Given a fake process runner creates "chunk-002.wav", "chunk-000.wav" and "chunk-001.wav" fixtures and reports exit code 0
    When media preparation splits a WAV fixture
    Then ffmpeg receives a segment duration target of 600 seconds and audio stream copy
    And chunks "chunk-000.wav", "chunk-001.wav" and "chunk-002.wav" are returned in playback order
