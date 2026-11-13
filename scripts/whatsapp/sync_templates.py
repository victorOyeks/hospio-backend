#!/usr/bin/env python3
"""
Submits the WhatsApp templates in templates.json to Infobip for Meta's approval, and shows where each one stands.

  python3 scripts/whatsapp/sync_templates.py            # check the file and show what would be sent (nothing is sent)
  python3 scripts/whatsapp/sync_templates.py --submit   # submit the templates the sender doesn't have yet
  python3 scripts/whatsapp/sync_templates.py --status   # list the sender's templates and their approval status

A template is matched by name and language; one that already exists is left alone (Meta doesn't allow editing an approved
template's text this way: give a changed template a new name, like hospio_booking_confirmation_v2).

The API key is never in the repo: INFOBIP_API_KEY in the environment, or a line INFOBIP_API_KEY=... in
~/.config/oyeks/infobip.env (chmod 600). Create the key in Infobip: Developer Tools → API keys.
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
NAME = re.compile(r"^[a-z0-9_]{1,512}$")
VAR = re.compile(r"\{\{(\d+)\}\}")


def api_key() -> str:
    key = os.environ.get("INFOBIP_API_KEY", "").strip()
    if not key:
        env = Path.home() / ".config" / "oyeks" / "infobip.env"
        if env.exists():
            for line in env.read_text().splitlines():
                if line.strip().startswith("INFOBIP_API_KEY="):
                    key = line.split("=", 1)[1].strip().strip('"').strip("'")
    if not key:
        sys.exit("No API key: set INFOBIP_API_KEY or put INFOBIP_API_KEY=... in ~/.config/oyeks/infobip.env")
    return key


def call(method: str, url: str, key: str, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={
        "Authorization": f"App {key}", "Content-Type": "application/json", "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"raw": raw.decode(errors="replace")[:500]}


def problems(t: dict) -> list:
    """Meta's common rejection reasons, checked before anything is sent."""
    out = []
    body = t["body"].strip()
    nums = [int(n) for n in VAR.findall(body)]
    if not NAME.match(t["name"]):
        out.append("name: lowercase letters, digits and underscores only")
    if nums != list(range(1, len(nums) + 1)):
        out.append("variables must be {{1}}, {{2}}, … in order, each once")
    if len(t.get("examples", [])) != len(nums):
        out.append(f"{len(nums)} variables but {len(t.get('examples', []))} examples")
    if body.startswith("{{") or re.search(r"\{\{\d+\}\}[.!?]?$", body):
        out.append("the body can't start or end with a variable")
    if len(body) > 1024:
        out.append("body over 1024 characters")
    if t.get("header") and (len(t["header"]) > 60 or VAR.search(t["header"])):
        out.append("header: up to 60 characters, no variables")
    if t.get("footer") and len(t["footer"]) > 60:
        out.append("footer: up to 60 characters")
    if any(not str(e).strip() for e in t.get("examples", [])):
        out.append("every example needs a real value")
    return out


def payload(t: dict) -> dict:
    structure = {"body": {"text": t["body"]}, "type": "TEXT"}
    if t.get("examples"):
        structure["body"]["examples"] = t["examples"]
    if t.get("header"):
        structure["header"] = {"format": "TEXT", "text": t["header"]}
    if t.get("footer"):
        structure["footer"] = {"text": t["footer"]}
    return {"name": t["name"], "language": t["language"], "category": t["category"],
            "allowCategoryChange": False, "structure": structure}


def main():
    args = set(sys.argv[1:])
    cfg = json.loads((HERE / "templates.json").read_text())
    templates = [{**cfg.get("defaults", {}), **t} for t in cfg["templates"]]
    base = cfg["baseUrl"].rstrip("/")
    url = f"{base}/whatsapp/2/senders/{cfg['sender']}/templates"

    bad = {t["name"]: problems(t) for t in templates if problems(t)}
    for name, why in bad.items():
        print(f"✗ {name}: " + "; ".join(why))
    if bad:
        sys.exit("Fix the templates above first; nothing was sent.")
    names = [t["name"] + "/" + t["language"] for t in templates]
    if len(set(names)) != len(names):
        sys.exit("The file lists one name/language twice.")

    if not args & {"--submit", "--status"}:
        for t in templates:
            print(f"✓ {t['name']} ({t['category'].lower()}, {t['language']}) looks fine")
        print(f"\n{len(templates)} templates checked. Run with --submit to send the new ones, --status to see approvals.")
        return

    key = api_key()
    status, existing = call("GET", url, key)
    if status >= 300:
        sys.exit(f"Could not list the sender's templates ({status}): {json.dumps(existing)[:500]}")
    have = {(x.get("name"), x.get("language")): x for x in existing.get("templates", [])}

    if "--status" in args:
        mine = [x for x in existing.get("templates", []) if not x.get("name", "").startswith(("hospio_", "coryd_", "oyeks_"))]
        for (name, lang), x in sorted(have.items()):
            if name.startswith(("hospio_", "coryd_", "oyeks_")):
                print(f"{x.get('status', '?'):<10} {name} ({lang}, {str(x.get('category', '')).lower()})")
        if mine:
            print(f"(+ {len(mine)} Infobip sample templates not shown)")
        return

    sent = skipped = failed = 0
    for t in templates:
        if (t["name"], t["language"]) in have:
            print(f"– {t['name']}: already there ({have[(t['name'], t['language'])].get('status', '?')})")
            skipped += 1
            continue
        status, res = call("POST", url, key, payload(t))
        if status < 300:
            print(f"✓ {t['name']}: submitted ({res.get('status', 'PENDING')})")
            sent += 1
        else:
            print(f"✗ {t['name']}: {status} {json.dumps(res)[:400]}")
            failed += 1
    print(f"\n{sent} submitted, {skipped} already there, {failed} refused.")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
