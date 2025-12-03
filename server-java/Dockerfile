# Multi-stage build for Spring Boot application with React frontend
FROM node:18-alpine AS frontend-build
WORKDIR /app/client

# Set npm configuration for better performance
ENV NPM_CONFIG_PROGRESS=false
ENV NPM_CONFIG_LOGLEVEL=warn

# Copy client package files
COPY client/package*.json ./
RUN npm install --legacy-peer-deps --prefer-offline --no-audit

# Copy client source and build
COPY client ./
RUN npm run build

# Spring Boot build stage
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Copy pom.xml and download dependencies
COPY server-java/pom.xml ./pom.xml
RUN mvn dependency:go-offline -B -DskipTests

# Copy source code
COPY server-java/src ./src

# Copy built frontend to Spring Boot static resources
COPY --from=frontend-build /app/client/build ./src/main/resources/static

# Build Spring Boot application
RUN mvn clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Copy built JAR (finalName이 설정되어 있어서 버전 없이 생성됨)
COPY --from=build /app/target/dinner-service.jar app.jar

# Create data directory for SQLite
RUN mkdir -p /app/data

# Expose port (Render will set PORT environment variable)
EXPOSE 5000

# Run application
ENTRYPOINT ["java", "-jar", "app.jar"]


