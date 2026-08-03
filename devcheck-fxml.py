"""Load Dashboard.fxml headlessly and report FXML_OK / FXML_FAIL.

Catches what javac cannot: bad onAction names, fx:id fields that do not exist,
missing <?import?> lines, malformed CSS.

SAFETY: runs with user.home pointed at a throwaway directory, so
DatabaseHelper creates a fresh empty DB there and the real
~/.unitracker/unitracker.db is never opened.
"""
import glob, os, subprocess, sys, tempfile

BS = chr(92)
ROOT = os.path.dirname(os.path.abspath(__file__))
M2 = os.path.join(os.path.expanduser("~"), ".m2", "repository")
CLASSES = os.path.join(ROOT, "target", "classes-check")

if not os.path.isdir(CLASSES):
    sys.exit("run build-check.py first - no compiled classes at " + CLASSES)

WANTED = ("javafx-", "sqlite-jdbc", "flexmark", "pdfbox", "fontbox",
          "commons-logging")
jars = [j for j in glob.glob(os.path.join(M2, "**", "*.jar"), recursive=True)
        if any(w in os.path.basename(j) for w in WANTED)
        and "sources" not in j and "javadoc" not in j]

# The *-win.jar artifacts ARE the real modules on Windows; filtering them out
# is what previously caused "module javafx.web not found".
mods = [j for j in jars if j.endswith("-win.jar")]

sandbox = tempfile.mkdtemp(prefix="unitracker_fxmlcheck_")

cp = os.pathsep.join([CLASSES, os.path.join(ROOT, "src", "main", "resources")]
                     + jars).replace(BS, "/")
mp = os.pathsep.join(mods).replace(BS, "/")

argfile = os.path.join(tempfile.gettempdir(), "unitracker_fxml_args.txt")
with open(argfile, "w", encoding="utf-8") as f:
    f.write('-cp "' + cp + '"\n')
    f.write('--module-path "' + mp + '"\n')
    f.write("--add-modules javafx.controls,javafx.fxml,javafx.web,javafx.swing,javafx.media\n")
    f.write('-Duser.home="' + sandbox.replace(BS, "/") + '"\n')
    # Headless-ish: no real window is shown, but JavaFX still needs a toolkit.
    f.write("com.unitracker.devcheck.FxmlCheck\n")

print("sandbox user.home =", sandbox)
r = subprocess.run(["java", "@" + argfile], capture_output=True, text=True)
sys.stdout.write(r.stdout)
sys.stderr.write(r.stderr)
sys.exit(0 if "FXML_OK" in r.stdout else 1)
