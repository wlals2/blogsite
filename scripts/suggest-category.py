#!/usr/bin/env python3
"""
새 포스트 카테고리 자동 제안 스크립트

사용법:
  python3 scripts/suggest-category.py "포스트 제목" "tag1,tag2,tag3"
  python3 scripts/suggest-category.py "Istio Traffic Management" "istio,kubernetes,service-mesh"
"""

import yaml
import sys
from collections import defaultdict

# 설정 파일 로드
with open('/home/jimin/blogsite/.blog-categories.yaml', 'r', encoding='utf-8') as f:
    config = yaml.safe_load(f)

CATEGORIES = config['categories']

def suggest_category(title, tags_str=""):
    """포스트 제목과 태그를 기반으로 카테고리 제안"""
    title_lower = title.lower()
    tags = [t.strip().lower() for t in tags_str.split(',')] if tags_str else []

    scores = defaultdict(int)

    # 제목 기반 점수 (가중치 3)
    for cat_id, cat_info in CATEGORIES.items():
        for keyword in cat_info['keywords']:
            if keyword in title_lower:
                scores[cat_id] += 3

    # 태그 기반 점수 (가중치 2)
    for cat_id, cat_info in CATEGORIES.items():
        for tag in tags:
            for keyword in cat_info['keywords']:
                if keyword in tag or tag in keyword:
                    scores[cat_id] += 2

    # 특별 규칙: Troubleshooting
    if "트러블슈팅" in title or "troubleshooting" in title_lower or "문제" in title:
        scores["troubleshooting"] += 10

    # 점수 순으로 정렬
    sorted_cats = sorted(scores.items(), key=lambda x: x[1], reverse=True)

    return sorted_cats

def main():
    if len(sys.argv) < 2:
        print("사용법: python3 scripts/suggest-category.py '포스트 제목' 'tag1,tag2,tag3'")
        sys.exit(1)

    title = sys.argv[1]
    tags_str = sys.argv[2] if len(sys.argv) > 2 else ""

    print("=" * 80)
    print("📝 카테고리 제안")
    print("=" * 80)
    print(f"제목: {title}")
    print(f"태그: {tags_str if tags_str else '(없음)'}")
    print()

    suggestions = suggest_category(title, tags_str)

    if not suggestions:
        print("⚠️  매칭되는 카테고리가 없습니다.")
        print()
        print("💡 사용 가능한 카테고리:")
        for cat_id, cat_info in CATEGORIES.items():
            print(f"  - {cat_info['name']}: {cat_info['description']}")
        sys.exit(0)

    print("🎯 추천 카테고리 (점수순):")
    print()

    for i, (cat_id, score) in enumerate(suggestions[:5], 1):
        cat_info = CATEGORIES[cat_id]
        marker = "✅" if i == 1 else "  "
        print(f"{marker} {i}. {cat_info['name']:<20} (점수: {score})")
        print(f"      {cat_info['description']}")
        print(f"      키워드: {', '.join(cat_info['keywords'][:5])}...")
        print()

    # Front matter 예시 생성
    print("=" * 80)
    print("📄 Front Matter 예시")
    print("=" * 80)
    print()

    # 1위 카테고리
    primary_cat = CATEGORIES[suggestions[0][0]]['name']
    categories = ["study", primary_cat]

    # 2위가 1위의 50% 이상이면 추가
    if len(suggestions) > 1 and suggestions[1][1] >= suggestions[0][1] * 0.5:
        secondary_cat = CATEGORIES[suggestions[1][0]]['name']
        categories.append(secondary_cat)

    print("---")
    print(f'title: "{title}"')
    print(f"date: $(date +%Y-%m-%d)")
    print(f"categories: {categories}")
    if tags_str:
        tags_list = [f'"{t.strip()}"' for t in tags_str.split(',')]
        print(f"tags: [{', '.join(tags_list)}]")
    print("---")
    print()

if __name__ == "__main__":
    main()
