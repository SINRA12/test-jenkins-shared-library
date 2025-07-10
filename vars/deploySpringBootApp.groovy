def call(Map config) {
    def jar = config.jarName
    def port = config.port ?: 8080
    def healthEndpoint = config.healthCheck ?: "/actuator/health"
    def serviceName = config.serviceName
    def imageName = config.imageName   // e.g., myrepo/myapp

    pipeline {
        agent any  // Run the initial stages on controller or default agent

        stages {
            stage('Checkout') {
                steps {
                    echo "Checking out the code from Git"
                    deleteDir()
                    checkout scm
                }
            }

            stage('Build JAR') {
                steps {
                    echo "Building the Spring Boot application"
                    sh "mvn clean package -DskipTests"
                    // Stash the JAR for later use
                    stash name: 'built-jar', includes: "target/${jar}"
                }
            }

            stage('Build Docker Image') {
                agent { label 'docker-builder' }  // Use the Docker VM for building
                steps {
                    echo "Building the Docker image using the existing Dockerfile"

                    script {
                        def tag = "${imageName}:${env.BUILD_NUMBER}"
                         // Unstash the JAR file to ensure it's available for the Docker build
                        unstash 'built-jar'
                        // Use the Jenkins credentials for Docker Hub login (Access Token)
                        withCredentials([usernamePassword(credentialsId: 'docker-hub-token', usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                            sh """
                                echo $DOCKER_PASS | docker login -u $DOCKER_USER --password-stdin
                                docker build --build-arg jar=${jar} --build-arg port=${port} -t ${tag} .
                                docker push ${tag}
                            """
                        }
                        env.DOCKER_IMAGE_TAG = tag  // Store the image tag for deployment
                    }
                }
            }

            stage('Deploy on App VM') {
                agent { label 'spring-deploy-agent' }  // Use the app-deployer agent (which already has SSH access)
                steps {
                    echo "Deploying Docker container on App server"

                    // No need for sshagent, since the agent already has SSH access
                    withCredentials([usernamePassword(credentialsId: 'docker-hub-token', usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                        sh """
                            # Login to Docker Hub
                            echo $DOCKER_PASS | docker login -u $DOCKER_USER --password-stdin
                        
                            whoami
                        
                            # Pull the Docker image from Docker Hub
                            docker pull ${env.DOCKER_IMAGE_TAG}

                            # Remove the existing container if it exists
                            docker rm -f ${serviceName} || true

                            # Run the new Docker container
                            docker run -d --name ${serviceName} -p ${port}:${port} \\
                                ${env.DOCKER_IMAGE_TAG}
                        """
                    }
                }
            }

           stage('Health Check') {
             steps {
                  echo "Checking Docker container health on app host..."
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
                echo "❌ Deployment failed. Checking Docker logs..."
                sh "docker logs ${serviceName} --tail 20 || true"
            }
            success {
                echo "✅ Docker-based Spring Boot application deployed successfully!"
            }
        }
    }
}
