#!/usr/bin/env python3
"""Generates the architecture diagrams as 1600x900 (16:9) SVGs plus export.html.

Run:  python3 build_diagrams.py
Then open export.html in a browser to download PNGs for the slides.
Standard library only.
"""
import html
import json
import os

OUT = os.path.dirname(os.path.abspath(__file__))
FONT = "Segoe UI, Helvetica Neue, Arial, sans-serif"
MONO = "Consolas, Menlo, DejaVu Sans Mono, monospace"
INK = "#202124"
MUTED = "#5F6368"
PAL = {  # name: (stroke / accent, fill)
    "blue": ("#1A73E8", "#E8F0FE"),
    "green": ("#188038", "#E6F4EA"),
    "amber": ("#B06000", "#FEF7E0"),
    "grey": ("#5F6368", "#F1F3F4"),
    "red": ("#C5221F", "#FCE8E6"),
    "purple": ("#9334E6", "#F3E8FD"),
    "orange": ("#E8710A", "#FEEFE3"),
    "note": ("#B08900", "#FFF8D6"),
}
BLUE, GREEN, AMBER, GREY, RED = (PAL[k][0] for k in ("blue", "green", "amber", "grey", "red"))


def esc(s):
    return html.escape(str(s), quote=True)


def tw(t, size):
    """Rough text width for sans-serif."""
    return len(t) * size * 0.55


class D:
    def __init__(self, name, title, subtitle="", w=1600, h=900):
        self.name, self.title, self.subtitle, self.w, self.h = name, title, subtitle, w, h
        self.p, self.markers = [], set()

    # ---- primitives -------------------------------------------------------
    def add(self, s):
        self.p.append(s)

    def text(self, x, y, t, size=16, weight=400, color=INK, anchor="middle", font=FONT, italic=False):
        st = ' font-style="italic"' if italic else ""
        self.add(f'<text x="{x:.1f}" y="{y:.1f}" font-family="{font}" font-size="{size}" '
                 f'font-weight="{weight}" fill="{color}" text-anchor="{anchor}" xml:space="preserve"{st}>{esc(t)}</text>')

    def ctext(self, cx, cy, ls, size=16, weight=400, color=INK, lh=None, **kw):
        """Lines of text vertically centred on cy."""
        lh = lh or size * 1.35
        y0 = cy - (len(ls) - 1) * lh / 2 + size * 0.35
        for i, l in enumerate(ls):
            self.text(cx, y0 + i * lh, l, size, weight, color, **kw)

    def rect(self, x, y, w, h, color="grey", rx=12, dash=False, sw=2, fill=None, stroke=None):
        s, f = PAL[color]
        da = ' stroke-dasharray="9 6"' if dash else ""
        self.add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill or f}" '
                 f'stroke="{stroke or s}" stroke-width="{sw}"{da}/>')

    def box(self, x, y, w, h, title, ls=(), color="blue", tsize=19, lsize=15, dash=False, tcolor=INK):
        self.rect(x, y, w, h, color, dash=dash)
        lh = lsize * 1.4
        block = tsize + (len(ls) * lh + 4 if ls else 0)
        ty = y + (h - block) / 2 + tsize * 0.8
        self.text(x + w / 2, ty, title, tsize, 700, tcolor)
        for i, l in enumerate(ls):
            self.text(x + w / 2, ty + 6 + (i + 1) * lh, l, lsize, 400, MUTED)

    def _marker(self, color):
        mid = "ah" + color.strip("#")
        self.markers.add((mid, color))
        return mid

    def path(self, d, color=MUTED, sw=2.5, dash=False, end=True, start=False):
        mid = self._marker(color)
        da = ' stroke-dasharray="8 6"' if dash else ""
        me = f' marker-end="url(#{mid})"' if end else ""
        ms = f' marker-start="url(#{mid})"' if start else ""
        self.add(f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{sw}" '
                 f'stroke-linejoin="round" stroke-linecap="round"{da}{me}{ms}/>')

    def line(self, pts, color=MUTED, **kw):
        self.path("M " + " L ".join(f"{x} {y}" for x, y in pts), color, **kw)

    def label(self, x, y, t, size=14, color=MUTED, bg="#FFFFFF", weight=400, anchor="middle"):
        w = tw(t, size) + 14
        x0 = {"middle": x - w / 2, "start": x - 7, "end": x - w + 7}[anchor]
        self.add(f'<rect x="{x0:.1f}" y="{y - size * 0.95 - 3:.1f}" width="{w:.1f}" '
                 f'height="{size * 1.25 + 6:.1f}" rx="5" fill="{bg}"/>')
        self.text(x, y, t, size, weight, color, anchor)

    def cylinder(self, x, y, w, h, title, ls=(), color="amber"):
        s, f = PAL[color]
        ry = 16
        self.add(f'<path d="M{x},{y + ry} A{w / 2},{ry} 0 0 0 {x + w},{y + ry} L{x + w},{y + h - ry} '
                 f'A{w / 2},{ry} 0 0 1 {x},{y + h - ry} Z" fill="{f}" stroke="{s}" stroke-width="2"/>')
        self.add(f'<ellipse cx="{x + w / 2}" cy="{y + ry}" rx="{w / 2}" ry="{ry}" fill="{f}" '
                 f'stroke="{s}" stroke-width="2"/>')
        cy = y + 2 * ry + (h - 2 * ry) / 2 - 6
        self.ctext(x + w / 2, cy - len(ls) * 11, [title], 19, 700)
        for i, l in enumerate(ls):
            self.text(x + w / 2, cy - len(ls) * 11 + 30 + i * 21, l, 14, 400, MUTED)

    def diamond(self, cx, cy, w, h, ls, color="note", size=16):
        s, f = PAL[color]
        pts = f"{cx},{cy - h / 2} {cx + w / 2},{cy} {cx},{cy + h / 2} {cx - w / 2},{cy}"
        self.add(f'<polygon points="{pts}" fill="{f}" stroke="{s}" stroke-width="2"/>')
        self.ctext(cx, cy, ls, size, 600)

    def bullets(self, x, y, items, size=16, lh=None, color=INK, bullet="•"):
        lh = lh or size * 1.55
        for i, t in enumerate(items):
            if t.startswith("  "):  # continuation line
                self.text(x + 18, y + i * lh, t.strip(), size, 400, MUTED, "start")
            else:
                self.text(x, y + i * lh, bullet, size, 700, color, "start")
                self.text(x + 18, y + i * lh, t, size, 400, color, "start")

    # ---- sequence-diagram helpers ------------------------------------------
    def actors(self, actors, top=110, bottom=878):
        for x, name, sub, color in actors:
            self.line([(x, top + 58), (x, bottom)], "#BDC1C6", sw=1.6, dash=True, end=False)
            self.box(x - 108, top, 216, 58, name, [sub] if sub else [], color, tsize=17, lsize=13)

    def msg(self, x1, x2, y, t, ret=False, color=None, size=15):
        color = color or (GREY if ret else BLUE)
        self.line([(x1, y), (x2, y)], color, sw=2.2, dash=ret)
        self.label((x1 + x2) / 2, y - 9, t, size, INK if not ret else MUTED)

    def note(self, x, y, t, size=14):
        w = tw(t, size) + 26
        self.rect(x - w / 2, y - 16, w, 32, "note", rx=6, sw=1.5)
        self.text(x, y + 5, t, size, 500, INK)

    def frame(self, x, y, w, h, t):
        self.rect(x, y, w, h, "grey", rx=6, sw=1.6, fill="none")
        lw = tw(t, 14) + 22
        self.add(f'<path d="M{x},{y} h{lw} v18 l-10,10 h{-lw + 10} z" fill="#F1F3F4" '
                 f'stroke="{GREY}" stroke-width="1.6"/>')
        self.text(x + 10, y + 20, t, 14, 700, INK, "start")

    # ---- output ------------------------------------------------------------
    def svg(self):
        defs = "".join(
            f'<marker id="{m}" viewBox="0 0 10 10" refX="8.5" refY="5" markerWidth="5.5" '
            f'markerHeight="5.5" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="{c}"/></marker>'
            for m, c in sorted(self.markers))
        head = [
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.w}" height="{self.h}" '
            f'viewBox="0 0 {self.w} {self.h}">',
            f"<defs>{defs}</defs>",
            f'<rect width="{self.w}" height="{self.h}" fill="#FFFFFF"/>',
        ]
        t = D(self.name, "")
        t.text(60, 60, self.title, 34, 700, INK, "start")
        if self.subtitle:
            t.text(60, 92, self.subtitle, 18, 400, MUTED, "start")
        return "\n".join(head + t.p + self.p + ["</svg>"])

    def save(self):
        path = os.path.join(OUT, self.name + ".svg")
        with open(path, "w", encoding="utf-8") as f:
            f.write(self.svg())
        return self.name


