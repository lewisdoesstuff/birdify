"""Generates app/src/main/res/raw/bird_refresh.json, the bird served for X's refresh logo.

  python3 tools/bird-refresh/gen.py app/src/main/res/raw/bird_refresh.json

X drives the animation by frame number, so the canvas, fps and the three
marker ranges are copied from X's own x_refresh_logo.json:
  pull    0..100  scrubbed by pull distance
  loop  100..152  repeats while refreshing (152 snaps back to 100)
  release 152..189 then X fades frame 0 in over the top and resets to 0
"""
import json
import math
import re
import sys

import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from svgpath import parse

BIRD_XML = os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../app/src/main/res/drawable/bird_blue.xml')
BLUE = [0x1D / 255, 0x9B / 255, 0xF0 / 255, 1]
W = H = 240
FR = 60
OP = 189
PULL, LOOP, RELEASE = 100, 152, 189

K = 10.0                     # bird units -> canvas units
BIRD_CX, BIRD_CY = 11.0, 8.5  # bbox centre of the bird path
# Body pivot (bird units): roughly the centre of mass, below the head.
PIVOT = (12.0, 9.5)
# Wing hinge (bird units) and how strongly each wing vertex follows it. The
# tip follows fully; feathers nearer the body and the joints bend less.
WING_HINGE = (10.5, 7.0)
WING = {6: 0.0, 7: 1.0, 8: 1.0, 9: 0.9, 10: 0.85, 11: 0.85, 12: 0.6, 13: 0.65, 14: 0.65, 15: 0.15}
# Wing extremes as (turn in degrees, vertical stretch about the hinge).
WING_UP = (16.0, 1.3)
WING_DOWN = (-24.0, 0.6)


def to_canvas(p):
    return ((p[0] - BIRD_CX) * K + W / 2, (p[1] - BIRD_CY) * K + H / 2)


def load_bird():
    d = re.search(r'pathData="([^"]+)"', open(BIRD_XML).read()).group(1)
    (verts,) = parse(d)
    return verts


BASE = load_bird()
ANCHOR = to_canvas(PIVOT)
BOTTOM = (16.5 - PIVOT[1]) * K  # pivot to feet, canvas units


