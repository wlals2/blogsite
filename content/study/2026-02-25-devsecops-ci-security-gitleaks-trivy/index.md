---
title: "[DevSecOps 시리즈 #1] CI/CD 보안 파이프라인 구축기 — GitLeaks와 Trivy로 코드부터 이미지까지"
date: 2026-02-25T14:00:00+09:00
categories:
  - study
  - Security
tags: ["devsecops", "trivy", "gitleaks", "github-actions", "ci-cd", "container-security"]
summary: "GitHub Actions CI/CD에 GitLeaks(Secret 탐지)와 Trivy(이미지 CVE 스캔)를 통합한 과정. 각 YAML 필드의 설계 의도와 다른 환경에 적용하는 방법까지."
showtoc: true
tocopen: true
draft: false
series: ["DevSecOps 시리즈"]
---

## 배경 — CI/CD에 보안이 왜 필요한가

블로그 서비스를 Kubernetes에서 운영하면서, 런타임 보안은 Falco + Wazuh로 갖춰놨다. 하지만 **배포 전 단계**는 비어 있었다.

```
Before:
  Git Push → Docker Build → ArgoCD 배포 → (런타임에서야 탐지)
  ❌ 코드에 DB 비밀번호가 포함되어도 → 빌드 통과
  ❌ 이미지에 CVE 취약점이 있어도 → 배포 완료

After:
  Git Push → GitLeaks(Secrets) → Trivy(CVE) → ArgoCD 배포 → Falco(Runtime)
  ✅ 비밀번호 커밋 → 즉시 탐지
  ✅ 취약한 패키지 → 알림 후 배포
```

이것을 **Shift Left**라고 부른다. 보안 검사를 배포 직전이 아닌, **개발 단계(왼쪽)로 당기는 것**이다. 문제를 일찍 발견할수록 수정 비용이 낮다.

### 전체 DevSecOps 파이프라인

```
Git Push → GitLeaks → Docker Build → Trivy → K8s 배포 → Falco+Talon
           (Secrets)                 (CVE)   (ArgoCD)   (Runtime IPS)
           ←────────── Shift Left ──────────→ ←─ Runtime Security ─→
```

| 도구 | 역할 | 탐지 시점 |
|------|------|----------|
| **GitLeaks** | Git 히스토리에서 비밀(API Key, Password) 탐지 | 코드 커밋 시 |
| **Trivy** | Docker 이미지 안 OS 패키지/라이브러리 CVE 스캔 | 빌드 후 |
| **Falco** | 컨테이너 내부 이상 행동(Shell 실행, 파일 접근) 탐지 | 런타임 |
| **Wazuh** | 보안 이벤트 상관 분석 (SIEM) | 상시 |

이 글에서는 **GitLeaks와 Trivy**를 CI/CD에 어떻게 통합했는지 상세하게 다룬다.

---

## GitLeaks — Git 히스토리 전체를 스캔하는 Secret 탐지기

### GitLeaks가 하는 일

GitLeaks는 Git 커밋 히스토리를 전체 순회하면서, **비밀처럼 보이는 문자열**을 정규식으로 찾아낸다.

핵심: 현재 코드에서 삭제했어도, **Git 히스토리에는 남아있다**. 예를 들어:

```
커밋 1: DB_PASSWORD=mySecret123 추가
커밋 2: DB_PASSWORD=mySecret123 삭제
→ 현재 코드에는 없음
→ git log -p로 커밋 1을 보면 여전히 보임
→ GitLeaks가 이것을 탐지
```

이것이 Trivy나 SonarQube 같은 도구와의 **결정적 차이**다. Trivy는 현재 이미지 안의 파일만 본다. GitLeaks는 **과거 커밋까지 전부** 본다.

### YAML 구현 상세

```yaml
secrets-scan:
  if: github.event_name != 'schedule'   # (1) 야간 Trivy 스캔 시에는 실행 안 함
  runs-on: self-hosted                   # (2) 자체 호스팅 Runner 사용
  steps:
    - name: Checkout code
      uses: actions/checkout@v4
      with:
        fetch-depth: 0                   # (3) 전체 히스토리 클론

    - name: Scan for secrets with GitLeaks
      uses: gitleaks/gitleaks-action@v2  # (4) GitLeaks 공식 Action
      env:
        GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        GITLEAKS_ENABLE_SUMMARY: true    # (5) GitHub Summary에 결과 표시
```

