#!/usr/bin/env python3
"""
Study 포스트 사진 썸네일 자동 생성기 (Unsplash / Pexels API)

Usage:
  python3 generate-thumbnails-photo.py --slug <slug>            # 특정 글만
  python3 generate-thumbnails-photo.py --slug <slug> --source pexels
  python3 generate-thumbnails-photo.py --all                    # 커버 없는 글 전체
  python3 generate-thumbnails-photo.py --all --force            # 전체 재생성

API 키 설정:
  export UNSPLASH_ACCESS_KEY="your_key_here"
  export PEXELS_API_KEY="your_key_here"
  또는 스크립트 내 UNSPLASH_KEY / PEXELS_KEY 변수에 직접 입력

API 키 발급:
  Unsplash: https://unsplash.com/developers  (무료, 50건/시간)
  Pexels  : https://www.pexels.com/api/     (무료, 200건/시간)
"""

import os
import re
import sys
import json
import random
import argparse
import urllib.request
import urllib.parse
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import io

# ─── API 키 설정 ──────────────────────────────────────────
# 환경 변수 우선, 없으면 아래 직접 입력
UNSPLASH_KEY = os.environ.get("UNSPLASH_ACCESS_KEY", "")
PEXELS_KEY   = os.environ.get("PEXELS_API_KEY", "")

# ─── 설정 ─────────────────────────────────────────────────
CONTENT_DIR  = Path(__file__).parent.parent / "content" / "study"
IMG_W, IMG_H = 800, 420
FONT_BOLD    = "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"
FONT_REG     = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"

# 카테고리 → Unsplash/Pexels 검색 키워드 매핑
# Why: 카테고리명 그대로 검색하면 관련 없는 이미지가 나올 수 있음
CAT_KEYWORDS = {
    "kubernetes":        "containers cloud server abstract",
    "security":          "cybersecurity dark network shield",
    "observability":     "dashboard monitoring analytics charts",
    "service mesh":      "network connection abstract digital",
    "networking":        "network cables server data center",
    "troubleshooting":   "debugging problem solving code",
    "development":       "programming code laptop dark",
    "cloud & terraform": "cloud sky infrastructure technology",
    "storage":           "server hardware storage technology",
    "elasticsearch":     "search data analytics technology",
}

# 태그 → 검색 키워드 매핑
# Why: 태그는 카테고리보다 더 구체적인 주제를 나타냄 → 더 관련성 높은 이미지 가능
# 우선순위: TAG_KEYWORDS > CAT_KEYWORDS (태그가 더 구체적)
TAG_KEYWORDS = {
    # Security tools
    "wazuh":            "cybersecurity soc analyst monitor screen",
    "falco":            "security surveillance detection system",
    "ids":              "security camera surveillance monitoring",
    "ips":              "security alert firewall protection",
    "siem":             "security operations center monitor",
    "soar":             "automation security response system",
    "devsecops":        "secure code development workflow",
    "sealed-secrets":   "lock encryption key vault secure",
    "gitops":           "software deployment automation git workflow",
    # Kubernetes ecosystem
    "argocd":           "continuous delivery automation software workflow",
    "helm":             "package management deployment",
    "istio":            "network mesh service proxy",
    "cilium":           "network security policy firewall",
    "prometheus":       "monitoring metrics graph dashboard",
    "grafana":          "analytics dashboard visualization chart",
    "loki":             "log aggregation search system",
    "opensearch":       "search data analytics technology",
    # Protocols / concepts
    "mtls":             "encryption secure connection lock",
    "rbac":             "access control permission lock",
    "zero-trust":       "security lock access control",
    "canary":           "deployment pipeline gradual release",
    "ebpf":             "linux kernel system low-level",
    # General tech
    "mysql":            "database server storage rows",
    "terraform":        "infrastructure cloud deployment code",
    "ansible":          "automation script configuration",
    "docker":           "container ship cargo abstract",
    "nginx":            "web server proxy load balance",
}
DEFAULT_KEYWORDS = "technology server abstract dark"


# ─── 헬퍼 ─────────────────────────────────────────────────

def parse_frontmatter(md_path: Path):
    text = md_path.read_text(encoding="utf-8", errors="ignore")
    title, categories, tags, date = "", [], [], ""
    in_fm, in_cats, in_tags = False, False, False
    for line in text.splitlines():
        if line.strip() == "---":
            if not in_fm:
                in_fm = True; continue
            else:
                break
        if not in_fm:
            continue
        if line.startswith("title:"):
            title = re.sub(r'^title:\s*["\']?', '', line).rstrip('"\'').strip()
        elif line.startswith("date:"):
            date = re.sub(r'^date:\s*', '', line).strip()[:10]
        elif line.strip() == "categories:":
            in_cats, in_tags = True, False
        elif line.strip() == "tags:":
            in_tags, in_cats = True, False
        elif in_cats:
            m = re.match(r'\s*-\s*(.+)', line)
            if m: categories.append(m.group(1).strip())
            else: in_cats = False
        elif in_tags:
            m = re.match(r'\s*-\s*(.+)', line)
            if m: tags.append(m.group(1).strip())
            else: in_tags = False

    cat = next((c for c in categories if c.lower() != "study"), "")
    return title, cat, tags, date


