FROM docker.io/jenkins/jenkins:2.529-slim-jdk21
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"

USER root

RUN apt-get update -yq && \
    apt-get install -yqq apt-transport-https ca-certificates gnupg curl && \
    curl "https://packages.cloud.google.com/apt/doc/apt-key.gpg" | gpg --dearmor -o /usr/share/keyrings/cloud.google.gpg && \
    echo "deb [signed-by=/usr/share/keyrings/cloud.google.gpg] https://packages.cloud.google.com/apt cloud-sdk main" | tee -a /etc/apt/sources.list.d/google-cloud-sdk.list && \
    apt-get update -yq && \
    apt-get install --no-install-recommends -qqy google-cloud-cli && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/* && \
    find /var/log -type f -delete && \
    gcloud --version

USER jenkins

COPY plugins.txt /usr/share/jenkins/ref/plugins.txt

RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

COPY jcasc/oidc-provider-init.groovy /usr/share/jenkins/ref/init.groovy.d/oidc-provider-init.groovy