# =============================================================================
def d01_overview():
    d = D("01-system-overview", "System Overview",
          "Central microservices coordinate; file bytes flow directly between peers")
    # control plane
    d.rect(80, 112, 1440, 253, "blue", dash=True, fill="#F8FBFF")
    d.text(108, 145, "CONTROL PLANE  ·  central microservices  ·  gRPC over TLS", 16, 700, BLUE, "start")
    for x, t, ls in [(130, "Auth Service", [":9091", "accounts · guests · JWT"]),
                     (450, "Catalog Service", [":9092", "manifests · access · tickets"]),
                     (770, "Tracker Service", [":9093", "live peers · chunk maps"])]:
        d.box(x, 168, 280, 112, t, ls, "blue")
    d.cylinder(1180, 158, 260, 152, "MongoDB", ["auth_db · catalog_db", "tracker_db"])
    for x in (270, 590, 910):
        d.line([(x, 280), (x, 330)], AMBER, end=False)
    d.line([(270, 330), (1310, 330), (1310, 314)], AMBER)
    d.label(1090, 335, "database-per-service", 13, AMBER, "#F8FBFF")

    # data plane
    d.rect(80, 448, 1440, 300, "green", dash=True, fill="#F7FCF8")
    xs = [130, 420, 710, 1000, 1290]
    names = [("Peer Node 1", "UI :8081 · gRPC :50051"), ("Peer Node 2", "UI :8082 · gRPC :50052"),
             ("Peer Node 3", "UI :8083 · gRPC :50053"), ("Peer Node 4", "UI :8084 · gRPC :50054"),
             ("Peer Node 10", "UI :8090 · gRPC :50060")]
    for x, (n, p) in zip(xs, names):
        d.line([(x + 100, 505), (x + 100, 367)], BLUE, sw=2.2)
        d.box(x, 505, 200, 130, n, [p, "shares + downloads"], "green", tsize=18, lsize=14)
    d.label(800, 418, "gRPC:  Login · RegisterFile · AuthorizeDownload · GetPeers · Announce · Heartbeat",
            15, BLUE, weight=600)
    for a, b in [(330, 420), (620, 710), (910, 1000)]:
        d.line([(a + 4, 570), (b - 4, 570)], GREEN, sw=3, start=True)
    d.text(1245, 578, "· · ·", 28, 700, MUTED)
    d.path("M 270 637 Q 520 722 770 637", GREEN, sw=3, start=True)
    d.path("M 560 637 Q 810 722 1060 637", GREEN, sw=3, start=True)
    d.label(665, 708, "chunks pulled from several peers in parallel (DownloadChunk stream)", 14, GREEN,
            "#F7FCF8", 600)
    d.text(108, 736, "DATA PLANE  ·  peer ↔ peer  ·  gRPC server-streaming", 16, 700, GREEN, "start")

    # browsers
    for x in xs:
        d.line([(x + 100, 637), (x + 100, 790)], GREY, sw=1.8, dash=True)
        d.box(x + 15, 792, 170, 56, "Browser", [f"web UI"], "grey", tsize=15, lsize=12)
    d.label(238, 775, "HTTP (localhost)", 13, MUTED, "#FFFFFF", anchor="start")
    return d.save()


