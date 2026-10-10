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

final class ShotUpPanel extends JPanel {
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
                Repeater captures every open tab exactly as it appears, with no separate request or response image.
                The tool you were using is selected again when the capture finishes.
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
        capture(true, false);
    }

    void captureRepeater() {
        capture(false, true);
    }

    void captureBoth() {
        capture(true, true);
    }

    private JButton action(String caption, Runnable action) {
        JButton button = new JButton(caption);
        button.addActionListener(event -> action.run());
        buttons.add(button);
        return button;
    }

    private void capture(boolean proxy, boolean repeater) {
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        setBusy(true, "Capturing. Burp will switch tools, then return to the one you had open.");
        SwingUtilities.invokeLater(() -> {
            try {
                Path dir = outputDirectory();
                Files.createDirectories(dir);
                Preferences.userNodeForPackage(ShotUpPanel.class).put(PREF_DIR, directory.getText().trim());
                Frame frame = api.userInterface().swingUtils().suiteFrame();
                List<String> saved = new ArrayList<>();
                SectionCapture.capture(frame, proxy, repeater, (name, image, detail) -> save(dir, saved, name, image, detail));
                status.setText("Saved " + saved.size() + " image" + (saved.size() == 1 ? "" : "s") + " to " + dir);
                api.logging().logToOutput("ShotUp saved " + saved.size() + " image(s) to " + dir);
            } catch (Exception exception) {
                status.setText("Capture failed.");
                append(exception.getMessage() == null ? exception.toString() : exception.getMessage());
                api.logging().logToError("ShotUp capture failed: " + exception);
            } finally {
                busy.set(false);
                setBusy(false, status.getText());
            }
        });
    }

    private void save(Path dir, List<String> saved, String name, BufferedImage image, String detail) {
        Path file = dir.resolve(name + ".png");
        try {
            ImageIO.write(image, "png", file.toFile());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write " + file, exception);
        }
        String line = file.toString();
        if (detail != null && !detail.isBlank()) {
            line += " — " + detail;
        }
        saved.add(line);
        append(line);
    }

    private Path outputDirectory() {
        String text = directory.getText().trim();
        Path root = text.isEmpty() ? Path.of(System.getProperty("user.home"), "ShotUp") : Path.of(text);
        return root.resolve(STAMP.format(LocalDateTime.now()));
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
