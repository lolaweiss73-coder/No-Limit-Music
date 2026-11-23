"""Audio identification using the MIT-licensed legacy ShazamIO fingerprinting algorithm.
Uses 16 kHz, 16-bit mono PCM, and the unofficial Shazam discovery endpoint.
Only fingerprint data leaves the device. No full recording is uploaded.
"""
import array
import json
import sys
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from legacy_shazamio.algorithm import SignatureGenerator

ENDPOINT = (
    "https://amp.shazam.com/discovery/v5/en/IL/android/-/tag/{u1}/{u2}"
    "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3"
    "&sharehub=true&hubv5minorversion=v5.1&hidelb=true&video=v3"
)

def recognize_pcm_file(filename):
    try:
        raw = Path(filename).read_bytes()
        if len(raw) < 16000 * 2 * 3:
            return json.dumps({"status": "no_match"})
        pcm = array.array("h")
        pcm.frombytes(raw)
        if sys.byteorder != "little":
            pcm.byteswap()

        generator = SignatureGenerator()
        generator.MAX_TIME_SECONDS = 10.0
        generator.feed_input(pcm.tolist())
        signature = generator.get_next_signature()
        if signature is None or not signature.frequency_band_to_sound_peaks:
            return json.dumps({"status": "no_match"})

        payload = {
            "timezone": "Asia/Jerusalem",
            "signature": {
                "uri": signature.encode_to_uri(),
                "samplems": int(signature.number_samples * 1000 / signature.sample_rate_hz),
            },
            "timestamp": int(time.time() * 1000),
            "context": {},
            "geolocation": {},
        }
        req = Request(
            ENDPOINT.format(u1=str(uuid.uuid4()).upper(), u2=str(uuid.uuid4()).upper()),
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "User-Agent": "Shazam/14.1.0 (Android; music recognition)",
                "X-Shazam-Platform": "ANDROID",
                "X-Shazam-AppVersion": "14.1.0",
                "Accept": "application/json",
                "Accept-Language": "en",
            },
            method="POST",
        )
        with urlopen(req, timeout=20) as response:
            results = json.load(response)
        track = results.get("track") or {}
        title = track.get("title", "").strip()
        if not title:
            return json.dumps({"status": "no_match"})
        artist = track.get("subtitle", "").strip()
        track_id = track.get("key") or (title + ":" + artist)
        return json.dumps({
            "status": "match",
            "title": title,
            "artist": artist,
            "key": str(track_id),
            "url": track.get("url") or (
                "https://www.shazam.com/track/" + str(track_id)
                if str(track_id).isdigit() else ""
            ),
        }, ensure_ascii=False)
    except HTTPError as exc:
        return json.dumps({"status": "error", "message": "HTTP " + str(exc.code)})
    except URLError:
        return json.dumps({"status": "error", "message": "אין חיבור לשרת הזיהוי"})
    except Exception as exc:
        return json.dumps({"status": "error", "message": str(exc)})
