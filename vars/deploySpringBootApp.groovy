def call(Map config) {
    def jar = config.jarName
    def port = config.port ?: 8080
    def healthEndpoint = config.healthCheck ?: "/actuator/health"
    def serviceName = config.serviceName

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
                        sudo systemctl stop ${serviceName} || true
                    """

                    echo "Creating /opt/${serviceName} directory and copying jar"
                    sh """
                        sudo mkdir -p /opt/${serviceName}
                        sudo cp target/${jar} /opt/${serviceName}/
                    """

                    echo "Creating dynamic systemd service unit"
                    sh """
                        sudo bash -c 'cat > /etc/systemd/system/${serviceName}.service <<EOF
[Unit]
Description=Spring Boot Jenkins App
After=network.target

[Service]
User=jenkins
WorkingDirectory=/opt/${serviceName}
ExecStart=/usr/bin/java -jar ${jar} --server.port=${port} --server.address=0.0.0.0
SuccessExitStatus=143
Restart=on-failure
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF'
                    """

                    echo "Reloading systemd and starting service"
                    sh """
                        sudo systemctl daemon-reload
                        sudo systemctl start ${serviceName}
                        sudo systemctl enable ${serviceName}
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
                sh 'sudo journalctl -u ${serviceName} -n 20 || true'
            }
            success {
                echo "✅ Spring Boot application deployed successfully!"
            }
        }
    }
}
