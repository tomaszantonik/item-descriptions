import json
import requests
import time
from pathlib import Path

WIKI_API_URL = "https://oldschool.runescape.wiki/api.php"

ROOT_DIR = Path(__file__).resolve().parent.parent
OUTPUT_FILE = ROOT_DIR / "data" / "item-descriptions.json"
MISSING_FILE = ROOT_DIR / "data" / "missing-item-descriptions.json"

HEADERS = {
    "User-Agent": "RuneLite Item Descriptions - data updater"
}

ITEM_BATCH_SIZE = 5000
EXTRACT_BATCH_SIZE = 50
REQUEST_DELAY = 0.2


def clean_description(text):
    if not text:
        return None

    text = text.replace("\r\n", "\n").replace("\r", "\n")

    paragraphs = []

    for paragraph in text.split("\n"):
        paragraph = " ".join(paragraph.split())

        if paragraph:
            paragraphs.append(paragraph)

    cleaned = "\n\n".join(paragraphs).strip()

    return cleaned if cleaned else None


def normalize_title_key(title):
    if not title:
        return None

    return title.replace("_", " ").strip().lower()


def fetch_wiki_items():
    items = []
    offset = 0

    while True:
        query = (
            "bucket('infobox_item')"
            ".select('page_name','page_name_sub','item_id','item_name')"
            ".orderBy('page_name','asc')"
            f".limit({ITEM_BATCH_SIZE})"
            f".offset({offset})"
            ".run()"
        )

        response = requests.get(
            WIKI_API_URL,
            params={
                "action": "bucket",
                "format": "json",
                "formatversion": 2,
                "query": query
            },
            headers=HEADERS,
            timeout=60
        )

        response.raise_for_status()

        data = response.json()
        batch = data.get("bucket", [])

        if not batch:
            break

        for entry in batch:
            item_id = entry.get("item_id")
            item_name = entry.get("item_name")
            page_name = entry.get("page_name")
            page_name_sub = entry.get("page_name_sub")

            if item_id is None or not item_name or not page_name:
                continue

            if isinstance(item_id, list):
                item_ids = item_id
            else:
                item_ids = [item_id]

            for raw_item_id in item_ids:
                try:
                    parsed_item_id = int(raw_item_id)
                except (TypeError, ValueError):
                    continue

                items.append({
                    "id": parsed_item_id,
                    "name": str(item_name).strip(),
                    "page": str(page_name).strip(),
                    "page_sub": str(page_name_sub).strip() if page_name_sub else None
                })

        print(f"Item rows fetched: {len(items)}")

        if len(batch) < ITEM_BATCH_SIZE:
            break

        offset += ITEM_BATCH_SIZE

        time.sleep(REQUEST_DELAY)

    items_by_id = {}

    for item in items:
        items_by_id[item["id"]] = item

    return items_by_id


def resolve_title(title, title_map):
    visited = set()

    while title in title_map and title not in visited:
        visited.add(title)
        title = title_map[title]

    return title


def fetch_extract_batch(titles):
    response = requests.get(
        WIKI_API_URL,
        params={
            "action": "query",
            "prop": "extracts",
            "exintro": 1,
            "explaintext": 1,
            "redirects": 1,
            "format": "json",
            "formatversion": 2,
            "titles": "|".join(titles)
        },
        headers=HEADERS,
        timeout=60
    )

    response.raise_for_status()

    data = response.json()
    query = data.get("query", {})

    normalized = query.get("normalized", [])
    redirects = query.get("redirects", [])
    pages = query.get("pages", [])

    title_map = {}

    for entry in normalized:
        source = entry.get("from")
        target = entry.get("to")

        if source and target:
            title_map[source] = target

    for entry in redirects:
        source = entry.get("from")
        target = entry.get("to")

        if source and target:
            title_map[source] = target

    pages_by_title = {}
    pages_by_normalized_title = {}

    for page in pages:
        if page.get("missing"):
            continue

        title = page.get("title")
        extract = clean_description(page.get("extract"))

        if not title or not extract:
            continue

        pages_by_title[title] = extract

        normalized_key = normalize_title_key(title)

        if normalized_key:
            pages_by_normalized_title[normalized_key] = extract

    descriptions = {}

    for original_title in titles:
        resolved_title = resolve_title(original_title, title_map)

        description = pages_by_title.get(resolved_title)

        if description is None:
            description = pages_by_normalized_title.get(
                normalize_title_key(resolved_title)
            )

        if description is not None:
            descriptions[original_title] = description

    return descriptions


def fetch_extracts(titles, label):
    titles = sorted({
        title
        for title in titles
        if title
    })

    descriptions = {}

    total = len(titles)

    if total == 0:
        return descriptions

    for offset in range(0, total, EXTRACT_BATCH_SIZE):
        batch = titles[offset:offset + EXTRACT_BATCH_SIZE]

        try:
            batch_descriptions = fetch_extract_batch(batch)
            descriptions.update(batch_descriptions)
        except requests.RequestException as exception:
            print()
            print(f"Request failed for batch starting at {offset}: {exception}")
            print("Waiting 5 seconds before retry...")

            time.sleep(5)

            batch_descriptions = fetch_extract_batch(batch)
            descriptions.update(batch_descriptions)

        done = min(offset + EXTRACT_BATCH_SIZE, total)

        print(
            f"{label}: "
            f"{done}/{total} "
            f"(found {len(descriptions)})"
        )

        time.sleep(REQUEST_DELAY)

    return descriptions


