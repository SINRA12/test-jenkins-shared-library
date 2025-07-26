def call(Map config) {
    def jar = config.jarName
    def port = config.port ?: 8080
    def healthEndpoint = config.healthCheck ?: "/actuator/health"
    def serviceName = config.serviceName
    def imageName = config.imageName
    def useExistingTag = params.EXISTING_IMAGE_TAG != '-- Build from Source --'
    def remoteHistoryFile = "/opt/deployment-history/${serviceName}.tag"
    
    pipeline {
        agent any
        stages {
            stage('Checkout') {
                when {
                    expression {
                        !useExistingTag  // Only checkout code if building from source
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
                        !useExistingTag  // Only build the JAR if building from source
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
                        !useExistingTag  // Only build Docker image if building from source
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
            stage('Use Existing Image') {
                when {
                    expression {
                        useExistingTag  // If using an existing image, set the tag
                    }
                }
                steps {
                    echo "📦 Using existing image: ${imageName}:${params.EXISTING_IMAGE_TAG}"
                    script {
                         env.DOCKER_IMAGE_TAG = "${imageName}:${params.EXISTING_IMAGE_TAG}"  // Set the full image tag
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
                        // Replace image in repo YAML with the correct image tag (new or existing)
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
                                echo "Attempt \$i: http://${externalIP}/api1"
                                status_code=\$(curl -o /dev/null -s -w "%{http_code}" http://${externalIP}/api1)
                                echo "Status: \$status_code"
                                if [ "\$status_code" = "200" ]; then
                                    break
                                elif [ "\$status_code" = "404" ]; then
                                    echo "❌ Error: Status code 404 received, failing the build."
                                    error "Health check failed with status code 404."
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
              echo "❌ Deployment failed. Rolling back to previous version..."
        
              // Trigger the rollback
              sh "kubectl rollout undo deployment/jenkin-app"
        
             // Wait for the rollback to complete
             sh "kubectl rollout status deployment/jenkin-app --timeout=120s"
         }
         success {
             echo "✅ Application deployed successfully!"
         }
      }
    }
}
