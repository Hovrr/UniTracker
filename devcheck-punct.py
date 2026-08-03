"""Report non-ASCII punctuation that would reach the UI as "AI slop" typography.

Em/en dashes, curly quotes and ellipsis characters in Java string literals and
FXML text attributes all render verbatim in the app. Comments are reported
separately since they are never user-visible.

Also catches the same characters written as Java unicode escapes (e.g. "\\u2014"
in source, which javac compiles to an em dash) - they reach the UI identically
but never match the literal-character scan. The middle dot is included for the
same reason; the level badge once used it.
"""
import io, glob, os, sys

PATTERNS = {
    u'—': 'em dash',
    u'–': 'en dash',
    u'‘': 'left single quote',
    u'’': 'right single quote',
    u'“': 'left double quote',
    u'”': 'right double quote',
    u'…': 'ellipsis',
}

# Same characters as Java unicode escapes. Split into (needle, label) pairs so
# a 6-char sequence is not falsely flagged for the 5-char substring of another.
ESCAPES = [
    (u'\\u2014', 'em dash escape'),
    (u'\\u2013', 'en dash escape'),
    (u'\\u2018', 'left single quote escape'),
    (u'\\u2019', 'right single quote escape'),
    (u'\\u201C', 'left double quote escape'),
    (u'\\u201D', 'right double quote escape'),
    (u'\\u2026', 'ellipsis escape'),
    (u'\\u00B7', 'middle dot escape'),
]

files = (glob.glob('src/main/java/**/*.java', recursive=True)
         + glob.glob('src/main/resources/**/*.fxml', recursive=True))

hits = 0
for path in files:
    name = os.path.basename(path)
    for lineno, line in enumerate(io.open(path, encoding='utf-8'), 1):
        stripped = line.lstrip()
        # A comment line cannot reach the UI.
        is_comment = (stripped.startswith('//') or stripped.startswith('*')
                      or stripped.startswith('/*') or stripped.startswith('<!--'))
        for ch, label in PATTERNS.items():
            if ch in line:
                kind = 'comment' if is_comment else 'UI TEXT'
                if kind == 'UI TEXT':
                    hits += 1
                print('%-8s %s:%d [%s] %s' % (kind, name, lineno, label, line.strip()[:88]))
        for needle, label in ESCAPES:
            if needle in line:
                kind = 'comment' if is_comment else 'UI TEXT'
                if kind == 'UI TEXT':
                    hits += 1
                print('%-8s %s:%d [%s] %s' % (kind, name, lineno, label, line.strip()[:88]))

print('\n%d occurrence(s) in user-visible text.' % hits)
sys.exit(1 if hits else 0)
