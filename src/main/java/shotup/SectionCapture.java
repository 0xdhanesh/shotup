package shotup;

import java.awt.Component;
import java.awt.Frame;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.AbstractButton;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;

/**
 * Screenshots Proxy and Repeater from the frame returned by
 * {@code UserInterface.swingUtils().suiteFrame()}. The Montoya API has no
 * screenshot method and Repeater has no call that reads open tabs, so the
 * picture is taken from that frame's live components.
 */
public final class SectionCapture {
    private SectionCapture() {
    }

    public interface ShotSink {
        void accept(String name, BufferedImage image, String detail);
    }

    public static void capture(Frame frame, boolean proxy, boolean repeater, ShotSink sink) {
        ComponentImages.runOnEdt(() -> captureOnEdt(frame, proxy, repeater, sink));
    }

    private static void captureOnEdt(Frame frame, boolean proxy, boolean repeater, ShotSink sink) {
        if (frame == null || !frame.isShowing()) {
            throw new IllegalStateException("The Burp window is not open. Restore it and try again.");
        }
        JTabbedPane suite = SwingSearch.suiteTabs(frame);
        if (suite == null) {
            throw new IllegalStateException("Could not find the Proxy and Repeater tabs in the Burp window.");
        }
        int previous = suite.getSelectedIndex();
        Map<JScrollPane, int[]> scroll = scrollPositions(frame);
        try {
            if (proxy) {
                captureProxy(suite, sink);
            }
            if (repeater) {
                captureRepeater(suite, sink);
            }
        } finally {
            select(suite, previous);
            restoreScroll(scroll);
        }
    }

    private static Map<JScrollPane, int[]> scrollPositions(Component root) {
        Map<JScrollPane, int[]> positions = new IdentityHashMap<>();
        for (JScrollPane pane : SwingSearch.findAll(root, JScrollPane.class)) {
            positions.put(pane, new int[] {
                    pane.getHorizontalScrollBar().getValue(),
                    pane.getVerticalScrollBar().getValue()
            });
        }
        return positions;
    }

    private static void restoreScroll(Map<JScrollPane, int[]> positions) {
        for (Map.Entry<JScrollPane, int[]> entry : positions.entrySet()) {
            entry.getKey().getHorizontalScrollBar().setValue(entry.getValue()[0]);
            entry.getKey().getVerticalScrollBar().setValue(entry.getValue()[1]);
        }
    }

    private static void captureProxy(JTabbedPane suite, ShotSink sink) {
        int proxyIndex = SwingSearch.indexOf(suite, "proxy");
        select(suite, proxyIndex);
        Component proxy = suite.getComponentAt(proxyIndex);
        layout(proxy);
        JTabbedPane sections = SwingSearch.paneWithTitle(proxy, "http history");
        if (sections == null) {
            sections = SwingSearch.paneWithTitle(proxy, "intercept");
        }
        if (sections == null) {
            saveView("proxy", proxy, sink, true);
            return;
        }
        int previous = sections.getSelectedIndex();
        try {
            captureTab(sections, "intercept", "proxy-intercept", sink, true);
            captureTab(sections, "http history", "proxy-http-history", sink, true);
        } finally {
            select(sections, previous);
        }
    }

    private static void captureRepeater(JTabbedPane suite, ShotSink sink) {
        int repeaterIndex = SwingSearch.indexOf(suite, "repeater");
        select(suite, repeaterIndex);
        Component repeater = suite.getComponentAt(repeaterIndex);
        layout(repeater);
        JTabbedPane tabs = SwingSearch.repeaterTabs(repeater);
        if (tabs != null) {
            int previous = tabs.getSelectedIndex();
            try {
                for (int i = 0; i < tabs.getTabCount(); i++) {
                    String name = "repeater-" + String.format("%02d", i + 1) + "-"
                            + SwingSearch.safeName(repeaterTitle(tabs, i));
                    select(tabs, i);
                    captureMessage(name, tabs.getSelectedComponent(), repeater, sink);
                }
            } finally {
                select(tabs, previous);
            }
            return;
        }
        List<Component> captions = SwingSearch.repeaterCaptions(repeater);
        if (!captions.isEmpty()) {
            List<AbstractButton> selected = selectedButtons(repeater);
            try {
                int index = 1;
                for (Component caption : captions) {
                    activateCaption(caption);
                    layout(repeater);
                    String name = "repeater-" + String.format("%02d", index++) + "-"
                            + SwingSearch.safeName(SwingSearch.captionText(caption));
                    captureMessage(name, repeater, repeater, sink);
                }
            } finally {
                restoreButtons(selected);
            }
            return;
        }
        captureMessage("repeater", repeater, repeater, sink);
    }

