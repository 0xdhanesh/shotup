package shotup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpCommandsTest {
    @Test
    void toolsCatalogNamesTheCaptures() {
        Fake actions = new Fake();
        McpCommands.Reply reply = McpCommands.execute("tools", "", actions);

        assertEquals(200, reply.status());
        assertTrue(reply.body().contains("\"extension\":\"ShotUp\""));
        assertTrue(reply.body().contains("\"version\":\"2.0.0\""));
        assertTrue(reply.body().contains("\"commandHeader\":\"X-Burp-Mcp-Discover\""));
        assertTrue(reply.body().contains(
                "\"commands\":\"tools, status, capture-proxy, capture-repeater, capture-both, set-output-directory\""));
        assertTrue(reply.body().contains("{\"name\":\"capture-proxy\",\"enabled\":true,"));
        assertTrue(reply.body().contains("{\"name\":\"capture-repeater\",\"enabled\":true,"));
        assertTrue(reply.body().contains("{\"name\":\"capture-both\",\"enabled\":true,"));
        assertTrue(reply.body().contains("{\"name\":\"status\",\"enabled\":true,"));
        assertTrue(reply.body().contains("{\"name\":\"set-output-directory\",\"enabled\":true,"));
        assertEquals(0, jsonDepth(reply.body()));
        assertEquals(0, actions.captures);
    }

    @Test
    void missingCommandIsRejected() {
        McpCommands.Reply reply = McpCommands.execute("  ", "", new Fake());
        assertEquals(400, reply.status());
        assertTrue(reply.body().contains("Missing X-Burp-Mcp-Discover"));
    }

    @Test
    void toolsStayListedWhenTheBodyIsNotJson() {
        McpCommands.Reply reply = McpCommands.execute("tools", "not-json", new Fake());
        assertEquals(200, reply.status());
        assertTrue(reply.body().contains("\"action\":\"tools\""));
    }

    @Test
    void captureToolsReportDisabledWhileBusy() {
        Fake actions = new Fake();
        actions.busy = true;
        McpCommands.Reply reply = McpCommands.execute("tools", null, actions);
        assertTrue(reply.body().contains("{\"name\":\"capture-proxy\",\"enabled\":false,"));
        assertTrue(reply.body().contains("{\"name\":\"status\",\"enabled\":true,"));
    }

    @Test
    void statusReportsTheFolder() {
        Fake actions = new Fake();
        McpCommands.Reply reply = McpCommands.execute("status", "   ", actions);
        assertEquals(200, reply.status());
        assertEquals(
                "{\"ok\":true,\"extension\":\"ShotUp\",\"version\":\"2.0.0\",\"action\":\"status\","
                        + "\"busy\":false,\"outputDirectory\":\"/tmp/ShotUp\","
                        + "\"commandHeader\":\"X-Burp-Mcp-Discover\","
                        + "\"commands\":\"tools, status, capture-proxy, capture-repeater, capture-both, set-output-directory\"}",
                reply.body());
    }

    @Test
    void captureProxyUsesTheOptionalFolderAndReturnsPaths() {
        Fake actions = new Fake();
        McpCommands.Reply reply = McpCommands.execute(
                "CAPTURE_PROXY",
                "{\"outputDir\":\"/tmp/shots\"}",
                actions);

        assertEquals(200, reply.status());
        assertEquals(1, actions.captures);
        assertTrue(actions.proxy);
        assertFalse(actions.repeater);
        assertEquals("/tmp/shots", actions.requestedDirectory);
        assertEquals(
                "{\"ok\":true,\"extension\":\"ShotUp\",\"version\":\"2.0.0\",\"action\":\"capture-proxy\","
                        + "\"outputDirectory\":\"/tmp/ShotUp/20261010-120000\",\"count\":1,\"files\":["
                        + "{\"name\":\"proxy-intercept\",\"path\":\"/tmp/ShotUp/20261010-120000/proxy-intercept.png\","
                        + "\"detail\":\"Request was truncated at 8000x14000 pixels.\"}]}",
                reply.body());
    }

    @Test
    void captureBothPassesBothSections() {
        Fake actions = new Fake();
        McpCommands.execute("capture-both", "{}", actions);
        assertTrue(actions.proxy);
        assertTrue(actions.repeater);
        assertEquals(null, actions.requestedDirectory);
    }

    @Test
    void invalidCaptureBodyDoesNotCapture() {
        Fake actions = new Fake();
        McpCommands.Reply reply = McpCommands.execute("capture-repeater", "[]", actions);
        assertEquals(400, reply.status());
        assertEquals(0, actions.captures);
        assertTrue(reply.body().contains("JSON object"));
    }

    @Test
    void emptyOutputDirectoryIsRejected() {
        Fake actions = new Fake();
        McpCommands.Reply reply = McpCommands.execute("capture_both", "{\"outputDirectory\":\"  \"}", actions);
        assertEquals(400, reply.status());
        assertEquals(0, actions.captures);
        assertTrue(reply.body().contains("outputDirectory is empty"));
    }

    @Test
    void alreadyRunningIsAConflict() {
        Fake actions = new Fake();
        actions.outcome = McpCommands.CaptureOutcome.inProgress();
        McpCommands.Reply reply = McpCommands.execute("capture-proxy", "", actions);
        assertEquals(409, reply.status());
        assertTrue(reply.body().contains("\"ok\":false"));
        assertTrue(reply.body().contains("A capture is already running."));
    }

    @Test
    void captureFailureIsAnError() {
        Fake actions = new Fake();
        actions.outcome = McpCommands.CaptureOutcome.failed("The Burp window is not open. Restore it and try again.");
        McpCommands.Reply reply = McpCommands.execute("capture-repeater", "", actions);
        assertEquals(500, reply.status());
        assertTrue(reply.body().contains("The Burp window is not open. Restore it and try again."));
        assertTrue(reply.body().contains("\"action\":\"capture-repeater\""));
    }

    @Test
    void setOutputDirectoryUpdatesTheFolder() {
        Fake actions = new Fake();
        McpCommands.Reply reply = McpCommands.execute(
                "set_output_directory",
                "{ \"directory\" : \"/var/shots\\\"quoted\" }",
                actions);
        assertEquals(200, reply.status());
        assertEquals("/var/shots\"quoted", actions.directory);
        assertTrue(reply.body().contains("\"outputDirectory\":\"/var/shots\\\"quoted\""));
    }

    @Test
    void setOutputDirectoryRequiresAFolder() {
        McpCommands.Reply reply = McpCommands.execute("set-output-directory", "{}", new Fake());
        assertEquals(400, reply.status());
        assertTrue(reply.body().contains("outputDirectory"));
    }

    @Test
    void unknownCommandIsRejected() {
        Fake actions = new Fake();
        McpCommands.Reply reply = McpCommands.execute("delete", "", actions);
        assertEquals(400, reply.status());
        assertEquals(0, actions.captures);
        assertTrue(reply.body().contains("Unknown command 'delete'"));
    }

    @Test
    void quotesAndControlsAreEscaped() {
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\"", JsonText.quote("a\"b\\c\n\u0001"));
    }

    @Test
    void objectReaderAcceptsEscapes() {
        Map<String, String> fields = JsonText.object("{\"outputDirectory\":\"C:\\\\shots\\u002fday\"}");
        assertEquals("C:\\shots/day", fields.get("outputDirectory"));
    }

    @Test
    void outputDirectoryWinsOverAliases() {
        Fake actions = new Fake();
        McpCommands.execute(
                "capture-proxy",
                "{\"directory\":\"/from-directory\",\"outputDir\":\"/from-dir\",\"outputDirectory\":\"/from-output\"}",
                actions);
        assertEquals("/from-output", actions.requestedDirectory);
    }

    private static int jsonDepth(String json) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            }
            if (depth < 0) {
                return depth;
            }
        }
        return inString ? -1 : depth;
    }

    private static final class Fake implements McpCommands.ShotActions {
        private boolean busy;
        private String directory = "/tmp/ShotUp";
        private int captures;
        private boolean proxy;
        private boolean repeater;
        private String requestedDirectory;
        private McpCommands.CaptureOutcome outcome = McpCommands.CaptureOutcome.saved(
                "/tmp/ShotUp/20261010-120000",
                List.of(new McpCommands.SavedFile(
                        "proxy-intercept",
                        "/tmp/ShotUp/20261010-120000/proxy-intercept.png",
                        "Request was truncated at 8000x14000 pixels.")));

        @Override
        public boolean busy() {
            return busy;
        }

        @Override
        public String outputDirectory() {
            return directory;
        }

        @Override
        public void setOutputDirectory(String directory) {
            this.directory = directory;
        }

        @Override
        public McpCommands.CaptureOutcome capture(boolean proxy, boolean repeater, String outputDirectory) {
            captures++;
            this.proxy = proxy;
            this.repeater = repeater;
            requestedDirectory = outputDirectory;
            return outcome;
        }
    }
}
