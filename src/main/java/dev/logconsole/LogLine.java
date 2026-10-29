package dev.logconsole;

final class LogLine {
    enum Kind { OUTPUT, COMMAND, SYSTEM }

    final String text;
    final Kind kind;
    /** For OUTPUT lines assigned on the EDT by the panel; may change when the settings change. */
    LogLevel level;
    /** False for a stack trace line and the like, which belongs to the entry above it; set with the level. */
    boolean entryStart = true;
    /** 1-based position in the tab's output since it was opened; assigned on the EDT. */
    int number;
    /** Non-null when bookmarked: the bookmark's note, possibly empty. EDT-only. */
    String bookmarkNote;

    LogLine(String text, Kind kind) {
        this.text = text;
        this.kind = kind;
        this.level = switch (kind) {
            case OUTPUT -> LogLevel.OTHER;
            case COMMAND -> LogLevel.COMMAND;
            case SYSTEM -> LogLevel.SYSTEM;
        };
    }
}