각 필드가 **왜 이렇게** 설정되었는지:

#### (1) `if: github.event_name != 'schedule'`

```yaml
# 이 워크플로우에는 2가지 트리거가 있다:
on:
  push:       # 코드 Push 시 → 전체 CI 실행
  schedule:   # 매일 02:00 → Trivy 야간 스캔만 실행

# schedule 이벤트일 때 secrets-scan을 실행하면?
# → 불필요한 Git 히스토리 스캔 (코드 변경 없으므로)
# → 그래서 schedule일 때는 건너뜀
```

#### (2) `runs-on: self-hosted`

GitHub에서 제공하는 호스팅 Runner 대신, Homelab 서버에 직접 설치한 Runner를 사용한다. 이유:
- GitHub 호스팅 Runner는 월 2,000분 무료 제한이 있다
- self-hosted는 제한 없고, Docker 이미지가 로컬에 캐싱되어 빠르다
- 단, 보안 주의 필요 — public repo에서는 외부 PR이 Runner에서 코드를 실행할 수 있다

#### (3) `fetch-depth: 0` — 전체 히스토리 클론

```yaml
# 기본값: fetch-depth: 1 (최신 커밋 1개만)
# → GitLeaks가 과거 커밋을 볼 수 없음
# → "커밋 1에서 추가하고 커밋 2에서 삭제한 비밀"을 놓침

# fetch-depth: 0 = 전체 히스토리 클론
# → 모든 커밋을 순회하며 비밀 탐지
# → 단점: 클론 시간 증가 (히스토리가 클수록)
```

이것이 **GitLeaks 사용 시 가장 중요한 설정**이다. `fetch-depth: 1`이면 GitLeaks를 쓰는 의미가 절반으로 줄어든다.

#### (4) GitLeaks Action 버전

```yaml
uses: gitleaks/gitleaks-action@v2
```

`@v2`는 메이저 버전 태그다. `@v2.3.1` 같은 정확한 버전 대신 `@v2`를 쓰면 2.x 범위의 최신 패치가 자동 적용된다. 보안 도구이므로 최신 탐지 규칙이 중요해서 이렇게 설정했다.

#### (5) `GITLEAKS_ENABLE_SUMMARY: true`

스캔 결과를 GitHub Actions의 **Job Summary** 탭에 표로 보여준다. 워크플로우 로그를 뒤질 필요 없이, Summary 탭에서 바로 "어떤 파일의 몇 번째 줄에서 발견되었는지" 확인 가능하다.

### 오탐 관리 — `.gitleaks.toml`

GitLeaks가 정상적인 문자열을 비밀로 오탐할 수 있다. 예를 들어 예제 코드의 `example_api_key=abc123`도 탐지될 수 있다.

```toml
# .gitleaks.toml (프로젝트 루트)
[allowlist]
  description = "허용 목록"
  paths = [
    '''docs/examples/.*'''    # 예제 코드 디렉토리는 스캔 제외
  ]
```

오탐이 발생하면 이 파일로 **명시적으로** 예외를 등록한다. "왜 이것을 허용했는가"를 주석으로 남기는 것이 중요하다.

### GitLeaks 내부 동작 — 무엇을 어떻게 찾는가

GitLeaks는 약 **150개 이상의 정규식 규칙**이 내장되어 있다. AWS, GCP, Azure, GitHub, Slack, Discord 등 주요 서비스의 Key/Token 패턴을 알고 있다.

탐지 패턴 예시:

```
AWS Access Key:     AKIA[0-9A-Z]{16}
AWS Secret Key:     [0-9a-zA-Z/+]{40}
GitHub Token:       ghp_[0-9a-zA-Z]{36}
Slack Webhook:      https://hooks.slack.com/services/T.../B.../...
Discord Webhook:    https://discord(app)?.com/api/webhooks/[0-9]+/...
Generic Password:   (?i)(password|passwd|pwd)\s*=\s*['"][^\s'"]+['"]
Private Key:        -----BEGIN (RSA|DSA|EC|OPENSSH) PRIVATE KEY-----
```

