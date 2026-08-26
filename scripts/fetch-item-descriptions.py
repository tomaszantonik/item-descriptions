import json
import requests
import time
from pathlib import Path

MAPPING_URL = "https://prices.runescape.wiki/api/v1/osrs/mapping"
WIKI_API_URL = "https://oldschool.runescape.wiki/api.php"

ROOT_DIR = Path(__file__).resolve().parent.parent
OUTPUT_FILE = ROOT_DIR / "data" / "item-descriptions.json"

HEADERS = {
    "User-Agent": "RuneLite Item Descriptions - data updater"
}

BATCH_SIZE = 50
REQUEST_DELAY = 0.2


def load_existing_descriptions():
    if not OUTPUT_FILE.exists():
        return {}

    with OUTPUT_FILE.open("r", encoding="utf-8") as file:
        data = json.load(file)

    return {
        str(item_id): description
        for item_id, description in data.items()
        if isinstance(description, str) and description.strip()
    }


def fetch_items():
    response = requests.get(
        MAPPING_URL,
        headers=HEADERS,
        timeout=30
    )

    response.raise_for_status()

    return response.json()


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


def resolve_title(title, title_map):
    visited = set()

    while title in title_map and title not in visited:
        visited.add(title)
        title = title_map[title]

    return title


def fetch_wiki_descriptions(names):
    descriptions_by_name = {}

    names = sorted(set(names))

    for offset in range(0, len(names), BATCH_SIZE):
        batch = names[offset:offset + BATCH_SIZE]

        response = requests.get(
            WIKI_API_URL,
            params={
                "action": "query",
                "prop": "extracts",
                "exintro": 1,
                "explaintext": 1,
                "redirects": 1,
                "format": "json",
                "titles": "|".join(batch)
            },
            headers=HEADERS,
            timeout=30
        )

        response.raise_for_status()

        data = response.json()
        query = data.get("query", {})

        pages = query.get("pages", {})
        normalized = query.get("normalized", [])
        redirects = query.get("redirects", [])

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

        for page in pages.values():
            title = page.get("title")

            if not title:
                continue

            extract = clean_description(page.get("extract"))

            if extract:
                pages_by_title[title] = extract

        pages_by_title_lower = {
            title.lower(): description
            for title, description in pages_by_title.items()
        }

        for original_name in batch:
            resolved_name = resolve_title(original_name, title_map)

            description = pages_by_title.get(resolved_name)

            if description is None:
                description = pages_by_title_lower.get(resolved_name.lower())

            if description is not None:
                descriptions_by_name[original_name] = description

        done = min(offset + BATCH_SIZE, len(names))

        print(f"Wiki: {done}/{len(names)}")

        time.sleep(REQUEST_DELAY)

    return descriptions_by_name


def main():
    OUTPUT_FILE.parent.mkdir(parents=True, exist_ok=True)

    existing = load_existing_descriptions()
    items = fetch_items()

    items_by_id = {}

    for item in items:
        item_id = item.get("id")
        name = item.get("name")

        if item_id is None or not name:
            continue

        items_by_id[str(item_id)] = item

    print(f"Items: {len(items_by_id)}")
    print(f"Existing descriptions: {len(existing)}")

    descriptions = {}

    for item_id, description in existing.items():
        if item_id in items_by_id:
            descriptions[item_id] = description

    missing_items = [
        item
        for item_id, item in items_by_id.items()
        if item_id not in descriptions
    ]

    missing_names = {
        item["name"]
        for item in missing_items
        if item.get("name")
    }

    print(f"Missing item IDs: {len(missing_items)}")
    print(f"Unique names to query: {len(missing_names)}")

    wiki_descriptions = {}

    if missing_names:
        wiki_descriptions = fetch_wiki_descriptions(missing_names)

    wiki_count = 0
    examine_count = 0
    no_description = []

    for item_id, item in items_by_id.items():
        if item_id in descriptions:
            continue

        name = item.get("name")
        examine = clean_description(item.get("examine"))

        wiki_description = wiki_descriptions.get(name)

        if wiki_description:
            descriptions[item_id] = wiki_description
            wiki_count += 1
            continue

        if examine:
            descriptions[item_id] = examine
            examine_count += 1
            continue

        no_description.append({
            "id": item_id,
            "name": name
        })

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
    print(f"Existing kept: {len(existing)}")
    print(f"Wiki descriptions added: {wiki_count}")
    print(f"Examine fallbacks added: {examine_count}")
    print(f"Descriptions saved: {len(descriptions)}")
    print(f"Missing: {len(no_description)}")
    print(f"Output: {OUTPUT_FILE}")

    if no_description:
        print()
        print("Items without any description:")

        for item in no_description[:50]:
            print(f'{item["id"]}: {item["name"]}')

        if len(no_description) > 50:
            print(f"... and {len(no_description) - 50} more")


if __name__ == "__main__":
    main()
