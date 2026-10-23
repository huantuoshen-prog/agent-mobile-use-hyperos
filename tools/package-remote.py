"""Build the portable stdio MCP client using the shared gateway implementation."""
import json
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[1]
cfg = json.loads((root / 'release-config.json').read_text())
dest = root / 'release' / ('agent-mobile-use-remote-' + cfg['remoteVersion'] + '.zip')
dest.parent.mkdir(exist_ok=True)
package = {
    'name': 'agent-mobile-use-remote', 'version': cfg['remoteVersion'], 'type': 'module',
    'private': True, 'license': 'MIT', 'engines': {'node': '>=22'},
    'bin': {'agent-mobile-use': 'lib/remote-cli.cjs'},
    'repository': 'https://github.com/' + cfg['repository'],
    'requiredModuleCandidate': cfg['moduleVersion'],
}
with zipfile.ZipFile(dest, 'w', zipfile.ZIP_DEFLATED) as z:
    z.writestr('package.json', json.dumps(package, indent=2) + '\n')
    z.write(root / 'LICENSE', 'LICENSE')
    z.write(root / 'docs/remote-access.md', 'README.md')
    for name in ('server.cjs', 'gateway.cjs', 'remote.cjs', 'remote-cli.cjs', 'questions.js', 'version.cjs'):
        z.write(root / 'dsha-plugin/lib' / name, 'lib/' + name)
print('Packaged', dest.name)
