# 🍕 Zomato Clone - Food Delivery Backend API

A production-ready Spring Boot backend for a food delivery platform inspired by Zomato. Built with Spring Boot 3, Java 17, Spring Security with JWT, MySQL / PostgreSQL, Firebase Admin SDK (Auth, FCM Push Notifications, Cloud Storage), Stripe Payments, Bucket4j Rate Limiting, and WebSocket Live Order Updates.

---

## 🚀 Features

- **Authentication & Authorization**:
  - JWT-based authentication with role-based access control (`CUSTOMER`, `RESTAURANT_OWNER`, `DELIVERY_AGENT`, `ADMIN`).
  - Firebase Authentication integration for Phone OTP and social logins.
- **Restaurant & Menu Management**:
  - Full CRUD for restaurants and categories.
  - Geo-spatial search for nearby restaurants using Haversine formula.
  - Menu items management with dietary tags (Veg/Non-Veg) and stock availability.
- **Order Management & Realtime Updates**:
  - Order lifecycle flow (`PENDING` ➔ `CONFIRMED` ➔ `PREPARING` ➔ `OUT_FOR_DELIVERY` ➔ `DELIVERED`).
  - WebSocket (STOMP) real-time notifications for restaurant owners and customers.
  - In-memory concurrency locks to prevent race conditions during high-volume ordering.
- **Payments & Billing**:
  - Stripe Payment Intent integration with idempotency key protection.
  - Webhook handling for automated order status confirmation upon payment capture.
- **Promotions & Offers**:
  - Customizable promo codes with percentage discounts, max discount caps, and usage limits.
- **Firebase Services Integration**:
  - **FCM (Firebase Cloud Messaging)**: Automated push notifications sent to customer devices when order status changes.
  - **Cloud Storage**: Food and restaurant image uploads.
- **Performance & Security**:
  - Caffeine In-Memory Caching for low-latency menu and restaurant lookups.
  - Bucket4j Rate Limiting per IP address to safeguard APIs.
  - OpenAPI 3 / Swagger UI interactive documentation.

---

## 🛠️ Tech Stack

- **Java**: 17
- **Framework**: Spring Boot 3.2.0 (Spring Data JPA, Spring Security, Spring WebSocket, Spring Validation)
- **Database**: MySQL / PostgreSQL
- **Cache**: Caffeine Cache
- **Payments**: Stripe Java SDK
- **Cloud & Mobile Services**: Firebase Admin SDK (Auth, Storage, FCM)
- **API Documentation**: SpringDoc OpenAPI / Swagger UI
- **Build Tool**: Maven

---

## 📦 Getting Started

### 1. Prerequisites
- JDK 17 or higher
- MySQL or PostgreSQL
- Maven 3.8+

### 2. Configuration
Configure your database and credentials in `src/main/resources/application.yml` or via Environment Variables:

```yaml
DATABASE_URL: jdbc:mysql://localhost:3306/zomato_db
DATABASE_USERNAME: root
DATABASE_PASSWORD: yourpassword
```

For Firebase integration, place your `firebase-service-account.json` in `src/main/resources/`.

### 3. Build & Run
```bash
# Build the JAR
mvn clean package

# Run the Application
java -jar target/zomato-clone-1.0.0.jar
```

### 4. API Documentation
Once started, access Swagger UI at:
```
http://localhost:8081/swagger-ui.html
```
