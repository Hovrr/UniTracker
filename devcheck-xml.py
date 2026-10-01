"""Guard every XML file in the project against the two mistakes that are
invisible to javac and only surface as a parse error at runtime.

WHY THIS EXISTS
---------------
The `--` mistake is now the third time this has bitten in one project:

  1. FloatingTimer.fxml  - a run of `-----` rules inside a comment.
  2. SettingsDialog.fxml - the same, twice.
  3. pom.xml             - the literal `--add-modules` flag name.

All three are the same mistake: **the XML 1.0 specification forbids a double
hyphen anywhere inside a comment.** The grammar is unambiguous about it, but
every editor, IDE and language server happily accepts it, javac never sees the
file, and Python's `re` and PowerShell's `[xml]` cast are both lenient enough
to pass. Maven's parser is the one that actually enforces it, which means the
failure surfaces as "Non-parseable POM" at build time - long after the edit,
in a different tool, with a message that does not name the file's real problem
in a way anyone connects to the line that caused it.

So: check it here, cheaply, before anything else runs.

WHAT ELSE IS GUARDED
--------------------
The second rule catches a related, subtler family: an `<?import?>` that is
missing, which makes FXMLLoader report the ROOT TYPE as "not a valid type" -
a genuinely misleading error message, since the root type is fine but unimported.
That cost real debugging time in SettingsDialog.fxml, so it is checked here too.

USAGE
-----
  python devcheck-xml.py
Exit code 0 = every file is valid. Non-zero = do not commit.
"""

import glob
import os
import re
import sys
import xml.dom.minidom

ROOT = os.path.dirname(os.path.abspath(__file__))


def xml_files():
    """Every XML file the project can hand to a parser."""
    found = ["pom.xml"]
    found += glob.glob(os.path.join(ROOT, "src", "**", "*.fxml"), recursive=True)
    found += glob.glob(os.path.join(ROOT, "src", "**", "*.xml"), recursive=True)
    # pom.xml leads and must exist; drop any accidental double-listing.
    seen, out = set(), []
    for p in found:
        real = os.path.abspath(p)
        if real not in seen and os.path.isfile(real):
            seen.add(real)
            out.append(real)
    return out


def line_of(text, offset):
    return text[:offset].count("\n") + 1


def check_no_double_hyphen(path, text):
    """The rule that has bitten three times.

    Scans only COMMENT BODIES, so a legitimate double hyphen in element text or
    an attribute value (a date range, a SQL fragment) is not flagged. Only
    comments are restricted.
    """
    problems = []
    for m in re.finditer(r"<!--(.*?)-->", text, flags=re.S):
        body = m.group(1)
        for hit in re.finditer(r"--", body):
            ctx = body[max(0, hit.start() - 30): hit.start() + 30].replace("\n", " ")
            problems.append((line_of(text, m.start() + hit.start() + 4), ctx))
    return problems


def check_fxml_imports(path, text):
    """An FXML file using a type with no <?import?> loads with the deeply
    misleading "X is not a valid type" instead of "you forgot an import".

    Compares SIMPLE names, because the imports are written fully qualified
    (`javafx.scene.layout.VBox`) while the tags are not (`<VBox>`). Comparing
    the two verbatim flags every single tag in the document, which is how this
    check reported eight false positives on its first run.
    """
    if not path.endswith(".fxml"):
        return []

    problems = []
    imported = {fqn.split(".")[-1] for fqn in re.findall(r"<\?import\s+([\w.]+)\?", text)}

    # Every element name used as a tag, comments and processing instructions
    # removed first so a tag mentioned in prose is not counted.
    body = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    body = re.sub(r"<\?.*?\?>", "", body, flags=re.S)
    tags = set(re.findall(r"<\s*([A-Z][\w.]*)", body))

    for tag in sorted(tags):
        # A dotted tag whose LAST segment starts lowercase is an ATTACHED
        # PROPERTY reference - <StackPane.margin>, <HBox.hgrow>,
        # <GridPane.rowIndex> - not a type. FXML resolves those against the
        # class named in the first segment, so they never need an import and
        # must not be flagged. Java type names start uppercase; Java
        # identifiers do not, which makes the case the discriminator.
        segments = tag.split(".")
        if len(segments) > 1 and segments[-1][:1].islower():
            continue
        simple = segments[-1]
        if simple not in imported:
            problems.append((
                0,
                "uses <%s> but has no <?import ...%s?> - FXMLLoader reports "
                "\"%s is not a valid type\", which does not name the real "
                "problem" % (tag, simple, simple)))
    return problems


def main():
    files = xml_files()
    if not files:
        print("no XML files found - is devcheck-xml.py in the right place?")
        return 1

    failures = 0
    for path in files:
        rel = os.path.relpath(path, ROOT)
        with open(path, "r", encoding="utf-8") as handle:
            text = handle.read()

        problems = check_no_double_hyphen(path, text)
        problems += check_fxml_imports(path, text)

        # Finally, parse for real. A strict parser is the authority; the checks
        # above exist to produce a message that NAMES THE LINE.
        try:
            xml.dom.minidom.parse(path)
        except Exception as exc:
            print("XML_PARSE_FAIL %s" % rel)
            print("               %s" % exc)
            failures += 1
            continue

        if problems:
            failures += 1
            print("XML_FAIL     %s" % rel)
            for line, ctx in problems:
                where = "line %d" % line if line else "document"
                print("               %s: ...%s..." % (where, ctx))
        else:
            print("XML_OK       %s" % rel)

    print()
    if failures:
        print("RESULT: %d file(s) FAILED." % failures)
        return 1
    print("RESULT: all %d XML file(s) valid." % len(files))
    return 0


if __name__ == "__main__":
    sys.exit(main())