def d02_components():
    d = D("02-components", "Microservices & gRPC Interfaces",
          "Every arrow except the browser link is gRPC (Protocol Buffers over HTTP/2 + TLS)")
    # browser
    d.box(255, 112, 300, 54, "Browser (HTML / JS)", [], "grey", tsize=17)
    d.line([(405, 166), (405, 226)], GREY, sw=2, dash=True)
    d.label(415, 200, "HTTP + Server-Sent Events · localhost", 13, MUTED, anchor="start")
    # peer container
    d.rect(50, 214, 710, 470, "green", dash=True, fill="#F7FCF8")
    d.text(72, 246, "PEER NODE  ·  Spring Boot  ·  one process per user", 15, 700, GREEN, "start")
    d.box(80, 262, 650, 62, "Spring MVC: PeerController + SseEmitter", ["pages · JSON API · live progress events (SSE)"], "green", 17, 13)
    d.box(80, 344, 315, 96, "Share Manager", ["split into 1 MB chunks", "SHA-256 per chunk + file"], "green", 17, 13)
    d.box(415, 344, 315, 96, "Download Manager", ["rarest-first scheduler", "verify · retry · failover"], "green", 17, 13)
    d.box(80, 460, 650, 62, "Chunk Store", [".part files · write at offset · .state.json for resume"], "green", 17, 13)
    d.box(80, 542, 315, 120, "PeerTransfer Server", ["gRPC :50051", "GetBitfield", "DownloadChunk → stream"], "green", 17, 13)
    d.box(415, 542, 315, 120, "Service Clients", ["generated gRPC stubs", "JWT interceptor (metadata)", "heartbeat timer · 10 s"], "green", 17, 13)
    # other peers
    d.box(80, 760, 315, 92, "Other Peer Nodes", ["same JAR, different ports"], "green", 17, 13, dash=True)
    d.line([(237, 666), (237, 756)], GREEN, sw=3, start=True)
    d.label(250, 716, "DownloadChunk (stream of 64 KB)", 13, GREEN, anchor="start")

    # services
    svc = [
        (112, "Auth Service  :9091", ["Register(username, password)", "Login(username, password)",
                                      "GuestLogin()  → guest JWT", "GetPublicKey()"], "auth_db", ["users"]),
        (320, "Catalog Service  :9092", ["RegisterFile(manifest, access)", "SearchFiles(query)", "ListMyFiles()",
                                         "UpdateAccess(fileId, visibility, users)", "DeleteFile(fileId)",
                                         "AuthorizeDownload(fileId) → ticket", "GetPublicKey()"],
         "catalog_db", ["files"]),
        (600, "Tracker Service  :9093", ["RegisterPeer(host, port)", "Heartbeat(peerId)", "Announce(fileId, bitfield)",
                                         "GetPeers(fileId, ticket)", "Leave(peerId)"],
         "tracker_db", ["peers (TTL) · swarm"]),
    ]
    for y, title, rpcs, db, cols in svc:
        h = 58 + len(rpcs) * 25
        d.rect(900, y, 400, h, "blue")
        d.text(920, y + 34, title, 18, 700, INK, "start")
        for i, r in enumerate(rpcs):
            d.text(924, y + 64 + i * 25, r, 14, 400, MUTED, "start", font=MONO)
        cy = y + h / 2
        d.cylinder(1370, cy - 62, 190, 124, db, cols)
        d.line([(1300, cy), (1366, cy)], AMBER, sw=2.2)
        d.line([(732, 602), (812, 602), (812, cy), (896, cy)], BLUE, sw=2.2)
    d.label(812, 528, "gRPC + TLS", 13, BLUE, weight=600)

    # legend
    lx, ly = 470, 770
    for i, (c, dash, t) in enumerate([(BLUE, False, "gRPC unary (control)"),
                                     (GREEN, False, "gRPC streaming (peer ↔ peer)"),
                                     (GREY, True, "HTTP, localhost only"),
                                     (AMBER, False, "MongoDB driver")]):
        yy = ly + i * 26
        d.line([(lx, yy), (lx + 46, yy)], c, sw=2.5, dash=dash)
        d.text(lx + 60, yy + 5, t, 14, 400, INK, "start")
    return d.save()


def d03_share():
    d = D("03-share-sequence", "Login & Share a File", "How a file becomes available to the network")
    B, P, A, C, T, M = 130, 400, 670, 940, 1210, 1470
    d.actors([(B, "Browser", "user", "grey"), (P, "Peer Node", "seeder", "green"),
              (A, "Auth Service", "", "blue"), (C, "Catalog Service", "", "blue"),
              (T, "Tracker Service", "", "blue"), (M, "MongoDB", "", "amber")])
    d.frame(320, 742, 1220, 128, "loop  every 10 s")
    d.msg(B, P, 202, "Login  |  Continue as guest")
    d.msg(P, A, 242, "Login(user, pass) | GuestLogin()")
    d.msg(A, P, 282, "JWT  (role USER | GUEST)", ret=True)
    d.msg(B, P, 330, "Share file + visibility")
    d.note(P, 372, "split into 1 MB chunks · SHA-256 each + whole-file hash")
    d.msg(P, C, 425, "RegisterFile(manifest, visibility, allowedUsers)")
    d.note(C, 465, "verify JWT · guest ⇒ PUBLIC only")
    d.msg(C, M, 510, "insert catalog_db.files")
    d.msg(C, P, 550, "FileInfo { fileId }", ret=True)
    d.msg(P, T, 598, "Announce(fileId, bitfield = all chunks)")
    d.msg(T, M, 638, "upsert tracker_db.swarm")
    d.msg(T, P, 678, "OK", ret=True)
    d.msg(P, B, 718, "Shared ✓  now seeding", ret=True)
    d.msg(P, T, 795, "Heartbeat(peerId)")
    d.msg(T, M, 840, "peers.lastSeen = now")
    return d.save()


