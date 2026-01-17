// ==============================================================================
// Hugo Blog CI/CD Pipeline
// ==============================================================================
// 목적: Git Push → Hugo Build → Docker → GHCR → K8s Deploy
//
// 트리거: GitHub Webhook 또는 수동 실행
// 예상 시간: 2-3분
// ==============================================================================

pipeline {
    agent any

    environment {
        // 이미지 설정
        IMAGE_NAME = 'ghcr.io/wlals2/blog-web'
        IMAGE_TAG = "v${BUILD_NUMBER}"

        // Kubernetes 설정
        NAMESPACE = 'blog-system'
        DEPLOYMENT_NAME = 'web'

        // GitHub 설정
        GIT_REPO = 'https://github.com/wlals2/blogsite.git'
        GIT_BRANCH = 'main'

        // GHCR 인증 (Jenkins Credentials에 저장)
        GHCR_CREDENTIALS = 'ghcr-credentials'
    }

    stages {
        // ======================================================================
        // Stage 1: Git Checkout
        // ======================================================================
        stage('Checkout') {
            steps {
                echo "=== Stage 1: Git Checkout ==="
                git url: "${GIT_REPO}", branch: "${GIT_BRANCH}"
            }
        }

        // ======================================================================
        // Stage 2: Docker Build
        // ======================================================================
        stage('Build Docker Image') {
            steps {
                echo "=== Stage 2: Docker Build ==="
                sh """
                    docker build -t ${IMAGE_NAME}:${IMAGE_TAG} .
                    docker tag ${IMAGE_NAME}:${IMAGE_TAG} ${IMAGE_NAME}:latest
                """
            }
        }

        // ======================================================================
        // Stage 3: Push to GHCR
        // ======================================================================
        stage('Push to GHCR') {
            steps {
                echo "=== Stage 3: Push to GHCR ==="
                withCredentials([usernamePassword(
                    credentialsId: "${GHCR_CREDENTIALS}",
                    usernameVariable: 'GHCR_USER',
                    passwordVariable: 'GHCR_TOKEN'
                )]) {
                    sh """
                        echo \$GHCR_TOKEN | docker login ghcr.io -u \$GHCR_USER --password-stdin
                        docker push ${IMAGE_NAME}:${IMAGE_TAG}
                        docker push ${IMAGE_NAME}:latest
                    """
                }
            }
        }

        // ======================================================================
        // Stage 4: Deploy to Kubernetes
        // ======================================================================
        stage('Deploy to K8s') {
            steps {
                echo "=== Stage 4: Deploy to Kubernetes ==="
                sh """
                    kubectl set image deployment/${DEPLOYMENT_NAME} \
                        ${DEPLOYMENT_NAME}=${IMAGE_NAME}:${IMAGE_TAG} \
                        -n ${NAMESPACE}
                    kubectl rollout status deployment/${DEPLOYMENT_NAME} -n ${NAMESPACE} --timeout=120s
                """
            }
        }

        // ======================================================================
        // Stage 5: Health Check
        // ======================================================================
        stage('Health Check') {
            steps {
                echo "=== Stage 5: Health Check ==="
                sh """
                    sleep 10
                    curl -f http://192.168.1.187:31852/ || exit 1
                    echo "Health check passed!"
                """
            }
        }
    }

    // ==========================================================================
    // Post Actions
    // ==========================================================================
    post {
        success {
            echo """
            =============================================
            SUCCESS: Hugo Blog deployed successfully!
            Image: ${IMAGE_NAME}:${IMAGE_TAG}
            URL: http://192.168.1.187:31852/
            =============================================
            """
        }
        failure {
            echo """
            =============================================
            FAILED: Deployment failed!
            Check the logs for details.
            =============================================
            """
        }
        always {
            // 로컬 이미지 정리 (선택사항)
            sh "docker rmi ${IMAGE_NAME}:${IMAGE_TAG} || true"
        }
    }
}
