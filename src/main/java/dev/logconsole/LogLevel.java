package dev.logconsole;

public enum LogLevel {
    ERROR,
    WARN,
    DEBUG,
    OTHER,
    /** A command typed by the user, echoed into the output. Always visible. */
    COMMAND,
    /** Messages from the Log Console itself (shell started/stopped). Always visible. */
    SYSTEM
}
