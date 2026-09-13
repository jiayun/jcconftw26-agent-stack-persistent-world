#!/usr/bin/env python3
"""Opt-in live regression for advice versus action. Creates two isolated saves; requires debug traces."""
import argparse
import json
import time
import urllib.request
import uuid
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--url', default='http://127.0.0.1:8080')
parser.add_argument('--record', required=True)
args = parser.parse_args()

def api(path, body=None):
    request = urllib.request.Request(args.url + '/api' + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.load(response)

settings = api('/settings')
assert settings['mode'] == 'live' and settings['debugEnabled'], 'Requires live mode and WORLD_DEBUG=true'
report = {'checks': {}, 'turns': [], 'saveIds': []}

def save_report():
    Path(args.record).write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')

def turn(scene, label, *, text=None, suggestion=None, expected='chat'):
    sid = scene['saveId']
    before = api(f'/saves/{sid}/export')
    command = {'requestId': str(uuid.uuid4()), 'expectedRevision': scene['revision']}
    command.update({'text': text} if text is not None else {'suggestionId': suggestion})
    receipt = api(f'/saves/{sid}/turns', command)
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        record = api(f'/saves/{sid}/turns/{receipt["turnId"]}')
        if record['status'] in ['COMPLETE', 'FAILED']:
            break
        time.sleep(.2)
    assert record['status'] == 'COMPLETE', record.get('error')
    result = api(f'/saves/{sid}/debug/{receipt["turnId"]}')['result']
    after = api(f'/saves/{sid}/export')
    report['turns'].append({'case': label, 'command': command, 'result': result})
    checks = {
        'accepted': result['accepted'] == (expected != 'reject'),
        'no_fallback': not result['trace']['fallback'],
        'idempotent': api(f'/saves/{sid}/turns', command)['turnId'] == receipt['turnId'],
    }
    if expected == 'chat':
        checks['no_illegal_action_message'] = '行動目前不可用' not in result['narrative']
        checks['only_conversation_changed'] = all(before[k] == after[k] for k in before if k not in ['revision', 'memories'])
        checks['revision_once'] = after['revision'] == before['revision'] + 1
        checks['no_new_fact'] = [m for m in after['memories'] if m['layer'] != 'WORKING'] == [m for m in before['memories'] if m['layer'] != 'WORKING']
        checks['original_question_saved'] = any(m['layer'] == 'WORKING' and m['text'] == text for m in after['memories'])
    elif expected == 'reject':
        checks['world_unchanged'] = before == after
    else:
        checks['time_advanced_once'] = after['tick'] == before['tick'] + 1
        if expected == 'move':
            checks['at_dock'] = after['place'] == 'DOCK'
    report['checks'].update({label + ':' + k: v for k, v in checks.items()})
    save_report()
    print(label, result['accepted'], result['narrative'], flush=True)
    return result['scene']

for demo in [False, True]:
    prefix = 'bridge' if demo else 'arrival'
    scene = api('/saves', {'name': '詢問意見回歸 ' + prefix, 'demo': demo, 'seed': 26})
    report['saveIds'].append(scene['saveId'])
    for i, question in enumerate(['Elia 你說怎麼辦？', 'Elia，你有什麼建議？', '你會怎麼選？', '你覺得要去渡口嗎？']):
        scene = turn(scene, f'{prefix}_advice_{i + 1}', text=question)
    if not demo:
        scene = turn(scene, 'ambiguous', text='你替我決定吧。', expected='reject')
        scene = turn(scene, 'blocked_move', text='去北方星燈台吧。', expected='reject')
        scene = turn(scene, 'chosen_event', suggestion=scene['suggestions'][0]['id'], expected='event')
        scene = turn(scene, 'explicit_move', text='去渡口吧。', expected='move')

failed = [key for key, value in report['checks'].items() if not value]
print(json.dumps({'passed': len(report['checks']) - len(failed), 'failed': failed}, ensure_ascii=False), flush=True)
raise SystemExit(1 if failed else 0)
