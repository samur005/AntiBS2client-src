#!/usr/bin/env python3
# Minimal pkg-config shim for cross builds (no pkg-config on the build box): --exists/--cflags/--libs/--modversion
import os, sys, re
args = sys.argv[1:]
if '--version' in args: print('0.29.2'); sys.exit(0)
if any(a.startswith('--atleast-pkgconfig-version') for a in args): sys.exit(0)
dirs = [d for d in (os.environ.get('PKG_CONFIG_LIBDIR','') + ':' + os.environ.get('PKG_CONFIG_PATH','')).split(':') if d]
opts = [a for a in args if a.startswith('-')]
mods = []; it = iter([a for a in args if not a.startswith('-')])
toks = [a for a in args if not a.startswith('-')]
i = 0
while i < len(toks):
    t = toks[i]
    if i + 2 < len(toks) + 1 and i + 1 < len(toks) and toks[i+1] in ('>=','<=','=','>','<'): mods.append(t); i += 3
    else:
        for m in t.replace(',', ' ').split(): mods.append(m)
        i += 1
def load(name):
    for d in dirs:
        p = os.path.join(d, name + '.pc')
        if os.path.exists(p):
            v = {}; f = {}
            for line in open(p):
                line = line.strip()
                m = re.match(r'^([A-Za-z_][\w.]*)=(.*)$', line)
                if m: v[m[1]] = m[2]; continue
                m = re.match(r'^([A-Za-z.]+):\s*(.*)$', line)
                if m: f[m[1]] = m[2]
            def sub(s):
                for _ in range(10): s = re.sub(r'\$\{(\w+)\}', lambda m: v.get(m[1], ''), s)
                return s
            for k in list(v): v[k] = sub(v[k])
            return {k: sub(x) for k, x in f.items()}
    return None
out = []
for m in mods:
    f = load(m)
    if f is None:
        if '--print-errors' in opts or '--short-errors' in opts: sys.stderr.write(f"Package {m} was not found\n")
        sys.exit(1)
    if '--modversion' in opts: out.append(f.get('Version', ''))
    if '--cflags' in opts or '--cflags-only-I' in opts: out.append(f.get('Cflags', ''))
    if '--libs' in opts:
        out.append(f.get('Libs', ''))
        if '--static' in opts: out.append(f.get('Libs.private', ''))
        for r in re.split(r'[ ,]+', f.get('Requires.private' if '--static' in opts else 'Requires', '')):
            if r and not re.match(r'^[<>=]', r) and not re.match(r'^[\d.]+$', r):
                g = load(r)
                if g: out.append(g.get('Libs', ''))
if out: print(' '.join(x for x in out if x).strip())
sys.exit(0)
