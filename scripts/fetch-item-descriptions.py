import json
import time
import requests

API_URL = "https://oldschool.runescape.wiki/api.php"
OUTPUT_FILE = "../data/item-descriptions.json"

HEADERS = {
    "User-Agent": "RuneLite Item Descriptions data updater"
}


def fetch_descriptions(item_names):
    result = {}

    for i in range(0, len(item_names), 50):
        batch = item_names[i:i + 50]

        response = requests.get(
            API_URL,
            params={
                "action": "query",
                "prop": "extracts",
                "exintro": 1,
                "explaintext": 1,
                "redirects": 1,
                "format": "json",
                "titles": "|".join(batch),
            },
            headers=HEADERS,
            timeout=30,
        )

        response.raise_for_status()

        pages = response.json()["query"]["pages"]

        for page in pages.values():
            title = page.get("title")
            extract = page.get("extract")

            if title and extract:
                result[title] = extract.strip()

        time.sleep(0.2)

    return result