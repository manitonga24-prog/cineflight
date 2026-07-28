# -*- coding: utf-8 -*-
"""
athlete.py — Router FastAPI CineFlight Athlete (balise LOGICIELLE de sujet).

Reçoit la télémétrie du téléphone de l'athlète (CineFlight Athlete iOS) et la met à
disposition de CineFlight Android. Le téléphone athlète NE COMMANDE JAMAIS le drone.

Routes :
    POST /api/v1/athlete/telemetry              -> réception télémétrie (TOLÉRANT)
    GET  /api/v1/subjects                        -> diagnostic : liste des subject_id connus
    GET  /api/v1/subjects/{subject_id}/latest    -> dernière position exploitable

Réception TOLÉRANTE : au lieu d'un schéma Pydantic strict (qui renvoyait 422 sur le moindre
champ manquant), on accepte le JSON brut, on le journalise (`[ATHLETE] telemetry recu: …`),
et on n'exige que subject_id + latitude/longitude valides. Les autres champs prennent un
défaut sûr. Objectif : ne jamais bloquer l'iPhone à l'intégration, et VOIR le vrai format.

Stockage EN MÉMOIRE (dernière position par subject_id). Un redémarrage vide la mémoire ;
l'iPhone repousse et la position réapparaît.

INSTALLATION : déposer dans cineflight_web/ puis dans app.py :
    from athlete import router as athlete_router
    app.include_router(athlete_router)
"""

import threading
from datetime import datetime, timezone

from fastapi import APIRouter, Header, HTTPException, Request

router = APIRouter()

_lock = threading.Lock()
_latest = {}   # subject_id -> dict


def _parse_ts(v):
    """Parse un timestamp ISO tolérant. Rend un datetime aware, ou None."""
    if not v:
        return None
    try:
        s = str(v).strip().replace("Z", "+00:00")
        dt = datetime.fromisoformat(s)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt
    except Exception:
        return None


def _num(b, *keys, default=None):
    """Premier champ numérique trouvé parmi plusieurs noms possibles."""
    for k in keys:
        v = b.get(k)
        if isinstance(v, bool):
            continue
        if isinstance(v, (int, float)):
            return float(v)
    return default


@router.post("/api/v1/athlete/telemetry")
@router.post("/api/v1/athlet/telemetry")   # alias défensif (faute de frappe iOS possible)
async def recevoir_telemetrie(request: Request, authorization: str = Header("")):
    try:
        b = await request.json()
    except Exception:
        raise HTTPException(400, "corps JSON illisible")
    if not isinstance(b, dict):
        raise HTTPException(400, "corps invalide")

    # DIAGNOSTIC : trace le payload REÇU (visible dans journalctl -u cineflight).
    print("[ATHLETE] telemetry recu:", b, flush=True)

    # IDENTIFIANT DU SUJET — noms réels de l'iPhone CineFlight Athlete : athlete_id /
    # athlete_number (pas subject_id). On accepte tous ces alias.
    subject_id = (b.get("subject_id") or b.get("subjectId")
                  or b.get("athlete_id") or b.get("athlete_number"))
    if not subject_id:
        raise HTTPException(422, "identifiant de sujet manquant (subject_id / athlete_id)")

    lat = _num(b, "latitude", "lat")
    lon = _num(b, "longitude", "lon")
    if lat is None or lon is None or not (-90.0 <= lat <= 90.0) or not (-180.0 <= lon <= 180.0):
        raise HTTPException(422, "latitude/longitude manquante ou hors bornes")

    now = datetime.now(timezone.utc)
    ts = _parse_ts(b.get("timestamp")) or now
    age_s = max(0.0, (now - ts).total_seconds())

    seq_raw = b.get("source_sequence", b.get("sourceSequence"))
    seq = int(seq_raw) if isinstance(seq_raw, (int, float)) and not isinstance(seq_raw, bool) else 0

    # iOS (CoreLocation) : speed=-1 / course=-1 = valeur INVALIDE — cas normal d'un sujet
    # IMMOBILE (pas de doppler à l'arrêt). Normalisé ici (vitesse -> 0, cap -> absent) ;
    # Android normalise AUSSI au parsing (défense en profondeur). Sans ça, l'app rendait
    # PERDU / « vitesse_aberrante » dès que la personne s'arrêtait (2026-07-25).
    spd = _num(b, "speed_mps", "speed", default=0.0) or 0.0
    if spd < 0:
        spd = 0.0
    crs = _num(b, "course_deg", "heading_deg", "heading", "course")
    if crs is not None and crs < 0:
        crs = None

    with _lock:
        prev = _latest.get(subject_id)
        # anti-doublon/désordre SEULEMENT si une vraie séquence croissante est fournie
        if prev and seq > 0 and seq <= prev.get("source_sequence", -1):
            return {"accepted": True, "duplicate": True}
        _latest[subject_id] = {
            "subject_id": subject_id,
            "session_id": b.get("session_id", b.get("sessionId", "")),
            "latitude": lat,
            "longitude": lon,
            "altitude_m": _num(b, "altitude_m", "alt_m", "altitude"),
            "speed_mps": spd,
            "course_deg": crs,
            # Champs RÉELLEMENT envoyés par l'iPhone (relevé du 2026-07-25) qui étaient
            # JETÉS à la réception : nom lisible (sélection dans l'app Android), précision
            # verticale, identité de l'appareil, profil, version de schéma.
            "athlete_name": b.get("athlete_name", ""),
            "device_id": b.get("device_id", ""),
            "vertical_accuracy_m": _num(b, "vertical_accuracy_m", "verticalAccuracy"),
            # Dénivelé depuis le départ (m) — nouveau champ iOS 2026-07-25. INDICATIF :
            # l'altitude téléphone n'est jamais une consigne de vol (spec §5).
            "elevation_from_start_m": _num(b, "elevation_from_start_m"),
            "profile_mode": b.get("profile_mode", ""),
            "competition_number": b.get("competition_number", ""),
            "schema_version": b.get("schema_version"),
            "source": b.get("source", ""),
            "horizontal_accuracy_m": (_num(b, "horizontal_accuracy_m", "horizontalAccuracy",
                                            "hAccuracy", default=99.0) or 99.0),
            "telemetry_quality": b.get("telemetry_quality", b.get("quality", "good")),
            "source_sequence": seq,
            "battery_pct": b.get("battery_pct", b.get("batteryPct", b.get("battery_percent"))),
            "motion": b.get("motion") if isinstance(b.get("motion"), dict) else {},
            "received_at": now.timestamp(),
            "measurement_age_s": age_s,
        }
    return {"accepted": True, "server_time": now.isoformat()}


