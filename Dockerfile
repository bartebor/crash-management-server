FROM maven:3.6.3-jdk-11 as build

ADD . /src

RUN cd /src && mvn package

FROM openjdk:11

EXPOSE 8080 12345

RUN mkdir /src

COPY --from=build /src/target/crash-management-server-DEV.jar /src/c-m-s.jar
COPY --from=build /src/application.properties /src/application.properties

WORKDIR /src

CMD [ "java", "-jar", "c-m-s.jar" ]
