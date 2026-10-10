package shotup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class SectionCaptureTest {
    @Test
    void scrollStopsCoverTheLastPositionOnce() {
        assertEquals(List.of(0), ComponentImages.scrollStops(0, 0, 80));
        assertEquals(List.of(0, 80, 160), ComponentImages.scrollStops(0, 160, 80));
        assertEquals(List.of(0, 80, 100), ComponentImages.scrollStops(0, 100, 80));
    }

    @Test
    void capturesProxyAndRepeaterEditorsAndRestoresSelection() throws Exception {
        SwingFixture fixture = SwingFixture.build();
        try {
            List<Shot> shots = new ArrayList<>();
            SwingUtilities.invokeAndWait(() -> {
                fixture.historyRequest.getVerticalScrollBar().setValue(15);
                SectionCapture.capture(fixture.frame, true, true, (name, image, detail) ->
                        shots.add(new Shot(name, image, detail)));
            });

            assertTrue(names(shots).contains("proxy-intercept"), names(shots).toString());
            assertTrue(names(shots).contains("proxy-http-history"), names(shots).toString());
            assertTrue(names(shots).contains("repeater-01-Alpha"), names(shots).toString());
            assertTrue(names(shots).contains("repeater-02-Beta"), names(shots).toString());
            Shot alpha = shot(shots, "repeater-01-Alpha");
            assertTrue(names(shots).stream().noneMatch(name -> name.startsWith("repeater-") && name.contains("request")),
                    names(shots).toString());
            assertEquals(fixture.repeaterTabs.getWidth(), alpha.image().getWidth());
            assertEquals(fixture.repeaterTabs.getHeight(), alpha.image().getHeight());

            Shot history = shot(shots, "proxy-http-history");
            assertTrue(history.detail() == null || !history.detail().toLowerCase().contains("not found"),
                    history.detail());
            assertTrue(history.image().getHeight() > 1500,
                    "expected the full request and response to be stitched in, height="
                            + history.image().getHeight() + " detail=" + history.detail());
            assertTrue(rowChecksum(history.image(), 40) != rowChecksum(history.image(), history.image().getHeight() - 10),
                    "top and bottom of the capture should show different editor contents");

            SwingUtilities.invokeAndWait(() -> {
                assertEquals(1, fixture.suite.getSelectedIndex(), "suite tab was not restored");
                assertEquals(1, fixture.proxyTabs.getSelectedIndex(), "proxy subtab was not restored");
                assertEquals(0, fixture.repeaterTabs.getSelectedIndex(), "repeater tab was not restored");
                assertEquals(15, fixture.historyRequest.getVerticalScrollBar().getValue(),
                        "request scroll position was not restored");
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> fixture.frame.dispose());
        }
    }

    private static int rowChecksum(BufferedImage image, int y) {
        int sample = Math.max(0, Math.min(image.getHeight() - 1, y));
        int checksum = 0;
        for (int x = 0; x < image.getWidth(); x += 3) {
            checksum = 31 * checksum + image.getRGB(x, sample);
        }
        return checksum;
    }

    private static Shot shot(List<Shot> shots, String name) {
        return shots.stream().filter(shot -> shot.name().equals(name)).findFirst().orElseThrow();
    }

    private static List<String> names(List<Shot> shots) {
        return shots.stream().map(Shot::name).toList();
    }

    private record Shot(String name, BufferedImage image, String detail) {
    }

    private static final class SwingFixture {
        private final JFrame frame;
        private final JTabbedPane suite;
        private final JTabbedPane proxyTabs;
        private final JTabbedPane repeaterTabs;
        private final JScrollPane historyRequest;

        private SwingFixture(JFrame frame, JTabbedPane suite, JTabbedPane proxyTabs,
                             JTabbedPane repeaterTabs, JScrollPane historyRequest) {
            this.frame = frame;
            this.suite = suite;
            this.proxyTabs = proxyTabs;
            this.repeaterTabs = repeaterTabs;
            this.historyRequest = historyRequest;
        }

        private static SwingFixture build() throws Exception {
            SwingFixture[] holder = new SwingFixture[1];
            SwingUtilities.invokeAndWait(() -> holder[0] = create());
            return holder[0];
        }

        private static SwingFixture create() {
            JScrollPane interceptRequest = editor("INTERCEPT request", 40);
            JScrollPane interceptResponse = editor("INTERCEPT response", 40);
            JPanel intercept = new JPanel(new GridLayout(2, 1));
            intercept.add(labeled("Request", interceptRequest));
            intercept.add(labeled("Response", interceptResponse));

            JScrollPane historyRequest = editor("HISTORY request", 160);
            JScrollPane historyResponse = editor("HISTORY response", 160);
            historyRequest.setPreferredSize(new Dimension(420, 90));
            historyResponse.setPreferredSize(new Dimension(420, 90));
            JTabbedPane message = new JTabbedPane();
            message.addTab("Request", historyRequest);
            message.addTab("Response", historyResponse);
            JPanel history = new JPanel(new BorderLayout());
            history.add(new JScrollPane(new JTable(8, 3)), BorderLayout.CENTER);
            history.add(message, BorderLayout.SOUTH);

            JTabbedPane proxyTabs = new JTabbedPane();
            proxyTabs.addTab("Intercept", intercept);
            proxyTabs.addTab("HTTP history", history);
            proxyTabs.setSelectedIndex(0);

            JTabbedPane repeaterTabs = new JTabbedPane();
            repeaterTabs.addTab("Alpha", messagePage("ALPHA"));
            repeaterTabs.addTab("Beta", messagePage("BETA"));
            repeaterTabs.setSelectedIndex(0);

            JTabbedPane suite = new JTabbedPane();
            suite.addTab("Target", new JPanel());
            suite.addTab("Proxy", proxyTabs);
            suite.addTab("Repeater", repeaterTabs);
            suite.setSelectedIndex(0);

            JFrame frame = new JFrame("ShotUp test suite");
            frame.setContentPane(suite);
            frame.setSize(900, 700);
            frame.setVisible(true);
            proxyTabs.setSelectedIndex(1);
            frame.validate();
            historyRequest.getVerticalScrollBar().setValue(15);
            suite.setSelectedIndex(1);
            return new SwingFixture(frame, suite, proxyTabs, repeaterTabs, historyRequest);
        }

        private static JPanel messagePage(String label) {
            JScrollPane request = editor(label + " request", 140);
            JScrollPane response = editor(label + " response", 140);
            JPanel page = new JPanel(new GridLayout(2, 1));
            page.add(labeled("Request", request));
            page.add(labeled("Response", response));
            return page;
        }

        private static JPanel labeled(String name, JScrollPane scroll) {
            JPanel panel = new JPanel(new BorderLayout());
            panel.add(new JLabel(name), BorderLayout.NORTH);
            panel.add(scroll, BorderLayout.CENTER);
            return panel;
        }

        private static JScrollPane editor(String label, int lines) {
            StringBuilder text = new StringBuilder();
            for (int i = 1; i <= lines; i++) {
                text.append(label).append(" line ").append(i).append(" the quick brown fox jumps over the lazy dog\n");
            }
            JTextArea area = new JTextArea(text.toString());
            area.setLineWrap(false);
            return new JScrollPane(area);
        }
    }
}