def build_page_descriptions(items):
    page_names = {
        item["page"]
        for item in items.values()
        if item.get("page")
    }

    print()
    print(f"Unique Wiki pages: {len(page_names)}")
    print()
    print("Fetching descriptions by page name...")

    return fetch_extracts(
        page_names,
        "Page descriptions"
    )


def build_name_descriptions(items, page_descriptions):
    missing_names = set()

    for item in items.values():
        page_name = item.get("page")

        if page_name in page_descriptions:
            continue

        item_name = item.get("name")

        if item_name:
            missing_names.add(item_name)

    print()
    print(f"Unique item names requiring fallback: {len(missing_names)}")

    if not missing_names:
        return {}

    print()
    print("Fetching descriptions by item name...")

    return fetch_extracts(
        missing_names,
        "Name descriptions"
    )


def build_descriptions(items, page_descriptions, name_descriptions):
    descriptions = {}
    sources = {
        "page": 0,
        "name": 0
    }
    missing = []

    for item_id, item in items.items():
        description = page_descriptions.get(item["page"])

        if description:
            descriptions[str(item_id)] = description
            sources["page"] += 1
            continue

        description = name_descriptions.get(item["name"])

        if description:
            descriptions[str(item_id)] = description
            sources["name"] += 1
            continue

        missing.append({
            "id": item_id,
            "name": item["name"],
            "page": item["page"],
            "page_sub": item["page_sub"]
        })

    descriptions = dict(
        sorted(
            descriptions.items(),
            key=lambda entry: int(entry[0])
        )
    )

    missing = sorted(
        missing,
        key=lambda item: item["id"]
    )

    return descriptions, sources, missing


def save_descriptions(descriptions):
    OUTPUT_FILE.parent.mkdir(parents=True, exist_ok=True)

    with OUTPUT_FILE.open("w", encoding="utf-8") as file:
        json.dump(
            descriptions,
            file,
            ensure_ascii=False,
            indent=2
        )

        file.write("\n")


def save_missing(missing):
    MISSING_FILE.parent.mkdir(parents=True, exist_ok=True)

    with MISSING_FILE.open("w", encoding="utf-8") as file:
        json.dump(
            missing,
            file,
            ensure_ascii=False,
            indent=2
        )

        file.write("\n")


def print_summary(items, descriptions, sources, missing):
    total = len(items)
    found = len(descriptions)

    coverage = (
        found / total * 100
        if total > 0
        else 0
    )

    print()
    print("=" * 60)
    print("GENERATION SUMMARY")
    print("=" * 60)
    print(f"Item IDs:             {total}")
    print(f"Descriptions:         {found}")
    print(f"From page name:       {sources['page']}")
    print(f"From item name:       {sources['name']}")
    print(f"Missing:              {len(missing)}")
    print(f"Coverage:             {coverage:.2f}%")
    print(f"Descriptions output:  {OUTPUT_FILE}")
    print(f"Missing output:       {MISSING_FILE}")
    print("=" * 60)

    if missing:
        print()
        print("First missing items:")

        for item in missing[:50]:
            print(
                f'{item["id"]}: '
                f'{item["name"]} '
                f'[{item["page"]}]'
            )

        if len(missing) > 50:
            print(
                f"... and {len(missing) - 50} more "
                f"in {MISSING_FILE}"
            )


def print_debug_item(items, descriptions, name):
    matches = [
        item
        for item in items.values()
        if item["name"].lower() == name.lower()
    ]

    print()
    print("=" * 60)
    print(f"DEBUG ITEM: {name}")
    print("=" * 60)

    if not matches:
        print("Item not found in Wiki item bucket.")
        print("=" * 60)
        return

    for item in matches:
        item_id = str(item["id"])

        print(f'ID:          {item["id"]}')
        print(f'Name:        {item["name"]}')
        print(f'Page:        {item["page"]}')
        print(f'Page sub:    {item["page_sub"]}')
        print(
            f'Description: '
            f'{"YES" if item_id in descriptions else "NO"}'
        )
        print("-" * 60)

    print("=" * 60)


def main():
    print("Fetching item list from OSRS Wiki...")

    items = fetch_wiki_items()

    print()
    print(f"Unique item IDs: {len(items)}")

    page_descriptions = build_page_descriptions(items)

    name_descriptions = build_name_descriptions(
        items,
        page_descriptions
    )

    descriptions, sources, missing = build_descriptions(
        items,
        page_descriptions,
        name_descriptions
    )

    save_descriptions(descriptions)
    save_missing(missing)

    print_summary(
        items,
        descriptions,
        sources,
        missing
    )

    print_debug_item(
        items,
        descriptions,
        "Colossal pouch"
    )


if __name__ == "__main__":
    main()
