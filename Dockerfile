FROM eclipse-temurin:8-jre

ARG SERVICE_NAME=SIMSvr
ARG MAIN_CLASS=SIMSvr
ARG REPO_URL

ENV DEPLOY_ROOT=/srv/dc \
    HOME=/srv/dc \
    ATS_ROOT=/srv/dc \
    BACKEND_ROOT=/srv/dc \
    SERVICE_NAME=${SERVICE_NAME} \
    MAIN_CLASS=${MAIN_CLASS} \
    TZ=Asia/Shanghai

LABEL org.opencontainers.image.source=$REPO_URL

WORKDIR /srv/dc/dc/${SERVICE_NAME}

COPY target/classes/ /srv/dc/dc/${SERVICE_NAME}/classes/
COPY target/dependency/ /srv/dc/dc/${SERVICE_NAME}/lib/
COPY salt-formula/SIMSvr/files/config/ /srv/dc/dc/${SERVICE_NAME}/config/
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh

RUN chmod +x /usr/local/bin/docker-entrypoint.sh \
    && mkdir -p /srv/dc/control /srv/dc/data /srv/dc/log /srv/dc/tpc/tpc /srv/dc/dc/${SERVICE_NAME} \
    && ln -s /srv/dc/data /srv/dc/dc/${SERVICE_NAME}/data \
    && ln -s /srv/dc/log /srv/dc/dc/${SERVICE_NAME}/log

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
