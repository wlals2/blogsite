---
title: "Apache httpd 프록시 설정 학습"
date: 2025-10-09
draft: false
tags: ["apache", "httpd", "proxy", "reverse-proxy", "study"]
categories: ["study", "Development"]
series: ["Apache 학습"]
---

## 학습 목표

Apache httpd의 프록시 기능을 이해하고 실제 설정할 수 있다.

---

## 1. 프록시(Proxy)란?

### 개념

프록시는 클라이언트와 서버 사이에서 중개 역할을 하는 서버입니다.

```

[클라이언트] ↔ [프록시 서버] ↔ [백엔드 서버]

```

### 프록시 종류

#### Forward Proxy (정방향 프록시)

```

[클라이언트] → [프록시] → [인터넷] → [목적지 서버]

```
- 클라이언트를 대신해서 요청
- 클라이언트가 프록시 존재를 인지
- 용도: 캐싱, 접근 제어, 익명화

#### Reverse Proxy (역방향 프록시)

```

[클라이언트] → [인터넷] → [리버스 프록시] → [백엔드 서버들]

```
- 서버를 대신해서 요청 받음
- 클라이언트는 프록시 존재를 모름
- 용도: 로드 밸런싱, SSL 종료, 캐싱, 보안

---

## 2. Apache httpd 프록시 모듈

### 필요한 모듈

```bash
# 모듈 확인
httpd -M | grep proxy

# 필요한 모듈들
# - mod_proxy          (기본 프록시)
# - mod_proxy_http     (HTTP/HTTPS 프록시)
# - mod_proxy_balancer (로드 밸런싱)
# - mod_proxy_wstunnel (WebSocket)

```

### 모듈 활성화

**CentOS/RHEL:**
```bash
# /etc/httpd/conf.modules.d/00-proxy.conf
LoadModule proxy_module modules/mod_proxy.so
LoadModule proxy_http_module modules/mod_proxy_http.so
LoadModule proxy_balancer_module modules/mod_proxy_balancer.so
LoadModule lbmethod_byrequests_module modules/mod_lbmethod_byrequests.so

```

**Ubuntu/Debian:**
```bash
sudo a2enmod proxy
sudo a2enmod proxy_http
sudo a2enmod proxy_balancer
sudo a2enmod lbmethod_byrequests
sudo systemctl restart apache2

```

---

## 3. 기본 리버스 프록시 설정

### 단순 프록시 (1:1)

```apache
<VirtualHost *:80>
    ServerName example.com

    # 프록시 활성화
    ProxyPreserveHost On

    # 모든 요청을 백엔드로 전달
    ProxyPass / http://localhost:8080/
    ProxyPassReverse / http://localhost:8080/
</VirtualHost>

```

### 설정 항목 설명

**ProxyPreserveHost On**
- 원본 Host 헤더를 백엔드로 전달
- Off: 백엔드 서버의 주소가 Host 헤더로 전달됨

**ProxyPass**
- 요청을 백엔드로 전달
- 형식: `ProxyPass [경로] [백엔드URL]`

**ProxyPassReverse**
- 백엔드 응답의 Location/Content-Location 헤더를 재작성
- 리다이렉트가 올바르게 동작하도록 함

---

## 4. 경로 기반 프록시

### 여러 백엔드 서버로 분기

```apache
<VirtualHost *:80>
    ServerName example.com

    ProxyPreserveHost On

    # /api 요청은 API 서버로
    ProxyPass /api http://localhost:3000/api
    ProxyPassReverse /api http://localhost:3000/api

    # /admin 요청은 관리 서버로
    ProxyPass /admin http://localhost:4000/admin
    ProxyPassReverse /admin http://localhost:4000/admin

    # 나머지는 웹 서버로
    ProxyPass / http://localhost:8080/
    ProxyPassReverse / http://localhost:8080/
</VirtualHost>

```

### 특정 경로 제외

```apache
<VirtualHost *:80>
    ServerName example.com

    # /static은 프록시 하지 않음 (로컬에서 직접 서빙)
    ProxyPass /static !

    # 나머지는 백엔드로
    ProxyPass / http://localhost:8080/
    ProxyPassReverse / http://localhost:8080/

    # /static은 로컬 디렉토리에서 제공
    Alias /static /var/www/static
    <Directory /var/www/static>
        Require all granted
    </Directory>
</VirtualHost>

```

---

## 5. 로드 밸런싱

### 기본 로드 밸런서 설정

