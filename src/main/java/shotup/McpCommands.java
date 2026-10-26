package shotup;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Commands burp-mcp reaches with {@code Http.sendRequest}. Discovery sends
 * {@code X-Burp-Mcp-Discover: tools}. {@code list_extension_tools} reads
 * {@code extension}, {@code commands}, and {@code tools[].name} from the JSON.
 */
final class McpCommands {
    static final String HEADER = "X-Burp-Mcp-Discover";
    static final String COMMANDS =
            "tools, status, capture-proxy, capture-repeater, capture-both, set-output-directory";

    private McpCommands() {
    }

    record Reply(int status, String body) {
    }

    record SavedFile(String name, String path, String detail) {
    }

    record CaptureOutcome(boolean ok, boolean alreadyRunning, String directory, List<SavedFile> files, String error) {
        static CaptureOutcome saved(String directory, List<SavedFile> files) {
            return new CaptureOutcome(true, false, directory, List.copyOf(files), "");
        }

        static CaptureOutcome failed(String error) {
            return new CaptureOutcome(false, false, "", List.of(), error == null ? "Capture failed." : error);
        }

        static CaptureOutcome inProgress() {
            return new CaptureOutcome(false, true, "", List.of(), "A capture is already running.");
        }
    }

    interface ShotActions {
        boolean busy();

        String outputDirectory();

        void setOutputDirectory(String directory);

        CaptureOutcome capture(boolean proxy, boolean repeater, String outputDirectory, String tabName);
    }

    static Reply execute(String command, String body, ShotActions actions) {
        try {
            if (command == null || command.isBlank()) {
                return new Reply(400, error("Missing " + HEADER + "."));
            }
            return switch (canonical(command)) {
                case "tools" -> new Reply(200, tools(actions));
                case "status" -> new Reply(200, status(actions));
                case "capture-proxy" -> capture("capture-proxy", true, false, body, actions);
                case "capture-repeater" -> capture("capture-repeater", false, true, body, actions);
                case "capture-both" -> capture("capture-both", true, true, body, actions);
                case "set-output-directory" -> setOutputDirectory(body, actions);
                default -> new Reply(400, error("Unknown command '" + shown(command) + "'. Use " + spokenCommands() + "."));
            };
        } catch (IllegalArgumentException exception) {
            return new Reply(400, error(exception.getMessage()));
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null ? exception.toString() : exception.getMessage();
            return new Reply(500, error(message));
        }
    }

    private static Reply capture(String action, boolean proxy, boolean repeater, String body, ShotActions actions) {
        Map<String, String> fields = JsonText.object(body);
        String override = directoryOverride(fields);
        if (override != null && override.isBlank()) {
            return new Reply(400, error("outputDirectory is empty."));
        }
        String tabName = fields.get("tabName");
        if (tabName != null && (tabName.isBlank() || !repeater || proxy)) {
            return new Reply(400, error("tabName requires capture-repeater and a nonempty Repeater tab title."));
        }
        CaptureOutcome outcome = actions.capture(proxy, repeater, override, tabName);
        if (outcome.alreadyRunning()) {
            return new Reply(409, actionError(action, outcome.error()));
        }
        if (!outcome.ok()) {
            return new Reply(500, actionError(action, outcome.error()));
        }
        StringBuilder out = new StringBuilder();
        out.append('{');
        envelope(out, true);
        field(out, "action", action);
        field(out, "outputDirectory", outcome.directory());
        out.append(",\"count\":").append(outcome.files().size());
        out.append(",\"files\":[");
        for (int i = 0; i < outcome.files().size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            SavedFile file = outcome.files().get(i);
            out.append("{\"name\":").append(JsonText.quote(file.name()));
            out.append(",\"path\":").append(JsonText.quote(file.path()));
            out.append(",\"detail\":").append(JsonText.quote(file.detail() == null ? "" : file.detail()));
            out.append('}');
        }
        out.append("]}");
        return new Reply(200, out.toString());
    }

