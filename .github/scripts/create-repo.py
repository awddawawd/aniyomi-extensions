import json
import os
import re
import subprocess
from pathlib import Path
from zipfile import ZipFile

PACKAGE_NAME_REGEX = re.compile(r"package: name='([^']+)'")
VERSION_CODE_REGEX = re.compile(r"versionCode='([^']+)'")
VERSION_NAME_REGEX = re.compile(r"versionName='([^']+)'")
IS_NSFW_REGEX = re.compile(r"'tachiyomi.animeextension.nsfw' value='([^']+)'")
APPLICATION_LABEL_REGEX = re.compile(r"^application-label:'([^']+)'", re.MULTILINE)
APPLICATION_ICON_320_REGEX = re.compile(
    r"^application-icon-320:'([^']+)'", re.MULTILINE
)
LANGUAGE_REGEX = re.compile(r"aniyomi-([^\.]+)")

ANDROID_BUILD_TOOLS = sorted((Path(os.environ["ANDROID_HOME"]) / "build-tools").iterdir())[-1]
REPO_DIR = Path("repo")
REPO_APK_DIR = REPO_DIR / "apk"
REPO_ICON_DIR = REPO_DIR / "icon"

REPO_ICON_DIR.mkdir(parents=True, exist_ok=True)

with open("output.json", encoding="utf-8") as f:
    inspector_data = json.load(f)

index_data = []
index_min_data = []

for apk in REPO_APK_DIR.iterdir():
    badging = subprocess.check_output(
        [
            ANDROID_BUILD_TOOLS / "aapt",
            "dump",
            "--include-meta-data",
            "badging",
            apk,
        ]
    ).decode()

    package_info = next(x for x in badging.splitlines() if x.startswith("package: "))
    package_name = PACKAGE_NAME_REGEX.search(package_info).group(1)    
    application_icon = APPLICATION_ICON_320_REGEX.search(badging).group(1)

    with ZipFile(apk) as z, z.open(application_icon) as i, (
        REPO_ICON_DIR / f"{package_name}.png"
    ).open("wb") as f:
        f.write(i.read())

    language = LANGUAGE_REGEX.search(apk.name).group(1)
    sources = inspector_data[package_name]

    if len(sources) == 1:
        source_language = sources[0]["lang"]

        if (
            source_language != language
            and source_language not in {"all", "other"}
            and language not in {"all", "other"}
        ):
            language = source_language

    common_data = {
        "name": APPLICATION_LABEL_REGEX.search(badging).group(1),
        "pkg": package_name,
        "apk": apk.name,
        "lang": language,
        "code": int(VERSION_CODE_REGEX.search(package_info).group(1)),
        "version": VERSION_NAME_REGEX.search(package_info).group(1),
        "nsfw": int(IS_NSFW_REGEX.search(badging).group(1)),
    }
    min_data = {
        **common_data,
        "sources": [],
    }

    for source in sources:
        min_data["sources"].append(
            {
                "name": source["name"],
                "lang": source["lang"],
                "id": source["id"],
                "baseUrl": source["baseUrl"],
            }
        )

    index_min_data.append(min_data)
    index_data.append(
        {
            **common_data,
            "hasReadme": 0,
            "hasChangelog": 0,
            "sources": sources,
        }
    )

index_data.sort(key=lambda x: x["pkg"])
index_min_data.sort(key=lambda x: x["pkg"])

with (REPO_DIR / "index.json").open("w", encoding="utf-8") as f:
    index_data_str = json.dumps(index_data, ensure_ascii=False, indent=2)

    print(index_data_str)
    f.write(index_data_str)

with (REPO_DIR / "index.min.json").open("w", encoding="utf-8") as f:
    json.dump(index_min_data, f, ensure_ascii=False, separators=(",", ":"))

def get_signing_key_fingerprint(apk_path: Path) -> str:
    try:
        out = subprocess.check_output(["keytool", "-printcert", "-jarfile", str(apk_path)]).decode()
        for line in out.splitlines():
            if "SHA256:" in line or "SHA-256:" in line:
                return line.split(":", 1)[1].strip().replace(":", "").lower()
    except Exception:
        pass
    try:
        from cryptography.hazmat.primitives.serialization import pkcs7
        from cryptography.hazmat.primitives import hashes
        with ZipFile(apk_path) as z:
            for n in z.namelist():
                if n.startswith("META-INF/") and (n.endswith(".RSA") or n.endswith(".DSA") or n.endswith(".EC")):
                    certs = pkcs7.load_der_pkcs7_certificates(z.read(n))
                    if certs:
                        return certs[0].fingerprint(hashes.SHA256()).hex().lower()
    except Exception:
        pass
    return "2b7a66f6dfe50ffc78d628fccad9d5c288953fc9ffc6fcac87fe4b8e1f47a13a"

all_apks = list(REPO_APK_DIR.glob("*.apk"))
first_apk = all_apks[0] if all_apks else None
fingerprint = get_signing_key_fingerprint(first_apk) if first_apk else "2b7a66f6dfe50ffc78d628fccad9d5c288953fc9ffc6fcac87fe4b8e1f47a13a"
print(f"Repository signing key fingerprint: {fingerprint}")

repo_name = "VoirAnime"
repo_website = f"https://github.com/{os.environ.get('GITHUB_REPOSITORY', 'awddawawd/aniyomi-extensions')}"

repo_meta = {
    "meta": {
        "name": repo_name,
        "shortName": repo_name,
        "website": repo_website,
        "signingKeyFingerprint": fingerprint,
    }
}

with (REPO_DIR / "repo.json").open("w", encoding="utf-8") as f:
    json.dump(repo_meta, f, ensure_ascii=False, indent=2)

import html
with (REPO_DIR / "index.html").open("w", encoding="utf-8") as f:
    f.write('<!DOCTYPE html>\n<html>\n<head>\n<meta charset="UTF-8">\n<title>VoirAnime Extensions</title>\n</head>\n<body>\n<pre>\n')
    for entry in index_min_data:
        apk_escaped = 'apk/' + html.escape(entry["apk"])
        name_escaped = html.escape(entry["name"])
        f.write(f'<a href="{apk_escaped}">{name_escaped}</a>\n')
    f.write('</pre>\n</body>\n</html>\n')

