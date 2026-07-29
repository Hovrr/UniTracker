package com.unitracker.util;

import com.unitracker.model.ProgressLog;
import com.unitracker.model.Skill;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generates a designed PDF progress report using Apache PDFBox: a colored
 * header band, an embedded screenshot of whichever chart is currently on
 * screen, and a real drawn table (not plain text lines) with a mini
 * progress bar per row.
 *
 * LIBRARY CHOICE (PDFBox vs iText): sticking with PDFBox, same reasoning as
 * the original PoC - it's Apache 2.0 licensed with no strings attached,
 * whereas iText's modern versions are AGPL/commercial-dual-licensed, which
 * matters if this project is ever shared or distributed. PDFBox is also
 * already the integrated dependency, so there's no migration cost. Both
 * are comparable in raw jar size; PDFBox is the lighter *decision* here
 * specifically because of licensing, not bytes.
 *
 * WHERE THE CHART IMAGE COMES FROM: this class has no access to live JavaFX
 * nodes (Node.snapshot() is a JavaFX Scene-graph API - a plain utility
 * class shouldn't reach back into the UI layer). DashboardController takes
 * the snapshot and converts it to a BufferedImage via SwingFXUtils before
 * calling exportProgressReport() - see handleExportPdf() there.
 */
public final class PdfExportUtil {

    private static final float MARGIN = 40;
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    private static final PDType1Font FONT_REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font FONT_BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    private static final Color BRAND_DEEP_BLUE = new Color(0x0B, 0x1A, 0x2B);
    private static final Color BRAND_ASTRONAUT = new Color(0x41, 0x4F, 0x6C);
    private static final Color BRAND_LIME = new Color(0xA8, 0xEB, 0x12);
    private static final Color ROW_ALT = new Color(0xF2, 0xF4, 0xF7);
    private static final Color TRACK_GRAY = new Color(0xE0, 0xE0, 0xE0);

    private PdfExportUtil() {
        // Static utility class - no instances.
    }

    /**
     * @param chartSnapshot the active chart, already rendered to a BufferedImage
     *                      by the caller (may be null - the report still works,
     *                      it just skips the chart section).
     * @param chartTitle    label shown above the embedded chart, e.g. "Comb-Shaped".
     */
    public static void exportProgressReport(File outputFile,
                                              List<Skill> skills,
                                              Map<Integer, List<ProgressLog>> logsBySkill,
                                              BufferedImage chartSnapshot,
                                              String chartTitle) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(document, page);

            float y = drawHeaderBand(cs);

            if (chartSnapshot != null) {
                y = drawChartSection(document, cs, y, chartSnapshot, chartTitle);
            }

            // Start the table on a fresh page if it wouldn't otherwise fit.
            // Row heights are now DYNAMIC (depend on per-skill word-wrap,
            // only known once drawStatsTable actually wraps each cell), so
            // this uses a conservative 2-line-per-row estimate rather than
            // computing exact wrapping twice - simpler, and erring toward
            // starting a new page slightly earlier is a safe failure mode.
            float estimatedRowHeight = 2 * LINE_HEIGHT + ROW_PADDING;
            float tableHeight = (skills.size() + 1) * estimatedRowHeight;
            if (y - tableHeight < MARGIN) {
                cs.close();
                page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                cs = new PDPageContentStream(document, page);
                y = PAGE_HEIGHT - MARGIN;
            }

            drawStatsTable(cs, y, skills);
            cs.close();
            document.save(outputFile);
        }
    }

    // =================================================================
    //  HEADER
    // =================================================================

    private static float drawHeaderBand(PDPageContentStream cs) throws IOException {
        float bandHeight = 56;
        cs.setNonStrokingColor(BRAND_DEEP_BLUE);
        cs.addRect(0, PAGE_HEIGHT - bandHeight, PAGE_WIDTH, bandHeight);
        cs.fill();

        cs.setNonStrokingColor(BRAND_LIME);
        cs.beginText();
        cs.setFont(FONT_BOLD, 20);
        cs.newLineAtOffset(MARGIN, PAGE_HEIGHT - 34);
        cs.showText("Uni Tracker");
        cs.endText();

        cs.setNonStrokingColor(Color.WHITE);
        cs.beginText();
        cs.setFont(FONT_REGULAR, 10);
        cs.newLineAtOffset(MARGIN, PAGE_HEIGHT - 48);
        cs.showText("Progress Report \u2014 generated " + LocalDate.now().format(DATE_FMT));
        cs.endText();

        return PAGE_HEIGHT - bandHeight - 24;
    }

    // =================================================================
    //  CHART SECTION
    // =================================================================

    private static float drawChartSection(PDDocument document, PDPageContentStream cs, float y,
                                           BufferedImage chartSnapshot, String chartTitle) throws IOException {
        cs.setNonStrokingColor(Color.BLACK);
        cs.beginText();
        cs.setFont(FONT_BOLD, 12);
        cs.newLineAtOffset(MARGIN, y);
        cs.showText("Active view: " + (chartTitle == null ? "Chart" : chartTitle));
        cs.endText();
        y -= 16;

        PDImageXObject pdImage = LosslessFactory.createFromImage(document, chartSnapshot);

        // Fit within CONTENT_WIDTH and a max height cap, preserving aspect ratio.
        float maxHeight = 260;
        float drawWidth = CONTENT_WIDTH;
        float drawHeight = drawWidth * ((float) chartSnapshot.getHeight() / chartSnapshot.getWidth());
        if (drawHeight > maxHeight) {
            drawHeight = maxHeight;
            drawWidth = drawHeight * ((float) chartSnapshot.getWidth() / chartSnapshot.getHeight());
        }

        float imgX = MARGIN + (CONTENT_WIDTH - drawWidth) / 2; // centered
        float imgY = y - drawHeight;

        // Thin border around the embedded chart so it reads as a deliberate figure.
        cs.setStrokingColor(BRAND_ASTRONAUT);
        cs.setLineWidth(1);
        cs.addRect(imgX, imgY, drawWidth, drawHeight);
        cs.stroke();

        cs.drawImage(pdImage, imgX, imgY, drawWidth, drawHeight);

        return imgY - 24;
    }

    // =================================================================
    //  STATS TABLE
    // =================================================================

    private static final float ROW_PADDING = 6;
    private static final float LINE_HEIGHT = 11;
    private static final float CELL_TEXT_PAD = 6;
    // Skill | Category | Status | Current | Target | Completion(bar) - Category widened
    // (110 vs the old 90) as a complementary fix: word-wrap now handles ANY overflow
    // gracefully, but a wider column means it's rarely even needed for common values.
    private static final float[] COL_WIDTHS = {115, 110, 60, 60, 60, 100};
    private static final String[] HEADERS = {"Skill", "Category", "Status", "Current", "Target", "Completion"};

    private static void drawStatsTable(PDPageContentStream cs, float startY, List<Skill> skills) throws IOException {
        float tableWidth = sum(COL_WIDTHS);
        float y = startY;

        // Header row - headers are short/known, so a fixed single-line height is fine here.
        float headerHeight = LINE_HEIGHT + ROW_PADDING;
        cs.setNonStrokingColor(BRAND_ASTRONAUT);
        cs.addRect(MARGIN, y - headerHeight, tableWidth, headerHeight);
        cs.fill();

        float x = MARGIN;
        cs.setNonStrokingColor(Color.WHITE);
        for (int i = 0; i < HEADERS.length; i++) {
            cs.beginText();
            cs.setFont(FONT_BOLD, 9);
            cs.newLineAtOffset(x + CELL_TEXT_PAD, y - headerHeight + 5);
            cs.showText(HEADERS[i]);
            cs.endText();
            x += COL_WIDTHS[i];
        }
        y -= headerHeight;

        boolean alt = false;
        for (Skill skill : skills) {
            double ratio = skill.getTargetPoints() <= 0
                    ? 0 : Math.min(1.0, skill.getCurrentPoints() / skill.getTargetPoints());

            // Wrap every text cell against its OWN column's usable width (column width
            // minus padding on both sides) - this is what fixes the "Programming
            // Language" running into "ACTIVE" bug: each cell now genuinely respects
            // its column boundary instead of assuming everything fits on one line.
            List<String> nameLines = wrapPdfText(skill.getName(), FONT_REGULAR, 9, COL_WIDTHS[0] - CELL_TEXT_PAD * 2);
            List<String> categoryLines = wrapPdfText(rootCategoryName(skill), FONT_REGULAR, 9, COL_WIDTHS[1] - CELL_TEXT_PAD * 2);
            List<String> statusLines = wrapPdfText(skill.getStatus(), FONT_REGULAR, 9, COL_WIDTHS[2] - CELL_TEXT_PAD * 2);

            int maxLines = Math.max(1, Math.max(nameLines.size(), Math.max(categoryLines.size(), statusLines.size())));
            float rowHeight = maxLines * LINE_HEIGHT + ROW_PADDING;

            if (alt) {
                cs.setNonStrokingColor(ROW_ALT);
                cs.addRect(MARGIN, y - rowHeight, tableWidth, rowHeight);
                cs.fill();
            }
            alt = !alt;

            cs.setNonStrokingColor(Color.BLACK);
            float col0X = MARGIN + CELL_TEXT_PAD;
            float col1X = col0X + COL_WIDTHS[0];
            float col2X = col1X + COL_WIDTHS[1];
            float col3X = col2X + COL_WIDTHS[2];
            float col4X = col3X + COL_WIDTHS[3];
            float col5X = col4X + COL_WIDTHS[4];

            drawWrappedCell(cs, nameLines, col0X, y, rowHeight);
            drawWrappedCell(cs, categoryLines, col1X, y, rowHeight);
            drawWrappedCell(cs, statusLines, col2X, y, rowHeight);
            drawWrappedCell(cs, List.of(String.format("%.1f", skill.getCurrentPoints())), col3X, y, rowHeight);
            drawWrappedCell(cs, List.of(String.format("%.1f", skill.getTargetPoints())), col4X, y, rowHeight);

            // Mini completion bar, vertically centered in the (now possibly taller) row.
            float barW = COL_WIDTHS[5] - 34, barH = 8;
            float barX = col5X;
            float barY = y - rowHeight / 2f - barH / 2f;
            cs.setNonStrokingColor(TRACK_GRAY);
            cs.addRect(barX, barY, barW, barH);
            cs.fill();
            cs.setNonStrokingColor(BRAND_LIME);
            cs.addRect(barX, barY, (float) (barW * ratio), barH);
            cs.fill();

            cs.setNonStrokingColor(Color.BLACK);
            cs.beginText();
            cs.setFont(FONT_REGULAR, 8);
            cs.newLineAtOffset(barX + barW + 4, barY + 1);
            cs.showText(Math.round(ratio * 100) + "%");
            cs.endText();

            y -= rowHeight;
        }

        cs.setStrokingColor(Color.LIGHT_GRAY);
        cs.setLineWidth(0.5f);
        cs.addRect(MARGIN, y, tableWidth, startY - y);
        cs.stroke();

        if (skills.isEmpty()) {
            cs.setNonStrokingColor(Color.GRAY);
            cs.beginText();
            cs.setFont(FONT_REGULAR, 10);
            cs.newLineAtOffset(MARGIN + CELL_TEXT_PAD, y - 16);
            cs.showText("No skills tracked yet.");
            cs.endText();
        }
    }

    /** Draws a block of pre-wrapped lines, vertically centered within a row
     *  of the given height. The +2.5pt nudge approximates a 9pt font's
     *  baseline-to-line-top offset closely enough for a report table - true
     *  pixel-perfect centering would need the font's ascent/descent via
     *  PDFont.getFontDescriptor(), which is more precision than a table
     *  cell needs. */
    private static void drawWrappedCell(PDPageContentStream cs, List<String> lines, float cellX,
                                         float rowTopY, float rowHeight) throws IOException {
        float blockHeight = lines.size() * LINE_HEIGHT;
        float blockTopY = rowTopY - (rowHeight - blockHeight) / 2f;
        for (int i = 0; i < lines.size(); i++) {
            float baselineY = blockTopY - (i + 1) * LINE_HEIGHT + 2.5f;
            cs.beginText();
            cs.setFont(FONT_REGULAR, 9);
            cs.newLineAtOffset(cellX, baselineY);
            cs.showText(lines.get(i));
            cs.endText();
        }
    }

    /** Greedy word-wrap measured against ACTUAL glyph widths for the given
     *  font/size (PDFont.getStringWidth returns 1000-units-per-em glyph
     *  space, scaled here by fontSize/1000 to get real point width - the
     *  standard PDFBox text-measurement pattern). This is what makes cells
     *  "dynamic": a long value like "Programming Language" now breaks onto
     *  a second line instead of overflowing into the next column. */
    private static List<String> wrapPdfText(String text, PDType1Font font, float fontSize, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            lines.add("");
            return lines;
        }
        String[] words = text.trim().split("\\s+");
        StringBuilder currentLine = new StringBuilder();

        for (String word : words) {
            String candidate = currentLine.isEmpty() ? word : currentLine + " " + word;
            float width = font.getStringWidth(candidate) / 1000f * fontSize;
            if (currentLine.isEmpty() || width <= maxWidth) {
                currentLine = new StringBuilder(candidate);
            } else {
                lines.add(currentLine.toString());
                currentLine = new StringBuilder(word);
            }
        }
        if (!currentLine.isEmpty()) {
            lines.add(currentLine.toString());
        }
        return lines;
    }

    private static float sum(float[] values) {
        float total = 0;
        for (float v : values) total += v;
        return total;
    }

    /**
     * HIERARCHY REFACTOR NOTE: Skill.getCategory() no longer exists - the
     * "Category" column now walks up to this node's root ancestor instead
     * (a Category node itself has no parent, so it returns its own name).
     * This intentionally shows just the root Category, not a full
     * "Design > Static Design > ..." breadcrumb - it's what the column
     * header actually says, and keeps the 110pt column width meaningful
     * for a deep tree instead of overflowing into word-wrap on every row.
     * <p>
     * REQUIRES the given skill to come from a tree built by
     * DatabaseHelper#getSkillTree() (directly, or via Skill.flatten() of
     * it) - parent/child links are only populated by that tree-building
     * pass, NOT by a plain DatabaseHelper#getAllSkills() row. See the
     * updated handleExportPdf() in DashboardController, which now passes
     * Skill.flatten(db.getSkillTree()) instead of the flat "skills" list
     * for exactly this reason.
     */
    private static String rootCategoryName(Skill skill) {
        Skill current = skill;
        while (current.getParent() != null) {
            current = current.getParent();
        }
        return current.getName();
    }
}
