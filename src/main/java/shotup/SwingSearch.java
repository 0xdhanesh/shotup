package shotup;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.swing.AbstractButton;
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
     * Repeater's own tab strip, not the Pretty/Raw switcher or the
     * Request/Response switcher nested inside a message.
     */
    static JTabbedPane repeaterTabs(Component repeaterRoot) {
        JTabbedPane found = null;
        int foundTabs = -1;
        int foundDepth = Integer.MAX_VALUE;
        for (JTabbedPane pane : findAll(repeaterRoot, JTabbedPane.class)) {
            if (pane.getTabCount() == 0 || isEditorChrome(pane)) {
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
            String title = normalize(pane.getTitleAt(i));
            if (title.equals("request") || title.equals("response")) {
                requestOrResponse = true;
            }
            if (!EDITOR_VIEW_TITLES.contains(title)) {
                onlyViews = false;
            }
        }
        return requestOrResponse || onlyViews;
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

    private static boolean contains(Component ancestor, Component child) {
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