@router.get("/api/v1/subjects")
def lister_sujets():
    """DIAGNOSTIC : subject_id ayant poussé de la télémétrie (avec l'âge)."""
    now = datetime.now(timezone.utc).timestamp()
    with _lock:
        return {
            "subjects": [
                {
                    "subject_id": sid,
                    # nom lisible : la liste de sélection Android affiche « Vsrre »
                    # plutôt qu'un identifiant technique SUJET-73BE742D.
                    "athlete_name": d.get("athlete_name", ""),
                    "session_id": d.get("session_id", ""),
                    "received_age_s": round(max(0.0, now - float(d["received_at"])), 2),
                    "source_sequence": d.get("source_sequence", -1),
                }
                for sid, d in _latest.items()
            ]
        }


@router.get("/api/v1/subjects/{subject_id}/latest")
def derniere_position(subject_id: str):
    with _lock:
        p = _latest.get(subject_id)
    if not p:
        return {"present": False}

    now = datetime.now(timezone.utc).timestamp()
    received_age = max(0.0, now - float(p["received_at"]))
    motion = p.get("motion") or {}
    batt = p.get("battery_pct")

    return {
        "present": True,
        "subject_id": subject_id,
        "session_id": p.get("session_id", ""),
        "lat": p["latitude"],
        "lon": p["longitude"],
        "alt_m": p.get("altitude_m"),
        "speed_mps": p["speed_mps"],
        "heading_deg": p.get("course_deg"),
        "horizontal_accuracy_m": p["horizontal_accuracy_m"],
        "quality": p.get("telemetry_quality", "good"),
        "source_sequence": p["source_sequence"],
        "measurement_age_s": p.get("measurement_age_s", 0.0),
        "received_age_s": received_age,
        "motion_state": motion.get("motion_state", ""),
        "battery_pct": batt if batt is not None else -1,
        # ── Bloc MOTION complet (CoreMotion iPhone, ~10 Hz agrégé sur ~2,4 s) ──────────
        # Il était STOCKÉ mais seul motion_state ressortait. Ces mesures INERTIELLES sont
        # bien plus fines que la vitesse GPS pour dire si le sujet bouge : à l'arrêt le
        # GPS dérive alors que l'accélération reste ~0,06 m/s² avec confidence ~0,99.
        "motion_acceleration_mps2": motion.get("acceleration_mps2"),
        "motion_peak_acceleration_mps2": motion.get("peak_acceleration_mps2"),
        "motion_rotation_rate_rad_s": motion.get("rotation_rate_rad_s"),
        "motion_peak_rotation_rate_rad_s": motion.get("peak_rotation_rate_rad_s"),
        "motion_confidence": motion.get("confidence"),
        "motion_orientation_changed": motion.get("orientation_changed"),
        "motion_sample_rate_hz": motion.get("sample_rate_hz"),
        "motion_window_duration_s": motion.get("window_duration_s"),
        # ── Identité / qualité ────────────────────────────────────────────────────────
        "athlete_name": p.get("athlete_name", ""),
        "device_id": p.get("device_id", ""),
        "vertical_accuracy_m": p.get("vertical_accuracy_m"),
        "elevation_from_start_m": p.get("elevation_from_start_m"),
        "profile_mode": p.get("profile_mode", ""),
        "competition_number": p.get("competition_number", ""),
        "schema_version": p.get("schema_version"),
        "source": p.get("source", ""),
        "server_time": datetime.now(timezone.utc).isoformat(),
    }
