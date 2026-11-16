package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.video2podcast.bot.TelegramBot;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;

/** Steps for {@code whoami.feature}. A new instance is created for every scenario. */
public class WhoamiSteps {

  @Autowired private TelegramBot bot;

  private long userId;

  @Given("a Telegram user")
  public void aTelegramUser() {
    userId = FunctionalTest.newUserId();
  }

  @When("the user sends {string} in a private chat")
  public void sendsInPrivateChat(String text) {
    bot.consume(Updates.privateMessage(userId, text));
  }

  @When("the user sends {string} in a group chat")
  public void sendsInGroupChat(String text) {
    bot.consume(Updates.groupMessage(-100, userId, text));
  }

  @Then("the bot replies with the user's Telegram id")
  public void repliesWithTheId() {
    assertThat(FunctionalTest.sentTo(userId))
        .singleElement()
        .satisfies(call ->
            assertThat(call.get("text").asText())
                .isEqualTo("Your Telegram id: <code>" + userId + "</code>"));
  }

  @Then("the bot sends nothing")
  public void sendsNothing() {
    assertThat(FunctionalTest.telegramCalls("sendMessage")).isEmpty();
  }
}