def d04_download():
    d = D("04-download-sequence", "Parallel Download", "Authorize once, then pull chunks from several peers at the same time")
    B, Dn, C, T, PA, PB = 120, 380, 640, 900, 1160, 1440
    d.actors([(B, "Browser", "user", "grey"), (Dn, "Peer Node", "downloader", "green"),
              (C, "Catalog Service", "", "blue"), (T, "Tracker Service", "", "blue"),
              (PA, "Peer A", "seeder", "green"), (PB, "Peer B", "seeder", "green")])
    d.frame(300, 508, 1250, 190, "par  rarest-first · ≤ 4 peers · ≤ 2 in-flight each · every peer checks the ticket")
    d.msg(B, Dn, 200, "Search \"lecture.mp4\"")
    d.msg(Dn, C, 240, "SearchFiles(query)")
    d.msg(C, Dn, 280, "only files you may see", ret=True)
    d.msg(B, Dn, 325, "Download")
    d.msg(Dn, C, 365, "AuthorizeDownload(fileId)")
    d.msg(C, Dn, 405, "manifest + signed ticket", ret=True)
    d.msg(Dn, T, 448, "GetPeers(fileId, ticket)")
    d.msg(T, Dn, 488, "A: chunks 0–299 · B: all chunks", ret=True)
    d.msg(Dn, PA, 562, "DownloadChunk(fileId, #7, ticket)", color=GREEN)
    d.msg(Dn, PB, 602, "DownloadChunk(fileId, #8, ticket)", color=GREEN)
    d.msg(PA, Dn, 642, "stream 16 × 64 KB pieces", ret=True)
    d.msg(PB, Dn, 682, "stream 16 × 64 KB pieces", ret=True)
    d.note(Dn, 725, "SHA-256 ✓ → write at offset → save bitfield")
    d.msg(Dn, T, 768, "Announce(fileId, new chunks)  → I can serve them too")
    d.msg(Dn, B, 808, "progress (SSE)", ret=True)
    d.note(Dn, 850, "all chunks ✓ → whole-file hash ✓ → COMPLETED")
    return d.save()


def d05_parallel():
    d = D("05-parallel-chunks", "Multi-Peer Chunk Scheduling",
          "Example: 20-chunk file, three partial sources, rarest chunks first")
    x0, step, sz = 236, 45, 38
    owners = {"A": set(range(0, 10)), "B": set(range(5, 20)), "C": {2, 3, 12, 13, 17}}
    col = {"A": "blue", "B": "orange", "C": "purple"}
    for i in range(20):
        d.text(x0 + i * step + sz / 2, 168, str(i), 12, 600, MUTED)
    rows = [("A", 180, "Peer A", "has 0–9"), ("B", 232, "Peer B", "has 5–19"), ("C", 284, "Peer C", "has 2,3,12,13,17")]
    for k, y, n, sub in rows:
        d.text(60, y + 18, n, 17, 700, PAL[col[k]][0], "start")
        d.text(60, y + 36, sub, 12, 400, MUTED, "start")
        for i in range(20):
            if i in owners[k]:
                d.rect(x0 + i * step, y, sz, sz, col[k], rx=6, sw=1.5)
            else:
                d.rect(x0 + i * step, y, sz, sz, "grey", rx=6, sw=1, fill="#FFFFFF", stroke="#DADCE0")
    # availability
    d.text(60, 365, "copies", 17, 700, INK, "start")
    d.text(60, 383, "in swarm", 12, 400, MUTED, "start")
    counts = [sum(i in owners[k] for k in owners) for i in range(20)]
    for i, c in enumerate(counts):
        x = x0 + i * step
        if c == 1:
            d.rect(x, 346, sz, sz, "red", rx=19, sw=1.5)
            d.text(x + sz / 2, 371, "1", 16, 700, RED)
        else:
            d.text(x + sz / 2, 371, str(c), 16, 500, MUTED)
    d.label(x0 + 10 * step, 420, "red = only one peer has it → fetch these first (rarest-first)", 14, RED, weight=600)

    # downloader
    assign = {0: "A", 1: "A", 2: "C", 3: "C", 4: "A", 5: "B", 6: "A", 7: "B", 8: "A", 9: "B",
              10: "B", 11: "B", 12: "C", 13: "C", 14: "B", 15: "B", 16: "B", 17: "C", 18: "B", 19: "B"}
    done = {0, 1, 4, 10, 11, 14}
    inflight = {15, 16, 18, 19, 2, 6}
    y = 470
    d.text(60, y + 22, "You", 19, 700, GREEN, "start")
    d.text(60, y + 42, "(Peer D)", 13, 400, MUTED, "start")
    for i in range(20):
        x = x0 + i * step
        s, f = PAL[col[assign[i]]]
        if i in done:
            d.rect(x, y, sz, 48, col[assign[i]], rx=6, sw=2, fill=s)
            d.text(x + sz / 2, y + 31, "✓", 18, 700, "#FFFFFF")
        elif i in inflight:
            d.rect(x, y, sz, 48, col[assign[i]], rx=6, sw=2.5, dash=True, fill=f)
            d.text(x + sz / 2, y + 31, "↓", 18, 700, s)
        else:
            d.rect(x, y, sz, 48, "grey", rx=6, sw=1.2, fill="#FFFFFF", stroke="#BDC1C6")
    # legend
    ly = 560
    items = [("solid", "verified & saved"), ("dash", "in flight"), ("empty", "still missing")]
    lx = x0
    for kind, t in items:
        if kind == "solid":
            d.rect(lx, ly - 15, 22, 22, "blue", rx=4, fill=BLUE)
        elif kind == "dash":
            d.rect(lx, ly - 15, 22, 22, "blue", rx=4, dash=True)
        else:
            d.rect(lx, ly - 15, 22, 22, "grey", rx=4, fill="#FFFFFF", stroke="#BDC1C6")
        d.text(lx + 32, ly + 2, t, 14, 400, INK, "start")
        lx += 52 + tw(t, 14)
    d.text(lx + 10, ly + 2, "colour = peer it came from", 14, 600, MUTED, "start")

    # pipeline
    d.text(60, 640, "Per-chunk pipeline", 18, 700, INK, "start")
    steps = [("Pick", ["rarest missing", "chunk"]), ("Choose peer", ["least-busy peer", "that has it"]),
             ("Stream", ["DownloadChunk", "64 KB pieces"]), ("Verify", ["SHA-256 ==", "manifest?"]),
             ("Commit", ["write @ offset", "set bit · Announce"])]
    bw, gap, px = 190, 34, 60
    for i, (t, ls) in enumerate(steps):
        x = px + i * (bw + gap)
        d.box(x, 660, bw, 92, t, ls, "note" if t == "Verify" else "green", 17, 13)
        if i < len(steps) - 1:
            d.line([(x + bw + 3, 706), (x + bw + gap - 3, 706)], GREEN, sw=2.5)
    vx = px + 3 * (bw + gap) + bw / 2
    d.line([(vx, 752), (vx, 800), (px + bw / 2, 800), (px + bw / 2, 756)], RED, sw=2.2, dash=True)
    d.label((vx + px + bw / 2) / 2, 805, "✗ mismatch → discard · strike the peer · request from another peer", 14, RED, weight=600)

    # rules panel
    d.rect(1180, 150, 360, 700, "blue", fill="#F8FBFF")
    d.text(1204, 188, "Scheduler rules", 20, 700, BLUE, "start")
    d.bullets(1204, 228, [
        "Rarest-first order",
        "≤ 4 peers in parallel",
        "≤ 2 requests in flight per peer",
        "Timeout 15 s → reassign chunk",
        "3 bad hashes → ignore that peer",
        "Ask peers for bitfields every 3 s,",
        "  tracker for new peers every 20 s",
        "Every verified chunk is",
        "  served to others at once",
        "Endgame: request the last few",
        "  chunks from 2 peers",
        "Bitfield saved after each chunk",
        "  → downloads can resume",
    ], 16, 31)
    return d.save()


