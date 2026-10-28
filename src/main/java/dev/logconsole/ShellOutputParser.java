package dev.logconsole;

import java.util.regex.Pattern;

/**
 * Splits the shell's PTY output into lines and recognizes the invisible markers the shell prints:
 * {@link #PWD_MARKER} (from PROMPT_COMMAND: the shell is idle again, with its working directory) and
 * {@link #COMPLETION_MARKER} (a Tab completion request finished). Not thread-safe; callers synchronize.
 */
final class ShellOutputParser {
    static final String PWD_MARKER = "\u001ELCPWD:";
    static final String COMPLETION_MARKER = "\u001ELCDONE:";
    /** A longer line (e.g. minified JSON, or output that never ends with a newline) is emitted in pieces. */
    static final int MAX_LINE_LENGTH = 32 * 1024;
    private static final Pattern ANSI =
            // CSI (colors, cursor), OSC (window title), designations like ESC ( B, and other 2-char sequences
            Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)|\u001B[ -/]+[0-~]|\u001B[@-Z\\\\-_]");

    interface Listener {
        /** An output line, without ANSI escape sequences. */
        void line(String text);

        /** The shell printed its prompt marker: the previous command finished. */
        void prompt(String directory);

        void completionDone(String id);
    }

    private final Listener listener;
    private final StringBuilder buffer = new StringBuilder();
    /** Output before the first prompt marker (bashrc noise, our init line) is dropped. */
    private boolean ready;
    /** A '\r' was seen; if anything but '\n' follows, the line is being overwritten (progress bars). */
    private boolean carriageReturn;

    ShellOutputParser(Listener listener) {
        this.listener = listener;
    }

    static String stripAnsi(String text) {
        return text.indexOf('\u001B') < 0 ? text : ANSI.matcher(text).replaceAll("");
    }

    void feed(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                carriageReturn = false;
                line();
            } else if (c == '\r') {
                carriageReturn = true;
            } else {
                if (carriageReturn) {
                    carriageReturn = false;
                    buffer.setLength(0);
                }
                buffer.append(c);
                if (buffer.length() >= MAX_LINE_LENGTH) line();
            }
        }
    }

    /** Emits a last unterminated line, e.g. when the shell exits. */
    void finish() {
        if (!buffer.isEmpty()) line();
    }

    private void line() {
        String raw = buffer.toString();
        buffer.setLength(0);
        int done = raw.indexOf(COMPLETION_MARKER);
        if (done >= 0) {
            if (ready && done > 0) emit(raw.substring(0, done));
            listener.completionDone(raw.substring(done + COMPLETION_MARKER.length()).trim());
            return;
        }
        int marker = raw.indexOf(PWD_MARKER);
        if (marker >= 0) {
            if (ready && marker > 0) emit(raw.substring(0, marker));
            ready = true;
            listener.prompt(raw.substring(marker + PWD_MARKER.length()));
        } else if (ready) {
            emit(raw);
        }
    }

    private void emit(String text) {
        listener.line(stripAnsi(text));
    }
}
