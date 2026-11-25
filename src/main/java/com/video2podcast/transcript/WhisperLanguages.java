package com.video2podcast.transcript;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Whisper's own language list: full names such as "russian" to ISO codes. whisper-server answers
 * {@code verbose_json} with the full name.
 */
final class WhisperLanguages {

  private static final String UNKNOWN = "unknown";
  private static final Pattern CODE = Pattern.compile("[a-z]{2,3}");

  private static final Map<String, String> NAME_TO_CODE = new HashMap<>();

  static {
    String[] pairs = {
      "english", "en",
      "chinese", "zh",
      "german", "de",
      "spanish", "es",
      "russian", "ru",
      "korean", "ko",
      "french", "fr",
      "japanese", "ja",
      "portuguese", "pt",
      "turkish", "tr",
      "polish", "pl",
      "catalan", "ca",
      "dutch", "nl",
      "arabic", "ar",
      "swedish", "sv",
      "italian", "it",
      "indonesian", "id",
      "hindi", "hi",
      "finnish", "fi",
      "vietnamese", "vi",
      "hebrew", "he",
      "ukrainian", "uk",
      "greek", "el",
      "malay", "ms",
      "czech", "cs",
      "romanian", "ro",
      "danish", "da",
      "hungarian", "hu",
      "tamil", "ta",
      "norwegian", "no",
      "thai", "th",
      "urdu", "ur",
      "croatian", "hr",
      "bulgarian", "bg",
      "lithuanian", "lt",
      "latin", "la",
      "maori", "mi",
      "malayalam", "ml",
      "welsh", "cy",
      "slovak", "sk",
      "telugu", "te",
      "persian", "fa",
      "latvian", "lv",
      "bengali", "bn",
      "serbian", "sr",
      "azerbaijani", "az",
      "slovenian", "sl",
      "kannada", "kn",
      "estonian", "et",
      "macedonian", "mk",
      "breton", "br",
      "basque", "eu",
      "icelandic", "is",
      "armenian", "hy",
      "nepali", "ne",
      "mongolian", "mn",
      "bosnian", "bs",
      "kazakh", "kk",
      "albanian", "sq",
      "swahili", "sw",
      "galician", "gl",
      "marathi", "mr",
      "punjabi", "pa",
      "sinhala", "si",
      "khmer", "km",
      "shona", "sn",
      "yoruba", "yo",
      "somali", "so",
      "afrikaans", "af",
      "occitan", "oc",
      "georgian", "ka",
      "belarusian", "be",
      "tajik", "tg",
      "sindhi", "sd",
      "gujarati", "gu",
      "amharic", "am",
      "yiddish", "yi",
      "lao", "lo",
      "uzbek", "uz",
      "faroese", "fo",
      "haitian creole", "ht",
      "pashto", "ps",
      "turkmen", "tk",
      "nynorsk", "nn",
      "maltese", "mt",
      "sanskrit", "sa",
      "luxembourgish", "lb",
      "myanmar", "my",
      "tibetan", "bo",
      "tagalog", "tl",
      "malagasy", "mg",
      "assamese", "as",
      "tatar", "tt",
      "hawaiian", "haw",
      "lingala", "ln",
      "hausa", "ha",
      "bashkir", "ba",
      "javanese", "jw",
      "sundanese", "su",
      "cantonese", "yue"
    };
    for (int i = 0; i < pairs.length; i += 2) {
      NAME_TO_CODE.put(pairs[i], pairs[i + 1]);
    }
  }

  private WhisperLanguages() {}

  /**
   * Trims and lower-cases. A known full name gives its code, anything that looks like a code is
   * returned as is (2-3 letters), everything else gives "unknown". The result holds only a-z, so it
   * is safe in a message. A null or blank argument gives "unknown".
   */
  static String toCode(String whisperLanguage) {
    if (whisperLanguage == null) {
      return UNKNOWN;
    }
    String value = whisperLanguage.strip().toLowerCase(Locale.ROOT);
    String code = NAME_TO_CODE.get(value);
    if (code != null) {
      return code;
    }
    return CODE.matcher(value).matches() ? value : UNKNOWN;
  }
}
