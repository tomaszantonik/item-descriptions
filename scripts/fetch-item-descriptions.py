import json
import re
import requests
import time
from bs4 import BeautifulSoup, Tag
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
PARSE_REQUEST_DELAY = 0.1

MAX_INTRO_PARAGRAPHS = 5


def clean_description(text):
    if not text:
        return None

    text = text.replace("\r\n", "\n")
    text = text.replace("\r", "\n")

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


def load_existing_descriptions():
    if not OUTPUT_FILE.exists():
        return {}

    with OUTPUT_FILE.open("r", encoding="utf-8") as file:
        data = json.load(file)

    descriptions = {}

    for item_id, description in data.items():
        if not isinstance(description, str):
            continue

        description = clean_description(description)

        if not description:
            continue

        descriptions[str(item_id)] = description

    return descriptions


def parse_item_ids(raw_item_id):
    if raw_item_id is None:
        return []

    if isinstance(raw_item_id, list):
        values = raw_item_id
    else:
        values = [raw_item_id]

    item_ids = []

    for value in values:
        if isinstance(value, int):
            item_ids.append(value)
            continue

        value = str(value).strip()

        if not value:
            continue

        for match in re.findall(r"\d+", value):
            try:
                item_ids.append(int(match))
            except ValueError:
                pass

    return item_ids


