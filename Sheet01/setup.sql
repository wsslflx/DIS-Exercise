-- Run this once as the postgres superuser: psql postgres -f setup.sql

CREATE USER dis_user WITH PASSWORD 'dis_password';
CREATE DATABASE dis_db OWNER dis_user;
GRANT ALL PRIVILEGES ON DATABASE dis_db TO dis_user;
