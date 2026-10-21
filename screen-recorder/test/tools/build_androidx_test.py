"""Builds the few androidx.test classes Robolectric needs, from the public
android/android-test sources (Google Maven is not reachable everywhere).
Usage: python3 build_androidx_test.py <workdir> <android.jar> <need.txt>
Leaves <workdir>/androidx-test.jar."""
import os, re, subprocess, glob, sys, zipfile
WORK, ANDROID_JAR, NEED = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(WORK, exist_ok=True)
os.chdir(WORK)
import urllib.request
BASE="https://raw.githubusercontent.com/android/android-test/main/"
ROOTS=["runner/monitor/java/","espresso/idling_resource/java/","core/java/","runner/android_junit_runner/java/","annotation/java/"]
def fetch(cls):
    path=cls.replace('.','/')+'.java'
    dst='src/'+path
    if os.path.exists(dst): return False
    for r in ROOTS:
        try:
            data=urllib.request.urlopen(BASE+r+path, timeout=20).read()
            os.makedirs(os.path.dirname(dst),exist_ok=True); open(dst,'wb').write(data); return True
        except Exception: pass
    return False
def stub(fq):
    pkg,_,name=fq.rpartition('.')
    dst='src/'+fq.replace('.','/')+'.java'
    if os.path.exists(dst): return
    os.makedirs(os.path.dirname(dst),exist_ok=True)
    open(dst,'w').write(f"package {pkg};\nimport java.lang.annotation.*;\n@Retention(RetentionPolicy.CLASS) public @interface {name} {{ String[] value() default {{}}; String[] of() default {{}}; int otherwise() default 0; Scope[] scope() default {{}}; enum Scope {{ LIBRARY, LIBRARY_GROUP, LIBRARY_GROUP_PREFIX, GROUP_ID, TESTS, SUBCLASSES }} }}\n")
for line in open(NEED):
    c=line.strip().replace('/','.')
    if c: fetch(c)
os.makedirs('src/androidx/annotation', exist_ok=True)
open('src/androidx/annotation/RestrictTo.java','w').write('''package androidx.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS) public @interface RestrictTo { Scope[] value(); enum Scope { LIBRARY, LIBRARY_GROUP, LIBRARY_GROUP_PREFIX, GROUP_ID, TESTS, SUBCLASSES } }
''')
ok=False
for it in range(15):
    files=glob.glob('src/**/*.java',recursive=True)
    for f in files:
        for m in re.findall(r'^import (?:static )?([\w.]+);', open(f).read(), re.M):
            if m.startswith('androidx.test.'):
                parts=m.split('.')
                # keep up to the first class name (drops static members / inner classes)
                for i,p in enumerate(parts):
                    if p[:1].isupper(): fetch('.'.join(parts[:i+1])); break
            elif m.startswith('androidx.annotation.') or m.startswith('com.google.errorprone.annotations.') or m.startswith('javax.annotation.'):
                if m.split('.')[-1][:1].isupper(): stub(m)
    r=subprocess.run(['javac','-nowarn','-proc:none','-encoding','UTF-8','-d','out','-cp',ANDROID_JAR]+glob.glob('src/**/*.java',recursive=True),capture_output=True,text=True)
    errs=r.stderr
    if r.returncode==0: ok=True; break
    progress=False
    for fpath,sym in re.findall(r'^(src/\S+?\.java):\d+: error: cannot find symbol\n.*\n.*\n\s+symbol:\s+class (\w+)', errs, re.M):
        pkg=os.path.dirname(fpath)[4:].replace('/','.')
        if fetch(pkg+'.'+sym): progress=True
    print(it, errs.count('error:'), 'errors')
    if not progress and it>2:
        print(errs[:3000]); break

if not ok: sys.exit("androidx.test build failed")
with zipfile.ZipFile('androidx-test.jar','w') as z:
    for f in glob.glob('out/**/*.class', recursive=True):
        z.write(f, os.path.relpath(f,'out'))
print("built", os.path.abspath('androidx-test.jar'))
