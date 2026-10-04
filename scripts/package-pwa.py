#!/usr/bin/env python3
"""Package built static assets and owner-configured Kubernetes artifacts."""
import argparse
import hashlib
import json
import re
import shutil
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--hostname', default='habits.mooneeb.dev')
parser.add_argument('--web-client-id', required=True)
parser.add_argument('--image', required=True, help='Immutable tag or digest available to the home cluster')
parser.add_argument('--namespace', default='habits')
parser.add_argument('--gateway', default='main')
parser.add_argument('--gateway-namespace', default='gateway')
parser.add_argument('--tls-listener', default='https')
parser.add_argument('--output', default='build/release/pwa')
parser.add_argument('--site-dir', help='Read-only release directory on a Kubernetes node; uses the stock nginx image')
parser.add_argument('--node', help='Node hosting --site-dir')
args = parser.parse_args()
if bool(args.site_dir) != bool(args.node):
    parser.error('--site-dir and --node must be used together')
if args.site_dir and (not re.fullmatch(r'/[a-zA-Z0-9/_-]+', args.site_dir) or not re.fullmatch(r'[a-z0-9][a-z0-9.-]*', args.node)):
    parser.error('Invalid absolute site directory or node name')
for value in [args.hostname, args.namespace, args.gateway, args.gateway_namespace, args.tls_listener]:
    if not re.fullmatch(r'[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?', value):
        parser.error('Invalid hostname, namespace or Gateway name')
if not re.fullmatch(r'[a-zA-Z0-9./:@_-]+', args.image) or not re.fullmatch(r'[a-zA-Z0-9_-]+\.apps\.googleusercontent\.com', args.web_client_id):
    parser.error('Invalid image or public OAuth client ID')
source = Path('uhabits-web/build/drive-gate')
if not (source / 'app/sql-wasm.wasm').is_file():
    parser.error('Build :uhabits-web:prepareDriveGate first')
out = Path(args.output)
if out.exists():
    parser.error('Output already exists; use a new directory to retain the previous release')
shutil.copytree(source, out / 'site')
# Live gate tools are development-only and are not published.
for name in ['index.html', 'gate.mjs', 'gate.css', 'drive-gate.mjs', 'packaging-probe.mjs']:
    (out / 'site' / name).unlink(missing_ok=True)
config = 'export const config = ' + json.dumps({'webClientId': args.web_client_id, 'workspaceId': 'default'}) + ';\n'
(out / 'site/app/config.mjs').write_text(config)
shutil.copytree('deploy/kubernetes', out / 'kubernetes')
(out / 'kubernetes/config.mjs').write_text(config)
for file in (out / 'kubernetes').glob('*.yaml'):
    text = file.read_text().replace('habits.mooneeb.dev', args.hostname).replace('namespace: habits', 'namespace: ' + args.namespace)
    if file.name == 'namespace.yaml':
        text = text.replace('name: habits', 'name: ' + args.namespace)
    if file.name == 'deployment.yaml':
        text = text.replace('loop-pwa:release', args.image)
    if file.name == 'http-route.yaml':
        text = text.replace('name: main', 'name: ' + args.gateway).replace('namespace: gateway', 'namespace: ' + args.gateway_namespace).replace('sectionName: https', 'sectionName: ' + args.tls_listener)
    file.write_text(text)
shutil.copy('deploy/Dockerfile', out / 'Dockerfile')
shutil.copy('deploy/nginx.conf', out / 'nginx.conf')
if args.site_dir:
    deployment = out / 'kubernetes/deployment.yaml'
    text = deployment.read_text().replace('      automountServiceAccountToken:', '      nodeSelector:\n        kubernetes.io/hostname: ' + args.node + '\n      automountServiceAccountToken:')
    text = text.replace('            - {name: temporary,', '            - {name: site, mountPath: /usr/share/nginx/html, readOnly: true}\n            - {name: nginx, mountPath: /etc/nginx/conf.d/default.conf, subPath: nginx.conf, readOnly: true}\n            - {name: temporary,')
    text = text.replace('      volumes:\n', '      volumes:\n        - name: site\n          hostPath: {path: ' + args.site_dir + ', type: Directory}\n        - name: nginx\n          configMap: {name: loop-nginx-config}\n')
    deployment.write_text(text)
    shutil.copy('deploy/nginx.conf', out / 'kubernetes/nginx.conf')
    with (out / 'kubernetes/kustomization.yaml').open('a') as file:
        file.write('  - name: loop-nginx-config\n    files:\n      - nginx.conf\n')
# Configuration participates in the shell version, including a replaced OAuth client.
digest = hashlib.sha256()
for file in sorted((out / 'site').rglob('*')):
    if file.is_file() and file.name != 'sw.js':
        digest.update(file.read_bytes())
worker = out / 'site/app/sw.js'
worker.write_text(re.sub(r'loop-owned-shell-[a-f0-9]+', 'loop-owned-shell-' + digest.hexdigest()[:24], worker.read_text()))
(out / 'release.json').write_text(json.dumps({'origin': 'https://' + args.hostname, 'image': args.image, 'namespace': args.namespace, 'shellVersion': digest.hexdigest()[:24]}, indent=2) + '\n')
print(out)
