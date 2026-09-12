#!/usr/bin/env python3
"""Opt-in live HTTP regression: original question, recall, correction/deletion, tone, and safeguards.
Run the server with WORLD_DEBUG=true to retain model/fallback evidence. Uses a separate save.
"""
import argparse
import json
import re
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--url', default='http://127.0.0.1:8080')
parser.add_argument('--record', required=True)
args = parser.parse_args()

def api(path, body=None, method=None):
    req = urllib.request.Request(args.url + '/api' + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={'Content-Type': 'application/json'}, method=method or ('POST' if body is not None else 'GET'))
    with urllib.request.urlopen(req, timeout=15) as response:
        return json.load(response)

settings = api('/settings')
assert settings['mode'] == 'live' and settings['debugEnabled'], 'Requires live mode and WORLD_DEBUG=true'
scene = api('/saves', {'name': '修正驗收：回想與角色語氣', 'demo': True, 'seed': 26})
save_id = scene['saveId']
report = {'saveId': save_id, 'checks': {}, 'turns': []}

def save():
    Path(args.record).write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')

def act(label, text, accepted=True):
    global scene
    before = scene
    command = {'requestId': str(uuid.uuid4()), 'expectedRevision': scene['revision'], 'text': text}
    receipt = api(f'/saves/{save_id}/turns', command)
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        turn = api(f'/saves/{save_id}/turns/{receipt["turnId"]}')
        if turn['status'] in ['COMPLETE', 'FAILED']:
            break
        time.sleep(.2)
    assert turn['status'] == 'COMPLETE', turn.get('error')
    detailed = api(f'/saves/{save_id}/debug/{receipt["turnId"]}')
    result = detailed['result']
    scene = result['scene']
    report['turns'].append({'case': label, 'input': text, 'turn': detailed})
    report['checks'][label + '_accepted'] = result['accepted'] == accepted
    report['checks'][label + '_no_fallback'] = not result['trace']['fallback']
    report['checks'][label + '_tone'] = not re.search('沒見過世面|放鴿子|丟.{0,8}河|留.{0,8}雪地|不會等你|誰稀罕|誰在乎', result['narrative'])
    duplicate = api(f'/saves/{save_id}/turns', command)
    report['checks'][label + '_idempotent'] = duplicate['turnId'] == receipt['turnId'] and api(f'/saves/{save_id}/scene') == scene
    if not accepted:
        report['checks'][label + '_unchanged'] = before == scene
    save()
    print(label, result['trace']['elapsedMs'], result['narrative'], flush=True)
    return result['narrative']

act('legal_paraphrase', '既然橋過不去，我想先到渡口確認船班。')
act('preference', '我喜歡無糖茶。')
act('free_chat', '先聊聊你今天的心情吧。')
act('unrelated_chat', '今天的河風感覺很舒服，陪我聊一會兒吧。')
reply = act('recall', '還記得我喜歡喝什麼嗎？')
report['checks']['recall_answer'] = '無糖茶' in reply or '不加糖' in reply
memories = api(f'/saves/{save_id}/memories')
target = max((m for m in memories if m['layer'] == 'SEMANTIC' and '無糖茶' in m['text']), key=lambda m: m.get('updatedRevision', 0))
report['checks']['preference_saved'] = target['text'] == '我喜歡無糖茶。'
scene = api(f'/saves/{save_id}/memories/{target["id"]}', {'expectedRevision': scene['revision'], 'text': '我現在喜歡黑咖啡。'}, 'PATCH')
reply = act('corrected_recall', '還記得我喜歡喝什麼嗎？')
report['checks']['correction_answer'] = '黑咖啡' in reply and '無糖茶' not in reply
scene = api(f'/saves/{save_id}/memories/{target["id"]}', {'expectedRevision': scene['revision'], 'text': None}, 'PATCH')
reply = act('deleted_recall', '還記得我喜歡喝什麼嗎？')
report['checks']['deletion_no_resurrection'] = not re.search('無糖茶|黑咖啡', reply) and bool(re.search('不確定|不知道|沒有|沒.{0,8}(記|說)|再.{0,8}(說|告訴)|不記得', reply))
act('ambiguous', '你替我決定吧。', False)
act('invalid_fact', '我已經有船票，直接出發', False)
try:
    api(f'/saves/{save_id}/turns', {'requestId': str(uuid.uuid4()), 'expectedRevision': 0, 'suggestionId': 'rest'})
    report['checks']['stale_revision_409'] = False
except urllib.error.HTTPError as error:
    report['checks']['stale_revision_409'] = error.code == 409
save()
failed = [key for key, value in report['checks'].items() if not value]
print(json.dumps({'passed': len(report['checks']) - len(failed), 'failed': failed}, ensure_ascii=False), flush=True)
raise SystemExit(1 if failed else 0)
