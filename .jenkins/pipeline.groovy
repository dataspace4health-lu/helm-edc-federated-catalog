pipeline {
    agent any

    environment {
        HELM_NAME = "${env.JOB_NAME.tokenize('/').dropRight(1).takeRight(1).join('-').toLowerCase().replaceFirst(/^helm-/, '')}"
        KUBECONFIG = ".jenkins/kubeconfig"
        REGISTRY = "ds4h-registry:5432"
        K3D_CLUSTER_NAME = "${env.HELM_NAME}-${env.BUILD_ID}"
        // MICROSERVICE_ROOT_PATH = "DS4H/Microservices"
        IMAGES = ""
    }

    parameters {
        booleanParam(name: 'PUBLISH', defaultValue: false, description: 'Set to true to publish artifacts')
    }

    stages {
        stage('Package Helm Chart') {
            steps {
                echo "Packaging Helm chart..."
                sh "make clean && make build"
            }
        }

        stage('Lint') {
            steps {
                echo "Linting Helm chart"
                sh """
                    mkdir -p build/reports/lint
                    helm lint . | sed 's/^/<pre>/' | sed '\$a</pre>' > build/reports/lint/lint.html
                """
            }
            post {
                always {
                    archiveArtifacts artifacts: 'build/reports/lint/lint.html', allowEmptyArchive: true
                }
            }
        }

        // stage('Build Images') {
        //     steps {
        //         script {
        //             sh "helm dependency build"
        //             // Get helm images
        //             def imagesRaw = sh(
        //                 script: """
        //                     helm template . \
        //                     | grep -E 'image:\\s' \
        //                     | sed -E 's/.*\\/([^:]+:[^"]+).*/\\1/'
        //                 """,
        //                 returnStdout: true
        //             ).trim()

        //             // Split by line, remove duplicates, convert to a proper List/array
        //             def images = imagesRaw.tokenize('\n').unique()
        //             echo "Got helm images: ${images}"

        //             // Find all jobs with mame contained in the images
        //             def parallelSteps = [:]
        //             def parallelResults = [:]
        //             images.each { image ->
        //                 def jobName = "${params.MICROSERVICE_ROOT_PATH}/${image.split(":")[0]}"
        //                 def tag = image.split(":")[1] == "latest" ? "main" : "tags/v${tag}"
        //                 parallelSteps[image] = {
        //                     try {
        //                         echo "Triggering pipeline: ${jobName}"
        //                         parallelResults["${jobName}/${tag}"] = build(
        //                             job: "${jobName}/${tag}",
        //                             // parameters: [string(name: 'PUBLISH', value: "true")],
        //                             parameters: [choice(name: 'PUBLISH', value: "ds4h-registry:5432")],
        //                             wait: true,       // wait for completion
        //                             propagate: true   // fail this pipeline if triggered job fails
        //                         )
        //                     } catch (err) {
        //                         if (err != "No item named ${jobName}/${tag} found")
        //                         {
        //                             catchError(buildResult: 'SUCCESS', stageResult: 'ABORTED') {
        //                                 error("No pipeline was found with name ${jobName}/${tag}. Assuming this is an external dependency.")
        //                             }
        //                         }
        //                         else {
        //                             error("Error triggering job ${jobName}/${tag}: ${err}")
        //                         }
        //                     }
        //                 }
        //             }

        //             if (parallelSteps && !parallelSteps.isEmpty()) {
        //                 parallel parallelSteps

        //                 // Evaluate results
        //                 def finalStatus = 'SUCCESS'
        //                 parallelResults.each { name, res ->
        //                     echo "Pipeline ${name} result: ${res.result}"
        //                     if (res.result == 'FAILURE') {
        //                         finalStatus = 'FAILURE'
        //                     }
        //                     else {
        //                         if (env.IMAGES) {
        //                             env.IMAGES += ','
        //                         }
        //                         env.IMAGES += res.description.split('=')[1]
        //                     }
        //                 }

        //                 // If any FAILURE → fail main pipeline
        //                 if (finalStatus == 'FAILURE') {
        //                     error("Main pipeline failed because at least one child failed.")
        //                 } else {
        //                     echo "Main pipeline succeeded (aborted children ignored)."
        //                 }
        //             }
        //         }
        //     }
        // }

        stage('Deploy to Kubernetes') {
            steps {
                script {
                    // creates a k3d cluster
                    // returns the cluster object
                    // nodes: number of worker nodes
                    def createCluster = { nodes = 0 ->
                        def cluster = [:]

                        def registryIP = sh(script: "getent hosts ds4h-registry | awk '{print \$1}'", returnStdout: true).trim()
                        sh "k3d cluster create ${env.K3D_CLUSTER_NAME} --servers 1 --servers-memory 4G --agents ${nodes} --agents-memory 8G --no-lb --host-alias ${registryIP}:ds4h-registry --registry-config .jenkins/registry.yaml --kubeconfig-update-default=false --kubeconfig-switch-context=false"
                        sh "k3d kubeconfig get ${env.K3D_CLUSTER_NAME} > ${env.KUBECONFIG}"
                        sh "chmod 600 ${env.KUBECONFIG}"
                        cluster.net = sh(script: "docker inspect -f '{{range \$key, \$value := .NetworkSettings.Networks}}{{println \$key}}{{end}}' k3d-${env.K3D_CLUSTER_NAME}-server-0", returnStdout: true).trim()
                        cluster.server_ip = sh(script: "docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' k3d-${env.K3D_CLUSTER_NAME}-server-0", returnStdout: true).trim()
                        sh """
                        until kubectl -n kube-system get configmap coredns > /dev/null 2>&1; do
                            echo "Waiting for ConfigMap 'coredns' to be available..."
                            sleep 5
                        done
                        """
                        // def secrets = [
                        //     [path: 'secret/jenkins/ds4h/azure-registry-ro-sp', secretValues:
                        //         [
                        //             [envVar: 'APP_ID', vaultKey: 'app-id'],
                        //             [envVar: 'SP_PASSWORD', vaultKey: 'password']
                        //         ]
                        //     ]
                        // ]
                        // withVault([vaultSecrets: secrets]) {
                        //     sh("kubectl create secret docker-registry acr-creds --docker-server=${params.registry_domain} --docker-username=$APP_ID --docker-password=$SP_PASSWORD")
                        // }
                        sh "kubectl apply -f .jenkins/traefik-config.yaml"
                        sh "helm repo add jetstack https://charts.jetstack.io --force-update"
                        sh "helm repo update"
                        sh "helm install cert-manager jetstack/cert-manager --namespace cert-manager --create-namespace --version v1.14.4 --set installCRDs=true --timeout 10m"
                        sh "kubectl apply -f .jenkins/ca-issuer.yaml"
                        sh "kubectl apply -f .jenkins/ca-certificate.yaml"
                        sh "kubectl apply -f .jenkins/issuer.yaml"
                        sh "kubectl apply -f .jenkins/certificate.yaml"
                        sh "kubectl wait --for=condition=Ready certificate/dataspace4health.local --timeout=300s"
                        sh """
                        kubectl -n kube-system get cm coredns -o yaml | \
                        sed 's#NodeHosts: |#NodeHosts: |\\n    ${cluster.server_ip} dataspace4health.local#' | \
                        kubectl apply -f -
                        """
                        return cluster
                    }

                    def cluster = createCluster(2)
                    // echo "Deploying helm to Kubernetes ..."
                    sh "make install"
                }
            }
        }

        stage('Post-Deployment Verification') {
            steps {
                script {
                    echo "Checking rollout status..."
                    def status = sh(
                        script: """
                            for ns in \$(kubectl get ns --no-headers | awk '{print \$1}' | grep -vE '^(cert-manager|kube-system|kube-public|kube-node-lease)\$'); do
                                for deploy in \$(kubectl get deployments -n \$ns --no-headers | awk '{print \$1}'); do
                                    echo "Waiting for deployment \$deploy in namespace \$ns..."
                                    kubectl rollout status deployment/\$deploy -n \$ns --timeout=300s || {
                                        echo "Timeout waiting for \$deploy in \$ns"
                                        exit 1
                                    }
                                done
                            done
                        """,
                        returnStatus: true
                    )
                    
                    if (status != 0) {
                        echo "❌ Rollout failed. Fetching events and pod details..."
                        sh "kubectl get events -A --sort-by=.metadata.creationTimestamp | tail -n 20"
                        error("Deployment ${d} failed rollout verification.")
                    } else {
                        echo "✅ Rollout successful."
                    }
                }
            }
        }

        stage('Test') {
            steps {
                echo "Running Playwright end-to-end tests..."

                // dir('tests') { // assuming your Playwright tests are in ./tests
                //     sh """
                //         npm ci
                //         npx playwright install --with-deps
                //         npx playwright test --reporter=html
                //     """
                // }
            }
            post {
                always {
                    archiveArtifacts artifacts: 'build/reports/playwright/test-report.html', allowEmptyArchive: true
                }
            }
        }

        stage("Code Quality Check (SonarQube)") {
            steps {
                script {
                    withSonarQubeEnv('sonar') {
                        script {
                            def scannerHome = tool "sonar"
                            sh """
                                helm template . > ./helm/manifests.yaml
                                ${scannerHome}/bin/sonar-scanner -X \
                                    -Dsonar.projectKey=${HELM_NAME} \
                                    -Dsonar.sources=./helm \
                                    -Dsonar.inclusions=**/*.yaml,**/*.yml \
                                    -Dsonar.scm.exclusions.disabled=true \
                                    -Dsonar.language=yaml \
                                    -Dsonar.branch.name=${env.GIT_BRANCH.replaceFirst("^origin/", "")} \
                                    -Dsonar.qualitygate.wait=true
                            """
                        }
                    }

                    // Wait for the Quality Gate result
                    timeout(time: 10, unit: 'MINUTES') {
                        def qg = waitForQualityGate()
                        if (qg.status != 'OK') {
                            error "❌ Quality Gate failed: ${qg.status}"
                        } else {
                            echo "✅ Quality Gate passed: ${qg.status}"
                        }
                    }
                }
            }
        }

        stage("Security Scan (Trivy)") {
            steps {
                sh """
                    helm template . > ./helm/manifests.yaml
                    docker run --rm \
                        -v /var/run/docker.sock:/var/run/docker.sock \
                        -v \$PWD/built/reports/trivy:/output \
                        -v \$PWD/.jenkins/trivy-html.tpl:/template.tpl \
                        -v \$PWD/helm/manifests.yaml:/manifests.yaml \
                        aquasec/trivy:latest config /manifests.yaml \
                        --debug --exit-code 1 --timeout 15m --severity HIGH,CRITICAL \
                        --format template --template "@/template.tpl" --output /output/trivy.html
                """
            }
            post {
                always {
                    archiveArtifacts artifacts: 'built/reports/trivy/trivy.html', allowEmptyArchive: true
                }
            }
        }

        stage('Publish Helm') {
            when {
                expression { env.BRANCH_NAME.startsWith('refs/tags/') || params.PUBLISH }
            }
            steps {
                script {
                    echo "Pushing helm"
                    
                    // Get latest tag
                    def tag = sh(
                        script: "git describe --tags --abbrev=0 2>/dev/null || echo \"0.0.0\"", 
                        returnStdout: true
                    ).trim()
                    tag = tag.replaceFirst(/^v/, "") // Trim leading v
                    // Get version defined in the code
                    def version = sh(
                        script: "helm show chart helm/${env.HELM_NAME}-*.tgz | grep '^version:' | head -1 | sed -E 's/version:\\s?([0-9]+\\.[0-9]+\\.[0-9]+)/\\1/'",
                        returnStdout: true
                    ).trim()
                    version = version.replaceFirst(/^v/, "") // Trim leading v
                    version = version ?: "0.0.0" // default to 0.0.0
                    if (version != tag) {
                        error("❌ Version and Tag mismatch")
                    }
                    
                    // Set the Tag to: 
                    // 1. <version number> if there is a tag and the commit is the one of the tag
                    // 2. <version number>-<commit hash> if there is a tag but the commit is ahead
                    // 3. 0.0.0 if no tag exists
                    tag = sh(
                        script: "git describe --tags --exact-match 2>/dev/null || git describe --tags 2>/dev/null || echo 0.0.0", 
                        returnStdout: true
                    ).trim()
                    tag = tag.replaceFirst(/^v/, "") // Trim leading v
                    
                    sh """
                        echo "Logging into registry ${env.REGISTRY}"
                        helm registry login --insecure ${env.REGISTRY} --username unused --password unused
                        echo "saving helm ${env.REGISTRY}/helm/${env.HELM_NAME}:${tag}."
                        helm push helm/${env.HELM_NAME}-*.tgz oci://${env.REGISTRY}/helm --plain-http
                    """
                    // Save it in the build description
                    currentBuild.description = "BuiltHelm=${env.REGISTRY}/helm/${env.HELM_NAME}:${tag}"
                }
            }
        }
    }

    post {
        always {
            script {
                cleanWs()

                // Delete published images (if any)
                (env.IMAGES?.split(",") ?: []).each { image ->
                    // sh "docker push ${image}"
                }
                // Delete k3d cluster
                sh "k3d cluster delete ${env.K3D_CLUSTER_NAME} 2> /dev/null"

                // Detect if build was triggered manually or by SCM
                def causes = currentBuild.getBuildCauses()
                def isManual = causes.any { it.toString().contains('UserIdCause') }
                def isSCM = causes.any { it.toString().contains('SCMTrigger') || it.toString().contains('GitLabWebHookCause') }
                def isUpstream = causes.any { it.toString().contains('UpstreamCause') }

                if (isManual) {
                    echo "Build test triggered manually — sending email to Requester"
                    emailext(
                        recipientProviders: [[$class: 'RequesterRecipientProvider']],
                        subject: "Manual Build ${currentBuild.currentResult}: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                        body: "The manually triggered Jenkins build (${env.JOB_NAME} #${env.BUILD_NUMBER}) has completed with result: ${currentBuild.currentResult}.\n\nDetails: ${env.BUILD_URL}"
                    )
                } else if (isSCM) {
                    echo "Build triggered by SCM — sending email to Developers"
                    emailext(
                        recipientProviders: [[$class: 'DevelopersRecipientProvider']],
                        subject: "Committed Build ${currentBuild.currentResult}: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                        body: "The Jenkins build was triggered by a code commit and has completed with result: ${currentBuild.currentResult}.\n\nDetails: ${env.BUILD_URL}"
                    )
                } else {
                    echo "Build triggered by another cause — sending default notification"
                    emailext(
                        recipientProviders: [[$class: 'DevelopersRecipientProvider']],
                        subject: "Build ${currentBuild.currentResult}: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                        body: "This build was triggered automatically or by another cause with result: ${currentBuild.currentResult}..\n\nDetails: ${env.BUILD_URL}"
                    )
                }
            }
        }
    }
}
