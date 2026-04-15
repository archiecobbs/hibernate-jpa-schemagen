
create table Person (
    id bigint not null auto_increment,
    createTime datetime not null,
    updateTime datetime not null,
    firstName varchar(255),
    lastName varchar(255),
    middleName varchar(255),
    primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;

create table PersonAnnotation (
    person bigint not null,
    value mediumtext not null,
    name varchar(180) not null,
    primary key (person, name)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;

create index idx_Person_lastName
   on Person (lastName);

create index idx_PersonAnnotation_name
   on PersonAnnotation (name);

alter table if exists PersonAnnotation
   add constraint FK287AFF4A0F6ED11
   foreign key (person)
   references Person (id);
