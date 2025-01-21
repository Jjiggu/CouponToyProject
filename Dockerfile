FROM openjdk:17
LABEL maintainer="Jihun"
ARG JAR_FILE=build/libs/CouponToyProject-0.0.1-SNAPSHOT.jar
ADD ${JAR_FILE} docker-springboot.jar
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-jar","/docker-springboot.jar"]
