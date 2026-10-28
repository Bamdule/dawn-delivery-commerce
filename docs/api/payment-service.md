# payment-service API

컨트롤러 테스트가 만든 문서입니다. 직접 고치지 말고 `./gradlew :payment-service:apiDocs` 로 다시 만듭니다.

## 승인

주문 서비스가 결제 회차 하나를 승인 요청한다. 회차를 연 뒤 대행사를 한 번만 3초까지 부른다. 타임아웃과 5xx 는 실패가 아니라 UNKNOWN 이고, 조회 배치가 같은 멱등키로 확정한다. 예시는 카드가 거절된 경우다

**요청**

```http
POST /payments HTTP/1.1
Content-Type: application/json;charset=UTF-8
Content-Length: 99
Host: localhost:8080

{
  "orderNo" : "20261028-PD24708",
  "paymentRound" : 1,
  "amount" : 45000,
  "method" : "CARD"
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `orderNo` | `String` | 주문 번호 |
| `paymentRound` | `Number` | 결제 회차. 주문 서비스가 정한다. 가장 최근 회차가 FAILED 나 EXPIRED 일 때만 새 회차가 열린다 |
| `amount` | `Number` | 결제 금액 |
| `method` | `String` | 결제 수단 |

**응답**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 92

{
  "outcome" : "DECLINED",
  "paymentRound" : 1,
  "failureCode" : "INSUFFICIENT_BALANCE"
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `outcome` | `String` | 결과. [APPROVED, DECLINED, UNKNOWN, IN_PROGRESS, ALREADY_PAID, SEALED, STALE] |
| `paymentRound` | `Number` | 결제 회차 |
| `failureCode` | `String` | 실패 사유. 대행사의 거절 코드, 부르지 않았으면 NOT_CALLED. 거절일 때만 있다 (없을 수 있음) |

## 회차 봉인

아직 열리지 않은 회차에 EXPIRED 행을 넣어 늦게 온 승인 요청이 열지 못하게 한다. 이미 열렸으면 봉인하지 않고 그 회차의 상태를 돌려준다. 재고 만료 배치와 주문 정리 배치만 부른다

**요청**

```http
POST /payments/20261028-PD42458/rounds/2/seal HTTP/1.1
Host: localhost:8080

```

| 경로 변수 | 설명 |
|---|---|
| `orderNo` | 주문 번호 |
| `paymentRound` | 봉인할 결제 회차 |

**응답**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 26

{
  "outcome" : "SEALED"
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `outcome` | `String` | SEALED 면 봉인했다. 아니면 이미 있던 회차의 상태다. [SEALED, REQUESTED, APPROVED, FAILED, UNKNOWN, EXPIRED] |

## 회차 조회

한 주문의 결제 회차 목록. 다시 결제할 때 주문 서비스가 앞 회차가 끝났는지 확인한다

**요청**

```http
GET /payments/20261028-PD59458 HTTP/1.1
Host: localhost:8080

```

| 경로 변수 | 설명 |
|---|---|
| `orderNo` | 주문 번호 |

**응답**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 175

{
  "rounds" : [ {
    "paymentRound" : 1,
    "status" : "FAILED",
    "amount" : 45000
  }, {
    "paymentRound" : 2,
    "status" : "APPROVED",
    "amount" : 45000
  } ]
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `rounds[].paymentRound` | `Number` | 결제 회차 |
| `rounds[].status` | `String` | 회차 상태. [REQUESTED, APPROVED, FAILED, UNKNOWN, EXPIRED] |
| `rounds[].amount` | `Number` | 금액. 봉인된 회차는 0 |

## 미결 회차 확정 (관리)

10분 넘게 결과를 모른 UNKNOWN 회차를 사람이 대행사에 확인한 뒤 확정한다. 결과 이벤트가 나가 주문에 반영된다. UNKNOWN 이 아니면 409

**요청**

```http
POST /admin/payments/20261028-PD11750/rounds/1/resolve HTTP/1.1
Content-Type: application/json;charset=UTF-8
Content-Length: 27
Host: localhost:8080

{
  "status" : "APPROVED"
}
```

| 경로 변수 | 설명 |
|---|---|
| `orderNo` | 주문 번호 |
| `paymentRound` | 결제 회차 |

| 필드 | 타입 | 설명 |
|---|---|---|
| `status` | `String` | 확정할 상태. APPROVED 또는 FAILED |

**응답**

```http
HTTP/1.1 204 No Content

```

## 환불 접수

주문 서비스의 환불 배치가 같은 키로 접수될 때까지 보낸다. 같은 키가 있으면 상한 검사 전에 그 환불의 지금 상태를 돌려준다. 회차의 환불 합이 승인 금액을 넘으면 거절한다

**요청**

```http
POST /refunds HTTP/1.1
Content-Type: application/json;charset=UTF-8
Content-Length: 128
Host: localhost:8080

{
  "refundKey" : "refund-20261028-PD33000-cancel",
  "orderNo" : "20261028-PD33000",
  "paymentRound" : 1,
  "amount" : 45000
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `refundKey` | `String` | 주문 서비스가 사유에서 만든 환불 키. 다시 계산해도 같다 |
| `orderNo` | `String` | 주문 번호 |
| `paymentRound` | `Number` | 환불할 결제 회차 |
| `amount` | `Number` | 환불 금액 |

**응답**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 54

{
  "outcome" : "ACCEPTED",
  "status" : "REQUESTED"
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `outcome` | `String` | ACCEPTED 또는 REJECTED |
| `status` | `String` | 접수됐으면 결제 쪽 환불의 지금 상태(REQUESTED, COMPLETED, FAILED, UNKNOWN), 거절이면 사유 |

## 환불 조회

1시간 넘게 결과가 오지 않은 환불을 주문 서비스가 물을 때 쓴다. 없으면 404

**요청**

```http
GET /refunds/refund-20261028-PD65625-cancel HTTP/1.1
Host: localhost:8080

```

| 경로 변수 | 설명 |
|---|---|
| `refundKey` | 환불 키 |

**응답**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 95

{
  "refundKey" : "refund-20261028-PD65625-cancel",
  "status" : "FAILED",
  "amount" : 45000
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `refundKey` | `String` | 환불 키 |
| `status` | `String` | REQUESTED, COMPLETED, FAILED, UNKNOWN |
| `amount` | `Number` | 환불 금액 |

## 환불 확정 (관리)

대행사가 거절했거나 결과를 모르는 환불을 사람이 확인해 확정한다. 대행사에서 직접 처리했으면 COMPLETED 다. 결과 이벤트가 나가 주문의 누적액에 반영된다

**요청**

```http
POST /admin/refunds/refund-20261028-PD65625-cancel/resolve HTTP/1.1
Content-Type: application/json;charset=UTF-8
Content-Length: 28
Host: localhost:8080

{
  "status" : "COMPLETED"
}
```

| 경로 변수 | 설명 |
|---|---|
| `refundKey` | 환불 키 |

| 필드 | 타입 | 설명 |
|---|---|---|
| `status` | `String` | COMPLETED 또는 FAILED. FAILED 는 UNKNOWN 에서만 |

**응답**

```http
HTTP/1.1 204 No Content

```
