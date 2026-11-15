package com.video2podcast.bot;

import com.video2podcast.access.AccessService;
import com.video2podcast.access.AccessService.RequestOutcome;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Answers users who are not on the allowlist: {@code /start} files an access request, anything else
 * gets a short refusal. Replies other than the first confirmation are rate-limited so a stranger
 * can't make the bot answer in a loop.
 */
class UnknownUserHandler {

  private static final Logger LOG = LoggerFactory.getLogger(UnknownUserHandler.class);

  private static final DateTimeFormatter UNTIL =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

  private final TelegramGateway gateway;
  private final AccessService access;
  private final AccessAdmin admin;

  UnknownUserHandler(TelegramGateway gateway, AccessService access, AccessAdmin admin) {
    this.gateway = gateway;
    this.access = access;
    this.admin = admin;
  }

  void handle(Sender sender, String command, String helpText) {
    if (!"/start".equals(command)) {
      replyLimited(
          sender,
          "No access. Send /start to request it. Your id: <code>" + sender.userId() + "</code>");
      return;
    }
    switch (access.requestAccess(sender.userId(), sender.username(), sender.displayName())) {
      case RequestOutcome.Created created -> {
        tell(sender, "Access requested. The owner will review it.");
        admin.notifyOwner(created.request());
      }
      case RequestOutcome.AlreadyPending pending -> {
        replyLimited(sender, "Your request is already waiting for the owner.");
        if (pending.request().requestMessageId() == null) {
          admin.notifyOwner(pending.request());
        }
      }
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
      tell(sender, html);
    }
  }

  /**
   * A reply that can't be delivered (the user blocked the bot) must not stop the rest of the flow.
   */
  private void tell(Sender sender, String html) {
    try {
      gateway.sendMessage(sender.chatId(), html, List.of());
    } catch (TelegramGatewayException e) {
      long userId = sender.userId();
      LOG.warn("Could not answer user {}", userId, e);
    }
  }
}
