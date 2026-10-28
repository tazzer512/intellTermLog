package dev.logconsole;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the bundled complete.bash in a real (non-interactive) bash, the way the Tab key uses it. */
@EnabledOnOs({OS.LINUX, OS.MAC})
class CompleteBashTest {
    @TempDir
    Path dir;
    private Path script;

    @BeforeEach
    void setUp() throws IOException {
        script = dir.resolve("complete.bash");
        try (InputStream in = LogConsolePanel.class.getResourceAsStream("complete.bash")) {
            Files.copy(in, script);
        }
        Files.createDirectories(dir.resolve("work/src/main"));
        Files.createDirectories(dir.resolve("work/samples"));
        Files.createFile(dir.resolve("work/samples.txt"));
        Files.createFile(dir.resolve("work/my file.txt"));
    }

    private List<String> complete(String line) throws Exception {
        String command = "source " + ShellText.shellQuote(script.toString())
                + "; cd " + ShellText.shellQuote(dir.resolve("work").toString())
                + "; lcfunc() { :; }; alias lcalias=true; export LC_TEST_VAR=1"
                + "; __lc_complete " + ShellText.shellQuote(line);
        Process p = new ProcessBuilder("bash", "--norc", "--noprofile", "-c", command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(10, TimeUnit.SECONDS));
        return Arrays.stream(out.split("\n")).filter(s -> !s.isEmpty()).sorted().toList();
    }

    @Test
    void completesFilesAndMarksDirectories() throws Exception {
        assertEquals(List.of("samples.txt", "samples/"), complete("cat sam"));
        assertEquals(List.of("src/main/"), complete("ls src/ma"));
        assertEquals(List.of("my file.txt"), complete("cat my"));
    }

    @Test
    void completesCommandsFunctionsAndAliasesInCommandPosition() throws Exception {
        assertTrue(complete("ech").contains("echo"));
        assertTrue(complete("lcfu").contains("lcfunc"));
        assertTrue(complete("lcal").contains("lcalias"));
    }

    @Test
    void wordAfterAPipeOrSeparatorIsACommand() throws Exception {
        assertTrue(complete("ls | ech").contains("echo"));
        assertTrue(complete("ls|ech").contains("echo"));
        assertTrue(complete("cd /; ech").contains("echo"));
        assertTrue(complete("true && ech").contains("echo"));
        assertTrue(complete("sudo ech").contains("echo"));
    }

    @Test
    void pathInCommandPositionCompletesFiles() throws Exception {
        assertEquals(List.of("./samples.txt", "./samples/"), complete("./sam"));
    }

    @Test
    void completesVariables() throws Exception {
        assertEquals(List.of("$LC_TEST_VAR"), complete("echo $LC_TEST"));
    }

    @Test
    void emptyResultForNoMatch() throws Exception {
        assertEquals(List.of(), complete("cat zzz-no-such-file"));
    }
}
