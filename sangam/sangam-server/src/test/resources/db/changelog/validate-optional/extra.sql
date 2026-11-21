--liquibase formatted sql

--changeset test:vo-03-real
CREATE TABLE VO_NEXT (id INT);

--changeset test:vo-04-guarded-halt
--comment: a precondition that fails with onFail:HALT is a real pending changeset, never skipped by validate
--preconditions onFail:HALT
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM VO_BASE WHERE id = 7
CREATE TABLE VO_HALT (id INT);