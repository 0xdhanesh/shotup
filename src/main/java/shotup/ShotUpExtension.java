package shotup;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.menu.BasicMenuItem;
import burp.api.montoya.ui.menu.Menu;

/**
 * Burp extension that screenshots Proxy and Repeater.
 * Entry point discovered by Burp because it implements {@link BurpExtension}.
 * burp-mcp discovers the same captures through {@code X-Burp-Mcp-Discover}.
 */
public class ShotUpExtension implements BurpExtension {
    static final String NAME = "ShotUp";
    /** Keep this equal to {@code version} in build.gradle. */
    static final String VERSION = "2.0.0";

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName(NAME);
        ShotUpPanel panel = new ShotUpPanel(api);
        api.http().registerHttpHandler(new ShotUpMcpHandler(api, panel));
        api.userInterface().applyThemeToComponent(panel);
        api.userInterface().registerSuiteTab(NAME, panel);
        api.userInterface().menuBar().registerMenu(Menu.menu(NAME).withMenuItems(
                BasicMenuItem.basicMenuItem("Capture Proxy and Repeater").withAction(panel::captureBoth),
                BasicMenuItem.basicMenuItem("Capture Proxy").withAction(panel::captureProxy),
                BasicMenuItem.basicMenuItem("Capture Repeater").withAction(panel::captureRepeater)
        ));
        api.logging().logToOutput("ShotUp loaded. Open the ShotUp tab, or call it from burp-mcp with "
                + McpCommands.HEADER + ".");
    }
}