def d06_lifecycle():
    d = D("06-download-lifecycle", "Download Lifecycle & Resume",
          "State machine of one download inside a peer node")
    S = {"QUEUED": (80, 190), "AUTHORIZING": (360, 190), "FINDING PEERS": (640, 190),
         "DOWNLOADING": (920, 190), "VERIFYING FILE": (1240, 190), "COMPLETED": (1240, 420),
         "PAUSED": (920, 420), "STALLED": (640, 420), "RESUMING": (360, 420)}
    sub = {"QUEUED": "waiting to start", "AUTHORIZING": "get manifest + ticket",
           "FINDING PEERS": "Tracker.GetPeers", "DOWNLOADING": "chunks in parallel",
           "VERIFYING FILE": "whole-file SHA-256", "COMPLETED": "now seeding",
           "PAUSED": "user paused", "STALLED": "no peers online", "RESUMING": "load .state.json"}
    colr = {"COMPLETED": "green", "STALLED": "orange", "PAUSED": "grey", "RESUMING": "purple"}
    W, H = 200, 70
    for n, (x, y) in S.items():
        d.box(x, y, W, H, n, [sub[n]], colr.get(n, "blue"), 16, 13)
    # start marker
    d.add(f'<circle cx="40" cy="225" r="11" fill="{INK}"/>')
    d.line([(52, 225), (76, 225)], INK, sw=2.2)

    def lab(x, y, t, a="middle", c=INK):
        d.label(x, y, t, 13, c, weight=600, anchor=a)

    d.line([(282, 225), (356, 225)], INK, sw=2.2); lab(320, 212, "start")
    d.line([(562, 225), (636, 225)], INK, sw=2.2); lab(600, 212, "ticket ✓")
    d.line([(842, 225), (916, 225)], INK, sw=2.2); lab(880, 212, "peers ✓")
    d.line([(1122, 225), (1236, 225)], INK, sw=2.2); lab(1180, 212, "all chunks ✓")
    d.line([(1340, 262), (1340, 416)], GREEN, sw=2.2); lab(1350, 340, "hash ✓", "start", GREEN)
    d.path("M 1290 188 C 1262 122, 1098 122, 1070 186", RED, sw=2.2)
    lab(1180, 130, "hash ✗ → re-fetch bad chunks", c=RED)
    d.line([(990, 262), (990, 416)], INK, sw=2.2); lab(982, 340, "pause", "end")
    d.line([(1050, 418), (1050, 264)], INK, sw=2.2); lab(1058, 340, "resume", "start")
    d.line([(950, 262), (812, 416)], "#E8710A", sw=2.2); lab(868, 318, "peers gone", "end", "#E8710A")
    d.line([(690, 418), (690, 264)], "#E8710A", sw=2.2); lab(682, 340, "retry 15 s", "end", "#E8710A")
    d.line([(460, 418), (460, 264)], PAL["purple"][0], sw=2.2); lab(470, 340, "state loaded", "start", PAL["purple"][0])
    d.add(f'<circle cx="150" cy="455" r="11" fill="none" stroke="{INK}" stroke-width="2.5"/>'
          f'<circle cx="150" cy="455" r="6" fill="{INK}"/>')
    d.line([(163, 455), (356, 455)], INK, sw=2.2)
    lab(258, 442, "node restart")
    lab(258, 480, "(crash / app reopened)", c=MUTED)

    # failed
    d.box(1240, 600, 200, 70, "FAILED", ["needs user action"], "red", 16, 13)
    d.text(1340, 700, "from any state: access revoked,", 13, 400, MUTED)
    d.text(1340, 719, "file deleted, disk full", 13, 400, MUTED)

    # persistence panel
    d.rect(80, 578, 1060, 280, "note", fill="#FFFDF2")
    d.text(104, 614, "Saved on disk for each download", 19, 700, INK, "start")
    d.text(104, 652, "peers/peer-4/downloads/", 16, 600, INK, "start", font=MONO)
    d.text(130, 682, "lecture.mp4.part", 16, 400, INK, "start", font=MONO)
    d.text(420, 682, "pre-allocated; chunk i written at i × 1 MB", 15, 400, MUTED, "start")
    d.text(130, 710, "<fileId>.state.json", 16, 400, INK, "start", font=MONO)
    d.text(420, 710, "{ manifest, bitfield, status, updatedAt }", 15, 400, MUTED, "start")
    d.bullets(104, 754, [
        "state.json rewritten atomically (write temp file → rename) after every verified chunk",
        "On restart: re-hash chunks marked done (catches half-written data) → get a new ticket",
        "Only the missing chunks are downloaded again; nothing restarts from zero",
    ], 15, 30)
    return d.save()


