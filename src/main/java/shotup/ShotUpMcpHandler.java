package shotup;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ToolSource;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.http.message.responses.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Answers {@code X-Burp-Mcp-Discover} by spoofing the response. burp-mcp probes
 * loaded extensions with that header because Montoya cannot list another
 * extension's tools. The request is accepted from Extensions, Burp AI, and
 * Repeater, which is where {@code Http.sendRequest} is attributed. Proxy
 * traffic is left unchanged.
 */
final class ShotUpMcpHandler implements HttpHandler {
    private final MontoyaApi api;
    private final McpCommands.ShotActions actions;

    ShotUpMcpHandler(MontoyaApi api, McpCommands.ShotActions actions) {
        this.api = api;
        this.actions = actions;
    }

    @Override
    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent request) {
        try {
            if (request == null || !request.hasHeader(McpCommands.HEADER)) {
                return RequestToBeSentAction.continueWith(request);
            }
            if (!controlPlane(request.toolSource())) {
                return RequestToBeSentAction.continueWith(request);
            }
            String body = request.bodyToString();
            McpCommands.Reply reply = McpCommands.execute(
                    request.headerValue(McpCommands.HEADER),
                    body == null ? "" : body,
                    actions);
            return RequestToBeSentAction.spoof(json(reply));
        } catch (RuntimeException exception) {
            api.logging().logToError("ShotUp MCP command failed: " + exception);
            return RequestToBeSentAction.spoof(json(new McpCommands.Reply(500, McpCommands.error(
                    exception.getMessage() == null ? exception.toString() : exception.getMessage()))));
        }
    }

    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived response) {
        return ResponseReceivedAction.continueWith(response);
    }

    static boolean controlPlane(ToolSource source) {
        return source != null && source.isFromTool(ToolType.EXTENSIONS, ToolType.BURP_AI, ToolType.REPEATER);
    }

    private static HttpResponse json(McpCommands.Reply reply) {
        String reason = switch (reply.status()) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 409 -> "Conflict";
            default -> "Error";
        };
        HttpResponse response = HttpResponse.httpResponse("HTTP/1.1 " + reply.status() + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "X-ShotUp: 1\r\n"
                + "\r\n");
        return response.withBody(burp.api.montoya.core.ByteArray.byteArray(reply.body().getBytes(StandardCharsets.UTF_8)));
    }
}