내부적으로 이런 순서로 동작한다:

```
1. gitleaks 바이너리 실행
2. git log --all --diff-filter=ACDMRT (모든 커밋의 변경 내역)
3. 각 변경 내역에서 정규식 패턴 매칭
4. 매칭되면 → "비밀 발견" 보고 + Job 실패 (exit-code 1)
```

### GitLeaks vs Trivy — 배포 차단 여부가 다른 이유

```
GitLeaks 탐지 시: secrets-scan Job 실패 → deploy 실행 안 됨 → 배포 차단
Trivy 탐지 시:   exit-code: '0' → 경고만 → 배포 진행
```

왜 다르게 설정했는가?

| | GitLeaks (차단) | Trivy (경고만) |
|---|---|---|
| **탐지 대상** | 코드에 하드코딩된 비밀 | OS/라이브러리 CVE |
| **수정 가능?** | 즉시 가능 (비밀 제거) | Base Image CVE는 직접 패치 불가 |
| **방치 위험** | 비밀 노출 → 즉각적 해킹 가능 | CVE 존재 → 악용 조건 필요 |
| **판단** | 무조건 수정해야 함 → 차단 | 판단 후 대응 → 경고 |

### 비밀이 발견되면 어떻게 대응하는가

```
1. secrets-scan Job 실패 → deploy 차단됨
2. GitHub Actions Summary 탭에서 확인:
   → 어떤 파일, 몇 번째 줄, 무슨 규칙에 걸렸는지

3. 대응 (2가지 경우):

   [실제 비밀인 경우]
   ① 즉시 비밀 Rotation (새 키 발급 → 기존 키 폐기)
   ② 코드에서 제거 → SealedSecret 또는 GitHub Secrets로 이동
   ③ BFG Repo Cleaner로 Git 히스토리에서도 완전 제거
   ④ force push (주의: 히스토리를 다시 쓰는 작업)

   [오탐인 경우]
   ① .gitleaks.toml에 예외 등록 (사유 주석 포함)
   ② 재실행 → 통과 확인
```

③번이 중요하다. 코드에서 삭제하는 것만으로는 부족하다. Git 히스토리에 남아있기 때문이다:

```bash
# BFG Repo Cleaner로 히스토리에서 특정 문자열 제거
java -jar bfg.jar --replace-text passwords.txt repo.git

# 이후 force push
git push --force
```

### 현재 구현의 빈 구간 — WEB에는 GitLeaks가 없다

```
WAS (deploy-was.yml):
  secrets-scan ✅ → test → build-push → deploy

WEB (deploy-web.yml):
  build → Trivy → deploy
  ❌ GitLeaks 없음
```

WEB 파이프라인은 초기에 단순하게 만들었기 때문이다. WEB 레포에도 설정 파일이나 Hugo config에 비밀이 포함될 수 있으므로 GitLeaks 추가가 필요하다.

### 다른 환경/도구와의 연동 — GitLeaks를 확장하는 방법

#### Pre-commit Hook — Push 전 로컬 탐지

현재는 GitHub에 Push **한 후에** GitLeaks가 실행된다. 이미 히스토리에 올라간 뒤다.

```
현재:
  로컬 commit → push → GitHub Actions에서 GitLeaks → 탐지
  ❌ 이미 Push됨 → 히스토리 정리 필요

개선:
  로컬 commit 시도 → pre-commit hook → 탐지 → commit 자체를 차단
  ✅ 히스토리에 비밀이 남는 것 자체를 방지
```

```yaml
# .pre-commit-config.yaml (프로젝트 루트)
repos:
  - repo: https://github.com/gitleaks/gitleaks
    rev: v8.18.0
    hooks:
      - id: gitleaks
```

이중 방어가 된다: Pre-commit(로컬)에서 놓쳐도 GitHub Actions(서버)에서 잡는다.

#### Wazuh SIEM 연동