def rot(v, deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    # y points down, so this turns clockwise on screen like Lottie's rotation.
    return (v[0] * c - v[1] * s, v[0] * s + v[1] * c)


def bird_shape(wing):
    """The bird path with the wing at `wing`: 1 raised, 0 folded, -1 lowered.

    The wing is stretched vertically about its hinge and then turned, which
    reads as a beat toward and away from the viewer rather than a stiff hinge.
    """
    hx, hy = WING_HINGE
    ext = WING_UP if wing >= 0 else WING_DOWN
    turn, stretch = ext[0] * abs(wing), 1 + (ext[1] - 1) * abs(wing)
    out_v, out_i, out_o = [], [], []
    for n, vert in enumerate(BASE):
        v, i, o = vert['v'], vert['i'], vert['o']
        w = WING.get(n, 0.0)
        if w:
            s = 1 + (stretch - 1) * w
            r = rot((v[0] - hx, (v[1] - hy) * s), turn * w)
            v = (r[0] + hx, r[1] + hy)
            i = rot((i[0], i[1] * s), turn * w)
            o = rot((o[0], o[1] * s), turn * w)
        cv = to_canvas(v)
        out_v.append([round(cv[0], 1), round(cv[1], 1)])
        out_i.append([round(i[0] * K, 1), round(i[1] * K, 1)])
        out_o.append([round(o[0] * K, 1), round(o[1] * K, 1)])
    return {'i': out_i, 'o': out_o, 'v': out_v, 'c': True}


# ---- choreography ---------------------------------------------------------

def clamp(x, a=0.0, b=1.0):
    return max(a, min(b, x))


def smooth(x):
    x = clamp(x)
    return x * x * (3 - 2 * x)


def ease_out(x):
    x = clamp(x)
    return 1 - (1 - x) ** 3


def ease_in(x):
    x = clamp(x)
    return x ** 3


def lerp(a, b, t):
    return a + (b - a) * t


def seg(t, t0, t1):
    return clamp((t - t0) / (t1 - t0))


# Hover pose shared by the end of pull, both ends of loop and start of release.
HOVER = dict(wing=1.0, tx=0.0, ty=-6.0, r=-4.0, s=0.92, sx=1.0, sy=1.0, o=100.0)
REST = dict(wing=0.0, tx=0.0, ty=0.0, r=0.0, s=1.0, sx=1.0, sy=1.0, o=100.0)


def mix(a, b, t):
    return {k: lerp(a[k], b[k], t) for k in a}


def pose(t):
    if t <= PULL:
        return pull(t)
    if t <= LOOP:
        return loop(t)
    return release(t)


CROUCH_END = 62


def pull(t):
    """Winds up as the feed is pulled: crouch, look up, flutter, spring up."""
    c = smooth(seg(t, 0, CROUCH_END))
    p = dict(REST)
    p['sy'] = lerp(1.0, 0.88, c)
    p['sx'] = lerp(1.0, 1.06, c)
    p['r'] = lerp(0.0, -8.0, c)
    p['s'] = lerp(1.0, 0.95, c)
    p['wing'] = lerp(0.0, 0.25, c)
    # Three quick wing twitches while crouched, swelling then fading.
    f = seg(t, 16, CROUCH_END)
    p['wing'] += 0.35 * math.sin(f * math.pi * 6) * (f * (1 - f) * 4)
    sp = seg(t, CROUCH_END, PULL)
    if sp > 0:
        p = mix(p, HOVER, ease_out(sp))
        # Stretch a little on the way up.
        bump = math.sin(math.pi * sp) * (1 - sp)
        p['sy'] += 0.08 * bump
        p['sx'] -= 0.04 * bump
    return p


def beat(u):
    """One wing beat, u in 0..1: quick downstroke, slower recovery.

    Returns the wing position and how far the beat has lifted the body.
    """
    if u < 0.4:
        e = smooth(u / 0.4)
        return lerp(1.0, -1.0, e), e
    e = smooth((u - 0.4) / 0.6)
    return lerp(-1.0, 1.0, e), 1 - e


def loop(t):
    """Hovers on two wing beats per loop; the body lifts on each downstroke."""
    p = dict(HOVER)
    u = 0.0 if t >= LOOP else ((t - PULL) / (LOOP - PULL) * 2) % 1.0
    p['wing'], lift = beat(u)
    p['ty'] = HOVER['ty'] - 8.0 * lift
    p['r'] = HOVER['r'] - 3.0 * lift
    p['sy'] = 1.0 + 0.03 * lift
    p['sx'] = 1.0 - 0.015 * lift
    return p


# The loop is flown small and wide so it reads as a loop rather than a spin:
# the bird shrinks into a circle about as wide as the canvas, and a trail draws
# the circle behind it. The circle is centred on the canvas, which the pivot
# sits off by CENTRE, and starts at the bottom flying right, as a plane would.
LOOP_R = 62.0
LOOP_S = 0.32
CENTRE = (W / 2 - ANCHOR[0], H / 2 - ANCHOR[1])
DIVE, LOOPED, HOME, RELEASE_END = 159.0, 178.0, 184.0, RELEASE


def on_loop(a):
    """Pivot offset and heading at angle a around the loop, 0 at the bottom."""
    return (CENTRE[0] + LOOP_R * math.sin(a), CENTRE[1] + LOOP_R * math.cos(a), -math.degrees(a))


def release(t):
    """Swoops into a loop-the-loop, then flies home to the perch and settles."""
    p = dict(HOVER)
    bottom_x, bottom_y, _ = on_loop(0.0)
    if t <= DIVE:
        # Shrink and dive to the bottom of the loop on one downstroke.
        u = smooth(seg(t, LOOP, DIVE))
        p['wing'] = lerp(1.0, -1.0, u)
        p['tx'] = lerp(HOVER['tx'], bottom_x, u)
        p['ty'] = lerp(HOVER['ty'], bottom_y, u)
        p['s'] = lerp(HOVER['s'], LOOP_S, u)
        p['r'] = lerp(HOVER['r'], 0.0, u)
        return p
    if t <= LOOPED:
        u = seg(t, DIVE, LOOPED)
        p['tx'], p['ty'], p['r'] = on_loop(2 * math.pi * u)
        p['s'] = LOOP_S
        # Two beats climbing the near side, a glide over the top, one beat down.
        p['wing'] = math.cos(u * 4 * math.pi * 1.5) if u < 0.4 else (
            0.8 if u < 0.65 else lerp(0.8, -1.0, math.sin(math.pi * seg(u, 0.65, 1.0))))
        return p
    # Fly home from the bottom of the loop, growing back to full size.
    u = smooth(seg(t, LOOPED, HOME))
    q = mix(dict(HOVER, tx=bottom_x, ty=bottom_y, r=-360.0, s=LOOP_S, wing=0.8),
            dict(REST, r=-360.0), u)
    q['wing'] = lerp(0.8, 0.0, smooth(seg(t, LOOPED, HOME + 2)))
    # Damped bounce from the moment the feet touch down.
    sq = seg(t, HOME, RELEASE_END)
    b = math.sin(sq * math.pi * 2.0) * (1 - sq) ** 1.5
    q['sy'] = 1.0 - 0.14 * b
    q['sx'] = 1.0 + 0.07 * b
    return q


def trail():
    """A stroke that draws the loop behind the bird and fades once it is home.

    The circle runs bottom, right, top, left, matching on_loop, and is trimmed
    so the stroke trails the bird by a little under half a lap.
    """
    k = 0.5523 * LOOP_R
    cx, cy = ANCHOR[0] + CENTRE[0], ANCHOR[1] + CENTRE[1]
    v = [[cx, cy + LOOP_R], [cx + LOOP_R, cy], [cx, cy - LOOP_R], [cx - LOOP_R, cy]]
    o = [[k, 0], [0, -k], [-k, 0], [0, k]]
    i = [[-k, 0], [0, k], [k, 0], [0, -k]]
    circle = {'c': True, 'v': [[round(x, 2) for x in pt] for pt in v],
              'i': [[round(x, 2) for x in pt] for pt in i], 'o': [[round(x, 2) for x in pt] for pt in o]}
    end, start, opacity = [], [], []
    for t in range(int(DIVE), int(HOME) + 7):
        u = seg(t, DIVE, LOOPED)
        end.append((t, [round(100 * u, 2)]))
        start.append((t, [round(100 * clamp(u - 0.45) if t <= LOOPED else 100 * lerp(0.55, 1.0, seg(t, LOOPED, HOME)), 2)]))
        opacity.append((t, [round(100 * (1 - seg(t, LOOPED, HOME + 6)), 1)]))
    return {
        'ddd': 0, 'ind': 2, 'ty': 4, 'nm': 'Trail', 'sr': 1, 'ao': 0,
        'ks': {
            'o': animated(opacity, 0.5),
            'r': {'a': 0, 'k': 0}, 'p': {'a': 0, 'k': [0, 0]},
            'a': {'a': 0, 'k': [0, 0]}, 's': {'a': 0, 'k': [100, 100]},
        },
        'shapes': [{
            'ty': 'gr', 'nm': 'Trail',
            'it': [
                {'ty': 'sh', 'nm': 'Loop', 'ks': {'a': 0, 'k': circle}},
                {'ty': 'tm', 'nm': 'Draw', 's': animated(start, 0.2), 'e': animated(end, 0.2),
                 'o': {'a': 0, 'k': 0}, 'm': 1},
                {'ty': 'st', 'nm': 'Twitter Blue', 'c': {'a': 0, 'k': BLUE}, 'o': {'a': 0, 'k': 45},
                 'w': {'a': 0, 'k': 7}, 'lc': 2, 'lj': 2},
                {'ty': 'tr', 'p': {'a': 0, 'k': [0, 0]}, 'a': {'a': 0, 'k': [0, 0]},
                 's': {'a': 0, 'k': [100, 100]}, 'r': {'a': 0, 'k': 0}, 'o': {'a': 0, 'k': 100},
                 'sk': {'a': 0, 'k': 0}, 'sa': {'a': 0, 'k': 0}},
            ],
        }],
        'ip': int(DIVE), 'op': min(int(HOME) + 7, OP + 1), 'st': 0, 'bm': 0,
    }


# ---- emit -----------------------------------------------------------------

LINEAR_O = {'x': [0.0], 'y': [0.0]}
LINEAR_I = {'x': [1.0], 'y': [1.0]}
FORCED = {0, PULL, LOOP, RELEASE}


def flat(v):
    if isinstance(v, dict):
        return [c for p in v['v'] + v['i'] + v['o'] for c in p]
    return list(v)


def simplify(samples, tol):
    """Drops frames that linear interpolation already reproduces within tol."""
    keep = [samples[0]]
    i = 0
    while i < len(samples) - 1:
        j = i + 1
        while j + 1 < len(samples) and samples[j][0] not in FORCED:
            a, b = flat(samples[i][1]), flat(samples[j + 1][1])
            ok = True
            for k in range(i + 1, j + 1):
                f = (samples[k][0] - samples[i][0]) / (samples[j + 1][0] - samples[i][0])
                m = flat(samples[k][1])
                if any(abs(lerp(x, y, f) - z) > tol for x, y, z in zip(a, b, m)):
                    ok = False
                    break
            if not ok:
                break
            j += 1
        keep.append(samples[j])
        i = j
    return keep


def animated(samples, tol, wrap=lambda v: v):
    ks = simplify(samples, tol)
    if all(flat(k[1]) == flat(ks[0][1]) for k in ks):
        return {'a': 0, 'k': wrap(ks[0][1]) if not isinstance(ks[0][1], dict) else ks[0][1]}
    out = []
    for n, (t, v) in enumerate(ks):
        kf = {'t': t, 's': [v] if isinstance(v, dict) else wrap(v)}
        if n < len(ks) - 1:
            kf['o'] = LINEAR_O
            kf['i'] = LINEAR_I
        out.append(kf)
    return {'a': 1, 'k': out}


def build():
    frames = list(range(0, OP + 1))
    poses = [(t, pose(t)) for t in frames]
    pos, scl, rotn, opa, shp = [], [], [], [], []
    for t, p in poses:
        # Squash from the feet rather than the pivot.
        sy = p['s'] * p['sy']
        sx = p['s'] * p['sx']
        dy = (1 - p['sy']) * BOTTOM * p['s']
        pos.append((t, [round(ANCHOR[0] + p['tx'], 2), round(ANCHOR[1] + p['ty'] + dy, 2)]))
        scl.append((t, [round(sx * 100, 2), round(sy * 100, 2)]))
        rotn.append((t, [round(p['r'], 2)]))
        opa.append((t, [round(p['o'], 1)]))
        shp.append((t, bird_shape(p['wing'])))
    ks = {
        'o': animated(opa, 0.5),
        'r': animated(rotn, 0.15),
        'p': animated(pos, 0.15),
        'a': {'a': 0, 'k': [round(ANCHOR[0], 2), round(ANCHOR[1], 2)]},
        's': animated(scl, 0.15),
    }
    for k in ('o', 'r'):
        if ks[k]['a'] == 0:
            ks[k]['k'] = ks[k]['k'][0]
    layer = {
        'ddd': 0, 'ind': 1, 'ty': 4, 'nm': 'Bird', 'sr': 1, 'ks': ks, 'ao': 0,
        'shapes': [{
            'ty': 'gr', 'nm': 'Bird',
            'it': [
                {'ty': 'sh', 'nm': 'Silhouette', 'ks': animated(shp, 1.0)},
                {'ty': 'fl', 'nm': 'Twitter Blue', 'c': {'a': 0, 'k': BLUE}, 'o': {'a': 0, 'k': 100}, 'r': 1},
                {'ty': 'tr', 'p': {'a': 0, 'k': [0, 0]}, 'a': {'a': 0, 'k': [0, 0]},
                 's': {'a': 0, 'k': [100, 100]}, 'r': {'a': 0, 'k': 0}, 'o': {'a': 0, 'k': 100},
                 'sk': {'a': 0, 'k': 0}, 'sa': {'a': 0, 'k': 0}},
            ],
        }],
        'ip': 0, 'op': OP + 1, 'st': 0, 'bm': 0,
    }
    return {
        'v': '5.7.6', 'fr': FR, 'ip': 0, 'op': OP, 'w': W, 'h': H,
        'nm': 'Bird Refresh', 'ddd': 0, 'assets': [], 'layers': [layer, trail()],
        'markers': [
            {'tm': 0, 'cm': 'pull', 'dr': PULL},
            {'tm': PULL, 'cm': 'loop', 'dr': LOOP - PULL},
            {'tm': LOOP, 'cm': 'release', 'dr': RELEASE - LOOP},
        ],
    }


if __name__ == '__main__':
    doc = build()
    json.dump(doc, open(sys.argv[1], 'w'), separators=(',', ':'))
