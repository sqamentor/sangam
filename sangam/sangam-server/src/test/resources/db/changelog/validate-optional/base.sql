--liquibase formatted sql

--changeset test:vo-01-base
CREATE TABLE VO_BASE (id INT);

--changeset test:vo-02-optional
--comment: shaped like 0044-08-vector-optional (decision 170): applied only once its prerequisite exists
--preconditions onFail:CONTINUE onError:HALT
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM VO_BASE WHERE id = 42
ALTER TABLE VO_BASE ADD COLUMN extra INT;
