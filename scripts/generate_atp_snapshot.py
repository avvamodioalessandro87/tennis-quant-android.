import csv, io, math, urllib.request
from collections import defaultdict
from pathlib import Path

K = 32.0
CUTOFF = 20260525
YEARS = (2024, 2025, 2026)
BASE = 'https://raw.githubusercontent.com/Kadantte/tennis_atp/refs/heads/master/atp_matches_{}.csv'
OUT = Path('app/src/main/assets/atp_ratings_20260525.csv')


def p_elo(a, b):
    return 1.0 / (1.0 + 10.0 ** ((b - a) / 400.0))


def upd(a, b, y):
    p = p_elo(a, b)
    return a + K * (y - p), b + K * ((1.0 - y) - (1.0 - p))

rows = []
for year in YEARS:
    with urllib.request.urlopen(BASE.format(year), timeout=60) as r:
        text = r.read().decode('utf-8-sig')
    for rec in csv.DictReader(io.StringIO(text)):
        try:
            d = int(rec.get('tourney_date') or 0)
        except ValueError:
            continue
        if not d or d > CUTOFF:
            continue
        surf = (rec.get('surface') or '').strip()
        if surf not in {'Hard','Clay','Grass'}:
            continue
        w = (rec.get('winner_name') or '').strip()
        l = (rec.get('loser_name') or '').strip()
        if not w or not l:
            continue
        rows.append((d, rec.get('tourney_id',''), rec.get('match_num',''), w, l, surf))

rows.sort(key=lambda x: (x[0], x[1], str(x[2])))

general = defaultdict(lambda: 1500.0)
surface = {s: defaultdict(lambda: 1500.0) for s in ('Hard','Clay','Grass')}
players = set()

for _, _, _, w, l, s in rows:
    players.add(w); players.add(l)
    general[w], general[l] = upd(general[w], general[l], 1.0)
    surface[s][w], surface[s][l] = upd(surface[s][w], surface[s][l], 1.0)

OUT.parent.mkdir(parents=True, exist_ok=True)
with OUT.open('w', encoding='utf-8', newline='') as f:
    wr = csv.writer(f)
    wr.writerow(['name','elo','hard','clay','grass'])
    for name in sorted(players, key=str.casefold):
        wr.writerow([
            name,
            round(general[name], 3),
            round(surface['Hard'][name], 3),
            round(surface['Clay'][name], 3),
            round(surface['Grass'][name], 3),
        ])

print(f'Generated {len(players)} ATP players from {len(rows)} matches through {CUTOFF}.')