    private static Reply setOutputDirectory(String body, ShotActions actions) {
        String directory = directoryOverride(JsonText.object(body));
        if (directory == null || directory.isBlank()) {
            return new Reply(400, error("set-output-directory requires a JSON object with outputDirectory."));
        }
        actions.setOutputDirectory(directory);
        StringBuilder out = new StringBuilder();
        out.append('{');
        envelope(out, true);
        field(out, "action", "set-output-directory");
        field(out, "outputDirectory", actions.outputDirectory());
        out.append('}');
        return new Reply(200, out.toString());
    }

    private static String tools(ShotActions actions) {
        boolean ready = !actions.busy();
        StringBuilder out = new StringBuilder();
        out.append('{');
        envelope(out, true);
        field(out, "action", "tools");
        field(out, "commandHeader", HEADER);
        field(out, "commands", COMMANDS);
        field(out, "usage",
                "call_extension_command with header " + HEADER
                        + " and command set to a tool name. "
                        + "capture-proxy, capture-repeater, and capture-both take an optional JSON body "
                        + "{\"outputDirectory\":\"/absolute/path\"}. "
                        + "capture-repeater also accepts tabName to capture only that exact tab. "
                        + "The response lists PNG paths.");
        out.append(",\"tools\":[");
        tool(out, "capture-proxy", ready,
                "Save PNG screenshots of Proxy Intercept and HTTP history. "
                        + "Each image stacks the visible section with the full request and the full response.");
        tool(out, "capture-repeater", ready,
                "Save a PNG of every open Repeater tab, or only the exact tabName supplied: request and response side by side. "
                        + "The Inspector rail is left out. Notes are included only when already open.");
        tool(out, "capture-both", ready,
                "Save Proxy screenshots, then every open Repeater tab "
                        + "(request and response side by side), and restore the tool that was open.");
        tool(out, "status", true,
                "Return the Save to folder and whether a capture is running.");
        tool(out, "set-output-directory", true,
                "Set the Save to folder. The body is a JSON object with outputDirectory. "
                        + "A capture writes a timestamped directory under that folder.");
        out.append("]}");
        return out.toString();
    }

    private static String status(ShotActions actions) {
        StringBuilder out = new StringBuilder();
        out.append('{');
        envelope(out, true);
        field(out, "action", "status");
        out.append(",\"busy\":").append(actions.busy());
        field(out, "outputDirectory", actions.outputDirectory());
        field(out, "commandHeader", HEADER);
        field(out, "commands", COMMANDS);
        out.append('}');
        return out.toString();
    }

    private static void tool(StringBuilder out, String name, boolean enabled, String description) {
        if (out.charAt(out.length() - 1) != '[') {
            out.append(',');
        }
        out.append("{\"name\":").append(JsonText.quote(name));
        out.append(",\"enabled\":").append(enabled);
        out.append(",\"description\":").append(JsonText.quote(description));
        out.append('}');
    }

    private static void envelope(StringBuilder out, boolean ok) {
        out.append("\"ok\":").append(ok);
        field(out, "extension", ShotUpExtension.NAME);
        field(out, "version", ShotUpExtension.VERSION);
    }

    private static void field(StringBuilder out, String name, String value) {
        out.append(",\"").append(name).append("\":").append(JsonText.quote(value));
    }

    private static String actionError(String action, String message) {
        StringBuilder out = new StringBuilder();
        out.append('{');
        envelope(out, false);
        field(out, "action", action);
        field(out, "error", message == null ? "Capture failed." : message);
        out.append('}');
        return out.toString();
    }

    static String error(String message) {
        return "{\"ok\":false,\"extension\":" + JsonText.quote(ShotUpExtension.NAME)
                + ",\"error\":" + JsonText.quote(message == null ? "unknown error" : message) + "}";
    }

    private static String directoryOverride(Map<String, String> fields) {
        if (fields.containsKey("outputDirectory")) {
            return fields.get("outputDirectory").trim();
        }
        if (fields.containsKey("outputDir")) {
            return fields.get("outputDir").trim();
        }
        if (fields.containsKey("directory")) {
            return fields.get("directory").trim();
        }
        return null;
    }

    private static String canonical(String command) {
        return command.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String shown(String command) {
        String trimmed = command.trim();
        if (trimmed.length() > 80) {
            return trimmed.substring(0, 80);
        }
        return trimmed;
    }

    private static String spokenCommands() {
        return "tools, status, capture-proxy, capture-repeater, capture-both, or set-output-directory";
    }
}