def d07_access_matrix():
    d = D("07-access-matrix", "File Visibility & Access",
          "Account holders choose who can find and download each file")
    cols = [("Visibility", 380), ("Owner", 220), ("Shared-with users", 220), ("Other users", 220), ("Guests", 240)]
    x0, y0, hh, rh = 160, 130, 64, 108
    x = x0
    d.rect(x0, y0, 1280, hh + 3 * rh, "grey", rx=14, fill="#FFFFFF", stroke="#DADCE0")
    d.add(f'<path d="M{x0} {y0 + 14} a14 14 0 0 1 14 -14 h1252 a14 14 0 0 1 14 14 v{hh - 14} h-1280 z" fill="#F1F3F4"/>')
    for name, w in cols:
        d.text(x + w / 2, y0 + 40, name, 18, 700, INK)
        x += w
    rows = [("PUBLIC", "anyone, including guests", "green", [1, 1, 1, 1]),
            ("REGISTERED", "any logged-in account", "blue", [1, 1, 1, 0]),
            ("PRIVATE", "owner + chosen usernames", "purple", [1, 1, 0, 0])]
    for r, (name, sub, c, vals) in enumerate(rows):
        y = y0 + hh + r * rh
        if r:
            d.line([(x0, y), (x0 + 1280, y)], "#DADCE0", sw=1.2, end=False)
        d.rect(x0 + 30, y + 30, 150, 46, c, rx=23)
        d.text(x0 + 105, y + 60, name, 16, 700, PAL[c][0])
        d.text(x0 + 200, y + 60, sub, 14, 400, MUTED, "start")
        x = x0 + 380
        for v, (_, w) in zip(vals, cols[1:]):
            cx, cy = x + w / 2, y + rh / 2
            if v:
                d.add(f'<circle cx="{cx}" cy="{cy}" r="22" fill="{PAL["green"][1]}" stroke="{GREEN}" stroke-width="2"/>')
                d.text(cx, cy + 8, "✓", 24, 700, GREEN)
            else:
                d.add(f'<circle cx="{cx}" cy="{cy}" r="22" fill="{PAL["red"][1]}" stroke="{RED}" stroke-width="2"/>')
                d.text(cx, cy + 8, "✗", 22, 700, RED)
            x += w
    cards = [("Owner decides", ["Pick visibility when sharing,", "change it any time with", "UpdateAccess(fileId, …)"], "blue"),
             ("Guests (anonymous)", ["Can search & download", "PUBLIC files and share files", "as PUBLIC only"], "grey"),
             ("Search is filtered", ["You never even see a file", "you are not allowed", "to download"], "green")]
    for i, (t, ls, c) in enumerate(cards):
        x = x0 + i * 440
        d.rect(x, 580, 400, 200, c)
        d.text(x + 26, 622, t, 20, 700, PAL[c][0], "start")
        for j, l in enumerate(ls):
            d.text(x + 26, 662 + j * 28, l, 16, 400, INK, "start")
    return d.save()


def d08_authorization():
    d = D("08-authorization-flow", "Authorizing a Download",
          "Catalog decides once; the signed ticket is then checked by the Tracker and every peer")
    cx, dw, dh = 430, 300, 82
    d.box(cx - 200, 118, 400, 56, "AuthorizeDownload(fileId)", ["caller's JWT in gRPC metadata"], "blue", 17, 13)
    ys = [240, 350, 460, 570, 680]
    qs = [["JWT valid?"], ["visibility", "= PUBLIC?"], ["caller is", "a guest?"], ["visibility", "= REGISTERED?"],
          ["owner or in", "allowedUsers?"]]
    # (right-branch label, terminal text, colour); down-branch label
    right = [("no", "✗ UNAUTHENTICATED", "red"), ("yes", "✓ issue Download Ticket", "green"),
             ("yes", "✗ PERMISSION_DENIED", "red"), ("yes", "✓ issue Download Ticket", "green"),
             ("yes", "✓ issue Download Ticket", "green")]
    down = ["yes", "no", "no", "no (PRIVATE)", "no"]
    d.line([(cx, 174), (cx, ys[0] - dh / 2 - 3)], INK, sw=2.2)
    for i, (y, q) in enumerate(zip(ys, qs)):
        d.diamond(cx, y, dw, dh, q, size=15)
        rl, tt, tc = right[i]
        d.line([(cx + dw / 2, y), (676, y)], PAL[tc][0], sw=2.2)
        d.label(cx + dw / 2 + 42, y - 9, rl, 13, PAL[tc][0], weight=700)
        d.box(680, y - 24, 300, 48, tt, [], tc, 16, tcolor=PAL[tc][0])
        ny = ys[i + 1] - dh / 2 - 3 if i < 4 else 772
        d.line([(cx, y + dh / 2), (cx, ny)], INK, sw=2.2)
        d.label(cx + 10, (y + dh / 2 + ny) / 2 + 5, down[i], 13, INK, weight=700, anchor="start")
    d.box(cx - 150, 776, 300, 48, "✗ PERMISSION_DENIED", [], "red", 16, tcolor=RED)

    # right panel
    px = 1060
    d.rect(px, 118, 480, 260, "green", fill="#F7FCF8")
    d.text(px + 24, 154, "Download Ticket = signed JWT", 19, 700, GREEN, "start")
    for j, l in enumerate(["{", '  "iss": "catalog-service",', '  "sub": "alice",', '  "fileId": "7f3c…",',
                           '  "exp": now + 30 min', "}"]):
        d.text(px + 30, 192 + j * 27, l, 15, 400, INK, "start", font=MONO)
    d.text(px + 24, 362, "Signed with Catalog's private key (RS256)", 13, 400, MUTED, "start")

    d.text(px, 430, "Checked at three points", 19, 700, INK, "start")
    pts = [("1  Catalog Service", "issues it only after the checks on the left"),
           ("2  Tracker · GetPeers", "no ticket → swarm of the file stays hidden"),
           ("3  Every peer · DownloadChunk", "no ticket → chunk is not served")]
    for j, (t, s) in enumerate(pts):
        y = 452 + j * 112
        d.rect(px, y, 480, 84, "blue")
        d.text(px + 22, y + 34, t, 17, 700, INK, "start")
        d.text(px + 22, y + 60, s, 14, 400, MUTED, "start")
        if j < 2:
            d.line([(px + 240, y + 86), (px + 240, y + 108)], BLUE, sw=2.2)
    d.text(px, 815, "Knowing a peer's address is not enough to fetch a private file.", 14, 600, MUTED, "start")
    return d.save()