```apache
<VirtualHost *:80>
    ServerName example.com

    # 로드 밸런서 그룹 정의
    <Proxy balancer://mycluster>
        BalancerMember http://backend1.example.com:8080
        BalancerMember http://backend2.example.com:8080
        BalancerMember http://backend3.example.com:8080

        # 로드 밸런싱 방식: 요청 수 기반
        ProxySet lbmethod=byrequests
    </Proxy>

    ProxyPreserveHost On
    ProxyPass / balancer://mycluster/
    ProxyPassReverse / balancer://mycluster/
</VirtualHost>

```

### 로드 밸런싱 알고리즘

```apache
<Proxy balancer://mycluster>
    BalancerMember http://backend1:8080
    BalancerMember http://backend2:8080

    # 방식 선택
    # byrequests  - 요청 수 기반 (라운드 로빈)
    # bytraffic   - 전송 바이트 기반
    # bybusyness  - 처리 중인 요청 수 기반
    # heartbeat   - 헬스체크 기반

    ProxySet lbmethod=byrequests
</Proxy>

```

### 가중치 설정

```apache
<Proxy balancer://mycluster>
    # loadfactor로 가중치 설정 (기본값 1)
    BalancerMember http://powerful-server:8080 loadfactor=3
    BalancerMember http://normal-server:8080 loadfactor=1

    ProxySet lbmethod=byrequests
</Proxy>

```

---

## 6. 헬스 체크와 장애 처리

### Sticky Session (세션 고정)

```apache
<Proxy balancer://mycluster>
    BalancerMember http://backend1:8080 route=node1
    BalancerMember http://backend2:8080 route=node2

    # Sticky Session 활성화
    ProxySet stickysession=JSESSIONID
</Proxy>

```

### 자동 복구 설정

```apache
<Proxy balancer://mycluster>
    BalancerMember http://backend1:8080 retry=60
    BalancerMember http://backend2:8080 retry=60

    # retry=60 : 60초 후 다시 시도
</Proxy>

```

### 헬스 체크

```apache
<Proxy balancer://mycluster>
    BalancerMember http://backend1:8080 retry=5
    BalancerMember http://backend2:8080 retry=5

    # 타임아웃 설정
    ProxySet timeout=10
</Proxy>

```

---

## 7. 보안 설정

### 프록시 악용 방지

```apache
# 기본적으로 프록시 비활성화
ProxyRequests Off

# 특정 호스트만 허용
<Proxy *>
    Order deny,allow
    Deny from all
    Allow from localhost
</Proxy>

```

### 헤더 조작

```apache
<VirtualHost *:80>
    ServerName example.com

    ProxyPreserveHost On

    # X-Forwarded-* 헤더 추가
    RequestHeader set X-Forwarded-Proto "http"
    RequestHeader set X-Forwarded-Port "80"

    ProxyPass / http://localhost:8080/
    ProxyPassReverse / http://localhost:8080/
</VirtualHost>

```

---

## 8. WebSocket 프록시

### WebSocket 지원

```apache
# mod_proxy_wstunnel 필요
LoadModule proxy_wstunnel_module modules/mod_proxy_wstunnel.so

<VirtualHost *:80>
    ServerName example.com

    # WebSocket 프록시
    ProxyPass /ws ws://localhost:8080/ws
    ProxyPassReverse /ws ws://localhost:8080/ws

    # 일반 HTTP
    ProxyPass / http://localhost:8080/
    ProxyPassReverse / http://localhost:8080/
</VirtualHost>

```

---

## 9. SSL/TLS 종료 (HTTPS Offloading)

### HTTPS → HTTP 프록시

```apache
<VirtualHost *:443>
    ServerName example.com

    # SSL 설정
    SSLEngine on
    SSLCertificateFile /path/to/cert.pem
    SSLCertificateKeyFile /path/to/key.pem

    ProxyPreserveHost On

    # HTTPS를 받아서 HTTP로 백엔드 전달
    RequestHeader set X-Forwarded-Proto "https"
    RequestHeader set X-Forwarded-Port "443"

    ProxyPass / http://localhost:8080/
    ProxyPassReverse / http://localhost:8080/
</VirtualHost>

```

---

## 10. 디버깅 및 모니터링

### 로그 설정

```apache
<VirtualHost *:80>
    ServerName example.com

    # 상세 로깅
    LogLevel warn proxy:trace2

    ErrorLog /var/log/httpd/proxy_error.log
    CustomLog /var/log/httpd/proxy_access.log combined

    ProxyPass / http://localhost:8080/
    ProxyPassReverse / http://localhost:8080/
</VirtualHost>

```

### 상태 페이지

```apache
<Location /balancer-manager>
    SetHandler balancer-manager

    # 접근 제한
    Require ip 127.0.0.1
</Location>

<Location /server-status>
    SetHandler server-status
    Require ip 127.0.0.1
</Location>

```

접속: `http://example.com/balancer-manager`

---

