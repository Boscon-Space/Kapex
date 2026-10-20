#!/usr/bin/env python3
"""Runs on the Pi (piped over ssh by tools/pi_camrec.sh check). Judges the last
finished camrec segment and the file still being written.

Pass conditions, each able to fail:
  finished: RIFF size equals file size - 8; idx1 entries == avih frame count ==
            the journal's frame count; dropped <= 1 % of frames (limit stated
            here, chosen 2026-10-20); frames within 10 % of seconds * fps.
  open:     avih frame count > 0 and the bytes beyond the last checkpoint are
            under 4 MB (one checkpoint every 2 s at ~1 MB/s): what a brownout
            would leave behind.
"""
import glob, json, os, struct, subprocess, sys

DROP_LIMIT = 0.01
FRAME_TOL = 0.10
OPEN_TAIL_LIMIT = 4_000_000

def u32(b, at): return struct.unpack("<I", b[at:at + 4])[0]

def journal_segments():
    out = subprocess.run(["journalctl", "-u", "hab1-camrec.service", "-n", "400", "--no-pager", "-o", "cat"], capture_output=True, text=True).stdout
    log = os.path.expanduser("~/footage/record.log")
    if os.path.exists(log): out += open(log).read()
    segs = []
    for line in out.splitlines():
        if line.startswith("{") and '"kind":"segment"' in line:
            try: segs.append(json.loads(line))
            except ValueError: pass
    return segs

def check_finished():
    segs = [s for s in journal_segments() if os.path.exists(s["path"])]
    if not segs: return False, "no finished segment in the journal"
    s = max(segs, key=lambda s: s["t"]); b = open(s["path"], "rb").read()
    riff_ok = b[:4] == b"RIFF" and u32(b, 4) == len(b) - 8
    idx = b.rfind(b"idx1"); n_idx = u32(b, idx + 4) // 16 if idx > 0 else -1
    tot = u32(b, 48)
    drop_ok = s["dropped"] <= DROP_LIMIT * s["frames"]
    frames_ok = abs(s["frames"] - s["expected_frames"]) <= FRAME_TOL * s["expected_frames"]
    ok = riff_ok and n_idx == s["frames"] == tot and drop_ok and frames_ok
    return ok, (f"{os.path.basename(s['path'])} frames={s['frames']} expected={s['expected_frames']} (tol {FRAME_TOL:.0%}) "
                f"dropped={s['dropped']} (limit {DROP_LIMIT:.0%} = {int(DROP_LIMIT * s['frames'])}) idx1={n_idx} avih_total={tot} "
                f"riff_size_ok={riff_ok} bytes={len(b)} seconds={s['seconds']} free_mb={s['free_mb']}")

def check_open():
    files = sorted(glob.glob(os.path.expanduser("~/footage/*.avi")), key=os.path.getmtime)
    if not files: return None, "no files"
    p = files[-1]; b = open(p, "rb").read()
    if b.rfind(b"idx1") > 0: return None, f"{os.path.basename(p)} is finished (has idx1); nothing open"
    tot = u32(b, 48); declared = u32(b, 4) + 8; tail = len(b) - declared
    ok = tot > 0 and 0 <= tail < OPEN_TAIL_LIMIT
    return ok, f"{os.path.basename(p)} avih_total_frames={tot} declared_bytes={declared} actual_bytes={len(b)} beyond_last_checkpoint={tail} (limit {OPEN_TAIL_LIMIT})"

if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "finished"
    ok, msg = check_finished() if mode == "finished" else check_open()
    print(("OK" if ok else "SKIP" if ok is None else "FAIL") + ": " + msg)
    sys.exit(0 if ok or ok is None else 1)
