-- SPDX-License-Identifier: Apache-2.0
-- Two-cell local setup: one PostgreSQL server holding FIVE separate databases. In production every cell has its own
-- database server (the blast radius); sharing one server here only saves laptop memory. Development passwords only.
-- sangam_rollup_ra: the optional state roll-up tier example (M-063, profile state-tier).
CREATE USER sangam_cell_a WITH PASSWORD 'cell-a-dev-only';
CREATE USER sangam_cell_b WITH PASSWORD 'cell-b-dev-only';
CREATE USER sangam_cp WITH PASSWORD 'cp-dev-only';
CREATE USER sangam_rollup WITH PASSWORD 'rollup-dev-only';
CREATE DATABASE sangam_cell_a OWNER sangam_cell_a ENCODING 'UTF8';
CREATE DATABASE sangam_cell_b OWNER sangam_cell_b ENCODING 'UTF8';
CREATE DATABASE sangam_cp OWNER sangam_cp ENCODING 'UTF8';
CREATE DATABASE sangam_rollup OWNER sangam_rollup ENCODING 'UTF8';
CREATE USER sangam_rollup_ra WITH PASSWORD 'rollup-ra-dev-only';
CREATE DATABASE sangam_rollup_ra OWNER sangam_rollup_ra ENCODING 'UTF8';
