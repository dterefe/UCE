FROM eclipse-temurin:21-jdk

EXPOSE 4567

COPY ./uce.portal/uce.web/target/classes /app/uce.portal/uce.web/target/classes
COPY ./uce.portal/uce.common/target/classes /app/uce.portal/uce.common/target/classes
COPY ./uce.portal/uce.corpus-importer/target/classes /app/uce.portal/uce.corpus-importer/target/classes
COPY ./uce.portal/uce.search/target/classes /app/uce.portal/uce.search/target/classes
COPY ./uce.portal/uce.analysis/target/classes /app/uce.portal/uce.analysis/target/classes
COPY ./uce.portal/uce.web/target/dependency /app/uce.portal/uce.web/target/dependency
RUN find /app/uce.portal/uce.web/target/dependency \
    \( -name 'jetty-*-9.4*.jar' -o -name 'websocket-*-9.4*.jar' \) \
    -delete
COPY ./uce.portal/resources /app/uce.portal/resources
COPY ./uce.portal/uce.web/src/main/resources/public /app/uce.portal/uce.web/src/main/resources/public
COPY ./uce.portal/uce.web/target/webportal-jar-with-dependencies.jar /app/uce.portal/uce.web/target/webportal-jar-with-dependencies.jar

WORKDIR /app/uce.portal/uce.web/
