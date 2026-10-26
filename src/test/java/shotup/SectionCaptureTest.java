package shotup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class SectionCaptureTest {
    /** Opaque fill used for the Inspector rail. It must not appear in a Repeater shot. */
    private static final int RAIL = 0xFFFF00FF;
    /** Opaque fill used for an already-open Notes panel. */
    private static final int NOTES = 0xFF00FF80;

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
            int[] alphaSize = new int[3];
            SwingUtilities.invokeAndWait(() -> {
                fixture.historyRequest.getVerticalScrollBar().setValue(15);
                SectionCapture.capture(fixture.frame, true, true, (name, image, detail) -> {
                    shots.add(new Shot(name, image, detail));
                    if ("repeater-01-Alpha".equals(name)) {
                        alphaSize[0] = fixture.alphaRequest.getWidth() + fixture.alphaResponse.getWidth();
                        alphaSize[1] = Math.max(fixture.alphaRequest.getHeight(), fixture.alphaResponse.getHeight());
                        alphaSize[2] = fixture.alphaPage.getWidth();
                    }
                });
            });

            assertTrue(names(shots).contains("proxy-intercept"), names(shots).toString());
            assertTrue(names(shots).contains("proxy-http-history"), names(shots).toString());
            assertTrue(names(shots).contains("repeater-01-Alpha"), names(shots).toString());
            assertTrue(names(shots).contains("repeater-02-Beta"), names(shots).toString());
            assertEquals(2, names(shots).stream().filter(name -> name.startsWith("repeater-")).count());
            Shot alpha = shot(shots, "repeater-01-Alpha");
            assertTrue(names(shots).stream().noneMatch(name -> name.toLowerCase().contains("inspector")
                            || name.toLowerCase().contains("custom")),
                    names(shots).toString());
            assertTrue(names(shots).stream().noneMatch(name -> name.startsWith("repeater-") && name.contains("request")),
                    names(shots).toString());
            assertEquals(alphaSize[0], alpha.image().getWidth());
            assertEquals(alphaSize[1], alpha.image().getHeight());
            assertTrue(alpha.image().getWidth() < alphaSize[2],
                    "repeater shot included the side rail: image=" + alpha.image().getWidth() + " page=" + alphaSize[2]);
            assertFalse(containsRgb(alpha.image(), RAIL), "Inspector rail was captured");
            assertFalse(containsRgb(alpha.image(), NOTES), "Notes were captured while Inspector was selected");
            assertFalse(containsRgb(shot(shots, "repeater-02-Beta").image(), RAIL));

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

    @Test
    void namedRepeaterCaptureLeavesOtherTabsOut() throws Exception {
        SwingFixture fixture = SwingFixture.build();
        try {
            List<Shot> shots = new ArrayList<>();
            SwingUtilities.invokeAndWait(() -> SectionCapture.capture(
                    fixture.frame, false, true, "Alpha",
                    (name, image, detail) -> shots.add(new Shot(name, image, detail))));
            assertEquals(List.of("repeater-01-Alpha"), names(shots));
            SwingUtilities.invokeAndWait(() -> assertEquals(0, fixture.repeaterTabs.getSelectedIndex()));
        } finally {
            SwingUtilities.invokeAndWait(() -> fixture.frame.dispose());
        }
    }

    @Test
    void capturesCaptionStripAndOpenNotesWithoutTheRail() throws Exception {
        CaptionFixture fixture = CaptionFixture.build();
        try {
            List<Shot> shots = new ArrayList<>();
            int[] alphaSize = new int[2];
            SwingUtilities.invokeAndWait(() -> {
                fixture.dock.setSelectedIndex(1);
                SectionCapture.capture(fixture.frame, false, true, (name, image, detail) -> {
                    shots.add(new Shot(name, image, detail));
                    if ("repeater-01-Alpha".equals(name)) {
                        alphaSize[0] = fixture.request.getWidth() + fixture.response.getWidth() + fixture.notes.getWidth();
                        alphaSize[1] = Math.max(fixture.request.getHeight(),
                                Math.max(fixture.response.getHeight(), fixture.notes.getHeight()));
                    }
                });
            });

            assertEquals(List.of("repeater-01-Alpha", "repeater-02-Beta"), names(shots));
            Shot alpha = shot(shots, "repeater-01-Alpha");
            Shot beta = shot(shots, "repeater-02-Beta");
            assertEquals(alphaSize[0], alpha.image().getWidth());
            assertEquals(alphaSize[1], alpha.image().getHeight());
            assertFalse(containsRgb(alpha.image(), RAIL), "Inspector rail was captured");
            assertTrue(containsRgb(alpha.image(), NOTES), "open Notes panel was left out");
            assertFalse(samePixels(alpha.image(), beta.image()), "Alpha and Beta captures should differ");
            SwingUtilities.invokeAndWait(() -> assertTrue(fixture.alpha.isSelected(), "caption selection was not restored"));
        } finally {
            SwingUtilities.invokeAndWait(() -> fixture.frame.dispose());
        }
    }

    @Test
    void sendBarJoinsTheEditorsWhenTheRailStaysOutside() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel request = new JPanel();
            JPanel response = new JPanel();
            JPanel editors = new JPanel(new GridLayout(1, 2));
            editors.add(request);
            editors.add(response);
            JPanel column = new JPanel(new BorderLayout());
            column.add(new JButton("Send"), BorderLayout.NORTH);
            column.add(editors, BorderLayout.CENTER);
            JTabbedPane dock = sideDock();
            JPanel root = new JPanel(new BorderLayout());
            root.add(column, BorderLayout.CENTER);
            root.add(dock, BorderLayout.EAST);

            assertSame(editors, SwingSearch.editorArea(request, response));
            assertSame(column, SwingSearch.includeToolbar(editors));
        });
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

    private static boolean containsRgb(BufferedImage image, int rgb) {
        int target = rgb & 0xFFFFFF;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) == target) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean samePixels(BufferedImage left, BufferedImage right) {
        if (left.getWidth() != right.getWidth() || left.getHeight() != right.getHeight()) {
            return false;
        }
        for (int y = 0; y < left.getHeight(); y += 3) {
            for (int x = 0; x < left.getWidth(); x += 3) {
                if (left.getRGB(x, y) != right.getRGB(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    private record Shot(String name, BufferedImage image, String detail) {
    }

    private static final class SwingFixture {
        private final JFrame frame;
        private final JTabbedPane suite;
        private final JTabbedPane proxyTabs;
        private final JTabbedPane repeaterTabs;
        private final JScrollPane historyRequest;
        private final JPanel alphaPage;
        private final JPanel alphaRequest;
        private final JPanel alphaResponse;

        private SwingFixture(JFrame frame, JTabbedPane suite, JTabbedPane proxyTabs,
                             JTabbedPane repeaterTabs, JScrollPane historyRequest,
                             JPanel alphaPage, JPanel alphaRequest, JPanel alphaResponse) {
            this.frame = frame;
            this.suite = suite;
            this.proxyTabs = proxyTabs;
            this.repeaterTabs = repeaterTabs;
            this.historyRequest = historyRequest;
            this.alphaPage = alphaPage;
            this.alphaRequest = alphaRequest;
            this.alphaResponse = alphaResponse;
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

            MessagePage alpha = messagePage("ALPHA");
            JTabbedPane repeaterTabs = new JTabbedPane();
            repeaterTabs.addTab("Alpha", alpha.page());
            repeaterTabs.addTab("Beta", messagePage("BETA").page());
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
            return new SwingFixture(frame, suite, proxyTabs, repeaterTabs, historyRequest,
                    alpha.page(), alpha.request(), alpha.response());
        }

        private static MessagePage messagePage(String label) {
            JPanel request = namedEditor("Request", label + " request", "httpRequestMessageAnalyser");
            JPanel response = namedEditor("Response", label + " response", "httpResponseMessageAnalyser");
            JPanel page = new JPanel(new BorderLayout());
            page.add(request, BorderLayout.WEST);
            page.add(response, BorderLayout.CENTER);
            page.add(sideDock(), BorderLayout.EAST);
            return new MessagePage(page, request, response);
        }

        private static JPanel labeled(String name, JScrollPane scroll) {
            JPanel panel = new JPanel(new BorderLayout());
            panel.add(new JLabel(name), BorderLayout.NORTH);
            panel.add(scroll, BorderLayout.CENTER);
            return panel;
        }

        private static JPanel namedEditor(String role, String label, String componentName) {
            JScrollPane scroll = editor(label, 40);
            scroll.setPreferredSize(new Dimension(260, 180));
            JPanel panel = new JPanel(new BorderLayout());
            panel.add(new JLabel(role), BorderLayout.NORTH);
            panel.add(scroll, BorderLayout.CENTER);
            panel.setName(componentName);
            panel.setPreferredSize(new Dimension(280, 220));
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

    private record MessagePage(JPanel page, JPanel request, JPanel response) {
    }

    private static final class CaptionFixture {
        private final JFrame frame;
        private final JToggleButton alpha;
        private final JPanel request;
        private final JPanel response;
        private final JPanel notes;
        private final JTabbedPane dock;

        private CaptionFixture(JFrame frame, JToggleButton alpha, JPanel request, JPanel response,
                               JPanel notes, JTabbedPane dock) {
            this.frame = frame;
            this.alpha = alpha;
            this.request = request;
            this.response = response;
            this.notes = notes;
            this.dock = dock;
        }

        private static CaptionFixture build() throws Exception {
            CaptionFixture[] holder = new CaptionFixture[1];
            SwingUtilities.invokeAndWait(() -> holder[0] = create());
            return holder[0];
        }

        private static CaptionFixture create() {
            JToggleButton alpha = new JToggleButton("Alpha");
            JToggleButton beta = new JToggleButton("Beta");
            ButtonGroup group = new ButtonGroup();
            group.add(alpha);
            group.add(beta);
            alpha.setSelected(true);
            JPanel captions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            captions.add(alpha);
            captions.add(beta);

            JTextArea requestText = new JTextArea("ALPHA request");
            JTextArea responseText = new JTextArea("ALPHA response");
            JPanel request = new JPanel(new BorderLayout());
            request.setName("httpRequestMessageAnalyser");
            request.add(new JLabel("Request"), BorderLayout.NORTH);
            request.add(new JScrollPane(requestText), BorderLayout.CENTER);
            request.setPreferredSize(new Dimension(280, 220));
            JPanel response = new JPanel(new BorderLayout());
            response.setName("httpResponseMessageAnalyser");
            response.add(new JLabel("Response"), BorderLayout.NORTH);
            response.add(new JScrollPane(responseText), BorderLayout.CENTER);
            response.setPreferredSize(new Dimension(280, 220));
            alpha.addActionListener(event -> {
                requestText.setText("ALPHA request");
                responseText.setText("ALPHA response");
            });
            beta.addActionListener(event -> {
                requestText.setText("BETA request line");
                responseText.setText("BETA response line");
            });

            JPanel notes = solid(new Color(NOTES));
            JTabbedPane dock = new JTabbedPane();
            dock.setTabPlacement(JTabbedPane.RIGHT);
            dock.addTab("Inspector", solid(new Color(RAIL)));
            dock.addTab("Notes", notes);
            dock.addTab("Custom actions", solid(Color.DARK_GRAY));
            dock.setPreferredSize(new Dimension(190, 240));
            dock.setSelectedIndex(0);

            JPanel repeater = new JPanel(new BorderLayout());
            repeater.add(captions, BorderLayout.NORTH);
            repeater.add(request, BorderLayout.WEST);
            repeater.add(response, BorderLayout.CENTER);
            repeater.add(dock, BorderLayout.EAST);

            JTabbedPane suite = new JTabbedPane();
            suite.addTab("Proxy", new JPanel());
            suite.addTab("Repeater", repeater);
            suite.setSelectedIndex(1);

            JFrame frame = new JFrame("ShotUp caption strip");
            frame.setContentPane(suite);
            frame.setSize(900, 700);
            frame.setVisible(true);
            frame.validate();
            return new CaptionFixture(frame, alpha, request, response, notes, dock);
        }
    }

    private static JTabbedPane sideDock() {
        JTabbedPane dock = new JTabbedPane();
        dock.setTabPlacement(JTabbedPane.RIGHT);
        dock.addTab("Inspector", solid(new Color(RAIL)));
        dock.addTab("Notes", solid(new Color(NOTES)));
        dock.addTab("Custom actions", solid(Color.DARK_GRAY));
        dock.setPreferredSize(new Dimension(190, 240));
        dock.setSelectedIndex(0);
        return dock;
    }

    private static JPanel solid(Color color) {
        JPanel panel = new JPanel();
        panel.setOpaque(true);
        panel.setBackground(color);
        panel.setPreferredSize(new Dimension(160, 220));
        return panel;
    }
}
