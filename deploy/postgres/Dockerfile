# Postgres 16 + pg_partman (monthly room_inventory partitions, docs/database-nuzul.md §2.1).
# Debian base: Alpine has no PGDG pg_partman package.
FROM postgres:16.16-bookworm

# Pin to the 5.5 line. PGDG prunes old builds; if a cold build fails here, bump to the current 5.x.
# gosu: swap the upstream static binary (built with Go 1.24.6, CVE-2025-68121 CRITICAL in Trivy) for
# Debian's package. The entrypoint only uses it once, to drop root -> postgres.
RUN apt-get update \
 && apt-get install -y --no-install-recommends 'postgresql-16-partman=5.5.*' gosu \
 && rm -f /usr/local/bin/gosu \
 && gosu nobody true \
 && rm -rf /var/lib/apt/lists/*

# initdb copies this sample into postgresql.conf, so every consumer (compose, Testcontainers) gets the
# pg_partman_bgw worker without repeating -c flags. dbname/role must match POSTGRES_DB/POSTGRES_USER.
RUN printf "%s\n" \
      "shared_preload_libraries = 'pg_partman_bgw'" \
      "pg_partman_bgw.interval = 3600" \
      "pg_partman_bgw.dbname = 'nuzul'" \
      "pg_partman_bgw.role = 'nuzul'" \
    >> /usr/share/postgresql/postgresql.conf.sample
