package dev.logconsole;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellProcessesTest {
    private final List<ProcessHandle> started = new ArrayList<>();

    @AfterEach
    void killLeftovers() {
        for (ProcessHandle p : started) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
    }

    @Test
    void parsePids() {
        assertEquals(List.of(12L, 345L), ShellProcesses.parsePids("  12\n345\n"));
        assertEquals(List.of(), ShellProcesses.parsePids(""));
        assertEquals(List.of(7L), ShellProcesses.parsePids("pgrep: warning\n7"));
    }

    @Test
    void hasEnvironmentEntryMatchesWholeEntriesOnly() {
        byte[] env = "A=1\0LOG_CONSOLE_SESSION=abc\0B=2\0".getBytes(StandardCharsets.UTF_8);
        assertTrue(ShellProcesses.hasEnvironmentEntry(env, bytes("LOG_CONSOLE_SESSION=abc")));
        assertTrue(ShellProcesses.hasEnvironmentEntry(env, bytes("A=1")));
        assertFalse(ShellProcesses.hasEnvironmentEntry(env, bytes("LOG_CONSOLE_SESSION=ab")));
        assertFalse(ShellProcesses.hasEnvironmentEntry(env, bytes("OG_CONSOLE_SESSION=abc")));
        assertTrue(ShellProcesses.hasEnvironmentEntry(bytes("X=last"), bytes("X=last")));
        assertFalse(ShellProcesses.hasEnvironmentEntry(new byte[0], bytes("X=1")));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void newTagsAreUnique() {
        assertFalse(ShellProcesses.newTag().equals(ShellProcesses.newTag()));
    }

    /**
     * The shell starts four kinds of processes: a child, one that ignores SIGTERM, an orphan whose parent
     * exited, and one detached with setsid from a subshell (so it left both the process tree and the session).
     * All four must be found, the shell itself must not, and SIGTERM + SIGKILL must end them.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    void findsEverythingStartedFromTheShell() throws Exception {
        String tag = ShellProcesses.newTag();
        String script = "sleep 401 & "
                + "bash -c 'trap \"\" TERM; sleep 402' & "
                + "sh -c 'sleep 403 &' ; "
                + "(setsid sleep 404 &) ; "
                + "wait";
        ProcessBuilder builder = new ProcessBuilder("setsid", "bash", "-c", script);
        builder.environment().put(ShellProcesses.TAG_VARIABLE, tag);
        Process shell = builder.start();
        ProcessHandle shellHandle = ProcessHandle.of(shell.pid()).orElseThrow();
        started.add(shellHandle);

        List<ProcessHandle> found = waitForSleeps(shellHandle, tag, 4);
        started.addAll(found);
        assertTrue(sleepArguments(found).containsAll(List.of("401", "402", "403", "404")), () -> describe(found));
        assertFalse(found.stream().anyMatch(p -> p.pid() == shell.pid()), "the shell itself is not included");

        found.forEach(ProcessHandle::destroy);
        Thread.sleep(1_000);
        found.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        Thread.sleep(500);
        assertTrue(found.stream().noneMatch(ProcessHandle::isAlive), () -> describe(found));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void taggedWithFindsOnlyProcessesWithThatTag() throws Exception {
        String tag = ShellProcesses.newTag();
        ProcessBuilder builder = new ProcessBuilder("sleep", "405");
        builder.environment().put(ShellProcesses.TAG_VARIABLE, tag);
        Process tagged = builder.start();
        Process untagged = new ProcessBuilder("sleep", "406").start();
        started.add(tagged.toHandle());
        started.add(untagged.toHandle());

        List<Long> pids = ShellProcesses.taggedWith(tag);
        assertEquals(List.of(tagged.pid()), pids);
        assertEquals(List.of(), ShellProcesses.taggedWith(ShellProcesses.newTag()));
    }

    @Test
    void describe() {
        ProcessHandle self = ProcessHandle.current();
        assertTrue(ShellProcesses.describe(List.of(self)).startsWith("1 process ("));
        assertTrue(ShellProcesses.describe(List.of(self, self)).startsWith("2 processes ("));
        assertTrue(ShellProcesses.describe(java.util.Collections.nCopies(9, self)).endsWith(", …)"));
    }

    private static List<ProcessHandle> waitForSleeps(ProcessHandle shell, String tag, int count) throws InterruptedException {
        List<ProcessHandle> found = List.of();
        for (int i = 0; i < 50; i++) {
            found = ShellProcesses.startedFrom(shell, tag);
            if (sleepArguments(found).size() >= count) break;
            Thread.sleep(100);
        }
        return found;
    }

    private static List<String> sleepArguments(List<ProcessHandle> processes) {
        List<String> args = new ArrayList<>();
        for (ProcessHandle p : processes) {
            if (p.info().command().map(c -> c.endsWith("/sleep")).orElse(false)) {
                p.info().arguments().ifPresent(a -> args.add(String.join(" ", a)));
            }
        }
        return args;
    }

    private static String describe(List<ProcessHandle> processes) {
        StringBuilder sb = new StringBuilder();
        for (ProcessHandle p : processes) {
            sb.append(p.pid()).append(' ').append(p.info().commandLine().orElse("?")).append(p.isAlive() ? "" : " (dead)").append('\n');
        }
        return sb.toString();
    }
}
