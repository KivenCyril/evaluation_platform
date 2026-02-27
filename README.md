# hm-dianping

A Spring Boot practice project for local life services (shops, vouchers, seckill orders, user login, and search).

## Overview

This project is built with Spring Boot + MyBatis-Plus + Redis + MySQL, and includes:

- SMS-style login flow (code stored in Redis)
- shop query and cache strategies
- voucher and seckill order flow
- asynchronous seckill order processing with RabbitMQ
- Elasticsearch-based shop search
- JMeter benchmark assets

## Tech Stack

- Java 8
- Spring Boot 2.3.12.RELEASE
- MyBatis-Plus 3.4.3
- MySQL 5.7+ (driver: 5.1.47)
- Redis (with Redisson)
- RabbitMQ
- Elasticsearch Java Client 8.12.2
- Maven

## Project Structure

```text
src/main/java/com/hmdp
+- config        # Spring / Redis / ES / RabbitMQ / MVC config
+- controller    # REST APIs
+- dto           # request/response objects
+- entity        # database entities
+- mapper        # MyBatis mapper interfaces
+- service       # service interfaces
+- service/impl  # service implementations
+- utils         # cache, lock, id worker, interceptors, etc.

src/main/resources
+- application.yaml
+- db/hmdp.sql
+- mapper/*.xml
+- seckill.lua
+- unlock.lua
```

## Main Modules

### 1. User Module

- `POST /user/code`: send login code
- `POST /user/login`: login and get token
- `GET /user/me`: current user info
- `POST /user/sign`: daily sign-in
- `GET /user/sign/count`: continuous sign-in count

### 2. Shop Module

- `GET /shop/{id}`: query shop detail
- `PUT /shop`: update shop
- `GET /shop/of/type`: query shops by type
- `GET /shop/of/name`: query shops by name
- `GET /shop-type/list`: shop type list

### 3. Voucher & Seckill Module

- `POST /voucher`: add normal voucher
- `POST /voucher/seckill`: add seckill voucher
- `POST /voucher-order/seckill/{id}`: place seckill order

Seckill core path:

1. Run `seckill.lua` in Redis for stock + duplicate-order check.
2. If allowed, generate order id by `RedisIdWorker`.
3. Send order message to RabbitMQ.
4. Consume message and create order in DB.

### 4. Search Module

- `GET /shop/es/search`: search shops via Elasticsearch
- `POST /shop/es/rebuild`: rebuild index from MySQL
- `POST /shop/es/upsert/{id}`: upsert one shop document
- `DELETE /shop/es/delete/{id}`: delete one shop document

## Local Run

### 1. Prerequisites

- JDK 8
- Maven 3.6+
- MySQL
- Redis
- RabbitMQ
- Elasticsearch (HTTP port `9200`)

### 2. Init Database

Import SQL:

```text
src/main/resources/db/hmdp.sql
```

### 3. Configure Environment

Edit:

```text
src/main/resources/application.yaml
```

Key configs:

- MySQL: `spring.datasource.*`
- Redis: `spring.redis.*`
- RabbitMQ: `spring.rabbitmq.*`
- ES: `es.*`

### 4. Start Service

```bash
mvn clean spring-boot:run
```

Default port: `8081`

## Tests and Benchmark

- Unit/Integration tests: `src/test/java/com/hmdp/HmDianPingApplicationTests.java`
- JMeter script: `seckill_test.jmx`
- generated token file for pressure test: `tokens.txt`

## Current Notes

- `POST /user/logout` is currently not fully implemented.
- Some social modules (follow/comments) are still placeholders.
- `application.yaml` currently contains local development credentials, move them to env vars before production.

## License

For study and internal practice use.
