"""Build fireball-overlay.apk (overlay planner for real attacks). Model: layout-recognition/mobile (export_mobile.py).

Paths default to the original macOS workspace; each can be overridden by an environment variable:
FB_SDK (android-sdk with build-tools/36.0.0 and platforms/android-36), FB_TOOLS, FB_JAR, FB_JAVA (folder with javac),
FB_ORT (onnxruntime-android aar: classes.jar + jni/arm64-v8a), FB_MODEL (folder with model.onnx + recognizer.json),
FB_BUILD (scratch folder), FB_KEY (keystore), FB_KEY_PASS (its password).
"""
import os, shutil, subprocess, zipfile
from pathlib import Path
P = Path(__file__).resolve().parent; ROOT = P.parent; WS = ROOT.parents[1] / 'work'
def env(name, default): return Path(os.environ[name]) if name in os.environ else default
SDK = env('FB_SDK', WS / 'android-sdk'); TOOLS = env('FB_TOOLS', SDK / 'build-tools/36.0.0'); JAR = env('FB_JAR', SDK / 'platforms/android-36/android.jar')
JAVA = env('FB_JAVA', Path('/opt/homebrew/opt/openjdk/bin')); ORT = env('FB_ORT', WS / 'onnxruntime-android/aar'); B = env('FB_BUILD', WS / 'fireball-overlay-build')
MODEL = env('FB_MODEL', ROOT / 'layout-recognition/mobile')
KEY = env('FB_KEY', WS / 'android-offline-build/lab.keystore'); KEY_PASS = os.environ.get('FB_KEY_PASS', 'offline-lab')
def run(a): subprocess.run([str(x) for x in a], check=True, env={**os.environ, 'JAVA_HOME': str(JAVA.parent), 'PATH': str(JAVA) + os.pathsep + os.environ['PATH']})
shutil.rmtree(B, ignore_errors=True); (B / 'classes').mkdir(parents=True); (B / 'dex').mkdir(); (B / 'assets').mkdir()
for f in ('model.onnx', 'recognizer.json'): shutil.copyfile(MODEL / f, B / 'assets' / f)
run([TOOLS / 'aapt2', 'link', '-o', B / 'base.apk', '--manifest', P / 'AndroidManifest.xml', '-I', JAR, '-A', B / 'assets'])
run([JAVA / 'javac', '-source', '8', '-target', '8', '-encoding', 'UTF-8', '-Xlint:-options', '-bootclasspath', f'{JAR}{os.pathsep}{TOOLS / "core-lambda-stubs.jar"}',
     '-cp', ORT / 'classes.jar', '-d', B / 'classes', *(P / 'src').rglob('*.java')])
run([TOOLS / 'd8', '--lib', JAR, '--min-api', '24', '--output', B / 'dex', *(B / 'classes').rglob('*.class'), ORT / 'classes.jar'])
shutil.copyfile(B / 'base.apk', B / 'u.apk')
with zipfile.ZipFile(B / 'u.apk', 'a') as z:
    for f in sorted((B / 'dex').glob('*.dex')): z.write(f, f.name)
    for n in ('libonnxruntime.so', 'libonnxruntime4j_jni.so'): z.write(ORT / 'jni/arm64-v8a' / n, 'lib/arm64-v8a/' + n)
run([TOOLS / 'zipalign', '-f', '-p', '4', B / 'u.apk', B / 'a.apk'])
run([TOOLS / 'apksigner', 'sign', '--ks', KEY, '--ks-pass', 'pass:' + KEY_PASS, '--out', P / 'fireball-overlay.apk', B / 'a.apk'])
run([TOOLS / 'apksigner', 'verify', P / 'fireball-overlay.apk'])
print(P / 'fireball-overlay.apk', (P / 'fireball-overlay.apk').stat().st_size)
