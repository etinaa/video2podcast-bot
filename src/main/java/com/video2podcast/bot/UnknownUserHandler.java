package com.video2podcast.bot;

import com.video2podcast.access.AccessService;
import com.video2podcast.access.AccessService.RequestOutcome;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Answers users who are not on the allowlist: {@code /start} files an access request, anything else
 * gets a short refusal. Replies other than the first confirmation are rate-limited so a stranger
 * can't make the bot answer in a loop.
 */
class UnknownUserHandler {

  private static final DateTimeFormatter UNTIL =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

  private final TelegramGateway gateway;
  private final AccessService access;

  UnknownUserHandler(TelegramGateway gateway, AccessService access) {
    this.gateway = gateway;
    this.access = access;
  }

  void handle(Sender sender, String command, String helpText) {
    if (!"/start".equals(command)) {
      replyLimited(
          sender,
          "No access. Send /start to request it. Your id: <code>" + sender.userId() + "</code>");
      return;
    }
    switch (access.requestAccess(sender.userId(), sender.username(), sender.displayName())) {
      case RequestOutcome.Created _ ->
          gateway.sendMessage(
              sender.chatId(), "Access requested. The owner will review it.", List.of());
      case RequestOutcome.AlreadyPending _ ->
          replyLimited(sender, "Your request is already waiting for the owner.");
      case RequestOutcome.CoolingDown cooling ->
          replyLimited(
              sender,
              "Your request was declined. You can ask again after "
                  + UNTIL.format(cooling.until())
                  + ".");
      case RequestOutcome.AlreadyAllowed _ ->
          gateway.sendMessage(sender.chatId(), helpText, List.of());
    }
  }

  private void replyLimited(Sender sender, String html) {
    if (access.shouldSendRefusal(sender.userId())) {
      gateway.sendMessage(sender.chatId(), html, List.of());
    }
  }
}
