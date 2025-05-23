ARG DOCKER_URL
ARG DOCKER_ORG
ARG ARTIFACT_ORG

FROM ${DOCKER_URL}/${DOCKER_ORG}/common-img:latest

# Custom build from here on
ENV PROJECT_NAME edd-core

ARG ARTIFACT_ORG
ENV ARTIFACT_ORG ${ARTIFACT_ORG}

COPY --chown=build:build resources resources
COPY --chown=build:build src src
COPY --chown=build:build features features
COPY --chown=build:build test test
COPY --chown=build:build modules modules
COPY --chown=build:build deps.edn deps.edn
COPY --chown=build:build tests.edn tests.edn
COPY --chown=build:build format.sh format.sh
COPY --chown=build:build dynamodb dynamodb
COPY --chown=build:build sql sql

RUN ./format.sh check

ARG BUILD_ID

COPY --chown=build:build ansible ansible

# We copy from Jenkinsfile repo here (cbd-jenkins-pipeline)
COPY --chown=build:build scripts/build.clj build.clj

RUN set -e &&\
    echo "Org: ${ARTIFACT_ORG}" &&\
    clojure -M:test:unit &&\
    SESSION_TOKEN=$(curl -s -X PUT "http://169.254.169.254/latest/api/token" -H "X-aws-ec2-metadata-token-ttl-seconds: 21600") &&\
    export AWS_DEFAULT_REGION=$(curl -s -H "X-aws-ec2-metadata-token: $SESSION_TOKEN" http://169.254.169.254/latest/dynamic/instance-identity/document | jq -r .region) &&\
    export AWS_REGION=$AWS_DEFAULT_REGION &&\
    TARGET_ACCOUNT_ID="$(aws sts get-caller-identity | jq -r '.Account')" &&\
    cred=$(aws sts assume-role \
                --role-arn arn:aws:iam::${TARGET_ACCOUNT_ID}:role/PipelineRole \
                --role-session-name "${PROJECT_NAME}-deployment-${RANDOM}" \
                --endpoint https://sts.${AWS_DEFAULT_REGION}.amazonaws.com \
                --region ${AWS_DEFAULT_REGION}) &&\
    export AWS_ACCESS_KEY_ID=$(echo $cred | jq -r '.Credentials.AccessKeyId') &&\
    export AWS_SECRET_ACCESS_KEY=$(echo $cred | jq -r '.Credentials.SecretAccessKey') &&\
    export AWS_SESSION_TOKEN=$(echo $cred | jq -r '.Credentials.SessionToken') &&\
    export ELASTIC_AUTH="Basic YWRtaW46YWRtaW4=" &&\
    export AccountId=$TARGET_ACCOUNT_ID &&\
    export Region=$AWS_DEFAULT_REGION &&\
    export EnvironmentNameLower=pipeline &&\
    export DatabasePassword="no-secret" &&\
    export EnvironmentNameUpper=PIPELINE &&\
    export HOST_IP="127.0.0.1" &&\
    export DatabaseEndpoint="$HOST_IP" &&\
    export IndexDomainScheme="http" &&\
    export IndexDomainEndpoint="$HOST_IP:9200" &&\
    flyway -password="${DatabasePassword}" \
               -schemas=glms,test,prod \
               -url=jdbc:postgresql://${DatabaseEndpoint}:5432/postgres?user=postgres \
               clean &&\
    flyway -password="${DatabasePassword}" \
           -schemas=test\
           -url=jdbc:postgresql://${DatabaseEndpoint}:5432/postgres?user=postgres \
           -locations="filesystem:${PWD}/sql/files/edd" migrate &&\
    flyway -password="${DatabasePassword}" \
            -schemas=prod \
            -url=jdbc:postgresql://${DatabaseEndpoint}:5432/postgres?user=postgres \
            -locations="filesystem:${PWD}/sql/files/edd" migrate &&\
    echo "Run ansible stuff" &&\
    ansible-playbook ansible/deploy/deploy.yml &&\
    echo "Building b${BUILD_ID}" &&\
    clojure -T:build jar   \
             :group-id "\"${ARTIFACT_ORG}\"" \
             :artifact-id "\"${PROJECT_NAME}\"" \
             :version "\"1.${BUILD_ID}\"" &&\
    cp target/classes/META-INF/maven/${ARTIFACT_ORG}/${PROJECT_NAME}/pom.xml \
             /dist/release-libs/${PROJECT_NAME}-1.${BUILD_ID}.jar.pom.xml &&\
    ls -la target &&\
    cp target/${PROJECT_NAME}-1.${BUILD_ID}.jar /dist/release-libs/${PROJECT_NAME}-1.${BUILD_ID}.jar &&\
    cat target/classes/META-INF/maven/${ARTIFACT_ORG}/${PROJECT_NAME}/pom.xml &&\
    mvn install:install-file \
         -Dfile=target/${PROJECT_NAME}-1.${BUILD_ID}.jar \
         -DgroupId=${ARTIFACT_ORG} \
         -DartifactId=${PROJECT_NAME} \
         -DpomFile=target/classes/META-INF/maven/${ARTIFACT_ORG}/${PROJECT_NAME}/pom.xml \
         -Dversion="1.${BUILD_ID}" \
         -Dpackaging=jar &&\
    echo "Building modules" &&\
    env &&\
    cd modules &&\
    for i in $(ls); do \
       cd $i &&\
       echo "Building module $i" &&\
       bb -i '(let [build-id "'${BUILD_ID}'" \
                    new-lib (symbol "'${ARTIFACT_ORG}/${PROJECT_NAME}'") \
                    lib (symbol "local/'${PROJECT_NAME}'") \
                    deps (read-string \
                          (slurp (io/file "deps.edn"))) \
                    deps (if (get-in deps [:deps lib]) \
                           (-> deps \
                               (update-in [:deps] (fn [%] (dissoc % lib))) \
                               (assoc-in [:deps new-lib] \
                                   {:mvn/version (str "1." build-id)})) \
                           deps) \
                    aliases [:test] \
                    deps (reduce \
                           (fn [p alias] \
                             (if (get-in p [:aliases alias :extra-deps lib]) \
                               (-> p \
                                   (update-in [:aliases alias :extra-deps] \
                                        (fn [%] (dissoc % lib))) \
                                   (assoc-in [:aliases alias :extra-deps new-lib] \
                                         {:mvn/version (str "1." build-id)})) \
			       p)) \
                           deps \
                           aliases)] \
                (spit "deps.edn" \
		      (with-out-str (clojure.pprint/pprint deps))))' &&\
       echo "Generated deps.edn" &&\
       cp ~/build.clj . &&\
       cat deps.edn &&\
       clojure -Stree &&\
       clojure -M:test:it &&\
       clojure -M:test:unit &&\
       clojure -T:build jar   \
             :group-id "\"${ARTIFACT_ORG}\"" \
             :artifact-id "\"${i}\"" \
             :version "\"1.${BUILD_ID}\"" &&\
       cp target/classes/META-INF/maven/${ARTIFACT_ORG}/${i}/pom.xml \
             /dist/release-libs/${i}-1.${BUILD_ID}.jar.pom.xml &&\
       cp target/${i}-1.${BUILD_ID}.jar /dist/release-libs/${i}-1.${BUILD_ID}.jar; \
       if [[ $? -gt 0 ]]; then exit 1; fi &&\
       cd ..; \
    done &&\
    cd .. &&\
    echo "Running integration tests: $(pwd)" &&\
    env &&\
    clojure -M:test:it &&\
    rm -rf /home/build/.m2/repository &&\
    rm -rf target &&\
    tree /dist

RUN ls -la

