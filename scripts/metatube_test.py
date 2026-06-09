#!/usr/bin/env python3
"""Small MetaTube scraper test client.

Examples:
  python scripts/metatube_test.py --base-url http://127.0.0.1:8080 SSIS-001
  python scripts/metatube_test.py --base-url http://your-vps-ip:8080 --token xxx SSIS-001 FC2-PPV-1234567
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from typing import Any


def request_json(base_url: str, path: str, token: str | None) -> Any:
    url = base_url.rstrip("/") + path
    headers = {
        "Accept": "application/json",
        "User-Agent": "metatube-test/1.0",
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"

    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            body = resp.read().decode("utf-8", errors="replace")
            if not body.strip():
                return None
            return json.loads(body)
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {exc.code} {exc.reason}: {body[:800]}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"request failed: {exc.reason}") from exc
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"response is not JSON: {exc}") from exc


def pick_items(payload: Any) -> list[dict[str, Any]]:
    if isinstance(payload, list):
        return [x for x in payload if isinstance(x, dict)]
    if not isinstance(payload, dict):
        return []

    for key in ("data", "items", "movies", "results", "result"):
        value = payload.get(key)
        if isinstance(value, list):
            return [x for x in value if isinstance(x, dict)]
        if isinstance(value, dict):
            nested = pick_items(value)
            if nested:
                return nested
    return []


def first_value(item: dict[str, Any], *keys: str) -> Any:
    for key in keys:
        if key in item and item[key] not in (None, ""):
            return item[key]
    return None


def print_movie(index: int, item: dict[str, Any]) -> None:
    movie_id = first_value(item, "id", "number", "code")
    provider = first_value(item, "provider", "source", "site")
    title = first_value(item, "title", "name")
    date = first_value(item, "date", "releaseDate", "release_date", "releasedAt")
    actors = first_value(item, "actors", "actresses", "stars")
    cover = first_value(item, "cover", "poster", "image", "thumb")

    print(f"{index}. title: {title or '-'}")
    print(f"   id: {movie_id or '-'}")
    print(f"   provider: {provider or '-'}")
    print(f"   date: {date or '-'}")
    print(f"   actors: {actors or '-'}")
    print(f"   cover: {cover or '-'}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Test MetaTube scraping/search result quality.")
    parser.add_argument("numbers", nargs="+", help="movie numbers to search, e.g. SSIS-001")
    parser.add_argument("--base-url", required=True, help="MetaTube server URL, e.g. http://1.2.3.4:8080")
    parser.add_argument("--token", help="MetaTube TOKEN if enabled")
    parser.add_argument("--limit", type=int, default=5, help="max search results to print for each number")
    parser.add_argument("--raw", action="store_true", help="print raw JSON payload")
    parser.add_argument("--detail", action="store_true", help="fetch detail for the first result when provider/id exist")
    args = parser.parse_args()

    for number in args.numbers:
        print(f"\n=== search: {number} ===")
        q = urllib.parse.quote(number)
        try:
            payload = request_json(args.base_url, f"/v1/movies/search?q={q}", args.token)
        except RuntimeError as exc:
            print(f"ERROR: {exc}", file=sys.stderr)
            continue

        if args.raw:
            print(json.dumps(payload, ensure_ascii=False, indent=2))

        items = pick_items(payload)
        if not items:
            print("No result.")
            continue

        for idx, item in enumerate(items[: args.limit], start=1):
            print_movie(idx, item)

        if args.detail:
            first = items[0]
            provider = first_value(first, "provider", "source", "site")
            movie_id = first_value(first, "id", "number", "code")
            if not provider or not movie_id:
                print("detail skipped: first result has no provider/id")
                continue

            path = "/v1/movies/{}/{}".format(
                urllib.parse.quote(str(provider), safe=""),
                urllib.parse.quote(str(movie_id), safe=""),
            )
            print(f"\n--- detail: {provider}/{movie_id} ---")
            try:
                detail = request_json(args.base_url, path, args.token)
            except RuntimeError as exc:
                print(f"ERROR: {exc}", file=sys.stderr)
                continue
            print(json.dumps(detail, ensure_ascii=False, indent=2))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