def get_search_keywords(category: str, tags: list) -> str:
    """태그 → 카테고리 순으로 Unsplash 검색 키워드 생성
    Why: 태그가 더 구체적인 주제를 나타내므로 우선 적용 → 더 관련성 높은 이미지
    태그에 매핑 없으면 카테고리로 폴백 → 카테고리도 없으면 기본값 사용
    """
    # 태그 먼저 확인 (더 구체적)
    for tag in (tags or []):
        tag_lower = tag.lower().strip()
        for key, kw in TAG_KEYWORDS.items():
            if key in tag_lower:
                return kw

    # 카테고리 폴백
    cat_lower = (category or "").lower().strip()
    for key, kw in CAT_KEYWORDS.items():
        if key in cat_lower:
            return kw

    return DEFAULT_KEYWORDS


def fetch_unsplash(keywords: str) -> tuple | None:
    """Unsplash에서 이미지 URL 검색"""
    if not UNSPLASH_KEY:
        print("  ⚠️  UNSPLASH_ACCESS_KEY 없음")
        return None

    # Why: /photos/random은 결과 없으면 404 반환 → /search/photos 사용
    # page 랜덤화: 같은 키워드라도 다양한 사진 선택
    page = random.randint(1, 5)
    query = urllib.parse.quote(keywords)
    url = (
        f"https://api.unsplash.com/search/photos"
        f"?query={query}&orientation=landscape&per_page=10&page={page}&client_id={UNSPLASH_KEY}"
    )
    try:
        req = urllib.request.Request(url, headers={"Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read())
            results = data.get("results", [])
            if not results:
                # 결과 없으면 page=1로 재시도
                url2 = url.replace(f"&page={page}", "&page=1")
                req2 = urllib.request.Request(url2, headers={"Accept": "application/json"})
                with urllib.request.urlopen(req2, timeout=15) as resp2:
                    data = json.loads(resp2.read())
                    results = data.get("results", [])
                if not results:
                    print(f"  Unsplash: 결과 없음 (query: {keywords[:40]})")
                    return None
            photo = random.choice(results)
            # regular URL: 1080px 고화질, Pillow로 리사이즈
            photo_url = photo["urls"]["regular"]
            credit = f"Photo by {photo['user']['name']} on Unsplash"
            return photo_url, credit
    except Exception as e:
        print(f"  Unsplash 오류: {e}")
        return None


def fetch_pexels(keywords: str) -> tuple | None:
    """Pexels에서 이미지 URL 검색"""
    if not PEXELS_KEY:
        print("  ⚠️  PEXELS_API_KEY 없음")
        return None

    query = urllib.parse.quote(keywords)
    url = f"https://api.pexels.com/v1/search?query={query}&orientation=landscape&per_page=1&size=large"
    try:
        req = urllib.request.Request(
            url, headers={"Authorization": PEXELS_KEY}
        )
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read())
            photos = data.get("photos", [])
            if not photos:
                print(f"  Pexels: 결과 없음 (query: {keywords})")
                return None
            photo = photos[0]
            photo_url = photo["src"]["large2x"]  # 고화질
            credit = f"Photo by {photo['photographer']} on Pexels"
            return photo_url, credit
    except Exception as e:
        print(f"  Pexels 오류: {e}")
        return None


def download_image(url: str) -> Image.Image | None:
    """URL에서 이미지 다운로드"""
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "BlogThumbnailBot/1.0"})
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = resp.read()
            img = Image.open(io.BytesIO(data)).convert("RGB")
            return img
    except Exception as e:
        print(f"  다운로드 오류: {e}")
        return None


def crop_center(img: Image.Image, target_w: int, target_h: int) -> Image.Image:
    """이미지를 target 비율로 중앙 크롭 후 리사이즈"""
    src_w, src_h = img.size
    target_ratio = target_w / target_h
    src_ratio = src_w / src_h

    if src_ratio > target_ratio:
        # 가로가 더 넓음 → 세로 기준 크롭
        new_w = int(src_h * target_ratio)
        offset = (src_w - new_w) // 2
        img = img.crop((offset, 0, offset + new_w, src_h))
    else:
        # 세로가 더 높음 → 가로 기준 크롭
        new_h = int(src_w / target_ratio)
        offset = (src_h - new_h) // 2
        img = img.crop((0, offset, src_w, offset + new_h))

    return img.resize((target_w, target_h), Image.LANCZOS)


