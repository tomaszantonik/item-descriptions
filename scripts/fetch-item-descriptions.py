import json
import requests
import time
from pathlib import Path

WIKI_API_URL = "https://oldschool.runescape.wiki/api.php"

ROOT_DIR = Path(__file__).resolve().parent.parent
OUTPUT_FILE = ROOT_DIR / "data" / "item-descriptions.json"

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
                    "name": item_name,
                    "page": page_name
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


def fetch_extracts(page_names):
    page_names = sorted(set(page_names))
    descriptions_by_page = {}

    for offset in range(0, len(page_names), EXTRACT_BATCH_SIZE):
        batch = page_names[offset:offset + EXTRACT_BATCH_SIZE]

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
                "titles": "|".join(batch)
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

        for page in pages:
            title = page.get("title")
            extract = clean_description(page.get("extract"))

            if title and extract:
                pages_by_title[title] = extract

        for original_page in batch:
            resolved_page = original_page
            visited = set()

            while resolved_page in title_map and resolved_page not in visited:
                visited.add(resolved_page)
                resolved_page = title_map[resolved_page]

            description = pages_by_title.get(resolved_page)

            if description:
                descriptions_by_page[original_page] = description

        done = min(offset + EXTRACT_BATCH_SIZE, len(page_names))

        print(f"Descriptions fetched: {done}/{len(page_names)}")

        time.sleep(REQUEST_DELAY)

    return descriptions_by_page


def main():
    OUTPUT_FILE.parent.mkdir(parents=True, exist_ok=True)

    print("Fetching item list from OSRS Wiki...")

    items = fetch_wiki_items()

    print()
    print(f"Unique item IDs: {len(items)}")

    page_names = {
        item["page"]
        for item in items.values()
    }

    print(f"Unique Wiki pages: {len(page_names)}")
    print()
    print("Fetching Wiki descriptions...")

    descriptions_by_page = fetch_extracts(page_names)

    descriptions = {}
    missing = []

    for item_id, item in items.items():
        description = descriptions_by_page.get(item["page"])

        if description:
            descriptions[str(item_id)] = description
        else:
            missing.append(item)

    descriptions = dict(
        sorted(
            descriptions.items(),
            key=lambda entry: int(entry[0])
        )
    )

    with OUTPUT_FILE.open("w", encoding="utf-8") as file:
        json.dump(
            descriptions,
            file,
            ensure_ascii=False,
            indent=2
        )

        file.write("\n")

    print()
    print("=" * 60)
    print("GENERATION SUMMARY")
    print("=" * 60)
    print(f"Item IDs:      {len(items)}")
    print(f"Descriptions:  {len(descriptions)}")
    print(f"Missing:       {len(missing)}")
    print(f"Coverage:      {len(descriptions) / len(items) * 100:.2f}%")
    print(f"Output:        {OUTPUT_FILE}")
    print("=" * 60)

    if missing:
        print()
        print("Items without Wiki description:")

        for item in missing[:100]:
            print(
                f'{item["id"]}: {item["name"]} '
                f'[{item["page"]}]'
            )

        if len(missing) > 100:
            print(f"... and {len(missing) - 100} more")


if __name__ == "__main__":
    main()
