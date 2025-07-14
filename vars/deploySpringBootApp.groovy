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
                when { expression { !useExistingTag && !rollback } }
                steps {
                    echo "📦 Checking out code"
                    deleteDir()
                    checkout scm
                }
            }

            stage('Build JAR') {
                when { expression { !useExistingTag && !rollback } }
                steps {
                    echo "🔧 Building JAR..."
                    sh "mvn clean package -DskipTests"
                    stash name: 'built-jar', includes: "target/${jar}"
                }
            }

            stage('Build Docker Image') {
                when { expression { !useExistingTag && !rollback } }
                agent { label 'docker-builder' }
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

            stage('Deploy on App VM') {
                agent { label 'spring-deploy-agent' }
                steps {
                    echo "🚀 Deploying to App VM..."
                    script {
                        def imageTag

                        if (rollback) {
                            echo "🔁 Rollback enabled. Reading previous deployed image from ${remoteHistoryFile}"
                            imageTag = sh(script: "cat ${remoteHistoryFile}", returnStdout: true).trim()
                        } else {
                            imageTag = useExistingTag
                                ? "${imageName}:${params.EXISTING_IMAGE_TAG}"
                                : env.DOCKER_IMAGE_TAG
                        }

                        withCredentials([usernamePassword(credentialsId: 'docker-hub-token', usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                            sh """
                                echo \$DOCKER_PASS | docker login -u \$DOCKER_USER --password-stdin
                                docker pull ${imageTag}
                                docker rm -f ${serviceName} || true
                                docker run -d --name ${serviceName} -p ${port}:${port} ${imageTag}
                            """

                            // Store current deployed image tag if it's not a rollback
                            if (!rollback) {
                                sh """
                                    mkdir -p /opt/deployment-history
                                    echo '${imageTag}' > ${remoteHistoryFile}
                                """
                            }
                        }
                    }
                }
            }

            stage('Health Check') {
                steps {
                    echo "🔍 Checking health endpoint..."
                    sh """
                        for i in {1..20}; do
                            echo "Attempt \$i: http://localhost:${port}${healthEndpoint}"
                            status_code=\$(curl -o /dev/null -s -w "%{http_code}" http://localhost:${port}${healthEndpoint})
                            echo "Status: \$status_code"
                            if [ "\$status_code" = "200" ] || [ "\$status_code" = "403" ] || [ "\$status_code" = "401" ]; then
                                break
                            fi
                            sleep 10
                        done
                    """
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
