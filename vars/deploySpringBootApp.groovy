def call(Map config) {
    def jar = config.jarName
    def port = config.port ?: 8080
    def healthEndpoint = config.healthCheck ?: "/actuator/health"

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
                    sh 'mvn clean package -DskipTests'
                    stash name: 'built-jar', includes: "target/${jar}"
                }
            }

            stage('Deploy on Spring Deployer Agent') {
                agent { label 'spring-deployer' }

                steps {
                    echo "Unstashing and deploying JAR on spring-deployer agent"
                    unstash 'built-jar'

                    echo "Stopping any existing application..."
                    sh """
                        set +e
                        pkill -f ${jar}
                        set -e
                    """

                    echo "Starting new application in background..."
                    sh """
                        nohup java -jar target/${jar} --server.port=8081 --server.address=0.0.0.0 > app.log 2>&1 &
                        sleep 5
                    """

                    echo "Checking health endpoint..."
                    sh """
                        for i in {1..10}; do
                          echo "Attempt \$i: http://localhost:${port}${healthEndpoint}"
                          curl -sSf http://localhost:${port}${healthEndpoint} && break || sleep 3
                        done

                        # Final check — fail pipeline if health check fails
                        curl -sSf http://localhost:${port}${healthEndpoint}
                    """
                }
            }
        }

        post {
            failure {
                echo "❌ Deployment failed. Last 20 lines of log:"
                sh 'tail -n 20 app.log || true'
            }
            success {
                echo "✅ Spring Boot application deployed successfully!"
            }
        }
    }
}
