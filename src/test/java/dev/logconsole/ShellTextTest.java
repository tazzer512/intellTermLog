package dev.logconsole;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShellTextTest {
    @Test
    void shellQuote() {
        assertEquals("'plain'", ShellText.shellQuote("plain"));
        assertEquals("'it'\\''s'", ShellText.shellQuote("it's"));
        assertEquals("'$HOME `x` \"y\"'", ShellText.shellQuote("$HOME `x` \"y\""));
        assertEquals("''", ShellText.shellQuote(""));
    }

    @Test
    void escapeKeepsLeadingTildeAndDollar() {
        assertEquals("my\\ file\\(1\\).txt", ShellText.escape("my file(1).txt"));
        assertEquals("~/a\\ b/", ShellText.escape("~/a b/"));
        assertEquals("$HOME", ShellText.escape("$HOME"));
        assertEquals("a\\&b\\;c\\|d\\<e\\>f", ShellText.escape("a&b;c|d<e>f"));
        assertEquals("\\'\\\"\\\\\\`\\!\\*\\?\\[\\]\\{\\}\\#", ShellText.escape("'\"\\`!*?[]{}#"));
    }

    @Test
    void completionTextAddsSpaceExceptForDirectories() {
        assertEquals("checkout ", ShellText.completionText("checkout"));
        assertEquals("src/", ShellText.completionText("src/"));
        assertEquals("my\\ dir/", ShellText.completionText("my dir/"));
    }

    @Test
    void commonPrefix() {
        assertEquals("che", ShellText.commonPrefix(List.of("checkout", "cherry-pick", "cherry")));
        assertEquals("", ShellText.commonPrefix(List.of("a", "b")));
        assertEquals("same", ShellText.commonPrefix(List.of("same")));
        assertEquals("", ShellText.commonPrefix(List.of()));
    }

    @Test
    void wordStart() {
        assertEquals(4, ShellText.wordStart("git che", 0, 7));
        assertEquals(0, ShellText.wordStart("gre", 0, 3));
        assertEquals(3, ShellText.wordStart("ls|gr", 0, 5));
        assertEquals(5, ShellText.wordStart("a && b", 0, 6));
        assertEquals(5, ShellText.wordStart("x\nab cd", 2, 7));
        assertEquals(2, ShellText.wordStart("x\nabc", 2, 5));
    }

    @Test
    void fileCompletions(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("src/main"));
        Files.createFile(dir.resolve("samples.txt"));
        Files.createFile(dir.resolve(".hidden"));
        Files.createFile(dir.resolve("src/main/App.java"));
        String cwd = dir.toString();

        assertEquals(List.of("samples.txt"), sorted(ShellText.fileCompletions("sam", cwd, "/nonexistent")));
        assertEquals(List.of("samples.txt", "src/"), sorted(ShellText.fileCompletions("", cwd, "/nonexistent")));
        assertEquals(List.of(".hidden"), sorted(ShellText.fileCompletions(".", cwd, "/nonexistent")));
        assertEquals(List.of("src/main/"), ShellText.fileCompletions("src/ma", cwd, "/nonexistent"));
        assertEquals(List.of(cwd + "/src/main/App.java"), ShellText.fileCompletions(cwd + "/src/main/A", "/", "/nonexistent"));
        assertEquals(List.of("~/src/"), ShellText.fileCompletions("~/sr", "/", cwd));
        assertEquals(List.of(), ShellText.fileCompletions("nope/x", cwd, "/nonexistent"));
    }

    private static List<String> sorted(List<String> list) {
        return list.stream().sorted().toList();
    }

    @Test
    void shortPath() {
        assertEquals("~", ShellText.shortPath("/home/me", "/home/me"));
        assertEquals("~/projects/app", ShellText.shortPath("/home/me/projects/app", "/home/me"));
        assertEquals("/home/meier/app", ShellText.shortPath("/home/meier/app", "/home/me"));
        assertEquals("/opt/app", ShellText.shortPath("/opt/app", "/home/me"));
    }

    @Test
    void tabTitle() {
        assertEquals("run-all.sh", ShellText.tabTitle("sh run-all.sh"));
        assertEquals("demo.sh", ShellText.tabTitle("cd /home/me/samples && bash 'demo.sh'"));
        assertEquals("app.jar", ShellText.tabTitle("java -jar target/app.jar --debug"));
        assertEquals("mvn spring-boot:run", ShellText.tabTitle("  mvn   spring-boot:run "));
        assertEquals("echo " + "a".repeat(24) + "…", ShellText.tabTitle("echo " + "a".repeat(40)));
    }
}
