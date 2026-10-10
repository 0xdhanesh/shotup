package shotup;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.menu.BasicMenuItem;
import burp.api.montoya.ui.menu.Menu;

/**
 * Burp extension that screenshots Proxy and Repeater.
 * Entry point discovered by Burp because it implements {@link BurpExtension}.
 */
public class ShotUpExtension implements BurpExtension {
    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("ShotUp");
        ShotUpPanel panel = new ShotUpPanel(api);
        api.userInterface().applyThemeToComponent(panel);
        api.userInterface().registerSuiteTab("ShotUp", panel);
        api.userInterface().menuBar().registerMenu(Menu.menu("ShotUp").withMenuItems(
                BasicMenuItem.basicMenuItem("Capture Proxy and Repeater").withAction(panel::captureBoth),
                BasicMenuItem.basicMenuItem("Capture Proxy").withAction(panel::captureProxy),
                BasicMenuItem.basicMenuItem("Capture Repeater").withAction(panel::captureRepeater)
        ));
        api.logging().logToOutput("ShotUp loaded. Open the ShotUp tab to capture Proxy and Repeater.");
    }
}
