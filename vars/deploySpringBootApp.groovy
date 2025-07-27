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
            stage('Update Git Repository with New Image Tag') {
                steps {
                    script {
                        def imageTag = env.DOCKER_IMAGE_TAG
                        echo "Updating Git repository with new image tag: ${imageTag}"
                        
                        
                        // Update the k8s YAML with the new image tag
                        sh """
                            sed -i 's|sinra12/springboot-myfirstdocker:latest|${imageTag}|g' k8s/k8s-deployment.yaml
                            cat k8s/k8s-deployment.yaml 
                        """
                        
                        // Commit and push the changes to the Git repository using SSH
                         // Commit and push the changes to the Git repository
                         withCredentials([sshUserPrivateKey(credentialsId: 'github-ssh-key', keyFileVariable: 'SSH_KEY')]) {
                        sh """
                          git config --global user.name "jenkins"
                          git config --global user.email "jenkins@example.com"
                          git status
                          git add k8s/k8s-deployment.yaml
                          git commit -m "Updated Docker image tag to ${imageTag}"
                           GIT_SSH_COMMAND="ssh -i ${SSH_KEY} -o StrictHostKeyChecking=no" git push git@github.com:SINRA12/testJenkinDeplymentK8.git HEAD:main
                        """
                       }
                    }
                }
            }
        }
        post {
            failure {
                echo "❌ Deployment failed. Rolling back to previous version..."
            }
            success {
                echo "✅ Application deployed successfully!"
            }
        }
    }
}
