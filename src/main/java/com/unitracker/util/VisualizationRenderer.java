package com.unitracker.util;

import com.unitracker.model.Skill;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * All Canvas-based visualizations live here as stateless static methods,
 * separate from DashboardController, which only decides WHICH one to call
 * and WHEN. Every method fully owns its drawing: it clears the canvas,
 * draws its content (or a helpful placeholder message if it can't), and
 * returns.
 *
 * DESIGN NOTE ON "SHAPES": Comb-Shaped and I-Shaped are the two remaining
 * bar-based views (T-Shaped and Pi-Shaped were removed as unneeded).
 * Comb-Shaped draws a horizontal "breadth" bar plus a deep bar for zero or
 * more selected skills via {@link #renderBreadthAndDepth}; I-Shaped has no
 * breadth bar at all, just one centered bar, so it stays a separate method.
 */
public final class VisualizationRenderer {

    private static final Color BG_MUTED = Color.web("#6E7A8F");
    private static final Color TEXT_PRIMARY = Color.web("#E7ECF3");
    private static final Color TEXT_SECONDARY = Color.web("#AAB4C4");
    private static final Color GRID_LINE = Color.web("#2A3B52");
    private static final Color BREADTH_BAR = Color.web("#414F6C");
    private static final Color LIME = Color.web("#A8EB12");

    /** Skill Decay: a stalled skill is drawn in this muted red-grey instead of
     *  its own colour, so "I've abandoned this" is visible at a glance in every
     *  bar/node view without having to click through each skill's status. */
    private static final Color STALLED_COLOR = Color.web("#7A5C68");

    private VisualizationRenderer() {
        // Static utility class - no instances.
    }

    /** Single source of truth for "what colour is this skill drawn in" -
     *  its own colour when active, the stalled tint when decayed. Every
     *  renderer goes through here so no view can disagree with another. */
    private static Color barColor(Skill skill) {
        return skill.isActive() ? Color.web(skill.getColorHex()) : STALLED_COLOR;
    }

    // =================================================================
    //  I-SHAPED  (single deep bar, no breadth bar - the "outlier" shape)
    // =================================================================

    public static void renderIShaped(GraphicsContext gc, double w, double h, Skill skill) {
        gc.clearRect(0, 0, w, h);
        gc.setFont(Font.font("Space Grotesk", 12));

        if (skill == null) {
            drawPlaceholder(gc, w, h, "Select a skill first.");
            return;
        }

        double baseY = h - 40, topY = 50, barWidth = 34;
        double x = w / 2 - barWidth / 2;
        double depth = skill.progressProperty().get();
        double barHeight = (baseY - topY) * depth;

        gc.setFill(barColor(skill));
        gc.fillRoundRect(x, baseY - barHeight, barWidth, barHeight, 8, 8);
        gc.setFill(TEXT_PRIMARY);
        gc.fillText(skill.getName() + (skill.isActive() ? "" : "  (stalled)"), x - 10, baseY + 20);
    }

    // =================================================================
    //  T / PI / COMB-SHAPED  (breadth bar + depth for a chosen subset)
    // =================================================================

    /** Top-Y of the breadth bar in canvas-space (topY(50) - 14) - single
     *  source of truth, also read by DashboardController#repositionBreadthLabel
     *  so the editable "General Knowledge" label can track this exact
     *  position instead of duplicating the magic number. */
    public static final double BREADTH_BAR_TOP_Y = 36;

    /**
     * @param allSkills every tracked skill - drawn along the breadth bar regardless of selection.
     * @param deepSkills the subset that also gets a "deep" bar rising from the breadth bar
     *                   (Comb-Shaped typically passes all of them - the caller decides, this
     *                   method just draws whatever set it's given).
     * @param rotateLabels false (default) = horizontal, word-wrapped, center-aligned labels;
     *                     true = the older -40deg diagonal style. Wired to a CheckBox next to
     *                     V-Spacing in the FXML so the user can pick per preference/screen space.
     */
    public static void renderBreadthAndDepth(GraphicsContext gc, double w, double h,
                                              List<Skill> allSkills, Set<Skill> deepSkills, boolean rotateLabels) {
        gc.clearRect(0, 0, w, h);
        gc.setFont(Font.font("Space Grotesk", 12));

        if (allSkills.isEmpty()) {
            drawPlaceholder(gc, w, h, "Add a skill to see this chart.");
            return;
        }

        double baseY = h - 70, topY = 50, barWidth = 34;
        double spacing = w / (allSkills.size() + 1);
        Font labelFont = Font.font("Poppins", 10);

        gc.setFill(BREADTH_BAR);
        gc.fillRoundRect(30, topY - 14, w - 60, 10, 6, 6);

        int index = 1;
        for (Skill s : allSkills) {
            boolean deep = deepSkills.contains(s);
            double progress = s.progressProperty().get();
            double barHeight = deep ? (baseY - topY) * progress : 6;
            double x = spacing * index - barWidth / 2;

            gc.setFill(barColor(s));
            gc.fillRoundRect(x, baseY - barHeight, barWidth, barHeight, 8, 8);

            if (rotateLabels) {
                drawRotatedLabel(gc, s.getName(), x + barWidth / 2, baseY + 14, TEXT_SECONDARY);
            } else {
                drawWrappedCenteredLabel(gc, s.getName(), x + barWidth / 2, baseY + 16,
                        Math.max(spacing - 8, 40), 3, 12, labelFont, TEXT_SECONDARY);
            }
            index++;
        }
    }

    /**
     * Draws up to {@code maxLines} lines of horizontal, center-aligned text
     * anchored at (centerX, topY), wrapping on word boundaries measured
     * against the ACTUAL rendered width of {@code font} (via a throwaway
     * {@link Text} node - JavaFX Canvas has no built-in text-measurement
     * call, but Text#getLayoutBounds() works correctly even for a node
     * that's never attached to a Scene, since font metrics don't require
     * scene-graph attachment). This is the direct replacement for the
     * earlier rotated-label approach.
     */
    private static void drawWrappedCenteredLabel(GraphicsContext gc, String text, double centerX, double topY,
                                                  double maxWidth, int maxLines, double lineHeight,
                                                  Font font, Color color) {
        List<String> lines = wrapTextToWidth(text, font, maxWidth, maxLines);
        gc.setFont(font);
        gc.setFill(color);
        gc.setTextAlign(TextAlignment.CENTER);
        double y = topY;
        for (String line : lines) {
            gc.fillText(line, centerX, y);
            y += lineHeight;
        }
        gc.setTextAlign(TextAlignment.LEFT); // restore the default so later drawing elsewhere isn't affected
    }

    /** Greedy word-wrap: keeps adding words to the current line while they
     *  still fit within maxWidth, breaks to a new line otherwise, and stops
     *  producing further lines once maxLines is reached (any remaining
     *  words are simply dropped rather than overflowing further - realistic
     *  skill names rarely need more than 2-3 lines at a sane column width). */
    private static List<String> wrapTextToWidth(String text, Font font, double maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) return lines;

        String[] words = text.trim().split("\\s+");
        StringBuilder currentLine = new StringBuilder();

        for (String word : words) {
            String candidate = currentLine.isEmpty() ? word : currentLine + " " + word;
            if (currentLine.isEmpty() || measureTextWidth(candidate, font) <= maxWidth) {
                currentLine = new StringBuilder(candidate);
            } else {
                lines.add(currentLine.toString());
                currentLine = new StringBuilder(word);
                if (lines.size() == maxLines) {
                    return lines;
                }
            }
        }
        if (!currentLine.isEmpty()) {
            lines.add(currentLine.toString());
        }
        return lines.size() > maxLines ? lines.subList(0, maxLines) : lines;
    }

    private static double measureTextWidth(String text, Font font) {
        Text probe = new Text(text);
        probe.setFont(font);
        return probe.getLayoutBounds().getWidth();
    }

    // =================================================================
    //  SKILL-TREE  (root -> Category -> Skill -> Subskill 1 -> Subskill 2
    //  -> ... - genuinely recursive now, reads Skill#getChildren() directly,
    //  so it draws whatever depth the database actually has, unlimited.
    // =================================================================

    /** Node radius scales with progress for LEAF nodes (bigger = more
     *  mastered); Stalled leaves get a dark ring so "decaying" reads
     *  visually even without checking the Active/Stalled toggle. Branch
     *  nodes (Category/Skill/Subskill-with-children) get a fixed-size dot
     *  in the muted breadth-bar color instead, since progress on a branch
     *  is ambiguous - it's whatever the user typed into that node directly,
     *  not an aggregate of its children (see DatabaseHelper note on that).
     *  @param roots the (possibly filter-pruned) forest returned by
     *               DatabaseHelper#getSkillTree - NOT a flat list anymore.
     *  @param rotateLabels same flag as renderBreadthAndDepth - default
     *                      (false) is horizontal/wrapped, rotated is opt-in. */
    public static void renderSkillTree(GraphicsContext gc, double w, double h,
                                        List<Skill> roots, boolean rotateLabels) {
        gc.clearRect(0, 0, w, h);
        gc.setFont(Font.font("Poppins", 11));

        if (roots.isEmpty()) {
            drawPlaceholder(gc, w, h, "Select at least one skill to draw the tree.");
            return;
        }

        int maxDepth = Skill.maxDepth(roots); // 0 if every root is a childless Category
        double topMargin = 34, bottomMargin = 46;
        double bandHeight = (h - topMargin - bottomMargin) / (maxDepth + 2); // +1 synthetic super-root band, +1 so depths 0..maxDepth each get their own band

        double superRootX = w / 2, superRootY = topMargin;
        gc.setFill(LIME);
        gc.fillOval(superRootX - 8, superRootY - 8, 16, 16);
        gc.setFill(TEXT_PRIMARY);
        gc.fillText("Skills", superRootX - 14, superRootY - 14);

        double sliceWidth = w / roots.size();
        for (int i = 0; i < roots.size(); i++) {
            double sliceCenterX = sliceWidth * i + sliceWidth / 2.0;
            double nodeY = topMargin + bandHeight;

            gc.setStroke(BREADTH_BAR);
            gc.setLineWidth(1.5);
            gc.strokeLine(superRootX, superRootY + 8, sliceCenterX, nodeY - 10);

            drawSkillTreeNode(gc, roots.get(i), sliceCenterX, nodeY, sliceWidth, bandHeight, rotateLabels);
        }
    }

    /** Draws one node then recurses into its children, splitting the
     *  parent's horizontal slice evenly between them - a simple equal-width
     *  tree layout. Not the fanciest algorithm (no collision-aware spacing
     *  for very unbalanced trees), but predictable and correct, and V-Spacing
     *  already gives the user a way to stretch canvas height for deep trees. */
    private static void drawSkillTreeNode(GraphicsContext gc, Skill node, double x, double y,
                                           double sliceWidth, double bandHeight, boolean rotateLabels) {
        boolean isBranch = !node.isLeaf();
        double progress = node.progressProperty().get();
        double nodeRadius = isBranch ? 9 : (6 + progress * 10); // 6..16px for leaves, fixed 9px for branches

        gc.setFill(isBranch ? BREADTH_BAR : barColor(node));
        gc.fillOval(x - nodeRadius, y - nodeRadius, nodeRadius * 2, nodeRadius * 2);
        if (!isBranch && !node.isActive()) {
            gc.setStroke(Color.web("#0B1A2B"));
            gc.setLineWidth(2);
            gc.strokeOval(x - nodeRadius, y - nodeRadius, nodeRadius * 2, nodeRadius * 2);
        }

        Color labelColor = isBranch ? TEXT_SECONDARY : TEXT_PRIMARY;
        if (rotateLabels) {
            drawRotatedLabel(gc, node.getName(), x, y + nodeRadius + 14, labelColor);
        } else {
            drawWrappedCenteredLabel(gc, node.getName(), x, y + nodeRadius + 16,
                    Math.max(sliceWidth - 8, 40), 2, 12, Font.font("Poppins", 10), labelColor);
        }

        List<Skill> children = node.getChildren();
        if (children.isEmpty()) return;

        double childSliceWidth = sliceWidth / children.size();
        double childY = y + bandHeight;
        double sliceLeftEdge = x - sliceWidth / 2.0;
        for (int i = 0; i < children.size(); i++) {
            double childX = sliceLeftEdge + childSliceWidth * i + childSliceWidth / 2.0;

            gc.setStroke(BREADTH_BAR);
            gc.setLineWidth(1.5);
            gc.strokeLine(x, y + nodeRadius, childX, childY - 10);

            drawSkillTreeNode(gc, children.get(i), childX, childY, childSliceWidth, bandHeight, rotateLabels);
        }
    }

    /** Rotated -40deg text, used only by Skill-Tree (its leaf labels stay
     *  rotated - only Comb-Shaped and Radar were asked to switch to
     *  horizontal wrapped text). */
    private static void drawRotatedLabel(GraphicsContext gc, String text, double anchorX, double anchorY, Color color) {
        gc.save();
        gc.translate(anchorX, anchorY);
        gc.rotate(-40);
        gc.setFill(color);
        gc.fillText(text, 0, 0);
        gc.restore();
    }

    // =================================================================
    //  RADAR CHART  (one axis per skill, polygon connects progress points)
    // =================================================================

    public static void renderRadar(GraphicsContext gc, double w, double h, List<Skill> filteredSkills) {
        gc.clearRect(0, 0, w, h);
        gc.setFont(Font.font("Poppins", 11));

        if (filteredSkills.size() < 3) {
            drawPlaceholder(gc, w, h, "Select at least 3 skills to draw a radar chart.");
            return;
        }

        double cx = w / 2, cy = h / 2 + 10;
        double maxRadius = Math.min(w, h) / 2 - 50;
        int n = filteredSkills.size();

        gc.setStroke(GRID_LINE);
        gc.setLineWidth(1);
        for (int ring = 1; ring <= 4; ring++) {
            double r = maxRadius * ring / 4.0;
            gc.strokeOval(cx - r, cy - r, r * 2, r * 2);
        }

        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            double angle = -Math.PI / 2 + i * (2 * Math.PI / n);
            double axisX = cx + maxRadius * Math.cos(angle);
            double axisY = cy + maxRadius * Math.sin(angle);
            gc.setStroke(GRID_LINE);
            gc.strokeLine(cx, cy, axisX, axisY);

            Skill s = filteredSkills.get(i);
            double progress = s.progressProperty().get();
            xs[i] = cx + maxRadius * progress * Math.cos(angle);
            ys[i] = cy + maxRadius * progress * Math.sin(angle);

            gc.setFill(TEXT_SECONDARY);
            double labelX = cx + (maxRadius + 20) * Math.cos(angle);
            double labelY = cy + (maxRadius + 20) * Math.sin(angle);
            drawWrappedCenteredLabel(gc, s.getName(), labelX, labelY, 78, 2, 12,
                    Font.font("Poppins", 10), TEXT_SECONDARY);
        }

        gc.beginPath();
        gc.moveTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) {
            gc.lineTo(xs[i], ys[i]);
        }
        gc.closePath();
        gc.setFill(Color.rgb(168, 235, 18, 0.25));
        gc.fill();
        gc.setStroke(LIME);
        gc.setLineWidth(2);
        gc.stroke();

        for (int i = 0; i < n; i++) {
            gc.setFill(barColor(filteredSkills.get(i)));
            gc.fillOval(xs[i] - 4, ys[i] - 4, 8, 8);
        }
    }

    // =================================================================
    //  TIME DISTRIBUTION PIE  (zero-budget maximizer #2)
    // =================================================================

    /**
     * "Where does my time actually go?" - minutes invested per root Category,
     * as a donut. Answers a different question from the points charts: points
     * are self-assigned and can be gamed, minutes are what you really spent.
     *
     * <p>Drawn on the existing Canvas rather than added as a JavaFX PieChart
     * node, so it inherits the zoom/pan/export plumbing every other structural
     * view already has for free.
     *
     * @param minutesPerCategory category name -> minutes, biggest first
     *                           (DatabaseHelper#getMinutesPerRootCategory).
     */
    public static void renderTimePie(GraphicsContext gc, double w, double h,
                                     Map<String, Integer> minutesPerCategory) {
        gc.clearRect(0, 0, w, h);
        if (minutesPerCategory == null || minutesPerCategory.isEmpty()) {
            drawPlaceholder(gc, w, h, "No time logged yet - log a session with minutes to see the split.");
            return;
        }

        long totalMinutes = 0;
        for (int m : minutesPerCategory.values()) totalMinutes += m;
        if (totalMinutes <= 0) {
            drawPlaceholder(gc, w, h, "No time logged yet.");
            return;
        }

        double cx = w * 0.36;
        double cy = h / 2;
        double radius = Math.min(w * 0.30, h * 0.36);
        double innerRadius = radius * 0.55; // donut hole - leaves room for the total

        gc.setFont(Font.font("Poppins", 11));
        double startAngle = 90; // 12 o'clock; JavaFX arcs sweep counter-clockwise
        int index = 0;
        double legendY = cy - radius;

        for (Map.Entry<String, Integer> entry : minutesPerCategory.entrySet()) {
            double share = entry.getValue() / (double) totalMinutes;
            double extent = -share * 360; // negative = clockwise, so slices read left-to-right
            Color slice = PIE_PALETTE[index % PIE_PALETTE.length];

            gc.setFill(slice);
            gc.fillArc(cx - radius, cy - radius, radius * 2, radius * 2, startAngle, extent, ArcType.ROUND);
            startAngle += extent;

            // Legend beside the donut, not on it: slice labels overlap badly
            // once any category drops below ~5%.
            gc.setFill(slice);
            gc.fillRoundRect(cx + radius + 24, legendY, 11, 11, 3, 3);
            gc.setFill(TEXT_PRIMARY);
            gc.fillText(entry.getKey(), cx + radius + 42, legendY + 10);
            gc.setFill(TEXT_SECONDARY);
            gc.fillText(formatHours(entry.getValue()) + "  ·  " + Math.round(share * 100) + "%",
                    cx + radius + 42, legendY + 24);
            legendY += 34;
            index++;
        }

        // Punch the donut hole with the panel background, then centre the total in it.
        gc.setFill(Color.web("#16263A"));
        gc.fillOval(cx - innerRadius, cy - innerRadius, innerRadius * 2, innerRadius * 2);

        gc.setFill(TEXT_PRIMARY);
        gc.setFont(Font.font("Space Grotesk", 16));
        String totalText = formatHours(totalMinutes);
        gc.fillText(totalText, cx - measureTextWidth(totalText, gc.getFont()) / 2, cy);
        gc.setFill(TEXT_SECONDARY);
        gc.setFont(Font.font("Poppins", 10));
        gc.fillText("total", cx - measureTextWidth("total", gc.getFont()) / 2, cy + 16);
    }

    /** 90 -> "1h 30m", 45 -> "45m". Raw minute counts get unreadable fast
     *  once a category passes a few hundred. */
    private static String formatHours(long minutes) {
        if (minutes < 60) return minutes + "m";
        long h = minutes / 60;
        long m = minutes % 60;
        return m == 0 ? h + "h" : h + "h " + m + "m";
    }

    private static final Color[] PIE_PALETTE = {
            Color.web("#A8EB12"), Color.web("#008793"), Color.web("#4CC9F0"),
            Color.web("#9B5DE5"), Color.web("#FF8552"), Color.web("#FFD23F"),
            Color.web("#F72585"), Color.web("#414F6C")
    };

    // =================================================================
    //  HELPERS
    // =================================================================

    private static void drawPlaceholder(GraphicsContext gc, double w, double h, String message) {
        gc.setFill(BG_MUTED);
        gc.setFont(Font.font("Poppins", 12));
        // Rough centering - good enough for a short hint message.
        gc.fillText(message, w / 2 - (message.length() * 3.2), h / 2);
    }
}