def fetch_item_id_bucket():
    items = {}
    offset = 0

    while True:
        query = (
            "bucket('item_id')"
            ".select('page_name','page_name_sub','id')"
            ".orderBy('id','asc')"
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
            page_name = entry.get("page_name")
            page_name_sub = entry.get("page_name_sub")

            if not page_name:
                continue

            item_ids = parse_item_ids(entry.get("id"))

            for item_id in item_ids:
                items[item_id] = {
                    "id": item_id,
                    "page": str(page_name).strip(),
                    "page_sub": str(page_name_sub).strip() if page_name_sub else None
                }

        print(f"Item IDs fetched: {len(items)}")

        if len(batch) < ITEM_BATCH_SIZE:
            break

        offset += ITEM_BATCH_SIZE

        time.sleep(REQUEST_DELAY)

    return items


def fetch_infobox_items():
    items = {}
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
            page_name = entry.get("page_name")
            page_name_sub = entry.get("page_name_sub")
            item_name = entry.get("item_name")

            if not page_name:
                continue

            item_ids = parse_item_ids(entry.get("item_id"))

            for item_id in item_ids:
                items[item_id] = {
                    "id": item_id,
                    "name": str(item_name).strip() if item_name else None,
                    "page": str(page_name).strip(),
                    "page_sub": str(page_name_sub).strip() if page_name_sub else None
                }

        if len(batch) < ITEM_BATCH_SIZE:
            break

        offset += ITEM_BATCH_SIZE

        time.sleep(REQUEST_DELAY)

    return items


def merge_item_sources(item_id_items, infobox_items):
    items = {}

    all_ids = set(item_id_items.keys())
    all_ids.update(infobox_items.keys())

    for item_id in all_ids:
        item_id_item = item_id_items.get(item_id)
        infobox_item = infobox_items.get(item_id)

        if item_id_item:
            page = item_id_item.get("page")
            page_sub = item_id_item.get("page_sub")
        else:
            page = infobox_item.get("page")
            page_sub = infobox_item.get("page_sub")

        name = None

        if infobox_item:
            name = infobox_item.get("name")

        items[item_id] = {
            "id": item_id,
            "name": name,
            "page": page,
            "page_sub": page_sub
        }

    return dict(
        sorted(
            items.items(),
            key=lambda entry: entry[0]
        )
    )


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
        description = clean_description(page.get("extract"))

        if not title or not description:
            continue

        pages_by_title[title] = description

        normalized_key = normalize_title_key(title)

        if normalized_key:
            pages_by_normalized_title[normalized_key] = description

    descriptions = {}

    for original_title in titles:
        resolved_title = resolve_title(
            original_title,
            title_map
        )

        description = pages_by_title.get(resolved_title)

        if description is None:
            description = pages_by_normalized_title.get(
                normalize_title_key(resolved_title)
            )

        if description:
            descriptions[original_title] = description

    return descriptions


def fetch_extract_descriptions(page_names):
    page_names = sorted(set(page_names))

    descriptions = {}
    total = len(page_names)

    if total == 0:
        return descriptions

    print()
    print(f"Trying TextExtracts for {total} Wiki pages...")
    print()

    for offset in range(0, total, EXTRACT_BATCH_SIZE):
        batch = page_names[offset:offset + EXTRACT_BATCH_SIZE]

        try:
            batch_descriptions = fetch_extract_batch(batch)
        except requests.RequestException as exception:
            print()
            print(f"TextExtracts request failed: {exception}")
            print("Retrying in 5 seconds...")

            time.sleep(5)

            batch_descriptions = fetch_extract_batch(batch)

        descriptions.update(batch_descriptions)

        done = min(
            offset + EXTRACT_BATCH_SIZE,
            total
        )

        print(
            f"TextExtracts: "
            f"{done}/{total} "
            f"(found {len(descriptions)})"
        )

        time.sleep(REQUEST_DELAY)

    return descriptions


def extract_intro_from_html(html):
    if not html:
        return None

    soup = BeautifulSoup(
        html,
        "html.parser"
    )

    content = soup.select_one(".mw-parser-output")

    if content is None:
        content = soup

    paragraphs = []

    for child in content.children:
        if not isinstance(child, Tag):
            continue

        if child.name in ["h2", "h3"]:
            break

        if child.name != "p":
            continue

        text = child.get_text(
            " ",
            strip=True
        )

        text = " ".join(text.split())

        if not text:
            continue

        if len(text) < 20:
            continue

        paragraphs.append(text)

        if len(paragraphs) >= MAX_INTRO_PARAGRAPHS:
            break

    if not paragraphs:
        for paragraph in content.find_all("p"):
            text = paragraph.get_text(
                " ",
                strip=True
            )

            text = " ".join(text.split())

            if not text:
                continue

            if len(text) < 20:
                continue

            paragraphs.append(text)

            if len(paragraphs) >= MAX_INTRO_PARAGRAPHS:
                break

    if not paragraphs:
        return None

    return clean_description(
        "\n\n".join(paragraphs)
    )


def fetch_parse_description(page_name):
    response = requests.get(
        WIKI_API_URL,
        params={
            "action": "parse",
            "page": page_name,
            "prop": "text",
            "section": 0,
            "redirects": 1,
            "format": "json",
            "formatversion": 2
        },
        headers=HEADERS,
        timeout=60
    )

    response.raise_for_status()

    data = response.json()
    parse_data = data.get("parse")

    if not parse_data:
        return None

    html = parse_data.get("text")

    return extract_intro_from_html(html)


def fetch_parse_descriptions(page_names):
    page_names = sorted(set(page_names))

    descriptions = {}
    failed = []

    total = len(page_names)

    if total == 0:
        return descriptions, failed

    print()
    print(
        f"Trying action=parse fallback "
        f"for {total} pages..."
    )
    print()

    for index, page_name in enumerate(
            page_names,
            start=1
    ):
        description = None

        try:
            description = fetch_parse_description(
                page_name
            )
        except requests.RequestException as exception:
            print()
            print(
                f"Parse request failed for "
                f"{page_name}: {exception}"
            )

            time.sleep(2)

            try:
                description = fetch_parse_description(
                    page_name
                )
            except requests.RequestException:
                description = None

        if description:
            descriptions[page_name] = description
        else:
            failed.append(page_name)

        if (
                index % 25 == 0
                or index == total
        ):
            print(
                f"Parse fallback: "
                f"{index}/{total} "
                f"(found {len(descriptions)}, "
                f"missing {len(failed)})"
            )

        time.sleep(PARSE_REQUEST_DELAY)

    return descriptions, failed


def should_ignore_item(item):
    name = (
            item.get("name")
            or ""
    ).lower()

    page = (
            item.get("page")
            or ""
    ).lower()

    page_sub = (
            item.get("page_sub")
            or ""
    ).lower()

    combined = " ".join([
        name,
        page,
        page_sub
    ])

    ignored_markers = [
        "(interface item)",
        "(animation item)",
        "(unobtainable item)",
        "(historical)",
        "(discontinued)",
        "null <sup",
        "[[category:pages with null name]]"
    ]

    for marker in ignored_markers:
        if marker in combined:
            return True

    if name == "spell":
        return True

    return False


def get_missing_items(items, descriptions):
    missing = []

    for item_id, item in items.items():
        if str(item_id) in descriptions:
            continue

        missing.append(item)

    return missing


def get_missing_pages(missing_items):
    pages = set()

    for item in missing_items:
        page = item.get("page")

        if page:
            pages.add(page)

    return pages


def apply_page_descriptions(
        items,
        descriptions,
        page_descriptions
):
    added = 0

    for item_id, item in items.items():
        item_id_string = str(item_id)

        if item_id_string in descriptions:
            continue

        page = item.get("page")
        description = page_descriptions.get(page)

        if not description:
            continue

        descriptions[item_id_string] = description
        added += 1

    return added


def build_missing_report(items, descriptions):
    missing = []
    ignored = []

    for item_id, item in items.items():
        if str(item_id) in descriptions:
            continue

        entry = {
            "id": item_id,
            "name": item.get("name"),
            "page": item.get("page"),
            "page_sub": item.get("page_sub")
        }

        if should_ignore_item(item):
            ignored.append(entry)
        else:
            missing.append(entry)

    missing = sorted(
        missing,
        key=lambda item: item["id"]
    )

    ignored = sorted(
        ignored,
        key=lambda item: item["id"]
    )

    return missing, ignored


def sort_descriptions(descriptions):
    return dict(
        sorted(
            descriptions.items(),
            key=lambda entry: int(entry[0])
        )
    )


def save_json(path, data):
    path.parent.mkdir(
        parents=True,
        exist_ok=True
    )

    with path.open(
            "w",
            encoding="utf-8"
    ) as file:
        json.dump(
            data,
            file,
            ensure_ascii=False,
            indent=2
        )

        file.write("\n")


def print_summary(
        items,
        descriptions,
        existing_count,
        added_from_extract,
        added_from_parse,
        missing,
        ignored
):
    total = len(items)

    relevant_total = (
            total
            - len(ignored)
    )

    relevant_found = (
            relevant_total
            - len(missing)
    )

    overall_coverage = (
        len(descriptions) / total * 100
        if total
        else 0
    )

    relevant_coverage = (
        relevant_found / relevant_total * 100
        if relevant_total
        else 0
    )

    print()
    print("=" * 70)
    print("GENERATION SUMMARY")
    print("=" * 70)
    print(f"All item IDs:             {total}")
    print(f"Existing descriptions:    {existing_count}")
    print(f"Added from TextExtracts:  {added_from_extract}")
    print(f"Added from parse:         {added_from_parse}")
    print(f"Descriptions total:       {len(descriptions)}")
    print(f"Ignored technical IDs:    {len(ignored)}")
    print(f"Relevant missing:         {len(missing)}")
    print(f"Overall coverage:         {overall_coverage:.2f}%")
    print(f"Relevant coverage:        {relevant_coverage:.2f}%")
    print(f"Descriptions output:      {OUTPUT_FILE}")
    print(f"Missing output:           {MISSING_FILE}")
    print("=" * 70)

    if missing:
        print()
        print("First relevant missing items:")
        print()

        for item in missing[:50]:
            print(
                f'{item["id"]}: '
                f'{item["name"] or "?"} '
                f'[{item["page"]}]'
            )

        if len(missing) > 50:
            print()
            print(
                f"... and {len(missing) - 50} more"
            )


def main():
    OUTPUT_FILE.parent.mkdir(
        parents=True,
        exist_ok=True
    )

    descriptions = load_existing_descriptions()
    existing_count = len(descriptions)

    print(
        f"Existing descriptions: "
        f"{existing_count}"
    )

    print()
    print("Fetching item IDs from OSRS Wiki...")

    item_id_items = fetch_item_id_bucket()

    print()
    print(
        f"Item IDs from item_id bucket: "
        f"{len(item_id_items)}"
    )

    print()
    print("Fetching item names from infobox_item...")

    infobox_items = fetch_infobox_items()

    print(
        f"Item IDs from infobox_item: "
        f"{len(infobox_items)}"
    )

    items = merge_item_sources(
        item_id_items,
        infobox_items
    )

    print()
    print(f"Combined item IDs: {len(items)}")

    missing_items = get_missing_items(
        items,
        descriptions
    )

    missing_pages = get_missing_pages(
        missing_items
    )

    print()
    print(
        f"Item IDs requiring description: "
        f"{len(missing_items)}"
    )

    print(
        f"Unique pages requiring request: "
        f"{len(missing_pages)}"
    )

    extract_descriptions = fetch_extract_descriptions(
        missing_pages
    )

    added_from_extract = apply_page_descriptions(
        items,
        descriptions,
        extract_descriptions
    )

    missing_items = get_missing_items(
        items,
        descriptions
    )

    parse_pages = get_missing_pages(
        missing_items
    )

    parse_descriptions, _ = fetch_parse_descriptions(
        parse_pages
    )

    added_from_parse = apply_page_descriptions(
        items,
        descriptions,
        parse_descriptions
    )

    descriptions = sort_descriptions(
        descriptions
    )

    missing, ignored = build_missing_report(
        items,
        descriptions
    )

    save_json(
        OUTPUT_FILE,
        descriptions
    )

    save_json(
        MISSING_FILE,
        missing
    )

    print_summary(
        items,
        descriptions,
        existing_count,
        added_from_extract,
        added_from_parse,
        missing,
        ignored
    )


if __name__ == "__main__":
    main()
