#!/usr/bin/env python3
"""
Study 포스트 썸네일 자동 생성기

Usage:
  python3 generate-thumbnails.py              # 커버 없는 글 전체 생성
  python3 generate-thumbnails.py --all        # 기존 커버 포함 전체 재생성
  python3 generate-thumbnails.py --slug xxxx  # 특정 글만 생성
"""

import os
import re
import argparse
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

# ─── 설정 ────────────────────────────────────────────
CONTENT_DIR  = Path(__file__).parent.parent / "content" / "study"
IMG_W, IMG_H = 800, 420
FONT_BOLD    = "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"
FONT_REG     = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"

CAT_COLORS = {
    "kubernetes":        ((50,  108, 229), (26,  79,  214)),
    "security":          ((229, 62,  62),  (180, 30,  30)),
    "observability":     ((221, 107, 32),  (180, 70,  10)),
    "service mesh":      ((70,  107, 176), (40,  70,  140)),
    "networking":        ((16,  185, 129), (5,   130,  90)),
    "troubleshooting":   ((124, 58,  237), (80,  20,  180)),
    "development":       ((213, 63,  140), (160, 30,  100)),
    "cloud & terraform": ((100, 60,  30),  (60,  30,  10)),
    "storage":           ((74,  85,  104), (40,  50,  65)),
    "elasticsearch":     ((214, 158, 46),  (160, 110, 20)),
}
DEFAULT_COLOR = ((14, 165, 233), (2, 100, 180))

# ─── 헬퍼 ────────────────────────────────────────────

def get_colors(category: str):
    cat = (category or "").lower().strip()
    for key, val in CAT_COLORS.items():
        if key in cat:
            return val
    return DEFAULT_COLOR


def draw_gradient_bg(img, c1, c2):
    """좌→우 수평 그라디언트 배경"""
    draw = ImageDraw.Draw(img)
    for x in range(img.width):
        t = x / max(img.width - 1, 1)
        r = int(c1[0] + (c2[0] - c1[0]) * t)
        g = int(c1[1] + (c2[1] - c1[1]) * t)
        b = int(c1[2] + (c2[2] - c1[2]) * t)
        draw.line([(x, 0), (x, img.height)], fill=(r, g, b))


def draw_bottom_overlay(img):
    """하단 반투명 다크 오버레이 — 텍스트 가독성용"""
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(overlay)
    start = img.height // 3
    for y in range(start, img.height):
        t = (y - start) / (img.height - start)
        alpha = int(160 * t ** 1.4)
        d.line([(0, y), (img.width, y)], fill=(0, 0, 0, alpha))
    base = img.convert("RGBA")
    merged = Image.alpha_composite(base, overlay)
    img.paste(merged.convert("RGB"))


def draw_deco(img, c1):
    """우상단 장식 원"""
    d = ImageDraw.Draw(img)
    light = tuple(min(255, v + 50) for v in c1)
    d.ellipse([IMG_W - 160, -80, IMG_W + 80, 160], fill=(*light, 40))
    # 좌상단 작은 원
    d.ellipse([-40, -40, 120, 120], fill=(*light, 20))


def parse_frontmatter(md_path: Path):
    text = md_path.read_text(encoding="utf-8", errors="ignore")
    title, categories, date = "", [], ""
    in_fm, in_cats = False, False
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
            in_cats = True
        elif in_cats:
            m = re.match(r'\s*-\s*(.+)', line)
            if m:
                categories.append(m.group(1).strip())
            else:
                in_cats = False

    cat = next((c for c in categories if c.lower() != "study"), categories[0] if categories else "")
    return title, cat, date


