"""Compile every source file with javac, without Maven.

Maven is not on PATH in this environment, so this walks the local ~/.m2
repository for the jars the project needs and hands javac an argfile.

Two Windows-specific traps this works around, both hit for real:
  - javac argfiles treat backslash as an ESCAPE character, so every path
    must be written with forward slashes.
  - the shell's /tmp is not visible to Windows javac, so the argfile goes to
    tempfile.gettempdir().
"""
import os, subprocess, sys, tempfile, glob

BS = chr(92)
ROOT = os.path.dirname(os.path.abspath(__file__))
M2 = os.path.join(os.path.expanduser("~"), ".m2", "repository")
OUT = os.path.join(ROOT, "target", "classes-check")

# Only the artifacts actually imported. Globbing all 418 jars in ~/.m2 blew
# past the Windows command-line length limit.
WANTED = ("javafx-", "sqlite-jdbc", "flexmark", "pdfbox", "fontbox",
          "commons-logging")

jars = [j for j in glob.glob(os.path.join(M2, "**", "*.jar"), recursive=True)
        if any(w in os.path.basename(j) for w in WANTED)
        and "sources" not in j and "javadoc" not in j]

sources = glob.glob(os.path.join(ROOT, "src", "main", "java", "**", "*.java"),
                    recursive=True)

os.makedirs(OUT, exist_ok=True)
argfile = os.path.join(tempfile.gettempdir(), "unitracker_javac_args.txt")
with open(argfile, "w", encoding="utf-8") as f:
    f.write("-cp " + '"' + os.pathsep.join(j.replace(BS, "/") for j in jars) + '"\n')
    f.write("-d " + '"' + OUT.replace(BS, "/") + '"\n')
    f.write("--release 25\n")
    f.write("-Xlint:none\n")
    for s in sources:
        f.write('"' + s.replace(BS, "/") + '"\n')

print(f"compiling {len(sources)} sources against {len(jars)} jars")
r = subprocess.run(["javac", "@" + argfile], capture_output=True, text=True)
sys.stdout.write(r.stdout)
sys.stderr.write(r.stderr)
print("COMPILE_OK" if r.returncode == 0 else f"COMPILE_FAIL rc={r.returncode}")
sys.exit(r.returncode)
