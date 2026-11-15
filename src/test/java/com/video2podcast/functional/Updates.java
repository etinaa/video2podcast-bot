package com.video2podcast.functional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicInteger;
import org.telegram.telegrambots.meta.api.objects.Update;

/** Builds Telegram updates from the JSON Telegram would send. */
final class Updates {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final AtomicInteger IDS = new AtomicInteger(1);

  private Updates() {}

  static Update privateMessage(long userId, String text) {
    return message("private", userId, userId, text);
  }

  static Update groupMessage(long chatId, long userId, String text) {
    return message("group", chatId, userId, text);
  }

  private static Update message(String chatType, long chatId, long userId, String text) {
    int id = IDS.getAndIncrement();
    return parse(
        """
        {"update_id":%d,"message":{"message_id":%d,"date":1700000000,
         "chat":{"id":%d,"type":"%s"},
         "from":{"id":%d,"is_bot":false,"first_name":"Test","username":"user%d"},
         "text":"%s"}}
        """
            .formatted(id, id, chatId, chatType, userId, userId, text));
  }

  private static Update parse(String json) {
    try {
      return MAPPER.readValue(json, Update.class);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Bad update JSON: " + json, e);
    }
  }
}
