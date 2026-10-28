package dev.logconsole;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Pure text helpers for the shell input: quoting, escaping and Tab completion. */
final class ShellText {
    /** Characters that end a word when looking back from the caret for the word to complete. */
    static final String WORD_BREAKS = " \t;|&<>()";
    /** Characters backslash-escaped when a completion is inserted. */
    static final String SHELL_SPECIAL = " \t'\"\\()&;|<>`!*?[]{}#";

    private ShellText() {
    }

    /** Single-quotes a string for bash, e.g. {@code it's} becomes {@code 'it'\''s'}. */
    static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Backslash-escapes characters the shell would interpret, keeping a leading ~ or $ intact. */
    static String escape(String candidate) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (SHELL_SPECIAL.indexOf(c) >= 0) sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    /** What to insert for a chosen completion: escaped, plus a space unless it is a directory. */
    static String completionText(String candidate) {
        return escape(candidate) + (candidate.endsWith("/") ? "" : " ");
    }

    static String commonPrefix(List<String> values) {
        if (values.isEmpty()) return "";
        String prefix = values.get(0);
        for (String v : values) {
            int i = 0;
            while (i < prefix.length() && i < v.length() && prefix.charAt(i) == v.charAt(i)) i++;
            prefix = prefix.substring(0, i);
        }
        return prefix;
    }

    /** Start offset of the word that ends at {@code caret}, not looking before {@code lineStart}. */
    static int wordStart(String text, int lineStart, int caret) {
        int start = caret;
        while (start > lineStart && WORD_BREAKS.indexOf(text.charAt(start - 1)) < 0) start--;
        return start;
    }

    /**
     * File and directory names completing {@code word}, relative to {@code cwd}; {@code ~/} means {@code home}.
     * Directories end with '/'; hidden files only when the typed name starts with a dot.
     */
    static List<String> fileCompletions(String word, String cwd, String home) {
        int slash = word.lastIndexOf('/');
        String dirPart = word.substring(0, slash + 1);
        String namePrefix = word.substring(slash + 1);
        String expanded = dirPart.startsWith("~/") ? home + dirPart.substring(1) : dirPart;
        File dir = expanded.isEmpty() ? new File(cwd)
                : new File(expanded).isAbsolute() ? new File(expanded) : new File(cwd, expanded);
        File[] children = dir.listFiles();
        if (children == null) return List.of();
        List<String> result = new ArrayList<>();
        for (File child : children) {
            String name = child.getName();
            if (name.startsWith(namePrefix) && (namePrefix.startsWith(".") || !name.startsWith("."))) {
                result.add(dirPart + name + (child.isDirectory() ? "/" : ""));
            }
        }
        return result;
    }

    /** Replaces a leading home directory by '~'. */
    static String shortPath(String path, String home) {
        if (path == null) return "null";
        if (path.equals(home)) return "~";
        return path.startsWith(home + "/") ? "~" + path.substring(home.length()) : path;
    }

    /** The script or jar a command runs, e.g. "run-all.sh"; otherwise the command shortened to 30 chars. */
    static String tabTitle(String command) {
        for (String token : command.trim().split("\\s+")) {
            String t = unquote(token);
            if (t.matches(".*\\.(sh|bash|zsh|jar)")) return t.substring(t.lastIndexOf('/') + 1);
        }
        String flat = command.trim().replaceAll("\\s+", " ");
        return flat.length() <= 30 ? flat : flat.substring(0, 29) + "…";
    }

    private static String unquote(String token) {
        if (token.length() >= 2 && (token.startsWith("'") && token.endsWith("'") || token.startsWith("\"") && token.endsWith("\""))) {
            return token.substring(1, token.length() - 1);
        }
        return token;
    }
}