GitLeaks 탐지 이벤트를 Wazuh SIEM에 보안 로그로 수집할 수 있다. 현재는 구현되어 있지 않지만, 연동하면 Wazuh 대시보드에서 "런타임 공격(Falco)" + "CI/CD 비밀 유출(GitLeaks)"을 한 화면에서 볼 수 있다. MITRE ATT&CK 매핑도 가능하다 (T1552: Unsecured Credentials).

#### GitHub Branch Protection

PR 기반 워크플로우에서는 Branch Protection Rule에 `secrets-scan`을 필수 체크로 등록할 수 있다. GitLeaks 실패 시 Merge 버튼이 비활성화된다.

---

## Trivy — Docker 이미지의 취약점 스캐너

### Trivy가 하는 일

Docker 이미지 안에 설치된 패키지들의 버전을 읽고, **NVD(National Vulnerability Database)**의 CVE 목록과 대조한다.

```
Docker 이미지:
  ├── Alpine 3.19 (OS)
  │   ├── libssl 3.1.4  → CVE-2024-XXXXX (CRITICAL)
  │   └── busybox 1.36  → 취약점 없음
  └── Java 라이브러리
      ├── spring-web 6.1.0  → 취약점 없음
      └── jackson 2.15.0    → CVE-2024-YYYYY (HIGH)
```

CVE(Common Vulnerabilities and Exposures)는 전 세계적으로 공유되는 취약점 번호다. `CVE-2021-44228`은 2021년에 발견된 Log4Shell 취약점이다. 보안 패치를 하려면 "어떤 CVE가 해당되는지"부터 알아야 한다.

### 구현: 2가지 스캔 방식

같은 Trivy인데 WEB과 WAS에서 **다른 방식**으로 통합했다. 이유가 있다.

### 방식 1: WEB — Push 시 Inline 스캔

```yaml
# deploy-web.yml (Step 4.6)
- name: Scan image with Trivy
  uses: aquasecurity/trivy-action@0.28.0       # (1) 버전 고정
  with:
    image-ref: ${{ env.IMAGE_NAME }}:v${{ github.run_number }}  # (2) 방금 빌드한 이미지
    format: 'table'                             # (3) 표 형태 출력
    exit-code: '0'                              # (4) 취약점 발견해도 빌드 실패 안 함
    severity: 'CRITICAL,HIGH'                   # (5) 심각한 것만
    timeout: '5m'                               # (6) 타임아웃
```

**WEB 파이프라인은 단일 Job** 구조다:

```
Checkout → Build → Verify → Trivy → GitOps Deploy → Discord
(하나의 Job 안에서 순서대로)
```

Trivy가 빌드 직후, 배포 직전에 위치한다. 스캔 시간이 전체 빌드 시간에 포함되지만, Job이 하나라서 구조가 단순하다.

### 방식 2: WAS — 야간 스케줄 스캔 (Nightly)

WAS는 구조가 다르다. **3개 Job이 병렬**로 실행된다:

```
git push →
  ├─[병렬] secrets-scan   ~30초
  ├─[병렬] test            ~1분
  └─[병렬] build-push      ~2분
          │
          └─(3개 모두 성공) → deploy  ~1분

총 Push CI: ~3분 (병렬이므로 가장 긴 Job 기준)
```

여기에 Trivy를 넣으려면? 4번째 병렬 Job을 추가하거나, build-push 안에 Step을 추가해야 한다. 두 방법 모두 **빌드 시간이 길어지거나 구조가 복잡**해진다.

그래서 **야간 별도 스캔**으로 분리했다:

```yaml
trivy-nightly:
  if: github.event_name == 'schedule'    # (1) schedule 트리거일 때만 실행
  runs-on: self-hosted
  steps:
    - name: Scan latest image with Trivy
      id: trivy                           # (2) Step ID → 결과 참조용
      uses: aquasecurity/trivy-action@0.28.0
      with:
        image-ref: ${{ env.IMAGE_NAME }}:latest  # (3) 운영 중인 latest 이미지
        format: 'table'
        exit-code: '0'
        severity: 'CRITICAL,HIGH'
        timeout: '10m'                    # (4) 야간이라 넉넉하게
      continue-on-error: true             # (5) 스캔 실패해도 알림은 보냄
```

각 필드의 설계 의도:

#### (1) `if: github.event_name == 'schedule'`

