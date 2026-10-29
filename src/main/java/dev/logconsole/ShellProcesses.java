package dev.logconsole;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Finds the processes started from a Log Console shell, for Stop and Kill.
 * <p>
 * Three sources, because each misses something:
 * <ul>
 *   <li>the shell's process tree;</li>
 *   <li>its terminal session (processes whose parent already exited, e.g. apps of a script without 'wait');</li>
 *   <li>an environment variable the shell is started with and every child inherits, found via /proc
 *       (processes that detached with setsid or a double fork and so left both the tree and the session).</li>
 * </ul>
 * ProcessHandle checks the start time as well as the pid, so a reused pid is never signalled later.
 */
final class ShellProcesses {
    static final String TAG_VARIABLE = "LOG_CONSOLE_SESSION";

    private ShellProcesses() {
    }

    /** A value for {@link #TAG_VARIABLE} that is unique to one shell. */
    static String newTag() {
        return UUID.randomUUID().toString();
    }

    /** All live processes started from the shell, excluding the shell itself and this JVM. */
    static List<ProcessHandle> startedFrom(ProcessHandle shell, String tag) {
        Map<Long, ProcessHandle> found = new LinkedHashMap<>();
        shell.descendants().forEach(p -> found.put(p.pid(), p));
        for (long pid : sessionMembers(shell.pid())) {
            ProcessHandle.of(pid).ifPresent(p -> {
                found.putIfAbsent(pid, p);
                p.descendants().forEach(d -> found.putIfAbsent(d.pid(), d));
            });
        }
        if (tag != null) {
            for (long pid : taggedWith(tag)) {
                ProcessHandle.of(pid).ifPresent(p -> found.putIfAbsent(pid, p));
            }
        }
        found.remove(shell.pid());
        found.remove(ProcessHandle.current().pid());
        return found.values().stream().filter(ProcessHandle::isAlive).toList();
    }

    /**
     * Like {@code pkill -f java}: every live process whose command line contains "java", but never this IDE or the
     * processes it runs under (e.g. the Gradle daemon of {@code runIde}), since killing those would end the IDE.
     */
    static List<ProcessHandle> javaProcesses() {
        java.util.Set<Long> ide = new java.util.HashSet<>();
        for (ProcessHandle p = ProcessHandle.current(); p != null; p = p.parent().orElse(null)) ide.add(p.pid());
        return ProcessHandle.allProcesses()
                .filter(p -> !ide.contains(p.pid()) && commandLine(p).contains("java"))
                .toList();
    }

    /** The full command line, as {@code pkill -f} matches it; Linux reads it from /proc so all arguments are seen. */
    static String commandLine(ProcessHandle process) {
        try {
            byte[] cmdline = Files.readAllBytes(Path.of("/proc", String.valueOf(process.pid()), "cmdline"));
            return new String(cmdline, StandardCharsets.UTF_8).replace('\0', ' ').strip();
        } catch (IOException | SecurityException | java.nio.file.InvalidPathException e) {
            ProcessHandle.Info info = process.info();
            return info.commandLine().orElse(info.command().orElse(""));
        }
    }

    /** Sends SIGHUP (which {@link ProcessHandle} can't), e.g. to end an interactive bash, which ignores SIGTERM. */
    static void hangUp(ProcessHandle process) {
        if (!process.isAlive()) return;
        try {
            new ProcessBuilder("kill", "-HUP", String.valueOf(process.pid())).start().waitFor(2, TimeUnit.SECONDS);
        } catch (IOException e) {
            // the SIGKILL fallback still ends it
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Pids in the session with this id (a shell on its own PTY leads a session whose id is its pid). */
    static List<Long> sessionMembers(long sessionId) {
        try {
            Process p = new ProcessBuilder("pgrep", "-s", String.valueOf(sessionId)).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(2, TimeUnit.SECONDS);
            return parsePids(out);
        } catch (IOException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    static List<Long> parsePids(String text) {
        List<Long> pids = new ArrayList<>();
        for (String token : text.split("\\s+")) {
            if (token.matches("\\d{1,18}")) pids.add(Long.parseLong(token));
        }
        return pids;
    }

    /** Pids of this user's processes whose environment contains {@code TAG_VARIABLE=tag}. Linux only. */
    static List<Long> taggedWith(String tag) {
        Path proc = Path.of("/proc");
        if (!Files.isDirectory(proc)) return List.of();
        byte[] needle = (TAG_VARIABLE + "=" + tag).getBytes(StandardCharsets.UTF_8);
        List<Long> pids = new ArrayList<>();
        try (Stream<Path> entries = Files.list(proc)) {
            entries.forEach(dir -> {
                String name = dir.getFileName().toString();
                if (!name.matches("\\d{1,18}")) return;
                try {
                    if (hasEnvironmentEntry(Files.readAllBytes(dir.resolve("environ")), needle)) {
                        pids.add(Long.parseLong(name));
                    }
                } catch (IOException | SecurityException ignored) {
                    // another user's process, or it just exited
                }
            });
        } catch (IOException e) {
            return List.of();
        }
        return pids;
    }

    /** Whether a NUL-separated environment block contains exactly this entry. */
    static boolean hasEnvironmentEntry(byte[] environ, byte[] entry) {
        int start = 0;
        while (start <= environ.length) {
            int end = start;
            while (end < environ.length && environ[end] != 0) end++;
            if (end - start == entry.length && java.util.Arrays.equals(environ, start, end, entry, 0, entry.length)) {
                return true;
            }
            start = end + 1;
        }
        return false;
    }

    /** E.g. "3 processes (bash, java, java)". */
    static String describe(List<ProcessHandle> processes) {
        String names = processes.stream()
                .map(p -> p.info().command().map(c -> c.substring(c.lastIndexOf('/') + 1)).orElse(String.valueOf(p.pid())))
                .limit(8)
                .reduce((a, b) -> a + ", " + b).orElse("");
        if (processes.size() > 8) names += ", …";
        return processes.size() + (processes.size() == 1 ? " process (" : " processes (") + names + ")";
    }
}