## 11. 실전 예제

### Node.js 앱 프록시

```apache
<VirtualHost *:80>
    ServerName myapp.example.com

    ProxyPreserveHost On

    # Node.js 앱
    ProxyPass / http://localhost:3000/
    ProxyPassReverse / http://localhost:3000/

    # 로그
    ErrorLog /var/log/httpd/myapp_error.log
    CustomLog /var/log/httpd/myapp_access.log combined
</VirtualHost>

```

### Django/Python 앱 (Gunicorn)

```apache
<VirtualHost *:80>
    ServerName django.example.com

    # 정적 파일은 Apache가 직접 서빙
    Alias /static /var/www/django/static
    <Directory /var/www/django/static>
        Require all granted
    </Directory>

    # 정적 파일 제외하고 프록시
    ProxyPass /static !
    ProxyPass / http://localhost:8000/
    ProxyPassReverse / http://localhost:8000/

    ProxyPreserveHost On
</VirtualHost>

```

### 다중 앱 로드 밸런싱

```apache
<VirtualHost *:80>
    ServerName prod.example.com

    # 백엔드 클러스터
    <Proxy balancer://backend>
        BalancerMember http://app1.internal:8080 route=app1 retry=5
        BalancerMember http://app2.internal:8080 route=app2 retry=5
        BalancerMember http://app3.internal:8080 route=app3 retry=5

        ProxySet lbmethod=byrequests
        ProxySet stickysession=SESSIONID
    </Proxy>

    ProxyPreserveHost On
    ProxyPass / balancer://backend/
    ProxyPassReverse / balancer://backend/

    # 관리 페이지
    <Location /balancer-manager>
        SetHandler balancer-manager
        Require ip 10.0.0.0/8
    </Location>
</VirtualHost>

```

---

## 12. 성능 최적화

### Keep-Alive 설정

```apache
<VirtualHost *:80>
    ServerName example.com

    # 백엔드 연결 재사용
    ProxyPass / http://localhost:8080/ keepalive=On
    ProxyPassReverse / http://localhost:8080/

    # 동시 연결 수 제한
    ProxyPass / http://localhost:8080/ max=20
</VirtualHost>

```

### 타임아웃 설정

```apache
<VirtualHost *:80>
    ServerName example.com

    # 타임아웃 (초 단위)
    ProxyTimeout 300

    ProxyPass / http://localhost:8080/ timeout=60
    ProxyPassReverse / http://localhost:8080/
</VirtualHost>

```

---

## 13. 트러블슈팅 체크리스트

### 프록시가 작동하지 않을 때

- [ ] 필요한 모듈이 로드되었는가?
  ```bash
  httpd -M | grep proxy
  ```

- [ ] SELinux가 차단하고 있는가? (CentOS/RHEL)
  ```bash
  # 확인
  getsebool httpd_can_network_connect

  # 허용
  sudo setsebool -P httpd_can_network_connect 1
  ```

- [ ] 방화벽이 차단하고 있는가?
  ```bash
  # 백엔드 포트 확인
  nc -zv localhost 8080
  ```

- [ ] 백엔드 서버가 실행 중인가?
  ```bash
  curl http://localhost:8080
  ```

- [ ] ProxyPass 순서가 올바른가?
  - 더 구체적인 경로가 위에 와야 함

- [ ] 설정 문법 오류는 없는가?
  ```bash
  httpd -t
  # 또는
  apachectl configtest
  ```

---

## 14. 학습 실습 계획

### 실습 1: 기본 리버스 프록시
- [ ] Apache 설치 및 모듈 활성화
- [ ] 간단한 백엔드 앱 실행 (Node.js/Python)
- [ ] ProxyPass 설정
- [ ] 동작 확인

### 실습 2: 로드 밸런싱
- [ ] 여러 백엔드 인스턴스 실행
- [ ] 로드 밸런서 설정
- [ ] 가중치 테스트
- [ ] Sticky Session 테스트

### 실습 3: SSL 종료
- [ ] Let's Encrypt 인증서 발급
- [ ] HTTPS 리버스 프록시 설정
- [ ] HTTP → HTTPS 리다이렉트

---

## 참고 자료

- [Apache mod_proxy 공식 문서](https://httpd.apache.org/docs/2.4/mod/mod_proxy.html)
- [Apache mod_proxy_balancer](https://httpd.apache.org/docs/2.4/mod/mod_proxy_balancer.html)
- [Apache Reverse Proxy Guide](https://httpd.apache.org/docs/2.4/howto/reverse_proxy.html)

---

## 다음 학습 주제

- Nginx 프록시와 비교
- HAProxy 학습
- 프록시 캐싱 설정
- 프록시 보안 강화
