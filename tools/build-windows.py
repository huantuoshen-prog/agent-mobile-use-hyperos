"""Build all shipped binaries with explicit toolchain paths and a persistent key.

KEYSTORE_PASSWORD is read by apksigner from the process environment. Keys never
belong in this repository or its build output.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

p = argparse.ArgumentParser()
for arg in ('jdk', 'build-tools', 'android-jar', 'go', 'keystore'):
    p.add_argument('--' + arg, required=True, type=Path)
args = p.parse_args()
root = Path(__file__).resolve().parents[1]
cfg = json.loads((root / 'release-config.json').read_text())
subprocess.run([__import__('sys').executable, str(root / 'tools/sync-version.py')], check=True)
build = root / '.build-windows'
if build.exists():
    assert build.resolve() == root.resolve() / '.build-windows'
    shutil.rmtree(build)
for name in ('gen', 'stubs', 'classes', 'dex', 'tools', 'apk'):
    (build / name).mkdir(parents=True)
if not args.keystore.is_file() or not os.environ.get('KEYSTORE_PASSWORD'):
    raise SystemExit('Persistent keystore and KEYSTORE_PASSWORD are required')

def run(*cmd, cwd=None, env=None):
    subprocess.run([str(v) for v in cmd], check=True, cwd=cwd or root, env=env)

java, javac = args.jdk / 'bin/java.exe', args.jdk / 'bin/javac.exe'
bt = args.build_tools
jar = args.android_jar
hook = root / 'agent-hook-apk'
run(bt / 'aapt.exe', 'package', '-f', '-m', '-0', 'arsc', '-S', hook / 'res', '-J', build / 'gen',
    '-M', hook / 'AndroidManifest.xml', '-I', jar, '-F', build / 'apk/unaligned.apk')

def compile_java(sources, out, classpath):
    argfile = build / (out.name + '-sources.txt')
    argfile.write_text('\n'.join('"' + str(v).replace('\\', '/') + '"' for v in sources), encoding='utf-8')
    run(javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:-options',
        '-cp', classpath, '-d', out, '@' + str(argfile))

compile_java(list((hook / 'stubs').rglob('*.java')), build / 'stubs', str(jar))
compile_java(list((hook / 'src').rglob('*.java')) + list((build / 'gen').rglob('*.java')),
             build / 'classes', str(jar) + os.pathsep + str(build / 'stubs'))

def dex(classes, destination):
    dest = build / 'dex'
    for old in dest.glob('*.dex'): old.unlink()
    program = build / 'dex-program.jar'
    with zipfile.ZipFile(program, 'w') as z:
        for f in classes:
            relative = f.as_posix().split('/com/', 1)[1]
            z.write(f, 'com/' + relative)
    run(java, '-cp', bt / 'lib/d8.jar', 'com.android.tools.r8.D8', '--release', '--min-api', '26',
        '--lib', jar, '--output', dest, program)
    shutil.copyfile(dest / 'classes.dex', destination)

dex(list((build / 'classes').rglob('*.class')), build / 'apk/classes.dex')
with zipfile.ZipFile(build / 'apk/unaligned.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(build / 'apk/classes.dex', 'classes.dex')
    for f in (hook / 'assets').rglob('*'):
        if f.is_file(): z.write(f, 'assets/' + str(f.relative_to(hook / 'assets')).replace('\\', '/'))
run(bt / 'zipalign.exe', '-p', '-f', '4', build / 'apk/unaligned.apk', build / 'apk/aligned.apk')
apk = build / 'agent_hook.apk'
run(java, '-jar', bt / 'lib/apksigner.jar', 'sign', '--ks', args.keystore, '--ks-pass', 'env:KEYSTORE_PASSWORD',
    '--key-pass', 'env:KEYSTORE_PASSWORD', '--out', apk, build / 'apk/aligned.apk')
run(java, '-jar', bt / 'lib/apksigner.jar', 'verify', apk)

native = root / 'vd-tool-java/src/com/agent'
compile_java(list(native.glob('*.java')), build / 'tools', str(jar))
for main, output in [('ToolMain', 'agent_tools.dex'), ('DaemonMain', 'agent_vd.dex')]:
    dex(list((build / 'tools/com/agent').glob(main + '*.class')), build / output)
goenv = dict(os.environ, GOOS='linux', GOARCH='arm64', CGO_ENABLED='0')
run(args.go, 'build', '-trimpath', '-o', build / 'vd_server', '.', cwd=root / 'vd-server-go', env=goenv)
module = root / 'ksu-module'
dest = root / ('release/agent-mobile-use-ksu-v' + cfg['moduleVersion'] + '.zip')
dest.parent.mkdir(exist_ok=True)
replacement = {'apk/agent_hook.apk': apk, 'bin/vd_server': build / 'vd_server',
               'bin/agent_tools.dex': build / 'agent_tools.dex', 'bin/agent_vd.dex': build / 'agent_vd.dex'}
with zipfile.ZipFile(dest, 'w', zipfile.ZIP_DEFLATED) as z:
    z.writestr('binaries.sha256', ''.join(hashlib.sha256(f.read_bytes()).hexdigest() + '  ' + name + '\n' for name, f in replacement.items()))
    for f in module.rglob('*'):
        if not f.is_file() or f.name in ('pack.sh', 'binaries.sha256'): continue
        rel = f.relative_to(module).as_posix()
        data = replacement.get(rel, f).read_bytes()
        if f.suffix in ('.sh', '.prop') or rel == 'system/bin/vd': data = data.replace(b'\r\n', b'\n')
        z.writestr(rel, data)
manifest = {rel: hashlib.sha256(f.read_bytes()).hexdigest() for rel, f in replacement.items()}
manifest['module_zip'] = hashlib.sha256(dest.read_bytes()).hexdigest()
(build / 'SHA256SUMS.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
print('Build complete:', dest)