def add_overlay(img: Image.Image, category: str, title: str) -> Image.Image:
    """
    사진 위에 오버레이 추가:
    - 하단 그라디언트 다크 오버레이 (텍스트 가독성)
    - 카테고리 라벨 (좌하단 pill)
    - 사이트명 (우하단)
    """
    # 하단 그라디언트 오버레이
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(overlay)
    start = img.height // 3
    for y in range(start, img.height):
        t = (y - start) / (img.height - start)
        alpha = int(200 * (t ** 1.2))
        d.line([(0, y), (img.width, y)], fill=(0, 0, 0, alpha))
    base = img.convert("RGBA")
    merged = Image.alpha_composite(base, overlay)
    img = merged.convert("RGB")

    draw = ImageDraw.Draw(img)

    try:
        font_cat  = ImageFont.truetype(FONT_BOLD, 18)
        font_site = ImageFont.truetype(FONT_REG,  15)
    except Exception:
        font_cat = font_site = ImageFont.load_default()

    PAD = 20

    # 카테고리 라벨 (좌하단)
    cat_text = (category or "STUDY").upper()
    cb = draw.textbbox((0, 0), cat_text, font=font_cat)
    cw, ch = cb[2] - cb[0], cb[3] - cb[1]
    cx = PAD
    cy = img.height - PAD - ch - 16
    draw.rounded_rectangle(
        [cx - 10, cy - 6, cx + cw + 10, cy + ch + 6],
        radius=16, fill=(0, 0, 0, 140)
    )
    draw.text((cx, cy), cat_text, font=font_cat, fill=(255, 255, 255))

    # 사이트명 (우하단)
    site = "blog.jiminhome.shop"
    sb = draw.textbbox((0, 0), site, font=font_site)
    sw = sb[2] - sb[0]
    draw.text(
        (img.width - PAD - sw, img.height - PAD - (sb[3] - sb[1])),
        site, font=font_site, fill=(255, 255, 255, 180)
    )

    return img


# ─── 핵심 로직 ────────────────────────────────────────────

def generate(post_dir: Path, source: str = "unsplash", force: bool = False):
    md = post_dir / "index.md"
    if not md.exists():
        return False

    out = post_dir / "cover.jpg"
    if out.exists() and not force:
        print(f"  SKIP  {post_dir.name}")
        return False

    title, category, tags, date = parse_frontmatter(md)
    if not title:
        print(f"  SKIP  {post_dir.name} (title 없음)")
        return False

    keywords = get_search_keywords(category, tags)
    print(f"  검색  {post_dir.name[:40]:40s} [{keywords[:40]}]")

    # API 호출
    result = None
    if source == "pexels":
        result = fetch_pexels(keywords)
        if not result and UNSPLASH_KEY:
            print("  Pexels 실패 → Unsplash fallback")
            result = fetch_unsplash(keywords)
    else:
        result = fetch_unsplash(keywords)
        if not result and PEXELS_KEY:
            print("  Unsplash 실패 → Pexels fallback")
            result = fetch_pexels(keywords)

    if not result:
        print(f"  FAIL  {post_dir.name} (이미지 없음)")
        return False

    photo_url, credit = result

    # 이미지 다운로드 + 크롭
    img = download_image(photo_url)
    if not img:
        print(f"  FAIL  {post_dir.name} (다운로드 실패)")
        return False

    img = crop_center(img, IMG_W, IMG_H)

    # 오버레이 추가
    img = add_overlay(img, category, title)

    # 저장
    img.save(out, "JPEG", quality=92)
    print(f"  OK    {post_dir.name}  ({credit})")
    return True


# ─── 메인 ─────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Unsplash/Pexels 사진으로 썸네일 생성")
    parser.add_argument("--slug",   type=str,   help="특정 글 slug만")
    parser.add_argument("--all",    action="store_true", help="커버 없는 글 전체")
    parser.add_argument("--force",  action="store_true", help="기존 커버 포함 재생성")
    parser.add_argument("--source", type=str, default="unsplash",
                        choices=["unsplash", "pexels"], help="이미지 출처 (기본: unsplash)")
    args = parser.parse_args()

    # API 키 확인
    if not UNSPLASH_KEY and not PEXELS_KEY:
        print("❌ API 키가 없습니다.")
        print("   export UNSPLASH_ACCESS_KEY='your_key'")
        print("   export PEXELS_API_KEY='your_key'")
        sys.exit(1)

    if args.slug:
        dirs = [CONTENT_DIR / args.slug]
    elif args.all:
        dirs = sorted(
            [d for d in CONTENT_DIR.iterdir() if d.is_dir()],
            reverse=True
        )
    else:
        # 기본: 가장 최신 글 1개만
        dirs_all = sorted(
            [d for d in CONTENT_DIR.iterdir() if d.is_dir()],
            reverse=True
        )
        dirs = [dirs_all[0]] if dirs_all else []

    generated = 0
    for d in dirs:
        if generate(d, source=args.source, force=args.force):
            generated += 1

    print(f"\n완료: {generated}개 생성")


if __name__ == "__main__":
    main()
