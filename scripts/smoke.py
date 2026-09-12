#!/usr/bin/env python3
"""使用真實 HTTP 與三框架走完四條作者路線；每次建立獨立測試存檔。"""
import argparse
import json
import time
import urllib.request
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('--url', default='http://127.0.0.1:8080')
parser.add_argument('--record', help='將完整回合與 trace 存入指定 JSON 檔')
args = parser.parse_args()
records = []

def api(path, body=None, method=None):
    req = urllib.request.Request(args.url + '/api' + path, method=method or ('POST' if body is not None else 'GET'),
        data=json.dumps(body).encode() if body is not None else None,
        headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=60) as response:
        return json.load(response)

settings = api('/settings')

for solution in ['repair', 'cooperate']:
    for ending in ['depart', 'postpone']:
        scene = api('/saves', {'name': f'自動驗收 {solution}/{ending}', 'seed': 26})
        save_id = scene['saveId']
        route = {'saveId': save_id, 'solution': solution, 'ending': ending, 'turns': []}
        def act(action):
            global scene
            receipt = api(f'/saves/{save_id}/turns', {'requestId': str(uuid.uuid4()), 'expectedRevision': scene['revision'], 'suggestionId': action})
            until = time.monotonic() + 60
            while time.monotonic() < until:
                turn = api(f'/saves/{save_id}/turns/{receipt["turnId"]}')
                if turn['status'] == 'FAILED':
                    raise RuntimeError(turn['error'])
                if turn['status'] == 'COMPLETE':
                    result = turn['result']
                    assert result['accepted'], result['narrative']
                    scene = result['scene']
                    if args.record and settings.get('debugEnabled'):
                        result = api(f'/saves/{save_id}/debug/{receipt["turnId"]}')['result']
                    route['turns'].append({'action': action, 'result': result})
                    return
                time.sleep(.05)
            raise TimeoutError(receipt)
        def move(place):
            if scene['place'] != place:
                act('move:' + place)
        for chapter, place, action in [(0, 'INN', 'event:arrival:tea'), (1, 'BRIDGE', 'event:promise:yes'),
                (2, 'MARKET', 'event:anomaly:observe'), (3, 'BRIDGE', 'event:closure:ferry')]:
            move(place)
            assert scene['chapter'] == chapter
            act(action)
        if solution == 'repair':
            move('TEAHOUSE')
            for _ in range(20):
                if any('不相容' in fact for fact in scene['facts']): break
                act(scene['suggestions'][0]['id'])
        move('MARKET')
        target = 'event:festival:' + solution
        for _ in range(20):
            if any(s['id'] == target for s in scene['suggestions']): break
            act(next(s['id'] for s in scene['suggestions'] if s['id'] != 'event:festival:repair'))
        act(target)
        move('DOCK')
        if ending == 'depart':
            for _ in range(20):
                if any(s['id'] == 'event:departure:depart' for s in scene['suggestions']): break
                act(scene['suggestions'][0]['id'])
        act('event:departure:' + ending)
        assert scene['chapter'] == 6
        assert scene['ending'] == ('departed' if ending == 'depart' else 'postponed')
        exported = api(f'/saves/{save_id}/export')
        imported = api('/saves/import', exported)
        assert imported['ending'] == scene['ending'] and imported['saveId'] != save_id
        memories = api(f'/saves/{save_id}/memories')
        memory = memories[0]
        changed = api(f'/saves/{save_id}/memories/{memory["id"]}', {'expectedRevision': scene['revision'], 'text': '驗收更正：現在喜歡熱湯。'}, 'PATCH')
        assert changed['revision'] == scene['revision'] + 1 and changed['ending'] == scene['ending']
        records.append(route)
        print(f'PASS {solution}/{ending}: {len(route["turns"])} turns, revision {changed["revision"]}', flush=True)
if args.record:
    with open(args.record, 'w') as output:
        json.dump(records, output, ensure_ascii=False, indent=2)
