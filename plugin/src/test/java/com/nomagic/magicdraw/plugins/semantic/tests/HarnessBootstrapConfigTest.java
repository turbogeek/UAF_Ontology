package com.nomagic.magicdraw.plugins.semantic.tests;

import com.nomagic.magicdraw.plugins.semantic.HarnessBootstrap;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Validates the harness-config repair logic that lets the integrated REST harness
 * auto-start without tripping the script's modal "locate SysMLv2Logger.groovy" dialog.
 */
public class HarnessBootstrapConfigTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File harnessDirWithLogger() throws Exception {
        File dir = temp.newFolder("harness");
        Files.writeString(new File(dir, "SysMLv2Logger.groovy").toPath(), "// logger", StandardCharsets.UTF_8);
        return dir;
    }

    @Test
    public void testFreshConfigIsCreated() throws Exception {
        File config = new File(temp.newFolder("cfg"), "config.json");
        File harnessDir = harnessDirWithLogger();

        assertTrue(HarnessBootstrap.ensureHarnessConfig(config, harnessDir));
        assertTrue(config.exists());
        Map<String, String> parsed = HarnessBootstrap.parseFlatJson(
                Files.readString(config.toPath(), StandardCharsets.UTF_8));
        assertEquals(harnessDir.getAbsolutePath(), parsed.get("harnessPath"));
        assertFalse(parsed.get("logPath").isBlank());
        assertEquals("ERROR", parsed.get("guiLogLevel"));
    }

    @Test
    public void testValidConfigIsPreserved() throws Exception {
        File harnessDir = harnessDirWithLogger();
        File config = new File(temp.newFolder("cfg"), "config.json");
        String original = HarnessBootstrap.encodeFlatJson(Map.of(
                "harnessPath", harnessDir.getAbsolutePath(),
                "projectPath", "C:\\work",
                "logPath", "C:\\logs",
                "guiLogLevel", "INFO"));
        Files.writeString(config.toPath(), original, StandardCharsets.UTF_8);

        assertFalse("hand-tuned config must survive untouched",
                HarnessBootstrap.ensureHarnessConfig(config, harnessDir));
        Map<String, String> parsed = HarnessBootstrap.parseFlatJson(
                Files.readString(config.toPath(), StandardCharsets.UTF_8));
        assertEquals("INFO", parsed.get("guiLogLevel"));
        assertEquals("C:\\logs", parsed.get("logPath"));
    }

    @Test
    public void testBrokenHarnessPathIsRepaired() throws Exception {
        File harnessDir = harnessDirWithLogger();
        File config = new File(temp.newFolder("cfg"), "config.json");
        Files.writeString(config.toPath(),
                "{\"harnessPath\":\"C:\\\\no\\\\such\\\\dir\",\"projectPath\":\"\",\"logPath\":\"\"}",
                StandardCharsets.UTF_8);

        assertTrue(HarnessBootstrap.ensureHarnessConfig(config, harnessDir));
        Map<String, String> parsed = HarnessBootstrap.parseFlatJson(
                Files.readString(config.toPath(), StandardCharsets.UTF_8));
        assertEquals(harnessDir.getAbsolutePath(), parsed.get("harnessPath"));
        assertFalse("empty logPath must be filled", parsed.get("logPath").isBlank());
    }

    @Test
    public void testJsonRoundTripEscapesWindowsPaths() {
        Map<String, String> map = Map.of("harnessPath", "E:\\Magic SW\\plugins\\x", "quote", "a\"b");
        Map<String, String> back = HarnessBootstrap.parseFlatJson(HarnessBootstrap.encodeFlatJson(map));
        assertEquals("E:\\Magic SW\\plugins\\x", back.get("harnessPath"));
        assertEquals("a\"b", back.get("quote"));
    }
}