같은 워크플로우 파일에 Push Job들과 Trivy Job이 함께 있다. `if` 조건으로 분기한다:
- Push 이벤트 → secrets-scan, test, build-push, deploy 실행
- Schedule 이벤트 → trivy-nightly만 실행

#### (2) `id: trivy` — Step ID 부여

다음 Step(Discord 알림)에서 이 Step의 결과를 참조하기 위해 ID를 부여한다.

```yaml
# 다음 Step에서 이렇게 참조:
${{ steps.trivy.outcome }}
# → 'success' 또는 'failure'
```

#### (3) `image-ref: latest` — 운영 이미지 스캔

Push 시 스캔은 "방금 빌드한 이미지"를 본다. 야간 스캔은 **현재 운영 중인 이미지**를 본다. 이 차이가 중요하다:

```
1월 15일: 빌드 + Push 스캔 → 취약점 0건 → 배포
1월 20일: NVD에 새 CVE 등록 (이 이미지의 패키지에 해당)
1월 21일: 야간 스캔 → "어제 등록된 CVE 발견!" → Discord 알림

야간 스캔이 없다면?
  → 다음 빌드 때까지 모름
  → 취약한 이미지가 계속 운영됨
```

**CVE는 매일 새로 발표된다.** 빌드 시점에 안전해도 다음날 취약해질 수 있다.

#### (4) `timeout: '10m'`

WEB은 5분, WAS Nightly는 10분이다. 야간은 시간 여유가 있고, 전체 CVE DB를 최신으로 다운로드하므로 넉넉하게 잡았다.

#### (5) `continue-on-error: true`

```yaml
continue-on-error: true
# → Trivy 스캔 자체가 실패해도 (네트워크 장애, CVE DB 다운로드 실패 등)
#    다음 Step(Discord 알림)은 실행됨

# 이것이 없으면:
# → 스캔 실패 시 Job 전체 중단 → Discord 알림도 안 보내짐
# → "스캔이 실패했다"는 사실조차 모름
```

### Discord 알림 — 결과에 따라 다른 메시지

```yaml
- name: Send Trivy Scan Result to Discord
  if: always()                # 스캔 성공이든 실패든 항상 실행
  uses: sarisia/actions-status-discord@v1
  with:
    webhook: ${{ secrets.DISCORD_WEBHOOK_URL }}
    status: ${{ steps.trivy.outcome == 'success' && 'success' || 'failure' }}
    title: "🔒 야간 보안 스캔 완료 (WAS)"
    description: |
      **스캔 대상**: ${{ env.IMAGE_NAME }}:latest
      **스캔 범위**: CRITICAL, HIGH CVE (OS 패키지 + Java 라이브러리)
      **결과**: ${{ steps.trivy.outcome == 'success' && '✅ 취약점 없음' || '⚠️ 취약점 발견 - 확인 필요' }}
    color: ${{ steps.trivy.outcome == 'success' && 0x00FF00 || 0xFF6600 }}
    username: Trivy Scanner
```

`steps.trivy.outcome`으로 **이전 Step의 성공/실패**를 읽어서 메시지를 분기한다:
- 취약점 없음 → 초록색 (`0x00FF00`), "✅ 취약점 없음"
- 취약점 발견 → 주황색 (`0xFF6600`), "⚠️ 취약점 발견"

주황색인 이유: `exit-code: '0'`이므로 빌드를 막지 않는 **경고** 수준이다. 빨강은 빌드 실패에 쓴다.

---

## 핵심 설계 결정 — 왜 이렇게 했는가

### 결정 1: `exit-code: '0'` — 취약점 발견해도 배포를 막지 않는다

```yaml
exit-code: '0'   # 현재: 경고만
exit-code: '1'   # 대안: 배포 차단
```

| 상황 | exit-code: '1' (차단) | exit-code: '0' (경고) |
|------|----------------------|----------------------|
| Alpine libssl CVE 발견 | 배포 차단 — 직접 패치 불가 | 알림 → Base Image 업데이트 검토 |
| Log4j급 치명적 CVE | 즉시 차단 — 안전 | 알림만 — 놓칠 위험 |
| 1인 운영, 새벽 | 배포 막혀도 확인할 사람 없음 | Discord 알림 → 다음날 확인 |

