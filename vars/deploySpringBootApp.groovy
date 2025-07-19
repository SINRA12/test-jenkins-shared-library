def call(Map config) {
    def jar = config.jarName
    def port = config.port ?: 8080
    def healthEndpoint = config.healthCheck ?: "/actuator/health"
    def serviceName = config.serviceName
    def imageName = config.imageName
    def useExistingTag = params.EXISTING_IMAGE_TAG != '-- Build from Source --'
    def rollback = params.ROLLBACK ?: false
    def remoteHistoryFile = "/opt/deployment-history/${serviceName}.tag"
    pipeline {
        agent any
        stages {
            stage('Checkout') {
                when {
                    expression {
                        !useExistingTag && !rollback
                    }
                }
                steps {
                    echo "📦 Checking out code"
                    deleteDir()
                    checkout scm
                }
            }
            stage('Build JAR') {
                when {
                    expression {
                        !useExistingTag && !rollback
                    }
                }
                steps {
                    echo "🔧 Building JAR..."
                    sh "mvn clean package -DskipTests"
                    stash name: 'built-jar', includes: "target/${jar}"
                }
            }
            stage('Build Docker Image') {
                when {
                    expression {
                        !useExistingTag && !rollback
                    }
                }
                agent {
                    label 'docker-builder'
                }
                steps {
                    echo "🐳 Building Docker image from source..."
                    script {
                        def tag = "${imageName}:${env.BUILD_NUMBER}"
                        unstash 'built-jar'
                        withCredentials([usernamePassword(credentialsId: 'docker-hub-token', usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                            sh """
                                echo \$DOCKER_PASS | docker login -u \$DOCKER_USER --password-stdin
                                docker build --build-arg jar=${jar} --build-arg port=${port} -t ${tag} .
                                docker push ${tag}
                            """
                        }
                        env.DOCKER_IMAGE_TAG = tag
                    }
                }
            }
            stage('Deploy to GKE') {
                steps {
                    script {
                        def imageTag = env.DOCKER_IMAGE_TAG
                        // Get cluster credentials
                        sh """
                gcloud container clusters get-credentials bisht-k8-cluster --region asia-south1 --project peerless-clock-464403-s3
            """
                        // Replace image in repo YAML
                        sh """
                sed 's|sinra12/springboot-myfirstdocker:latest|${imageTag}|g' k8s/k8s-deployment.yaml > k8s/k8s-deployment-updated.yaml
            """
                        // Apply updated manifest
                        sh """
                kubectl apply -f k8s/k8s-deployment-updated.yaml
            """
                        // Rollout check
                        sh "kubectl rollout status deployment/jenkin-app --timeout=120s"
                    }
                }
            }
            stage('Health Check') {
                steps {
                    script {
                        echo "🔍 Waiting for LoadBalancer external IP..."
                        def externalIP = ""
                        for (int i = 0; i < 20; i++) {
                            externalIP = sh(script: "kubectl get svc jenkin-app-service -o jsonpath='{.status.loadBalancer.ingress[0].ip}'", returnStdout: true).trim()
                            if (externalIP) {
                                break
                            }
                                            sleep 10
                        }
                        echo "✅ External IP: ${externalIP}"
                        sh """
                for i in {1..20}; do
                    echo "Attempt \$i: http://${externalIP}/api"
                    status_code=\$(curl -o /dev/null -s -w "%{http_code}" http://${externalIP}/api)
                    echo "Status: \$status_code"
                    if [ "\$status_code" = "200" ]; then
                        break
                    fi
                    sleep 10
                done
            """
                    }
                }
            }
        }
        post {
            failure {
                echo "❌ Deployment failed. Fetching container logs..."
                sh "docker logs ${serviceName} --tail 20 || true"
            }
            success {
                echo "✅ Application deployed successfully!"
            }
        }
    }
}
