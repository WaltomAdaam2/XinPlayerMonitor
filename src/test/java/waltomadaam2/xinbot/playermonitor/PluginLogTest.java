package waltomadaam2.xinbot.playermonitor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PluginLogTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void fileOutputStripsAnsiSectionColors() throws Exception {
        PluginLog log = new PluginLog(temporaryDirectory);

        log.warn(SectionFormatter.header("Recent logins"));

        List<Path> files;
        try (var stream = Files.list(temporaryDirectory)) {
            files = stream.toList();
        }
        assertEquals(1, files.size());
        String content = Files.readString(files.get(0), StandardCharsets.UTF_8);
        assertFalse(content.contains("\u001B["), "file output must not contain ANSI escape sequences");
        assertFalse(content.contains("38;2;"), "file output must not contain true-color SGR parameters");
        assertFalse(content.contains("38;5;"), "file output must not contain xterm palette SGR parameters");
        assertEquals("===== Recent logins =====", content.substring(content.indexOf("] ") + 2).trim());
    }
}