def table(d, x, y, w, title, rows, color, sub=""):
    rh = 30
    h = 52 + rh * len(rows) + 8
    s, f = PAL[color]
    d.rect(x, y, w, h, color, fill="#FFFFFF")
    d.add(f'<path d="M{x} {y + 12} a12 12 0 0 1 12 -12 h{w - 24} a12 12 0 0 1 12 12 v40 h{-w} z" fill="{f}"/>')
    d.add(f'<line x1="{x}" y1="{y + 52}" x2="{x + w}" y2="{y + 52}" stroke="{s}" stroke-width="1.5"/>')
    d.text(x + 18, y + 33, title, 18, 700, s, "start", font=MONO)
    if sub:
        d.text(x + w - 16, y + 33, sub, 13, 600, s, "end")
    ys = {}
    for i, (k, v) in enumerate(rows):
        yy = y + 52 + 22 + i * rh
        d.text(x + 18, yy, k, 15, 600, INK, "start", font=MONO)
        d.text(x + w - 16, yy, v, 14, 400, MUTED, "end")
        ys[k] = yy - 5
    return h, ys


def d09_data_model():
    d = D("09-data-model", "Data Model (MongoDB)", "Database-per-service: each service owns its collections")
    _, uy = table(d, 60, 130, 420, "auth_db.users", [
        ("_id", "ObjectId"), ("username", "string · unique"), ("passwordHash", "bcrypt"), ("createdAt", "date")],
        "blue", "Auth")
    _, fy = table(d, 560, 130, 480, "catalog_db.files", [
        ("_id", "fileId · UUID"), ("name", "string · indexed"), ("sizeBytes", "long"),
        ("chunkSize", "int · 1 MB"), ("chunkCount", "int"), ("chunkHashes", "[SHA-256 × n]"),
        ("fileHash", "SHA-256"), ("ownerId", "users._id | null (guest)"), ("ownerName", "string"),
        ("visibility", "PUBLIC|REGISTERED|PRIVATE"), ("allowedUsers", "[username]"), ("createdAt", "date")],
        "blue", "Catalog")
    _, py = table(d, 1120, 130, 420, "tracker_db.peers", [
        ("_id", "peerId · UUID"), ("principal", "user id | guest id"), ("host", "string"),
        ("grpcPort", "int"), ("lastSeen", "date · TTL 30 s")], "amber", "Tracker")
    _, sy = table(d, 1120, 420, 420, "tracker_db.swarm", [
        ("_id", "ObjectId"), ("fileId", "files._id"), ("peerId", "peers._id"),
        ("bitfield", "BinData · 1 bit/chunk"), ("haveCount", "int"), ("updatedAt", "date")], "amber", "Tracker")
    _, ly = table(d, 60, 420, 420, "<fileId>.state.json", [
        ("fileId", "UUID"), ("manifest", "copy from Catalog"), ("bitfield", "chunks verified"),
        ("status", "DOWNLOADING | PAUSED…"), ("updatedAt", "date")], "green", "peer disk")

    # relations
    d.line([(556, fy["ownerId"]), (520, fy["ownerId"]), (520, uy["_id"]), (484, uy["_id"])], GREY, sw=1.8, dash=True)
    d.label(520, 365, "logical ref", 12, MUTED)
    d.line([(1116, sy["fileId"]), (1080, sy["fileId"]), (1080, fy["_id"]), (1044, fy["_id"])], GREY, sw=1.8, dash=True)
    d.line([(1530, sy["peerId"]), (1556, sy["peerId"]), (1556, py["_id"]), (1544, py["_id"])], GREY, sw=1.8, dash=True)
    d.line([(484, ly["manifest"]), (556, ly["manifest"])], GREY, sw=1.8, dash=True)
    d.label(520, ly["manifest"] - 12, "copy", 12, MUTED)

    d.rect(560, 685, 480, 175, "note", fill="#FFFDF2")
    d.text(582, 717, "Why MongoDB fits", 18, 700, INK, "start")
    d.bullets(582, 749, [
        "A manifest + its hash array is one document",
        "TTL index drops dead peers automatically",
        "Bitfield stored compactly as BinData",
        "No cross-service joins: refs are just ids",
    ], 15, 29)
    d.rect(1120, 685, 420, 175, "grey", fill="#FFFFFF", stroke="#DADCE0")
    d.text(1142, 717, "Indexes", 18, 700, INK, "start")
    d.bullets(1142, 749, [
        "users: { username: 1 } unique",
        "files: { name: 1 }, { ownerId: 1 }",
        "peers: { lastSeen: 1 } TTL 30 s",
        "swarm: { fileId: 1, peerId: 1 } unique",
    ], 15, 29)
    return d.save()


