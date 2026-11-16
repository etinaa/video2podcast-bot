Feature: Telegram id lookup
  Anyone can ask the bot for their Telegram id, but only in a private chat.

  Scenario: A private /whoami gets the sender's id
    Given a Telegram user
    When the user sends "/whoami" in a private chat
    Then the bot replies with the user's Telegram id

  Scenario: A /whoami in a group gets no reply
    Given a Telegram user
    When the user sends "/whoami" in a group chat
    Then the bot sends nothing
