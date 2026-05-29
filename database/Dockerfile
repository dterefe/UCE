FROM postgres:16

RUN apt-get update && apt-get install -y --no-install-recommends \
    gnupg2 curl ca-certificates lsb-release \
    build-essential git flex bison postgresql-server-dev-16 \
 && curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc \
    | gpg --dearmor -o /usr/share/keyrings/postgresql.gpg \
 && echo "deb [signed-by=/usr/share/keyrings/postgresql.gpg] http://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" > /etc/apt/sources.list.d/pgdg.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends \
    postgresql-16-postgis-3 \
    postgresql-16-postgis-3-scripts \
    postgresql-16-pgvector \
 && git clone https://github.com/apache/age.git /tmp/age \
 && cd /tmp/age \
 && git checkout PG16 \
 && make install \
 && rm -rf /tmp/age /var/lib/apt/lists/*