현재 환경 판단: **1인 운영 + 블로그 서비스** → Base Image CVE로 배포가 막히면 대응이 안 된다. 팀 환경에서는 `exit-code: '1'` + `.trivyignore`로 전환해야 한다.

### 결정 2: `severity: 'CRITICAL,HIGH'` — LOW/MEDIUM 무시

LOW/MEDIUM까지 스캔하면 수십~수백 건이 나온다. 대부분 "이론적으로 가능하지만 실제 악용 어려움" 수준이다. 모두 알리면 **알람 피로(Alert Fatigue)**로 정작 중요한 것도 무시하게 된다.

Wazuh에서도 알람 Level 10 이상만 Discord로 보내는 것과 같은 원칙이다.

### 결정 3: WEB은 Inline, WAS는 Nightly — 파이프라인 구조에 따라 다르게

```
WEB: 단일 Job → Trivy를 Step으로 추가해도 구조 변경 없음
WAS: 3개 병렬 Job → Trivy 추가 시 빌드 시간 증가 또는 구조 복잡화
```

같은 도구라도 **파이프라인 구조에 맞게** 통합 방식을 다르게 했다.

---

## 다른 환경에서는 어떻게 적용하는가

### 팀 환경 (운영 서비스)

```yaml
# 변경 1: 취약점 발견 시 배포 차단
exit-code: '1'

# 변경 2: 예외 관리
# .trivyignore 파일로 "패치 불가능한 CVE"는 명시적 허용
# 허용 사유를 주석으로 기록
```

```
# .trivyignore
# Alpine libssl - 다음 Base Image 릴리스에서 패치 예정 (ETA: 2026-03)
CVE-2024-12345
```

### Kubernetes Admission Controller 연동

현재는 CI/CD에서만 스캔한다. 누군가 파이프라인을 우회해서 `kubectl`로 직접 배포하면 스캔되지 않는다.

```
현재 방어선:
  Git Push → GitLeaks → Trivy → ArgoCD 배포
  ✅ CI를 통과한 이미지만 스캔됨

빈 구간:
  kubectl set image deployment/was was=악성이미지:latest
  ❌ 스캔 없이 직접 배포
```

이것을 막으려면 **OPA Gatekeeper** 같은 Admission Controller에서 "스캔 결과가 없는 이미지는 배포 거부"하는 정책을 설정한다. 이 부분은 다음 글에서 다룬다.

### 이미지 레지스트리 스캔

현재: GitHub Actions에서 빌드 직후 스캔
대안: Harbor, AWS ECR 같은 레지스트리 자체에서 Push 시 자동 스캔

| 방식 | 장점 | 단점 |
|------|------|------|
| CI 스캔 (현재) | 배포 전 즉시 확인 | CI를 우회하면 스캔 안 됨 |
| 레지스트리 스캔 | 모든 이미지 자동 스캔 | 배포 차단은 별도 구현 필요 |
| 양쪽 다 | 이중 방어 | 스캔 중복, 비용 |

---

## 성과

| 항목 | Before | After |
|------|--------|-------|
| Secret 탐지 범위 | 없음 (코드에 비밀 포함 가능) | Git 전체 히스토리 스캔 (push마다) |
| 이미지 CVE 스캔 | 없음 (취약한 이미지 배포 가능) | Push 시 + 매일 야간 스캔 |
| 새 CVE 탐지 시간 | 다음 빌드까지 모름 (며칠~몇 주) | 최대 24시간 이내 (야간 스캔) |
| 보안 게이트 | 0단계 | 3단계 (GitLeaks → Trivy → deploy 게이트) |
| Push 빌드 시간 영향 | - | WEB: +30초 / WAS: 영향 없음 (야간 분리) |

---

## 다음 단계

- **OPA Gatekeeper**: CI를 우회한 배포까지 차단하는 Admission Controller 정책
- **kube-bench**: CIS Kubernetes Benchmark로 클러스터 보안 점검
- **exit-code 전환 검토**: 서비스 성숙 시 `exit-code: '1'` + `.trivyignore` 관리
