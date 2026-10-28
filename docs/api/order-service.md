# order-service API

컨트롤러 테스트가 만든 문서입니다. 직접 고치지 말고 `./gradlew :order-service:apiDocs` 로 다시 만듭니다.

## 주문 생성

주문을 저장하고 재고를 예약한 뒤 결제를 부른다. 같은 주문서 ID 로 다시 보내면 새 주문을 만들지 않고 기존 주문의 지금 상태를 돌려준다. 예시는 카드가 거절된 경우다. 주문은 PENDING 으로 남고 다른 카드로 다시 결제할 수 있다

**요청**

```http
POST /orders HTTP/1.1
Content-Type: application/json;charset=UTF-8
Content-Length: 389
Host: localhost:8080

{
  "checkoutId" : "d97f5275-4c4b-4321-bfab-e1399c0ff9c0",
  "memberId" : 1001,
  "recipientName" : "김새벽",
  "address" : "서울시 강남구 테헤란로 1",
  "regionCode" : "SEOUL",
  "paymentMethod" : "CARD",
  "items" : [ {
    "productId" : 700010,
    "quantity" : 2,
    "unitPrice" : 12000
  }, {
    "productId" : 700011,
    "quantity" : 1,
    "unitPrice" : 21000
  } ]
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `checkoutId` | `String` | 주문서 화면을 열 때 받은 ID. 응답을 못 받고 다시 보내는 요청을 한 주문으로 묶는다 |
| `memberId` | `Number` | 회원 번호 |
| `recipientName` | `String` | 받는 사람 |
| `address` | `String` | 배송지 |
| `regionCode` | `String` | 배송 권역. 출고 센터를 고르고 판매 제한 권역을 거르는 데 쓴다 |
| `paymentMethod` | `String` | 결제 수단 |
| `items[].productId` | `Number` | 상품 번호 |
| `items[].quantity` | `Number` | 수량. 1 이상 |
| `items[].unitPrice` | `Number` | 할인을 품목에 나눠 반영한 실제 결제 단가. 환불 금액을 이 값으로 센다 |

**응답**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 139

{
  "orderNo" : "20261028-J5S00F75EG",
  "status" : "PENDING",
  "outcome" : "PAYMENT_DECLINED",
  "failureCode" : "INSUFFICIENT_BALANCE"
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `orderNo` | `String` | 주문 번호. 품절 목록에서 걸러 저장하지 않았으면 없다 (없을 수 있음) |
| `status` | `String` | 주문 상태. [PENDING, PAID, FAILED, CANCELLED] (없을 수 있음) |
| `outcome` | `String` | 화면에 보여 줄 결과. [PAID, SOLD_OUT, FAILED, CANCELLED, PAYMENT_DECLINED, PAYMENT_DELAYED, PAYMENT_CHECKING, PAYMENT_IN_PROGRESS, RETRY_PAYMENT, RETRY_LATER] |
| `failureCode` | `String` | 실패 사유. 카드 거절, 결제 지연, 품절일 때만 있다 (없을 수 있음) |

## 다시 결제

카드가 거절된 뒤 다른 수단으로 다음 회차를 결제한다. 앞 결제의 결과를 아직 모르면 새 시도를 열지 않고 PAYMENT_IN_PROGRESS 로 답한다

**요청**

```http
POST /orders/20261028-D6T0W4R4S6/payments HTTP/1.1
Content-Type: application/json;charset=UTF-8
Content-Length: 36
Host: localhost:8080

{
  "paymentMethod" : "OTHER_CARD"
}
```

| 경로 변수 | 설명 |
|---|---|
| `orderNo` | 주문 번호 |

| 필드 | 타입 | 설명 |
|---|---|---|
| `paymentMethod` | `String` | 결제 수단 |

**응답**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 131

{
  "orderNo" : "20261028-D6T0W4R4S6",
  "status" : "PENDING",
  "outcome" : "PAYMENT_DECLINED",
  "failureCode" : "CARD_EXPIRED"
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `orderNo` | `String` | 주문 번호. 품절 목록에서 걸러 저장하지 않았으면 없다 (없을 수 있음) |
| `status` | `String` | 주문 상태. [PENDING, PAID, FAILED, CANCELLED] (없을 수 있음) |
| `outcome` | `String` | 화면에 보여 줄 결과. [PAID, SOLD_OUT, FAILED, CANCELLED, PAYMENT_DECLINED, PAYMENT_DELAYED, PAYMENT_CHECKING, PAYMENT_IN_PROGRESS, RETRY_PAYMENT, RETRY_LATER] |
| `failureCode` | `String` | 실패 사유. 카드 거절, 결제 지연, 품절일 때만 있다 (없을 수 있음) |

## 고객 취소

속한 마감 회차의 마감 전까지만 받는다. 남은 품목을 모두 취소하고 환불 요청과 CANCELLED 이벤트를 한 트랜잭션에 쓴다

**요청**

```http
POST /orders/20261028-XEFYQGMV0H/cancel HTTP/1.1
Host: localhost:8080

