drop table IF EXISTS gameobjects;

create TABLE gameobjects (
  id VARCHAR(36) PRIMARY KEY,
  creation_time BIGINT NOT NULL,
  last_modified BIGINT NOT NULL,
  ttl BIGINT NOT NULL,
  value BINARY NOT NULL
);