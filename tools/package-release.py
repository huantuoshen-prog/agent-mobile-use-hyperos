"""Package a matching plugin and release index after building the signed module.

The update index is staged under .build-windows; publish it only after device
acceptance and after every referenced Release asset has been uploaded.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

p=argparse.ArgumentParser();p.add_argument('--apksigner',required=True,type=Path);p.add_argument('--java',required=True,type=Path)
p.add_argument('--apk',type=Path);p.add_argument('--staging-dir',type=Path);args=p.parse_args()
root=Path(__file__).resolve().parents[1];cfg=json.loads((root/'release-config.json').read_text());release=root/'release'
module=release/('agent-mobile-use-ksu-v'+cfg['moduleVersion']+'.zip');plugin=release/('dsh-mobile-use-'+cfg['pluginVersion']+'.zip')
apk=args.apk or root/'.build-windows/agent_hook.apk'
staging=args.staging_dir or root/'.build-windows'
staging.mkdir(parents=True,exist_ok=True)
package=json.loads((root/'dsha-plugin/package.json').read_text())
assert package['version']==cfg['pluginVersion'],'Plugin version does not match release configuration'
output=subprocess.check_output([str(args.java),'-jar',str(args.apksigner),'verify','--print-certs',str(apk)],text=True)
cert=re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)',output).group(1).lower()
expected=(root/'release-certificate.sha256').read_text().strip();assert cert==expected,'Release certificate changed'
with zipfile.ZipFile(module) as z:
    assert z.read('apk/agent_hook.apk')==apk.read_bytes()
    assert 'version='+cfg['moduleVersion'] in z.read('module.prop').decode()
    for line in z.read('binaries.sha256').decode().splitlines():
        digest,name=line.split();assert hashlib.sha256(z.read(name)).hexdigest()==digest,name
    assert not any(name.endswith(('.keystore','.jks','.p12')) for name in z.namelist())
with zipfile.ZipFile(plugin,'w',zipfile.ZIP_DEFLATED) as z:
    for name in ('package.json','cordis.patch.yml','README.md'):z.write(root/'dsha-plugin'/name,name)
    z.write(root/'LICENSE','LICENSE')
    for f in (root/'dsha-plugin/lib').rglob('*'):
        if f.is_file():z.write(f,f.relative_to(root/'dsha-plugin').as_posix())
prefix='https://github.com/'+cfg['repository']+'/releases/download/v'+cfg['moduleVersion']+'/'
def asset(f,version):return {'file':f.name,'version':version,'sha256':hashlib.sha256(f.read_bytes()).hexdigest(),'bytes':f.stat().st_size,'url':prefix+f.name}
manifest={'schema_version':1,'protocol_version':cfg['protocolVersion'],'apk_certificate_sha256':cert,'module':asset(module,cfg['moduleVersion']),'plugin':asset(plugin,cfg['pluginVersion']),'dsh_version':cfg['dshVersion']}
dest=release/('live-agent-'+cfg['moduleVersion'].removesuffix('-alpha')+'-manifest.json');dest.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
sums=release/('SHA256SUMS-'+cfg['moduleVersion'].removesuffix('-alpha')+'.txt');sums.write_text(''.join(hashlib.sha256(f.read_bytes()).hexdigest()+'  '+f.name+'\n' for f in [module,plugin,dest]),encoding='utf-8')
update={'version':cfg['moduleVersion'],'versionCode':cfg['moduleVersionCode'],'zipUrl':prefix+module.name,'changelog':'https://raw.githubusercontent.com/'+cfg['repository']+'/main/docs/releases/v'+cfg['moduleVersion']+'.md',**manifest}
(staging/'alpha.json').write_text(json.dumps(update,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('Packaged',module.name,plugin.name,'certificate='+cert)
