package dev.logconsole;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
    void describeNamesTheCommandsOfAtMostEightProcesses() {
        ProcessHandle self = ProcessHandle.current(); // this test JVM: ".../bin/java"
        assertEquals("1 process (java)", ShellProcesses.describe(List.of(self)));
        assertEquals("2 processes (java, java)", ShellProcesses.describe(List.of(self, self)));
        assertEquals("9 processes (" + String.join(", ", Collections.nCopies(8, "java")) + ", …)",
                ShellProcesses.describe(Collections.nCopies(9, self)));
    }

    /** Like pkill -f: "java" anywhere in the command line counts, also in an argument. */
    @Test
    @EnabledOnOs(OS.LINUX)
    void javaProcessesMatchTheWholeCommandLine() throws Exception {
        Process javaArgument = new ProcessBuilder("bash", "-c", "sleep 407; : java-marker").start();
        Process other = new ProcessBuilder("sleep", "408").start();
        started.add(javaArgument.toHandle());
        started.add(other.toHandle());

        List<Long> pids = ShellProcesses.javaProcesses().stream().map(ProcessHandle::pid).toList();
        assertTrue(pids.contains(javaArgument.pid()));
        assertFalse(pids.contains(other.pid()));
    }

    /** Killing the JVM the plugin runs in (or the Gradle daemon a sandbox IDE runs under) would end the IDE. */
    @Test
    @EnabledOnOs(OS.LINUX)
    void javaProcessesNeverIncludeThisJvmOrItsAncestors() {
        List<Long> pids = ShellProcesses.javaProcesses().stream().map(ProcessHandle::pid).toList();
        for (ProcessHandle p = ProcessHandle.current(); p != null; p = p.parent().orElse(null)) {
            assertFalse(pids.contains(p.pid()), "includes " + p.pid() + " " + p.info().command().orElse("?"));
        }
    }

    /** Other users' processes are out of reach anyway (no permission without root) and none of our business. */
    @Test
    @EnabledOnOs(OS.LINUX)
    void javaProcessesAreOnlyThoseOfThisUser() {
        String me = ProcessHandle.current().info().user().orElseThrow();
        for (ProcessHandle p : ShellProcesses.javaProcesses()) {
            assertEquals(me, p.info().user().orElse("?"), "pid " + p.pid());
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void ownedByComparesTheProcessOwner() {
        String me = ProcessHandle.current().info().user().orElseThrow();
        assertTrue(ShellProcesses.ownedBy(ProcessHandle.current(), me));
        assertFalse(ShellProcesses.ownedBy(ProcessHandle.current(), me + "-someone-else"));
        ProcessHandle init = ProcessHandle.of(1).orElseThrow(); // owned by root
        org.junit.jupiter.api.Assumptions.assumeFalse("root".equals(me));
        assertFalse(ShellProcesses.ownedBy(init, me));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void hangUpEndsAnInteractiveBashThatIgnoresSigterm() throws Exception {
        Process bash = new ProcessBuilder("bash", "-i").redirectErrorStream(true).start(); // stdin stays open
        started.add(bash.toHandle());
        Thread.sleep(300);
        bash.toHandle().destroy();
        Thread.sleep(300);
        assertTrue(bash.isAlive(), "an interactive bash ignores SIGTERM");
        ShellProcesses.hangUp(bash.toHandle());
        assertTrue(bash.waitFor(2, TimeUnit.SECONDS));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void commandLineHasAllArguments() throws Exception {
        Process sleep = new ProcessBuilder("sleep", "409").start();
        started.add(sleep.toHandle());
        assertEquals("sleep 409", ShellProcesses.commandLine(sleep.toHandle()));
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