```

| 경로 변수 | 설명 |
|---|---|
| `orderNo` | 주문 번호 |

**응답**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 66

{
  "orderNo" : "20261028-XEFYQGMV0H",
  "outcome" : "CANCELLED"
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `orderNo` | `String` | 주문 번호 |
| `outcome` | `String` | 결과. [CANCELLED, ALREADY_CANCELLED, CUTOFF_PASSED, NOT_CANCELLABLE] |

## 주문 상세

사용자의 주문 조회와, 출고 배치가 이벤트로 받지 못한 주문을 채울 때 쓴다. 없으면 404

**요청**

```http
GET /orders/20261028-2Q7ZZF3ELQ HTTP/1.1
Host: localhost:8080

```

| 경로 변수 | 설명 |
|---|---|
| `orderNo` | 주문 번호 |

**응답**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 419

{
  "orderNo" : "20261028-2Q7ZZF3ELQ",
  "status" : "PAID",
  "memberId" : 1,
  "totalAmount" : 25000,
  "refundedAmount" : 0,
  "paidRound" : 1,
  "paidAt" : "2026-10-28T13:57:42.534",
  "recipientName" : "김새벽",
  "address" : "서울시 강남구 테헤란로 1",
  "regionCode" : "SEOUL",
  "items" : [ {
    "productId" : 700009,
    "quantity" : 1,
    "cancelledQuantity" : 0,
    "unitPrice" : 25000
  } ]
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `orderNo` | `String` | 주문 번호 |
| `status` | `String` | 주문 상태. [PENDING, PAID, FAILED, CANCELLED] |
| `memberId` | `Number` | 회원 번호 |
| `totalAmount` | `Number` | 결제 금액 |
| `refundedAmount` | `Number` | 완료된 환불의 누적액 |
| `paidRound` | `Number` | 주문을 PAID 로 만든 결제 회차. 결제 전에는 없다 (없을 수 있음) |
| `paidAt` | `String` | PAID 커밋 시각(주문 DB 시계). 마감 회차를 정한다. 결제 전에는 없다 (없을 수 있음) |
| `recipientName` | `String` | 받는 사람 |
| `address` | `String` | 배송지 |
| `regionCode` | `String` | 배송 권역 |
| `items[].productId` | `Number` | 상품 번호 |
| `items[].quantity` | `Number` | 주문 수량 |
| `items[].cancelledQuantity` | `Number` | 취소된 수량. 고객 취소, 출고 불가, 관리자 취소를 모두 더한다 |
| `items[].unitPrice` | `Number` | 실제 결제 단가 |

## 마감 회차의 결제 완료 목록

출고 배치의 대상 확정이 원천과 대조할 때 쓴다. 전 회차 마감 뒤부터 이번 마감까지 PAID 가 된 주문을 (paid_at, id) 순으로 나눠 준다

**요청**

```http
GET /orders/paid?cutoffAt=2031-03-02T23:00:00&size=1 HTTP/1.1
Host: localhost:8080

```

| 쿼리 변수 | 설명 |
|---|---|
| `cutoffAt` | 마감 시각. 예) 2031-03-02T23:00:00 |
| `size` | 한 페이지의 주문 수. 기본 1,000, 최대 5,000 (없을 수 있음) |
| `cursorPaidAt` | 앞 페이지 응답의 nextCursorPaidAt. 첫 페이지면 비운다 (없을 수 있음) |
| `cursorId` | 앞 페이지 응답의 nextCursorId. 첫 페이지면 비운다 (없을 수 있음) |

**응답**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 112

{
  "orderNos" : [ "20261028-H6TTJH73TD" ],
  "nextCursorPaidAt" : "2031-03-02T21:10:00",
  "nextCursorId" : 6
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `orderNos` | `Array` | 주문 번호 목록 |
| `nextCursorPaidAt` | `String` | 다음 페이지 커서. 마지막 페이지면 없다 (없을 수 있음) |
| `nextCursorId` | `Number` | 다음 페이지 커서. 마지막 페이지면 없다 (없을 수 있음) |

## 환불 다시 요청 (관리)

실패했거나 결제 서비스가 접수를 거절한 환불을 사람이 금액을 확인해 새 키로 다시 요청한다. 진행 중인 환불이면 409

**요청**

```http
POST /admin/refunds/refund-20261028-X3DUWU8J20-cancel/reissue HTTP/1.1
Content-Type: application/json;charset=UTF-8
Content-Length: 22
Host: localhost:8080

