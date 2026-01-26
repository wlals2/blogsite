#!/usr/bin/env python3
"""
study 포스트 카테고리 자동 업데이트 스크립트

사용법:
  python3 scripts/update-categories.py           # 드라이런 (변경 없음)
  python3 scripts/update-categories.py --apply   # 실제 적용
"""

import os
import re
import yaml
from pathlib import Path
from collections import defaultdict
import argparse

# 설정 파일 로드
with open('/home/jimin/blogsite/.blog-categories.yaml', 'r', encoding='utf-8') as f:
    config = yaml.safe_load(f)

CATEGORIES = config['categories']
STUDY_DIR = Path("/home/jimin/blogsite/content/study")

def categorize_post(title, tags):
    """포스트 제목과 태그를 기반으로 카테고리 자동 분류"""
    title_lower = title.lower()
    tags_lower = [t.lower() for t in tags]

    scores = defaultdict(int)

    # 제목 기반 점수 (가중치 2)
    for cat_id, cat_info in CATEGORIES.items():
        for keyword in cat_info['keywords']:
            if keyword in title_lower:
                scores[cat_id] += 2

    # 태그 기반 점수 (가중치 1)
    for cat_id, cat_info in CATEGORIES.items():
        for tag in tags_lower:
            for keyword in cat_info['keywords']:
                if keyword in tag:
                    scores[cat_id] += 1

    # 특별 규칙: Troubleshooting이 제목에 명시적으로 있으면 우선
    if "트러블슈팅" in title or "troubleshooting" in title_lower:
        scores["troubleshooting"] += 10

    # 가장 높은 점수의 카테고리 선택 (최대 2개)
    if scores:
        sorted_cats = sorted(scores.items(), key=lambda x: x[1], reverse=True)
        # 1위 카테고리는 무조건 포함
        result = [CATEGORIES[sorted_cats[0][0]]['name']]
        # 2위가 1위의 50% 이상 점수면 포함
        if len(sorted_cats) > 1 and sorted_cats[1][1] >= sorted_cats[0][1] * 0.5:
            result.append(CATEGORIES[sorted_cats[1][0]]['name'])
        return result
    else:
        return []

def update_front_matter(file_path, dry_run=True):
    """포스트의 front matter에 카테고리 추가"""
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            content = f.read()

        # Front matter 추출
        match = re.search(r'^---\s*\n(.*?)\n---', content, re.DOTALL)
        if not match:
            return None, "No front matter"

        front_matter = match.group(1)

        # 제목 추출
        title_match = re.search(r'^title:\s*["\']?(.*?)["\']?\s*$', front_matter, re.MULTILINE)
        title = title_match.group(1) if title_match else "No Title"

        # 태그 추출
        tags_match = re.search(r'^tags:\s*\[(.*?)\]', front_matter, re.MULTILINE)
        tags = tags_match.group(1) if tags_match else ""
        tags = [t.strip().strip('"') for t in tags.split(',')] if tags else []

        # 기존 categories 확인
        categories_match = re.search(r'^categories:\s*\[(.*?)\]', front_matter, re.MULTILINE)
        existing_categories = categories_match.group(1) if categories_match else ""

        # 카테고리 자동 분류
        suggested_categories = categorize_post(title, tags)

        # study + 세부 카테고리 형식으로 생성
        new_categories = '["study"' + ''.join([f', "{cat}"' for cat in suggested_categories]) + ']'

        # Front matter 업데이트
        if categories_match:
            # 기존 categories 교체
            new_front_matter = re.sub(
                r'^categories:\s*\[.*?\]',
                f'categories: {new_categories}',
                front_matter,
                flags=re.MULTILINE
            )
        else:
            # categories 없으면 tags 다음에 추가
            if tags_match:
                new_front_matter = re.sub(
                    r'(^tags:\s*\[.*?\])',
                    f'\\1\ncategories: {new_categories}',
                    front_matter,
                    flags=re.MULTILINE
                )
            else:
                # tags도 없으면 title 다음에 추가
                new_front_matter = re.sub(
                    r'(^title:.*$)',
                    f'\\1\ncategories: {new_categories}',
                    front_matter,
                    flags=re.MULTILINE
                )

        # 전체 콘텐츠 업데이트
        new_content = content.replace(front_matter, new_front_matter)

        if not dry_run:
            # 백업 생성
            backup_path = file_path.with_suffix('.md.bak')
            with open(backup_path, 'w', encoding='utf-8') as f:
                f.write(content)

            # 파일 업데이트
            with open(file_path, 'w', encoding='utf-8') as f:
                f.write(new_content)

        return suggested_categories, None

    except Exception as e:
        return None, str(e)

def main():
    parser = argparse.ArgumentParser(description='study 포스트 카테고리 자동 업데이트')
    parser.add_argument('--apply', action='store_true', help='실제로 파일 수정 (기본: 드라이런)')
    args = parser.parse_args()

    dry_run = not args.apply

    if dry_run:
        print("🔍 드라이런 모드 (파일 변경 없음)")
        print("실제 적용하려면: python3 scripts/update-categories.py --apply")
    else:
        print("✏️  실제 적용 모드 (파일 수정)")
    print()

    # 통계
    stats = defaultdict(int)
    errors = []

    # 전체 포스트 처리
    for item in STUDY_DIR.rglob("*.md"):
        if item.name in ["_index.md", "README.md"]:
            continue

        categories, error = update_front_matter(item, dry_run=dry_run)

        if error:
            errors.append((item.name, error))
            stats['errors'] += 1
        else:
            for cat in categories:
                stats[cat] += 1
            stats['total'] += 1

            # 처음 10개만 출력
            if stats['total'] <= 10:
                print(f"✅ {item.name}")
                print(f"   → {', '.join(categories)}")

    # 결과 출력
    print()
    print("=" * 80)
    print("📊 카테고리별 통계")
    print("=" * 80)

    for cat_id, cat_info in CATEGORIES.items():
        count = stats.get(cat_info['name'], 0)
        if count > 0:
            print(f"  {cat_info['name']:<20} {count:>3}개")

    print()
    print(f"총 {stats['total']}개 포스트 처리")

    if errors:
        print()
        print("⚠️  오류 발생:")
        for filename, error in errors:
            print(f"  - {filename}: {error}")

    if dry_run:
        print()
        print("💡 실제 적용하려면: python3 scripts/update-categories.py --apply")

if __name__ == "__main__":
    main()
