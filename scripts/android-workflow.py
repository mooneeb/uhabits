#!/usr/bin/env python3
"""Inspect and drive the real Loop UI; never write tracking data through a test API."""
import argparse
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--port', default='5037')
parser.add_argument('--serial', default='emulator-5554')
parser.add_argument('action', choices=['inspect', 'tap', 'hold', 'input', 'assert', 'shot'])
parser.add_argument('target', nargs='?')
parser.add_argument('value', nargs='?')
args = parser.parse_args()
adb = ['adb', '-P', args.port, '-s', args.serial]

def run(*parts):
    return subprocess.check_output(adb + list(parts), text=True, timeout=15)

def nodes():
    run('shell', 'uiautomator dump /sdcard/loop-workflow.xml')
    return list(ET.fromstring(run('shell', 'cat /sdcard/loop-workflow.xml')).iter('node'))

def find(target):
    kind, value = target.split('=', 1)
    key = {'text': 'text', 'id': 'resource-id', 'desc': 'content-desc'}[kind]
    values = {value}
    if kind == 'id' and ':' not in value:
        values.add('org.isoron.uhabits:id/' + value)
    deadline = time.monotonic() + 15
    while True:
        matching = [node for node in nodes() if node.get(key) in values and node.get('bounds') != '[0,0][0,0]']
        actionable = [node for node in matching if node.get('clickable') == 'true']
        if len(actionable) == 1:
            matching = actionable
        if len(matching) == 1:
            return matching[0]
        if len(matching) > 1:
            raise RuntimeError('Ambiguous UI target: ' + target)
        if time.monotonic() >= deadline:
            raise RuntimeError('Missing UI target: ' + target)
        time.sleep(.3)

def point(node):
    left, top, right, bottom = map(int, re.findall(r'\d+', node.get('bounds')))
    return str((left + right) // 2), str((top + bottom) // 2)

if args.action == 'inspect':
    for node in nodes():
        if node.get('password') == 'true':
            continue
        if node.get('text') or node.get('content-desc'):
            print(node.get('text'), node.get('content-desc'), node.get('resource-id'), node.get('bounds'))
elif args.action == 'shot':
    with open(args.target, 'wb') as output:
        subprocess.run(adb + ['exec-out', 'screencap', '-p'], stdout=output, check=True, timeout=15)
else:
    node = find(args.target)
    x, y = point(node)
    if args.action == 'assert':
        print('PASS', args.target)
    elif args.action == 'hold':
        run('shell', f'input swipe {x} {y} {x} {y} 1100')
    else:
        run('shell', f'input tap {x} {y}')
        if args.action == 'input':
            if node.get('password') == 'true':
                raise RuntimeError('Credential input is outside this harness')
            run('shell', 'input keycombination 113 29')
            run('shell', 'input text ' + shlex.quote(args.value.replace(' ', '%s')))
