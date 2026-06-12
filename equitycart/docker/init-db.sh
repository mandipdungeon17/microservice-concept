#!/bin/bash
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    CREATE DATABASE equitycart_ledger;
    CREATE DATABASE equitycart_notification;
    CREATE DATABASE equitycart_order;
    CREATE DATABASE equitycart_portfolio;
    CREATE DATABASE equitycart_product;
    CREATE DATABASE equitycart_user;
EOSQL