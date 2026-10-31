"""Generate component version declarations from release-config.json."""
import json
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
cfg = json.loads((root / 'release-config.json').read_text())
def replace(path, pattern, value):
    p = root / path
    p.write_text(re.sub(pattern, value, p.read_text(encoding='utf-8')), encoding='utf-8', newline='\n')
replace('ksu-module/module.prop', r'(?m)^version=.*$', 'version=' + cfg['moduleVersion'])
replace('ksu-module/module.prop', r'(?m)^versionCode=.*$', 'versionCode=' + str(cfg['moduleVersionCode']))
replace('agent-hook-apk/AndroidManifest.xml', r'android:versionName="[^"]+"', 'android:versionName="' + cfg['moduleVersion'] + '"')
replace('agent-hook-apk/AndroidManifest.xml', r'android:versionCode="[^"]+"', 'android:versionCode="' + str(cfg['moduleVersionCode']) + '"')
p=root/'dsha-plugin/package.json';package=json.loads(p.read_text());package['version']=cfg['pluginVersion'];package.setdefault('engines',{})['dsh']=cfg.get('dshCompatibility',cfg['dshVersion']);package.setdefault('peerDependencies',{})['@deepseek-ai/dsh-mcp-client']=cfg.get('dshCompatibility',cfg['dshVersion']);p.write_text(json.dumps(package,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
(root/'vd-server-go/version.go').write_text('package main\n\nconst moduleVersion = '+json.dumps(cfg['moduleVersion'])+'\nconst pluginVersion = '+json.dumps(cfg['pluginVersion'])+'\nconst gatewayProtocol = '+str(cfg['protocolVersion'])+'\n',encoding='utf-8')
(root/'dsha-plugin/lib/version.cjs').write_text("module.exports = "+json.dumps(cfg)+";\n",encoding='utf-8')
(root/'agent-hook-apk/src/com/agent/mobileuse/ReleaseInfo.java').write_text('package com.agent.mobileuse;\nfinal class ReleaseInfo {\n static final String MODULE = '+json.dumps(cfg['moduleVersion'])+';\n static final String PLUGIN = '+json.dumps(cfg['pluginVersion'])+';\n static final int CODE = '+str(cfg['moduleVersionCode'])+';\n}\n',encoding='utf-8')
