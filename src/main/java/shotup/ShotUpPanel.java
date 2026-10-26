package shotup;

import burp.api.montoya.MontoyaApi;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.Preferences;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

final class ShotUpPanel extends JPanel implements McpCommands.ShotActions {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String PREF_DIR = "outputDir";

    private final MontoyaApi api;
    private final JTextField directory = new JTextField(savedDirectory(), 36);
    private final JTextArea log = new JTextArea(14, 60);
    private final JLabel status = new JLabel("Idle");
    private final AtomicBoolean busy = new AtomicBoolean();
    private final List<JButton> buttons = new ArrayList<>();

    ShotUpPanel(MontoyaApi api) {
        this.api = api;
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JTextArea help = new JTextArea("""
                Captures the Proxy and Repeater sections from the Burp window.
                Proxy captures Intercept and HTTP history, with the full request and response appended.
                Repeater saves one picture per open tab: the request and response editors side by side.
                The Inspector and Custom actions rail is left out. Notes are included only when that panel is already open.
                The tool you were using is selected again when the capture finishes.
                burp-mcp runs the same captures with the header X-Burp-Mcp-Discover.
                """);
        help.setEditable(false);
        help.setLineWrap(true);
        help.setWrapStyleWord(true);
        help.setOpaque(false);

        JPanel dirRow = new JPanel(new BorderLayout(8, 8));
        dirRow.add(new JLabel("Save to"), BorderLayout.WEST);
        dirRow.add(directory, BorderLayout.CENTER);
        JButton browse = new JButton("Browse");
        browse.addActionListener(event -> chooseDirectory());
        dirRow.add(browse, BorderLayout.EAST);

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttonRow.add(action("Capture Proxy", this::captureProxy));
        buttonRow.add(action("Capture Repeater", this::captureRepeater));
        buttonRow.add(action("Capture both", this::captureBoth));

        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.add(help);
        controls.add(dirRow);
        controls.add(buttonRow);
        controls.add(status);

        log.setEditable(false);
        add(controls, BorderLayout.NORTH);
        add(new JScrollPane(log), BorderLayout.CENTER);
    }

    void captureProxy() {
        startCapture(true, false);
    }

    void captureRepeater() {
        startCapture(false, true);
    }

    void captureBoth() {
        startCapture(true, true);
    }

    @Override
    public boolean busy() {
        return busy.get();
    }

    @Override
    public String outputDirectory() {
        String[] value = new String[1];
        ComponentImages.runOnEdt(() -> value[0] = displayedDirectory());
        return value[0];
    }

    @Override
    public void setOutputDirectory(String path) {
        String folder = path == null ? "" : path.trim();
        ComponentImages.runOnEdt(() -> {
            directory.setText(folder);
            Preferences.userNodeForPackage(ShotUpPanel.class).put(PREF_DIR, folder);
        });
    }

    @Override
    public McpCommands.CaptureOutcome capture(boolean proxy, boolean repeater, String outputDirectory, String tabName) {
        if (SwingUtilities.isEventDispatchThread()) {
            return runCapture(proxy, repeater, outputDirectory, tabName);
        }
        McpCommands.CaptureOutcome[] outcome = new McpCommands.CaptureOutcome[1];
        ComponentImages.runOnEdt(() -> outcome[0] = runCapture(proxy, repeater, outputDirectory, tabName));
        return outcome[0];
    }

    private JButton action(String caption, Runnable action) {
        JButton button = new JButton(caption);
        button.addActionListener(event -> action.run());
        buttons.add(button);
        return button;
    }

    private void startCapture(boolean proxy, boolean repeater) {
        if (busy.get()) {
            return;
        }
        SwingUtilities.invokeLater(() -> runCapture(proxy, repeater, null, null));
    }

    private McpCommands.CaptureOutcome runCapture(boolean proxy, boolean repeater, String outputOverride, String tabName) {
        if (!busy.compareAndSet(false, true)) {
            return McpCommands.CaptureOutcome.inProgress();
        }
        setBusy(true, "Capturing. Burp will switch tools, then return to the one you had open.");
        try {
            if (outputOverride != null && !outputOverride.isBlank()) {
                directory.setText(outputOverride.trim());
            }
            Path dir = timestampedDirectory().toAbsolutePath().normalize();
            Preferences.userNodeForPackage(ShotUpPanel.class).put(PREF_DIR, directory.getText().trim());
            Frame frame = api.userInterface().swingUtils().suiteFrame();
            List<McpCommands.SavedFile> saved = new ArrayList<>();
            SectionCapture.capture(frame, proxy, repeater, tabName,
                    (name, image, detail) -> saved.add(save(dir, name, image, detail)));
            if (saved.isEmpty()) {
                Files.createDirectories(dir);
            }
            status.setText("Saved " + saved.size() + " image" + (saved.size() == 1 ? "" : "s") + " to " + dir);
            api.logging().logToOutput("ShotUp saved " + saved.size() + " image(s) to " + dir);
            return McpCommands.CaptureOutcome.saved(dir.toAbsolutePath().toString(), saved);
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? exception.toString() : exception.getMessage();
            status.setText("Capture failed.");
            append(message);
            api.logging().logToError("ShotUp capture failed: " + exception);
            return McpCommands.CaptureOutcome.failed(message);
        } finally {
            busy.set(false);
            setBusy(false, status.getText());
        }
    }

    private McpCommands.SavedFile save(Path dir, String name, BufferedImage image, String detail) {
        Path file = dir.resolve(name + ".png");
        try {
            Files.createDirectories(dir);
            ImageIO.write(image, "png", file.toFile());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write " + file, exception);
        }
        String shown = detail == null ? "" : detail;
        String line = file.toString();
        if (!shown.isBlank()) {
            line += " — " + shown;
        }
        append(line);
        return new McpCommands.SavedFile(name, file.toAbsolutePath().normalize().toString(), shown);
    }

    private Path timestampedDirectory() {
        return Path.of(displayedDirectory()).resolve(STAMP.format(LocalDateTime.now()));
    }

    private String displayedDirectory() {
        String text = directory.getText().trim();
        if (text.isEmpty()) {
            return Path.of(System.getProperty("user.home"), "ShotUp").toString();
        }
        return text;
    }

    private void chooseDirectory() {
        JFileChooser chooser = new JFileChooser(directory.getText().trim());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
            directory.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void setBusy(boolean capturing, String text) {
        status.setText(text);
        for (JButton button : buttons) {
            button.setEnabled(!capturing);
        }
    }

    private void append(String line) {
        log.append(line);
        log.append("\n");
        log.setCaretPosition(log.getDocument().getLength());
    }

    private static String savedDirectory() {
        String saved = Preferences.userNodeForPackage(ShotUpPanel.class).get(PREF_DIR, "");
        if (saved == null || saved.isBlank()) {
            return Path.of(System.getProperty("user.home"), "ShotUp").toString();
        }
        return saved;
    }
}
