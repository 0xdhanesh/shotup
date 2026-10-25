package shotup;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;

/**
 * Walks the Swing tree Burp exposes through {@code SwingUtils.suiteFrame()}.
 * Tab titles and labels are the only structure the official API gives us.
 */
final class SwingSearch {
    private static final Set<String> EDITOR_VIEW_TITLES = Set.of(
            "pretty", "raw", "hex", "render", "headers");
    private static final Set<String> CAPTION_CHROME = Set.of(
            "send", "cancel", "pretty", "raw", "hex", "render", "headers",
            "inspector", "notes", "note", "search", "target", "proxy", "repeater",
            "http/1", "http/2", "burp ai", "custom actions", "request", "response",
            "intercept", "http history", "websockets history", "dashboard", "learn",
            "organizer", "intruder", "logger", "comparer", "decoder", "sequencer",
            "extensions", "collaborator");

    private SwingSearch() {
    }

    static List<Component> walk(Component root) {
        List<Component> found = new ArrayList<>();
        walk(root, found, new IdentityHashMap<>());
        return found;
    }

    static <T extends Component> List<T> findAll(Component root, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component component : walk(root)) {
            if (type.isInstance(component)) {
                found.add(type.cast(component));
            }
        }
        return found;
    }

    static JTabbedPane suiteTabs(Component root) {
        JTabbedPane found = null;
        for (JTabbedPane pane : findAll(root, JTabbedPane.class)) {
            if (indexOf(pane, "proxy") >= 0 && indexOf(pane, "repeater") >= 0) {
                found = pane;
            }
        }
        return found;
    }

    static int indexOf(JTabbedPane pane, String title) {
        String expected = normalize(title);
        for (int i = 0; i < pane.getTabCount(); i++) {
            if (expected.equals(normalize(pane.getTitleAt(i)))) {
                return i;
            }
        }
        return -1;
    }

    static String normalize(String title) {
        if (title == null) {
            return "";
        }
        return title.replace("&", "").trim().toLowerCase(Locale.ROOT);
    }

    static JTabbedPane paneWithTitle(Component root, String title) {
        for (JTabbedPane pane : findAll(root, JTabbedPane.class)) {
            if (indexOf(pane, title) >= 0) {
                return pane;
            }
        }
        return null;
    }

    /**
     * Repeater's own tab strip, not the Pretty/Raw switcher, the
     * Request/Response switcher, or the Inspector / Notes / Custom actions rail.
     */
    static JTabbedPane repeaterTabs(Component repeaterRoot) {
        JTabbedPane found = null;
        int foundTabs = -1;
        int foundDepth = Integer.MAX_VALUE;
        for (JTabbedPane pane : findAll(repeaterRoot, JTabbedPane.class)) {
            if (pane.getTabCount() == 0 || isEditorChrome(pane) || isSideDock(pane)) {
                continue;
            }
            int depth = depth(repeaterRoot, pane);
            if (depth < 0) {
                continue;
            }
            if (pane.getTabCount() > foundTabs || (pane.getTabCount() == foundTabs && depth < foundDepth)) {
                found = pane;
                foundTabs = pane.getTabCount();
                foundDepth = depth;
            }
        }
        return found;
    }

    /**
     * Burp 2026 paints Repeater captions on their own strip. Those captions are
     * buttons or labels, and the only {@link JTabbedPane} in the tool is often
     * the right-hand rail.
     */
    static List<Component> repeaterCaptions(Component repeaterRoot) {
        List<Component> captions = new ArrayList<>();
        for (Component component : walk(repeaterRoot)) {
            if (!(component instanceof JLabel) && !(component instanceof AbstractButton)) {
                continue;
            }
            if (component instanceof JLabel && component.getParent() instanceof AbstractButton) {
                continue;
            }
            if (!isRepeaterCaption(captionText(component)) || inChrome(component)) {
                continue;
            }
            captions.add(component);
        }
        List<Component> grouped = List.of();
        for (int levels = 1; levels <= 3 && grouped.size() < 2; levels++) {
            grouped = largestSiblingGroup(captions, levels);
        }
        if (grouped.size() < 2) {
            return List.of();
        }
        List<Component> sorted = new ArrayList<>(grouped);
        sorted.sort(Comparator.comparingInt(SwingSearch::captionY).thenComparingInt(SwingSearch::captionX));
        return sorted;
    }

    /**
     * The request or response editor. Burp 2026 names those panels
     * {@code httpRequestMessageAnalyser} and {@code httpResponseMessageAnalyser}.
     */
    static Component messageEditor(Component root, boolean request) {
        if (root == null) {
            return null;
        }
        String name = request ? "httpRequestMessageAnalyser" : "httpResponseMessageAnalyser";
        Component named = namedEditor(root, name);
        if (named != null && !insideSideDock(named)) {
            return growEditor(named);
        }
        String role = request ? "request" : "response";
        Component marker = showingMarker(root, role);
        if (marker == null) {
            marker = marker(root, role);
        }
        if (marker == null || insideSideDock(marker)) {
            return null;
        }
        Container searchRoot = ancestor(marker);
        if (searchRoot == null) {
            return null;
        }
        JScrollPane pane = nearest(marker, largeScrollPanes(searchRoot, marker));
        if (pane == null || insideSideDock(pane)) {
            return null;
        }
        return growEditor(pane);
    }

    /** Tightest component that holds both editors and not the side rail. */
    static Component editorArea(Component request, Component response) {
        Component current = request;
        while (current != null) {
            if (current != request && contains(current, response)) {
                if (containsSideDock(current, request)) {
                    return null;
                }
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    /** Include the Send bar when an ancestor holds it and still excludes the rail. */
    static Component includeToolbar(Component area) {
        if (area == null) {
            return null;
        }
        Component best = area;
        Component current = area;
        while (current.getParent() != null) {
            Component parent = current.getParent();
            if (containsSideDock(parent, area)) {
                break;
            }
            if (hasSend(parent)) {
                best = parent;
            }
            current = parent;
        }
        return best;
    }

    /**
     * Notes content when that rail tab is already selected. Inspector and
     * Custom actions are not returned, and this does not switch tabs.
     */
    static Component visibleNotes(Component root) {
        if (root == null) {
            return null;
        }
        for (JTabbedPane pane : findAll(root, JTabbedPane.class)) {
            if (!isSideDock(pane) || !pane.isShowing()) {
                continue;
            }
            int index = pane.getSelectedIndex();
            if (index < 0 || !isNotesLabel(tabLabel(pane, index))) {
                continue;
            }
            Component selected = pane.getComponentAt(index);
            if (selected != null && selected.isShowing() && selected.getWidth() > 40 && selected.getHeight() > 40) {
                return selected;
            }
        }
        Component named = namedEditor(root, "notesCollapsibleViewTextArea");
        if (named != null && named.isShowing() && !insideSideDock(named)
                && named.getWidth() > 40 && named.getHeight() > 40) {
            return named;
        }
        return null;
    }

    static String tabLabel(JTabbedPane pane, int index) {
        String title = normalize(pane.getTitleAt(index));
        if (!title.isEmpty()) {
            return title;
        }
        Component header = pane.getTabComponentAt(index);
        return header == null ? "" : normalize(rawText(header));
    }

    static String captionText(Component component) {
        if (component instanceof AbstractButton button && button.getText() != null) {
            return button.getText();
        }
        if (component instanceof JLabel label && label.getText() != null) {
            return label.getText();
        }
        return "";
    }

    static boolean isSideDock(JTabbedPane pane) {
        boolean inspector = false;
        boolean custom = false;
        boolean notes = false;
        int other = 0;
        for (int i = 0; i < pane.getTabCount(); i++) {
            String title = tabLabel(pane, i);
            if (title.equals("inspector")) {
                inspector = true;
            } else if (title.contains("custom action")) {
                custom = true;
            } else if (isNotesLabel(title)) {
                notes = true;
            } else if (!title.isEmpty()) {
                other++;
            }
        }
        if (other > 0) {
            return false;
        }
        return inspector || custom || (notes && pane.getTabCount() > 1);
    }

    /** The scroll pane that holds a message editor, including a tab whose content is the pane itself. */
    static JScrollPane scrollBody(Component root) {
        if (root instanceof JScrollPane pane && isLarge(pane)) {
            return pane;
        }
        JScrollPane best = null;
        int bestArea = 0;
        for (JScrollPane pane : findAll(root, JScrollPane.class)) {
            if (!isLarge(pane)) {
                continue;
            }
            int area = pane.getWidth() * pane.getHeight();
            if (area > bestArea) {
                best = pane;
                bestArea = area;
            }
        }
        return best;
    }

    static boolean isEditorChrome(JTabbedPane pane) {
        if (pane.getTabCount() == 0) {
            return false;
        }
        boolean requestOrResponse = false;
        boolean onlyViews = true;
        for (int i = 0; i < pane.getTabCount(); i++) {
            String title = tabLabel(pane, i);
            if (title.equals("request") || title.equals("response")) {
                requestOrResponse = true;
            }
            if (!EDITOR_VIEW_TITLES.contains(title)) {
                onlyViews = false;
            }
        }
        return requestOrResponse || onlyViews;
    }

    static String rawText(Component component) {
        StringBuilder text = new StringBuilder();
        for (Component child : walk(component)) {
            if (child instanceof JLabel && child.getParent() instanceof AbstractButton) {
                continue;
            }
            String part = captionText(child);
            if (part.isBlank()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(part.trim());
        }
        return text.toString();
    }

    private static boolean isRepeaterCaption(String text) {
        String title = normalize(text);
        if (title.length() < 2 || title.length() > 80) {
            return false;
        }
        return !CAPTION_CHROME.contains(title) && !title.startsWith("send ");
    }

    private static boolean inChrome(Component component) {
        Component current = component;
        while (current != null) {
            if (current instanceof JTabbedPane tabs && (isSideDock(tabs) || isEditorChrome(tabs))) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private static List<Component> largestSiblingGroup(List<Component> captions, int levels) {
        Map<Component, List<Component>> groups = new IdentityHashMap<>();
        for (Component caption : captions) {
            Component key = ancestorAt(caption, levels);
            if (key == null) {
                continue;
            }
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(caption);
        }
        List<Component> best = List.of();
        for (List<Component> group : groups.values()) {
            if (group.size() > best.size()) {
                best = group;
            }
        }
        return best;
    }

    private static Component ancestorAt(Component component, int levels) {
        Component current = component;
        for (int i = 0; i < levels && current != null; i++) {
            current = current.getParent();
        }
        return current;
    }

    private static int captionX(Component component) {
        if (component.isShowing()) {
            return component.getLocationOnScreen().x;
        }
        return component.getX();
    }

    private static int captionY(Component component) {
        if (component.isShowing()) {
            return component.getLocationOnScreen().y;
        }
        return component.getY();
    }

    private static Component namedEditor(Component root, String name) {
        Component best = null;
        long bestScore = -1;
        for (Component component : walk(root)) {
            if (!(component instanceof JComponent jcomponent)) {
                continue;
            }
            String componentName = jcomponent.getName();
            if (componentName == null || !componentName.equalsIgnoreCase(name)) {
                continue;
            }
            if (insideSideDock(component)) {
                continue;
            }
            long area = (long) Math.max(component.getWidth(), 0) * Math.max(component.getHeight(), 0);
            long score = (component.isShowing() ? 1_000_000_000L : 0L) + area;
            if (score > bestScore) {
                best = component;
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean insideSideDock(Component component) {
        Component current = component;
        while (current != null) {
            if (current instanceof JTabbedPane tabs && isSideDock(tabs)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private static Component growEditor(Component editor) {
        Component current = editor;
        while (current.getParent() instanceof JTabbedPane tabs && isEditorChrome(tabs)) {
            current = tabs;
        }
        return current;
    }

    private static Component showingMarker(Component root, String role) {
        for (Component component : walk(root)) {
            if (!component.isShowing()) {
                continue;
            }
            if (role.equals(normalize(captionText(component)))) {
                return component;
            }
        }
        return null;
    }

    private static Container ancestor(Component marker) {
        Component current = marker;
        while (current != null && current.getParent() != null) {
            Container parent = current.getParent();
            if (!largeScrollPanes(parent, marker).isEmpty()) {
                return parent;
            }
            current = parent;
        }
        return marker == null ? null : marker.getParent();
    }

    private static boolean containsSideDock(Component root, Component editor) {
        for (JTabbedPane pane : findAll(root, JTabbedPane.class)) {
            if (isSideDock(pane) && !contains(pane, editor)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSend(Component root) {
        for (Component component : walk(root)) {
            if ("send".equals(normalize(captionText(component)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNotesLabel(String title) {
        return "notes".equals(title) || "note".equals(title);
    }

    static JScrollPane requestEditor(Component view) {
        return editor(view, "request");
    }

    static JScrollPane responseEditor(Component view) {
        return editor(view, "response");
    }

    static String safeName(String value) {
        String cleaned = value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]+", "-");
        cleaned = cleaned.replaceAll("-{2,}", "-");
        cleaned = cleaned.replaceAll("^-|-$", "");
        if (cleaned.length() > 40) {
            cleaned = cleaned.substring(0, 40);
        }
        return cleaned.isEmpty() ? "tab" : cleaned;
    }

    private static JScrollPane editor(Component view, String role) {
        JTabbedPane tabs = paneWithTitle(view, role);
        if (tabs != null) {
            JScrollPane fromTab = largestScroll(tabs.getComponentAt(indexOf(tabs, role)));
            if (fromTab != null) {
                return fromTab;
            }
        }
        Component marker = marker(view, role);
        if (marker == null) {
            return null;
        }
        Component current = marker;
        while (current.getParent() != null) {
            List<JScrollPane> panes = largeScrollPanes(current.getParent(), marker);
            if (!panes.isEmpty()) {
                return nearest(marker, panes);
            }
            current = current.getParent();
        }
        return null;
    }

    private static Component marker(Component root, String role) {
        for (Component component : walk(root)) {
            String text = null;
            if (component instanceof JLabel label) {
                text = label.getText();
            } else if (component instanceof AbstractButton button) {
                text = button.getText();
            }
            if (role.equals(normalize(text))) {
                return component;
            }
        }
        return null;
    }

    private static List<JScrollPane> largeScrollPanes(Container parent, Component marker) {
        List<JScrollPane> panes = new ArrayList<>();
        for (JScrollPane pane : findAll(parent, JScrollPane.class)) {
            if (contains(pane, marker)) {
                continue;
            }
            if (isLarge(pane)) {
                panes.add(pane);
            }
        }
        return panes;
    }

    private static JScrollPane largestScroll(Component root) {
        JScrollPane best = null;
        int bestArea = 0;
        for (JScrollPane pane : findAll(root, JScrollPane.class)) {
            if (!isLarge(pane)) {
                continue;
            }
            int area = pane.getWidth() * pane.getHeight();
            if (area > bestArea) {
                best = pane;
                bestArea = area;
            }
        }
        return best;
    }

    private static JScrollPane nearest(Component marker, List<JScrollPane> panes) {
        int markX = marker.getX();
        int markY = marker.getY();
        if (marker.isShowing()) {
            markX = marker.getLocationOnScreen().x;
            markY = marker.getLocationOnScreen().y;
        }
        JScrollPane best = null;
        long bestDistance = Long.MAX_VALUE;
        for (JScrollPane pane : panes) {
            int x = pane.getX();
            int y = pane.getY();
            if (pane.isShowing()) {
                x = pane.getLocationOnScreen().x;
                y = pane.getLocationOnScreen().y;
            }
            long distance = Math.abs((long) y - markY) * 4 + Math.abs((long) x - markX);
            if (distance < bestDistance) {
                best = pane;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static boolean isLarge(JScrollPane pane) {
        return pane.isShowing() && pane.getWidth() >= 80 && pane.getHeight() >= 40;
    }

    static boolean contains(Component ancestor, Component child) {
        Component current = child;
        while (current != null) {
            if (current == ancestor) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private static int depth(Component root, Component target) {
        return depth(root, target, 0, new IdentityHashMap<>());
    }

    private static int depth(Component root, Component target, int level, IdentityHashMap<Component, Boolean> seen) {
        if (root == null || seen.put(root, Boolean.TRUE) != null) {
            return -1;
        }
        if (root == target) {
            return level;
        }
        if (root instanceof JTabbedPane tabs) {
            for (int i = 0; i < tabs.getTabCount(); i++) {
                int found = depth(tabs.getComponentAt(i), target, level + 1, seen);
                if (found >= 0) {
                    return found;
                }
            }
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                int found = depth(child, target, level + 1, seen);
                if (found >= 0) {
                    return found;
                }
            }
        }
        return -1;
    }

    private static void walk(Component root, List<Component> found, IdentityHashMap<Component, Boolean> seen) {
        if (root == null || seen.put(root, Boolean.TRUE) != null) {
            return;
        }
        found.add(root);
        if (root instanceof JTabbedPane tabs) {
            for (int i = 0; i < tabs.getTabCount(); i++) {
                walk(tabs.getComponentAt(i), found, seen);
            }
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                walk(child, found, seen);
            }
        }
    }
}
