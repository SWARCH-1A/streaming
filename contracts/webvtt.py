"""Offline conformance checker for the bounded P2 WebVTT profile, not a VOD service.

The provider must validate authorization and the authoritative media timeline separately.
See docs/spec-p2/spec_22_subtitles.md. No upload, persistence or HTML rendering occurs here.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re

MAX_BYTES = 1_048_576
MAX_CUES = 10_000
MAX_SAFE_INTEGER = 9_007_199_254_740_991
TIMESTAMP = r"(?:[0-9]{2,}:)?[0-9]{2}:[0-9]{2}\.[0-9]{3}"
TIMING = re.compile(rf"^({TIMESTAMP})[ \t]+-->[ \t]+({TIMESTAMP})$")
ENTITY = re.compile(r"&(?!amp;|lt;|gt;|nbsp;|lrm;|rlm;)")


class InvalidTrack(ValueError):
    """Stable error code without echoing uploaded text or private data."""


@dataclass(frozen=True)
class Cue:
    start_ms: int
    end_ms: int
    text: str


def timestamp_ms(value: str) -> int:
    parts = value.split(":")
    hours, minutes, seconds = ("0", *parts) if len(parts) == 2 else parts
    whole, millis = seconds.split(".")
    if int(minutes) > 59 or int(whole) > 59:
        raise InvalidTrack("INVALID_TIMESTAMP")
    # Bound length before integer conversion, including pathological input.
    if len(hours) > 10:
        raise InvalidTrack("INVALID_TIMESTAMP")
    return ((int(hours) * 60 + int(minutes)) * 60 + int(whole)) * 1000 + int(millis)


def validate_webvtt(data: bytes, *, duration_ms: int) -> tuple[Cue, ...]:
    if type(duration_ms) is not int or not 0 < duration_ms <= MAX_SAFE_INTEGER:
        raise InvalidTrack("INVALID_DURATION")
    if len(data) > MAX_BYTES:
        raise InvalidTrack("TRACK_TOO_LARGE")
    try:
        text = data.decode("utf-8-sig")
    except UnicodeDecodeError as error:
        raise InvalidTrack("INVALID_UTF8") from error
    if any((ord(c) < 32 and c not in "\r\n\t") or c == "\x7f" for c in text):
        raise InvalidTrack("INVALID_CONTROL_CHARACTER")
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    lines = text.split("\n")
    if not re.fullmatch(r"WEBVTT(?:[ \t][^\n]*)?", lines[0]) or "-->" in lines[0]:
        raise InvalidTrack("INVALID_HEADER")
    if len(lines) < 2 or lines[1] != "":
        raise InvalidTrack("MISSING_HEADER_SEPARATOR")
    blocks = re.split(r"\n{2,}", "\n".join(lines[2:]).strip("\n"))
    cues: list[Cue] = []
    previous_start = -1
    for block in blocks:
        if not block:
            continue
        rows = block.split("\n")
        if re.match(r"^NOTE(?:[ \t]|$)", rows[0]):
            if "-->" in block:
                raise InvalidTrack("INVALID_NOTE")
            continue
        if rows[0] in ("STYLE", "REGION"):
            raise InvalidTrack("UNSUPPORTED_WEBVTT_FEATURE")
        timing_index = 0 if "-->" in rows[0] else 1
        if len(rows) <= timing_index + 1:
            raise InvalidTrack("INVALID_CUE")
        match = TIMING.fullmatch(rows[timing_index])
        if not match:
            raise InvalidTrack("INVALID_CUE_TIMING")
        start, end = (timestamp_ms(v) for v in match.groups())
        if not 0 <= start < end <= duration_ms or start < previous_start:
            raise InvalidTrack("INVALID_CUE_INTERVAL")
        payload = "\n".join(rows[timing_index + 1:])
        if not payload.strip():
            raise InvalidTrack("EMPTY_CUE")
        # Text-only profile: entities are permitted, tags/settings/styles are not.
        if "<" in payload or "-->" in payload or ENTITY.search(payload):
            raise InvalidTrack("UNSUPPORTED_CUE_TEXT")
        cues.append(Cue(start, end, payload))
        previous_start = start
        if len(cues) > MAX_CUES:
            raise InvalidTrack("TOO_MANY_CUES")
    if not cues:
        raise InvalidTrack("EMPTY_TRACK")
    return tuple(cues)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file", type=Path)
    parser.add_argument("--duration-ms", type=int, required=True)
    args = parser.parse_args()
    try:
        # A large file is rejected without reading its entire content into memory.
        with args.file.open("rb") as source:
            cues = validate_webvtt(source.read(MAX_BYTES + 1), duration_ms=args.duration_ms)
    except (InvalidTrack, OSError) as error:
        parser.exit(1, f"FAIL: {error if isinstance(error, InvalidTrack) else 'TRACK_UNREADABLE'}\n")
    print(f"PASS: {len(cues)} cues conform to the P2 profile; authorization/playback not tested")


if __name__ == "__main__":
    main()
