"""Build fireball-overlay.apk (overlay planner for real attacks). Model: layout-recognition/mobile (export_mobile.py)."""
import os, shutil, subprocess, zipfile
from pathlib import Path
P = Path(__file__).resolve().parent; ROOT = P.parent; WS = ROOT.parents[1] / 'work'
SDK = WS / 'android-sdk'; TOOLS = SDK / 'build-tools/36.0.0'; JAR = SDK / 'platforms/android-36/android.jar'
JAVA = Path('/opt/homebrew/opt/openjdk/bin'); ORT = WS / 'onnxruntime-android/aar'; B = WS / 'fireball-overlay-build'
KEY = WS / 'android-offline-build/lab.keystore'
def run(a): subprocess.run([str(x) for x in a], check=True, env={**os.environ, 'JAVA_HOME': str(JAVA.parent)})
shutil.rmtree(B, ignore_errors=True); (B / 'classes').mkdir(parents=True); (B / 'dex').mkdir(); (B / 'assets').mkdir()
for f in ('model.onnx', 'recognizer.json'): shutil.copyfile(ROOT / 'layout-recognition/mobile' / f, B / 'assets' / f)
run([TOOLS / 'aapt2', 'link', '-o', B / 'base.apk', '--manifest', P / 'AndroidManifest.xml', '-I', JAR, '-A', B / 'assets'])
run([JAVA / 'javac', '-source', '8', '-target', '8', '-encoding', 'UTF-8', '-bootclasspath', f'{JAR}{os.pathsep}{TOOLS / "core-lambda-stubs.jar"}',
     '-cp', ORT / 'classes.jar', '-d', B / 'classes', *(P / 'src').rglob('*.java')])
run([TOOLS / 'd8', '--lib', JAR, '--min-api', '24', '--output', B / 'dex', *(B / 'classes').rglob('*.class'), ORT / 'classes.jar'])
shutil.copyfile(B / 'base.apk', B / 'u.apk')
with zipfile.ZipFile(B / 'u.apk', 'a') as z:
    for f in sorted((B / 'dex').glob('*.dex')): z.write(f, f.name)
    for n in ('libonnxruntime.so', 'libonnxruntime4j_jni.so'): z.write(ORT / 'jni/arm64-v8a' / n, 'lib/arm64-v8a/' + n)
run([TOOLS / 'zipalign', '-f', '-p', '4', B / 'u.apk', B / 'a.apk'])
run([TOOLS / 'apksigner', 'sign', '--ks', KEY, '--ks-pass', 'pass:offline-lab', '--out', P / 'fireball-overlay.apk', B / 'a.apk'])
run([TOOLS / 'apksigner', 'verify', P / 'fireball-overlay.apk'])
print(P / 'fireball-overlay.apk', (P / 'fireball-overlay.apk').stat().st_size)