def d10_deployment():
    d = D("10-deployment", "Deployment on One Machine",
          "Spring Boot jars (java -jar) on localhost; only MongoDB runs in a Docker container")
    d.rect(50, 112, 1500, 770, "grey", fill="#FBFBFC", stroke="#BDC1C6", dash=True)
    d.text(76, 146, "localhost  ·  Linux / Windows / macOS  ·  Java 21", 16, 700, MUTED, "start")
    d.cylinder(100, 170, 240, 150, "MongoDB", [":27017", "Docker: p2p-mongo (mongo:7)"])
    for x, n, p in [(420, "auth-service", "gRPC :9091"), (740, "catalog-service", "gRPC :9092"),
                    (1060, "tracker-service", "gRPC :9093")]:
        d.box(x, 180, 280, 110, n, [p, f"Spring Boot · {n}.jar"], "blue", 18, 14)
    for x in (560, 880, 1200):
        d.line([(x, 290), (x, 334)], AMBER, end=False)
    d.line([(1200, 334), (220, 334), (220, 324)], AMBER)
    d.label(700, 338, "MongoDB driver", 13, AMBER, "#FBFBFC")

    d.text(76, 400, "Peer nodes: same JAR, started 10× with different --peer.id, ports and data folder",
           16, 700, GREEN, "start")
    xs = [90, 380, 670, 960, 1250]
    for r in range(2):
        for c in range(5):
            n = r * 5 + c + 1
            x, y = xs[c], 425 + r * 200
            d.box(x, y, 260, 150, f"Peer {n}",
                  [f"web UI  :{8080 + n}", f"gRPC  :{50050 + n}", f"peers/peer-{n}/"], "green", 18, 14)
            if c < 4:
                d.line([(x + 264, y + 75), (x + 286, y + 75)], GREEN, sw=2.5, start=True)
        if r == 0:
            for c in range(5):
                d.line([(xs[c] + 130, 579), (xs[c] + 130, 621)], GREEN, sw=2.5, start=True)
    d.line([(1520, 425), (1520, 235), (1344, 235)], BLUE, sw=2.5, start=True)
    d.label(1520, 380, "gRPC", 13, BLUE, "#FBFBFC", weight=700)
    d.rect(76, 805, 1450, 58, "note", rx=10, fill="#FFFDF2")
    d.text(100, 841, "Start order:   ① scripts/start-mongo.sh   →   ② scripts/start-services.sh   →   ③ scripts/start-peers.sh 10"
                     "   →   open http://localhost:8081 … :8090", 16, 600, INK, "start")
    return d.save()


# =============================================================================
EXPORT_HTML = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<title>P2P Architecture Diagrams</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
  body{font-family:system-ui,Segoe UI,Arial,sans-serif;margin:0;background:#F1F3F4;color:#202124}
  header{position:sticky;top:0;background:#fff;border-bottom:1px solid #DADCE0;padding:14px 24px;
         display:flex;gap:12px;align-items:center;flex-wrap:wrap;z-index:1}
  h1{font-size:20px;margin:0 auto 0 0}
  button,select{font:inherit;padding:7px 14px;border-radius:8px;border:1px solid #DADCE0;background:#fff;cursor:pointer}
  button.primary{background:#1A73E8;color:#fff;border-color:#1A73E8}
  main{max-width:1240px;margin:24px auto;padding:0 16px;display:grid;gap:28px}
  figure{margin:0;background:#fff;border:1px solid #DADCE0;border-radius:12px;overflow:hidden}
  figure img{width:100%;display:block}
  figcaption{display:flex;gap:10px;align-items:center;padding:10px 16px;border-top:1px solid #DADCE0}
  figcaption span{margin-right:auto;font-weight:600}
</style></head><body>
<header><h1>Architecture diagrams (16:9)</h1>
  <label>PNG size <select id="scale"><option value="1">1600×900</option><option value="2" selected>3200×1800</option>
  <option value="3">4800×2700</option></select></label>
  <button class="primary" onclick="downloadAll()">Download all PNGs</button></header>
<main id="list"></main>
<script>
const SVGS = __DATA__;
const urls = {};
for (const [n, s] of Object.entries(SVGS)) urls[n] = URL.createObjectURL(new Blob([s], {type: 'image/svg+xml'}));
const list = document.getElementById('list');
for (const n of Object.keys(SVGS)) {
  const f = document.createElement('figure');
  f.innerHTML = `<img alt="${n}" src="${urls[n]}"><figcaption><span>${n}</span>
    <button onclick="downloadPng('${n}')">PNG</button><a href="${urls[n]}" download="${n}.svg"><button>SVG</button></a></figcaption>`;
  list.appendChild(f);
}
async function toPng(n, scale) {
  scale = scale || +document.getElementById('scale').value;
  const img = new Image(); img.src = urls[n]; await img.decode();
  const c = document.createElement('canvas'); c.width = 1600 * scale; c.height = 900 * scale;
  c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
  return new Promise(r => c.toBlob(r, 'image/png'));
}
function save(blob, name) { const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = name; a.click(); }
async function downloadPng(n) { save(await toPng(n), n + '.png'); }
async function downloadAll() { for (const n of Object.keys(SVGS)) { await downloadPng(n); await new Promise(r => setTimeout(r, 300)); } }
// used by the local save server when regenerating docs/diagrams/png/
window.saveAllToServer = async (scale) => { const out = [];
  for (const n of Object.keys(SVGS)) { const b = await toPng(n, scale);
    const r = await fetch('/save/' + n + '.png', {method: 'POST', body: b}); out.push(n + ':' + r.status); }
  return out; };
</script></body></html>
"""


def main():
    names = [f() for f in (d01_overview, d02_components, d03_share, d04_download, d05_parallel,
                           d06_lifecycle, d07_access_matrix, d08_authorization, d09_data_model, d10_deployment)]
    data = {}
    for n in names:
        with open(os.path.join(OUT, n + ".svg"), encoding="utf-8") as f:
            data[n] = f.read()
    page = EXPORT_HTML.replace("__DATA__", json.dumps(data).replace("</", "<\\/"))
    with open(os.path.join(OUT, "export.html"), "w", encoding="utf-8") as f:
        f.write(page)
    print("wrote", len(names), "diagrams + export.html to", OUT)


if __name__ == "__main__":
    main()
