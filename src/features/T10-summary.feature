@T10
Feature: Summarize a video link through the local Ollama HTTP API
  Future acceptance: T11 supplies the Telegram trigger and step definitions.
  Exclude @T10 until then; T10 itself is verified by unit tests.
  Transcript fixtures are returned by the Whisper mock, not by internal service calls.
  Counts and budgets in the fixture setup use the agreed context-estimation contract.

  Background:
    Given an allowed Telegram user
    And real yt-dlp and ffmpeg are enabled with the local WireMock media proxy
    And WireMock serves an ffmpeg-generated 59 second m4a recording at "http://videos.example/video"
    And the Whisper base URL points to the Whisper mock
    And the Ollama base URL points to the Ollama mock
    And the configured Ollama model is "qwen3:32b"
    And the configured Ollama context window is 8192 tokens
    And the Whisper mock returns JSON {"text":"The budget meeting is on Tuesday. Engineering spending increased."}

  @R1 @R3 @R6
  Scenario: Summarize a short transcript in one non-thinking request
    Given the Ollama mock returns valid prompt counts and this content:
      """
      - The budget meeting is on Tuesday.
      - Engineering spending increased.
      """
    When the user sends "http://videos.example/video" in a private chat
    Then the Ollama mock receives exactly 1 POST request to "/api/chat"
    And every Ollama request contains model "qwen3:32b", stream false, think false and options.num_ctx 8192
    And the Ollama request contains the complete transcript and instructions for English bullet points
    And the Telegram summary contains the bullet points about Tuesday and engineering spending

  @R1
  Scenario: Use the configured model instead of a hard-coded model
    Given the configured Ollama model is "qwen3:14b"
    And the Ollama mock returns valid prompt counts and content "- The budget meeting is on Tuesday."
    When the user sends "http://videos.example/video" in a private chat
    Then every Ollama request contains model "qwen3:14b", stream false, think false and options.num_ctx 8192

  @R1 @R2 @R3
  Scenario: Summarize every chunk and then all three partial summaries
    Given WireMock serves an ffmpeg-generated 10800 second m4a recording at "http://videos.example/video"
    And the Whisper mock returns an ordered English transcript that fills exactly 3 summary input chunks at context 8192
    And the first and last transcript words are "Opening" and "Closing"
    And the Ollama mock returns valid prompt counts and these contents in order:
      | content                      |
      | - First section: budget.     |
      | - Second section: staffing.  |
      | - Final section: deadlines.  |
      | - Budget, staffing and deadlines were discussed. |
    When the user sends "http://videos.example/video" in a private chat
    Then the Ollama mock receives exactly 4 POST requests to "/api/chat"
    And every Ollama request contains model "qwen3:32b", stream false, think false and options.num_ctx 8192
    And the first 3 Ollama requests contain the entire transcript in order without omissions or duplication
    And their transcript inputs start with "Opening" and end with "Closing"
    And the fourth Ollama request contains all 3 partial summaries in order and instructions for English bullet points
    And each recorded Ollama request fits its context including instructions, chat overhead and reserved output
    And the Telegram summary contains "Budget, staffing and deadlines were discussed."

  @R2 @R3
  Scenario: Reduce again when all partial summaries cannot fit together
    Given WireMock serves an ffmpeg-generated 10800 second m4a recording at "http://videos.example/video"
    And the Whisper mock returns an ordered English transcript that fills exactly 8 summary input chunks at context 8192
    And the Ollama mock returns valid prompt counts and one oversized English bullet for each of the first 8 requests
    And each oversized bullet begins with its section number and exceeds the entire reduction input budget
    And the ninth and tenth Ollama answers are valid short English bullet summaries
    And the eleventh Ollama answer is a valid short final English bullet summary
    When the user sends "http://videos.example/video" in a private chat
    Then the Ollama mock receives exactly 11 POST requests to "/api/chat"
    And each recorded Ollama request fits its context including instructions, chat overhead and reserved output
    And the ninth Ollama request contains bounded prefixes for sections 1 through 4 in order
    And the tenth Ollama request contains bounded prefixes for sections 5 through 8 in order
    And the eleventh Ollama request contains both group summaries in order
    And the bot sends one successful Telegram summary after the final Ollama answer

  @R4 @R7
  Scenario Outline: Reject unusable successful HTTP responses
    Given the Ollama mock returns status 200 with body <body>
    When the user sends "http://videos.example/video" in a private chat
    Then the bot replies with "Summary failed. Please try again later."
    And no successful Telegram summary is sent

    Examples:
      | body                                                    |
      | the T3 fixture ollama-chat-thinking-default.json         |
      | {"message":{"content":""},"prompt_eval_count":71}         |
      | {"message":{"content":" \t\n "},"prompt_eval_count":71}   |
      | {"message":{},"prompt_eval_count":71}                     |
      | {"message":{"content":null},"prompt_eval_count":71}       |
      | {"message":{"content":42},"prompt_eval_count":71}         |
      | {broken                                                 |
      | an empty body                                           |

  @R3 @R4 @R7
  Scenario: Do not publish a partial summary after a map failure
    Given the Whisper mock returns an ordered English transcript that fills exactly 3 summary input chunks at context 8192
    And the Ollama mock first returns valid prompt counts and content "- Earlier section succeeded."
    And every subsequent Ollama answer has empty content
    When the user sends "http://videos.example/video" in a private chat
    Then the bot replies with "Summary failed. Please try again later."
    And the Ollama mock receives no request for the third transcript chunk or for reduction
    And no successful Telegram summary is sent

  @R3 @R4 @R7
  Scenario: Do not publish mapped summaries after the final reduction fails
    Given the Whisper mock returns an ordered English transcript that fills exactly 3 summary input chunks at context 8192
    And the Ollama mock returns valid short English bullet summaries for the first 3 requests
    And every reduction answer has empty content
    When the user sends "http://videos.example/video" in a private chat
    Then the bot replies with "Summary failed. Please try again later."
    And no successful Telegram summary is sent

  @R5 @R7
  Scenario: Detect a silently truncated short-path prompt
    Given the Whisper mock returns an English transcript whose complete prompt has an expected input size of approximately 4831 tokens
    And the Ollama mock returns JSON {"message":{"content":"- A plausible but incomplete summary."},"done":true,"prompt_eval_count":1026}
    When the user sends "http://videos.example/video" in a private chat
    Then the bot replies with "Summary failed. Please try again later."
    And no successful Telegram summary is sent

  @R5 @R7
  Scenario: A cached prompt count cannot hide truncation
    Given the Ollama mock returns JSON {"message":{"content":"- An incomplete summary."},"done":true,"prompt_eval_count":1,"prompt_eval_cached_count":100000}
    When the user sends "http://videos.example/video" in a private chat
    Then the bot replies with "Summary failed. Please try again later."
    And no successful Telegram summary is sent

  @R3 @R5 @R7
  Scenario Outline: Detect truncation after earlier successful answers
    Given the Whisper mock returns an ordered English transcript that fills exactly 3 summary input chunks at context 8192
    And the Ollama mock returns valid prompt counts and short English bullet content before the <stage> request
    And every answer for the <stage> request contains nonempty bullet content but prompt_eval_count 1
    When the user sends "http://videos.example/video" in a private chat
    Then the bot replies with "Summary failed. Please try again later."
    And no successful Telegram summary is sent
    And the Ollama mock receives no requests for later stages after the failure

    Examples:
      | stage             |
      | second map        |
      | final reduction   |

  @R6
  Scenario Outline: Cap the final summary body independently of the prompt instruction
    Given the Ollama mock returns valid prompt counts and English bullet content of <length> characters
    When the user sends "http://videos.example/video" in a private chat
    Then the Telegram summary body has at most 1500 characters including bullet markers and line separators
    And the summary body is nonempty bullet text

    Examples:
      | length |
      | 1499   |
      | 1500   |
      | 1800   |

  @R7
  Scenario: Explain an unreachable Ollama instead of silently hanging
    Given the Ollama base URL points to a local port that refuses connections
    When the user sends "http://videos.example/video" in a private chat
    Then the bot replies with "The summary server is unreachable."
    And no successful Telegram summary is sent

  @R4 @R7
  Scenario Outline: Report HTTP failures without exposing server diagnostics
    Given the Ollama mock returns status <status> with body <body> for every request
    When the user sends "http://videos.example/video" in a private chat
    Then the bot replies with "Summary failed. Please try again later."
    And no successful Telegram summary is sent
    And no Telegram message contains "nope:1b" or "/private/jobs/secret" or "hidden transcript"

    Examples:
      | status | body                                                       |
      | 404    | the T3 fixture ollama-model-not-found.json                  |
      | 500    | {"error":"/private/jobs/secret hidden transcript"}           |

  @R7
  Scenario: Stop waiting for a connected Ollama that does not answer
    Given OLLAMA_TIMEOUT is "PT0.1S"
    And the Ollama mock accepts connections but delays every answer for 2 seconds
    When the user sends "http://videos.example/video" in a private chat
    Then the bot replies with "Processing took too long and was stopped."
    And no successful Telegram summary is sent

  @R6 @R7
  Scenario Outline: Reject a final answer containing only bullet markers
    Given the Ollama mock returns valid prompt counts and JSON message.content <content>
    When the user sends "http://videos.example/video" in a private chat
    Then the bot replies with "Summary failed. Please try again later."
    And no successful Telegram summary is sent

    Examples:
      | content       |
      | "-"           |
      | "*"           |
      | "•"           |
      | "–"           |
      | "- \n* \n"   |

  @R6
  Scenario: Preserve a marker attached directly to summary text
    Given the Ollama mock returns valid prompt counts and this content:
      """
      -5 degrees
      *note*
      """
    When the user sends "http://videos.example/video" in a private chat
    Then the Telegram summary body contains exactly these bullet lines:
      """
      • -5 degrees
      • *note*
      """
