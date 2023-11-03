drop table IF EXISTS gameobjects;

create TABLE gameobjects (
  id VARCHAR(36) PRIMARY KEY,
  creation_time BIGINT NOT NULL,
  ttl BIGINT NOT NULL,
  value BINARY NOT NULL
);

--CREATE TRIGGER TRIG_ME
--BEFORE INSERT ON gameobjects FOR EACH ROW
--CALL 'org.nomad.storage.triggers.TestTrigger'
--
--insert into gameobjects (id, creation_time, ttl, value) values
--  ('2', 1612014373, 100, STRINGTOUTF8('value-1')),
--  ('3', 1612014373, 100, STRINGTOUTF8('value-2')),
--  ('4', 1612014373, 100, STRINGTOUTF8('value-3'));