"""Converts SVG/Android pathData into Lottie shape vertices.

Lottie stores each vertex with in/out tangents relative to the vertex, so a
cubic P0 -> (c1, c2) -> P1 becomes o[P0] = c1 - P0 and i[P1] = c2 - P1.
"""
import re

TOKEN = re.compile(r'[MmLlHhVvCcSsQqTtZz]|-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?')


def parse(d):
    toks = TOKEN.findall(d)
    pos = 0
    cmd = None
    cur = (0.0, 0.0)
    start = (0.0, 0.0)
    subpaths = []
    verts = None  # list of dicts {v, i, o}

    def num():
        nonlocal pos
        x = float(toks[pos]); pos += 1
        return x

    def add(pt, cin=None):
        # cin: absolute in-control point for pt
        nonlocal cur
        if cin is not None:
            verts[-1]['o'] = (verts[-1]['o'][0], verts[-1]['o'][1]) if False else verts[-1]['o']
        verts.append({'v': pt, 'i': (0.0, 0.0) if cin is None else (cin[0] - pt[0], cin[1] - pt[1]), 'o': (0.0, 0.0)})
        cur = pt

    while pos < len(toks):
        t = toks[pos]
        if re.match(r'[A-Za-z]', t):
            cmd = t; pos += 1
            if cmd in 'Zz':
                close(verts)
                subpaths.append(verts)
                verts = None
                cur = start
                continue
        rel = cmd.islower()
        c = cmd.upper()
        ox, oy = (cur if rel else (0.0, 0.0))
        if c == 'M':
            if verts:
                subpaths.append(verts)
            p = (num() + ox, num() + oy)
            verts = [{'v': p, 'i': (0.0, 0.0), 'o': (0.0, 0.0)}]
            cur = start = p
            cmd = 'l' if rel else 'L'  # implicit lineto after moveto
        elif c == 'L':
            add((num() + ox, num() + oy))
        elif c == 'H':
            add((num() + ox, cur[1]))
        elif c == 'V':
            add((cur[0], num() + oy))
        elif c == 'C':
            c1 = (num() + ox, num() + oy)
            c2 = (num() + ox, num() + oy)
            p = (num() + ox, num() + oy)
            verts[-1]['o'] = (c1[0] - cur[0], c1[1] - cur[1])
            add(p, c2)
        else:
            raise ValueError('unsupported command ' + cmd)
    if verts:
        subpaths.append(verts)
    return subpaths


def close(verts):
    """Folds a closing segment that lands back on the first vertex into it."""
    a, b = verts[0]['v'], verts[-1]['v']
    if len(verts) > 1 and abs(a[0] - b[0]) < 1e-6 and abs(a[1] - b[1]) < 1e-6:
        verts[0]['i'] = verts[-1]['i']
        verts.pop()
