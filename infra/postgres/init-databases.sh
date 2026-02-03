#!/bin/bash
# Script tạo multiple databases trong cùng 1 PostgreSQL instance.
# WHY? Mỗi microservice cần database riêng (database-per-service pattern)
# nhưng local dev chỉ cần 1 PostgreSQL container để tiết kiệm resource.

set -e
set -u

function create_user_and_database() {
    local database=$1
    echo "  Đang tạo database '$database'..."
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
        CREATE DATABASE $database;
        GRANT ALL PRIVILEGES ON DATABASE $database TO $POSTGRES_USER;
EOSQL
}

if [ -n "$POSTGRES_MULTIPLE_DATABASES" ]; then
    echo "Tạo multiple databases: $POSTGRES_MULTIPLE_DATABASES"
    for db in $(echo $POSTGRES_MULTIPLE_DATABASES | tr ',' ' '); do
        create_user_and_database $db
    done
    echo "Multiple databases đã tạo xong."
fi
