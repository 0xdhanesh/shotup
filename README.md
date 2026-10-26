# ShotUp

ShotUp is a Burp Suite extension that screenshots Proxy and Repeater from the open Burp window. The same captures are available to [burp-mcp](https://github.com/0xdhanesh/mcp-server) through the header `X-Burp-Mcp-Discover`.

The ShotUp tab and the ShotUp menu work on their own. Load burp-mcp in the same Burp when you want an MCP client to run the captures.

## Requirements

- Burp Suite with the Montoya API. This project compiles against `net.portswigger.burp.extensions:montoya-api:2026.7`.
- Java 21 or newer, to build. The jar targets Java 21.
- Gradle 9.8.0 to build locally (the same version used in GitHub Actions).

## Build

```bash
gradle test jar --no-daemon
```

The jar is `build/libs/shotup-2.0.2.jar`. The version comes from `build.gradle`.

The tests paint Swing components, so they need a display. The GitHub Actions workflow runs them under `xvfb-run` on Ubuntu. On macOS they use the window server.

## Install

1. In Burp, open **Extensions → Installed → Add**.
2. Set the extension type to **Java**.
3. Select `build/libs/shotup-2.0.2.jar`.

Burp adds a **ShotUp** suite tab and a **ShotUp** menu. The extension output shows `ShotUp loaded` when the jar starts.

## Capture from the tab

**Save to** is the parent folder. Saved PNGs go in a new `yyyyMMdd-HHmmss` directory under that folder. The default parent is `~/ShotUp`. The folder is stored when a capture runs, and when `set-output-directory` runs.

The Burp window has to be open. ShotUp selects Proxy or Repeater, takes the pictures, then selects the tool, tab, and scroll positions you had open.

| Action | What it saves |
| --- | --- |
| Capture Proxy | `proxy-intercept.png` and `proxy-http-history.png`. Each image stacks the visible section with the full request and the full response. |
| Capture Repeater | One PNG per open Repeater tab, named `repeater-01-<title>.png`, `repeater-02-<title>.png`, and so on. Each picture is the request and response editors side by side. The Inspector and Custom actions rail is left out. Notes are included only when that panel is already open. |
| Capture both | Proxy, then Repeater. |

A message that would make the stacked image taller than 30000 pixels is saved as separate files: `<name>-section.png`, `<name>-request.png`, and `<name>-response.png`. Request and response editors are capped at 8000×14000 pixels. The ShotUp log records that truncation.

Repeater tab titles are reduced to letters, digits, `.`, `_`, and `-`, and cut at 40 characters.

## Call it from burp-mcp

burp-mcp discovers loaded extensions by probing command headers. `list_extension_tools` sends:

```http
X-Burp-Mcp-Discover: tools
```

ShotUp spoofs a JSON response before Burp opens a connection. `call_extension_command` uses the same header and puts the command in the header value.

The command is accepted from **Extensions**, **Burp AI**, and **Repeater**. burp-mcp's `Http.sendRequest` is attributed to Extensions. A Proxy request that carries the header is sent on unchanged, so a browser cannot run a capture by adding the header.

Leave host, port, and TLS unset on the MCP call. burp-mcp then addresses `127.0.0.1` port 1, and ShotUp answers first.

### Commands

| Command | Body | Result |
| --- | --- | --- |
| `tools` | Ignored. This is the discovery probe. | Catalog. `extension` is `ShotUp`. `commands` lists the commands. `tools` is an array of `{name, enabled, description}`. |
| `status` | Ignored. | `outputDirectory` (the Save to folder) and `busy`. |
| `capture-proxy` | Optional `{"outputDirectory":"/absolute/path"}`. | PNG paths. |
| `capture-repeater` | Optional `{"outputDirectory":"/absolute/path","tabName":"AUTH-001-control"}`. `tabName` must exactly match one Repeater tab; omit it only when you intend to capture every open tab. | PNG paths. |
| `capture-both` | Same optional body. | PNG paths. |
| `set-output-directory` | `{"outputDirectory":"/absolute/path"}`. | Updates the Save to field. |

`outputDir` and `directory` are accepted as aliases. `outputDirectory` wins when more than one is present. Pass an absolute path. A relative path is resolved from Burp's working directory. A capture writes a timestamped directory under that folder, and the response `outputDirectory` is that timestamped directory.

Underscores stand in for hyphens: `capture_proxy` and `set_output_directory` are the same commands. Matching is case-insensitive.

The response lists file paths and a `detail` string for each file. `count` is the number of files.

A capture that starts while another capture is running returns HTTP 409 and `{"ok":false,...,"error":"A capture is already running."}`. An unknown command returns HTTP 400. A failed capture returns HTTP 500 with the same message the ShotUp tab shows, for example `The Burp window is not open. Restore it and try again.` Responses use `Content-Type: application/json` and `X-ShotUp: 1`.

`list_extension_tools` prints a summary in this shape:

```text
Extension: ShotUp
Probe: X-Burp-Mcp-Discover: tools
Commands: tools, status, capture-proxy, capture-repeater, capture-both, set-output-directory
Tools:
- capture-proxy enabled=true
- capture-repeater enabled=true
- capture-both enabled=true
- status enabled=true
- set-output-directory enabled=true
```

The JSON body follows that summary. While a capture is running, the three capture tools report `enabled=false`. `status` and `set-output-directory` stay enabled.

Example `call_extension_command` arguments:

```text
header: X-Burp-Mcp-Discover
command: capture-both
body: {"outputDirectory":"/tmp/shotup"}
```

A successful body looks like:

```json
{"ok":true,"extension":"ShotUp","version":"2.0.2","action":"capture-both","outputDirectory":"/tmp/shotup/20261010-153045","count":4,"files":[{"name":"proxy-intercept","path":"/tmp/shotup/20261010-153045/proxy-intercept.png","detail":""}]}
```

The call blocks until the PNGs are written. Burp switches to Proxy and Repeater during the capture, then returns to the tool you had open. Screenshots can contain credentials and customer data. For assessment reporting, capture one named tab into an approved restricted staging directory, verify a redacted copy, then remove the raw PNG.

## How the picture is taken

The Montoya API has no screenshot method, and Repeater has no call that reads open tabs. ShotUp walks the frame from `UserInterface.swingUtils().suiteFrame()`, selects the Proxy and Repeater tabs, and paints those components. Repeater paints the request and response editors, not the Inspector / Custom actions rail. Notes are included only when that panel is already open. That is why the Burp window has to be showing.

## Project layout

| Path | Role |
| --- | --- |
| `src/main/java/shotup/ShotUpExtension.java` | Burp entry point. Registers the tab, the menu, and the MCP handler. |
| `src/main/java/shotup/ShotUpPanel.java` | Suite tab. Saves PNGs and runs a capture for the UI and for MCP. |
| `src/main/java/shotup/ShotUpMcpHandler.java` | HTTP handler for `X-Burp-Mcp-Discover`. |
| `src/main/java/shotup/McpCommands.java` | Command names and JSON responses. |
| `src/main/java/shotup/SectionCapture.java` | Finds Proxy and Repeater in the suite frame and captures them. |
| `src/main/java/shotup/ComponentImages.java` | Paints components and stitches scrolled editors. |
| `src/main/java/shotup/SwingSearch.java` | Walks the Swing tree by tab title. |

## Tests

```bash
gradle test --no-daemon
```

`SectionCaptureTest` builds a stand-in suite frame and checks Proxy, Repeater, scroll restoration, and tab restoration. `McpCommandsTest` checks the JSON catalog and the capture commands without loading Burp.