def wrap_title(text, font, max_w, draw, max_lines=3):
    """
    영문 단어 단위 + 한글 글자 단위 혼합 줄바꿈.
    영문 'word' 가 잘리지 않도록 공백 기준 우선 처리.
    """
    import re as _re
    # 토큰 분리: 영문 단어, 한글/특수문자, 공백
    tokens = _re.findall(r'[a-zA-Z0-9/+_\-\.]+|[\s]+|[^\s]', text)

    lines, cur = [], ""
    for tok in tokens:
        test = cur + tok
        w = draw.textbbox((0, 0), test, font=font)[2]
        if w > max_w and cur.strip():
            # 공백 토큰이라면 버리고 다음 줄
            if tok.strip() == "":
                lines.append(cur.rstrip())
                cur = ""
            else:
                lines.append(cur.rstrip())
                cur = tok.lstrip()
            if len(lines) >= max_lines - 1:
                # 남은 텍스트 합쳐서 마지막 줄로
                rest_idx = tokens.index(tok)
                cur = "".join(tokens[rest_idx:]).strip()
                break
        else:
            cur = test

    if cur.strip():
        lines.append(cur.strip())
    # 최대 줄 수 초과 시 말줄임
    if len(lines) > max_lines:
        lines = lines[:max_lines]
        lines[-1] = lines[-1].rstrip()[:-1] + "…"
    return lines


def generate(post_dir: Path, force=False):
    md = post_dir / "index.md"
    if not md.exists():
        return False

    out = post_dir / "cover.jpg"
    if out.exists() and not force:
        print(f"  SKIP  {post_dir.name}")
        return False

    title, category, date = parse_frontmatter(md)
    if not title:
        print(f"  SKIP  {post_dir.name} (title 없음)")
        return False

    c1, c2 = get_colors(category)

    # ── 이미지 베이스 ─────────────────────────
    img = Image.new("RGB", (IMG_W, IMG_H), c1)
    draw_gradient_bg(img, c1, c2)
    draw_deco(img, c1)
    draw_bottom_overlay(img)

    draw = ImageDraw.Draw(img)

    try:
        font_title = ImageFont.truetype(FONT_BOLD, 44)
        font_cat   = ImageFont.truetype(FONT_BOLD, 20)
        font_site  = ImageFont.truetype(FONT_REG,  17)
    except Exception as e:
        print(f"  ERROR {post_dir.name}: {e}")
        return False

    PAD = 50

    # ── 카테고리 라벨 (상단) ───────────────────
    cat_text = (category or "STUDY").upper()
    cb = draw.textbbox((0, 0), cat_text, font=font_cat)
    cw, ch = cb[2] - cb[0], cb[3] - cb[1]
    cx, cy = PAD, PAD
    # 어두운 반투명 pill 배경 → 흰 텍스트가 선명하게 보임
    draw.rounded_rectangle(
        [cx - 14, cy - 8, cx + cw + 14, cy + ch + 8],
        radius=20,
        fill=(0, 0, 0, 110)
    )
    draw.text((cx, cy), cat_text, font=font_cat, fill=(255, 255, 255))

    # ── 제목 (하단 영역) ───────────────────────
    lines = wrap_title(title, font_title, IMG_W - PAD * 2, draw)
    line_h  = draw.textbbox((0, 0), "가A", font=font_title)[3] + 10
    total_h = line_h * len(lines)

    # 하단 1/3 영역 수직 중앙 정렬
    area_top = IMG_H // 2
    ty = area_top + (IMG_H - area_top - total_h) // 2

    for line in lines:
        draw.text((PAD, ty), line, font=font_title,
                  fill="white",
                  stroke_width=1, stroke_fill=(0, 0, 0))
        ty += line_h

    # ── 사이트명 (하단 우측) ───────────────────
    site = "blog.jiminhome.shop"
    sb   = draw.textbbox((0, 0), site, font=font_site)
    sw   = sb[2] - sb[0]
    draw.text((IMG_W - PAD - sw, IMG_H - PAD),
              site, font=font_site, fill=(255, 255, 255, 180))

    img.save(out, "JPEG", quality=92)
    print(f"  OK    {post_dir.name}  [{category or '기본'}]")
    return True


# ─── 메인 ────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--all",  action="store_true", help="기존 커버 포함 재생성")
    parser.add_argument("--slug", type=str,            help="특정 글 slug만")
    args = parser.parse_args()

    if args.slug:
        dirs = [CONTENT_DIR / args.slug]
    else:
        dirs = sorted(
            [d for d in CONTENT_DIR.iterdir() if d.is_dir()],
            reverse=True
        )

    generated = 0
    for d in dirs:
        if generate(d, force=args.all):
            generated += 1

    print(f"\n완료: {generated}개 생성")

if __name__ == "__main__":
    main()
