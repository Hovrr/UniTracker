"""Run the headless devcheck harnesses against a throwaway user.home.

WHY A DEDICATE RUNNER: DatabaseHelper derives its database path from
`user.home` in a static initialiser, so every check that touches the real
DatabaseHelper has to be launched with -Duser.home pointed somewhere
disposable. Doing that by hand each time is exactly the kind of step that
gets skipped, and a check silently pointed at the real profile would be
worse than no check at all.

Each harness runs in its own fresh sandbox directory, so they cannot
interfere with one another, and none of them can ever open the real
~/.unitracker/unitracker.db.

XML PREFLIGHT: the run starts with devcheck-xml.py, because a malformed
XML file is the one class of error that makes EVERYTHING else fail in a
confusing way - an unparseable pom.xml stops the build outright, and a bad
FXML file stops the harness that is supposed to be checking it. Catching
it first means a real failure is never buried under a cascade of
consequence. It is also the only check that needs no JDK, so a broken
FXML is still reported even if compilation is not yet working.

Usage:  python run-tests.py [harness-class-simple-name ...]
"""
import glob
import os
import subprocess
import sys
import tempfile

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

cp = os.pathsep.join([CLASSES, os.path.join(ROOT, "src", "main", "resources")]
                     + jars).replace(BS, "/")
mp = os.pathsep.join(mods).replace(BS, "/")

# name -> sandbox directory suffix. The suffix is also asserted on by
# DerivedPointsCheck, so it is not decorative.
HARNESSES = {
    "SqlLogicCheck": "utcheck_streaks",
    "DerivedPointsCheck": "utcheck",
    "TimerStateCheck": "utcheck_timer",
}


def run(name):
    simple = name.split(".")[-1]
    if simple not in HARNESSES:
        sys.exit("unknown harness " + simple + " - known: " + ", ".join(HARNESSES))
    sandbox = tempfile.mkdtemp(prefix=HARNESSES[simple] + "_")
    argfile = os.path.join(tempfile.gettempdir(), "ut_run_" + simple + ".txt")
    with open(argfile, "w", encoding="utf-8") as f:
        f.write('-cp "' + cp + '"\n')
        f.write('--module-path "' + mp + '"\n')
        f.write("--add-modules javafx.controls,javafx.fxml,javafx.web,javafx.swing,javafx.media\n")
        f.write('-Duser.home="' + sandbox.replace(BS, "/") + '"\n')
        f.write("com.unitracker.devcheck." + simple + "\n")

    print("=" * 70)
    print("RUN " + simple + "   (sandbox: " + sandbox + ")")
    print("=" * 70)
    # -ea is mandatory: every harness asserts the flag is on and refuses to run
    # without it, so a "pass" can never be a silently skipped assertion.
    r = subprocess.run(["java", "-ea", "@" + argfile], capture_output=True, text=True)
    sys.stdout.write(r.stdout)
    sys.stderr.write(r.stderr)
    ok = r.returncode == 0
    print("--> " + simple + ": " + ("PASS" if ok else "FAIL (rc=%d)" % r.returncode))
    return ok


targets = sys.argv[1:] or list(HARNESSES)

# ---- Preflight: XML validity -------------------------------------------
# Runs before anything else, and is reported separately, because it needs no
# JDK and no compiled classes. A broken FXML would otherwise be reported as a
# mysterious harness failure, and a broken pom.xml as a mysterious build
# failure.
print("=" * 70)
print("PREFLIGHT  devcheck-xml.py")
print("=" * 70)
xml_proc = subprocess.run([sys.executable, os.path.join(ROOT, "devcheck-xml.py")],
                          capture_output=True, text=True)
sys.stdout.write(xml_proc.stdout)
if xml_proc.stderr:
    sys.stderr.write(xml_proc.stderr)
if xml_proc.returncode != 0:
    print()
    print("RESULT: ABORTED - the XML is invalid, so every result below would be")
    print("        meaningless. Fix the file named above and re-run.")
    sys.exit(1)
print()

results = [(t, run(t)) for t in targets]

print("=" * 70)
failed = [t for t, ok in results if not ok]
if failed:
    print("RESULT: FAILED -> " + ", ".join(failed))
    sys.exit(1)
print("RESULT: all %d harness(es) passed." % len(results))
