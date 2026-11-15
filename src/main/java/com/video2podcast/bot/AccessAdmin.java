package com.video2podcast.bot;

import com.video2podcast.access.AccessService;
import com.video2podcast.access.AccessService.RemoveResult;
import com.video2podcast.access.AllowedUser;
import com.video2podcast.access.PendingRequest;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;

/**
 * The owner's side of access control: the request notification with Approve and Deny buttons, the
 * {@code /allow}, {@code /deny} and {@code /users} commands, and the button taps. Everything here
 * is owner-only. Button data carries the user id, so the buttons keep working after a restart.
 */
class AccessAdmin {

  private static final Logger LOG = LoggerFactory.getLogger(AccessAdmin.class);

  private static final String OWNER_ONLY = "Owner only.";

  private final TelegramGateway gateway;
  private final AccessService access;

  AccessAdmin(TelegramGateway gateway, AccessService access) {
    this.gateway = gateway;
    this.access = access;
  }

  /** Tells the owner about a new request. A failure is logged: the request stays pending. */
  void notifyOwner(PendingRequest request) {
    try {
      int messageId =
          gateway.sendMessage(
              access.ownerId(), requestText(request), Keyboards.accessRequest(request.id()));
      access.recordRequestMessage(request.id(), messageId);
    } catch (TelegramGatewayException e) {
      long userId = request.id();
      LOG.error("Could not notify the owner about the request from user {}", userId, e);
    }
  }

  void handleCommand(Sender sender, String command, String args) {
    if (!access.isOwner(sender.userId())) {
      reply(sender, OWNER_ONLY);
      return;
    }
    if ("/users".equals(command)) {
      reply(sender, usersText());
      return;
    }
    Optional<Long> target = parseId(args);
    if (target.isEmpty()) {
      reply(sender, "Usage: " + command + " &lt;user id&gt;");
    } else if ("/allow".equals(command)) {
      reply(sender, allow(target.get(), null));
    } else {
      reply(sender, deny(target.get(), null));
    }
  }

  void handleCallback(CallbackQuery callback) {
    if (!access.isOwner(callback.getFrom().getId())) {
      gateway.answerCallback(callback.getId(), OWNER_ONLY);
      return;
    }
    String[] parts = callback.getData().split(":");
    Optional<Long> target = parts.length == 3 ? parseId(parts[2]) : Optional.empty();
    boolean approve = parts.length == 3 && Keyboards.APPROVE.equals(parts[1]);
    if (target.isEmpty() || !(approve || Keyboards.DENY.equals(parts[1]))) {
      gateway.answerCallback(callback.getId(), "Unknown button.");
      return;
    }
    MaybeInaccessibleMessage message = callback.getMessage();
    Tapped tapped = new Tapped(message.getChatId(), message.getMessageId());
    String result = approve ? allow(target.get(), tapped) : deny(target.get(), tapped);
    gateway.answerCallback(callback.getId(), result);
  }

  /** The owner's message that held the tapped button. */
  private record Tapped(long chatId, int messageId) {}

  private String allow(long userId, Tapped tapped) {
    Optional<PendingRequest> pending = access.pendingRequest(userId);
    if (!access.allow(userId, null, access.ownerId())) {
      return "User " + userId + " already has access.";
    }
    notifyUser(userId, "Access granted. Send me a video link to get started.");
    pending.ifPresent(request -> markResolved(request, tapped, "Approved."));
    return "User " + userId + " allowed.";
  }

  private String deny(long userId, Tapped tapped) {
    Optional<PendingRequest> pending = access.pendingRequest(userId);
    RemoveResult result = access.deny(userId, access.ownerId());
    return switch (result) {
      case OWNER -> "The owner can't be removed.";
      case NOT_FOUND -> "User " + userId + " has no request and no access.";
      case REMOVED -> {
        notifyUser(
            userId,
            pending.isPresent() ? "Your access request was declined." : "Your access was removed.");
        pending.ifPresent(request -> markResolved(request, tapped, "Denied."));
        yield "User " + userId + " removed.";
      }
    };
  }

  /** Replaces the request message's buttons with the outcome, from the tap or the stored id. */
  private void markResolved(PendingRequest request, Tapped tapped, String outcome) {
    long chatId = tapped != null ? tapped.chatId() : access.ownerId();
    Optional<Integer> messageId =
        tapped != null
            ? Optional.of(tapped.messageId())
            : Optional.ofNullable(request.requestMessageId());
    if (messageId.isEmpty()) {
      return;
    }
    try {
      gateway.editMessage(
          chatId, messageId.get(), requestText(request) + "\n\n" + outcome, List.of());
    } catch (TelegramGatewayException e) {
      long userId = request.id();
      LOG.warn("Could not update the request message for user {}", userId, e);
    }
  }

  private void notifyUser(long userId, String text) {
    try {
      gateway.sendMessage(userId, text, List.of());
    } catch (TelegramGatewayException e) {
      LOG.warn("Could not notify user {}", userId, e);
    }
  }

  private String usersText() {
    StringBuilder text = new StringBuilder("<b>Allowed users</b>\n");
    List<AllowedUser> users = access.users();
    if (users.isEmpty()) {
      text.append("none\n");
    }
    users.forEach(user -> text.append(user.id()).append(handle(user.username())).append('\n'));
    text.append("\n<b>Pending requests</b>\n");
    List<PendingRequest> pending = access.pending();
    if (pending.isEmpty()) {
      text.append("none\n");
    }
    pending.forEach(request ->
        text.append(request.id())
            .append(handle(request.username()))
            .append(" - ")
            .append(Html.escape(request.displayName()))
            .append('\n'));
    return text.toString().stripTrailing();
  }

  private static String requestText(PendingRequest request) {
    String who =
        request.username() == null
            ? "id <code>" + request.id() + "</code>"
            : "@" + Html.escape(request.username()) + ", id <code>" + request.id() + "</code>";
    return "Access request from <b>" + Html.escape(request.displayName()) + "</b> (" + who + ")";
  }

  private static String handle(String username) {
    return username == null ? "" : " @" + Html.escape(username);
  }

  private static Optional<Long> parseId(String text) {
    try {
      long id = Long.parseLong(text.strip());
      return id > 0 ? Optional.of(id) : Optional.empty();
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  private void reply(Sender sender, String html) {
    gateway.sendMessage(sender.chatId(), html, List.of());
  }
}