{
  "amount" : 24000
}
```

| 경로 변수 | 설명 |
|---|---|
| `refundKey` | 원래 환불 키 |

| 필드 | 타입 | 설명 |
|---|---|---|
| `amount` | `Number` | 바로잡은 금액. 비우면 원래 금액 (없을 수 있음) |

**응답**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 58

{
  "refundKey" : "refund-20261028-X3DUWU8J20-reissue-6"
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `refundKey` | `String` | 새 환불 키. refund-{주문번호}-reissue-{원래 요청 번호} |

## 여러 번 실패한 이벤트 (관리)

10번 넘게 보내지 못한 PENDING 이벤트. 이벤트를 내는 서비스(주문, 결제, 풀필먼트)마다 같은 주소로 있다. 격리된 이벤트는 /admin/outbox/quarantined 로 본다

**요청**

```http
GET /admin/outbox/stuck HTTP/1.1
Host: localhost:8080

```

**응답**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 784

[ {
  "seq" : 2,
  "eventId" : "0ac734fd-a7dc-4788-9803-6e9b5d4b87ac",
  "topic" : "order.events",
  "partitionKey" : "metrics-test",
  "attemptCount" : 11,
  "createdAt" : "2026-10-28T13:56:16.64",
  "nextAttemptAt" : "2026-10-28T14:56:56.64"
}, {
  "seq" : 6,
  "eventId" : "e976a70b-6587-41f5-a274-9816aaafbdb6",
  "topic" : "order.events",
  "partitionKey" : "admin-test-daceeb89-b45f-49af-ac79-ca4fa6ace156",
  "attemptCount" : 11,
  "createdAt" : "2026-10-28T13:57:41.147",
  "nextAttemptAt" : "2026-10-28T13:57:41.161"
}, {
  "seq" : 13,
  "eventId" : "45486c18-0f2f-4c28-96c6-e1cebb779d7c",
  "topic" : "order.events",
  "partitionKey" : "20261028-K3F9A2Q7XZ",
  "attemptCount" : 11,
  "createdAt" : "2026-10-28T13:42:42.469",
  "nextAttemptAt" : "2026-10-28T13:58:42.469"
} ]
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `[].seq` | `Number` | 아웃박스 순번. 같은 키 안의 발행 순서 |
| `[].eventId` | `String` | 이벤트 아이디. 사실에서 만든 이름 기반 UUID |
| `[].topic` | `String` | 토픽 |
| `[].partitionKey` | `String` | 파티션 키. 보통 주문 번호 |
| `[].attemptCount` | `Number` | 실패한 횟수 |
| `[].createdAt` | `String` | 기록 시각 |
| `[].nextAttemptAt` | `String` | 다음 시도 시각 |

## 이벤트 바로 다시 보내기 (관리)

원인을 고친 뒤 백오프를 기다리지 않고 다음 바퀴에 보낸다. PENDING 이 아니면 409

**요청**

```http
POST /admin/outbox/13/retry HTTP/1.1
Host: localhost:8080

```

| 경로 변수 | 설명 |
|---|---|
| `seq` | 아웃박스 순번 |

**응답**

```http
HTTP/1.1 204 No Content

```

## 이벤트 격리 (관리)

영구히 보낼 수 없는 이벤트를 QUARANTINED 로 둔다. 같은 키의 뒤 이벤트가 풀리므로 순서가 바뀌어도 되는지 판단한 뒤에만 쓴다. PENDING 이 아니면 409

**요청**

```http
POST /admin/outbox/13/quarantine HTTP/1.1
Host: localhost:8080

```

| 경로 변수 | 설명 |
|---|---|
| `seq` | 아웃박스 순번 |

**응답**

```http
HTTP/1.1 204 No Content

```
