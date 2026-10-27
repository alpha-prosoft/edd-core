ARG DOCKER_URL
ARG DOCKER_ORG

ARG RESOURCE_NAME=projectname
ARG MAIN_CLASS=projectname.main

FROM ${DOCKER_URL}/${DOCKER_ORG}/cljjava-ansible-img:b703

ENV RESOURCE_NAME=projectname

COPY . .
