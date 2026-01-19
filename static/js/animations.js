// ========================================
// ✨ 메인 페이지 애니메이션 & 인터랙티브 효과
// ========================================

document.addEventListener('DOMContentLoaded', function() {

    // ========================================
    // 1. 스킬 바 애니메이션 (페이지 로드 시 자동 실행)
    // ========================================
    function animateSkillBars() {
        const skillBars = document.querySelectorAll('.skill-bar-fill');

        // Intersection Observer로 화면에 보일 때 애니메이션 시작
        const observer = new IntersectionObserver((entries) => {
            entries.forEach(entry => {
                if (entry.isIntersecting) {
                    const bar = entry.target;
                    const percentage = bar.getAttribute('data-percentage');

                    // 지연 후 애니메이션 시작
                    setTimeout(() => {
                        bar.style.width = percentage + '%';
                    }, 100);

                    observer.unobserve(bar);
                }
            });
        }, { threshold: 0.5 });

        skillBars.forEach(bar => observer.observe(bar));
    }

    // ========================================
    // 2. Typing 효과 (부제목)
    // ========================================
    function typingEffect(element, text, speed = 100) {
        if (!element) return;

        let index = 0;
        element.textContent = '';

        function type() {
            if (index < text.length) {
                element.textContent += text.charAt(index);
                index++;
                setTimeout(type, speed);
            }
        }

        type();
    }

    // 부제목에 typing 효과 적용 (선택 사항)
    const subtitle = document.querySelector('.profile-mode .subtitle');
    if (subtitle && subtitle.hasAttribute('data-typing')) {
        const originalText = subtitle.textContent;
        typingEffect(subtitle, originalText, 80);
    }

    // ========================================
    // 3. 숫자 카운트업 애니메이션 (메트릭 배지)
    // ========================================
    function animateCounter(element) {
        const target = element.getAttribute('data-count');
        const suffix = element.getAttribute('data-suffix') || '';
        const duration = 2000; // 2초
        const steps = 60;
        const increment = target / steps;
        let current = 0;

        const timer = setInterval(() => {
            current += increment;
            if (current >= target) {
                element.textContent = target + suffix;
                clearInterval(timer);
            } else {
                element.textContent = Math.floor(current) + suffix;
            }
        }, duration / steps);
    }

    // 메트릭 배지에 카운트업 적용
    const counters = document.querySelectorAll('.metric-badge[data-count]');
    if (counters.length > 0) {
        const counterObserver = new IntersectionObserver((entries) => {
            entries.forEach(entry => {
                if (entry.isIntersecting) {
                    animateCounter(entry.target);
                    counterObserver.unobserve(entry.target);
                }
            });
        }, { threshold: 0.5 });

        counters.forEach(counter => counterObserver.observe(counter));
    }

    // ========================================
    // 4. 스크롤 시 섹션 Fade-in (추가 애니메이션)
    // ========================================
    const sections = document.querySelectorAll('.profile-section');
    if (sections.length > 0) {
        const sectionObserver = new IntersectionObserver((entries) => {
            entries.forEach(entry => {
                if (entry.isIntersecting) {
                    entry.target.classList.add('visible');
                }
            });
        }, { threshold: 0.1 });

        sections.forEach(section => sectionObserver.observe(section));
    }

    // ========================================
    // 5. 다크모드 토글 버튼 (부드러운 전환)
    // ========================================
    function setupDarkModeToggle() {
        const toggle = document.getElementById('theme-toggle');
        if (!toggle) return;

        // 현재 테마 가져오기
        const currentTheme = localStorage.getItem('pref-theme') || 'auto';

        toggle.addEventListener('click', () => {
            const body = document.body;
            const currentTheme = body.classList.contains('dark') ? 'light' : 'dark';

            // 부드러운 전환 효과
            body.style.transition = 'background-color 0.3s ease, color 0.3s ease';

            // 테마 전환
            body.classList.toggle('dark');
            body.classList.toggle('light');

            // localStorage에 저장
            localStorage.setItem('pref-theme', currentTheme);

            // 아이콘 변경
            updateThemeIcon(currentTheme);
        });
    }

    function updateThemeIcon(theme) {
        const icon = document.querySelector('#theme-toggle svg');
        if (!icon) return;

        if (theme === 'dark') {
            // 달 아이콘 → 해 아이콘
            icon.innerHTML = '<path d="M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z"></path>';
        } else {
            // 해 아이콘 → 달 아이콘
            icon.innerHTML = '<path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path>';
        }
    }

    // ========================================
    // 6. 프로젝트 카드 클릭 이벤트
    // ========================================
    const projectCards = document.querySelectorAll('.project-card[data-link]');
    projectCards.forEach(card => {
        card.style.cursor = 'pointer';
        card.addEventListener('click', function() {
            const link = this.getAttribute('data-link');
            if (link) {
                window.location.href = link;
            }
        });
    });

    // ========================================
    // 7. 기술 스택 클릭 시 필터링 (선택 사항)
    // ========================================
    const techItems = document.querySelectorAll('.tech-grid > div');
    techItems.forEach(item => {
        item.addEventListener('click', function() {
            const tech = this.textContent.trim().split('\n')[0];
            console.log('Selected tech:', tech);
            // 여기에 필터링 로직 추가 가능
            // 예: 해당 기술을 사용한 프로젝트 하이라이트
        });
    });

    // ========================================
    // 초기화
    // ========================================
    animateSkillBars();
    setupDarkModeToggle();

    // 페이지 로드 완료 로그
    console.log('✨ Animations initialized');
});

// ========================================
// 스크롤 진행률 표시 (선택 사항)
// ========================================
function updateScrollProgress() {
    const scrollProgress = document.querySelector('.scroll-progress');
    if (!scrollProgress) return;

    const windowHeight = window.innerHeight;
    const documentHeight = document.documentElement.scrollHeight - windowHeight;
    const scrolled = window.scrollY;
    const progress = (scrolled / documentHeight) * 100;

    scrollProgress.style.width = progress + '%';
}

window.addEventListener('scroll', updateScrollProgress);

// ========================================
// 부드러운 스크롤 (앵커 링크)
// ========================================
document.querySelectorAll('a[href^="#"]').forEach(anchor => {
    anchor.addEventListener('click', function (e) {
        e.preventDefault();
        const target = document.querySelector(this.getAttribute('href'));
        if (target) {
            target.scrollIntoView({
                behavior: 'smooth',
                block: 'start'
            });
        }
    });
});