    private static String repeaterTitle(JTabbedPane tabs, int index) {
        String title = tabs.getTitleAt(index);
        if (title != null && !title.isBlank()) {
            return title;
        }
        Component header = tabs.getTabComponentAt(index);
        return header == null ? "" : SwingSearch.rawText(header);
    }

    /**
     * Request and response side by side. The Inspector and Custom actions rail
     * is left out. Notes are appended only when that panel is already open.
     */
    private static void captureMessage(String name, Component scope, Component repeater, ShotSink sink) {
        Component request = editorIn(scope, repeater, true);
        Component response = editorIn(scope, repeater, false);
        Component captured = null;
        BufferedImage image = null;
        if (request != null && response != null && request != response) {
            Component area = SwingSearch.editorArea(request, response);
            if (area != null) {
                captured = SwingSearch.includeToolbar(area);
                image = paintVisible(captured);
            }
            if (image == null) {
                BufferedImage left = paintVisible(request);
                BufferedImage right = paintVisible(response);
                if (left != null && right != null) {
                    image = ComponentImages.row(left, right);
                    captured = null;
                }
            }
        } else if (request != null || response != null) {
            captured = request != null ? request : response;
            image = paintVisible(captured);
        }
        if (image == null) {
            captured = scope == null ? repeater : scope;
            image = paintVisible(captured);
        }
        if (image == null) {
            sink.accept(name, new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB),
                    "The section has no size yet. Open it once in Burp and try again.");
            return;
        }
        Component notes = SwingSearch.visibleNotes(repeater);
        if (notes != null && (captured == null || !SwingSearch.contains(captured, notes))) {
            BufferedImage notesImage = paintVisible(notes);
            if (notesImage != null) {
                image = ComponentImages.row(image, notesImage);
            }
        }
        sink.accept(name, image, "");
    }

    private static Component editorIn(Component scope, Component repeater, boolean request) {
        Component found = scope == null ? null : SwingSearch.messageEditor(scope, request);
        if (found != null) {
            return found;
        }
        if (repeater != null && repeater != scope) {
            return SwingSearch.messageEditor(repeater, request);
        }
        return null;
    }

    private static BufferedImage paintVisible(Component view) {
        layout(view);
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return null;
        }
        return ComponentImages.capture(view);
    }

    private static List<AbstractButton> selectedButtons(Component root) {
        List<AbstractButton> selected = new ArrayList<>();
        for (AbstractButton button : SwingSearch.findAll(root, AbstractButton.class)) {
            if (button.isSelected()) {
                selected.add(button);
            }
        }
        return selected;
    }

    private static void restoreButtons(List<AbstractButton> selected) {
        for (AbstractButton button : selected) {
            if (!button.isSelected()) {
                button.doClick(0);
            }
        }
    }

    private static void activateCaption(Component caption) {
        AbstractButton button = buttonFor(caption);
        if (button != null) {
            button.doClick(0);
            return;
        }
        long when = System.currentTimeMillis();
        caption.dispatchEvent(new MouseEvent(
                caption, MouseEvent.MOUSE_PRESSED, when, 0, 1, 1, 1, false, MouseEvent.BUTTON1));
        caption.dispatchEvent(new MouseEvent(
                caption, MouseEvent.MOUSE_RELEASED, when, 0, 1, 1, 1, false, MouseEvent.BUTTON1));
        caption.dispatchEvent(new MouseEvent(
                caption, MouseEvent.MOUSE_CLICKED, when, 0, 1, 1, 1, false, MouseEvent.BUTTON1));
    }

    private static AbstractButton buttonFor(Component caption) {
        Component current = caption;
        for (int i = 0; i < 4 && current != null; i++) {
            if (current instanceof AbstractButton button) {
                return button;
            }
            current = current.getParent();
        }
        return null;
    }

    private static void captureTab(JTabbedPane tabs, String title, String name, ShotSink sink, boolean includeEditors) {
        int index = SwingSearch.indexOf(tabs, title);
        if (index < 0) {
            return;
        }
        select(tabs, index);
        saveView(name, tabs, sink, includeEditors);
    }

    private static void saveView(String name, Component view, ShotSink sink, boolean includeEditors) {
        layout(view);
        if (view.getWidth() <= 0 || view.getHeight() <= 0) {
            sink.accept(name, new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB),
                    "The section has no size yet. Open it once in Burp and try again.");
            return;
        }
        BufferedImage gui = ComponentImages.capture(view);
        if (!includeEditors) {
            sink.accept(name, gui, "");
            return;
        }
        Component editorRoot = view;
        if (view instanceof JTabbedPane tabs && tabs.getSelectedComponent() != null) {
            editorRoot = tabs.getSelectedComponent();
        }
        EditorShot request = editorShot(editorRoot, true);
        EditorShot response = editorShot(editorRoot, false);
        List<BufferedImage> parts = new ArrayList<>();
        List<String> captions = new ArrayList<>();
        parts.add(gui);
        captions.add(null);
        StringBuilder detail = new StringBuilder();
        appendEditor(parts, captions, detail, "Request", request);
        appendEditor(parts, captions, detail, "Response", response);
        if (request == null && response == null) {
            detail.append("Request and response editors were not found. Saved the visible section only.");
        }
        long height = 0;
        for (BufferedImage part : parts) {
            height += part.getHeight();
        }
        if (parts.size() > 1 && height <= 30000) {
            sink.accept(name, ComponentImages.stack(parts, captions), detail.toString().trim());
            return;
        }
        sink.accept(name + "-section", gui, detail.toString().trim());
        if (request != null) {
            sink.accept(name + "-request", request.image(), editorDetail("Request", request));
        }
        if (response != null) {
            sink.accept(name + "-response", response.image(), editorDetail("Response", response));
        }
    }

    private static void appendEditor(List<BufferedImage> parts, List<String> captions, StringBuilder detail,
                                     String label, EditorShot shot) {
        if (shot == null) {
            detail.append(label).append(" editor was not found. ");
            return;
        }
        parts.add(shot.image());
        captions.add(label);
        if (shot.truncated()) {
            detail.append(label)
                    .append(" was truncated at ")
                    .append(ComponentImages.MAX_WIDTH)
                    .append("x")
                    .append(ComponentImages.MAX_HEIGHT)
                    .append(" pixels. ");
        }
    }

    private static String editorDetail(String label, EditorShot shot) {
        if (!shot.truncated()) {
            return "";
        }
        return label + " was truncated at " + ComponentImages.MAX_WIDTH + "x" + ComponentImages.MAX_HEIGHT + " pixels.";
    }

    private static EditorShot editorShot(Component view, boolean request) {
        JTabbedPane tabs = SwingSearch.paneWithTitle(view, request ? "request" : "response");
        if (tabs != null) {
            int index = SwingSearch.indexOf(tabs, request ? "request" : "response");
            int previous = tabs.getSelectedIndex();
            try {
                select(tabs, index);
                Component page = tabs.getComponentAt(index);
                JScrollPane pane = SwingSearch.scrollBody(page);
                if (pane == null) {
                    pane = request ? SwingSearch.requestEditor(page) : SwingSearch.responseEditor(page);
                }
                return shotOf(pane);
            } finally {
                select(tabs, previous);
            }
        }
        JScrollPane pane = request ? SwingSearch.requestEditor(view) : SwingSearch.responseEditor(view);
        return shotOf(pane);
    }

    private static EditorShot shotOf(JScrollPane pane) {
        if (pane == null || pane.getWidth() <= 0 || pane.getHeight() <= 0) {
            return null;
        }
        ComponentImages.Stitched stitched = ComponentImages.captureScroll(pane);
        return new EditorShot(stitched.image(), stitched.truncated());
    }

    private static void select(JTabbedPane tabs, int index) {
        if (index >= 0 && index < tabs.getTabCount() && tabs.getSelectedIndex() != index) {
            tabs.setSelectedIndex(index);
        }
        layout(tabs);
    }

    private static void layout(Component component) {
        if (component == null) {
            return;
        }
        component.invalidate();
        component.validate();
        if (component.getParent() != null) {
            component.getParent().validate();
        }
    }

    private record EditorShot(BufferedImage image, boolean truncated) {
    }
}
