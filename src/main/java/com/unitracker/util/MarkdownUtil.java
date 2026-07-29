package com.unitracker.util;

import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;

import java.util.Arrays;

/**
 * Converts sticky-note Markdown into styled HTML for display inside a
 * JavaFX WebView (JavaFX has no native Markdown or rich-text renderer, so
 * WebView + a tiny inline stylesheet is the standard approach).
 *
 * Includes the GFM task-list extension so "- [ ] / - [x]" lines render as
 * real checkboxes, satisfying the PRD's "to-do lists ... supporting basic
 * Markdown" requirement.
 */
public final class MarkdownUtil {

    private static final Parser PARSER;
    private static final HtmlRenderer RENDERER;

    static {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, Arrays.asList(
                TaskListExtension.create(),
                StrikethroughExtension.create()
        ));
        PARSER = Parser.builder(options).build();
        RENDERER = HtmlRenderer.builder(options).build();
    }

    private MarkdownUtil() {
        // Static utility class - no instances.
    }

    /** Raw HTML fragment (no surrounding &lt;html&gt;/&lt;body&gt;) rendered from markdown. */
    public static String toHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) return "";
        Node document = PARSER.parse(markdown);
        return RENDERER.render(document);
    }

    /**
     * Wraps rendered markdown in a full HTML document styled to match the
     * app's dark glassmorphism theme, ready for {@code WebEngine.loadContent(...)}.
     *
     * NOTE: JavaFX's WebView does not reliably support a fully transparent
     * page background across versions, so instead of fighting that we give
     * the note body a solid dark tone (#16263A) that blends closely with
     * the surrounding glass panel rather than flashing white.
     */
    public static String toStyledDocument(String markdown) {
        String body = toHtml(markdown);
        return """
                <html>
                <head>
                <style>
                    body {
                        margin: 4px 6px;
                        background-color: #16263A;
                        color: #E7ECF3;
                        font-family: 'Poppins', 'Segoe UI', sans-serif;
                        font-size: 13px;
                        line-height: 1.45;
                    }
                    a { color: #A8EB12; }
                    code {
                        background: rgba(255,255,255,0.10);
                        padding: 1px 4px;
                        border-radius: 4px;
                        font-family: monospace;
                    }
                    input[type=checkbox] {
                        accent-color: #A8EB12;
                        margin-right: 6px;
                        vertical-align: middle;
                        position: relative;
                        top: -1px;
                        cursor: pointer;
                    }
                    /* "task-list-item" is the exact class flexmark-java's GFM
                       TaskListExtension emits on <li> - confirmed against its
                       documented output, safer than a :has() selector whose
                       support in JavaFX's bundled WebKit is unverified. */
                    li.task-list-item { list-style: none; margin-left: -18px; }
                    ul { padding-left: 18px; margin: 4px 0; }
                    p { margin: 4px 0; }
                    strong { color: #FFFFFF; }
                </style>
                </head>
                <body>
                """ + body + """
                </body>
                </html>
                """;
    }
}
