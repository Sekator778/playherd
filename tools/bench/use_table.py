#!/usr/bin/env python3
"""USE table of a run from use-sampler.sh output (design §6.4).
   use_table.py <raw> <from_epoch_s> <to_epoch_s> [--gen 8-11,20-23,32-35,44-47] [--srv 12-19] [--db 0-7] [--other 24-31,36-43]
   Prints, per resource, mean / p95 / max of the per-second values inside the window; with --csv writes the series."""
import sys, argparse

def cpuset(s):
    out = set()
    for part in s.split(','):
        if '-' in part:
            a, b = part.split('-'); out |= set(range(int(a), int(b) + 1))
        elif part:
            out.add(int(part))
    return out

ap = argparse.ArgumentParser()
ap.add_argument('raw'); ap.add_argument('t0', type=float); ap.add_argument('t1', type=float)
ap.add_argument('--gen', default='8-11,20-23,32-35,44-47'); ap.add_argument('--srv', default='12-19')
ap.add_argument('--db', default='0-7'); ap.add_argument('--other', default='24-31,36-43')
ap.add_argument('--csv')
a = ap.parse_args()
sets = {'gen': cpuset(a.gen), 'srv': cpuset(a.srv), 'db': cpuset(a.db), 'other': cpuset(a.other)}

samples = []  # (t, {cpu:(busy,total)}, running, disk(io_ticks, wq, wr_ios), net(rx, tx, rxdrop, txdrop, rxerr, txerr))
cur = None
for line in open(a.raw):
    if line.startswith('#'): continue
    f = line.split()
    if line.startswith('T '):
        cur = [float(f[1]), {}, 0, None, None]; samples.append(cur)
    elif line.startswith('cpu'):
        v = list(map(int, f[1:9])); total = sum(v); idle = v[3] + v[4]
        cur[1][int(f[0][3:])] = (total - idle, total)
    elif line.startswith('procs_running'):
        cur[2] = int(f[1])
    elif line.startswith('D '):
        # D major minor name r_ios r_merge r_sect r_ms w_ios w_merge w_sect w_ms inflight io_ticks wq_ms
        cur[3] = (int(f[13]), int(f[14]), int(f[8]))
    elif line.startswith('N '):
        # N br: rx_bytes rx_pk rx_err rx_drop ... tx_bytes tx_pk tx_err tx_drop
        v = list(map(int, [x for x in f[1:]])) if False else list(map(int, f[2:]))
        cur[4] = (v[0], v[8], v[3], v[11], v[2], v[10])
rows = []
for p, q in zip(samples, samples[1:]):
    if not (a.t0 <= q[0] <= a.t1): continue
    dt = q[0] - p[0]
    r = {'t': q[0]}
    for name, cs in sets.items():
        b = t = 0
        for c in cs:
            if c in p[1] and c in q[1]:
                b += q[1][c][0] - p[1][c][0]; t += q[1][c][1] - p[1][c][1]
        r[name] = 100.0 * b / t if t else 0.0
    r['runq'] = q[2]
    if p[3] and q[3]:
        r['disk_util'] = 100.0 * (q[3][0] - p[3][0]) / (dt * 1000); r['disk_aqu'] = (q[3][1] - p[3][1]) / (dt * 1000); r['disk_wps'] = (q[3][2] - p[3][2]) / dt
    if p[4] and q[4]:
        r['net_rx_MBs'] = (q[4][0] - p[4][0]) / dt / 1e6; r['net_tx_MBs'] = (q[4][1] - p[4][1]) / dt / 1e6
        r['net_drops'] = (q[4][2] - p[4][2]) + (q[4][3] - p[4][3]); r['net_errs'] = (q[4][4] - p[4][4]) + (q[4][5] - p[4][5])
    rows.append(r)
if not rows:
    print('no samples in the window'); sys.exit(1)
def stat(k):
    v = sorted(x[k] for x in rows if k in x)
    if not v: return None
    return sum(v) / len(v), v[int(0.95 * (len(v) - 1))], v[-1]
labels = {'gen': 'CPU generator (cpuset)', 'srv': 'CPU server (cpuset)', 'db': 'CPU Postgres (cpuset)', 'other': 'CPU neighbours (cores left to others)',
          'runq': 'runnable tasks (procs_running, whole box)', 'disk_util': 'DB disk util %', 'disk_aqu': 'DB disk queue (avg in flight)', 'disk_wps': 'DB disk writes/s',
          'net_rx_MBs': 'server eth0 rx MB/s', 'net_tx_MBs': 'server eth0 tx MB/s', 'net_drops': 'server eth0 drops/s', 'net_errs': 'server eth0 errors/s'}
print(f'window {a.t1 - a.t0:.0f} s, {len(rows)} samples')
print('| resource | mean | p95 | max |\n|---|---|---|---|')
for k, lab in labels.items():
    s = stat(k)
    if s: print(f'| {lab} | {s[0]:.1f} | {s[1]:.1f} | {s[2]:.1f} |')
if a.csv:
    keys = ['t'] + [k for k in labels if k in rows[0]]
    open(a.csv, 'w').write(','.join(keys) + '\n' + '\n'.join(','.join(f'{r.get(k, 0):.3f}' for k in keys) for r in rows) + '\n')
