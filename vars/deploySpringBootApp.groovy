def call(Map config = [:]) {
    pipeline {
        agent any

        stages {
            stage('Checkout') {
                steps {
                    checkout scm
                }
            }

            stage('Build') {
                steps {
                    sh 'mvn clean package'
                }
            }

            stage('Run Spring Boot App') {
                steps {
                    sh "java -jar target/${config.jarName ?: 'app.jar'}"
                }
            }
        }
    }
}
