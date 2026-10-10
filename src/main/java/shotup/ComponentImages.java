package shotup;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;

/**
 * Paints Swing components returned by the suite frame. Scroll positions are
 * restored. Images are capped so a huge message cannot exhaust memory.
 */
final class ComponentImages {
    static final int MAX_WIDTH = 8000;
    static final int MAX_HEIGHT = 14000;

    private ComponentImages() {
    }

    static BufferedImage capture(Component component) {
        int width = Math.max(component.getWidth(), 1);
        int height = Math.max(component.getHeight(), 1);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(background(component));
        graphics.fillRect(0, 0, width, height);
        component.paintAll(graphics);
        graphics.dispose();
        return image;
    }

    static Stitched captureScroll(JScrollPane pane) {
        JViewport viewport = pane.getViewport();
        if (viewport == null || viewport.getWidth() <= 0 || viewport.getHeight() <= 0) {
            return new Stitched(capture(pane), false);
        }
        JScrollBar vertical = pane.getVerticalScrollBar();
        JScrollBar horizontal = pane.getHorizontalScrollBar();
        int verticalStart = vertical.getValue();
        int horizontalStart = horizontal.getValue();
        try {
            return stitch(viewport, horizontal, vertical);
        } finally {
            horizontal.setValue(horizontalStart);
            vertical.setValue(verticalStart);
        }
    }

    static BufferedImage stack(List<BufferedImage> parts, List<String> captions) {
        int captionHeight = 28;
        int width = 1;
        int height = 0;
        for (int i = 0; i < parts.size(); i++) {
            BufferedImage part = parts.get(i);
            width = Math.max(width, part.getWidth());
            height += part.getHeight();
            if (captions.get(i) != null) {
                height += captionHeight;
            }
        }
        BufferedImage image = new BufferedImage(width, Math.max(height, 1), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(32, 32, 32));
        graphics.fillRect(0, 0, width, image.getHeight());
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        int y = 0;
        for (int i = 0; i < parts.size(); i++) {
            String caption = captions.get(i);
            if (caption != null) {
                graphics.setColor(new Color(32, 32, 32));
                graphics.fillRect(0, y, width, captionHeight);
                graphics.setColor(Color.WHITE);
                graphics.drawString(caption, 8, y + 19);
                y += captionHeight;
            }
            graphics.drawImage(parts.get(i), 0, y, null);
            y += parts.get(i).getHeight();
        }
        graphics.dispose();
        return image;
    }

    static List<Integer> scrollStops(int minimum, int last, int extent) {
        List<Integer> stops = new ArrayList<>();
        int step = Math.max(extent, 1);
        int position = minimum;
        while (true) {
            stops.add(position);
            if (position >= last || stops.size() > 10000) {
                break;
            }
            int next = Math.min(position + step, last);
            if (next <= position) {
                break;
            }
            position = next;
        }
        return stops;
    }

    private static Stitched stitch(JViewport viewport, JScrollBar horizontal, JScrollBar vertical) {
        int viewWidth = viewport.getWidth();
        int viewHeight = viewport.getHeight();
        int minX = horizontal.getMinimum();
        int minY = vertical.getMinimum();
        int lastX = lastStop(horizontal, viewWidth);
        int lastY = lastStop(vertical, viewHeight);
        boolean truncated = false;
        if ((long) lastX - minX + viewWidth > MAX_WIDTH) {
            lastX = minX + MAX_WIDTH - viewWidth;
            truncated = true;
        }
        if ((long) lastY - minY + viewHeight > MAX_HEIGHT) {
            lastY = minY + MAX_HEIGHT - viewHeight;
            truncated = true;
        }
        lastX = Math.max(minX, lastX);
        lastY = Math.max(minY, lastY);

        List<Integer> xs = scrollStops(minX, lastX, Math.max(horizontal.getVisibleAmount(), viewWidth));
        List<Integer> ys = scrollStops(minY, lastY, Math.max(vertical.getVisibleAmount(), viewHeight));
        int width = lastX - minX + viewWidth;
        int height = lastY - minY + viewHeight;
        BufferedImage image = new BufferedImage(Math.max(width, 1), Math.max(height, 1), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(background(viewport));
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        for (int y : ys) {
            for (int x : xs) {
                horizontal.setValue(x);
                vertical.setValue(y);
                viewport.validate();
                BufferedImage slice = capture(viewport);
                graphics.drawImage(slice, x - minX, y - minY, null);
            }
        }
        graphics.dispose();
        return new Stitched(image, truncated);
    }

    private static int lastStop(JScrollBar bar, int viewportSize) {
        int extent = bar.getVisibleAmount() > 0 ? bar.getVisibleAmount() : viewportSize;
        return Math.max(bar.getMinimum(), bar.getMaximum() - extent);
    }

    private static Color background(Component component) {
        Color color = component.getBackground();
        return color == null ? Color.WHITE : color;
    }

    static void runOnEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Capture was interrupted.", exception);
        } catch (java.lang.reflect.InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        }
    }

    record Stitched(BufferedImage image, boolean truncated) {
    }
}
