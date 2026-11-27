# Imagen del backend para Render (que no ejecuta Java de forma nativa).
# Los tests no se pasan aqui: los ejecuta el pipeline antes de desplegar.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Primero solo el pom, para reutilizar la capa de dependencias mientras no cambie
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/esibuy-backend-0.0.1-SNAPSHOT.jar app.jar
# Sin privilegios de administrador dentro del contenedor
USER 10001
# El plan gratuito de Render tiene 512 MB: se deja margen fuera del heap (Argon2id usa 64 MB por contrasena)
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "app.jar"]
